//! Loopback receiver tests exercising Controller, resource ACKs and real Opus UDP.
use super::{Controller,Input,MainCommand,Session};
use crate::{airplay::{AirplayParams,AudioCodec,SocketProvider},aux_setup::Kind,bplist::{self,Value},modes::Modes,rtsp::{RtspChannel,RtspMessage},speech_resource::SpeechResource};
use std::{sync::{Arc,atomic::AtomicBool},time::Duration};
use parking_lot::Mutex;
use tokio::{net::{TcpListener,TcpStream,UdpSocket},sync::mpsc,io::{AsyncReadExt,AsyncWriteExt}};

struct Factory { receive: bool }
impl SocketProvider for Factory {
    fn open_tcp(&self,_:&str,_:u16,_:u32)->Option<TcpStream>{None}
    fn open_udp(&self,host:&str,port:u16)->Option<UdpSocket>{
        let socket=std::net::UdpSocket::bind("127.0.0.1:0").ok()?;
        if port != 0 || !self.receive { socket.connect((host,port)).ok()?; }
        socket.set_nonblocking(true).ok()?;UdpSocket::from_std(socket).ok()
    }
}
struct Fixture {
    media: tokio::sync::watch::Receiver<crate::udp_keepalive::MediaState>,
    controller:Controller,input:Arc<Input>,peer:TcpStream,data:UdpSocket,sync:UdpSocket,
    commands:mpsc::UnboundedReceiver<&'static str>,fallback:mpsc::UnboundedReceiver<crate::engine::SourceEvent>,
    command_task:tokio::task::JoinHandle<()>,buffer:Vec<u8>,order:Vec<String>,
    shutdown:Arc<crate::shutdown::EngineShutdown>,
    installed_rate:Arc<std::sync::atomic::AtomicU32>,
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
        let factory=Arc::new(Factory { receive: params.microphone_probe.is_some() });
        let session=Session{kinds,control,factory,params,shared:[9;32],info,resource,media_codec:Mutex::new(Some(codec)),receiver_audio_generation:Arc::default(),realtime_media_rebuild};
        let input=Arc::new(Input::default());let(command_tx,mut command_rx)=mpsc::unbounded_channel();
        let(event_tx,commands)=mpsc::unbounded_channel();let(fallback_tx,fallback)=mpsc::unbounded_channel();
        let installed_rate=Arc::new(std::sync::atomic::AtomicU32::new(0));
        let observed_rate=installed_rate.clone();
        let command_task=tokio::spawn(async move {while let Some(command)=command_rx.recv().await {
            match command {
                MainCommand::Pause { reply, .. }=>{event_tx.send("pause").unwrap();let _=reply.send(None);}
                MainCommand::Install(out)=>{assert!(out.is_some(),"working PCM media must be restored");
                    observed_rate.store(out.as_ref().unwrap().sample_rate(),std::sync::atomic::Ordering::Release);
                    event_tx.send("install").unwrap();drop(out);}
                MainCommand::ReplaceRealtime { output, reply, .. } => {
                    assert_eq!(output.stream_type(), 102);
                    event_tx.send("replace").unwrap();
                    let _ = reply.send(());
                }
            }
        }});
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let controller=Controller::spawn(session,input.clone(),command_tx,fallback_tx,shutdown.clone());
        Self{media,controller,input,peer,data:UdpSocket::bind("127.0.0.1:0").await.unwrap(),sync:UdpSocket::bind("127.0.0.1:0").await.unwrap(),commands,fallback,command_task,buffer:Vec::new(),order:Vec::new(),shutdown,installed_rate}
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
async fn shared_main_microphone_requires_an_explicit_start_and_restores_media_after_acknowledged_cleanup() {
    shared_microphone_handoff(SharedSetup::Audio).await;
}

#[tokio::test]
async fn rejected_shared_microphone_transport_restores_media_without_tearing_down_a_missing_stream() {
    for status in [400, 451, 461] {
        shared_microphone_handoff(SharedSetup::Rejected(status)).await;
    }
}

#[tokio::test]
async fn cancelling_shared_microphone_setup_waits_for_the_transaction_before_teardown() {
    shared_microphone_handoff(SharedSetup::Cancelled).await;
}

#[tokio::test]
async fn diplay_shared_microphone_latches_the_first_authenticated_uplink_port() {
    shared_microphone_handoff(SharedSetup::DiPlay).await;
}

#[tokio::test]
async fn shared_microphone_start_waits_for_an_existing_media_transition() {
    shared_microphone_handoff(SharedSetup::Busy).await;
}

#[tokio::test]
async fn shared_microphone_recovers_after_lost_ack_and_matching_stream_absent() {
    shared_microphone_handoff(SharedSetup::Absent).await;
}

#[tokio::test]
async fn shared_microphone_exhausts_cleanup_when_absence_is_not_scoped() {
    shared_microphone_handoff(SharedSetup::Unknown).await;
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SharedSetup { Audio, Rejected(u16), Cancelled, DiPlay, Busy, Absent, Unknown }

fn shared_input_absent(id: i64) -> Value {
    Value::dict(vec![("status", Value::int(-6727)), ("streams", Value::array(vec![Value::dict(vec![
        ("type", Value::int(100)), ("streamConnectionID", Value::int(id)), ("status", Value::int(-6727)),
    ])]))])
}

async fn shared_microphone_handoff(outcome: SharedSetup) {
    let probe = Arc::new(crate::mic_probe::ProbeControl::for_carwith());
    let mut f = Fixture::with_config(|params, info| {
        params.microphone_probe = Some(probe.clone());
        params.pipeline.lock().main_audio_stream_type = Some(100);
        let Value::Dict(fields) = info else { panic!("/info dictionary"); };
        if outcome == SharedSetup::DiPlay { fields.push((Value::str("model"), Value::str("DiPlay"))); }
        let (_, Value::Array(formats)) = fields.iter_mut().find(|(key, _)| key.as_str() == Some("audioFormats")).unwrap() else { panic!("audioFormats array"); };
        formats.push(Value::dict(vec![
            ("type", Value::int(100)), ("audioType", Value::str("speechRecognition")),
            ("audioInputFormats", Value::int(16)), ("audioOutputFormats", Value::int(16)),
        ]));
    }).await;
    let sink = probe.carwith.as_ref().unwrap();
    let mut byte = [0];
    assert!(tokio::time::timeout(Duration::from_millis(30), f.peer.read(&mut byte)).await.is_err());
    let transition = if outcome == SharedSetup::Busy {
        Some(f.controller.stop.main_transition.clone().lock_owned().await)
    } else { None };
    probe.request(true);
    if transition.is_some() {
        assert!(tokio::time::timeout(Duration::from_millis(50), f.peer.read(&mut byte)).await.is_err());
    }
    drop(transition);
    let (m, modes) = f.request("modesChanged").await;
    let app = &modes.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0];
    assert_eq!(app.get("speechMode").and_then(Value::as_int), Some(2));
    f.ack(&m, None).await;
    let (m, _) = f.request("teardown:100").await;
    assert_eq!(f.commands.try_recv().unwrap(), "pause");
    f.ack(&m, None).await;
    let (m, setup) = f.request("setup:100:speechRecognition").await;
    let stream = &setup.get("streams").unwrap().as_array().unwrap()[0];
    assert_eq!(stream.get("input").and_then(Value::as_bool), Some(true));
    assert_eq!(stream.get("audioFormat").and_then(Value::as_int), Some(16));
    let id = stream.get("streamConnectionID").and_then(Value::as_int).unwrap();
    let port = stream.get("dataPort").and_then(Value::as_int).unwrap() as u16;
    if let SharedSetup::Rejected(status) = outcome {
        f.reject(&m, status).await;
    } else {
        if matches!(outcome, SharedSetup::Cancelled | SharedSetup::Busy | SharedSetup::Absent | SharedSetup::Unknown) {
            probe.request(false);
            assert!(tokio::time::timeout(Duration::from_millis(30), f.peer.read(&mut byte)).await.is_err());
        }
        f.setup_reply(&m, &setup).await;
    }

    if matches!(outcome, SharedSetup::Audio | SharedSetup::DiPlay) {
    let key = crate::mic_probe::input_key(&[9; 32], id as u64);
    let packet = |kind, sequence: u8, key: &[u8;32]| {
        let mut wire = vec![0x80, kind, 0, sequence, 0, 0, 0, 0, 0, 0, 0, 0];
        let payload = 1234i16.to_be_bytes().repeat(512);
        wire.extend(crate::crypto::chacha_seal(key, &crate::crypto::nonce64(u64::from(sequence)), &payload, &[0;8]));
        wire.extend(u64::from(sequence).to_le_bytes());
        wire
    };
    let address = ("127.0.0.1", port);
    if outcome == SharedSetup::Audio {
        f.sync.send_to(&packet(100, 1, &key), address).await.unwrap(); // Wrong sender port.
    }
    f.data.send_to(&packet(107, 1, &key), address).await.unwrap();
    f.data.send_to(&packet(100, 1, &[0; 32]), address).await.unwrap();
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert_eq!(sink.queued_bytes(), 0);
    let uplink = if outcome == SharedSetup::DiPlay { &f.sync } else { &f.data };
    uplink.send_to(&packet(100, 1, &key), address).await.unwrap();
    tokio::time::timeout(Duration::from_secs(1), async {
        while sink.queued_bytes() == 0 { tokio::time::sleep(Duration::from_millis(2)).await; }
    }).await.unwrap();
    assert_eq!(sink.queued_bytes(), 1024);
    if outcome == SharedSetup::DiPlay {
        f.data.send_to(&packet(100, 2, &key), address).await.unwrap();
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(sink.queued_bytes(), 1024, "an established sender cannot migrate to another port");
    }

    probe.request(false);
    assert_eq!(sink.queued_bytes(), 0);
    if outcome == SharedSetup::Audio {
    f.input.init(Kind::Speech, 48000, 2);
    let mut reply = 4000i16.to_le_bytes().repeat(9_600);
    reply.extend(2000i16.to_le_bytes().repeat(57_600));
    assert_eq!(f.input.push(Kind::Speech, 48000, 2, &reply), super::Route::Independent);
    f.input.stop(Kind::Speech);
    assert_eq!(f.input.lanes[0].lock().samples.front(), Some(&4000), "preserve the start of the queued assistant reply");
    for _ in 0..2 {
        let (m, cleanup) = f.request("teardown:100").await;
        let rows = cleanup.get("streams").unwrap().as_array().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].get("streamConnectionID").and_then(Value::as_int), Some(id));
        assert!(f.commands.try_recv().is_err());
        f.reject(&m, 500).await;
    }
    assert!(tokio::time::timeout(Duration::from_millis(150), f.peer.read(&mut byte)).await.is_err());
    assert!(f.input.independent(Kind::Speech), "assistant reply must wait for the microphone teardown ACK");
    f.input.media_restart();
    }
    }
    if matches!(outcome, SharedSetup::Absent | SharedSetup::Unknown) {
        let _lost_ack = f.request("teardown:100").await;
        let (m, _) = f.request("teardown:100").await;
        if outcome == SharedSetup::Unknown {
            f.reject(&m, 454).await;
            let (r, _) = f.request("teardown:100").await;
            f.ack(&r, Some(shared_input_absent(id + 1))).await;
            tokio::time::timeout(Duration::from_secs(1), async {
                while !f.controller.session.control.is_closed() { tokio::time::sleep(Duration::from_millis(2)).await; }
            }).await.expect("unresolved stream cleanup must end the old control channel");
            assert!(f.commands.try_recv().is_err(), "never reopen the main stream over an unverified slot");
            return;
        }
        f.ack(&m, Some(shared_input_absent(id))).await;
    }
    if !matches!(outcome, SharedSetup::Rejected(_) | SharedSetup::Absent) {
        let (m, cleanup) = f.request("teardown:100").await;
        assert_eq!(cleanup.get("streams").unwrap().as_array().unwrap()[0].get("streamConnectionID").and_then(Value::as_int), Some(id));
        f.ack(&m, None).await;
    }
    let (m, _) = f.request("modesChanged").await;
    f.ack(&m, None).await;
    let (m, restored) = f.request("setup:100:media").await;
    let stream = &restored.get("streams").unwrap().as_array().unwrap()[0];
    assert_ne!(stream.get("streamConnectionID").and_then(Value::as_int), Some(id));
    assert_eq!(stream.get("input").and_then(Value::as_bool), Some(false));
    assert_eq!(stream.get("audioFormat").and_then(Value::as_int), Some(1 << 15));
    f.setup_reply(&m, &restored).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    if outcome == SharedSetup::Audio {
        let (m, _) = f.request("modesChanged").await;
        f.ack(&m, None).await;
        let (m, _) = f.request("teardown:100").await;
        assert_eq!(f.commands.try_recv().unwrap(), "pause");
        f.ack(&m, None).await;
        let (m, offer) = f.request("setup:100:speechRecognition").await;
        f.setup_reply(&m, &offer).await;
        f.opus_packet(100).await;
        let (m, _) = f.request("teardown:100").await;
        f.ack(&m, None).await;
        let (m, _) = f.request("modesChanged").await;
        f.ack(&m, None).await;
        let (m, offer) = f.request("setup:100:media").await;
        f.setup_reply(&m, &offer).await;
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    }
    f.stopped().await;
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
    let (aac, offer) = f.request("setup:102:media").await;
    let stream = &offer.get("streams").unwrap().as_array().unwrap()[0];
    let port = stream.get("controlPort").cloned();
    f.reject(&aac, 400).await;
    if let Some(port) = port {
        assert_eq!(stream.get("supportsHighAccuracyTimestamps").and_then(Value::as_bool), Some(true));
        let (retry, offer) = f.request("setup:102:media").await;
        let stream = &offer.get("streams").unwrap().as_array().unwrap()[0];
        assert_eq!(stream.get("controlPort"), Some(&port));
        assert!(stream.get("supportsHighAccuracyTimestamps").is_none());
        f.reject(&retry, 400).await;
    }
    let (pcm, offer) = f.request("setup:100:media").await;
    f.setup_reply_with_type(&pcm, &offer, false).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1), f.commands.recv()).await.unwrap(), Some("install"));
    assert_eq!(f.controller.media_stream_type(), Some(100));
    assert!(tokio::time::timeout(Duration::from_millis(60), f.peer.read_u8()).await.is_err(), "fallback must not send another RECORD");
    f.stopped().await;
}

