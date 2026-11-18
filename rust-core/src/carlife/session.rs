//! Xiaomi CarWith 4.1: channel admission, challenge proof and content encryption are separate gates.
use super::*;
use crate::emit_state;
use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use rsa::pkcs8::EncodePublicKey;
use sha2::Digest;

pub(super) type Shared = Arc<Mutex<Session>>;

#[cfg(test)]
mod video_packet_probe;
#[cfg(test)]
pub(super) use video_packet_probe::VideoPacketProbe;

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
    #[cfg(test)]
    probe_reset: Option<Arc<Notify>>,
    #[cfg(test)]
    probe_reset_written: bool,
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
    fallback_init: Option<(u32,u32)>,
    declared_display: Option<(u32,u32)>,
    pub started: bool,
    matched: bool,
    navigation_requested: bool,
    navigation_started: bool,
}

impl Default for Session {
    fn default() -> Self {
        Self { challenge: format!("2.0;{}", uuid::Uuid::new_v4().simple()), info: None,
            #[cfg(test)]
            probe_reset: None,
            #[cfg(test)]
            probe_reset_written: false,
            proof_ok: false, admitted: false, features_requested: false, features_sent: false,
            rsa: None, seed: None, ready: false, init_sent: false, fallback_init: None, declared_display: None, started: false, matched: false, navigation_requested: false, navigation_started: false }
    }
}

#[cfg(test)]
pub(super) fn microphone_test_session()->Shared { let mut s=Session::default();s.started=true;s.proof_ok=true;s.admitted=true;s.ready=true;s.seed=Some(vec![7;16]);Arc::new(Mutex::new(s)) }

