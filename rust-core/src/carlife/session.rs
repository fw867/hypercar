//! Xiaomi CarWith 4.1: channel admission, challenge proof and content encryption are separate gates.
use super::*;
use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use rsa::pkcs8::EncodePublicKey;
use sha2::Digest;

pub(super) type Shared = Arc<Mutex<Session>>;

/// Only CarWith uses this canvas policy; the self-rendered encoder policy is untouched.
pub(super) fn canvas_for_receiver(width:u32,height:u32)->(u32,u32) {
    if width<2 || height<2 {return (1280,720);}
    let divisor=width.max(height).max(2560) as u64;
    let even=|v:u32| ((v as u64*2560/divisor) as u32 & !1).max(2);
    (even(width),even(height))
}

#[derive(Default)]
pub(super) struct TouchGesture {active:bool, last:(u32,u32)}
impl TouchGesture {
    pub fn accept(&mut self,action:u32,x:u32,y:u32,size:(u32,u32))->Option<Vec<u8>> {
        if size.0<2 || size.1<2 {return None;}
        let point=(x.min(size.0-1),y.min(size.1-1));
        let kind=match action {
            TOUCH_DOWN if !self.active=>{self.active=true;0x68002},
            TOUCH_DOWN | TOUCH_MOVE if self.active=>0x68004,
            TOUCH_UP if self.active=>{self.active=false;0x68003},
            _=>return None,
        };
        if kind!=0x68003 {self.last=point;}
        let mut body=Vec::new();
        proto::varint(&mut body,1,self.last.0 as u64);
        proto::varint(&mut body,2,self.last.1 as u64);
        Some(build_cmd_frame(kind,&body))
    }
}

pub(super) struct Session {
    challenge: String,
    info: Option<(String, String, String)>,
    proof_ok: bool,
    admitted: bool,
    features_requested: bool,
    features_sent: bool,
    rsa: Option<rsa::RsaPrivateKey>,
    seed: Option<Vec<u8>>,
    ready: bool,
    init_sent: bool,
    pub started: bool,
    matched: bool,
}

impl Default for Session {
    fn default() -> Self {
        Self { challenge: format!("2.0;{}", uuid::Uuid::new_v4().simple()), info: None,
            proof_ok: false, admitted: false, features_requested: false, features_sent: false,
            rsa: None, seed: None, ready: false, init_sent: false, started: false, matched: false }
    }
}

type Action = (u32, Vec<u8>);
impl Session {
    pub fn encrypt(&self, bytes: &[u8]) -> Result<Vec<u8>, String> { crypt(bytes, self.seed.as_deref(), true) }
    pub fn decrypt(&self, bytes: &[u8]) -> Result<Vec<u8>, String> { crypt(bytes, self.seed.as_deref(), false) }

