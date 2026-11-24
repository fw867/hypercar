//! Loopback receiver tests exercising Controller, resource ACKs and real Opus UDP.
use super::{Controller,Input,MainCommand,Session};
use crate::{airplay::{AirplayParams,AudioCodec,SocketProvider},aux_setup::Kind,bplist::{self,Value},modes::Modes,rtsp::{RtspChannel,RtspMessage},speech_resource::SpeechResource};
use std::{sync::{Arc,atomic::AtomicBool},time::Duration};
use parking_lot::Mutex;
use tokio::{net::{TcpListener,TcpStream,UdpSocket},sync::mpsc,io::{AsyncReadExt,AsyncWriteExt}};

struct Factory;
impl SocketProvider for Factory {
    fn open_tcp(&self,_:&str,_:u16,_:u32)->Option<TcpStream>{None}
    fn open_udp(&self,host:&str,port:u16)->Option<UdpSocket>{
        let socket=std::net::UdpSocket::bind("127.0.0.1:0").ok()?;
        socket.connect((host,port)).ok()?;socket.set_nonblocking(true).ok()?;UdpSocket::from_std(socket).ok()
    }
}
struct Fixture {
    media: tokio::sync::watch::Receiver<crate::udp_keepalive::MediaState>,
    controller:Controller,input:Arc<Input>,peer:TcpStream,data:UdpSocket,sync:UdpSocket,
    commands:mpsc::UnboundedReceiver<&'static str>,fallback:mpsc::UnboundedReceiver<crate::engine::SourceEvent>,
    command_task:tokio::task::JoinHandle<()>,buffer:Vec<u8>,order:Vec<String>,
    shutdown:Arc<crate::shutdown::EngineShutdown>,
}
impl Drop for Fixture {fn drop(&mut self){self.command_task.abort();}}
impl Fixture {
    async fn new()->Self {
        Self::with_config(|_, _| {}).await
    }
    async fn with_config(configure: impl FnOnce(&mut AirplayParams, &mut Value))->Self {
        Self::with_codec(AudioCodec::Pcm, configure).await
    }
    async fn with_codec(codec: AudioCodec, configure: impl FnOnce(&mut AirplayParams, &mut Value))->Self {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(peer,_)=listener.accept().await.unwrap();
        let(r,w)=stream.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let modes=Arc::new(Mutex::new(Modes::initial()));
        let resource=Arc::new(SpeechResource::new(control.clone(),modes,Arc::new(AtomicBool::new(true))));
        let mut params=AirplayParams {
                wifi_mac_address:None,
            car_ip:"127.0.0.1".into(),airplay_port:7000,device_id:"test".into(),device_name:"Test phone".into(),identity_path:"unused".into(),
            receiver_id:None,receiver_model:None,receiver_previously_connected:true,enable_audio:true,audio_codec:"pcm".into(),video_codec:String::new(),bmw_capabilities:true,microphone_probe:None,keep_alive_stats:false,request_receiver_stats:false,
            head_unit_profile:"bmw_id85".into(),bmw_box_bootstrap:true,skip_bluetooth_handoff:false,mbux_screen_bootstrap:false,id9_screen_bootstrap:false,nbt_audio_first:false,mbux_media_compat:false,desktop_receiver:false,
            width:1920,height:720,touch_display:None,touch_rect:None,encoder_size:Arc::new(Mutex::new((1920,720))),touch_size:Arc::new(Mutex::new((1920,720))),
            carwith_display:None,carwith_media_rate:None,carwith_stretch:false,screen_resource_lifecycle:true,pipeline:Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        };
        let mut info=Value::dict(vec![("audioFormats",Value::array(vec![
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("speechRecognition")),("audioOutputFormats",Value::int(1<<29))]),
            Value::dict(vec![("type",Value::int(101)),("audioType",Value::str("default")),("audioOutputFormats",Value::int(1<<30))]),
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("media")),("audioOutputFormats",Value::int(1<<15))]),
        ]))]);
        let media=control.udp_media.subscribe();
        configure(&mut params, &mut info);
        let kinds = crate::airplay::independent_audio_kinds(&params, &info);
        let realtime_media_rebuild = crate::airplay::supports_realtime_media_rebuild(&params, &info, params.desktop_receiver);
        let session=Session{kinds,control,factory:Arc::new(Factory),params,shared:[9;32],info,resource,media_codec:Mutex::new(Some(codec)),receiver_audio_generation:Arc::default(),realtime_media_rebuild};
        let input=Arc::new(Input::default());let(command_tx,mut command_rx)=mpsc::unbounded_channel();
        let(event_tx,commands)=mpsc::unbounded_channel();let(fallback_tx,fallback)=mpsc::unbounded_channel();
        let command_task=tokio::spawn(async move {while let Some(command)=command_rx.recv().await {
            match command {
                MainCommand::Pause(reply)=>{event_tx.send("pause").unwrap();let _=reply.send(());}
                MainCommand::Install(out)=>{assert!(out.is_some(),"working PCM media must be restored");event_tx.send("install").unwrap();drop(out);}
                MainCommand::ReplaceRealtime { output, reply, .. } => {
                    assert_eq!(output.stream_type(), 102);
                    event_tx.send("replace").unwrap();
                    let _ = reply.send(());
                }
            }
        }});
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let controller=Controller::spawn(session,input.clone(),command_tx,fallback_tx,shutdown.clone());
        Self{media,controller,input,peer,data:UdpSocket::bind("127.0.0.1:0").await.unwrap(),sync:UdpSocket::bind("127.0.0.1:0").await.unwrap(),commands,fallback,command_task,buffer:Vec::new(),order:Vec::new(),shutdown}
    }
    async fn request(&mut self,expected:&str)->(RtspMessage,Value){
        let message=tokio::time::timeout(Duration::from_secs(3),async {loop {
            if let Some(m)=crate::rtsp::parse_rtsp_messages(&mut self.buffer).unwrap().into_iter().next(){return m;}
            let mut bytes=[0;4096];let n=self.peer.read(&mut bytes).await.unwrap();assert!(n>0);self.buffer.extend_from_slice(&bytes[..n]);
        }}).await.unwrap();
        let value=if message.body.is_empty() { Value::dict(vec![]) } else { bplist::parse(&message.body).unwrap() };
        let actual=if message.first_line == "RECORD / RTSP/1.0" { "record".into() }
        else if message.first_line.starts_with("POST /command ") {value.get("type").and_then(Value::as_str).unwrap().to_string()} else {
            let stream=&value.get("streams").unwrap().as_array().unwrap()[0];
            if message.first_line.starts_with("SETUP "){format!("setup:{}:{}",stream.get("type").unwrap().as_int().unwrap(),stream.get("audioType").unwrap().as_str().unwrap())}
            else {format!("teardown:{}",stream.get("type").unwrap().as_int().unwrap())}
        };
        assert_eq!(actual,expected,"unexpected request {}",message.first_line);self.order.push(actual);(message,value)
    }
    async fn ack(&mut self,message:&RtspMessage,body:Option<Value>){
        let bytes=body.map(|v|bplist::encode(&v)).unwrap_or_default();
        self.peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",message.cseq().unwrap(),bytes.len()).as_bytes()).await.unwrap();self.peer.write_all(&bytes).await.unwrap();
    }
    async fn reject(&mut self,message:&RtspMessage,status:u16){
        self.peer.write_all(format!("RTSP/1.0 {status} Rejected\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",message.cseq().unwrap()).as_bytes()).await.unwrap();
    }
    async fn setup_reply(&mut self,message:&RtspMessage,value:&Value){
        self.setup_reply_with_type(message,value,true).await;
    }
    async fn setup_reply_with_type(&mut self,message:&RtspMessage,value:&Value,include_type:bool){
        let request=&value.get("streams").unwrap().as_array().unwrap()[0];
        let mut fields=vec![("dataPort",Value::int(self.data.local_addr().unwrap().port() as i64)),("controlPort",Value::int(self.sync.local_addr().unwrap().port() as i64))];
        if include_type { fields.push(("type",request.get("type").unwrap().clone())); }
        for field in ["streamConnectionID","audioFormat","audioType"]{if let Some(value)=request.get(field){fields.push((field,value.clone()));}}
        self.ack(message,Some(Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))).await;
    }
    fn source(&self,kind:Kind){
        self.input.init(kind,16000,1);
        assert_eq!(self.input.push(kind,16000,1,&4000i16.to_le_bytes().repeat(1600)),super::Route::Independent);
        self.input.stop(kind);
    }
    async fn opus_packet(&self,typ:u8){
        let mut bytes=[0;2048];let n=tokio::time::timeout(Duration::from_secs(2),self.data.recv(&mut bytes)).await.unwrap().unwrap();
        assert!(n>36,"encrypted Opus packet must have payload/tag/counter");assert_eq!(bytes[0],0x80);assert_eq!(bytes[1]&127,typ);
    }
    async fn stopped(&mut self){
        assert!(self.controller.shutdown().await.is_empty());assert!(self.fallback.try_recv().is_err());
    }
}