type Action = (u32, Vec<u8>);
fn navigation_subscription()->Vec<u8> {
    let mut module=Vec::new();proto::varint(&mut module,1,0);
    let mut list=Vec::new();proto::varint(&mut list,1,1);proto::bytes(&mut list,2,&module);list
}
impl Session {
    pub(super) fn navigation_ready(&self) -> bool { self.proof_ok && self.admitted && self.ready }
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
                #[cfg(test)]
                let keygen_started = {
                    log::info!(target: "carwith_probe", "RSA-2048 keygen begin");
                    std::time::Instant::now()
                };
                let key = rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).map_err(|e| e.to_string())?;
                #[cfg(test)]
                log::info!(target: "carwith_probe", "RSA-2048 keygen done duration_us={}", keygen_started.elapsed().as_micros());
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
                if cfg.navigation_updates {
                    self.navigation_requested=true;
                    out.push((0x18043, navigation_subscription()));
                }
            }
            0x10044 if self.navigation_requested && !self.navigation_started && self.navigation_ready() => {
                if super::navigation::supports_turn_updates(body)==Some(true) {
                    self.navigation_started=true;
                    out.push((0x18045,navigation_subscription()));
                    emit_log("CARWITH-NAV: peer supports module=0; START turn-by-turn subscription");
                } else {
                    self.navigation_requested=false;
                    emit_log("CARWITH-NAV: module=0 unsupported or invalid capability response; do not subscribe");
                }
            }
            CMD_FOREGROUND | CMD_SCREEN_ON => {
                if self.matched { out.push((CMD_HU_GO_TO_FOREGROUND, vec![])); }
            }
            0x10024 if self.started && self.navigation_ready() => {
                if let Some(mic) = cfg.microphone.as_ref() {
                    emit_log("CARWITH-MIC: RECOG_START; request declared car microphone"); mic.request(true);
                }
            }
            0x10023 => { if let Some(mic) = cfg.microphone.as_ref() { mic.request(false); } }
            0x10022 => { if cfg.microphone.is_some() { emit_log("CARWITH-MIC: background WAKEUP_START ignored; recognition requests only"); } }
            CMD_MD_EXIT => return Err("peer exited CarWith".into()),
            _ => {}
        }
        if self.proof_ok && self.features_requested && !self.features_sent {
            self.features_sent = true;
            let mut features = vec![("CONTENT_ENCRYPTION", 1), ("VOICE_MIC", u64::from(cfg.microphone.is_none()))];
            if self.declared_display.is_some() {
                features.push(("DISABLE_RESOLUTION_OPTION", 1));
            }
            let mut payload = Vec::new();
            proto::varint(&mut payload, 1, features.len() as u64);
            for (key, value) in features {
                let mut entry = Vec::new();
                proto::string(&mut entry, 1, key);
                proto::varint(&mut entry, 2, value);
                proto::bytes(&mut payload, 2, &entry);
            }
            out.push((CMD_HU_FEATURE_CONFIG_RESPONSE, payload));
        }
        if self.proof_ok && self.admitted && self.ready && !self.init_sent {
            self.init_sent = true;
            let (width,height) = self.declared_display.unwrap_or((cfg.width,cfg.height));
            let (w,h) = canvas_for_receiver(width, height);
            if self.declared_display.is_none() { self.fallback_init = Some((w,h)); }
            out.push((CMD_VIDEO_ENCODER_INIT, proto_encoder_info_payload(w,h,cfg.fps)));
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
    size: Arc<Mutex<(u32,u32)>>, requested: DisplayRequest, cfg: CarLifeConfig, state: Shared, reset_request: Arc<Notify>) -> Result<(),String> {
    let (mut rd, mut wr) = stream.into_split();
    if !send_cmd(&mut wr, CMD_HU_PROTOCOL_VERSION, &proto_version_payload()).await { return Err("version write failed".into()); }
    #[cfg(test)]
    log::info!(target: "carwith_probe", "CMD TX written id=0x{CMD_HU_PROTOCOL_VERSION:x}");
    let mut buf=Vec::new(); let mut chunk=[0;4096];
    let mut display_updates = requested.subscribe();
    let mut last_reset: Option<tokio::time::Instant> = None;
    #[cfg(test)]
    { state.lock().probe_reset = Some(reset_request.clone()); }
    let mut navigation_diagnostics = super::navigation::Diagnostics::default();
    loop {
        let started=state.lock().started;
        // An already initialized handset can ACK another INIT with its old size.
        // Reopen only the local source so feature negotiation and INIT see the display.
        let display = *display_updates.borrow_and_update();
        if started && display.0 >= 2 && display.1 >= 2 {
            let target = canvas_for_receiver(display.0, display.1);
            if state.lock().fallback_init.is_some_and(|initial| initial != target) {
                *size.lock() = (0, 0);
                emit_log(&format!("CARWITH-SIZE: late receiver={}x{} restart local source once for {}x{}",display.0,display.1,target.0,target.1));
                return Ok(());
            }
        }
        let read = tokio::select! {
            _ = display_updates.changed() => continue,
            _ = reset_request.notified(), if started => {
                // mcarplay CarLifeSession.requestKeyFrame: CMD 0x1800B, not CTRL or START.
                if last_reset.map(|t| t.elapsed() >= Duration::from_secs(1)).unwrap_or(true) {
                    if !send_cmd(&mut wr, 0x1800b, &[]).await { return Err("CarWith RESET write failed".into()); }
                    #[cfg(test)]
                    { state.lock().probe_reset_written = true; }
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
            // Production RX diagnostics below run after processing, which can include key generation.
            #[cfg(test)]
            log::info!(target: "carwith_probe", "CMD RX complete id=0x{kind:x} wire_bytes={consumed}");
            buf.drain(..consumed);
            let payload=state.lock().decrypt(&payload)?;
            let display=*requested.borrow();
            let declared = (display.0 >= 2 && display.1 >= 2).then_some(display);
            let cfg_copy=cfg.clone(); let session=state.clone(); let decoded=payload.clone();
            let actions=tokio::task::spawn_blocking(move || {
                let mut session = session.lock();
                session.declared_display = declared;
                session.receive(kind,&decoded,&cfg_copy)
            }).await.map_err(|e|e.to_string())??;
            let status={let s=state.lock(); (s.proof_ok,s.admitted,s.ready)};
            if matches!(kind, super::navigation::NEXT_TURN | super::navigation::ASSISTANT) {
                let trace=navigation_diagnostics.observe("CMD",kind,consumed-8,Some(payload.len()));
                dispatch_navigation(kind,&payload,&state,&events,trace);
            } else {
                emit_log(&format!("CARWITH-HS: RX 0x{kind:x} {}B proof={} admitted={} encrypted={}",payload.len(),status.0,status.1,status.2));
            }
            if kind==CMD_VIDEO_ENCODER_INIT_DONE {
                let (w,h)=parse_encoder_info(&payload); *size.lock()=(w,h);
                emit_log(&format!("CARWITH-SIZE: actual={w}x{h}; touch target uses actual source pixels"));
                let _=events.send(CarLifeEvent::EncoderResolution { width:w,height:h });
            }
            if kind == CMD_GO_TO_DESKTOP && payload.is_empty() && status == (true,true,true) && state.lock().started {
                let _ = events.send(CarLifeEvent::GoToDesktop);
            }
            for (id,body) in actions {
                let encrypted=state.lock().encrypt(&body)?;
                if !send_cmd(&mut wr,id,&encrypted).await { return Err(format!("CarWith write 0x{id:x} failed")); }
                emit_log(&format!("CARWITH-HS: TX 0x{id:x} payload={}B wire={}B",body.len(),encrypted.len()));
                if id == CMD_HU_FEATURE_CONFIG_RESPONSE {
                    emit_log(&format!("CARWITH-SIZE: prefer_vehicle_resolution={}", declared.is_some()));
                }
                if id==CMD_VIDEO_ENCODER_INIT {
                    let (width,height) = declared.unwrap_or((cfg.width,cfg.height));
                    emit_log(&format!("CARWITH-SIZE: receiver={width}x{height} request={}x{} cap=2560x2560 requested_fps={}",first_int(&body,1).unwrap_or(0),first_int(&body,2).unwrap_or(0),cfg.fps));
                }
                if id==CMD_VIDEO_ENCODER_START {
                    emit_log("CARWITH-NAV: authenticated CMD/DATA monitoring 0x10030; dashboard card alone does not confirm HUD output");
                    let _=events.send(CarLifeEvent::Handshake);
                    emit_state("carlife_active","CarWith proof/admission/encryption complete; video START sent");
                }
            }
        }
    }
}

pub(super) async fn control(mut stream: TcpStream, queue: Arc<Mutex<Vec<QueuedControl>>>, state: Shared, stop: Arc<Notify>) {
    let mut sent=0u64;
    let mut last_selector: Option<(u64, std::time::Instant)> = None;
    loop {
        let frame={let mut q=queue.lock(); if q.is_empty(){None}else{Some(q.remove(0))}};
        if let Some(packet)=frame {
            let Some((kind, body, _)) = read_cmd_frame(&packet.bytes) else {continue};
            let hard_key = if kind == CTRL_CAR_HARD_KEY_CODE { first_int(&body, 1) } else { None };
            let selector = hard_key.filter(|&code| matches!(code, SELECTOR_PREVIOUS | SELECTOR_NEXT));
            // CarlifeTouchManager drops an identical hard key within 50ms of the previous one.
            if let (Some(code), Some((previous, sent_at))) = (selector, last_selector) {
                let due = sent_at + Duration::from_millis(55);
                if code == previous && due > std::time::Instant::now() {
                    if packet.expires.is_some_and(|expires| due >= expires) { continue; }
                    tokio::select! {
                        _ = stop.notified() => return,
                        _ = tokio::time::sleep_until(due.into()) => {}
                    }
                }
            }
            if packet.expires.is_some_and(|at|std::time::Instant::now()>=at) {continue;}
            let wire={
                let s=state.lock();
                if !s.started { if packet.expires.is_some(){emit_log("CARWITH-CTRL: source not ready; command dropped");} continue; }
                let Ok(body)=s.encrypt(&body) else {return};
                build_cmd_frame(kind,&body)
            };
            if stream.write_all(&wire).await.is_err(){return;}
            sent+=1;
            if let Some(code) = hard_key {
                last_selector = selector.map(|key| (key, std::time::Instant::now()));
                if code == 0x21 || sent <= 8 || sent % 60 == 0 {
                    emit_log(&format!("CARWITH-KEY: encrypted CTRL write key={code}"));
                }
            }
            if sent<=8 || sent%60==0 {emit_log(&format!("CARWITH-TOUCH: encrypted CTRL packet #{sent} wire={}B",wire.len()));}
        } else {tokio::select!{_=stop.notified()=>return,_=tokio::time::sleep(Duration::from_millis(8))=>{}}}
    }
}

fn dispatch_navigation(kind:u32, payload:&[u8], state:&Shared, events:&mpsc::UnboundedSender<CarLifeEvent>, trace:bool) {
    if kind != super::navigation::NEXT_TURN {return;}
    if !state.lock().navigation_ready() {
        if trace {emit_log("CARWITH-NAV: ignored before authenticated source ready");}
        return;
    }
    let update=super::navigation::decode(payload);
    if trace {
        if let Some(n)=&update {emit_log(&format!("CARWITH-NAV: decoded action={} turn={:?} distance={:?}m roadBytes={} (road/icon omitted)",n.action,n.turn,n.distance,n.road.len()));}
        else {emit_log("CARWITH-NAV: invalid navigation payload; clear old guidance");}
    }
    let _=events.send(CarLifeEvent::Navigation(update));
}

pub(super) async fn data(mut stream:TcpStream,state:Shared,events:mpsc::UnboundedSender<CarLifeEvent>) {
    emit_log("CARWITH-DATA: channel=DATA monitoring framed input (metadata only)");
    let mut buf=Vec::new(); let mut chunk=[0u8;4096];
    let mut diagnostic=super::navigation::Diagnostics::default();
    loop {
        let n=match stream.read(&mut chunk).await {Ok(0)|Err(_)=>return,Ok(n)=>n};
        buf.extend_from_slice(&chunk[..n]);
        while let Some((kind,payload,consumed))=read_cmd_frame(&buf) {
            buf.drain(..consumed);
            if matches!(kind, super::navigation::NEXT_TURN | super::navigation::ASSISTANT) && state.lock().navigation_ready() {
                let plain=state.lock().decrypt(&payload);
                let trace=diagnostic.observe("DATA",kind,payload.len(),plain.as_ref().ok().map(Vec::len));
                if let Ok(plain)=plain {dispatch_navigation(kind,&plain,&state,&events,trace);}
                else if kind==super::navigation::NEXT_TURN {let _=events.send(CarLifeEvent::Navigation(None));}
            } else {diagnostic.observe("DATA",kind,payload.len(),None);}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn repeated_rotary_steps_arrive_after_the_carwith_debounce_interval() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        let queue = Arc::new(Mutex::new(vec![]));
        for code in [6, 6, 7] {
            queue.lock().push(QueuedControl {
                bytes: build_cmd_frame(0x68008, &[8, code]),
                expires: None,
            });
        }
        let started = tokio::time::Instant::now();
        let writer = tokio::spawn(control(client, queue, state.clone(), Arc::new(Notify::new())));
        for expected in [6, 6, 7] {
            let mut frame = [0; 24];
            tokio::time::timeout(Duration::from_secs(2), peer.read_exact(&mut frame))
                .await.unwrap().unwrap();
            let (kind, body, _) = read_cmd_frame(&frame).unwrap();
            assert_eq!(kind, 0x68008);
            assert_eq!(state.lock().decrypt(&body).unwrap(), [8, expected]);
        }
        assert!(started.elapsed() >= Duration::from_millis(50));
        writer.abort();
    }

    #[test]
    fn console_keys_use_only_the_active_source_and_never_seek_tracks() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let hub = CarLifeHub {
            stop: Arc::new(DialStop::default()),
            touch_state: Arc::new(Mutex::new(TouchGesture::default())),
            touch_tx: tx,
            encoder_size: Arc::new(Mutex::new((1920, 720))),
            keyframe_request: Arc::new(Notify::new()),
        };
        let mut local_keys = Vec::new();
        for (key, wire_code) in [
            (0x30001, 14), (0x30002, 20), (0x30003, 21), (0x30004, 22),
            (0x30005, 23), (0x30006, 24), (0x30007, 7), (0x30008, 6),
        ] {
            assert_eq!(crate::voice_key::action(key), None);
            crate::engine::route_display_key(None, key, |k| local_keys.push(k));
            assert_eq!(local_keys.pop(), Some(key));
            crate::engine::route_display_key(Some(&hub), key, |k| local_keys.push(k));
            assert!(local_keys.is_empty(), "CarWith console key leaked to the local display");
            let packet = rx.try_recv().expect("CarWith CTRL packet");
            assert!(packet.expires.is_some());
            assert_eq!(packet.bytes, [0, 2, 0, 0, 0, 6, 128, 8, 8, wire_code]);
        }
        crate::engine::route_display_key(Some(&hub), 0xb5, |k| local_keys.push(k));
        assert_eq!(local_keys, [0xb5]);
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn voice_start_uses_ctrl_protobuf_and_has_an_expiry() {
        let (tx,mut rx)=mpsc::unbounded_channel();
        let hub=CarLifeHub{stop:Arc::new(DialStop::default()),touch_state:Arc::new(Mutex::new(TouchGesture::default())),touch_tx:tx,
            encoder_size:Arc::new(Mutex::new((1920,720))),keyframe_request:Arc::new(Notify::new())};
        {
            let expected=33;
            hub.voice_start();
            let packet=rx.recv().await.unwrap();
            assert!(packet.expires.unwrap()>std::time::Instant::now());
            let (kind,body,_)=read_cmd_frame(&packet.bytes).unwrap();
            assert_eq!(kind,0x68008);assert_eq!(first_int(&body,1),Some(expected));
        }
    }
    use tokio::net::TcpListener;
    fn cfg()->CarLifeConfig {CarLifeConfig{width:1280,height:720,fps:60,navigation_updates:false,microphone:None,bt_address:"00:11:22:33:44:55".into(),bt_name:"HyperCar".into()}}

    #[tokio::test(start_paused = true)]
    async fn pause_at_canvas_deadline_prevents_reopen_and_next_port_dial() {
        let stop = Arc::new(DialStop::default());
        let (events, mut rx) = mpsc::unbounded_channel();
        let waiting = wait_for_canvas_reopen(&events, &stop);
        tokio::pin!(waiting);
        let reset = tokio::select! {
            biased;
            _ = &mut waiting => panic!("timer finished before cache reset"),
            event = rx.recv() => event.unwrap(),
        };
        let CarLifeEvent::SourceReset { cache_cleared: Some(done) } = reset else { panic!("missing reset"); };
        done.notify_one();
        tokio::select! { biased; _ = &mut waiting => panic!("timer skipped"), _ = tokio::task::yield_now() => {} }
        tokio::time::advance(Duration::from_secs(4)).await;
        // Both branches are ready for the same poll. Cancellation also survives into a new dial loop.
        stop.cancel();
        assert!(!waiting.await);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ports = [listener.local_addr().unwrap().port(); 7];
        let (_hub, mut output) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            stop.clone(), None, || panic!("retired owner reopened")).unwrap();
        assert!(output.recv().await.is_none());
        assert!(tokio::time::timeout(Duration::from_millis(1), listener.accept()).await.is_err());
    }

    #[tokio::test]
    async fn same_address_dialer_waits_for_all_seven_previous_sockets() {
        // The entry command already reached Android. Both owners see the same listeners.
        let mut listeners = Vec::new();
        let mut ports = [0; 7];
        for port in &mut ports {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            *port = listener.local_addr().unwrap().port();
            listeners.push(listener);
        }
        let old_stop = Arc::new(DialStop::default());
        let (_old, mut old_events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            old_stop.clone(), None, || {}).unwrap();
        let mut peers = Vec::new();
        for listener in &listeners { peers.push(listener.accept().await.unwrap().0); }
        assert!(matches!(old_events.recv().await, Some(CarLifeEvent::SourceReset { .. })));
        // An intermediate cancelled invitation must not let its successor bypass the old task.
        let skipped_stop = Arc::new(DialStop::default());
        let (_skipped, mut skipped_events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            skipped_stop.clone(), None, || {}).unwrap();
        skipped_stop.cancel();
        let (_new, _events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            Arc::new(DialStop::default()), None, || {}).unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(80), listeners[0].accept()).await.is_err(),
            "new owner dialed while the previous seven sockets were live");
        old_stop.cancel();
        let _new_cmd = tokio::time::timeout(Duration::from_secs(2), listeners[0].accept()).await.unwrap().unwrap();
        assert!(skipped_events.recv().await.is_none());
        for peer in peers {
            // Nonblocking: require EOF already observable when the successor first connects.
            // CMD may still have a buffered protocol-version announcement before its FIN.
            let mut socket = peer.into_std().unwrap();
            std::io::Read::read_to_end(&mut socket, &mut Vec::new()).expect("old socket was still open");
        }
    }
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

    #[tokio::test] async fn late_display_repro_requests_once_and_uses_the_returned_canvas() {
        async fn frame(phone: &mut TcpStream) -> (u32, Vec<u8>) {
            let mut header = [0; 8];
            phone.read_exact(&mut header).await.unwrap();
            let mut body = vec![0; u16::from_be_bytes([header[0], header[1]]) as usize];
            phone.read_exact(&mut body).await.unwrap();
            (u32::from_be_bytes(header[4..8].try_into().unwrap()), body)
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let size = Arc::new(Mutex::new((0, 0)));
        let display = DisplayRequest::new((0, 0));
        let (events, _rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new())));
        assert_eq!(frame(&mut phone).await.0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_INIT);
        let reply = |w,h| build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,
            &state.lock().encrypt(&proto_encoder_info_payload(w,h,60)).unwrap());
        phone.write_all(&reply(1024,576)).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_START);
        display.send_replace((2880,960));
        tokio::time::timeout(Duration::from_secs(1),task).await.expect("late display did not restart source").unwrap().unwrap();
        drop(phone);
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let (events, mut rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new())));
        assert_eq!(frame(&mut phone).await.0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        let (kind, bytes) = frame(&mut phone).await;
        assert_eq!(kind, CMD_VIDEO_ENCODER_INIT);
        let fields = state.lock().decrypt(&bytes).unwrap();
        assert_eq!((first_int(&fields,1), first_int(&fields,2)), (Some(2560),Some(852)));
        assert_eq!(*size.lock(),(0,0), "the fallback touch canvas survived the renegotiation gap");
        let actual = state.lock().encrypt(&proto_encoder_info_payload(2400,800,60)).unwrap();
        phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &actual)).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_START);
        assert_eq!(*size.lock(),(2400,800));
        assert!(std::iter::from_fn(||rx.try_recv().ok()).any(|e|matches!(e,CarLifeEvent::EncoderResolution{width:2400,height:800})));
        display.send_replace((2880,960));
        assert!(tokio::time::timeout(Duration::from_millis(50),frame(&mut phone)).await.is_err());
        drop(phone);
        assert!(task.await.unwrap().is_err());
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
        assert_eq!(actions[1].1, b"\x08\x02\x12\x16\x0a\x12CONTENT_ENCRYPTION\x10\x01\x12\x0d\x0a\x09VOICE_MIC\x10\x01");
        assert!(!s.init_sent);
        assert!(s.receive(CMD_MD_ENCRYPT_READY,&[],&cfg()).is_err());
    }

    #[test]
    fn receiver_canvas_is_advertised_and_latest_declaration_wins_at_init() {
        let c = cfg();
        let mut s = prepared();
        s.proof_ok = true;
        s.declared_display = Some((1920,1080)); // same receiver's cached size
        let actions = s.receive(CMD_MD_FEATURE_CONFIG_REQUEST, &[], &c).unwrap();
        let fields = &actions.iter().find(|a| a.0 == CMD_HU_FEATURE_CONFIG_RESPONSE).unwrap().1;
        assert_eq!(first_int(fields, 1), Some(3));
        assert!(fields.ends_with(b"\x12\x1d\x0a\x19DISABLE_RESOLUTION_OPTION\x10\x01"));
        s.declared_display = Some((2760,800));
        s.admitted = true;
        s.ready = true;
        let actions = s.receive(CMD_SCREEN_ON, &[], &c).unwrap();
        let init = &actions.iter().find(|a| a.0 == CMD_VIDEO_ENCODER_INIT).unwrap().1;
        assert_eq!((first_int(init,1), first_int(init,2), first_int(init,3)), (Some(2560), Some(742), Some(60)));
    }

    #[tokio::test]
    async fn desktop_click_reaches_live_source_and_preserves_cmd_connection() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let (events, mut rx) = mpsc::unbounded_channel();
        let size = Arc::new(Mutex::new((1280,720)));
        let task = tokio::spawn(run(stream,events,size.clone(),DisplayRequest::new((0,0)),cfg(),state.clone(),Arc::new(Notify::new())));
        let click = build_cmd_frame(CMD_GO_TO_DESKTOP,&[]);
        phone.write_all(&click).await.unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(100),rx.recv()).await.is_err());
        state.lock().started = true;
        for _ in 0..2 {
            phone.write_all(&click).await.unwrap();
            let event = tokio::time::timeout(Duration::from_secs(2),rx.recv()).await.unwrap();
            assert!(matches!(event,Some(CarLifeEvent::GoToDesktop)));
        }
        drop(phone);
        assert!(task.await.unwrap().is_err());
    }

    #[test] fn car_microphone_requires_explicit_option_authentication_and_recognition() {
        let mic=Arc::new(crate::mic_probe::ProbeControl::for_carwith());mic.arm_test();
        let mut cfg=cfg();cfg.microphone=Some(mic.clone());
        let mut session=prepared();
        session.receive(0x10024,&[],&cfg).unwrap();assert_eq!(mic.generation()&1,0);
        session.receive(CMD_MD_FEATURE_CONFIG_REQUEST,&[],&cfg).unwrap();
        let actions=session.receive(CMD_MD_AUTHEN_RESPONSE,&proof(&session),&cfg).unwrap();
        assert_eq!(*actions.last().unwrap().1.last().unwrap(),0);
        session.started=true;session.admitted=true;session.ready=true;
        session.receive(0x10022,&[],&cfg).unwrap();assert_eq!(mic.generation()&1,0);
        session.receive(0x10024,&[],&cfg).unwrap();let first=mic.generation();assert_eq!(first&1,1);
        session.receive(0x10024,&[],&cfg).unwrap();assert_eq!(mic.generation(),first);
        session.receive(0x10023,&[],&cfg).unwrap();assert_eq!(mic.generation()&1,0);
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
        assert_eq!(first_int(&actions[0].1,3),Some(u64::from(cfg().fps)));
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
        let task = tokio::spawn(run(stream, events, size.clone(), DisplayRequest::new((0,0)), cfg(), state.clone(), reset.clone()));
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
        let stop=Arc::new(DialStop::default());
        let mut config=cfg();config.width=2732;config.height=794;
        let (_hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),None, || {}).unwrap();
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
                        let actual=*_hub.encoder_size.lock();
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
        stop.cancel();
        assert!(result.is_ok(),"CarWith did not produce decrypted video after authenticated handshake");
        assert!(authenticated && frames>=60);
        println!("CarWith verified: {frames} decrypted frames across {:?}",began.elapsed());
    }

    /// Source-only timing probe: no car connection, saved video, or injected user input.
    #[tokio::test]
    #[ignore = "requires a Xiaomi phone with the local CarWith entry open"]
    async fn live_carwith_late_display() {
        start_probe_timeline();
        // Optional test input to vary encoder packet lengths on a static phone display.
        let height = std::env::var("HC_PROBE_DISPLAY_HEIGHT").map(|value| value.parse::<u32>().unwrap()).unwrap_or(960);
        let receiver = (2880, height);
        let display = DisplayRequest::new((0, 0));
        let stop = Arc::new(DialStop::default());
        let (_hub, mut events) = CarLifeHub::connect(cfg(), "127.0.0.1".into(), stop.clone(), Some(display.clone()), || {
            // This opt-in probe runs as adb shell; the app uses the attempt-scoped Kotlin callback.
            let status = std::process::Command::new("/system/bin/am").args(["start", "-n",
                "com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity"]).status().unwrap();
            assert!(status.success());
        }).unwrap();
        let mut rounds = 0;
        let result = tokio::time::timeout(Duration::from_secs(35), async {
            while let Some(event) = events.recv().await {
                match event {
                    CarLifeEvent::SourceReset { cache_cleared: Some(done) } => done.notify_one(),
                    CarLifeEvent::SourceReset { cache_cleared: None } => {
                        rounds += 1;
                        log::info!(target: "carwith_probe", "seven channels round={rounds}");
                    }
                    CarLifeEvent::EncoderResolution { width, height } =>
                        log::info!(target: "carwith_probe", "round={rounds} actual={width}x{height}"),
                    CarLifeEvent::VideoFrame {..} | CarLifeEvent::VideoFrameTagged {..} if rounds == 1 => {
                        display.send_if_modified(|value| {
                            if *value == receiver { return false; }
                            log::info!(target: "carwith_probe", "round={rounds} first video event; declare 2880x{height}");
                            *value = receiver;
                            true
                        });
                    }
                    CarLifeEvent::VideoFrame {..} | CarLifeEvent::VideoFrameTagged {..} if rounds == 2 => {
                        log::info!(target: "carwith_probe", "round={rounds} first video event");
                        return true;
                    }
                    _ => {}
                }
            }
            false
        }).await;
        stop.cancel();
        assert_eq!(result, Ok(true), "no video after late display, local rounds={rounds}");
    }

    fn start_probe_timeline() -> std::time::Instant {
        struct Timeline(std::time::Instant);
        impl log::Log for Timeline {
            fn enabled(&self, _: &log::Metadata<'_>) -> bool { true }
            fn log(&self, record: &log::Record<'_>) {
                let line=record.args().to_string();
                if record.target() == "carwith_probe" || line.starts_with("CARWITH-HS:") || line.starts_with("CARWITH-SIZE:") {
                    let epoch = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis();
                    println!("SOURCE-PROBE epoch_ms={epoch} +{}us {line}", self.0.elapsed().as_micros());
                }
            }
            fn flush(&self) {}
        }
        let began=std::time::Instant::now();
        let logger=Box::leak(Box::new(Timeline(began)));
        if log::set_logger(logger).is_ok() {log::set_max_level(log::LevelFilter::Info);}
        let epoch=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis();
        println!("SOURCE-PROBE start_epoch_ms={epoch}");
        began
    }

    /// Source-only timing probe: no car connection, saved video, or injected user input.
    #[tokio::test]
    #[ignore = "requires a real Xiaomi phone; launch CarWith after probe start"]
    async fn live_carwith_startup_timing() {
        let began = start_probe_timeline();
        let stop=Arc::new(DialStop::default());
        let mut config=cfg();config.width=2736;config.height=800;
        let (_hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),None, || {}).unwrap();
        let mut authenticated=false;
        let mut frames=0;
        let mut first_idr=None;
        let result=tokio::time::timeout(Duration::from_secs(25),async {
            while let Some(event)=events.recv().await {
                let (data,codec)=match event {
                    CarLifeEvent::SourceReset { .. }=>{println!("SOURCE-PROBE +{}ms all_channels_event",began.elapsed().as_millis());continue;},
                    CarLifeEvent::EncoderResolution{width,height}=>{println!("SOURCE-PROBE +{}ms encoder={width}x{height}",began.elapsed().as_millis());continue;},
                    CarLifeEvent::Handshake=>{authenticated=true;println!("SOURCE-PROBE +{}ms proof_admission_encryption_complete_video_START_written",began.elapsed().as_millis());continue;},
                    CarLifeEvent::VideoFrame{data,..}=>(data,crate::video_format::VideoCodec::H264),
                    CarLifeEvent::VideoFrameTagged{data,codec,..}=>(data,codec),
                    _=>continue,
                };
                frames+=1;
                let types:Vec<_>=crate::screen::annexb_nals(&data).iter().map(|nal|match codec {
                    crate::video_format::VideoCodec::H264=>nal[0]&31,
                    crate::video_format::VideoCodec::Hevc=>crate::hevc::nal_type(nal).unwrap_or(255),
                }).collect();
                if frames==1 {println!("SOURCE-PROBE +{}ms first_AU codec={codec:?} bytes={} types={types:?}",began.elapsed().as_millis(),data.len());}
                if first_idr.is_none() && types.iter().any(|&kind|match codec {
                    crate::video_format::VideoCodec::H264=>kind==5,
                    crate::video_format::VideoCodec::Hevc=>matches!(kind,19..=21),
                }) {
                    first_idr=Some(began.elapsed().as_millis());
                    println!("SOURCE-PROBE +{}ms first_IDR codec={codec:?}",first_idr.unwrap());
                }
                if authenticated && first_idr.is_some() && frames>=20 && began.elapsed()>=Duration::from_secs(3) {break;}
            }
        }).await;
        stop.cancel();
        println!("SOURCE-PROBE finished elapsed_ms={} authenticated={authenticated} frames={frames} first_idr_ms={first_idr:?}",began.elapsed().as_millis());
        assert!(result.is_ok() && authenticated && first_idr.is_some() && frames>=20,"CarWith source did not become ready within probe budget");
    }
}

