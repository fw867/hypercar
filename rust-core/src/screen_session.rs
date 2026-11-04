//! Optional screen-only recovery. Control, timing, Wi-Fi iAP2 and audio remain alive.
use std::sync::{Arc, atomic::{AtomicBool,Ordering}};
use std::time::Duration;
use parking_lot::Mutex;
use tokio::sync::{mpsc,oneshot};
use crate::{airplay::{self,AirplayParams,SocketProvider},bplist::{self,Value},rtsp::RtspChannel,screen::{ScreenSender,ScreenCipher}};

pub enum ScreenCommand { Install(ScreenSender), Pause(oneshot::Sender<()>) }

pub struct ScreenSession {
    pub control: Arc<RtspChannel>,
    pub factory: Arc<dyn SocketProvider>,
    pub params: AirplayParams,
    pub shared: Option<[u8;32]>,
    pub uuid: String,
    pub keep_stats: bool,
    pub display: (u32,u32),
    pub owned: Arc<AtomicBool>,
    pub commands: mpsc::Sender<ScreenCommand>,
    pub initialized: AtomicBool,
}

impl ScreenSession {
    pub fn needs_rebuild(&self) -> bool {
        self.initialized.load(Ordering::Acquire) &&
            matches!(self.params.pipeline.lock().video_stream.as_str(), "PAUSED" | "FAILED")
    }
    pub async fn open(&self) -> Result<ScreenSender,String> {
        let conn_id=airplay::random_positive_i64();
        let body=airplay::build_screen_setup(conn_id,&self.uuid);
        let response=self.control.request("SETUP","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(8)).await?;
        if response.status()!=200 { return Err(format!("屏幕 SETUP 被拒 {}",response.first_line)); }
        let root=bplist::parse(&response.body).map_err(|e|format!("屏幕 SETUP 解析失败 {e}"))?;
        let port=root.get("streams").and_then(Value::as_array).and_then(|s|s.first())
            .map(|s|airplay::port_of(s.get("dataPort"))).unwrap_or(0);
        if port==0 { return Err("屏幕 SETUP 没有 dataPort".into()); }
        let cipher=match &self.shared {
            Some(shared)=>ScreenCipher::ChaCha(airplay::stream_key(shared,conn_id as u64)),
            None=>{let (key,iv)=airplay::aes_stream_keys_zero(conn_id as u64);ScreenCipher::AesCtr(crate::crypto::Aes128CtrStream::new(&key,&iv))},
        };
        let socket=self.factory.open_tcp(&self.params.car_ip,port,5000).ok_or_else(||format!("屏幕端口 {port} 连接失败"))?;
        let (_,write)=socket.into_split();
        let (w,h)=*self.params.encoder_size.lock();
        let mut sender=ScreenSender::new(write,cipher,w,h,self.keep_stats);
        sender.attach_diagnostics(self.params.pipeline.clone(),conn_id as u64);
        if self.params.carwith_stretch { sender.set_display_target(self.display.0,self.display.1); }
        sender.set_ownership(self.owned.clone());
        crate::emit_log(&format!("AIRPLAY: 主屏 TCP 就绪 dataPort={port} connId={conn_id} source={w}x{h}; new cipher/config state"));
        Ok(sender)
    }

    pub async fn install(&self,sender:ScreenSender) -> Result<(),String> {
        self.commands.send(ScreenCommand::Install(sender)).await.map_err(|_|"视频泵已退出".to_string())?;
        self.initialized.store(true,Ordering::Release);
        self.params.pipeline.lock().video_stream="READY".into();
        Ok(())
    }

    pub async fn rebuild(&self,modes:&Arc<Mutex<crate::modes::Modes>>,revision:u64) -> Result<bool,String> {
        let allowed=||modes.lock().can_restore_screen(revision);
        if !self.initialized.load(Ordering::Acquire) || !allowed() { return Ok(false); }
        self.params.pipeline.lock().video_stream="REBUILDING".into();
        crate::emit_log("SCREEN-RECOVERY: pause old sender; preserve audio/control/iAP2");
        let (tx,rx)=oneshot::channel();
        tokio::time::timeout(Duration::from_secs(3),async {
            self.commands.send(ScreenCommand::Pause(tx)).await.map_err(|_|"video pump closed")?;
            rx.await.map_err(|_|"pause not acknowledged")
        }).await.map_err(|_|"暂停旧视频发送器超时".to_string())?.map_err(str::to_string)?;
        // The acknowledgement is sent only after the old sender/socket has been dropped.
        if !allowed() { self.params.pipeline.lock().video_stream="PAUSED".into(); return Ok(false); }
        let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
            ("type",Value::int(110)),("uuid",Value::str(&self.uuid)),
        ])]))]));
        let response=self.control.request("TEARDOWN","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(3)).await?;
        crate::emit_log(&format!("SCREEN-RECOVERY: screen-only TEARDOWN status={}",response.status()));
        if response.status()!=200 { return Err(format!("屏幕 TEARDOWN 被拒 {}",response.first_line)); }
        if !allowed() { self.params.pipeline.lock().video_stream="PAUSED".into(); return Ok(false); }
        let sender=self.open().await?;
        if !allowed() { self.params.pipeline.lock().video_stream="PAUSED".into(); return Ok(false); }
        airplay::record_session(&self.control).await?;
        if !allowed() { self.params.pipeline.lock().video_stream="PAUSED".into(); return Ok(false); }
        self.install(sender).await?;
        self.params.pipeline.lock().screen_rebuilds+=1;
        crate::emit_force_keyframe();
        crate::emit_log("SCREEN-RECOVERY: fresh sender installed; await new SPS/PPS/IDR; audio/control retained");
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{net::{TcpListener,TcpStream,UdpSocket},io::{AsyncReadExt,AsyncWriteExt}};
    struct Factory;
    impl SocketProvider for Factory {
        fn open_tcp(&self,host:&str,port:u16,_timeout:u32)->Option<TcpStream> {
            let socket=std::net::TcpStream::connect((host,port)).ok()?;
            socket.set_nonblocking(true).ok()?; TcpStream::from_std(socket).ok()
        }
        fn open_udp(&self,_host:&str,_port:u16)->Option<UdpSocket> { None }
    }
    async fn read(car:&mut TcpStream)->crate::rtsp::RtspMessage {
        let mut bytes=Vec::new();let mut chunk=[0;4096];
        loop {
            let n=tokio::time::timeout(Duration::from_secs(2),car.read(&mut chunk)).await.unwrap().unwrap();
            assert!(n>0);bytes.extend_from_slice(&chunk[..n]);
            if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut bytes).pop() {return message;}
        }
    }
    async fn reply(car:&mut TcpStream,msg:&crate::rtsp::RtspMessage,body:&[u8]) {
        car.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",msg.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();
        car.write_all(body).await.unwrap();
    }
    #[tokio::test]
    async fn screen_only_rebuild_closes_old_socket_and_new_stream_uses_fresh_crypto() {
        for withdraw in [false,true] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let phone=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut car,_)=listener.accept().await.unwrap();
            let (r,w)=phone.into_split();let (control,_)=RtspChannel::spawn(r,w,None);
            let video=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let old=TcpStream::connect(video.local_addr().unwrap()).await.unwrap();
            let (mut old_peer,_)=video.accept().await.unwrap();let (_,old_w)=old.into_split();
            let old_sender=ScreenSender::new(old_w,ScreenCipher::ChaCha([7;32]),1920,720,false);
            let (commands,mut rx)=mpsc::channel(2);
            let pump=tokio::spawn(async move {
                let mut old=Some(old_sender);
                match rx.recv().await.unwrap() {
                    ScreenCommand::Pause(done)=>{drop(old.take());done.send(()).unwrap();},
                    _=>panic!("must pause before replacement"),
                }
                if let Some(ScreenCommand::Install(mut sender))=rx.recv().await {
                    let au=[0,0,0,1,0x67,0x42,0,0x1f,0,0,0,1,0x68,1,0,0,0,1,0x65,2];
                    sender.send_video_annexb(&au,2000).await.unwrap();
                }
            });
            let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
            pipeline.lock().audio_ready=true;
            let params=AirplayParams {
                car_ip:"127.0.0.1".into(),airplay_port:7000,device_id:"test".into(),identity_path:"test".into(),
                receiver_id:None,receiver_previously_connected:true,enable_audio:true,keep_alive_stats:false,
                bmw_box_bootstrap:true,desktop_receiver:false,width:1920,height:720,touch_display:None,touch_rect:None,
                encoder_size:Arc::new(Mutex::new((1920,720))),touch_size:Arc::new(Mutex::new((1920,720))),
                carwith_display:None,carwith_stretch:false,ui_retake_recovery:false,screen_stream_recovery:true,pipeline:pipeline.clone(),
            };
            let session=Arc::new(ScreenSession {control:control.clone(),factory:Arc::new(Factory),params,
                shared:Some([9;32]),uuid:"screen-test".into(),keep_stats:false,display:(1920,720),
                owned:Arc::new(AtomicBool::new(true)),commands,initialized:AtomicBool::new(true)});
            let modes=Arc::new(Mutex::new(crate::modes::Modes::initial()));
            modes.lock().request_ui();let revision=modes.lock().take_ui_retake().unwrap();
            let session_task=session.clone();let modes_task=modes.clone();
            let rebuild=tokio::spawn(async move {session_task.rebuild(&modes_task,revision).await});
            let teardown=read(&mut car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN"));
            let body=bplist::parse(&teardown.body).unwrap();let streams=body.get("streams").unwrap().as_array().unwrap();
            assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").unwrap().as_int(),Some(110));
            let mut eof=[0;1];assert_eq!(old_peer.read(&mut eof).await.unwrap(),0);
            if withdraw {
                assert!(modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                    ("resourceID",Value::int(1)),("transferType",Value::int(3)),("transferPriority",Value::int(500)),("unborrowConstraint",Value::int(1000)),
                ])]))])));
            }
            reply(&mut car,&teardown,&[]).await;
            if !withdraw {
                let setup=read(&mut car).await;assert!(setup.first_line.starts_with("SETUP"));
                let request=bplist::parse(&setup.body).unwrap();
                let connection=request.get("streams").unwrap().as_array().unwrap()[0].get("streamConnectionID").unwrap().as_int().unwrap();
                let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("dataPort",Value::int(video.local_addr().unwrap().port() as i64))])]))]));
                reply(&mut car,&setup,&body).await;
                let (mut peer,_)=video.accept().await.unwrap();
                let record=read(&mut car).await;assert!(record.first_line.starts_with("RECORD"));reply(&mut car,&record,&[]).await;
                assert_eq!(rebuild.await.unwrap().unwrap(),true);
                let mut header=[0;128];peer.read_exact(&mut header).await.unwrap();assert_eq!(header[4],1);
                let mut config=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut config).await.unwrap();
                peer.read_exact(&mut header).await.unwrap();assert_eq!(header[4],0);
                let mut encrypted=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut encrypted).await.unwrap();
                let plain=crate::crypto::chacha_open(&airplay::stream_key(&[9;32],connection as u64),&crate::crypto::nonce64(0),&encrypted,&header).unwrap();
                assert_eq!(plain,vec![0,0,0,2,0x65,2]);
                assert_eq!(pipeline.lock().screen_rebuilds,1);
                pump.await.unwrap();
            } else {
                assert!(!rebuild.await.unwrap().unwrap());assert_eq!(pipeline.lock().video_stream,"PAUSED");pump.abort();
            }
            assert!(pipeline.lock().audio_ready);
            // The same control channel is still usable, with no re-pairing or audio teardown.
            control.post_fire_and_forget("/feedback",&[],"application/octet-stream").await.unwrap();
            assert!(read(&mut car).await.first_line.contains("/feedback"));
        }
    }
}