#[tokio::test]
async fn id9_unborrow_only_switches_after_usable_setup_and_then_records() {
    use std::sync::atomic::Ordering;
    let mut f = Fixture::with_codec(AudioCodec::Aac, |p, _| {
        p.head_unit_profile = "bmw_id9".into();
        p.pipeline.lock().main_audio_stream_type = Some(102);
    }).await;
    let control = f.controller.session.control.clone();
    let trigger = |generation| {
        control.audio_unborrow_generation.store(generation, Ordering::Release);
        control.audio_unborrow_wake.notify_one();
    };
    trigger(1);
    let (setup, _) = f.request("setup:102:media").await;
    f.reject(&setup, 400).await;
    trigger(2);
    let (setup, _) = f.request("setup:102:media").await;
    assert!(f.commands.try_recv().is_err());
    // The 200 allocated a stream but its unusable body must not cause a type-wide TEARDOWN.
    f.ack(&setup, None).await;
    trigger(3);
    let (setup, offer) = f.request("setup:102:media").await;
    assert!(f.commands.try_recv().is_err());
    f.setup_reply(&setup, &offer).await;
    let (record, _) = f.request("record").await;
    assert!(f.commands.try_recv().is_err());
    f.ack(&record, None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("replace"));
    trigger(3);
    let mut bytes = [0; 64];
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read(&mut bytes)).await.is_err());
}