async fn speech_handoff(fail_first_restore:bool){
    speech_handoff_fixture(Fixture::new().await, fail_first_restore, 1<<15).await;
}
async fn speech_handoff_fixture(mut f: Fixture, fail_first_restore:bool, media_format:i64){
    let aac=media_format==1<<22 || media_format==1<<23;
    let media_type=if aac {102}else{100};
    let media_rate=if media_format==1<<11 || media_format==1<<22 {44100}else{48000};
    let media_setup=format!("setup:{media_type}:media");
    f.source(Kind::Speech);
    let(m,v)=f.request("modesChanged").await;
    let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(1));
    f.ack(&m,None).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
    let(m,_)=f.request(&format!("teardown:{media_type}")).await;f.ack(&m,None).await;
    let(m,v)=f.request("setup:100:speechRecognition").await;f.setup_reply(&m,&v).await;
    f.opus_packet(100).await;
    let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
    let(m,v)=f.request("modesChanged").await;let apps=v.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
    assert_eq!(apps[0].get("entity").and_then(Value::as_int),Some(0));assert_eq!(apps[0].get("speechMode").and_then(Value::as_int),Some(-1));f.ack(&m,None).await;
    if fail_first_restore {
        let(m,_)=f.request(&media_setup).await;
        f.peer.write_all(format!("RTSP/1.0 400 Rejected\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",m.cseq().unwrap()).as_bytes()).await.unwrap();
    }
    let(m,v)=f.request(&media_setup).await;
    let stream=&v.get("streams").unwrap().as_array().unwrap()[0];
    assert_eq!(stream.get("audioFormat").and_then(Value::as_int),Some(media_format));
    assert_eq!(stream.get("spf").and_then(Value::as_int),Some(if aac {1024}else{352}));
    if aac {assert_eq!(stream.get("sr").and_then(Value::as_int),Some(media_rate));}
    assert!(f.commands.try_recv().is_err(),"media install must wait for SETUP ACK");f.setup_reply(&m,&v).await;
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
    assert_eq!(f.installed_rate.load(std::sync::atomic::Ordering::Acquire),media_rate as u32);
    assert!(!f.media.borrow().idle());
    f.stopped().await;
    let mut expected=vec!["modesChanged".to_owned(),format!("teardown:{media_type}"),"setup:100:speechRecognition".into(),"teardown:100".into(),"modesChanged".into(),media_setup.clone()];
    if fail_first_restore {expected.push(media_setup);}
    assert_eq!(f.order,expected);
}
#[tokio::test] async fn siri_main_stream_handoff_restores_pcm_only_after_speech_teardown_and_end_ack(){speech_handoff(false).await;}
#[tokio::test] async fn rejected_first_media_restore_retries_without_installing_an_empty_sender(){speech_handoff(true).await;}