#[cfg(test)]
mod navigation_tests {
    use super::*;
    fn ready_state() -> Shared {
        let mut s=Session::default();
        s.proof_ok=true; s.admitted=true; s.ready=true; s.started=true;
        s.seed=Some(vec![7;16]);
        Arc::new(Mutex::new(s))
    }
    #[test]
    fn navigation_requires_auth_and_invalid_updates_clear_previous_data() {
        let (tx,mut rx)=mpsc::unbounded_channel();
        let unready=Arc::new(Mutex::new(Session::default()));
        dispatch_navigation(0x10030,&[8,2,16,5,40,35],&unready,&tx,false);
        assert!(rx.try_recv().is_err());
        let state=ready_state();
        dispatch_navigation(0x10030,&[8,2,16,5,40,35],&state,&tx,false);
        assert!(matches!(rx.try_recv().unwrap(),CarLifeEvent::Navigation(Some(n)) if n.turn==Some(5) && n.distance==Some(35)));
        dispatch_navigation(0x10047,&[8,2,16,5,40,35],&state,&tx,false);
        assert!(rx.try_recv().is_err());
        dispatch_navigation(0x10030,&[8,2,16],&state,&tx,false);
        assert!(matches!(rx.try_recv().unwrap(),CarLifeEvent::Navigation(None)));
    }
    #[tokio::test]
    async fn data_channel_reassembles_and_decrypts_fragmented_navigation() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (server,_)=listener.accept().await.unwrap();
        let state=ready_state();
        let encrypted=state.lock().encrypt(&[8,2,16,5,40,35]).unwrap();
        let frame=build_cmd_frame(0x10030,&encrypted);
        let (tx,mut rx)=mpsc::unbounded_channel();
        let task=tokio::spawn(data(server,state,tx));
        for part in frame.chunks(3) {client.write_all(part).await.unwrap();}
        let event=tokio::time::timeout(Duration::from_secs(2),rx.recv()).await.unwrap().unwrap();
        assert!(matches!(event,CarLifeEvent::Navigation(Some(n)) if n.turn==Some(5) && n.distance==Some(35)));
        client.shutdown().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2),task).await.unwrap().unwrap();
    }
}

