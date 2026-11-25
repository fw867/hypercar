use super::*;
use rsa::pkcs8::DecodePublicKey;
use tokio::net::TcpListener;

fn config() -> CarLifeConfig {
    CarLifeConfig {dialect:CarLifeDialect::SystemAuto,manufacturer:"Test HU".into(),model:"Receiver".into(),
        width:1280,height:720,fps:30,navigation_updates:false,microphone:None,vehicle_gps:None,
        bt_address:"00:11:22:33:44:55".into(),bt_name:"Test HU".into()}
}
async fn listeners() -> (Vec<TcpListener>,[u16;7]) {
    let mut listeners=Vec::new();let mut ports=[0;7];
    for port in &mut ports {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        *port=listener.local_addr().unwrap().port();listeners.push(listener);
    }
    (listeners,ports)
}
async fn accept(listener:&TcpListener) -> TcpStream {
    tokio::time::timeout(Duration::from_secs(3),listener.accept()).await.unwrap().unwrap().0
}
async fn frame(socket:&mut TcpStream) -> (u32,Vec<u8>) {
    tokio::time::timeout(Duration::from_secs(3),async {
        let mut header=[0;8];socket.read_exact(&mut header).await.unwrap();
        let mut body=vec![0;u16::from_be_bytes([header[0],header[1]]) as usize];
        socket.read_exact(&mut body).await.unwrap();
        (u32::from_be_bytes(header[4..8].try_into().unwrap()),body)
    }).await.unwrap()
}
async fn send(socket:&mut TcpStream,id:u32,body:&[u8]) {
    socket.write_all(&build_cmd_frame(id,body)).await.unwrap();
}
const H264_SYNC: &[u8] = &[0,0,0,1,0x67,0x42,0,0x1f,0,0,0,1,0x68,1,
    0,0,0,1,0x65,0x80,0,0,0,1,0x41,0x80];

async fn plain_start(cmd: &mut TcpStream) {
    assert_eq!(frame(cmd).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 3, 16, 0]));
    send(cmd, CMD_VERSION_MATCH_STATUS, &[8, 1]).await;
    assert_eq!(frame(cmd).await.0, CMD_HU_STATISTIC_INFO);
    send(cmd, CMD_MD_INFO, &[]).await;
    assert_eq!(frame(cmd).await.0, CMD_HU_INFO);
    assert_eq!(frame(cmd).await.0, CMD_HU_FEATURE_CONFIG_RESPONSE);
    send(cmd, CMD_MD_AUTHEN_RESULT, &[8, 1]).await;
    assert_eq!(frame(cmd).await.0, CMD_HU_MD_AUTH_RESULT_ACK);
    send(cmd, 0x10022, &[]).await;
    assert_eq!(frame(cmd).await.0, CMD_VIDEO_ENCODER_INIT);
    send(cmd, CMD_VIDEO_ENCODER_INIT_DONE, &proto_encoder_info_payload(1280, 720, 30)).await;
    assert_eq!(frame(cmd).await.0, CMD_VIDEO_ENCODER_START);
}

async fn accept_round(listeners: &[TcpListener]) -> Vec<TcpStream> {
    let mut peers = Vec::new();
    for listener in listeners { peers.push(accept(listener).await); }
    peers
}