#[tokio::test]
async fn id9_rejected_or_timed_out_record_keeps_old_sender_and_ignores_late_200() {
    let mut f = id9_aac_fixture().await;
    let rejected = pending_id9_record(&mut f, 1).await;
    f.reject(&rejected, 455).await;
    let expired = pending_id9_record(&mut f, 2).await;
    // The peer uses real TCP sockets. Let the actual RECORD deadline expire.
    tokio::time::sleep(Duration::from_secs(6)).await;
    let fresh = pending_id9_record(&mut f, 3).await;
    f.ack(&expired, None).await;
    assert!(tokio::time::timeout(Duration::from_millis(50), f.commands.recv()).await.is_err());
    f.ack(&fresh, None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("replace"));
}

async fn id9_aac_fixture() -> Fixture {
    Fixture::with_codec(AudioCodec::Aac, |p, info| {
        p.head_unit_profile = "bmw_id9".into();
        p.pipeline.lock().main_audio_stream_type = Some(102);
        let Value::Dict(fields) = info else { panic!("fixture /info must be a dictionary"); };
        fields.push((Value::str("audioLatencies"), Value::array(vec![Value::dict(vec![
            ("type", Value::int(102)), ("audioType", Value::str("media")),
            ("outputLatencyMicros", Value::int(325_000)),
        ])])));
    }).await
}

async fn pending_id9_record(f: &mut Fixture, generation: u64) -> RtspMessage {
    use std::sync::atomic::Ordering;
    f.controller.session.control.audio_unborrow_generation.store(generation, Ordering::Release);
    f.controller.session.control.audio_unborrow_wake.notify_one();
    let (setup, offer) = f.request("setup:102:media").await;
    let stream = &offer.get("streams").unwrap().as_array().unwrap()[0];
    assert_eq!(stream.get("audioLatencyMs").and_then(Value::as_int), Some(325));
    f.setup_reply(&setup, &offer).await;
    let (record, _) = f.request("record").await;
    assert!(f.commands.try_recv().is_err());
    record
}