fn speech_profile(p: &mut AirplayParams, profile: &str, carwith: bool) {
    p.head_unit_profile=profile.into();
    p.bmw_capabilities=profile.starts_with("bmw_");
    p.id9_screen_bootstrap=profile=="bmw_id9";
    p.nbt_audio_first=profile=="bmw_id6";
    p.mbux_media_compat=profile=="mercedes_mbux";
    p.desktop_receiver=profile=="desktop";
    p.carwith_media_rate=carwith.then(||tokio::sync::watch::channel(None).1);
}
fn fixture_audio_formats(info: &mut Value) -> &mut Vec<Value> {
    let Value::Dict(fields)=info else {panic!("fixture /info dictionary")};
    let (_,Value::Array(rows))=fields.iter_mut().find(|(key,_)|key.as_str()==Some("audioFormats")).unwrap()
        else {panic!("fixture audioFormats array")};
    rows
}

#[tokio::test] async fn declared_speech_preserves_music_and_restores_both_rates_for_every_profile() {
    for profile in ["bmw_id9","bmw_id6","mercedes_mbux","generic","desktop","remote"] {
        for carwith in [false,true] {
            for (codec,media_format) in [(AudioCodec::Pcm,1<<15),(AudioCodec::Pcm441,1<<11),
                (AudioCodec::Aac,1<<23),(AudioCodec::Aac441,1<<22)] {
                let f=Fixture::with_codec(codec, |p,info| {
                    speech_profile(p,profile,carwith);
                    let aac=media_format==1<<22 || media_format==1<<23;
                    if aac {p.audio_codec="aac".into();}
                    {
                        let rows=fixture_audio_formats(info);
                        rows.retain(|row|row.get("audioType").and_then(Value::as_str)!=Some("media"));
                        rows.push(Value::dict(vec![("type",Value::int(if aac {102}else{100})),("audioType",Value::str("media")),("audioOutputFormats",Value::int(media_format))]));
                    }
                }).await;
                // Also exercise a rejected first restore without another RECORD.
                speech_handoff_fixture(f, carwith, media_format).await;
            }
        }
    }
}