async fn encrypted_start(cmd:&mut TcpStream,late_video:Option<u16>) -> (Vec<u8>,String,Option<(TcpListener,TcpStream)>) {
    assert_eq!(frame(cmd).await,(CMD_HU_PROTOCOL_VERSION,vec![8,4,16,1]));
    let late_video=if let Some(port)=late_video {
        let listener=TcpListener::bind(("127.0.0.1",port)).await.unwrap();
        let peer=accept(&listener).await;
        Some((listener,peer))
    }else{None};
    send(cmd,CMD_VERSION_MATCH_STATUS,&[8,1]).await;
    assert_eq!(frame(cmd).await.0,CMD_HU_STATISTIC_INFO);
    let mut info=Vec::new();
    for (id,text) in [(4,"Samsung"),(14,"Fold7"),(20,"sdk")] {proto::string(&mut info,id,text);}
    send(cmd,CMD_MD_INFO,&info).await;
    assert_eq!(frame(cmd).await.0,CMD_HU_INFO);
    let (id,request)=frame(cmd).await;assert_eq!(id,CMD_HU_AUTHEN_REQUEST);
    let challenge=pb_first_string(&request,1).unwrap();
    let proof=format!("{:x}",md5::Md5::digest(format!("CARLIFESamsungFold7sdk{}",&challenge[4..]).as_bytes()));
    let mut body=Vec::new();proto::string(&mut body,1,&proof);
    send(cmd,CMD_MD_FEATURE_CONFIG_REQUEST,&[]).await;
    send(cmd,CMD_MD_AUTHEN_RESPONSE,&body).await;
    assert_eq!(frame(cmd).await.0,CMD_HU_AUTHEN_RESULT);
    let (id,features)=frame(cmd).await;assert_eq!(id,CMD_HU_FEATURE_CONFIG_RESPONSE);
    assert!(features.windows(18).any(|bytes|bytes==b"CONTENT_ENCRYPTION"));
    send(cmd,CMD_MD_RSA_KEY_REQUEST,&[]).await;
    let (id,public)=frame(cmd).await;assert_eq!(id,CMD_HU_RSA_PUBLIC_KEY);
    let der=base64::Engine::decode(&base64::engine::general_purpose::STANDARD,pb_first_string(&public,1).unwrap()).unwrap();
    let key=rsa::RsaPublicKey::from_public_key_der(&der).unwrap();
    let seed=vec![17;16];
    let wrapped=key.encrypt(&mut rand::thread_rng(),rsa::Pkcs1v15Encrypt,&seed).unwrap();
    let mut body=Vec::new();proto::string(&mut body,1,&base64::Engine::encode(&base64::engine::general_purpose::STANDARD,wrapped));
    send(cmd,CMD_MD_AES_SEED,&body).await;
    assert_eq!(frame(cmd).await.0,CMD_HU_AES_ACK);
    send(cmd,CMD_MD_ENCRYPT_READY,&[]).await;
    send(cmd,CMD_MD_AUTHEN_RESULT,&crypt(&[8,1],Some(&seed),true).unwrap()).await;
    assert_eq!(frame(cmd).await.0,CMD_HU_MD_AUTH_RESULT_ACK);
    let (id,body)=frame(cmd).await;assert_eq!(id,CMD_VIDEO_ENCODER_INIT);
    assert_eq!(parse_encoder_info(&crypt(&body,Some(&seed),false).unwrap()),(1280,720));
    send(cmd,CMD_VIDEO_ENCODER_INIT_DONE,&crypt(&proto_encoder_info_payload(1280,720,30),Some(&seed),true).unwrap()).await;
    assert_eq!(frame(cmd).await.0,CMD_VIDEO_ENCODER_START);
    (seed,challenge,late_video)
}

#[test]
fn admission_is_not_a_content_encryption_signal_and_proof_failure_never_downgrades() {
    let mut cfg=config();cfg.dialect=CarLifeDialect::SystemPlain30;
    let mut plain=Session::new(cfg.dialect);
    plain.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
    plain.receive(CMD_MD_INFO,&[],&cfg).unwrap();
    assert!(plain.receive(CMD_MD_AUTHEN_RESULT,&[8,1],&cfg).unwrap().iter().any(|(id,_)|*id==CMD_HU_MD_AUTH_RESULT_ACK));
    assert!(!plain.protocol_evidence().authentication && plain.seed.is_none());
    assert!(plain.receive(CMD_MD_AUTHEN_RESULT,&[8,0],&cfg).is_err());
    assert!(plain.receive(CMD_MD_RSA_KEY_REQUEST,&[],&cfg).is_err());
    assert!(plain.protocol_evidence().authentication && plain.seed.is_none());
    let mut choice=super::super::protocol_selection::Selection::new(CarLifeDialect::SystemAuto);
    let mut encrypted=Session::default();
    assert!(encrypted.receive(CMD_MD_AUTHEN_RESPONSE,&[],&config()).is_err());
    assert!(!choice.round_ended(encrypted.protocol_evidence(), RoundEnd::Command));
    assert!(!choice.round_ended(Default::default(), RoundEnd::Command));
}

#[test]
fn actual_plain_version_rejection_retries_encrypted_immediately() {
    let mut cfg = config();
    cfg.dialect = CarLifeDialect::SystemPlain30;
    let mut choice = super::super::protocol_selection::Selection::new(config().dialect);
    assert!(choice.round_ended(Default::default(), RoundEnd::Command));
    let mut plain = Session::new(cfg.dialect);
    assert!(plain.receive(CMD_VERSION_MATCH_STATUS, &[8, 2], &cfg).is_err());
    assert!(choice.round_ended(plain.protocol_evidence(), RoundEnd::Command));
    assert_eq!(choice.current(), CarLifeDialect::CarWithEncrypted41);
}