#[tokio::test]
async fn non_id9_profiles_rebuild_realtime_audio_unless_receiver_uses_single_record() {
    use std::sync::atomic::Ordering;
    for (profile, model, manufacturer, can_rebuild) in [
        ("bmw_id85", Some("MGU-55"), None, true),
        ("mercedes_mbux", Some("MB_E-P-4"), None, false),
        ("mercedes_mbux", Some("MB_D-PP-4"), None, false),
        ("mercedes_mbux", Some("Future model"), None, false),
        ("mercedes_mbux", None, None, false),
        ("auto", Some("MB_E-P-4"), None, false),
        ("auto", None, Some("Mercedes-Benz"), false),
        ("auto", Some("Other car"), None, true),
    ] {
        let mut f = Fixture::with_codec(AudioCodec::Aac, |p, info| {
            p.head_unit_profile = profile.into();
            if let Value::Dict(fields) = info {
                for (key, value) in [("model", model), ("manufacturer", manufacturer)] {
                    if let Some(value) = value {
                        fields.push((Value::str(key), Value::str(value)));
                    }
                }
            }
            p.pipeline.lock().main_audio_stream_type = Some(102);
        }).await;
        let control = f.controller.session.control.clone();
        control.audio_unborrow_generation.store(1, Ordering::Release);
        control.audio_unborrow_wake.notify_one();
        if can_rebuild {
            let (setup, offer) = f.request("setup:102:media").await;
            f.setup_reply(&setup, &offer).await;
            let (record, _) = f.request("record").await;
            assert!(f.commands.try_recv().is_err());
            f.ack(&record, None).await;
            assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("replace"));
        } else {
            assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err());
        }
        f.stopped().await;
    }
}

#[tokio::test]
async fn speech_lease_supersedes_pending_media_record() {
    use std::sync::atomic::Ordering;
    let mut f = Fixture::with_codec(AudioCodec::Aac, |p, _| {
        p.head_unit_profile = "bmw_id85".into();
        p.pipeline.lock().main_audio_stream_type = Some(102);
    }).await;
    let control = f.controller.session.control.clone();
    control.audio_unborrow_generation.store(1, Ordering::Release);
    control.audio_unborrow_wake.notify_one();
    let (setup, offer) = f.request("setup:102:media").await;
    f.setup_reply(&setup, &offer).await;
    let (record, _) = f.request("record").await;
    {
        let mut modes = f.controller.session.resource.modes.lock();
        let (speech, lease) = modes.speech_begin(false).unwrap();
        assert!(modes.commit_aux(&speech));
        let end = modes.speech_end(&lease).unwrap();
        assert!(modes.commit_aux(&end));
    }
    f.ack(&record, None).await;
    assert!(tokio::time::timeout(Duration::from_millis(60), f.commands.recv()).await.is_err());
}

#[tokio::test]
async fn id9_take_then_return_discards_pending_record_even_when_phone_owns_audio_again() {
    let mut f = id9_aac_fixture().await;
    let old = pending_id9_record(&mut f, 1).await;
    let session = &f.controller.session;
    let mut modes = session.resource.modes.lock();
    for transfer in [1, 2] {
        let mut fields = vec![("resourceID", Value::int(2)), ("transferType", Value::int(transfer))];
        if transfer == 1 {
            fields.extend([("transferPriority", Value::int(500)), ("takeConstraint", Value::int(100)), ("borrowConstraint", Value::int(100))]);
        }
        let command = Value::dict(vec![("type", Value::str("changeModes")), ("params",
            Value::dict(vec![("resources", Value::array(vec![Value::dict(fields)]))]))]);
        modes.response(&command, &session.receiver_audio_generation).unwrap();
        assert_eq!(modes.audio_owned(), transfer == 2);
    }
    drop(modes);
    f.ack(&old, None).await;
    assert!(tokio::time::timeout(Duration::from_millis(50), f.commands.recv()).await.is_err());
    let fresh = pending_id9_record(&mut f, 2).await;
    f.ack(&fresh, None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("replace"));
}

#[tokio::test]
async fn id9_same_address_reinvite_discards_old_record_even_with_matching_cseq() {
    let mut old = id9_aac_fixture().await;
    let old_record = pending_id9_record(&mut old, 1).await;
    old.shutdown.request(false);
    old.stopped().await;
    let mut next = id9_aac_fixture().await;
    let next_record = pending_id9_record(&mut next, 1).await;
    assert_eq!(old_record.cseq(), next_record.cseq());
    old.ack(&old_record, None).await;
    assert!(old.commands.try_recv().is_err());
    assert!(tokio::time::timeout(Duration::from_millis(50), next.commands.recv()).await.is_err());
    next.ack(&next_record, None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), next.commands.recv()).await.unwrap(), Some("replace"));
}