    fn receive(&mut self, kind: u32, body: &[u8], cfg: &CarLifeConfig) -> Result<Vec<Action>, String> {
        let mut out = Vec::new();
        match kind {
            CMD_VERSION_MATCH_STATUS => {
                if self.matched || first_int(body, 1) != Some(1) { return Err("protocol version rejected/duplicate".into()); }
                self.matched = true;
                out.push((CMD_HU_STATISTIC_INFO, proto_statistic_payload()));
            }
            CMD_MD_INFO => {
                if !self.matched || self.info.is_some() { return Err("MD_INFO out of order".into()); }
                let text = |id| pb_first_string(body, id).filter(|s| !s.trim().is_empty() && s.len() <= 1024)
                    .ok_or_else(|| format!("MD_INFO missing field {id}"));
                self.info = Some((text(4)?, text(14)?, text(20)?));
                let mut request = Vec::new();
                proto::string(&mut request, 1, &self.challenge);
                out.push((CMD_HU_INFO, proto_hu_info_payload(&cfg.bt_address, &cfg.bt_name)));
                out.push((CMD_HU_AUTHEN_REQUEST, request));
            }
            CMD_MD_AUTHEN_RESPONSE => {
                let (brand, model, sdk) = self.info.as_ref().ok_or("auth before MD_INFO")?;
                if self.proof_ok { return Err("duplicate challenge response".into()); }
                let proof = pb_first_string(body, 1).ok_or("missing challenge proof")?;
                let material = format!("CARLIFE{brand}{model}{sdk}{}", &self.challenge[4..]);
                let expected = format!("{:x}", md5::Md5::digest(material.as_bytes()));
                if proof.len() != 32 || !proof.bytes().zip(expected.bytes()).all(|(a,b)| a == b) {
                    return Err("CarWith challenge proof mismatch".into());
                }
                self.proof_ok = true;
                out.push((CMD_HU_AUTHEN_RESULT, proto_authen_result_payload(true)));
            }
            CMD_MD_AUTHEN_RESULT => {
                if self.info.is_none() || self.admitted || first_int(body, 1) != Some(1) {
                    return Err("channel admission rejected/out of order".into());
                }
                self.admitted = true;
                out.push((CMD_HU_MD_AUTH_RESULT_ACK, vec![]));
            }
            CMD_MD_FEATURE_CONFIG_REQUEST => {
                if self.info.is_none() { return Err("features before MD_INFO".into()); }
                self.features_requested = true;
            }
            CMD_MD_RSA_KEY_REQUEST => {
                if !self.features_sent || self.rsa.is_some() { return Err("RSA request out of order".into()); }
                let key = rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).map_err(|e| e.to_string())?;
                let der = key.to_public_key().to_public_key_der().map_err(|e| e.to_string())?;
                let encoded = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, der.as_bytes());
                let mut payload = Vec::new(); proto::string(&mut payload, 1, &encoded);
                self.rsa = Some(key);
                out.push((CMD_HU_RSA_PUBLIC_KEY, payload));
            }
            CMD_MD_AES_SEED => {
                if self.seed.is_some() { return Err("duplicate seed".into()); }
                let key = self.rsa.as_ref().ok_or("seed before RSA")?;
                let encoded = pb_first_string(body, 1).ok_or("missing seed")?;
                let encrypted = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, encoded).map_err(|e| e.to_string())?;
                let seed = key.decrypt(rsa::Pkcs1v15Encrypt, &encrypted).map_err(|e| e.to_string())?;
                if !matches!(seed.len(), 16 | 24 | 32) { return Err("invalid AES seed length".into()); }
                self.seed = Some(seed);
                out.push((CMD_HU_AES_ACK, vec![]));
            }
            CMD_MD_ENCRYPT_READY => {
                if !body.is_empty() || self.seed.is_none() || self.ready { return Err("invalid ENCRYPT_READY".into()); }
                self.ready = true;
            }
            CMD_VIDEO_ENCODER_INIT_DONE => {
                if !self.init_sent || self.started { return Err("VIDEO_INIT_DONE out of order".into()); }
                let (w,h) = parse_encoder_info(body);
                if w < 2 || h < 2 { return Err("invalid encoder dimensions".into()); }
                self.started = true;
                out.push((CMD_VIDEO_ENCODER_START, vec![]));
            }
            CMD_FOREGROUND | CMD_SCREEN_ON => {
                if self.matched { out.push((CMD_HU_GO_TO_FOREGROUND, vec![])); }
            }
            CMD_MD_EXIT => return Err("peer exited CarWith".into()),
            _ => {}
        }
        if self.proof_ok && self.features_requested && !self.features_sent {
            self.features_sent = true;
            let mut entry = Vec::new(); proto::string(&mut entry, 1, "CONTENT_ENCRYPTION"); proto::varint(&mut entry, 2, 1);
            let mut payload = Vec::new(); proto::varint(&mut payload, 1, 1); proto::bytes(&mut payload, 2, &entry);
            out.push((CMD_HU_FEATURE_CONFIG_RESPONSE, payload));
        }
        if self.proof_ok && self.admitted && self.ready && !self.init_sent {
            self.init_sent = true;
            let (w,h) = canvas_for_receiver(cfg.width, cfg.height);
            out.push((CMD_VIDEO_ENCODER_INIT, proto_encoder_info_payload(w,h,cfg.fps.min(30).max(1))));
        }
        Ok(out)
    }
}

impl Drop for Session { fn drop(&mut self) { if let Some(seed) = &mut self.seed { seed.fill(0); } } }

fn first_int(bytes: &[u8], field: u32) -> Option<u64> {
    let mut i = 0;
    while i < bytes.len() {
        let (tag,n) = pb_read_varint(bytes,i)?; i=n;
        match tag & 7 {
            0 => { let (value,n) = pb_read_varint(bytes,i)?; i=n; if tag >> 3 == field as u64 { return Some(value); } }
            2 => { let (len,n) = pb_read_varint(bytes,i)?; i=n.checked_add(usize::try_from(len).ok()?)?; if i>bytes.len() { return None; } }
            _ => return None,
        }
    }
    None
}