#[tokio::test]
async fn pre_auth_secondary_eof_reconnects_that_lane_without_losing_cmd() {
    let (listeners, ports) = listeners().await;
    let stop = Arc::new(DialStop::default());
    let (_hub, _events) = CarLifeHub::connect_to_ports(config(), "127.0.0.1".into(), ports,
        stop.clone(), None, |_| {}).unwrap();
    let mut peers = Vec::new();
    for listener in &listeners { peers.push(accept(listener).await); }
    assert_eq!(frame(&mut peers[0]).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 4, 16, 1]));
    drop(peers.remove(3)); // TTS EOF while CMD remains open, before any authentication.
    let _tts = accept(&listeners[3]).await;
    send(&mut peers[0], CMD_VERSION_MATCH_STATUS, &[8, 1]).await;
    assert_eq!(frame(&mut peers[0]).await.0, CMD_HU_STATISTIC_INFO);
    let mut info = Vec::new();
    for (id, value) in [(4, "Samsung"), (14, "Fold7"), (20, "sdk")] {
        proto::string(&mut info, id, value);
    }
    send(&mut peers[0], CMD_MD_INFO, &info).await;
    assert_eq!(frame(&mut peers[0]).await.0, CMD_HU_INFO);
    assert_eq!(frame(&mut peers[0]).await.0, CMD_HU_AUTHEN_REQUEST);
    stop.cancel();
}

#[tokio::test]
async fn encrypted_start_without_video_socket_retires_and_retries_encrypted() {
    let (mut listeners, ports) = listeners().await;
    drop(listeners.remove(1));
    let stop = Arc::new(DialStop::default());
    let (_hub, _events) = CarLifeHub::connect_to_ports(config(), "127.0.0.1".into(), ports,
        stop.clone(), None, |_| {}).unwrap();
    let mut peers = Vec::new();
    for listener in &listeners { peers.push(accept(listener).await); }
    encrypted_start(&mut peers[0], None).await;
    assert!(tokio::time::timeout(Duration::from_secs(11), peers[0].read_u8()).await.unwrap().is_err());
    drop(peers);
    let mut next = accept(&listeners[0]).await;
    assert_eq!(frame(&mut next).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 4, 16, 1]));
    stop.cancel();
}

#[tokio::test]
async fn encrypted_video_socket_without_sync_picture_retries_encrypted() {
    let (listeners, ports) = listeners().await;
    let stop = Arc::new(DialStop::default());
    let (_hub, _events) = CarLifeHub::connect_to_ports(config(), "127.0.0.1".into(), ports,
        stop.clone(), None, |_| {}).unwrap();
    let mut peers = Vec::new();
    for listener in &listeners { peers.push(accept(listener).await); }
    let (seed, _, _) = encrypted_start(&mut peers[0], None).await;
    // A heartbeat and pictures without their parameter sets cannot start a decoder.
    peers[1].write_all(&build_media_frame(VIDEO_HEARTBEAT, 0, &[])).await.unwrap();
    let incomplete = [0,0,0,1,0x65,0x80,0,0,0,1,0x41,0x80];
    let encrypted = crypt(&incomplete, Some(&seed), true).unwrap();
    peers[1].write_all(&build_media_frame(VIDEO_DATA, 0, &encrypted)).await.unwrap();
    assert_eq!(tokio::time::timeout(Duration::from_secs(11), peers[0].read_u8()).await
        .expect("VIDEO without a sync picture must expire").unwrap_err().kind(), std::io::ErrorKind::UnexpectedEof);
    drop(peers);
    let mut next = accept(&listeners[0]).await;
    assert_eq!(frame(&mut next).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 4, 16, 1]));
    stop.cancel();
}

#[tokio::test]
async fn automatic_plain_start_without_a_sync_picture_exhausts_the_plain_probe() {
    let (listeners, ports) = listeners().await;
    let stop = Arc::new(DialStop::default());
    let (_hub, _events) = CarLifeHub::connect_to_ports(config(), "127.0.0.1".into(), ports,
        stop.clone(), None, |_| {}).unwrap();
    let mut probe = accept_round(&listeners).await;
    assert_eq!(frame(&mut probe[0]).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 4, 16, 1]));
    send(&mut probe[0], CMD_VERSION_MATCH_STATUS, &[8, 2]).await;
    assert!(tokio::time::timeout(Duration::from_secs(1), probe[0].read_u8()).await.unwrap().is_err());
    drop(probe);

    for _ in 0..3 {
        let mut peers = accept_round(&listeners).await;
        plain_start(&mut peers[0]).await;
        peers[1].write_all(&build_media_frame(VIDEO_HEARTBEAT, 0, &[])).await.unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_secs(10), peers[0].read_u8()).await
            .expect("unconfirmed plaintext video must expire").unwrap_err().kind(),
            std::io::ErrorKind::UnexpectedEof);
    }
    let mut next = accept(&listeners[0]).await;
    assert_eq!(frame(&mut next).await, (CMD_HU_PROTOCOL_VERSION, vec![8, 4, 16, 1]));
    stop.cancel();
}