#[tokio::test]
async fn failed_buffered_teardown_never_starts_a_second_main_stream() {
    let mut f = Fixture::with_codec(AudioCodec::BufferedAac, |params, _| {
        params.head_unit_profile = "bmw_id9".into();
        params.audio_codec = "auto".into();
    }).await;
    f.input.buffered_failed.notify_one();
    let (teardown, _) = f.request("teardown:103").await;
    f.reject(&teardown, 500).await;
    let (retry, _) = f.request("teardown:103").await;
    assert_ne!(teardown.cseq(), retry.cseq());
    f.reject(&retry, 500).await;
    assert!(tokio::time::timeout(Duration::from_millis(80), f.peer.read_u8()).await.is_err());
    assert_eq!(f.controller.media_stream_type(), Some(103));
    f.stopped().await;
}

#[cfg(target_os="android")]
async fn mbux_buffered_fixture() -> Fixture {
    let f = Fixture::with_codec(AudioCodec::BufferedAac, |p, info| {
        p.head_unit_profile = "mercedes_mbux".into();
        p.mbux_media_compat = true;
        p.audio_codec = "auto".into();
        *info = Value::dict(vec![("audioFormats", Value::array(vec![Value::dict(vec![
            ("type", Value::int(102)), ("audioType", Value::str("media")),
            ("audioOutputFormats", Value::int(1 << 23)),
        ])]))]);
    }).await;
    let control = &f.controller.session.control;
    control.udp_media.set(110, true);
    control.udp_media.set(103, true);
    f
}

#[cfg(target_os="android")]
#[tokio::test]
async fn buffered_cleanup_timeout_requires_new_ack_before_102_and_preserves_active_screen() {
    let mut f = mbux_buffered_fixture().await;
    f.input.buffered_failed.notify_one();
    let (first, _) = f.request("teardown:103").await;
    let (retry, _) = f.request("teardown:103").await;
    assert_ne!(first.cseq(), retry.cseq());
    f.ack(&first, None).await;
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err());
    assert!(f.controller.session.control.buffered_cleanup_pending.load(std::sync::atomic::Ordering::Acquire));
    assert_eq!(f.controller.media_stream_type(), Some(103));
    f.ack(&retry, None).await;
    let (setup, offer) = f.request("setup:102:media").await;
    assert!(!f.media.borrow().idle(), "the screen remains an active media stream before 102 SETUP is acknowledged");
    f.setup_reply(&setup, &offer).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err());
    f.stopped().await;
}

#[cfg(target_os="android")]
#[tokio::test]
async fn buffered_cleanup_retry_reply_stays_on_its_session_during_same_address_reinvite() {
    let mut old = mbux_buffered_fixture().await;
    old.input.buffered_failed.notify_one();
    let (first, _) = old.request("teardown:103").await;
    old.reject(&first, 500).await;
    let (old_retry, _) = old.request("teardown:103").await;
    old.shutdown.request(false);
    old.ack(&old_retry, None).await;
    old.stopped().await;
    assert!(old.commands.try_recv().is_err());
    assert!(tokio::time::timeout(Duration::from_millis(60), old.peer.read_u8()).await.is_err());

    let mut next = mbux_buffered_fixture().await;
    next.input.buffered_failed.notify_one();
    let (first, _) = next.request("teardown:103").await;
    next.reject(&first, 500).await;
    let (next_retry, _) = next.request("teardown:103").await;
    assert_eq!(old_retry.cseq(), next_retry.cseq());
    old.ack(&old_retry, None).await;
    assert!(tokio::time::timeout(Duration::from_millis(60), next.peer.read_u8()).await.is_err());
    assert!(next.controller.session.control.buffered_cleanup_pending.load(std::sync::atomic::Ordering::Acquire));
    assert!(!next.media.borrow().idle());
    next.ack(&next_retry, None).await;
    let (setup, offer) = next.request("setup:102:media").await;
    next.setup_reply(&setup, &offer).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), next.commands.recv()).await.unwrap(), Some("install"));
    next.stopped().await;
}

