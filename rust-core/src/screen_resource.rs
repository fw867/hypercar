//! Experimental video-stream lifecycle driven by the latest screen resource grant.
//! One serialized owner reconciles initial setup, live mode changes and UI retries.
use std::sync::{Arc, atomic::Ordering};
use std::time::Duration;
use parking_lot::Mutex;
use crate::{bplist::{self, Value}, modes::Modes, screen_session::{ScreenSession, ScreenCommand}};

#[derive(Default)]
pub(crate) struct ResourceStream {
    negotiated: bool,
    ever_installed: bool,
    revision: Option<u64>,
}

impl ScreenSession {
    /// MBUX opens the screen TCP endpoint before RECORD, but never exposes it to the pump
    /// until RECORD succeeds. Event callbacks may arbitrate while this serialized bootstrap runs.
    pub async fn bootstrap_before_record(&self, modes: &Arc<Mutex<Modes>>) -> Result<(), String> {
        let mut state = self.resource_state.lock().await;
        let (revision, owned, declaration) = {
            let m = modes.lock();
            (m.screen_generation(), m.screen_owned(), m.declaration())
        };
        let response = self.control.request("POST", "/command", &declaration,
            "application/x-apple-binary-plist", &[], Duration::from_secs(5)).await?;
        let business = bplist::parse(&response.body).ok().and_then(|v| v.get("status").and_then(Value::as_int));
        if response.status() != 200 || business.is_some_and(|s| s != 0) {
            return Err("MBUX 初始资源声明被拒".into());
        }
        crate::pipeline::activation(&self.params.pipeline, "RESOURCES_ACCEPTED");
        crate::emit_log("MBUX: resource declaration accepted; preparing screen before RECORD");
        let sender = if owned && modes.lock().can_restore_screen(revision) {
            state.negotiated = true;
            match self.open().await {
                Ok(sender) => Some(sender),
                Err(error) => {
                    let _ = self.release_resource_stream(&mut state).await;
                    return Err(error);
                }
            }
        } else { None };
        if let Err(error) = crate::airplay::record_session(&self.control).await {
            drop(sender);
            if state.negotiated { let _ = self.release_resource_stream(&mut state).await; }
            return Err(error);
        }
        crate::pipeline::activation(&self.params.pipeline, "RECORD");
        self.activated.store(true, Ordering::Release);
        crate::emit_log("MBUX: RECORD accepted; video installation now permitted");
        if let Some(sender) = sender {
            let (done, installed) = tokio::sync::oneshot::channel();
            // The pump verifies both the latest grant and its revision after the network awaits.
            self.commands.send(ScreenCommand::InstallIfOwned {
                sender, modes: modes.clone(), revision, reply: done,
            }).await.map_err(|_| "视频泵已退出".to_string())?;
            if installed.await.map_err(|_| "视频安装未确认".to_string())? {
                state.revision = Some(revision);
                state.ever_installed = true;
                crate::pipeline::activation(&self.params.pipeline, "VIDEO_CHANNEL_READY");
            } else {
                self.release_resource_stream(&mut state).await?;
            }
        }
        // A concurrent resource event can now reconcile; never keep the bootstrap lock across it.
        Ok(())
    }

    /// Query after event workers are stopped: graceful exit includes only negotiated streams.
    pub async fn resource_teardown(&self, audio: bool) -> Vec<u8> {
        let state = self.resource_state.lock().await;
        let mut streams = Vec::new();
        if state.negotiated { streams.push(Value::dict(vec![("type",Value::int(110)),("uuid",Value::str(&self.uuid))])); }
        if audio { streams.push(Value::dict(vec![("type",Value::int(100))])); }
        bplist::encode(&Value::dict(vec![("streams",Value::array(streams))]))
    }