#[test]
fn queued_authentication_is_observed_before_the_blocking_worker_runs() {
    tokio::runtime::Builder::new_current_thread().enable_all().max_blocking_threads(1)
        .build().unwrap().block_on(async {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut peer = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let stream = accept(&listener).await;
        let (events, _rx) = mpsc::unbounded_channel();
        let mut cfg = config();
        cfg.dialect = CarLifeDialect::CarWithEncrypted41;
        let state = Arc::new(Mutex::new(Session::new(cfg.dialect)));
        let cmd = tokio::spawn(run(stream, events, Arc::new(Mutex::new((1280,720))),
            DisplayRequest::new(ReceiverDisplay::default()), cfg, state.clone(), Arc::new(Notify::new()), false));
        assert_eq!(frame(&mut peer).await.0, CMD_HU_PROTOCOL_VERSION);
        send(&mut peer, CMD_VERSION_MATCH_STATUS, &[8,1]).await;
        assert_eq!(frame(&mut peer).await.0, CMD_HU_STATISTIC_INFO);
        let mut info = Vec::new();
        for (id, value) in [(4, "Samsung"), (14, "Fold7"), (20, "sdk")] {
            proto::string(&mut info, id, value);
        }
        send(&mut peer, CMD_MD_INFO, &info).await;
        assert_eq!(frame(&mut peer).await.0, CMD_HU_INFO);
        assert_eq!(frame(&mut peer).await.0, CMD_HU_AUTHEN_REQUEST);
        let (entered_tx, entered) = tokio::sync::oneshot::channel();
        let (release, blocked) = std::sync::mpsc::channel();
        let worker = tokio::task::spawn_blocking(move || {
            let _ = entered_tx.send(());
            let _ = blocked.recv_timeout(Duration::from_secs(3));
        });
        entered.await.unwrap();
        let batch = [
            build_cmd_frame(CMD_SCREEN_ON, &[]),
            build_cmd_frame(CMD_MD_AUTHEN_RESPONSE, &[]),
        ].concat();
        peer.write_all(&batch).await.unwrap();
        let observed = tokio::time::timeout(Duration::from_secs(1), async {
            while !state.lock().protocol_evidence().authentication {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        }).await;
        release.send(()).unwrap();
        worker.await.unwrap();
        assert!(observed.is_ok(), "CMD must record authentication before dispatching validation");
        assert!(cmd.await.unwrap().is_err(), "invalid proof must still fail validation");
    });
}

#[test]
fn buffered_authentication_requires_a_complete_frame() {
    let progress = Progress::default();
    let batch = [
        build_cmd_frame(CMD_MD_AUTHEN_RESULT, &[8, 1]),
        build_cmd_frame(CMD_MD_AUTHEN_RESPONSE, &[0x0a, 1, b'x']),
    ].concat();
    observe_buffered_authentication(&progress, &batch[..batch.len() - 1]);
    assert!(!progress.evidence().authentication);
    observe_buffered_authentication(&progress, &batch);
    assert!(progress.evidence().authentication);
}

fn spawn_deadline(progress: Arc<Progress>) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move { handshake_deadline(&progress).await })
}

#[tokio::test(start_paused = true)]
async fn authenticated_deadline_does_not_wait_for_the_session_lock() {
    let state = Arc::new(Mutex::new(Session::default()));
    let progress = state.lock().protocol_progress.clone();
    progress.authentication.store(true, Ordering::Relaxed);
    let deadline = spawn_deadline(progress);
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(8)).await;
    tokio::task::yield_now().await;
    assert!(!deadline.is_finished());
    let (entered_tx, entered) = std::sync::mpsc::channel();
    let (release, blocked) = std::sync::mpsc::channel();
    let worker = std::thread::spawn(move || {
        let _guard = state.lock();
        entered_tx.send(()).unwrap();
        blocked.recv_timeout(Duration::from_secs(2)).is_ok()
    });
    // Waiting outside Tokio's paused clock keeps it from advancing to 30s
    // before the other thread acquires the lock.
    entered.recv_timeout(Duration::from_secs(2)).unwrap();
    tokio::time::advance(Duration::from_secs(22)).await;
    deadline.await.unwrap();
    let _ = release.send(());
    assert!(worker.join().unwrap(), "deadline waited for the session lock");
}