#[tokio::test] async fn speech_without_output_capability_stays_mixed_without_pausing_music() {
    for profile in ["bmw_id85","bmw_id9","bmw_id6","mercedes_mbux","generic","desktop"] {
        let mut f=Fixture::with_config(|p,info| {
            speech_profile(p,profile,false);
            {
                let rows=fixture_audio_formats(info);
                rows.retain(|row|row.get("audioType").and_then(Value::as_str)!=Some("speechRecognition"));
            }
        }).await;
        f.input.init(Kind::Speech,16000,1);
        assert_eq!(f.input.push(Kind::Speech,16000,1,&4000i16.to_le_bytes().repeat(1600)),super::Route::Mix);
        assert!(tokio::time::timeout(Duration::from_millis(30),f.peer.read_u8()).await.is_err());
        assert!(f.commands.try_recv().is_err());
        f.stopped().await;
    }
}

#[tokio::test] async fn refused_declared_speech_restores_music_before_returning_pcm_to_mix() {
    for profile in ["bmw_id9","bmw_id6","mercedes_mbux","generic","desktop"] {
        let mut f=Fixture::with_config(|p,_|speech_profile(p,profile,false)).await;
        f.source(Kind::Speech);
        let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
        assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("pause"));
        let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
        let(m,_)=f.request("setup:100:speechRecognition").await;f.reject(&m,400).await;
        let(m,_)=f.request("teardown:100").await;f.ack(&m,None).await;
        let(m,_)=f.request("modesChanged").await;f.ack(&m,None).await;
        let(m,v)=f.request("setup:100:media").await;
        assert!(f.fallback.try_recv().is_err(),"mix must wait until media is restored");
        f.setup_reply(&m,&v).await;
        assert_eq!(tokio::time::timeout(Duration::from_secs(1),f.commands.recv()).await.unwrap(),Some("install"));
        assert!(matches!(tokio::time::timeout(Duration::from_secs(1),f.fallback.recv()).await.unwrap(),
            Some(crate::engine::SourceEvent::AuxFallback{kind:Kind::Speech,samples}) if !samples.is_empty()));
        assert_eq!(f.order.iter().filter(|s|s.as_str()=="setup:100:speechRecognition").count(),1);
        f.stopped().await;
    }
}