#[cfg(target_os="android")]
#[tokio::test]
async fn failed_buffered_playback_recovers_only_main_audio_with_aac_102() {
    let mut f = Fixture::with_codec(AudioCodec::BufferedAac, |params, info| {
        params.head_unit_profile = "bmw_id9".into();
        params.audio_codec = "auto".into();
        *info = Value::dict(vec![("audioFormats", Value::array(vec![Value::dict(vec![
            ("type", Value::int(102)), ("audioType", Value::str("media")),
            ("audioOutputFormats", Value::int(1 << 23)),
        ])]))]);
    }).await;
    f.input.buffered_failed.notify_one();
    let (teardown, _) = f.request("teardown:103").await;
    f.ack(&teardown, None).await;
    let (setup, request) = f.request("setup:102:media").await;
    f.setup_reply(&setup, &request).await;
    assert_eq!(f.commands.recv().await, Some("install"));
    assert_eq!(f.controller.media_stream_type(), Some(102));
    f.stopped().await;
}

#[tokio::test]
async fn failed_buffered_playback_uses_declared_pcm_when_no_aac_is_declared() {
    let mut f = Fixture::with_codec(AudioCodec::BufferedAac, |params, _| {
        params.audio_codec = "auto".into();
    }).await;
    f.input.buffered_failed.notify_one();
    let (teardown, _) = f.request("teardown:103").await;
    assert!(f.commands.try_recv().is_err());
    f.ack(&teardown, None).await;
    let (setup, offer) = f.request("setup:100:media").await;
    assert!(f.commands.try_recv().is_err());
    f.setup_reply(&setup, &offer).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    assert_eq!(f.controller.media_stream_type(), Some(100));
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err(), "fallback must not send another RECORD");
    f.stopped().await;
}

#[cfg(target_os="android")]
#[tokio::test]
async fn failed_buffered_playback_tries_preferred_aac_then_accepts_untyped_pcm_for_detected_diplay() {
    let media_format = |kind, bits| Value::dict(vec![
        ("type", Value::int(kind)), ("audioType", Value::str("media")),
        ("audioOutputFormats", Value::int(bits)),
    ]);
    let mut f = Fixture::with_codec(AudioCodec::BufferedAac, |params, info| {
        params.audio_codec = "aac".into();
        assert!(!params.desktop_receiver, "software receiver should be detected from /info only");
        *info = Value::dict(vec![
            ("name", Value::str("DiPlay")),
            ("audioFormats", Value::array(vec![media_format(100, 1 << 15), media_format(102, 1 << 23)])),
        ]);
    }).await;
    f.input.buffered_failed.notify_one();
    let (teardown, _) = f.request("teardown:103").await;
    f.ack(&teardown, None).await;
    let (aac, _) = f.request("setup:102:media").await;
    f.reject(&aac, 400).await;
    let (pcm, offer) = f.request("setup:100:media").await;
    f.setup_reply_with_type(&pcm, &offer, false).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    assert_eq!(f.controller.media_stream_type(), Some(100));
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err(), "fallback must not send another RECORD");
    f.stopped().await;
}

async fn speech_handoff(fail_first_restore:bool){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,v)=f.request("modesChanged").await;
    let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(1));
    f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:speechRecognition").await;f.setup_reply(&m,&v).await;
    f.opus_packet(100).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
    assert_eq!(apps[0].get("entity").and_then(Value::as_int),Some(0));assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(-1));f.ack(&m,None).await;
    if fail_first_restore {
        let(m,_)=f.request("setup:100:media").await;
        f.peer.write_all(format!("RTSP/1.0 400 Rejected\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",m.cseq().unwrap()).as_bytes()).await.unwrap();
    }
    let(m,v)=f.request("setup:100:media").await;assert_eq!(v.get("streams").unwrap().as_array().unwrap()[0].get("audioFormat").and_then(Value::as_int),Some(1<<15));
    assert!(f.commands.try_recv().is_err(),"media install must wait for SETUP ACK");f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert!(!f.media.borrow().idle());
    f.stopped().await;
    let mut expected=vec!["modesChanged","teardown:100","setup:100:speechRecognition","teardown:100","modesChanged","setup:100:media"];
    if fail_first_restore {expected.push("setup:100:media");}
    assert_eq!(f.order,expected);
}
#[tokio::test] async fn siri_main_stream_handoff_restores_pcm_only_after_speech_teardown_and_end_ack(){speech_handoff(false).await;}
#[tokio::test] async fn rejected_first_media_restore_retries_without_installing_an_empty_sender(){speech_handoff(true).await;}