#[tokio::test]
async fn automatic_encrypted_handshake_keeps_late_canvas_and_routes_ctrl_voice() {
    let (mut listeners,ports)=listeners().await;
    drop(listeners.remove(1)); // VIDEO is opened only after CMD receives HU4.1.
    let stop=Arc::new(DialStop::default());
    let display=DisplayRequest::new(ReceiverDisplay::default());
    let reopened=Arc::new(std::sync::atomic::AtomicU32::new(0));let observed=reopened.clone();
    let (hub,mut events)=CarLifeHub::connect_to_ports(config(),"127.0.0.1".into(),ports,stop.clone(),Some(display.clone()),move |_| {
        observed.fetch_add(1,std::sync::atomic::Ordering::Relaxed);
    }).unwrap();
    let mut peers=Vec::new();for listener in &listeners {peers.push(accept(listener).await);}
    let (seed,challenge,late)=encrypted_start(&mut peers[0],Some(ports[1])).await;
    let (listener,peer)=late.unwrap();listeners.insert(1,listener);peers.insert(1,peer);
    let encrypted = crypt(H264_SYNC, Some(&seed), true).unwrap();
    peers[1].write_all(&build_media_frame(VIDEO_DATA, 0, &encrypted)).await.unwrap();
    tokio::time::timeout(Duration::from_secs(1),async {loop {
        if let Some(CarLifeEvent::VideoFrameTagged {data, codec, ..}) = events.recv().await {
            assert!(super::super::video::is_configured_sync(codec, &data));
            break;
        }
    }}).await.unwrap();
    assert!(hub.system_voice_epoch().is_none());
    hub.voice_start();
    let (id,body)=frame(&mut peers[5]).await;assert_eq!(id,CTRL_CAR_HARD_KEY_CODE);
    assert_eq!(first_int(&crypt(&body,Some(&seed),false).unwrap(),1),Some(0x21));
    display.send_replace(ReceiverDisplay {size:(1920,720),max_fps:Some(60),confirmed:true});
    assert!(tokio::time::timeout(Duration::from_millis(8250),peers[0].read_u8()).await.is_err(),
        "a configured sync picture must disarm the VIDEO startup deadline");
    assert_eq!(reopened.load(std::sync::atomic::Ordering::Relaxed),0);
    drop(peers);
    let mut next=Vec::new();for listener in &listeners {next.push(accept(listener).await);}
    assert_eq!(frame(&mut next[0]).await,(CMD_HU_PROTOCOL_VERSION,vec![8,4,16,1]));
    send(&mut next[0],CMD_VERSION_MATCH_STATUS,&[8,1]).await;
    assert_eq!(frame(&mut next[0]).await.0,CMD_HU_STATISTIC_INFO);
    let mut info=Vec::new();for (id,text) in [(4,"Samsung"),(14,"Fold7"),(20,"sdk")] {proto::string(&mut info,id,text);}
    send(&mut next[0],CMD_MD_INFO,&info).await;
    assert_eq!(frame(&mut next[0]).await.0,CMD_HU_INFO);
    let (id,body)=frame(&mut next[0]).await;assert_eq!(id,CMD_HU_AUTHEN_REQUEST);
    assert_ne!(pb_first_string(&body,1).unwrap(),challenge,"a retry must not reuse challenge or cipher state");
    stop.cancel();
    assert_eq!(tokio::time::timeout(Duration::from_secs(1),next[0].read_u8()).await.unwrap().unwrap_err().kind(),std::io::ErrorKind::UnexpectedEof);
}