fn id9_carwith(params: &mut AirplayParams, _: &mut Value) {
    params.head_unit_profile = "bmw_id9".into();
    params.id9_screen_bootstrap = true;
    params.carwith_media_rate = Some(tokio::sync::watch::channel(None).1);
}

#[tokio::test] async fn navigation_uses_current_output_capability_for_every_profile_and_source() {
    let mut f = Fixture::with_config(|p, info| {
        id9_carwith(p, info);
        let kinds = crate::airplay::independent_audio_kinds;
        assert_eq!(kinds(p, info), &[Kind::Speech,Kind::Navigation]);
        for (typ, label, field, mask) in [(100,"default","audioOutputFormats",1<<30),
            (101,"media","audioOutputFormats",1<<30),(101,"default","audioInputFormats",1<<30),
            (101,"default","audioOutputFormats",1<<29)] {
            let entry = Value::dict(vec![("type",Value::int(typ)),("audioType",Value::str(label)),(field,Value::int(mask))]);
            assert!(kinds(p,&Value::dict(vec![("audioFormats",Value::array(vec![entry]))])).is_empty());
        }
        p.carwith_media_rate = None;
        assert_eq!(kinds(p, info), &[Kind::Speech,Kind::Navigation]);
        for profile in ["bmw_id9", "bmw_id6", "mercedes_mbux", "generic", "motorcycle_generic", "desktop"] {
            for carwith in [false, true] {
                p.head_unit_profile = profile.into();
                p.bmw_capabilities = profile.starts_with("bmw_");
                p.nbt_audio_first = profile == "bmw_id6";
                p.id9_screen_bootstrap = profile == "bmw_id9";
                p.desktop_receiver = profile == "desktop";
                p.carwith_media_rate = carwith.then(|| tokio::sync::watch::channel(None).1);
                assert_eq!(kinds(p, info), &[Kind::Speech,Kind::Navigation], "profile={profile} carwith={carwith}");
                assert!(kinds(p, &Value::dict(vec![])).is_empty());
            }
        }
    }).await;
    f.stopped().await;
}

