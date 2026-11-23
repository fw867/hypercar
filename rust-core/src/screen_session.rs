//! Video stream creation and pump control, shared by default and resource-driven sessions.
use std::sync::{Arc, atomic::AtomicBool};
use parking_lot::Mutex;
use tokio::sync::{mpsc,oneshot};
use crate::{airplay::{self,AirplayParams,SocketProvider},bplist::{self,Value},rtsp::RtspChannel,screen::{ScreenSender,ScreenCipher}};

#[path = "setup_stream.rs"]
pub(crate) mod setup_stream;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct GrantedTransport {
    pub revision: u64,
    pub connection_id: u64,
}

pub(crate) struct TransportMonitor {
    pub control: Arc<RtspChannel>,
    pub shutdown: Arc<crate::shutdown::EngineShutdown>,
    pub failures: tokio::sync::watch::Sender<Option<GrantedTransport>>,
    pub connection_id: u64,
}

pub enum ScreenCommand {
    Install(ScreenSender),
    Pause(oneshot::Sender<()>),
    InstallIfOwned { sender:ScreenSender, modes:Arc<Mutex<crate::modes::Modes>>, revision:u64, reply:oneshot::Sender<bool> },
}

pub struct ScreenSession {
    pub control: Arc<RtspChannel>,
    pub(crate) shutdown: Arc<crate::shutdown::EngineShutdown>,
    pub wifi_iap_id: u64,
    pub factory: Arc<dyn SocketProvider>,
    pub params: AirplayParams,
    pub software_receiver: bool,
    pub shared: Option<[u8;32]>,
    pub uuid: String,
    pub keep_stats: bool,
    pub hevc_allowed: bool,
    pub display: (u32,u32),
    pub owned: Arc<AtomicBool>,
    pub commands: mpsc::Sender<ScreenCommand>,
    pub bootstrap_event: Option<Arc<RtspChannel>>,
    pub bootstrap_hids: Vec<Value>,
    pub activated: AtomicBool,
    pub(crate) transport_failures: tokio::sync::watch::Sender<Option<GrantedTransport>>,
    pub(crate) resource_state: tokio::sync::Mutex<crate::screen_resource::ResourceStream>,
}

impl ScreenSession {
    pub(crate) fn session_is_live(&self) -> bool {
        !self.shutdown.requested() && self.control.session_is_live()
    }

    pub async fn open(&self) -> Result<ScreenSender,String> {
        self.open_while(|| true).await
    }

    pub(crate) async fn open_while(&self, current: impl Fn() -> bool) -> Result<ScreenSender,String> {
        let conn_id=airplay::random_positive_i64();
        let body=if self.params.mbux_screen_bootstrap {
            airplay::build_screen_setup_with_latency(conn_id,&self.uuid,100)
        } else if self.params.id9_screen_bootstrap {
            // xiap2 0x3abac..0x3abb8 proposes 75ms. Keep this ID9 comparison
            // independent of SAP, HEVC and resource/RECORD ordering.
            crate::emit_log("ID9: screen SETUP latencyMs=75 reference=box-firmware; receiver acceptance pending");
            airplay::build_screen_setup_with_latency(conn_id,&self.uuid,75)
        } else if self.params.bmw_capabilities && self.params.bmw_box_bootstrap && !self.params.nbt_audio_first && !self.software_receiver {
            crate::emit_log("BMW-MGU: screen SETUP latencyMs=75 reference=box-firmware; vehicle validation pending");
            airplay::build_screen_setup_with_latency(conn_id,&self.uuid,75)
        } else { airplay::build_screen_setup(conn_id,&self.uuid) };
        let response=self.control.screen_setup(&body).await?;
        if response.status()!=200 { return Err(format!("屏幕 SETUP 被拒 {}",response.first_line)); }
        crate::resource_trace::validate_ack("SCREEN_SETUP", &response)?;
        let root=bplist::parse(&response.body).map_err(|e|format!("屏幕 SETUP 解析失败 {e}"))?;
        let stream=setup_stream::select(&root,110,self.software_receiver)
            .map_err(|error|format!("屏幕 {error}"))?;
        let port=airplay::port_of(stream.get("dataPort"));
        if port==0 { return Err("屏幕 SETUP 没有 dataPort".into()); }
        self.control.udp_media.set(110, true);
        crate::pipeline::activation(&self.params.pipeline, "SCREEN_SETUP");
        crate::wifi_iap2::screen_setup_accepted(self.wifi_iap_id);
        let cipher=match &self.shared {
            Some(shared)=>ScreenCipher::ChaCha(airplay::stream_key(shared,conn_id as u64)),
            None=>{let (key,iv)=airplay::aes_stream_keys_zero(conn_id as u64);ScreenCipher::AesCtr(crate::crypto::Aes128CtrStream::new(&key,&iv))},
        };
        if !current() { return Err("屏幕授权或会话已失效".into()); }
        let socket=self.factory.open_tcp(&self.params.car_ip,port,5000).ok_or_else(||format!("屏幕端口 {port} 连接失败"))?;
        let (_,write)=socket.into_split();
        let (w,h)=*self.params.encoder_size.lock();
        let mut sender=ScreenSender::new(write,cipher,w,h,self.keep_stats);
        sender.set_receiver_size(self.display);
        sender.set_hevc_allowed(self.hevc_allowed);
        sender.attach_diagnostics(self.params.pipeline.clone(),conn_id as u64);
        if self.params.carwith_stretch { sender.set_display_target(self.display.0,self.display.1); }
        sender.set_ownership(self.owned.clone());
        if self.params.screen_resource_lifecycle {
            sender.transport_monitor = Some(TransportMonitor {
                control: self.control.clone(), shutdown: self.shutdown.clone(),
                failures: self.transport_failures.clone(), connection_id: conn_id as u64,
            });
        }
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