#[tokio::test(start_paused=true)]
async fn probe_deadline_is_absolute_and_completed_plain_reconfiguration_is_not_a_new_probe() {
    let deadline = spawn_deadline(Arc::new(Progress::default()));
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(7)).await;
    assert!(!deadline.is_finished());
    tokio::time::advance(Duration::from_secs(1)).await;
    deadline.await.unwrap();
    let progress = Arc::new(Progress::default());
    progress.authentication.store(true, Ordering::Relaxed);
    let deadline = spawn_deadline(progress);
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(8)).await;
    tokio::task::yield_now().await;
    assert!(!deadline.is_finished());
    tokio::time::advance(Duration::from_secs(22)).await;
    deadline.await.unwrap();
    let session = Session::new(CarLifeDialect::SystemPlain30);
    session.protocol_progress.started.store(true, Ordering::Relaxed);
    assert!(!session.started, "video reconfiguration clears live START");
    let deadline = spawn_deadline(session.protocol_progress.clone());
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(60)).await;
    tokio::task::yield_now().await;
    assert!(!deadline.is_finished());
    deadline.abort();
}

#[tokio::test]
async fn rejected_encrypted_probe_trials_plain_then_locks_its_success_and_cmd_voice() {
    let (listeners,ports)=listeners().await;
    let stop=Arc::new(DialStop::default());
    let (hub,mut events)=CarLifeHub::connect_to_ports(config(),"127.0.0.1".into(),ports,stop.clone(),None,|_|{}).unwrap();
    let mut probe=accept(&listeners[0]).await;
    let mut probe_lanes=Vec::new();for listener in &listeners[1..] {probe_lanes.push(accept(listener).await);}
    assert_eq!(frame(&mut probe).await,(CMD_HU_PROTOCOL_VERSION,vec![8,4,16,1]));
    send(&mut probe,CMD_VERSION_MATCH_STATUS,&[8,2]).await;
    assert!(tokio::time::timeout(Duration::from_secs(1),probe.read_u8()).await.unwrap().is_err());
    drop(probe_lanes);
    let mut cmd=accept(&listeners[0]).await;
    let mut lanes=Vec::new();for listener in &listeners[1..] {lanes.push(accept(listener).await);}
    plain_start(&mut cmd).await;
    lanes[0].write_all(&build_media_frame(VIDEO_DATA, 0, H264_SYNC)).await.unwrap();
    tokio::time::timeout(Duration::from_secs(1), async {
        while !matches!(events.recv().await.unwrap(), CarLifeEvent::VideoFrameTagged { .. }) {}
    }).await.unwrap();
    let old_epoch=hub.system_voice_epoch().unwrap();
    hub.voice_start();
    let (id,body)=frame(&mut cmd).await;assert_eq!(id,0x68008);assert_eq!(first_int(&body,1),Some(0x21));
    drop(lanes.remove(0));
    let _recovered_video = accept(&listeners[1]).await;
    assert_eq!(frame(&mut cmd).await.0, CMD_HU_FEATURE_CONFIG_RESPONSE);
    send(&mut cmd, 0x10022, &[]).await;
    assert_eq!(frame(&mut cmd).await.0, CMD_VIDEO_ENCODER_INIT);
    send(&mut cmd, CMD_VIDEO_ENCODER_INIT_DONE, &proto_encoder_info_payload(1280, 720, 30)).await;
    assert_eq!(frame(&mut cmd).await.0, CMD_VIDEO_ENCODER_START);
    assert!(tokio::time::timeout(Duration::from_millis(8250), cmd.read_u8()).await.is_err(),
        "plaintext VIDEO recovery must retain CMD after the first picture");
    drop(cmd);
    tokio::time::timeout(Duration::from_millis(750), async {
        while !matches!(events.recv().await.unwrap(), CarLifeEvent::SourceReset { .. }) {}
    }).await.expect("retired round must reset the source before dialing CMD again");
    let mut next=accept(&listeners[0]).await;
    assert_eq!(frame(&mut next).await,(CMD_HU_PROTOCOL_VERSION,vec![8,3,16,0]));
    assert!(!hub.system_voice_key_for_epoch(old_epoch,0x22),"old CMD voice lease must not cross a retry");
    send(&mut next,CMD_VERSION_MATCH_STATUS,&[8,1]).await;
    assert_eq!(frame(&mut next).await.0,CMD_HU_STATISTIC_INFO);
    send(&mut next,CMD_MD_INFO,&[]).await;
    assert_eq!(frame(&mut next).await.0,CMD_HU_INFO);
    assert_eq!(frame(&mut next).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE);
    send(&mut next,CMD_MD_RSA_KEY_REQUEST,&[]).await;
    assert!(tokio::time::timeout(Duration::from_secs(1),next.read_u8()).await.unwrap().is_err());
    let mut upgraded=accept(&listeners[0]).await;
    assert_eq!(frame(&mut upgraded).await,(CMD_HU_PROTOCOL_VERSION,vec![8,4,16,1]));
    stop.cancel();
}