#[tokio::test] async fn navigation_owns_only_alt_stream_and_never_pauses_or_replaces_media(){
    navigation_duck_cycle(Fixture::new().await).await;
}

#[tokio::test] async fn id9_navigation_waits_for_pcm_then_restores_music_volume() {
    let mut f = Fixture::with_config(|p,info| {
        id9_carwith(p,info);
        // This navigation test also checks speech mixing without a declaration.
        fixture_audio_formats(info).retain(|row|row.get("audioType").and_then(Value::as_str)!=Some("speechRecognition"));
    }).await;
    f.input.init(Kind::Speech,16000,1);
    assert_eq!(f.input.push(Kind::Speech,16000,1,&[1;640]),super::Route::Mix);
    f.input.init(Kind::Navigation,16000,1);
    let mut wire = [0; 512];
    let read = tokio::time::timeout(Duration::from_millis(80), f.peer.read(&mut wire)).await;
    assert!(read.is_err(), "unexpected RTSP bytes before navigation PCM: {read:?} {:?}", String::from_utf8_lossy(&wire));
    navigation_duck_cycle(f).await;
}

fn generic_receiver(params: &mut AirplayParams, _: &mut Value) {
    params.head_unit_profile = "generic".into();
    params.bmw_capabilities = false;
    params.carwith_media_rate = None;
}

#[tokio::test] async fn generic_self_drawn_navigation_preserves_media_and_restores_volume() {
    navigation_duck_cycle(Fixture::with_config(generic_receiver).await).await;
}

#[tokio::test] async fn generic_navigation_without_declared_output_stays_in_media_mix() {
    let mut f = Fixture::with_config(|p, info| {
        generic_receiver(p, info);
        *info = Value::dict(vec![]);
    }).await;
    f.input.init(Kind::Navigation, 48000, 2);
    assert_eq!(f.input.push(Kind::Navigation, 48000, 2, &3000i16.to_le_bytes().repeat(1920)), super::Route::Mix);
    assert!(f.fallback.try_recv().is_err(), "unsupported input already belongs to the media mix");
    f.stopped().await;
}