#[cfg(test)]
mod navigation_subscription_tests {
    use super::*;
    fn config(enabled:bool)->CarLifeConfig {CarLifeConfig{width:1280,height:720,fps:30,navigation_updates:enabled,microphone:None,bt_address:String::new(),bt_name:String::new()}}
    fn ready()->Session {let mut s=Session::default();s.proof_ok=true;s.admitted=true;s.ready=true;s.init_sent=true;s}
    #[test] fn capability_query_ack_then_start_only_once() {
        let mut s=ready();let c=config(true);
        let actions=s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&c).unwrap();
        assert_eq!(actions.iter().map(|a|a.0).collect::<Vec<_>>(),vec![CMD_VIDEO_ENCODER_START,0x18043]);
        assert_eq!(actions[1].1,vec![8,1,18,2,8,0]);
        let response=[8,1,18,4,8,0,16,1];
        let start=s.receive(0x10044,&response,&c).unwrap();
        assert_eq!(start,vec![(0x18045,vec![8,1,18,2,8,0])]);
        assert!(s.receive(0x10044,&response,&c).unwrap().is_empty());
    }
    #[test] fn disabled_unsupported_and_unsolicited_responses_do_not_start_subscription() {
        let mut s=ready();let c=config(false);
        assert_eq!(s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&c).unwrap().len(),1);
        assert!(s.receive(0x10044,&[8,1,18,4,8,0,16,1],&c).unwrap().is_empty());
        let mut s=ready();let c=config(true);
        s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&c).unwrap();
        assert!(s.receive(0x10044,&[8,1,18,4,8,0,16,0],&c).unwrap().is_empty());
        assert!(super::super::navigation::supports_turn_updates(&[8,2,18,4,8,0,16,1]).is_none());
    }
}