fn crypt(bytes: &[u8], key: Option<&[u8]>, encrypt: bool) -> Result<Vec<u8>,String> {
    if bytes.is_empty() || key.is_none() { return Ok(bytes.to_vec()); }
    let key = key.unwrap();
    let mut data = bytes.to_vec();
    if encrypt { let n=16-data.len()%16; data.extend(std::iter::repeat(n as u8).take(n)); }
    else if data.len()%16 != 0 { return Err("AES body not block aligned".into()); }
    macro_rules! apply { ($cipher:ty) => {{
        let cipher = <$cipher>::new_from_slice(key).map_err(|_| "invalid AES key")?;
        for block in data.chunks_exact_mut(16) {
            let block = aes::cipher::generic_array::GenericArray::from_mut_slice(block);
            if encrypt { cipher.encrypt_block(block); } else { cipher.decrypt_block(block); }
        }
    }}; }
    match key.len() { 16 => apply!(aes::Aes128), 24 => apply!(aes::Aes192), 32 => apply!(aes::Aes256), _ => return Err("invalid AES key".into()) }
    if !encrypt {
        let n = *data.last().ok_or("missing padding")? as usize;
        if n==0 || n>16 || n>data.len() || !data[data.len()-n..].iter().all(|&v| v as usize==n) { return Err("invalid AES padding".into()); }
        data.truncate(data.len()-n);
    }
    Ok(data)
}

pub(super) async fn run(stream: TcpStream, events: mpsc::UnboundedSender<CarLifeEvent>,
    size: Arc<Mutex<(u32,u32)>>, requested: Arc<Mutex<(u32,u32)>>, mut cfg: CarLifeConfig, state: Shared, reset_request: Arc<Notify>) -> Result<(),String> {
    let (mut rd, mut wr) = stream.into_split();
    if !send_cmd(&mut wr, CMD_HU_PROTOCOL_VERSION, &proto_version_payload()).await { return Err("version write failed".into()); }
    let mut buf=Vec::new(); let mut chunk=[0;4096];
    let mut last_reset: Option<tokio::time::Instant> = None;
    loop {
        let started=state.lock().started;
        let read = tokio::select! {
            _ = reset_request.notified(), if started => {
                // mcarplay CarLifeSession.requestKeyFrame: CMD 0x1800B, not CTRL or START.
                if last_reset.map(|t| t.elapsed() >= Duration::from_secs(1)).unwrap_or(true) {
                    if !send_cmd(&mut wr, 0x1800b, &[]).await { return Err("CarWith RESET write failed".into()); }
                    last_reset = Some(tokio::time::Instant::now());
                    emit_log("CARWITH-HS: TX 0x1800b VIDEO_ENCODER_RESET (keyframe request)");
                }
                continue;
            }
            result = async {
                if started { Ok(rd.read(&mut chunk).await) }
                else { tokio::time::timeout(Duration::from_secs(20),rd.read(&mut chunk)).await }
            } => result.map_err(|_| "CarWith command timeout")?,
        };
        let n=read.map_err(|e|e.to_string())?;
        if n==0 { return Err("CarWith command EOF".into()); }
        buf.extend_from_slice(&chunk[..n]);
        while let Some((kind, payload, consumed)) = read_cmd_frame(&buf) {
            buf.drain(..consumed);
            let payload=state.lock().decrypt(&payload)?;
            let display=*requested.lock(); cfg.width=display.0; cfg.height=display.1;
            let cfg_copy=cfg.clone(); let session=state.clone(); let decoded=payload.clone();
            let actions=tokio::task::spawn_blocking(move || session.lock().receive(kind,&decoded,&cfg_copy)).await.map_err(|e|e.to_string())??;
            let status={let s=state.lock(); (s.proof_ok,s.admitted,s.ready)};
            emit_log(&format!("CARWITH-HS: RX 0x{kind:x} {}B proof={} admitted={} encrypted={}",payload.len(),status.0,status.1,status.2));
            if kind==CMD_VIDEO_ENCODER_INIT_DONE {
                let (w,h)=parse_encoder_info(&payload); *size.lock()=(w,h);
                emit_log(&format!("CARWITH-SIZE: actual={w}x{h}; touch target uses actual source pixels"));
                let _=events.send(CarLifeEvent::EncoderResolution { width:w,height:h });
            }
            for (id,body) in actions {
                let encrypted=state.lock().encrypt(&body)?;
                if !send_cmd(&mut wr,id,&encrypted).await { return Err(format!("CarWith write 0x{id:x} failed")); }
                emit_log(&format!("CARWITH-HS: TX 0x{id:x} payload={}B wire={}B",body.len(),encrypted.len()));
                if id==CMD_VIDEO_ENCODER_INIT {
                    emit_log(&format!("CARWITH-SIZE: receiver={}x{} request={}x{} cap=2560x2560",cfg.width,cfg.height,first_int(&body,1).unwrap_or(0),first_int(&body,2).unwrap_or(0)));
                }
                if id==CMD_VIDEO_ENCODER_START {
                    let _=events.send(CarLifeEvent::Handshake);
                    emit_state("carlife_active","CarWith proof/admission/encryption complete; video START sent");
                }
            }
        }
    }
}