#[tokio::test] async fn id9_self_drawn_navigation_uses_independent_output() {
    navigation_duck_cycle(Fixture::with_config(|p, info| {
        id9_carwith(p, info);
        p.carwith_media_rate = None;
    }).await).await;
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
    navigation_setup_failure(false, id9_carwith).await;
}

#[tokio::test] async fn id9_deferred_navigation_release_keeps_pcm_until_cleanup_ack() {
    navigation_setup_failure(true, id9_carwith).await;
}

#[tokio::test] async fn generic_navigation_rejected_setup_falls_back_to_mix_once() {
    navigation_setup_failure(false, generic_receiver).await;
}

#[tokio::test] async fn generic_navigation_waits_for_cleanup_ack_before_mixing() {
    navigation_setup_failure(true, generic_receiver).await;
}

async fn navigation_setup_failure(reject_first_release: bool, configure: fn(&mut AirplayParams, &mut Value)) {
    let mut f = Fixture::with_config(configure).await;
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

#[tokio::test] async fn navigation_partial_cleanup_does_not_repeat_acknowledged_teardown() {
    for rejected_setup in [false, true] {
        let mut f = Fixture::with_config(generic_receiver).await;
        f.input.init(Kind::Navigation, 48000, 2);
        let pcm = 3000i16.to_le_bytes().repeat(1920);
        assert_eq!(f.input.push(Kind::Navigation, 48000, 2, &pcm), super::Route::Independent);
        let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
        let (m, _) = f.request("duckAudio").await; f.ack(&m, None).await;
        let (m, v) = f.request("setup:101:default").await;
        if rejected_setup { f.reject(&m, 400).await; }
        else {
            f.setup_reply(&m, &v).await;
            f.opus_packet(101).await;
            f.input.stop(Kind::Navigation);
        }
        let (m, _) = f.request("unduckAudio").await; f.reject(&m, 500).await;
        let (m, _) = f.request("teardown:101").await; f.ack(&m, None).await;
        assert!(f.fallback.try_recv().is_err(), "must not mix while volume cleanup is pending");
        let (m, _) = f.request("unduckAudio").await; f.ack(&m, None).await;
        // A second TEARDOWN may be a 404: the ACK above must remain recorded.
        let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
        if rejected_setup {
            let event = tokio::time::timeout(Duration::from_secs(1), f.fallback.recv()).await.unwrap();
            assert!(matches!(event, Some(crate::engine::SourceEvent::AuxFallback { kind: Kind::Navigation, samples }) if samples == vec![3000; 1920]));
            assert_eq!(f.input.push(Kind::Navigation, 48000, 2, &pcm), super::Route::Mix);
        }
        assert!(f.fallback.try_recv().is_err(), "fallback must be emitted only once");
        assert!(f.commands.try_recv().is_err(), "navigation must never pause or reinstall music");
        f.stopped().await;
    }
}

#[tokio::test] async fn rejected_navigation_duck_retries_only_unduck_without_tearing_down_unallocated_stream() {
    let mut f = Fixture::with_config(generic_receiver).await;
    f.source(Kind::Navigation);
    let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
    let (m, _) = f.request("duckAudio").await; f.reject(&m, 400).await;
    let (m, _) = f.request("unduckAudio").await; f.reject(&m, 500).await;
    let (m, _) = f.request("unduckAudio").await; f.ack(&m, None).await;
    let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
    assert!(tokio::time::timeout(Duration::from_secs(1), f.fallback.recv()).await.unwrap().is_some());
    assert!(f.commands.try_recv().is_err());
    f.stopped().await;
}

#[tokio::test] async fn navigation_teardown_retry_does_not_repeat_successful_volume_restore() {
    let mut f = Fixture::with_config(generic_receiver).await;
    f.source(Kind::Navigation);
    let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
    let (m, _) = f.request("duckAudio").await; f.ack(&m, None).await;
    let (m, v) = f.request("setup:101:default").await; f.setup_reply(&m, &v).await;
    f.opus_packet(101).await;
    let (m, _) = f.request("unduckAudio").await; f.ack(&m, None).await;
    let (m, _) = f.request("teardown:101").await; f.reject(&m, 500).await;
    let (m, _) = f.request("teardown:101").await; f.ack(&m, None).await;
    let (m, _) = f.request("modesChanged").await; f.ack(&m, None).await;
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
        assert!(refusals<=18,"media recovery must remain bounded");
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