fn id9_carwith(params: &mut AirplayParams, _: &mut Value) {
    params.head_unit_profile = "bmw_id9".into();
    params.id9_screen_bootstrap = true;
    params.carwith_media_rate = Some(tokio::sync::watch::channel(None).1);
}

#[tokio::test] async fn id9_requires_carwith_and_current_navigation_output_capability() {
    let mut f = Fixture::with_config(|p, info| {
        id9_carwith(p, info);
        let kinds = crate::airplay::independent_audio_kinds;
        assert_eq!(kinds(p, info), &[Kind::Navigation]);
        for (typ, label, field, mask) in [(100,"default","audioOutputFormats",1<<30),
            (101,"media","audioOutputFormats",1<<30),(101,"default","audioInputFormats",1<<30),
            (101,"default","audioOutputFormats",1<<29)] {
            let entry = Value::dict(vec![("type",Value::int(typ)),("audioType",Value::str(label)),(field,Value::int(mask))]);
            assert!(kinds(p,&Value::dict(vec![("audioFormats",Value::array(vec![entry]))])).is_empty());
        }
        p.carwith_media_rate = None;
        assert!(kinds(p, info).is_empty());
    }).await;
    f.stopped().await;
}

#[tokio::test] async fn navigation_owns_only_alt_stream_and_never_pauses_or_replaces_media(){
    navigation_duck_cycle(Fixture::new().await).await;
}

#[tokio::test] async fn id9_navigation_waits_for_pcm_then_restores_music_volume() {
    let mut f = Fixture::with_config(id9_carwith).await;
    f.input.init(Kind::Speech,16000,1);
    assert_eq!(f.input.push(Kind::Speech,16000,1,&[1;640]),super::Route::Mix);
    f.input.init(Kind::Navigation,16000,1);
    let mut wire = [0; 512];
    let read = tokio::time::timeout(Duration::from_millis(80), f.peer.read(&mut wire)).await;
    assert!(read.is_err(), "unexpected RTSP bytes before navigation PCM: {read:?} {:?}", String::from_utf8_lossy(&wire));
    navigation_duck_cycle(f).await;
}

async fn navigation_duck_cycle(mut f: Fixture) {
    f.source(Kind::Navigation);
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[2].get("entity").and_then(Value::as_int),Some(1));f.ack(&m,None).await;
    let(m,v)=f.request("duckAudio").await;
    assert_eq!(v.get("params").unwrap().get("volume"),Some(&Value::real(-12.0)));
    f.ack(&m,None).await;
    let(m,v)=f.request("setup:101:default").await;f.setup_reply(&m,&v).await;f.opus_packet(101).await;
    let(m,v)=f.request("unduckAudio").await;assert!(!f.media.borrow().idle());
    assert_eq!(v.get("params").unwrap().get("volume"),Some(&Value::real(0.2)));
    f.ack(&m,None).await;
    let(m,_)=f.request("teardown:101").await;assert!(f.media.borrow().idle());f.ack(&m,None).await;
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[2].get("entity").and_then(Value::as_int),Some(0));f.ack(&m,None).await;
    f.stopped().await;assert!(f.commands.try_recv().is_err());
    assert_eq!(f.order,["modesChanged","duckAudio","setup:101:default","unduckAudio","teardown:101","modesChanged"]);
}

#[tokio::test] async fn id9_rejected_navigation_setup_returns_pcm_to_mix_after_cleanup() {
    id9_setup_failure(false).await;
}

#[tokio::test] async fn id9_deferred_navigation_release_keeps_pcm_until_cleanup_ack() {
    id9_setup_failure(true).await;
}

