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
    /// Match the MGU box's post-handoff initial Take, without replaying a receiver decision.
    /// The caller has completed RECORD/resources but has not activated screen reconciliation.
    pub async fn claim_box_startup_screen(&self, modes: &Arc<Mutex<Modes>>, revision: u64) -> Result<(), String> {
        if !self.params.bmw_capabilities || !self.params.bmw_box_bootstrap || self.software_receiver ||
            self.params.id9_screen_bootstrap || self.params.nbt_audio_first || self.params.mbux_screen_bootstrap ||
            self.activated.load(Ordering::Acquire) { return Ok(()); }
        let (previous, claim_revision, declaration) = {
            let mut m = modes.lock();
            let previous = m.clone();
            if m.screen_generation() != revision || !m.claim_startup_screen() { return Ok(()); }
            self.owned.store(false,Ordering::Release);
            (previous,m.screen_generation(),m.declaration())
        };
        let result = self.control.request("POST","/command",&declaration,
            "application/x-apple-binary-plist",&[],Duration::from_secs(5)).await
            .and_then(|response|crate::resource_trace::validate_ack("BMW STARTUP_SCREEN_CLAIM",&response));
        // Event commands may have changed ownership while the claim was in flight.
        let mut m = modes.lock();
        if result.is_err() { m.restore_startup_screen(&previous,claim_revision); }
        self.owned.store(m.screen_owned(),Ordering::Release);
        self.params.pipeline.lock().screen_owned = Some(m.screen_owned());
        crate::emit_log(&format!("BMW-MGU: startup screen declaration accepted={} {}; latest receiver changes retained",result.is_ok(),m.diagnostics()));
        result
    }
    /// MBUX opens the screen TCP endpoint before RECORD, but never exposes it to the pump
    /// until RECORD succeeds. Event callbacks may arbitrate while this serialized bootstrap runs.
    pub async fn bootstrap_before_record(&self, modes: &Arc<Mutex<Modes>>) -> Result<(), String> {
        self.bootstrap_before_record_with(modes, async { Ok(()) }).await
    }

    /// NBT Evo inserts audio SETUP after the screen TCP is open, before activation.
    pub async fn bootstrap_before_record_with<T,F>(&self, modes: &Arc<Mutex<Modes>>, prepare: F) -> Result<T,String>
        where F: std::future::Future<Output=Result<T,String>> {
        let mut state = self.resource_state.lock().await;
        let label = if self.params.nbt_audio_first { "NBT" } else if self.params.id9_screen_bootstrap { "ID9" } else { "MBUX" };
        let (screen_revision, mut revision, mut owned, declaration) = {
            let m = modes.lock();
            (m.screen_generation(), m.screen_grant_generation(), m.screen_owned(), m.declaration())
        };
        let response = self.control.request("POST", "/command", &declaration,
            "application/x-apple-binary-plist", &[], Duration::from_secs(5)).await?;
        crate::resource_trace::validate_ack(&format!("{label} RESOURCES"), &response)?;
        if self.params.id9_screen_bootstrap {
            let claim = {
                let mut m = modes.lock();
                if m.screen_generation() == screen_revision { m.request_ui(); }
                self.owned.store(m.screen_owned(), Ordering::Release);
                revision = m.screen_grant_generation();
                owned = m.screen_owned();
                self.params.pipeline.lock().screen_owned = Some(owned);
                m.declaration()
            };
            let response = self.control.request("POST", "/command", &claim,
                "application/x-apple-binary-plist", &[], Duration::from_secs(5)).await?;
            crate::resource_trace::validate_ack("ID9 SCREEN_CLAIM", &response)?;
            if let Some(event) = &self.bootstrap_event {
                for id in &self.bootstrap_hids {
                    let body = bplist::encode(&Value::dict(vec![("type",Value::str("hidSetInputMode")),
                        ("params",Value::dict(vec![("hidInputMode",Value::int(0)),("uuid",id.clone())]))]));
                    event.post_fire_and_forget("/command", &body, "application/x-apple-binary-plist").await?;
                    crate::emit_log("ID9: HID input mode initialized on event channel");
                }
            }
        }
        crate::pipeline::activation(&self.params.pipeline, "RESOURCES_ACCEPTED");
        crate::emit_log(&format!("{label}: resource declaration accepted; preparing screen before RECORD"));
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
        let prepared = match prepare.await {
            Ok(value) => value,
            Err(error) => {
                drop(sender);
                if state.negotiated { let _ = self.release_resource_stream(&mut state).await; }
                return Err(error);
            }
        };
        let activation = if self.params.nbt_audio_first { crate::nbt::record_once(&self.control).await }
            else { crate::airplay::record_session(&self.control).await };
        if let Err(error) = activation {
            drop(sender);
            if state.negotiated { let _ = self.release_resource_stream(&mut state).await; }
            return Err(error);
        }
        crate::pipeline::activation(&self.params.pipeline, "RECORD");
        self.activated.store(true, Ordering::Release);
        crate::emit_log(&format!("{label}: RECORD accepted; video installation now permitted"));
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
        Ok(prepared)
    }

    /// Query after event workers are stopped: graceful exit includes only negotiated streams.
    pub async fn resource_teardown(&self, audio: Option<i64>) -> Vec<Value> {
        let state = self.resource_state.lock().await;
        let mut streams = Vec::new();
        if state.negotiated { streams.push(Value::dict(vec![("type",Value::int(110)),("uuid",Value::str(&self.uuid))])); }
        if let Some(kind) = audio { streams.push(Value::dict(vec![("type",Value::int(kind))])); }
        streams
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
        self.reconcile_screen(modes, false).await
    }

    pub async fn reconcile_after_mbux_ready(&self, modes: &Arc<Mutex<Modes>>, model: &str) -> Result<(), String> {
        self.reconcile_screen(modes, self.params.mbux_media_compat && model == "MB_E-P-4").await
    }

    async fn reconcile_screen(&self, modes: &Arc<Mutex<Modes>>, mut literal_root: bool) -> Result<(), String> {
        // Event dispatch is already running before the initial global RECORD. It may update
        // arbitration, but no lifecycle SETUP can precede successful session activation.
        if !self.activated.load(Ordering::Acquire) { return Ok(()); }
        let mut state = self.resource_state.lock().await;
        loop {
            let (revision, owned, declaration, note) = {
                let m = modes.lock();
                (m.screen_grant_generation(), m.screen_owned(), m.declaration(), m.diagnostics())
            };
            crate::emit_log(&format!("SCREEN-LIFECYCLE: reconcile {note} negotiated={} installed_revision={:?}", state.negotiated, state.revision));
            // This also publishes audio/app updates while video is awaiting screen ownership.
            let response = self.control.request("POST", "/command", &declaration, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await?;
            let business = bplist::parse(&response.body).ok().and_then(|v| v.get("status").and_then(Value::as_int));
            crate::emit_log(&format!("SCREEN-LIFECYCLE: modes feedback revision={revision} RTSP_status={} business_status={business:?}; receiver display unconfirmed", response.status()));
            if response.status() != 200 || business.is_some_and(|s| s != 0) {
                return Err("画面资源声明未被接受".into());
            }
            if modes.lock().screen_grant_generation() != revision { continue; }
            let failed = self.params.pipeline.lock().video_stream == "FAILED";
            if state.negotiated && (!owned || state.revision != Some(revision) || failed) {
                self.release_resource_stream(&mut state).await?;
                // A grant arriving during TEARDOWN needs its own current declaration before SETUP.
                if modes.lock().screen_grant_generation() != revision { continue; }
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
            let sender = match self.open_screen(std::mem::take(&mut literal_root)).await {
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
            if modes.lock().screen_grant_generation() != revision { continue; }
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
                if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap().pop() {return message;}
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
                wifi_mac_address:None,
                car_ip:"127.0.0.1".into(),airplay_port:7000,device_id:"test".into(),device_name:"Test phone".into(),identity_path:"unused".into(),
                receiver_id:None,receiver_previously_connected:true,enable_audio:true,audio_codec:String::new(),video_codec:String::new(),bmw_capabilities:true,microphone_probe:None,keep_alive_stats:false,
                bmw_box_bootstrap:!mbux,mbux_screen_bootstrap:mbux,id9_screen_bootstrap:false,nbt_audio_first:false,mbux_media_compat:false,desktop_receiver:false,width:1920,height:720,touch_display:None,touch_rect:None,
                encoder_size:Arc::new(Mutex::new((1920,720))),touch_size:Arc::new(Mutex::new((1920,720))),
                carwith_display:None,carwith_stretch:false,screen_resource_lifecycle:true,pipeline:pipeline.clone(),
            };
            let (commands,rx)=mpsc::channel(2);let (source,video_rx)=mpsc::unbounded_channel();
            let pump=tokio::spawn(video_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(PumpStat::default())),true,pipeline,rx,video_rx,false,30,None));
            let owned=Arc::new(AtomicBool::new(modes.screen_owned()));
            let session=Arc::new(ScreenSession {control,factory:Arc::new(Factory),params,shared:Some([9;32]),uuid:"screen-test".into(),
                hevc_allowed:false,keep_stats:false,software_receiver:false,display:(1920,720),owned,commands,bootstrap_event:None,bootstrap_hids:Vec::new(),activated:AtomicBool::new(!mbux),resource_state:tokio::sync::Mutex::new(Default::default())});
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
                ("type",Value::int(110)),("dataPort",Value::int(self.video.local_addr().unwrap().port() as i64)),
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
    async fn nbt_screen_then_audio_then_record_with_failure_cleanup() {
        for fail in [false,true] {
            let mut f=Fixture::with_bootstrap(Modes::initial(),true).await;
            {let session=Arc::get_mut(&mut f.session).unwrap();
                session.params.mbux_screen_bootstrap=false;session.params.nbt_audio_first=true;}
            let session=f.session.clone();let modes=f.modes.clone();
            let task=tokio::spawn(async move {
                let control=session.control.clone();
                session.bootstrap_before_record_with(&modes,async move {
                    let r=control.request("SETUP","/",b"test audio","application/octet-stream",&[],Duration::from_secs(2)).await?;
                    if r.status()!=200 {return Err("audio rejected".into());} Ok(42)
                }).await
            });
            f.modes_reply(1).await;
            let setup=read(&mut f.car).await;let(mut video,id)=f.setup_reply(&setup).await;
            let audio=read(&mut f.car).await;assert_eq!(audio.body,b"test audio");
            assert!(!f.session.activated.load(Ordering::Acquire));
            reply(&mut f.car,&audio,if fail{400}else{200},&[]).await;
            if fail {
                f.teardown_reply().await; // No RECORD is allowed on failed audio preparation.
                assert!(task.await.unwrap().is_err());
                assert!(!f.session.activated.load(Ordering::Acquire));
                assert!(!f.session.resource_state.lock().await.negotiated);
            } else {
                let record=read(&mut f.car).await;assert_eq!(record.first_line,"RECORD / RTSP/1.0");
                reply(&mut f.car,&record,200,&[]).await;
                assert_eq!(task.await.unwrap().unwrap(),42);
                f.video_frame(&mut video,id).await;
                crate::nbt::record_once(&f.session.control).await.unwrap();
                let streams = f.session.resource_teardown(Some(102)).await;
                assert_eq!(streams[1].get("type").and_then(Value::as_int),Some(102));
            }
            let mut byte=[0];assert!(tokio::time::timeout(Duration::from_millis(30),f.car.read(&mut byte)).await.is_err());
        }
    }
    fn startup_screen_modes(constraint: i64) -> Modes {
        let info=Value::dict(vec![("modes",Value::dict(vec![("initialPermanentEntity",Value::array(vec![
            Value::dict(vec![("resourceID",Value::int(1)),("permanentEntity",Value::int(2)),
                ("takeConstraint",Value::int(constraint)),("borrowConstraint",Value::int(100))]),
        ]))]))]);
        Modes::from_info(&info,true,true)
    }
    async fn streaming_mgu(start: std::time::Instant) -> (Fixture, RtspMessage, TcpStream, u64, tokio::task::JoinHandle<()>, u128, u128) {
        let mut f = Fixture::new(startup_screen_modes(500)).await;
        f.session.activated.store(false, Ordering::Release);
        let session = f.session.clone();
        let modes = f.modes.clone();
        let revision = modes.lock().screen_generation();
        let claim = tokio::spawn(async move {session.claim_box_startup_screen(&modes, revision).await});
        f.modes_reply(1).await;
        claim.await.unwrap().unwrap();
        let session = f.session.clone();
        let modes = f.modes.clone();
        let setup = tokio::spawn(async move {
            let mut tasks = Vec::new();
            let handoff = crate::airplay::start_mgu_handoff(&mut tasks, session.control.clone(),
                "phone".into(), || true, Arc::new(Notify::new()));
            assert!(!crate::airplay::activate_screen_before_handoff(&session, &modes, Some(handoff)).await.unwrap());
            tasks.pop().unwrap()
        });
        f.modes_reply(1).await;
        let request = read(&mut f.car).await;
        let setup_ms = start.elapsed().as_millis();
        let (mut video, id) = f.setup_reply(&request).await;
        let handoff = read(&mut f.car).await;
        assert_eq!(bplist::parse(&handoff.body).unwrap().get("type").and_then(Value::as_str), Some("disableBluetooth"));
        let task = setup.await.unwrap();
        f.video_frame(&mut video, id).await;
        let idr_ms = start.elapsed().as_millis();
        (f, handoff, video, id, task, setup_ms, idr_ms)
    }

    #[tokio::test]
    async fn first_ui_after_mgu_startup_keeps_stream_and_sends_declaration() {
        let (mut f, handoff, mut video, id, task, _, _) = streaming_mgu(std::time::Instant::now()).await;
        reply(&mut f.car, &handoff, 200, &[]).await;
        task.await.unwrap();

        f.update(Modes::request_ui);
        let reconcile = f.reconcile();
        f.modes_reply(1).await;
        // A healthy stream can still deliver a P-frame encrypted under the original key.
        verify_followup_frame(&f, &mut video, id, 0x33).await;
        reconcile.await.unwrap().unwrap();
        assert_eq!(f.session.params.pipeline.lock().screen_rebuilds, 0);
    }

    #[tokio::test]
    async fn first_ui_during_mgu_setup_preserves_inflight_grant() {
        let mut modes = startup_screen_modes(500);
        assert!(modes.claim_startup_screen());
        let mut f = Fixture::new(modes).await;
        let setup = f.reconcile();
        f.modes_reply(1).await;
        let request = read(&mut f.car).await;
        f.update(Modes::request_ui);
        let ui = f.reconcile();
        let (mut video, id) = f.setup_reply(&request).await;
        f.modes_reply(1).await;
        setup.await.unwrap().unwrap();
        ui.await.unwrap().unwrap();
        f.video_frame(&mut video, id).await;
    }

    #[tokio::test]
    async fn mgu_screen_and_idr_arrive_before_delayed_handoff_reply() {
        let start = std::time::Instant::now();
        let (mut f, handoff, _video, _id, task, setup_ms, idr_ms) = streaming_mgu(start).await;
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(!task.is_finished());
        reply(&mut f.car, &handoff, 200, &[]).await;
        task.await.unwrap();
        println!("MGU loopback type110={setup_ms}ms decrypted_IDR={idr_ms}ms handoff_reply={}ms",start.elapsed().as_millis());
    }

    async fn verify_followup_frame(f: &Fixture, video: &mut TcpStream, id: u64, marker: u8) {
        f.source.send(SourceEvent::VideoFrame { pts_us: 35_333, data: vec![0,0,0,1,0x41,marker] }).unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            loop {
                let mut header = [0;128];
                video.read_exact(&mut header).await.unwrap();
                let mut body = vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
                video.read_exact(&mut body).await.unwrap();
                if header[4] == 0 {
                    let key = crate::airplay::stream_key(&[9;32], id);
                    assert_eq!(crate::crypto::chacha_open(&key, &crate::crypto::nonce64(1), &body, &header).unwrap(),
                        [0,0,0,2,0x41,marker]);
                    break;
                }
            }
        }).await.unwrap();
    }

    async fn verify_feedback(f: &mut Fixture) {
        let control = f.session.control.clone();
        let task = tokio::spawn(async move {
            control.request("POST", "/feedback", &[], "application/octet-stream", &[], Duration::from_secs(1)).await
        });
        let msg = read(&mut f.car).await;
        assert!(msg.first_line.starts_with("POST /feedback "));
        reply(&mut f.car, &msg, 200, &[]).await;
        assert_eq!(task.await.unwrap().unwrap().status(), 200);
    }

    #[tokio::test]
    async fn mgu_handoff_timeout_and_late_rejection_preserve_video_and_isolate_reconnect() {
        let start = std::time::Instant::now();
        let (mut old, old_msg, mut old_video, old_id, old_task, _, idr) = streaming_mgu(start).await;
        tokio::time::timeout(Duration::from_secs(4), old_task).await.unwrap().unwrap();
        assert!(start.elapsed() >= Duration::from_secs(3));
        verify_followup_frame(&old, &mut old_video, old_id, 0x11).await;
        verify_feedback(&mut old).await;
        println!("old CSeq={:?} IDR={idr}ms subsequent P frame and feedback OK at {}ms", old_msg.cseq(), start.elapsed().as_millis());
        let next = std::time::Instant::now();
        let (mut new, new_msg, mut new_video, new_id, new_task, _, idr) = streaming_mgu(next).await;
        assert_eq!(old_msg.cseq(), new_msg.cseq());
        // Keep the retiring control socket readable to exercise an actual late arrival.
        reply(&mut old.car, &old_msg, 503, &[]).await;
        verify_feedback(&mut old).await;
        old.session.control.close();
        drop(old_video);
        drop(old);
        tokio::time::sleep(Duration::from_secs(1)).await;
        assert!(!new_task.is_finished(), "old reply completed the new transaction");
        let reject = next.elapsed().as_millis();
        reply(&mut new.car, &new_msg, 500, &[]).await;
        tokio::time::timeout(Duration::from_millis(500), new_task).await.unwrap().unwrap();
        verify_followup_frame(&new, &mut new_video, new_id, 0x22).await;
        verify_feedback(&mut new).await;
        println!("new CSeq={:?} IDR={idr}ms delayed 500={reject}ms subsequent P frame and feedback OK at {}ms",
            new_msg.cseq(), next.elapsed().as_millis());
    }

    #[tokio::test]
    async fn box_startup_claim_unblocks_500_only_after_its_ack_and_preserves_newer_owner() {
        for receiver_changes in [false,true] {
            let mut f=Fixture::new(startup_screen_modes(500)).await;
            f.session.activated.store(false,Ordering::Release);
            let revision=f.modes.lock().screen_generation();
            let session=f.session.clone(); let modes=f.modes.clone();
            let task=tokio::spawn(async move {session.claim_box_startup_screen(&modes,revision).await});
            let request=tokio::time::timeout(Duration::from_millis(500),read(&mut f.car)).await
                .expect("BMW startup must publish a constrained screen claim instead of waiting indefinitely");
            assert!(request.first_line.starts_with("POST /command "));
            let body=bplist::parse(&request.body).unwrap();
            assert_eq!(body.get("type").and_then(Value::as_str),Some("modesChanged"));
            let resources=body.get("params").unwrap().get("resources").unwrap().as_array().unwrap();
            assert_eq!(resources[0].get("entity").and_then(Value::as_int),Some(1));
            assert_eq!(resources[1].get("entity").and_then(Value::as_int),Some(1));
            assert!(!f.session.activated.load(Ordering::Acquire));
            assert!(!f.session.owned.load(Ordering::Acquire),"no screen gate before claim ACK");
            assert!(tokio::time::timeout(Duration::from_millis(30),f.video.accept()).await.is_err());
            if receiver_changes {f.update(|m|borrow(m,1000));}
            reply(&mut f.car,&request,200,&bplist::encode(&Value::dict(vec![("status",Value::int(0))]))).await;
            task.await.unwrap().unwrap();
            assert_eq!(f.modes.lock().screen_owned(),!receiver_changes);
            assert_eq!(f.session.owned.load(Ordering::Acquire),!receiver_changes);
            let latest=f.modes.lock().screen_generation();
            f.session.claim_box_startup_screen(&f.modes,latest).await.unwrap();
            assert!(tokio::time::timeout(Duration::from_millis(30),read(&mut f.car)).await.is_err());
        }
    }
    #[tokio::test]
    async fn box_startup_claim_respects_never_borrows_revision_and_profile_exclusions() {
        for case in 0..8 {
            let mut f=Fixture::new(startup_screen_modes(if case==0 {1000}else{500})).await;
            let mut revision=f.modes.lock().screen_generation();
            if case==1 {f.update(|m|m.request_ui()); f.update(|m|borrow(m,1000)); revision=f.modes.lock().screen_generation();}
            if case==2 {f.update(|m|m.request_ui()); f.update(|m|borrow(m,100));}
            let session=Arc::get_mut(&mut f.session).unwrap();
            session.activated.store(false,Ordering::Release);
            if case==3 {session.params.id9_screen_bootstrap=true;}
            if case==4 {session.params.nbt_audio_first=true;}
            if case==5 {session.software_receiver=true;}
            if case==6 {session.params.bmw_box_bootstrap=false;}
            if case==7 {session.params.mbux_screen_bootstrap=true;}
            let before=f.modes.lock().snapshot();
            f.session.claim_box_startup_screen(&f.modes,revision).await.unwrap();
            assert_eq!(f.modes.lock().snapshot(),before,"case {case}");
            assert!(tokio::time::timeout(Duration::from_millis(30),read(&mut f.car)).await.is_err(),"case {case}");
        }
    }
    #[tokio::test]
    async fn box_startup_claim_rejection_cannot_open_a_video_socket() {
        let mut f=Fixture::new(startup_screen_modes(500)).await;
        f.session.activated.store(false,Ordering::Release);
        let revision=f.modes.lock().screen_generation();
        let session=f.session.clone();let modes=f.modes.clone();
        let task=tokio::spawn(async move {session.claim_box_startup_screen(&modes,revision).await});
        let request=tokio::time::timeout(Duration::from_millis(500),read(&mut f.car)).await.unwrap();
        reply(&mut f.car,&request,200,&bplist::encode(&Value::dict(vec![("status",Value::int(1))]))).await;
        assert!(task.await.unwrap().unwrap_err().contains("business rejection"));
        assert!(!f.modes.lock().screen_owned(),"rejected startup claim restores earlier screen owner");
        assert!(!f.session.owned.load(Ordering::Acquire));
        assert!(!f.session.activated.load(Ordering::Acquire));
        assert!(tokio::time::timeout(Duration::from_millis(30),f.video.accept()).await.is_err());
    }
    #[tokio::test]
    async fn box_startup_claim_rejection_preserves_concurrent_screen_and_audio_changes() {
        for screen_change in [false,true] {
            let mut f=Fixture::new(startup_screen_modes(500)).await;
            f.session.activated.store(false,Ordering::Release);
            let revision=f.modes.lock().screen_generation();
            let session=f.session.clone();let modes=f.modes.clone();
            let task=tokio::spawn(async move {session.claim_box_startup_screen(&modes,revision).await});
            let request=tokio::time::timeout(Duration::from_millis(500),read(&mut f.car)).await.unwrap();
            if screen_change {f.update(|m|borrow(m,1000));}
            else {f.update(|m|assert!(m.change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                ("resourceID",Value::int(2)),("transferType",Value::int(1)),
                ("transferPriority",Value::int(500)),("takeConstraint",Value::int(100)),("borrowConstraint",Value::int(100)),
            ])]))]))));}
            let after_receiver=f.modes.lock().snapshot();
            reply(&mut f.car,&request,400,&[]).await;
            assert!(task.await.unwrap().is_err());
            let modes=f.modes.lock();
            if screen_change {assert_eq!(modes.snapshot(),after_receiver);}
            else {assert!(!modes.audio_owned());}
            assert!(!modes.screen_owned());
            assert!(!f.session.owned.load(Ordering::Acquire));
        }
    }
    fn enable_id9(f: &mut Fixture) {
        let session=Arc::get_mut(&mut f.session).unwrap();
        session.params.id9_screen_bootstrap=true;
        session.params.bmw_box_bootstrap=false;
        session.activated.store(false,Ordering::Release);
    }
    #[tokio::test]
    async fn id9_declares_requests_screen_sets_up_tcp_and_records_before_any_frame() {
        for (status,revoke) in [(200,false),(403,false),(200,true)] {
            let mut f=Fixture::new(Modes::id9_initial(&Value::dict(vec![]),true,"ID9 test")).await;
            enable_id9(&mut f);
            let session=f.session.clone();let modes=f.modes.clone();
            let task=tokio::spawn(async move {session.bootstrap_before_record(&modes).await});
            f.modes_reply(2).await;
            f.modes_reply(1).await;
            assert_eq!(f.session.params.pipeline.lock().screen_owned, Some(true));
            let setup=read(&mut f.car).await;
            let body=bplist::parse(&setup.body).unwrap();
            let stream=&body.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("uuid").and_then(Value::as_str),Some("screen-test"));
            assert_eq!(stream.get("latencyMs").and_then(Value::as_int),Some(75));
            assert_eq!(stream.as_dict().unwrap().len(),4);
            assert_eq!(stream.get("type").and_then(Value::as_int),Some(110));
            assert!(stream.get("streamConnectionID").and_then(Value::as_int).unwrap()>0);
            let (mut video,id)=f.setup_reply(&setup).await;
            let record=read(&mut f.car).await;assert_eq!(record.first_line,"RECORD / RTSP/1.0");
            assert!(!f.session.activated.load(Ordering::Acquire));
            assert!(!f.session.params.pipeline.lock().video_channel_ready);
            f.source.send(SourceEvent::VideoFrame{pts_us:1,data:vec![0,0,0,1,0x65,2]}).unwrap();
            let mut byte=[0];
            assert!(tokio::time::timeout(Duration::from_millis(30),video.read(&mut byte)).await.is_err());
            if revoke { f.update(|m|borrow(m,1000)); }
            reply(&mut f.car,&record,status,&[]).await;
            if status!=200 || revoke {
                f.teardown_reply().await;
                assert_eq!(task.await.unwrap().is_ok(),status==200);
                assert_eq!(f.session.params.pipeline.lock().video_frames_written,0);
                assert!(!f.session.resource_state.lock().await.negotiated);
            } else {
                task.await.unwrap().unwrap();
                f.video_frame(&mut video,id).await;
            }
        }
    }
    #[tokio::test]
    async fn id9_screen_400_never_sends_record_and_business_rejection_never_sends_setup() {
        for business_reject in [false,true] {
            let mut f=Fixture::new(Modes::id9_initial(&Value::dict(vec![]),true,"ID9 test")).await;
            enable_id9(&mut f);
            let session=f.session.clone();let modes=f.modes.clone();
            let task=tokio::spawn(async move {session.bootstrap_before_record(&modes).await});
            f.modes_reply(2).await;
            if business_reject {
                let claim=read(&mut f.car).await;
                reply(&mut f.car,&claim,200,&bplist::encode(&Value::dict(vec![("status",Value::int(-1))]))).await;
            } else {
                f.modes_reply(1).await;
                let setup=read(&mut f.car).await;assert!(setup.first_line.starts_with("SETUP "));
                reply(&mut f.car,&setup,400,&[]).await;
                f.teardown_reply().await;
            }
            assert!(task.await.unwrap().is_err());
            assert!(!f.session.activated.load(Ordering::Acquire));
            assert!(!f.session.params.pipeline.lock().record_accepted);
            let mut byte=[0];
            assert!(tokio::time::timeout(Duration::from_millis(30),f.car.read(&mut byte)).await.is_err());
        }
    }
    #[tokio::test]
    async fn mgu_box_latency_is_75_while_generic_and_nbt_remain_25() {
        for (bmw,nbt,expected) in [(true,false,75),(false,false,25),(true,true,25)] {
            let mut f=Fixture::new(Modes::initial()).await;
            let session=Arc::get_mut(&mut f.session).unwrap();
            session.params.bmw_capabilities=bmw;
            session.params.bmw_box_bootstrap=bmw;
            session.params.nbt_audio_first=nbt;
            let session=f.session.clone();
            let task=tokio::spawn(async move {session.open().await.err().unwrap()});
            let request=read(&mut f.car).await;
            let proposal=bplist::parse(&request.body).unwrap();
            assert_eq!(proposal.get("streams").unwrap().as_array().unwrap()[0]
                .get("latencyMs").and_then(Value::as_int),Some(expected));
            reply(&mut f.car,&request,400,&[]).await;
            assert!(task.await.unwrap().contains("400"));
        }
    }
    #[tokio::test]
    async fn screen_business_rejection_or_missing_port_never_opens_data_socket_or_marks_setup() {
        for reject_business in [true, false] {
            let mut f=Fixture::new(Modes::initial()).await;
            let session=f.session.clone();
            let task=tokio::spawn(async move { session.open().await.err().unwrap() });
            let setup=read(&mut f.car).await;
            assert_eq!(setup.first_line,"SETUP / RTSP/1.0");
            let proposal=bplist::parse(&setup.body).unwrap();
            assert_eq!(proposal.get("streams").unwrap().as_array().unwrap()[0]
                .get("latencyMs").and_then(Value::as_int),Some(75));
            let body=if reject_business {
                // A transport 200 and a plausible port must not override a business rejection.
                Value::dict(vec![("status",Value::int(-1)),("streams",Value::array(vec![Value::dict(vec![
                    ("type",Value::int(110)),("dataPort",Value::int(f.video.local_addr().unwrap().port() as i64)),
                ])]))])
            } else { Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(110))])]))]) };
            reply(&mut f.car,&setup,200,&bplist::encode(&body)).await;
            let error=task.await.unwrap();
            assert!(error.contains(if reject_business { "business rejection" } else { "dataPort" }));
            assert!(!f.session.params.pipeline.lock().screen_setup_accepted);
            assert!(tokio::time::timeout(Duration::from_millis(60),f.video.accept()).await.is_err());
        }
    }

    #[tokio::test]
    async fn id9_hid_initialization_uses_event_channel_before_screen_setup() {
        let mut f=Fixture::new(Modes::id9_initial(&Value::dict(vec![]),true,"ID9 test")).await;
        enable_id9(&mut f);
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car_event,_)=listener.accept().await.unwrap();let(r,w)=socket.into_split();
        let(event,_)=RtspChannel::spawn(r,w,None);
        let session=Arc::get_mut(&mut f.session).unwrap();
        session.bootstrap_event=Some(event);
        session.bootstrap_hids=vec![Value::str("150B"),Value::str("150C"),Value::str("150D")];
        let session=f.session.clone();let modes=f.modes.clone();
        let task=tokio::spawn(async move {session.bootstrap_before_record(&modes).await});
        f.modes_reply(2).await;f.modes_reply(1).await;
        let mut buffer=Vec::new();
        let mut received=Vec::new();
        while received.len()<3 {
            let mut chunk=[0u8;4096];
            let n=tokio::time::timeout(Duration::from_secs(2),car_event.read(&mut chunk)).await.unwrap().unwrap();
            assert!(n>0);buffer.extend_from_slice(&chunk[..n]);
            for request in crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap() {
                let body=bplist::parse(&request.body).unwrap();
                assert_eq!(body.get("type").and_then(Value::as_str),Some("hidSetInputMode"));
                received.push(body.get("params").unwrap().get("uuid").and_then(Value::as_str).unwrap().to_owned());
                reply(&mut car_event,&request,200,&[]).await;
            }
        }
        assert_eq!(received,vec!["150B","150C","150D"]);
        let setup=read(&mut f.car).await;
        reply(&mut f.car,&setup,400,&[]).await;f.teardown_reply().await;
        assert!(task.await.unwrap().is_err());
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
        let streams = f.session.resource_teardown(Some(100)).await;
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
    async fn mercedes_rejected_screen_can_retry_without_repeating_record_or_reviving_lost_grant() {
        for revoked in [false,true] {
            let mut f=Fixture::new(Modes::initial()).await;
            {let session=Arc::get_mut(&mut f.session).unwrap();session.params.mbux_media_compat=true;session.params.bmw_box_bootstrap=false;}
            let control=f.session.control.clone();let record=tokio::spawn(async move {crate::airplay::record_session(&control).await});
            let request=read(&mut f.car).await;assert_eq!(request.first_line,"RECORD / RTSP/1.0");
            reply(&mut f.car,&request,200,&[]).await;record.await.unwrap().unwrap();
            let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
            reply(&mut f.car,&setup,400,&[]).await;f.teardown_reply().await;assert!(task.await.unwrap().is_err());
            if revoked {f.update(|m|borrow(m,100));}
            // Same reconciliation path used by the one pre-ready retry.
            let task=f.reconcile();f.modes_reply(if revoked {2}else{1}).await;
            if !revoked {
                let setup=read(&mut f.car).await;let(mut peer,id)=f.setup_reply(&setup).await;
                task.await.unwrap().unwrap();f.video_frame(&mut peer,id).await;
            } else {task.await.unwrap().unwrap();assert_eq!(f.session.params.pipeline.lock().video_stream,"WAIT_OWNERSHIP");}
            assert!(f.session.params.pipeline.lock().audio_ready);
            assert!(tokio::time::timeout(Duration::from_millis(30),f.car.read(&mut [0])).await.is_err());
        }
    }
    #[tokio::test]
    async fn mercedes_ready_retry_root_applies_only_to_exact_model_and_retry() {
        for model in ["MB_E-P-4", "MB_D-PP-4", ""] {
            let mut f = Fixture::new(Modes::initial()).await;
            let session = Arc::get_mut(&mut f.session).unwrap();
            session.params.mbux_media_compat = true;
            session.control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 7).unwrap()).unwrap();
            for retry in [false, true, false] {
                let session = f.session.clone();
                let modes = f.modes.clone();
                let task = tokio::spawn(async move {
                    if retry { session.reconcile_after_mbux_ready(&modes, model).await }
                    else { session.reconcile_resource(&modes).await }
                });
                f.modes_reply(1).await;
                let setup = read(&mut f.car).await;
                let target = if retry && model == "MB_E-P-4" { "/" } else { "rtsp://127.0.0.1/7" };
                assert_eq!(setup.first_line, format!("SETUP {target} RTSP/1.0"));
                reply(&mut f.car, &setup, 400, &[]).await;
                let teardown = read(&mut f.car).await;
                assert_eq!(teardown.first_line, "TEARDOWN rtsp://127.0.0.1/7 RTSP/1.0");
                reply(&mut f.car, &teardown, 200, &[]).await;
                assert!(task.await.unwrap().is_err());
            }
        }
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
        let modes=Arc::new(Mutex::new(Modes::initial()));let revision=modes.lock().screen_grant_generation();
        let (commands,rx)=mpsc::channel(2);let (source,video_rx)=mpsc::unbounded_channel();
        let (done,accepted)=tokio::sync::oneshot::channel();
        commands.send(ScreenCommand::InstallIfOwned {
            sender:crate::screen::ScreenSender::new(write,crate::screen::ScreenCipher::ChaCha([1;32]),1920,720,false),
            modes:modes.clone(),revision,reply:done,
        }).await.unwrap();
        {let mut m=modes.lock();borrow(&mut m,100);m.request_ui();assert!(m.screen_owned());}
        let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        let pump=tokio::spawn(video_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(PumpStat::default())),true,pipeline,rx,video_rx,false,30,None));
        assert!(!tokio::time::timeout(Duration::from_secs(1),accepted).await.unwrap().unwrap());
        let mut byte=[0];assert_eq!(peer.read(&mut byte).await.unwrap(),0);
        pump.abort();drop(source);
    }
    #[tokio::test]
    async fn cancelled_setup_never_installs_a_late_video_sender() {
        let mut f=Fixture::new(Modes::initial()).await;
        let task=f.reconcile();f.modes_reply(1).await;let setup=read(&mut f.car).await;
        task.abort();assert!(task.await.unwrap_err().is_cancelled());
        let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(110)),("dataPort",Value::int(f.video.local_addr().unwrap().port() as i64))])]))]));
        reply(&mut f.car,&setup,200,&body).await;
        assert!(tokio::time::timeout(Duration::from_millis(50),f.video.accept()).await.is_err());
    }
}
