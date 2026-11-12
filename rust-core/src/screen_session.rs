//! Video stream creation and pump control, shared by default and resource-driven sessions.
use std::sync::{Arc, atomic::AtomicBool};
use std::time::Duration;
use parking_lot::Mutex;
use tokio::sync::{mpsc,oneshot};
use crate::{airplay::{self,AirplayParams,SocketProvider},bplist::{self,Value},rtsp::RtspChannel,screen::{ScreenSender,ScreenCipher}};

pub enum ScreenCommand {
    Install(ScreenSender),
    Pause(oneshot::Sender<()>),
    InstallIfOwned { sender:ScreenSender, modes:Arc<Mutex<crate::modes::Modes>>, revision:u64, reply:oneshot::Sender<bool> },
}

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
    pub bootstrap_event: Option<Arc<RtspChannel>>,
    pub bootstrap_hids: Vec<Value>,
    pub activated: AtomicBool,
    pub(crate) resource_state: tokio::sync::Mutex<crate::screen_resource::ResourceStream>,
}

impl ScreenSession {
    pub async fn open(&self) -> Result<ScreenSender,String> {
        let conn_id=airplay::random_positive_i64();
        let body=if self.params.mbux_screen_bootstrap {
            airplay::build_screen_setup_with_latency(conn_id,&self.uuid,100)
        } else { airplay::build_screen_setup(conn_id,&self.uuid) };
        let response=self.control.request("SETUP","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(8)).await?;
        if response.status()!=200 { return Err(format!("屏幕 SETUP 被拒 {}",response.first_line)); }
        crate::pipeline::activation(&self.params.pipeline, "SCREEN_SETUP");
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
        if !(self.params.mbux_screen_bootstrap || self.params.id9_screen_bootstrap) || self.activated.load(std::sync::atomic::Ordering::Acquire) {
            crate::pipeline::activation(&self.params.pipeline, "VIDEO_CHANNEL_READY");
        }
        Ok(sender)
    }

    pub async fn install(&self,sender:ScreenSender) -> Result<(),String> {
        self.commands.send(ScreenCommand::Install(sender)).await.map_err(|_|"视频泵已退出".to_string())?;
        self.params.pipeline.lock().video_stream="READY".into();
        Ok(())
    }

}