async fn id9_setup_failure(reject_first_release: bool) {
    let mut f = Fixture::with_config(id9_carwith).await;
    f.input.init(Kind::Navigation,48000,2);
    let pcm = 3000i16.to_le_bytes().repeat(1920);
    assert_eq!(f.input.push(Kind::Navigation,48000,2,&pcm),super::Route::Independent);
    let (m,_) = f.request("modesChanged").await; f.ack(&m,None).await;
    let (m,_) = f.request("duckAudio").await; f.ack(&m,None).await;
    let (m,_) = f.request("setup:101:default").await; f.reject(&m,400).await;
    for command in ["unduckAudio", "teardown:101", "modesChanged"] {
        if command == "modesChanged" && reject_first_release {
            let (m,_) = f.request(command).await;
            f.reject(&m,403).await;
        }
        let (m,_) = f.request(command).await;
        assert!(f.fallback.try_recv().is_err(), "cannot mix before cleanup ACK");
        f.ack(&m,None).await;
    }
    let event = tokio::time::timeout(Duration::from_secs(1), f.fallback.recv()).await.unwrap();
    assert!(matches!(event,Some(crate::engine::SourceEvent::AuxFallback{kind:Kind::Navigation,samples}) if samples == vec![3000;1920]));
    assert_eq!(f.input.push(Kind::Navigation,48000,2,&pcm),super::Route::Mix);
    assert!(f.commands.try_recv().is_err());
    f.stopped().await;
}

#[tokio::test] async fn accepted_unusable_speech_setup_cleans_once_then_restores_media(){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,_)=f.request("setup:100:speechRecognition").await;
    f.ack(&m,Some(Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(100)),("dataPort",Value::int(0))])]))]))).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    // A second TEARDOWN would be a 404 on a real receiver: expect end modes now.
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert!(matches!(tokio::time::timeout(Duration::from_secs(1),f.fallback.recv()).await.unwrap(),Some(crate::engine::SourceEvent::AuxFallback{kind:Kind::Speech,..})));
    f.stopped().await;
}

async fn completed_speech_before_end_ack()->Fixture {
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:speechRecognition").await;f.setup_reply(&m,&v).await;f.opus_packet(100).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    f
}

#[tokio::test] async fn rejected_speech_end_retries_before_releasing_the_paused_media_sender(){
    let mut f=completed_speech_before_end_ack().await;
    let(m,v)=f.request("modesChanged").await;
    let first=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0].clone();
    assert_eq!(first.get("entity").and_then(Value::as_int),Some(0));
    f.reject(&m,403).await;
    let(m,v)=f.request("modesChanged").await;
    assert!(f.commands.try_recv().is_err(),"end refusal must keep media paused");
    let retried=&v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0];
    assert_eq!(retried,&first,"end retry must release speech, never republish Speaking");
    f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    f.stopped().await;
    assert_eq!(f.order,["modesChanged","teardown:100","setup:100:speechRecognition","teardown:100","modesChanged","modesChanged","setup:100:media"]);
}

#[tokio::test] async fn exhausted_restore_retries_resume_on_fresh_media_restart_without_replaying_speech(){
    let mut f=completed_speech_before_end_ack().await;
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    let mut refusals=0;
    loop {
        // Allow all bounded automatic recovery attempts to finish, then prove that
        // a new source media event wakes the pending recovery independently.
        if refusals>=2 && tokio::time::timeout(Duration::from_millis(1600),f.peer.readable()).await.is_err(){break;}
        let(m,_)=f.request("setup:100:media").await;f.reject(&m,400).await;refusals+=1;
        assert!(refusals<=12,"media recovery must remain bounded");
        assert!(f.commands.try_recv().is_err(),"refused restore cannot install an empty sender");
    }
    assert!(refusals>=2);
    f.input.media_restart();
    let(m,v)=f.request("setup:100:media").await;f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert_eq!(f.order.iter().filter(|s|s.as_str()=="setup:100:speechRecognition").count(),1,"media restart cannot replay the completed speech epoch");
    assert_eq!(f.order.iter().filter(|s|s.as_str()=="modesChanged").count(),2,"media-only recovery cannot reclaim old speech resources");
    f.stopped().await;
}

#[tokio::test] async fn shutdown_during_unanswered_speech_setup_reports_main_stream_for_final_teardown(){
    let mut f=Fixture::new().await;f.source(Kind::Speech);
    let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(_unanswered,_)=f.request("setup:100:speechRecognition").await;
    let start=tokio::time::Instant::now();
    let pending=tokio::time::timeout(Duration::from_secs(5),f.controller.shutdown()).await.unwrap();
    assert_eq!(pending,vec![100],"in-flight receiver allocation must survive worker cancellation into final TEARDOWN");
    assert!(start.elapsed()>=Duration::from_secs(3));
    assert!(f.commands.try_recv().is_err(),"shutdown must not install a replacement media sender");
}