pub(super) async fn control(mut stream: TcpStream, queue: Arc<Mutex<Vec<Vec<u8>>>>, state: Shared, stop: Arc<Notify>) {
    let mut sent=0u64;
    loop {
        let frame={let mut q=queue.lock(); if q.is_empty(){None}else{Some(q.remove(0))}};
        if let Some(frame)=frame {
            let wire={
                let s=state.lock();
                if !s.started { continue; }
                let Some((kind,body,_))=read_cmd_frame(&frame) else {continue};
                let Ok(body)=s.encrypt(&body) else {return};
                build_cmd_frame(kind,&body)
            };
            if stream.write_all(&wire).await.is_err(){return;}
            sent+=1;
            if sent<=8 || sent%60==0 {emit_log(&format!("CARWITH-TOUCH: encrypted CTRL packet #{sent} wire={}B",wire.len()));}
        } else {tokio::select!{_=stop.notified()=>return,_=tokio::time::sleep(Duration::from_millis(8))=>{}}}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn cfg()->CarLifeConfig {CarLifeConfig{width:1280,height:720,fps:60,voice:0,bt_address:"00:11:22:33:44:55".into(),bt_name:"HyperCar".into()}}
    fn info()->Vec<u8> {let mut out=Vec::new(); for (field,text) in [(4,"Xiaomi"),(14,"test"),(20,"37")] {proto::string(&mut out,field,text);} out}
    fn prepared()->Session {
        let mut s=Session::default();
        s.receive(CMD_VERSION_MATCH_STATUS,&proto_authen_result_payload(true),&cfg()).unwrap();
        let actions=s.receive(CMD_MD_INFO,&info(),&cfg()).unwrap();
        assert_eq!(actions.iter().map(|a|a.0).collect::<Vec<_>>(),vec![CMD_HU_INFO,CMD_HU_AUTHEN_REQUEST]); s
    }
    fn proof(s:&Session)->Vec<u8> {
        let digest=format!("{:x}",md5::Md5::digest(format!("CARLIFEXiaomitest37{}",&s.challenge[4..]).as_bytes()));
        let mut out=Vec::new(); proto::string(&mut out,1,&digest); out
    }

    #[test] fn carwith_canvas_preserves_bmw_ratio_and_caps_both_axes() {
        assert_eq!(canvas_for_receiver(2560,744),(2560,744));
        assert_eq!(canvas_for_receiver(2732,794),(2560,744));
        assert_eq!(canvas_for_receiver(5120,2560),(2560,1280));
        assert_eq!(canvas_for_receiver(2000,4000),(1280,2560));
        assert_eq!(canvas_for_receiver(1920,720),(1920,720));
    }

    #[test] fn carwith_touch_uses_separate_message_ids_and_actual_source_bounds() {
        let mut gesture=TouchGesture::default();
        assert!(gesture.accept(TOUCH_MOVE,100,100,(1280,372)).is_none());
        assert!(gesture.accept(TOUCH_UP,100,100,(1280,372)).is_none());
        let down=gesture.accept(TOUCH_DOWN,640,186,(1280,372)).unwrap();
        let (kind,body,_)=read_cmd_frame(&down).unwrap();
        assert_eq!(kind,0x68002); assert_eq!(first_int(&body,1),Some(640));assert_eq!(first_int(&body,2),Some(186));
        let moved=gesture.accept(TOUCH_MOVE,u32::MAX,u32::MAX,(1280,372)).unwrap();
        let (kind,body,_)=read_cmd_frame(&moved).unwrap();
        assert_eq!(kind,0x68004); assert_eq!(first_int(&body,1),Some(1279));assert_eq!(first_int(&body,2),Some(371));
        let released=gesture.accept(TOUCH_UP,0,0,(1280,372)).unwrap();
        let (kind,body,_)=read_cmd_frame(&released).unwrap();
        assert_eq!(kind,0x68003);assert_eq!(first_int(&body,1),Some(1279));assert_eq!(first_int(&body,2),Some(371));
        assert!(gesture.accept(TOUCH_UP,0,0,(1280,372)).is_none());
    }

    #[test] fn channel_admission_never_skips_proof_features_or_encryption() {
        let mut s=prepared();
        assert_eq!(s.receive(CMD_MD_AUTHEN_RESULT,&proto_authen_result_payload(true),&cfg()).unwrap().len(),1);
        assert!(s.receive(CMD_MD_FEATURE_CONFIG_REQUEST,&[],&cfg()).unwrap().is_empty());
        assert!(!s.init_sent);
        let actions=s.receive(CMD_MD_AUTHEN_RESPONSE,&proof(&s),&cfg()).unwrap();
        assert_eq!(actions.iter().map(|a|a.0).collect::<Vec<_>>(),vec![CMD_HU_AUTHEN_RESULT,CMD_HU_FEATURE_CONFIG_RESPONSE]);
        assert_eq!(parse_feature_config(&actions[1].1),vec![("CONTENT_ENCRYPTION".into(),1)]);
        assert!(!s.init_sent);
        assert!(s.receive(CMD_MD_ENCRYPT_READY,&[],&cfg()).is_err());
    }

    #[test] fn invalid_proof_admission_and_stale_results_cannot_start_video() {
        let mut s=prepared();
        assert!(s.receive(CMD_MD_AUTHEN_RESPONSE,&[],&cfg()).is_err());
        assert!(s.receive(CMD_MD_AUTHEN_RESULT,&proto_authen_result_payload(false),&cfg()).is_err());
        assert!(s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg()).is_err());
        let mut fresh=Session::default();
        assert_ne!(s.challenge,fresh.challenge);
        assert!(fresh.receive(CMD_MD_AUTHEN_RESULT,&proto_authen_result_payload(true),&cfg()).is_err());
    }

    #[test] fn rsa_seed_and_peer_ready_gate_encrypted_video_init() {
        let mut s=prepared();
        s.receive(CMD_MD_FEATURE_CONFIG_REQUEST,&[],&cfg()).unwrap();
        s.receive(CMD_MD_AUTHEN_RESPONSE,&proof(&s),&cfg()).unwrap();
        s.receive(CMD_MD_AUTHEN_RESULT,&proto_authen_result_payload(true),&cfg()).unwrap();
        s.receive(CMD_MD_RSA_KEY_REQUEST,&[],&cfg()).unwrap();
        let key=s.rsa.as_ref().unwrap().to_public_key();
        let seed=[7u8;16];
        let encrypted=key.encrypt(&mut rand::thread_rng(),rsa::Pkcs1v15Encrypt,&seed).unwrap();
        let mut body=Vec::new(); proto::string(&mut body,1,&base64::Engine::encode(&base64::engine::general_purpose::STANDARD,encrypted));
        assert_eq!(s.receive(CMD_MD_AES_SEED,&body,&cfg()).unwrap()[0].0,CMD_HU_AES_ACK);
        assert!(!s.init_sent);
        let actions=s.receive(CMD_MD_ENCRYPT_READY,&[],&cfg()).unwrap();
        assert_eq!(actions[0].0,CMD_VIDEO_ENCODER_INIT);
        let wire=s.encrypt(&actions[0].1).unwrap();
        assert_ne!(wire,actions[0].1);
        assert_eq!(s.decrypt(&wire).unwrap(),actions[0].1);
        assert_eq!(first_int(&actions[0].1,3),Some(30));
        assert!(s.receive(CMD_MD_AES_SEED,&body,&cfg()).is_err());
    }

    #[tokio::test]
    async fn reset_waits_for_video_start_and_uses_command_channel_with_throttling() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        async fn next(stream: &mut TcpStream) -> u32 {
            let mut header = [0; 8]; stream.read_exact(&mut header).await.unwrap();
            let mut body = vec![0; u16::from_be_bytes([header[0], header[1]]) as usize];
            stream.read_exact(&mut body).await.unwrap();
            u32::from_be_bytes(header[4..8].try_into().unwrap())
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = Shared::new(Mutex::new(Session::default()));
        let reset = Arc::new(Notify::new());
        let (events, _rx) = mpsc::unbounded_channel();
        let size = Arc::new(Mutex::new((1280, 720)));
        let task = tokio::spawn(run(stream, events, size.clone(), size, cfg(), state.clone(), reset.clone()));
        assert_eq!(next(&mut phone).await, CMD_HU_PROTOCOL_VERSION);
        reset.notify_one();
        assert!(tokio::time::timeout(Duration::from_millis(30), next(&mut phone)).await.is_err());
        state.lock().started = true;
        phone.write_all(&build_cmd_frame(0x10022, &[])).await.unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), next(&mut phone)).await.unwrap(), 0x1800b);
        reset.notify_one();
        assert!(tokio::time::timeout(Duration::from_millis(30), next(&mut phone)).await.is_err());
        drop(phone);
        assert!(tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap().is_err());
    }

    #[test] fn aes_ecb_matches_known_block_and_rejects_bad_padding() {
        let key:Vec<u8>=(0u8..16).collect();
        let clear:Vec<u8>=(0u8..16).map(|i|i*17).collect();
        let encrypted=crypt(&clear,Some(&key),true).unwrap();
        assert_eq!(encrypted[..16],[0x69,0xc4,0xe0,0xd8,0x6a,0x7b,0x04,0x30,0xd8,0xcd,0xb7,0x80,0x70,0xb4,0xc5,0x5a]);
        assert_eq!(crypt(&encrypted,Some(&key),false).unwrap(),clear);
        assert!(crypt(&encrypted[..16],Some(&key),false).is_err());
        for n in [16,24,32] {let key=vec![1;n];let bytes=b"CarWith wire payload"; assert_eq!(crypt(&crypt(bytes,Some(&key),true).unwrap(),Some(&key),false).unwrap(),bytes);}
    }

    #[tokio::test]
    #[ignore = "requires a real Xiaomi phone with CarWith entry opened"]
    async fn live_carwith_proof_encryption_and_video() {
        use std::io::Write;
        let stop=Arc::new(Notify::new());
        let mut config=cfg();config.width=2732;config.height=794;
        let (_hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),None).unwrap();
        let mut authenticated=false;let mut frames=0;let mut touched=false;
        let began=std::time::Instant::now();
        let mut capture=std::fs::File::create("/data/local/tmp/hypercar-carwith-video.h264").unwrap();
        let result=tokio::time::timeout(Duration::from_secs(35),async {
            while let Some(event)=events.recv().await {match event {
                CarLifeEvent::Handshake=>authenticated=true,
                CarLifeEvent::VideoFrame{data,..}=>{
                    assert!(authenticated);assert!(data.starts_with(&[0,0,0,1])||data.starts_with(&[0,0,1]));
                    capture.write_all(&data).unwrap();frames+=1;
                    if !touched && began.elapsed()>=Duration::from_secs(4) {
                        let actual=*_hub.encoder_size_handle().lock();
                        println!("CarWith touch test: actual={}x{}",actual.0,actual.1);
                        let (x,y)=(actual.0*45/1280,actual.1*665/720);
                        _hub.inject_touch(TOUCH_DOWN,x,y);tokio::time::sleep(Duration::from_millis(100)).await;
                        _hub.inject_touch(TOUCH_UP,x,y);touched=true;
                    }
                    if frames>=60 && began.elapsed()>=Duration::from_secs(12) {break;}
                }
                _=>{}
            }}
        }).await;
        stop.notify_waiters();
        assert!(result.is_ok(),"CarWith did not produce decrypted video after authenticated handshake");
        assert!(authenticated && frames>=60);
        println!("CarWith verified: {frames} decrypted frames across {:?}",began.elapsed());
    }
}