    async fn release_resource_stream(&self, state: &mut ResourceStream) -> Result<(), String> {
        let (done, released) = tokio::sync::oneshot::channel();
        tokio::time::timeout(Duration::from_secs(3), async {
            self.commands.send(ScreenCommand::Pause(done)).await.map_err(|_| "video pump closed")?;
            released.await.map_err(|_| "video pause not acknowledged")
        }).await.map_err(|_| "视频释放超时".to_string())?.map_err(str::to_string)?;
        // Pump acknowledgement follows socket drop. Never retain stale TCP samples as live facts.
        self.params.pipeline.lock().video_tcp = None;
        state.revision = None;
        if state.negotiated {
            let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
                ("type", Value::int(110)), ("uuid", Value::str(&self.uuid)),
            ])]))]));
            let response = self.control.request("TEARDOWN", "/", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await?;
            crate::emit_log(&format!("SCREEN-LIFECYCLE: screen-only TEARDOWN status={}; audio/control retained", response.status()));
            if response.status() != 200 { return Err(format!("屏幕 TEARDOWN 被拒 {}", response.first_line)); }
            state.negotiated = false;
        }
        Ok(())
    }

    pub async fn reconcile_resource(&self, modes: &Arc<Mutex<Modes>>) -> Result<(), String> {
        // Event dispatch is already running before the initial global RECORD. It may update
        // arbitration, but no lifecycle SETUP can precede successful session activation.
        if !self.activated.load(Ordering::Acquire) { return Ok(()); }
        let mut state = self.resource_state.lock().await;
        loop {
            let (revision, owned, declaration, note) = {
                let m = modes.lock();
                (m.screen_generation(), m.screen_owned(), m.declaration(), m.diagnostics())
            };
            crate::emit_log(&format!("SCREEN-LIFECYCLE: reconcile {note} negotiated={} installed_revision={:?}", state.negotiated, state.revision));
            // This also publishes audio/app updates while video is awaiting screen ownership.
            let response = self.control.request("POST", "/command", &declaration, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await?;
            let business = bplist::parse(&response.body).ok().and_then(|v| v.get("status").and_then(Value::as_int));
            crate::emit_log(&format!("SCREEN-LIFECYCLE: modes feedback revision={revision} RTSP_status={} business_status={business:?}; receiver display unconfirmed", response.status()));
            if response.status() != 200 || business.is_some_and(|s| s != 0) {
                return Err("画面资源声明未被接受".into());
            }
            if modes.lock().screen_generation() != revision { continue; }
            let failed = self.params.pipeline.lock().video_stream == "FAILED";
            if state.negotiated && (!owned || state.revision != Some(revision) || failed) {
                self.release_resource_stream(&mut state).await?;
                // A grant arriving during TEARDOWN needs its own current declaration before SETUP.
                if modes.lock().screen_generation() != revision { continue; }
            }
            if !owned {
                self.params.pipeline.lock().video_stream = "WAIT_OWNERSHIP".into();
                crate::emit_log("SCREEN-LIFECYCLE: no screen grant; no video socket; audio/control remain active");
                return Ok(());
            }
            if state.negotiated { return Ok(()); }
            if !modes.lock().can_restore_screen(revision) { continue; }
            self.params.pipeline.lock().video_stream = "REBUILDING".into();
            crate::emit_log(&format!("SCREEN-LIFECYCLE: SETUP after modes feedback revision={revision}"));
            // Conservatively clean up even if SETUP succeeded but the local data socket failed.
            state.negotiated = true;
            let sender = match self.open().await {
                Ok(sender) => sender,
                Err(error) => {
                    if let Err(cleanup) = self.release_resource_stream(&mut state).await {
                        crate::emit_log(&format!("SCREEN-LIFECYCLE: partial SETUP cleanup failed: {cleanup}"));
                    }
                    return Err(error);
                }
            };
            if !modes.lock().can_restore_screen(revision) {
                drop(sender);
                self.release_resource_stream(&mut state).await?;
                continue;
            }
            // Validate again in the actual video pump: a queued install must not resurrect a
            // stream from a superseded grant, even if the car has since returned the screen.
            let (done, installed) = tokio::sync::oneshot::channel();
            self.commands.send(ScreenCommand::InstallIfOwned {
                sender, modes: modes.clone(), revision, reply: done,
            }).await.map_err(|_| "视频泵已退出".to_string())?;
            let accepted = installed.await.map_err(|_| "视频安装未确认".to_string())?;
            if !accepted {
                self.release_resource_stream(&mut state).await?;
                continue;
            }
            state.revision = Some(revision);
            if state.ever_installed { self.params.pipeline.lock().screen_rebuilds += 1; }
            state.ever_installed = true;
            crate::emit_log(&format!("SCREEN-LIFECYCLE: stream installed revision={revision}; fresh config/IDR requested; global RECORD retained"));
            // A change concurrent with installation must be reconciled even on the initial call.
            if modes.lock().screen_generation() != revision { continue; }
            return Ok(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;
    use tokio::{net::{TcpListener,TcpStream,UdpSocket},io::{AsyncReadExt,AsyncWriteExt},sync::{mpsc,Notify}};
    use crate::{airplay::{AirplayParams,SocketProvider},engine::{SourceEvent,PumpStat,video_pump},rtsp::{RtspChannel,RtspMessage}};

    struct Factory;
    impl SocketProvider for Factory {
        fn open_tcp(&self,host:&str,port:u16,_timeout:u32)->Option<TcpStream> {
            let socket=std::net::TcpStream::connect((host,port)).ok()?;
            socket.set_nonblocking(true).ok()?;TcpStream::from_std(socket).ok()
        }
        fn open_udp(&self,_host:&str,_port:u16)->Option<UdpSocket> { None }
    }
    async fn read(car:&mut TcpStream)->RtspMessage {
        tokio::time::timeout(Duration::from_secs(2),async {
            let mut buffer=Vec::new();let mut chunk=[0;4096];
            loop {
                let n=car.read(&mut chunk).await.unwrap();assert!(n>0);
                buffer.extend_from_slice(&chunk[..n]);
                if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut buffer).pop() {return message;}
            }
        }).await.unwrap()
    }
    async fn reply(car:&mut TcpStream,message:&RtspMessage,status:u16,body:&[u8]) {
        car.write_all(format!("RTSP/1.0 {status} Reply\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",message.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();
        car.write_all(body).await.unwrap();
    }
    fn borrow(modes:&mut Modes,unborrow:i64) {
        assert!(modes.change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
            ("resourceID",Value::int(1)),("transferType",Value::int(3)),
            ("transferPriority",Value::int(500)),("unborrowConstraint",Value::int(unborrow)),
        ])]))])));
    }
    struct Fixture {
        session:Arc<ScreenSession>,modes:Arc<Mutex<Modes>>,car:TcpStream,video:TcpListener,
        source:mpsc::UnboundedSender<SourceEvent>,pump:tokio::task::JoinHandle<()>,
    }
    impl Drop for Fixture {fn drop(&mut self) {self.pump.abort();}}
    impl Fixture {
        async fn new(modes:Modes)->Self { Self::with_bootstrap(modes, false).await }
        async fn with_bootstrap(modes:Modes, mbux:bool)->Self {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (car,_)=listener.accept().await.unwrap();let (r,w)=socket.into_split();
            let (control,_)=RtspChannel::spawn(r,w,None);
            let video=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
            pipeline.lock().audio_ready=true;
            let params=AirplayParams {
                car_ip:"127.0.0.1".into(),airplay_port:7000,device_id:"test".into(),device_name:"Test phone".into(),identity_path:"unused".into(),
                receiver_id:None,receiver_previously_connected:true,enable_audio:true,keep_alive_stats:false,
                bmw_box_bootstrap:!mbux,mbux_screen_bootstrap:mbux,desktop_receiver:false,width:1920,height:720,touch_display:None,touch_rect:None,
                encoder_size:Arc::new(Mutex::new((1920,720))),touch_size:Arc::new(Mutex::new((1920,720))),
                carwith_display:None,carwith_stretch:false,screen_resource_lifecycle:true,pipeline:pipeline.clone(),
            };
            let (commands,rx)=mpsc::channel(2);let (source,video_rx)=mpsc::unbounded_channel();
            let pump=tokio::spawn(video_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(PumpStat::default())),true,pipeline,rx,video_rx));
            let owned=Arc::new(AtomicBool::new(modes.screen_owned()));
            let session=Arc::new(ScreenSession {control,factory:Arc::new(Factory),params,shared:Some([9;32]),uuid:"screen-test".into(),
                keep_stats:false,display:(1920,720),owned,commands,activated:AtomicBool::new(!mbux),resource_state:tokio::sync::Mutex::new(Default::default())});
            Self {session,modes:Arc::new(Mutex::new(modes)),car,video,source,pump}
        }
        fn reconcile(&self)->tokio::task::JoinHandle<Result<(),String>> {
            let session=self.session.clone();let modes=self.modes.clone();
            tokio::spawn(async move {session.reconcile_resource(&modes).await})
        }
        fn update(&self,f:impl FnOnce(&mut Modes)) {
            let mut modes=self.modes.lock();f(&mut modes);
            self.session.owned.store(modes.screen_owned(),Ordering::Release);
        }
        async fn modes_reply(&mut self,owner:i64) {
            let request=read(&mut self.car).await;assert!(request.first_line.starts_with("POST /command "));
            let body=bplist::parse(&request.body).unwrap();assert_eq!(body.get("type").and_then(Value::as_str),Some("modesChanged"));
            let resources=body.get("params").unwrap().get("resources").unwrap().as_array().unwrap();
            assert_eq!(resources[0].get("entity").and_then(Value::as_int),Some(owner));
            assert_eq!(resources[1].get("entity").and_then(Value::as_int),Some(1));
            reply(&mut self.car,&request,200,&[]).await;
        }
        async fn setup_reply(&mut self,request:&RtspMessage)->(TcpStream,u64) {
            assert!(request.first_line.starts_with("SETUP "));
            let body=bplist::parse(&request.body).unwrap();let streams=body.get("streams").unwrap().as_array().unwrap();
            assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").and_then(Value::as_int),Some(110));
            let id=streams[0].get("streamConnectionID").and_then(Value::as_int).unwrap() as u64;
            let response=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
                ("dataPort",Value::int(self.video.local_addr().unwrap().port() as i64)),
            ])]))]));
            reply(&mut self.car,request,200,&response).await;
            let (peer,_)=tokio::time::timeout(Duration::from_secs(2),self.video.accept()).await.unwrap().unwrap();
            (peer,id)
        }
        async fn teardown_reply(&mut self) {
            let request=read(&mut self.car).await;assert!(request.first_line.starts_with("TEARDOWN "));
            let body=bplist::parse(&request.body).unwrap();let streams=body.get("streams").unwrap().as_array().unwrap();
            assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").and_then(Value::as_int),Some(110));
            reply(&mut self.car,&request,200,&[]).await;
        }
        async fn video_frame(&self,peer:&mut TcpStream,id:u64) {
            self.source.send(SourceEvent::VideoFrame {pts_us:2000,data:vec![0,0,0,1,0x67,0x42,0,0x1f,0,0,0,1,0x68,1,0,0,0,1,0x65,2]}).unwrap();
            tokio::time::timeout(Duration::from_secs(2),async {
                let mut config=false;
                loop {
                    let mut header=[0;128];peer.read_exact(&mut header).await.unwrap();
                    let mut body=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut body).await.unwrap();
                    match header[4] {
                        1=>{config=true;assert_eq!(header[6],4);},
                        0=>{assert!(config);let plain=crate::crypto::chacha_open(&crate::airplay::stream_key(&[9;32],id),&crate::crypto::nonce64(0),&body,&header).unwrap();assert_eq!(plain,vec![0,0,0,2,0x65,2]);break;},
                        _=>{},
                    }
                }
            }).await.unwrap();
        }
    }
    #[tokio::test]
    async fn mbux_screen_socket_precedes_record_but_frames_wait_for_success() {
        for (record_status, revoke) in [(200, false), (403, false), (200, true)] {
            let modes=Modes::from_info_with_audio_policy(&Value::dict(vec![]),true,false,true);
            let mut f=Fixture::with_bootstrap(modes,true).await;
            let session=f.session.clone();let modes=f.modes.clone();
            let task=tokio::spawn(async move {session.bootstrap_before_record(&modes).await});
            let declare=read(&mut f.car).await;
            assert_eq!(declare.first_line,"POST /command RTSP/1.0");
            let body=bplist::parse(&declare.body).unwrap();
            let resources=body.get("params").unwrap().get("resources").unwrap().as_array().unwrap();
            assert_eq!(resources[0].get("entity").and_then(Value::as_int),Some(1));
            assert_eq!(resources[1].get("entity").and_then(Value::as_int),Some(2));
            reply(&mut f.car,&declare,200,&[]).await;
            let setup=read(&mut f.car).await;
            let b=bplist::parse(&setup.body).unwrap();
            assert_eq!(b.get("streams").unwrap().as_array().unwrap()[0].get("latencyMs").and_then(Value::as_int),Some(100));
            let (mut video,id)=f.setup_reply(&setup).await;
            let record=read(&mut f.car).await;
            assert_eq!(record.first_line,"RECORD / RTSP/1.0");
            assert!(!f.session.activated.load(Ordering::Acquire));
            assert!(!f.session.params.pipeline.lock().video_channel_ready);
            f.source.send(SourceEvent::VideoFrame {pts_us:1,data:vec![0,0,0,1,0x65,2]}).unwrap();
            let mut byte=[0];
            assert!(tokio::time::timeout(Duration::from_millis(30),video.read(&mut byte)).await.is_err());
            if revoke {f.update(|m|borrow(m,1000));}
            reply(&mut f.car,&record,record_status,&[]).await;
            if record_status!=200 || revoke {
                f.teardown_reply().await;
                let result=task.await.unwrap();
                assert_eq!(result.is_ok(),record_status==200);
                let mut byte=[0];
                assert_eq!(tokio::time::timeout(Duration::from_secs(1),video.read(&mut byte)).await.unwrap().unwrap(),0);
                assert_eq!(f.session.params.pipeline.lock().video_frames_written,0);
                assert!(!f.session.resource_state.lock().await.negotiated);
            } else {
                task.await.unwrap().unwrap();
                assert!(f.session.activated.load(Ordering::Acquire));
                f.video_frame(&mut video,id).await;
            }
        }
    }

    #[tokio::test]
    async fn mbux_screen_rejection_does_not_send_record_or_report_ready() {
        let mut f=Fixture::with_bootstrap(Modes::initial(),true).await;
        let session=f.session.clone();let modes=f.modes.clone();
        let task=tokio::spawn(async move {session.bootstrap_before_record(&modes).await});
        f.modes_reply(1).await;
        let setup=read(&mut f.car).await;assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car,&setup,400,&[]).await;
        f.teardown_reply().await;
        assert!(task.await.unwrap().unwrap_err().contains("400"));
        assert!(!f.session.activated.load(Ordering::Acquire));
        assert!(!f.session.params.pipeline.lock().record_accepted);
    }

    #[tokio::test]
    async fn no_setup_before_activation_or_screen_grant_and_never_is_respected() {
        let mut modes=Modes::initial();borrow(&mut modes,1000);modes.request_ui();assert!(!modes.screen_owned());
        let mut f=Fixture::new(modes).await;
        f.session.activated.store(false,Ordering::Release);
        f.reconcile().await.unwrap().unwrap();
        let mut byte=[0];assert!(tokio::time::timeout(Duration::from_millis(25),f.car.read(&mut byte)).await.is_err());
        f.session.activated.store(true,Ordering::Release);
        let task=f.reconcile();f.modes_reply(2).await;task.await.unwrap().unwrap();
        assert_eq!(f.session.params.pipeline.lock().video_stream,"WAIT_OWNERSHIP");
        assert!(f.session.params.pipeline.lock().audio_ready);
        assert!(tokio::time::timeout(Duration::from_millis(25),f.video.accept()).await.is_err());
        assert!(tokio::time::timeout(Duration::from_millis(25),f.car.read(&mut byte)).await.is_err());
    }
    #[tokio::test]
    async fn first_grant_setup_then_borrow_closes_video_and_return_uses_fresh_crypto() {
        let mut modes=Modes::initial();borrow(&mut modes,100);
        let mut f=Fixture::new(modes).await;
        let task=f.reconcile();f.modes_reply(2).await;task.await.unwrap().unwrap();
        f.update(|m|m.request_ui());let task=f.reconcile();f.modes_reply(1).await;
        let setup=read(&mut f.car).await;let (mut peer,first)=f.setup_reply(&setup).await;task.await.unwrap().unwrap();
        f.video_frame(&mut peer,first).await;
        // A repeated click with the same grant sends feedback, without destructive teardown.
        f.update(|m|m.request_ui());let task=f.reconcile();f.modes_reply(1).await;task.await.unwrap().unwrap();
        assert_eq!(f.session.params.pipeline.lock().screen_rebuilds,0);
        f.update(|m|borrow(m,100));let task=f.reconcile();f.modes_reply(2).await;
        f.teardown_reply().await;task.await.unwrap().unwrap();
        let mut byte=[0];assert_eq!(tokio::time::timeout(Duration::from_secs(1),peer.read(&mut byte)).await.unwrap().unwrap(),0);
        assert!(f.session.params.pipeline.lock().video_tcp.is_none());
        let exit=bplist::parse(&f.session.resource_teardown(true).await).unwrap();
        let streams=exit.get("streams").unwrap().as_array().unwrap();
        assert_eq!(streams.len(),1);assert_eq!(streams[0].get("type").and_then(Value::as_int),Some(100));
        assert!(f.session.params.pipeline.lock().audio_ready);
        f.update(|m|m.request_ui());let task=f.reconcile();f.modes_reply(1).await;
        let setup=read(&mut f.car).await;let (mut peer,second)=f.setup_reply(&setup).await;task.await.unwrap().unwrap();
        assert_ne!(first,second);f.video_frame(&mut peer,second).await;
        assert_eq!(f.session.params.pipeline.lock().screen_rebuilds,1);
        // The test parser expects modes/SETUP/TEARDOWN only: no new global RECORD or audio setup.
        f.session.control.post_fire_and_forget("/feedback",&[],"application/octet-stream").await.unwrap();
        assert!(read(&mut f.car).await.first_line.contains("/feedback"));
    }
    #[tokio::test]
    async fn borrow_during_setup_cleans_partial_stream_and_does_not_publish_stale_grant() {
        let mut f=Fixture::new(Modes::initial()).await;
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        f.update(|m|borrow(m,1000));
        let (mut peer,_)=f.setup_reply(&setup).await;
        f.teardown_reply().await;f.modes_reply(2).await;task.await.unwrap().unwrap();
        let mut byte=[0];assert_eq!(peer.read(&mut byte).await.unwrap(),0);
        assert_eq!(f.session.params.pipeline.lock().video_stream,"WAIT_OWNERSHIP");
        assert!(f.session.params.pipeline.lock().audio_ready);
    }
    #[tokio::test]
    async fn rejected_setup_cleans_video_only_and_next_ui_can_retry() {
        let mut f=Fixture::new(Modes::initial()).await;
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        reply(&mut f.car,&setup,500,&[]).await;f.teardown_reply().await;
        assert!(task.await.unwrap().is_err());assert!(f.session.params.pipeline.lock().audio_ready);
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        let (mut peer,id)=f.setup_reply(&setup).await;task.await.unwrap().unwrap();f.video_frame(&mut peer,id).await;
    }
    #[tokio::test]
    async fn active_sender_does_not_resume_old_grant_after_fast_borrow_and_return() {
        let mut f=Fixture::new(Modes::initial()).await;
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        let (mut peer,id)=f.setup_reply(&setup).await;task.await.unwrap().unwrap();
        f.video_frame(&mut peer,id).await;
        f.update(|m|{borrow(m,100);m.request_ui();});
        // The dispatcher can see borrow+return faster than lifecycle cleanup runs. The old
        // active sender must still stop; merely seeing phone_owned=true again is insufficient.
        f.source.send(SourceEvent::VideoFrame {pts_us:3000,data:vec![0,0,0,1,0x41,3]}).unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(100),async {
            loop {
                let mut header=[0;128];peer.read_exact(&mut header).await.unwrap();
                let mut body=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut body).await.unwrap();
                if header[4]==0 {break;}
            }
        }).await.is_err());
        let task=f.reconcile();f.modes_reply(1).await;f.teardown_reply().await;
        let setup=read(&mut f.car).await;let (mut peer,new_id)=f.setup_reply(&setup).await;
        task.await.unwrap().unwrap();assert_ne!(id,new_id);f.video_frame(&mut peer,new_id).await;
    }
    #[tokio::test]
    async fn queued_install_rejects_old_grant_even_after_screen_is_returned() {
        let video=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(video.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=video.accept().await.unwrap();let (_,write)=socket.into_split();
        let modes=Arc::new(Mutex::new(Modes::initial()));let revision=modes.lock().screen_generation();
        let (commands,rx)=mpsc::channel(2);let (source,video_rx)=mpsc::unbounded_channel();
        let (done,accepted)=tokio::sync::oneshot::channel();
        commands.send(ScreenCommand::InstallIfOwned {
            sender:crate::screen::ScreenSender::new(write,crate::screen::ScreenCipher::ChaCha([1;32]),1920,720,false),
            modes:modes.clone(),revision,reply:done,
        }).await.unwrap();
        {let mut m=modes.lock();borrow(&mut m,100);m.request_ui();assert!(m.screen_owned());}
        let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        let pump=tokio::spawn(video_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(PumpStat::default())),true,pipeline,rx,video_rx));
        assert!(!tokio::time::timeout(Duration::from_secs(1),accepted).await.unwrap().unwrap());
        let mut byte=[0];assert_eq!(peer.read(&mut byte).await.unwrap(),0);
        pump.abort();drop(source);
    }
    #[tokio::test]
    async fn cancelled_setup_never_installs_a_late_video_sender() {
        let mut f=Fixture::new(Modes::initial()).await;
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        task.abort();assert!(task.await.unwrap_err().is_cancelled());
        let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("dataPort",Value::int(f.video.local_addr().unwrap().port() as i64))])]))]));
        reply(&mut f.car,&setup,200,&body).await;
        assert!(tokio::time::timeout(Duration::from_millis(50),f.video.accept()).await.is_err());
    }
}
