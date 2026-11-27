//! Xiaomi CarWith 4.1: channel admission, challenge proof and content encryption are separate gates.
use super::*;
use crate::emit_state;
use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use rsa::pkcs8::EncodePublicKey;
use sha2::Digest;
use super::protocol_selection::Progress;
use std::sync::atomic::Ordering;

pub(super) type Shared = Arc<Mutex<Session>>;

/// A weak target always belongs to exactly one live CMD connection, never its retry.
#[derive(Default)]
pub(super) struct SystemVoiceTarget(Mutex<std::sync::Weak<Mutex<Session>>>);
impl SystemVoiceTarget {
    pub(super) fn bind(&self, state: &Shared) { *self.0.lock() = Arc::downgrade(state); }
    pub(super) fn clear(&self) { *self.0.lock() = std::sync::Weak::new(); }
    pub(super) fn epoch(&self) -> Option<u64> {
        let state=self.0.lock().upgrade()?;
        let session=state.lock();
        session.is_system_plain().then_some(session.voice_epoch)
    }
    pub(super) fn queue(&self, key: u64) -> bool {
        let Some(epoch)=self.epoch() else { return false; };
        self.queue_for_epoch(epoch,key)
    }
    pub(super) fn queue_for_epoch(&self, epoch:u64, key:u64) -> bool {
        // Hold the target lock through validation and enqueue, so a new connection
        // cannot inherit a delayed STOP from an old actor lease.
        let target=self.0.lock();
        let Some(state)=target.upgrade() else { return false; };
        let mut session=state.lock();
        session.voice_epoch==epoch && session.queue_voice(key)
    }
}

#[cfg(test)]
mod video_packet_probe;
#[cfg(test)]
mod auto_protocol_tests;
#[cfg(test)]
pub(super) use video_packet_probe::VideoPacketProbe;

/// Only CarWith uses this canvas policy; the self-rendered encoder policy is untouched.
pub(super) fn canvas_for_receiver(width:u32,height:u32)->(u32,u32) {
    if width<2 || height<2 {return (1280,720);}
    let divisor=width.max(height).max(2560) as u64;
    let even=|v:u32| ((v as u64*2560/divisor) as u32 & !1).max(2);
    (even(width),even(height))
}

#[derive(Clone, Default)]
pub(super) struct TouchGesture {
    active: bool,
    last: (u32, u32),
    primary_id: Option<u32>,
    secondary: Option<(u32, (u32, u32))>,
    compound: bool,
}

const TOUCH_CANCEL: u32 = 3;
const TOUCH_POINTER_DOWN: u32 = 5;
const TOUCH_POINTER_UP: u32 = 6;
const TOUCH_SECOND_INDEX: u32 = 1 << 8;

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

    pub(super) fn accept_report(&mut self, report: &crate::airplay::TouchInput, size: (u32, u32)) -> Vec<Vec<u8>> {
        let action = report.action & 0xff;
        if action == TOUCH_CANCEL || size.0 < 2 || size.1 < 2 {
            return self.release();
        }

        if report.pointers.is_empty() {
            if self.compound {
                // A legacy report cannot move either pointer of a compound gesture.
                // A fresh DOWN ends the old gesture before starting a single touch.
                if action == TOUCH_DOWN {
                    let mut frames = self.release();
                    frames.extend(self.accept(action, report.x, report.y, size));
                    return frames;
                }
                return if action == TOUCH_UP { self.finish_compound(TOUCH_UP, self.last) } else { Vec::new() };
            }
            return self.accept(action, report.x, report.y, size).into_iter().collect();
        }

        let point = |p: &crate::airplay::TouchPoint| (p.x.min(size.0 - 1), p.y.min(size.1 - 1));
        if action == TOUCH_DOWN {
            let mut frames = self.release();
            let first = &report.pointers[0];
            self.primary_id = Some(first.id);
            frames.extend(self.accept(TOUCH_DOWN, first.x, first.y, size));
            return frames;
        }
        if !self.active { return Vec::new(); }

        let primary = report.pointers.iter().find(|p| Some(p.id) == self.primary_id).map(point);
        let changed = report.pointers.get((report.action >> 8) as usize);
        match action {
            TOUCH_POINTER_DOWN if self.secondary.is_none() => {
                let (Some(primary), Some(other)) = (primary, changed.filter(|p| Some(p.id) != self.primary_id)) else {
                    return Vec::new();
                };
                self.last = primary;
                self.secondary = Some((other.id, point(other)));
                self.compound = true;
                vec![Self::compound_frame(TOUCH_POINTER_DOWN | TOUCH_SECOND_INDEX, primary, self.secondary.map(|(_, p)| p))]
            }
            TOUCH_MOVE => {
                let Some(primary) = primary else { return Vec::new(); };
                if !self.compound {
                    return self.accept(TOUCH_MOVE, primary.0, primary.1, size).into_iter().collect();
                }
                let secondary = if let Some((id, _)) = self.secondary {
                    let Some(p) = report.pointers.iter().find(|p| p.id == id).map(point) else { return Vec::new(); };
                    self.secondary = Some((id, p));
                    Some(p)
                } else { None };
                self.last = primary;
                vec![Self::compound_frame(TOUCH_MOVE, primary, secondary)]
            }
            TOUCH_POINTER_UP => {
                let (Some((second_id, old_second)), Some(released), Some(primary)) = (self.secondary, changed, primary) else {
                    return Vec::new();
                };
                let second = report.pointers.iter().find(|p| p.id == second_id).map(point).unwrap_or(old_second);
                if released.id == second_id {
                    self.last = primary;
                    self.secondary = None;
                    vec![Self::compound_frame(TOUCH_POINTER_UP | TOUCH_SECOND_INDEX, primary, Some(second))]
                } else if Some(released.id) == self.primary_id {
                    // CarLife receivers follow a single remaining MOVE with pointer ID 0.
                    // Close the old ID 1 before continuing that finger as the new primary.
                    self.primary_id = Some(second_id);
                    self.secondary = None;
                    self.last = second;
                    vec![Self::compound_frame(TOUCH_POINTER_UP, primary, Some(second)),
                        Self::compound_frame(TOUCH_UP, second, None), Self::compound_frame(TOUCH_DOWN, second, None)]
                } else { Vec::new() }
            }
            TOUCH_UP if self.compound => self.finish_compound(TOUCH_UP, primary.unwrap_or(self.last)),
            TOUCH_UP => {
                let up = self.accept(TOUCH_UP, 0, 0, size);
                *self = Self::default();
                up.into_iter().collect()
            }
            _ => Vec::new(),
        }
    }

    pub(super) fn release(&mut self) -> Vec<Vec<u8>> {
        if !self.active { return Vec::new(); }
        // A forced release must cancel even a one-finger tap so a held control cannot fire.
        self.finish_compound(TOUCH_CANCEL, self.last)
    }

    fn finish_compound(&mut self, action: u32, primary: (u32, u32)) -> Vec<Vec<u8>> {
        let mut frames = Vec::new();
        if let Some((_, second)) = self.secondary {
            frames.push(Self::compound_frame(TOUCH_POINTER_UP | TOUCH_SECOND_INDEX, primary, Some(second)));
        }
        frames.push(Self::compound_frame(action, primary, None));
        *self = Self::default();
        frames
    }

    fn compound_frame(action: u32, primary: (u32, u32), secondary: Option<(u32, u32)>) -> Vec<u8> {
        let mut body = Vec::new();
        proto::varint(&mut body, 1, u64::from(action));
        proto::varint(&mut body, 2, u64::from(primary.0));
        proto::varint(&mut body, 3, u64::from(primary.1));
        if let Some((x, y)) = secondary {
            proto::varint(&mut body, 4, u64::from(x));
            proto::varint(&mut body, 5, u64::from(y));
        }
        build_cmd_frame(0x68001, &body)
    }
}

pub(super) struct Session {
    dialect: CarLifeDialect,
    #[cfg(test)]
    probe_reset: Option<Arc<Notify>>,
    #[cfg(test)]
    probe_reset_written: bool,
    challenge: String,
    info: Option<(String, String, String)>,
    proof_ok: bool,
    pub(super) protocol_progress: Arc<Progress>,
    admitted: bool,
    features_requested: bool,
    features_sent: bool,
    feature_ack: bool,
    feature_deadline: Option<tokio::time::Instant>,
    rsa: Option<Arc<rsa::RsaPrivateKey>>,
    seed: Option<Vec<u8>>,
    ready: bool,
    init_sent: bool,
    init_fps: Option<u32>,
    init_canvas: Option<(u32,u32)>,
    reconfigure_request: Arc<Notify>,
    encoder_epoch: u64,
    encoder_actual: Option<(u32,u32)>,
    encoder_fps: Option<u32>,
    #[cfg(test)]
    init_used_fallback: bool,
    declared_display: Option<(u32,u32)>,
    pub started: bool,
    pub(super) video_start_written: Arc<Notify>,
    pub(super) video_sync_received: Option<watch::Sender<bool>>,
    pub(super) video_rate_failure: watch::Sender<Option<u32>>,
    matched: bool,
    navigation_requested: bool,
    navigation_started: bool,
    vehicle_gps_subscribed: bool,
    voice_epoch: u64,
    voice_request: Arc<Notify>,
    voice_lease: Option<tokio::time::Instant>,
    voice_pending: std::collections::VecDeque<(u64, std::time::Instant)>,
}

impl Default for Session {
    fn default() -> Self {
        Self { dialect: CarLifeDialect::default(), challenge: format!("2.0;{}", uuid::Uuid::new_v4().simple()), info: None,
            #[cfg(test)]
            probe_reset: None,
            #[cfg(test)]
            probe_reset_written: false,
            #[cfg(test)]
            init_used_fallback: false,
            proof_ok: false, protocol_progress: Arc::new(Progress::default()), admitted: false, features_requested: false, features_sent: false, feature_ack: false, feature_deadline: None,
            rsa: None, seed: None, ready: false, init_sent: false, init_fps: None, init_canvas: None, reconfigure_request: Arc::new(Notify::new()), encoder_epoch: 0, encoder_actual: None, encoder_fps: None, declared_display: None, started: false, video_start_written:Arc::new(Notify::new()), video_sync_received:None, video_rate_failure: watch::Sender::new(None), matched: false, navigation_requested: false, navigation_started: false, vehicle_gps_subscribed: false, voice_epoch: 0, voice_request: Arc::new(Notify::new()), voice_lease: None, voice_pending: std::collections::VecDeque::new() }
    }
}

#[cfg(test)]
pub(super) fn microphone_test_session()->Shared { let mut s=Session::default();s.started=true;s.proof_ok=true;s.admitted=true;s.ready=true;s.seed=Some(vec![7;16]);Arc::new(Mutex::new(s)) }

type Action = (u32, Vec<u8>);

enum CryptoWork {
    GenerateKey,
    DecryptSeed { key: Arc<rsa::RsaPrivateKey>, encoded: String },
}

enum CommandCrypto {
    PublicKey { key: Arc<rsa::RsaPrivateKey>, encoded: String },
    Seed(Vec<u8>),
}

impl CryptoWork {
    fn execute(self) -> Result<CommandCrypto, String> {
        match self {
            Self::GenerateKey => {
                #[cfg(test)]
                let started = {
                    log::info!(target: "carwith_probe", "RSA-2048 keygen begin");
                    std::time::Instant::now()
                };
                let key = rsa::RsaPrivateKey::new(&mut rand::thread_rng(), 2048).map_err(|e| e.to_string())?;
                #[cfg(test)]
                log::info!(target: "carwith_probe", "RSA-2048 keygen done duration_us={}", started.elapsed().as_micros());
                let der = key.to_public_key().to_public_key_der().map_err(|e| e.to_string())?;
                let encoded = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, der.as_bytes());
                Ok(CommandCrypto::PublicKey { key: Arc::new(key), encoded })
            }
            Self::DecryptSeed { key, encoded } => {
                let encrypted = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, encoded)
                    .map_err(|e| e.to_string())?;
                let seed = key.decrypt(rsa::Pkcs1v15Encrypt, &encrypted).map_err(|e| e.to_string())?;
                if !matches!(seed.len(), 16 | 24 | 32) { return Err("invalid AES seed length".into()); }
                Ok(CommandCrypto::Seed(seed))
            }
        }
    }
}

fn is_encryption_handshake(dialect: CarLifeDialect, kind: u32) -> bool {
    match kind {
        CMD_MD_AUTHEN_RESPONSE => dialect != CarLifeDialect::SystemPlain32,
        CMD_MD_RSA_KEY_REQUEST | CMD_MD_AES_SEED | CMD_MD_ENCRYPT_READY => true,
        _ => false,
    }
}

fn observe_buffered_authentication(progress: &Progress, dialect: CarLifeDialect, mut buffered: &[u8]) {
    // The AES seed cannot be installed before authentication is observed, so
    // only the pre-key stream needs this lookahead. Leave validation and all
    // protocol side effects in their original wire order.
    if progress.authentication.load(Ordering::Relaxed) { return; }
    while let Some((kind, _, consumed)) = cmd_frame_parts(buffered) {
        if is_encryption_handshake(dialect, kind) {
            progress.authentication.store(true, Ordering::Relaxed);
            return;
        }
        buffered = &buffered[consumed..];
    }
}

fn navigation_subscription()->Vec<u8> {
    let mut module=Vec::new();proto::varint(&mut module,1,0);
    let mut list=Vec::new();proto::varint(&mut list,1,1);proto::bytes(&mut list,2,&module);list
}
impl Session {
    pub(super) fn new(dialect: CarLifeDialect) -> Self {
        static NEXT_VOICE_EPOCH: std::sync::atomic::AtomicU64=std::sync::atomic::AtomicU64::new(1);
        let mut session=Self::default();session.dialect=dialect;
        session.voice_epoch=NEXT_VOICE_EPOCH.fetch_add(1,std::sync::atomic::Ordering::Relaxed);
        session
    }
    pub(super) fn navigation_ready(&self) -> bool {
        match self.dialect {
            CarLifeDialect::CarWithEncrypted41 => self.proof_ok && self.admitted && self.ready,
            CarLifeDialect::SystemPlain30 | CarLifeDialect::SystemPlain32 => self.started && self.matched && self.info.is_some() && self.init_sent,
            CarLifeDialect::SystemAuto => false,
        }
    }
    #[cfg(test)]
    pub(super) fn protocol_evidence(&self) -> super::protocol_selection::Evidence {
        self.protocol_progress.evidence()
    }
    pub(super) fn is_system_plain(&self) -> bool { self.dialect.is_system_plain() }
    pub(super) fn media_ready(&self) -> bool {
        // Audio lanes retain their format and playback during video reconfiguration.
        self.started || (self.is_system_plain() && self.encoder_epoch!=0)
    }
    pub(super) fn request_reconfigure(&self) {
        if self.is_system_plain() && self.started { self.reconfigure_request.notify_one(); }
    }
    fn reconfigure(&mut self, display:ReceiverDisplay, cfg:&CarLifeConfig) -> Result<Vec<Action>,String> {
        if !self.is_system_plain() || !self.started { return Ok(Vec::new()); }
        // A new encoder epoch keeps the owning CMD socket and the HU identity.
        // Feature ACK/timeout and INIT are independently deduplicated for this epoch.
        self.declared_display = (display.size.0>=2 && display.size.1>=2).then_some(display.size);
        self.encoder_epoch=self.encoder_epoch.wrapping_add(1).max(1);
        self.started=false;self.features_sent=false;self.feature_ack=false;self.feature_deadline=None;
        self.init_sent=false;self.init_fps=None;self.init_canvas=None;self.encoder_fps=None;
        self.receive_prepared(u32::MAX, &[], cfg, None)
    }
    fn feature_timeout(&self) -> Option<tokio::time::Instant> {
        self.feature_deadline.filter(|_| !self.init_sent)
    }
    fn vehicle_gps_ready(&self) -> bool {
        if self.is_system_plain() { self.media_ready() }
        else { self.vehicle_gps_subscribed && self.started && self.navigation_ready() }
    }
    fn voice_ready(&self) -> bool {
        self.is_system_plain() && self.media_ready() && self.matched && self.info.is_some()
    }
    fn queue_voice(&mut self, key: u64) -> bool {
        if !matches!(key, 0x21 | 0x22) || !self.voice_ready() || self.voice_pending.len() >= 16 { return false; }
        self.voice_pending.push_back((key, std::time::Instant::now() + Duration::from_secs(2)));
        self.voice_request.notify_one();
        true
    }
    fn take_voice(&mut self) -> Option<u64> {
        while let Some((key, deadline)) = self.voice_pending.pop_front() {
            if std::time::Instant::now() < deadline && self.voice_ready() {
                if !self.voice_pending.is_empty() { self.voice_request.notify_one(); }
                return Some(key);
            }
        }
        None
    }
    pub fn encrypt(&self, bytes: &[u8]) -> Result<Vec<u8>, String> { crypt(bytes, self.seed.as_deref(), true) }
    pub fn decrypt(&self, bytes: &[u8]) -> Result<Vec<u8>, String> { crypt(bytes, self.seed.as_deref(), false) }

    fn observe_command(&self, kind: u32, body: &[u8]) {
        if is_encryption_handshake(self.dialect, kind) {
            self.protocol_progress.authentication.store(true, Ordering::Relaxed);
        }
        if kind == CMD_VERSION_MATCH_STATUS {
            match first_int(body, 1) {
                Some(1) => self.protocol_progress.version_matched.store(true, Ordering::Relaxed),
                Some(0 | 2) => self.protocol_progress.version_rejected.store(true, Ordering::Relaxed),
                _ => {}
            }
        }
    }

    // Take only the inputs the background job needs. A timed-out job can
    // neither hold the session lock nor publish a result to a retired round.
    fn crypto_work(&self, kind: u32, body: &[u8]) -> Result<Option<CryptoWork>, String> {
        if self.is_system_plain() { return Ok(None); }
        match kind {
            CMD_MD_RSA_KEY_REQUEST => {
                if !self.features_sent || self.rsa.is_some() { return Err("RSA request out of order".into()); }
                Ok(Some(CryptoWork::GenerateKey))
            }
            CMD_MD_AES_SEED => {
                if self.seed.is_some() { return Err("duplicate seed".into()); }
                let key = self.rsa.clone().ok_or("seed before RSA")?;
                let encoded = pb_first_string(body, 1).ok_or("missing seed")?;
                Ok(Some(CryptoWork::DecryptSeed { key, encoded }))
            }
            _ => Ok(None),
        }
    }

    #[cfg(test)]
    fn receive(&mut self, kind: u32, body: &[u8], cfg: &CarLifeConfig) -> Result<Vec<Action>, String> {
        self.observe_command(kind, body);
        let crypto = self.crypto_work(kind, body)?.map(CryptoWork::execute).transpose()?;
        self.receive_prepared(kind, body, cfg, crypto)
    }

    fn receive_prepared(&mut self, kind: u32, body: &[u8], cfg: &CarLifeConfig, crypto: Option<CommandCrypto>) -> Result<Vec<Action>, String> {
        let mut out = Vec::new();
        let plain = self.is_system_plain();
        let plain_32 = self.dialect == CarLifeDialect::SystemPlain32;
        // 3.2 uses the box's version check without negotiating a content key.
        if plain && is_encryption_handshake(self.dialect, kind) {
            return Err(format!("encrypted handshake message in {:?}", self.dialect));
        }
        match kind {
            CMD_MD_AUTHEN_RESULT if plain => {
                if !self.matched || (!plain_32 && self.info.is_none()) || first_int(body,1)!=Some(1) {
                    return Err("plaintext admission rejected/out of order".into());
                }
                self.admitted=true;
                out.push((CMD_HU_MD_AUTH_RESULT_ACK,vec![]));
            }
            CMD_VERSION_MATCH_STATUS if plain => {
                if first_int(body, 1) != Some(1) { return Err("protocol version rejected".into()); }
                if !self.matched {
                    out.push((CMD_HU_STATISTIC_INFO, proto_statistic_payload()));
                    if plain_32 {
                        let mut request = Vec::new();
                        proto::string(&mut request, 1, "CarLife_1.0.0");
                        out.push((CMD_HU_AUTHEN_REQUEST, request));
                    }
                }
                self.matched = true;
            }
            CMD_MD_INFO if plain => {
                if self.info.is_none() {
                    self.info = Some(Default::default());
                    let mut identity=cfg.clone();
                    if let Some((w,h))=self.declared_display { identity.width=w;identity.height=h; }
                    out.push((CMD_HU_INFO, proto_hu_info_payload(&identity)));
                }
            }
            CMD_MD_FEATURE_CONFIG_REQUEST if plain => { self.features_requested |= plain_32; }
            0x10026 if plain => {
                if let Some(control)=system_module_control(body) { out.push((0x18028,control)); }
            }
            0x10022 if plain && self.features_sent => { self.feature_ack = true; }
            // The phone can ask before admission. The fix itself is gated on the encrypted session.
            0x10031 if cfg.vehicle_gps.is_some() => {
                let mut gps_module = Vec::new();
                proto::varint(&mut gps_module, 1, 0);
                proto::varint(&mut gps_module, 2, 1);
                let mut modules = Vec::new();
                proto::varint(&mut modules, 1, 1);
                proto::bytes(&mut modules, 2, &gps_module);
                out.push((0x18032, modules));
                emit_log("CARWITH-GPS: capability reply 0x18032 module=0 flag=1");
            }
            0x10033 | 0x10034 if cfg.vehicle_gps.is_some() && first_int(body, 1) == Some(0) => {
                self.vehicle_gps_subscribed = kind == 0x10033 && first_int(body, 2) == Some(1);
                emit_log(&format!("CARWITH-GPS: subscription 0x{kind:x} active={}", self.vehicle_gps_subscribed));
            }
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
                out.push((CMD_HU_INFO, proto_hu_info_payload(cfg)));
                out.push((CMD_HU_AUTHEN_REQUEST, request));
            }
            // The box treats this legacy version response as informational.
            // The local CarLife service separately grants admission to the stream.
            CMD_MD_AUTHEN_RESPONSE if plain_32 => {}
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
                let Some(CommandCrypto::PublicKey { key, encoded }) = crypto else {
                    return Err("RSA key result missing".into());
                };
                let mut payload = Vec::new(); proto::string(&mut payload, 1, &encoded);
                self.rsa = Some(key);
                out.push((CMD_HU_RSA_PUBLIC_KEY, payload));
            }
            CMD_MD_AES_SEED => {
                let Some(CommandCrypto::Seed(seed)) = crypto else {
                    return Err("AES seed result missing".into());
                };
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
                self.encoder_actual=Some((w,h));
                self.encoder_fps = first_int(body, 3).and_then(|rate| u32::try_from(rate).ok()).filter(|&rate| rate > 0);
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
        let feature_ready = if plain {
            self.matched && self.info.is_some() && (!plain_32 || self.features_requested)
        } else {
            self.proof_ok && self.features_requested
        };
        if !self.features_sent && feature_ready {
            self.features_sent = true;
            let mut features = if plain_32 {
                vec![("FOCUS_UI", 0), ("VOICE_MIC", 1), ("AUDIO_TRANSMISSION_MODE", 0)]
            } else {
                vec![("CONTENT_ENCRYPTION", u64::from(!plain)), ("VOICE_MIC", u64::from(cfg.microphone.is_none()))]
            };
            if plain && !plain_32 {
                features.push(("CONNECT_TYPE", 5));
                features.push(("I_FRAME_INTERVAL", 20));
                self.feature_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(2));
            }
            if !plain_32 && (plain || self.declared_display.is_some()) {
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
            if plain && !plain_32 {
                proto::varint(&mut payload,3,0);
                proto::string(&mut payload,4,&cfg.bt_name);
                proto::string(&mut payload,5,&cfg.bt_address);
            }
            out.push((CMD_HU_FEATURE_CONFIG_RESPONSE, payload));
        }
        let init_ready = if plain_32 {
            self.features_sent && self.admitted
        } else if plain {
            self.features_sent && (self.feature_ack || self.feature_deadline.is_some_and(|at| tokio::time::Instant::now() >= at))
        } else {
            self.proof_ok && self.admitted && self.ready
        };
        if !self.init_sent && init_ready {
            self.init_sent = true;
            self.init_fps = Some(cfg.fps);
            let (width,height) = self.declared_display.unwrap_or((cfg.width,cfg.height));
            let (w,h) = canvas_for_receiver(width, height);
            self.init_canvas = Some((w,h));
            #[cfg(test)]
            { self.init_used_fallback = self.declared_display.is_none(); }
            out.push((CMD_VIDEO_ENCODER_INIT, proto_encoder_info_payload(w,h,cfg.fps)));
        }
        Ok(out)
    }
}

impl Drop for Session { fn drop(&mut self) { if let Some(seed) = &mut self.seed { seed.fill(0); } } }

// Only the public dialect responds to module reports. Bound both allocations and
// parsing work; a truncated/unknown protobuf structure cannot terminate CMD.
#[derive(Clone, Copy)]
enum ModuleField<'a> { Int(u64), Bytes(&'a [u8]) }
fn module_fields(bytes:&[u8])->Option<Vec<(u32,ModuleField<'_>)>> {
    if bytes.len()>4096 { return None; }
    let mut at=0;let mut fields=Vec::new();let mut scanned=0;
    while at<bytes.len() {
        scanned+=1;if scanned>96 { return None; }
        let (tag,next)=pb_read_varint(bytes,at)?;at=next;
        let field=u32::try_from(tag>>3).ok()?;if field==0 {return None;}
        match tag&7 {
            0=>{let (value,next)=pb_read_varint(bytes,at)?;at=next;fields.push((field,ModuleField::Int(value)));}
            2=>{
                let (len,next)=pb_read_varint(bytes,at)?;
                let end=next.checked_add(usize::try_from(len).ok()?)?;
                let value=bytes.get(next..end)?;at=end;fields.push((field,ModuleField::Bytes(value)));
            }
            1=>{at=at.checked_add(8)?;if at>bytes.len(){return None;}}
            5=>{at=at.checked_add(4)?;if at>bytes.len(){return None;}}
            _=>return None,
        }
    }
    Some(fields)
}
fn system_module_control(body:&[u8])->Option<Vec<u8>> {
    let fields=module_fields(body)?;let mut count=None;let mut modules=0;let mut enabled=Vec::new();
    for (field,value) in fields {
        match (field,value) {
            (1,ModuleField::Int(value))=>{if count.replace(value).is_some(){return None;}}
            (2,ModuleField::Bytes(bytes))=>{
                modules+=1;if modules>32{return None;}
                let mut id=None;let mut status=None;
                for (field,value) in module_fields(bytes)? {
                    match (field,value) {
                        (1,ModuleField::Int(value))=>{if id.replace(value).is_some(){return None;}}
                        (2,ModuleField::Int(value))=>{if status.replace(value).is_some(){return None;}}
                        (1|2,_)=>return None,
                        _=>{}
                    }
                }
                let (id,status)=(id?,status?);
                if (1..=9).contains(&id) && !(matches!(id,4|6) && status==0) && !enabled.contains(&id) {enabled.push(id);}
            }
            (1|2,_)=>return None,
            _=>{}
        }
    }
    if count.is_some_and(|count|count!=modules) || enabled.is_empty(){return None;}
    let mut out=Vec::new();proto::varint(&mut out,1,enabled.len() as u64);
    for id in enabled {
        let mut entry=Vec::new();proto::varint(&mut entry,1,id);proto::varint(&mut entry,2,1);proto::bytes(&mut out,2,&entry);
    }
    Some(out)
}

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

/// Only the CMD writer owns the phone-microphone lease. Media actors may disappear
/// while this socket remains alive, so their timers cannot provide the final STOP.
async fn write_system_voice(wr:&mut tokio::net::tcp::OwnedWriteHalf, state:&Shared, key:u64) -> Result<(),String> {
    let mut body=Vec::new();proto::varint(&mut body,1,key);
    let written=tokio::time::timeout(Duration::from_millis(250),send_cmd(wr,0x68008,&body)).await;
    if !matches!(written,Ok(true)) { return Err("System CarLife voice command write failed".into()); }
    state.lock().voice_lease=if key==0x21 {Some(tokio::time::Instant::now()+Duration::from_secs(60))} else {None};
    crate::emit_hid_log(&format!("VOICE-KEY: System CMD 0x68008 key=0x{key:x} written (recognition unconfirmed)"));
    Ok(())
}

/// A stream of foreground/keepalive messages must not keep an automatic probe
/// half-open forever. Authentication allows the longer budget, never plaintext.
pub(super) async fn handshake_deadline(progress: &Progress) {
    let start = tokio::time::Instant::now();
    tokio::time::sleep_until(start + Duration::from_secs(8)).await;
    let stage = progress.evidence();
    if stage.started {return std::future::pending().await;}
    if stage.authentication {
        tokio::time::sleep_until(start + Duration::from_secs(30)).await;
        if progress.evidence().started {return std::future::pending().await;}
    }
}

async fn write_guarded_init(
    wr: &mut tokio::net::tcp::OwnedWriteHalf,
    id: u32,
    requested: &DisplayRequest,
    cfg: &CarLifeConfig,
    state: &Shared,
) -> Result<bool, String> {
    let Some(hint) = cfg.cached_system_canvas.as_ref().filter(|_| id == CMD_VIDEO_ENCODER_INIT) else {
        return Ok(false);
    };
    loop {
        wr.writable().await.map_err(|e| e.to_string())?;
        let display = *requested.borrow();
        let ((width, height), source) = if display.size.0 >= 2 && display.size.1 >= 2 {
            (display.size, "receiver")
        } else if (hint.is_current)() {
            (hint.size, "receiver_cache")
        } else {
            ((cfg.width, cfg.height), "manual")
        };
        let (w, h) = canvas_for_receiver(width, height);
        let fps = display.effective_fps(cfg.fps);
        let body = proto_encoder_info_payload(w, h, fps);
        let wire = state.lock().encrypt(&body)?;
        let frame = build_cmd_frame(id, &wire);
        // Choose again after WouldBlock. Once TCP has accepted a prefix, finish that frame.
        let written = match wr.try_write(&frame) {
            Ok(0) => return Err("CarLife INIT write returned zero".into()),
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
            Err(e) => return Err(format!("CarLife INIT write failed: {e}")),
        };
        wr.write_all(&frame[written..]).await.map_err(|e| e.to_string())?;
        let _ = wr.flush().await;
        {
            let mut session = state.lock();
            session.init_canvas = Some((w, h));
            session.init_fps = Some(fps);
        }
        emit_log(&format!("CARWITH-HS: TX 0x{id:x} payload={}B wire={}B", body.len(), wire.len()));
        emit_log(&format!("CARWITH-SIZE: receiver={width}x{height} request={w}x{h} cap=2560x2560 requested_fps={} effective_fps={fps} canvas_source={source}", cfg.fps));
        return Ok(true);
    }
}

pub(super) async fn run(stream: TcpStream, events: mpsc::UnboundedSender<CarLifeEvent>,
    size: Arc<Mutex<(u32,u32)>>, requested: DisplayRequest, cfg: CarLifeConfig, state: Shared, reset_request: Arc<Notify>, can_reopen_display: bool) -> Result<(),String> {
    let (mut rd, mut wr) = stream.into_split();
    let version = match cfg.dialect {
        CarLifeDialect::SystemPlain30 => vec![8, 3, 16, 0],
        CarLifeDialect::SystemPlain32 => vec![8, 3, 16, 2],
        _ => proto_version_payload(),
    };
    if !send_cmd(&mut wr, CMD_HU_PROTOCOL_VERSION, &version).await { return Err("version write failed".into()); }
    #[cfg(test)]
    log::info!(target: "carwith_probe", "CMD TX written id=0x{CMD_HU_PROTOCOL_VERSION:x}");
    let mut buf=Vec::new(); let mut chunk=[0;4096];
    let mut display_updates = requested.subscribe();
    let mut next_reset = tokio::time::Instant::now();
    #[cfg(test)]
    { state.lock().probe_reset = Some(reset_request.clone()); }
    let mut navigation_diagnostics = super::navigation::Diagnostics::default();
    let mut rx_log = super::log_sampling::CommandRx::default();
    let reconfigure_request=state.lock().reconfigure_request.clone();
    let voice_request = state.lock().voice_request.clone();
    let protocol_progress = state.lock().protocol_progress.clone();
    let mut next_gps = tokio::time::Instant::now();
    let mut gps_sent = 0_u64;
    let system_plain = cfg.dialect.is_system_plain();
    let (mut rate_failures, source_sync) = {
        let session = state.lock();
        (session.video_rate_failure.subscribe(), session.video_sync_received.as_ref().map(watch::Sender::subscribe))
    };
    let mut failed_rate = None;
    let mut late_sync_deadline = None;
    loop {
        let started=state.lock().started;
        let display = *display_updates.borrow_and_update();
        let reported_failure = failed_rate.take();
        let mut waiting_for_source_sync = false;
        if started && display.confirmed && (can_reopen_display || system_plain) {
            let (w, h) = display.size;
            let target = canvas_for_receiver(w, h);
            let fps = display.effective_fps(cfg.fps);
            let (init_canvas, init_fps, actual, encoder_fps) = {
                let session = state.lock();
                (session.init_canvas, session.init_fps, session.encoder_actual, session.encoder_fps)
            };
            let target_changed = w >= 2 && h >= 2 && init_canvas.is_some_and(|initial| initial != target);
            if system_plain {
                if target_changed || init_fps.is_some_and(|initial| initial != fps) {
                    reconfigure_request.notify_one();
                }
            } else {
                // The aspect-ratio threshold belongs to AirPlay stretching. Reopen the
                // source when its acknowledged pixel canvas still differs from the new INIT.
                let resize = target_changed && actual.is_some_and(|source| source != target);
                let raise_fps = init_fps.is_some_and(|initial| initial < fps) && encoder_fps.is_none_or(|rate| rate < fps);
                let late_lower_fps = init_fps.is_some_and(|initial| initial > fps);
                waiting_for_source_sync = late_lower_fps && source_sync.as_ref().is_some_and(|sync| !*sync.borrow());
                if waiting_for_source_sync && late_sync_deadline.is_none() {
                    reset_request.notify_one();
                    late_sync_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(8));
                }
                let no_sync = waiting_for_source_sync && late_sync_deadline.is_some_and(|deadline| tokio::time::Instant::now() >= deadline);
                let pacing_failed = late_lower_fps && reported_failure == Some(fps);
                // AirPlay can pace a healthy source to a lower ceiling. It cannot
                // recover pictures that a stale prewarm cap prevented the phone from encoding.
                if resize || raise_fps || no_sync || pacing_failed {
                    *size.lock() = (0, 0);
                    emit_log(&format!("CARWITH-SIZE: late receiver={w}x{h} restart local source once for {}x{}@{fps} resize={resize} raise_fps={raise_fps} no_sync={no_sync} pacing_failed={pacing_failed}",target.0,target.1));
                    return Ok(());
                }
            }
        }
        if !waiting_for_source_sync { late_sync_deadline = None; }
        let gps = cfg.vehicle_gps.as_ref().filter(|_| state.lock().vehicle_gps_ready());
        let feature_deadline = state.lock().feature_timeout();
        let voice_deadline = state.lock().voice_lease;
        let read = tokio::select! {
            _ = reconfigure_request.notified(), if system_plain => {
                let display=*requested.borrow();
                let mut epoch_cfg=cfg.clone();epoch_cfg.fps=display.effective_fps(cfg.fps);
                let actions=state.lock().reconfigure(display,&epoch_cfg)?;
                for (id,body) in actions {
                    if write_guarded_init(&mut wr, id, &requested, &cfg, &state).await? { continue; }
                    if !send_cmd(&mut wr,id,&body).await { return Err("System CarLife reconfiguration write failed".into()); }
                }
                continue;
            }
            _ = voice_request.notified(), if system_plain => {
                let requested = state.lock().take_voice();
                if let Some(key) = requested {
                    let active=state.lock().voice_lease.is_some();
                    // A replacement media actor cannot know that this CMD still has
                    // an active recognition lease. Restart it in the same writer.
                    if key==0x21 && active {write_system_voice(&mut wr,&state,0x22).await?;}
                    // The source fallback may beat an old actor's STOP to this writer.
                    if key==0x21 || active {write_system_voice(&mut wr,&state,key).await?;}
                }
                continue;
            }
            _ = async {tokio::time::sleep_until(voice_deadline.unwrap()).await}, if voice_deadline.is_some() && system_plain => {
                write_system_voice(&mut wr,&state,0x22).await?;
                continue;
            }
            _ = async { tokio::time::sleep_until(feature_deadline.unwrap()).await }, if feature_deadline.is_some() => {
                let display = *requested.borrow();
                let mut timer_cfg = cfg.clone();
                timer_cfg.fps = display.effective_fps(cfg.fps);
                let actions = {
                    let mut session = state.lock();
                    session.declared_display = (display.size.0 >= 2 && display.size.1 >= 2).then_some(display.size);
                    session.receive_prepared(u32::MAX, &[], &timer_cfg, None)?
                };
                for (id, body) in actions {
                    if write_guarded_init(&mut wr, id, &requested, &cfg, &state).await? { continue; }
                    let wire = state.lock().encrypt(&body)?;
                    if !send_cmd(&mut wr, id, &wire).await { return Err("System CarLife INIT timer write failed".into()); }
                }
                continue;
            }
            _ = tokio::time::sleep_until(next_gps), if gps.is_some() => {
                if let Some(payload) = gps.and_then(|pending| pending.take()) {
                    let encrypted = state.lock().encrypt(&payload)?;
                    let written = tokio::time::timeout(Duration::from_millis(250),
                        send_cmd(&mut wr, super::vehicle_gps::GPS_MESSAGE, &encrypted)).await;
                    if !matches!(written, Ok(true)) {
                        emit_log("CARWITH-GPS: 0x18010 command write failed");
                        return Err("CarWith GPS command write failed".into());
                    }
                    gps_sent += 1;
                    if gps_sent <= 3 || gps_sent % 30 == 0 {
                        emit_log(&format!("CARWITH-GPS: TX 0x18010 count={gps_sent} payload={}B wire={}B",
                            payload.len(), encrypted.len()));
                    }
                }
                next_gps = tokio::time::Instant::now() + super::vehicle_gps::MIN_INTERVAL;
                continue;
            }
            _ = display_updates.changed() => continue,
            _ = rate_failures.changed(), if can_reopen_display && !system_plain => {
                failed_rate = *rate_failures.borrow_and_update();
                continue;
            }
            _ = async { tokio::time::sleep_until(late_sync_deadline.unwrap()).await }, if late_sync_deadline.is_some() => continue,
            _ = async {
                // Leave the single Notify permit pending through the cooldown.
                // The command socket can still read and handle other work meanwhile.
                if next_reset > tokio::time::Instant::now() { tokio::time::sleep_until(next_reset).await; }
                reset_request.notified().await;
            }, if started => {
                // mcarplay CarLifeSession.requestKeyFrame: CMD 0x1800B, not CTRL or START.
                if !send_cmd(&mut wr, 0x1800b, &[]).await { return Err("CarWith RESET write failed".into()); }
                #[cfg(test)]
                { state.lock().probe_reset_written = true; }
                next_reset = tokio::time::Instant::now() + Duration::from_secs(1);
                emit_log("CARWITH-HS: TX 0x1800b VIDEO_ENCODER_RESET (keyframe request)");
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
        observe_buffered_authentication(&protocol_progress, cfg.dialect, &buf);
        while let Some((kind, payload, consumed)) = read_cmd_frame(&buf) {
            // Production RX diagnostics below run after processing, which can include key generation.
            #[cfg(test)]
            log::info!(target: "carwith_probe", "CMD RX complete id=0x{kind:x} wire_bytes={consumed}");
            buf.drain(..consumed);
            let (payload, crypto_work) = {
                let session = state.lock();
                let decoded = session.decrypt(&payload)?;
                session.observe_command(kind, &decoded);
                let work = session.crypto_work(kind, &decoded);
                (decoded, work)
            };
            let display=*requested.borrow();
            let (w, h) = display.size;
            let declared = (w >= 2 && h >= 2).then_some((w, h));
            let mut cfg_copy=cfg.clone();
            cfg_copy.fps = display.effective_fps(cfg.fps);
            let actions = async {
                let crypto = if let Some(work) = crypto_work? {
                    let mut worker = tokio::task::JoinSet::new();
                    worker.spawn_blocking(move || work.execute());
                    Some(worker.join_next().await.expect("one crypto job").map_err(|error| error.to_string())??)
                } else {
                    None
                };
                let mut session = state.lock();
                session.declared_display = declared;
                session.receive_prepared(kind, &payload, &cfg_copy, crypto)
            }.await.map_err(|error| {
                emit_log(&format!("CARLIFE-PROTOCOL: RX rejected id=0x{kind:x} bytes={} mode={:?}: {error}",payload.len(),cfg.dialect));
                error
            })?;
            let status={let s=state.lock(); (s.proof_ok,s.admitted,s.ready)};
            if matches!(kind, super::navigation::NEXT_TURN | super::navigation::ASSISTANT) {
                let trace=navigation_diagnostics.observe("CMD",kind,consumed-8,Some(payload.len()));
                dispatch_navigation(kind,&payload,&state,&events,trace);
            } else if rx_log.due(kind, payload.len(), status == (true,true,true), std::time::Instant::now()) {
                emit_log(&format!("CARWITH-HS: RX 0x{kind:x} {}B proof={} admitted={} encrypted={}",payload.len(),status.0,status.1,status.2));
            }
            if kind==CMD_VIDEO_ENCODER_INIT_DONE {
                let (w,h)=parse_encoder_info(&payload); *size.lock()=(w,h);
                let ack = state.lock().encoder_fps;
                emit_log(&format!("CARWITH-SIZE: actual={w}x{h} encoder_ack_fps={ack:?}; touch target uses actual source pixels"));
                if let Some(rate) = ack { let _ = events.send(CarLifeEvent::EncoderFrameRate(rate)); }
                let _=events.send(CarLifeEvent::EncoderResolution { width:w,height:h });
            }
            if kind == CMD_GO_TO_DESKTOP && payload.is_empty() && state.lock().navigation_ready() && state.lock().started {
                let _ = events.send(CarLifeEvent::GoToDesktop);
            }
            for (id,body) in actions {
                if write_guarded_init(&mut wr, id, &requested, &cfg, &state).await? { continue; }
                let encrypted=state.lock().encrypt(&body)?;
                if !send_cmd(&mut wr,id,&encrypted).await { return Err(format!("CarWith write 0x{id:x} failed")); }
                emit_log(&format!("CARWITH-HS: TX 0x{id:x} payload={}B wire={}B",body.len(),encrypted.len()));
                if id == CMD_HU_FEATURE_CONFIG_RESPONSE {
                    emit_log(&format!("CARWITH-SIZE: prefer_vehicle_resolution={}", declared.is_some()));
                }
                if id==CMD_VIDEO_ENCODER_INIT {
                    let (width,height) = declared.unwrap_or((cfg.width,cfg.height));
                    emit_log(&format!("CARWITH-SIZE: receiver={width}x{height} request={}x{} cap=2560x2560 requested_fps={} effective_fps={}",first_int(&body,1).unwrap_or(0),first_int(&body,2).unwrap_or(0),cfg.fps,first_int(&body,3).unwrap_or(0)));
                }
                if id==CMD_VIDEO_ENCODER_START {
                    protocol_progress.started.store(true, Ordering::Relaxed);
                    state.lock().video_start_written.notify_one();
                    emit_log("CARWITH-NAV: authenticated CMD/DATA monitoring 0x10030; dashboard card alone does not confirm HUD output");
                    let _=events.send(CarLifeEvent::Handshake);
                    let state = match cfg.dialect {
                        CarLifeDialect::SystemPlain30 => "System CarLife 3.0 plaintext video START sent",
                        CarLifeDialect::SystemPlain32 => "System CarLife 3.2 plaintext video START sent",
                        _ => "CarWith proof/admission/encryption complete; video START sent",
                    };
                    emit_state("carlife_active", state);
                }
            }
        }
    }
}

async fn write_touch_frames(
    stream: &mut TcpStream, state: &Shared, frames: Vec<Vec<u8>>, grant: Option<&crate::airplay::TouchGrant>,
) -> std::io::Result<bool> {
    let (wire,epoch,system) = {
        let session = state.lock();
        if !session.started { return Ok(false); }
        let mut wire = Vec::new();
        for frame in frames {
            let (kind, body, _) = read_cmd_frame(&frame).expect("locally encoded touch frame");
            let encrypted = session.encrypt(&body).map_err(std::io::Error::other)?;
            wire.extend(build_cmd_frame(kind, &encrypted));
        }
        (wire,session.encoder_epoch,session.is_system_plain())
    };
    if wire.is_empty() { return Ok(true); }
    if grant.is_none() && !system {stream.write_all(&wire).await?;return Ok(true);}
    let mut written = 0;
    while written < wire.len() {
        stream.writable().await?;
        let result=if system {
            // Revalidate immediately around each nonblocking write. A reconfiguration
            // during socket backpressure cannot deliver old coordinates to a new canvas.
            let session=state.lock();
            if session.encoder_epoch!=epoch || !session.started {None}
            else if let Some(grant)=grant {grant.with_current(||stream.try_write(&wire[written..]))}
            else {Some(stream.try_write(&wire[written..]))}
        } else {grant.unwrap().with_current(||stream.try_write(&wire[written..]))};
        match result {
            None if written == 0 => return Ok(false),
            None => return Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "touch ownership changed during CTRL write")),
            Some(Ok(0)) => return Err(std::io::ErrorKind::WriteZero.into()),
            Some(Ok(count)) => written += count,
            Some(Err(error)) if error.kind() == std::io::ErrorKind::WouldBlock => continue,
            Some(Err(error)) => return Err(error),
        }
    }
    Ok(true)
}

pub(super) async fn control(mut stream: TcpStream, queue: Arc<Mutex<Vec<QueuedControl>>>, state: Shared, stop: Arc<Notify>) {
    let mut sent=0u64;
    let mut last_selector: Option<(u64, std::time::Instant)> = None;
    let mut gesture = TouchGesture::default();
    let mut gesture_grant: Option<crate::airplay::TouchGrant> = None;
    let mut encoder_epoch=state.lock().encoder_epoch;
    loop {
        let epoch=state.lock().encoder_epoch;
        if epoch!=encoder_epoch {
            // System reconfiguration keeps CTRL alive. Retire old coordinates and
            // gesture state before accepting any input for the next encoder epoch.
            queue.lock().clear();
            let cancel=gesture.release().concat();
            if !cancel.is_empty() && !matches!(tokio::time::timeout(Duration::from_millis(250),stream.write_all(&cancel)).await,Ok(Ok(()))) {return;}
            gesture_grant=None;encoder_epoch=epoch;
        }
        if gesture_grant.as_ref().is_some_and(|grant| grant.with_current(|| ()).is_none()) {
            if write_touch_frames(&mut stream, &state, gesture.release(), None).await.is_err() { return; }
            gesture_grant = None;
        }
        let frame={let mut q=queue.lock(); if q.is_empty(){None}else{Some(q.remove(0))}};
        if let Some(packet)=frame {
            if let Some(input) = packet.touch {
                if packet.expires.is_some_and(|at| std::time::Instant::now() >= at) { continue; }
                match input {
                    QueuedTouch::Release => {
                        if write_touch_frames(&mut stream, &state, gesture.release(), None).await.is_err() { return; }
                        gesture_grant = None;
                    }
                    QueuedTouch::Report(report, size) => {
                        {
                            let session=state.lock();
                            if session.is_system_plain() && (!session.started || session.encoder_actual!=Some(size)) {continue;}
                        }
                        let same_grant = match (gesture_grant.as_ref(), report.grant.as_ref()) {
                            (Some(old), Some(new)) => old.same_as(new),
                            (None, None) => true,
                            _ => false,
                        };
                        if report.action & 0xff == TOUCH_CANCEL {
                            if same_grant {
                                if write_touch_frames(&mut stream, &state, gesture.release(), None).await.is_err() { return; }
                                gesture_grant = None;
                            }
                            continue;
                        }
                        if report.grant.as_ref().is_some_and(|grant| grant.with_current(|| ()).is_none()) { continue; }
                        if gesture.active && !same_grant {
                            if write_touch_frames(&mut stream, &state, gesture.release(), None).await.is_err() { return; }
                            gesture_grant = None;
                        }
                        let mut next = gesture.clone();
                        let frames = next.accept_report(&report, size);
                        if frames.is_empty() { continue; }
                        match write_touch_frames(&mut stream, &state, frames, report.grant.as_ref()).await {
                            Ok(true) => {
                                gesture = next;
                                gesture_grant = if gesture.active { report.grant } else { None };
                                sent += 1;
                                if sent <= 8 || sent % 60 == 0 { crate::emit_hid_log(&format!("CARWITH-TOUCH: encrypted CTRL report #{sent}")); }
                            }
                            Ok(false) => {}
                            Err(_) => return,
                        }
                    }
                }
                continue;
            }
            let Some((kind, body, _)) = read_cmd_frame(&packet.bytes) else {continue};
            let hard_key = if kind == CTRL_CAR_HARD_KEY_CODE { first_int(&body, 1) } else { None };
            let phone_key = hard_key.filter(|code| matches!(code, 2 | 3));
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
            if packet.expires.is_some_and(|at|std::time::Instant::now()>=at) {
                if let Some(code) = phone_key { crate::emit_hid_log(&format!("PHONE-KEY: key={code} dropped expired")); }
                continue;
            }
            let wire={
                let s=state.lock();
                if phone_key.is_some() && (s.dialect != CarLifeDialect::CarWithEncrypted41 || !s.started || !s.navigation_ready()) {
                    crate::emit_hid_log("PHONE-KEY: dropped before authenticated CarWith source ready");
                    continue;
                }
                if !s.started { if packet.expires.is_some(){emit_log("CARWITH-CTRL: source not ready; command dropped");} continue; }
                let Ok(body)=s.encrypt(&body) else {return};
                build_cmd_frame(kind,&body)
            };
            if stream.write_all(&wire).await.is_err(){
                if let Some(code) = phone_key { crate::emit_hid_log(&format!("PHONE-KEY: key={code} CTRL write failed")); }
                return;
            }
            sent+=1;
            if let Some(code) = phone_key { crate::emit_hid_log(&format!("PHONE-KEY: encrypted CTRL written key={code} (execution unconfirmed)")); }
            if let Some(code) = hard_key {
                last_selector = selector.map(|key| (key, std::time::Instant::now()));
                if code == 0x21 || sent <= 8 || sent % 60 == 0 {
                    crate::emit_hid_log(&format!("CARWITH-KEY: encrypted CTRL write key={code}"));
                }
            }
            if sent<=8 || sent%60==0 {crate::emit_hid_log(&format!("CARWITH-TOUCH: encrypted CTRL packet #{sent} wire={}B",wire.len()));}
        } else {
            let plain=state.lock().is_system_plain();
            tokio::select! {
                _=stop.notified()=>return,
                result=stream.readable(), if plain => {
                    if result.is_err() { return; }
                    let mut discard=[0u8;1024];
                    match stream.try_read(&mut discard) {
                        Ok(0)=>return,
                        Err(error) if error.kind()!=std::io::ErrorKind::WouldBlock=>return,
                        _=>{}
                    }
                }
                _=tokio::time::sleep(Duration::from_millis(8))=>{}
            }
        }
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
                touch: None,
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
        let queue=Arc::new(Mutex::new(Vec::new()));
        let hub = CarLifeHub {
            system_voice: None,
            stop: Arc::new(DialStop::default()),
            input_queue: queue.clone(),
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
            let packet = queue.lock().remove(0);
            assert!(packet.expires.is_some());
            assert_eq!(packet.bytes, [0, 2, 0, 0, 0, 6, 128, 8, 8, wire_code]);
        }
        crate::engine::route_display_key(Some(&hub), 0xb5, |k| local_keys.push(k));
        assert_eq!(local_keys, [0xb5]);
        assert!(queue.lock().is_empty());
    }

    #[tokio::test]
    async fn voice_start_uses_ctrl_protobuf_and_has_an_expiry() {
        let queue=Arc::new(Mutex::new(Vec::new()));
        let hub=CarLifeHub{system_voice:None,stop:Arc::new(DialStop::default()),input_queue:queue.clone(),
            encoder_size:Arc::new(Mutex::new((1920,720))),keyframe_request:Arc::new(Notify::new())};
        {
            assert!(hub.system_voice_epoch().is_none());
            assert!(!hub.system_voice_key(0x22));
            let expected=33;
            hub.voice_start();
            let packet=queue.lock().remove(0);
            assert!(packet.expires.unwrap()>std::time::Instant::now());
            let (kind,body,_)=read_cmd_frame(&packet.bytes).unwrap();
            assert_eq!(kind,0x68008);assert_eq!(first_int(&body,1),Some(expected));
        }
    }

    #[test]
    fn phone_keys_only_reach_carwith_and_never_fall_back_to_media() {
        let queue=Arc::new(Mutex::new(Vec::new()));
        let mut hub=CarLifeHub{system_voice:None,stop:Arc::new(DialStop::default()),input_queue:queue.clone(),
            encoder_size:Arc::new(Mutex::new((1920,720))),keyframe_request:Arc::new(Notify::new())};
        let mut local=Vec::new();
        for (key,code) in [(0xb0020,2),(0xb0026,3)] {
            crate::engine::route_display_key(None,key,|key|local.push(key));
            assert!(local.is_empty(),"self source must not dispatch telephone input to media");
            crate::engine::route_display_key(Some(&hub),key,|key|local.push(key));
            assert!(local.is_empty());
            let packet=queue.lock().remove(0);
            assert!(packet.expires.unwrap() > std::time::Instant::now());
            assert!(packet.expires.unwrap() <= std::time::Instant::now()+Duration::from_secs(1));
            assert_eq!(packet.bytes,[0,2,0,0,0,6,128,8,8,code]);
        }
        hub.system_voice=Some(Arc::new(SystemVoiceTarget::default()));
        crate::engine::route_display_key(Some(&hub),0xb0020,|key|local.push(key));
        assert!(queue.lock().is_empty(),"system CarLife must not receive CarWith phone operations");
        assert!(local.is_empty());
        hub.system_voice=None;
        hub.stop.cancel();
        crate::engine::route_display_key(Some(&hub),0xb0026,|key|local.push(key));
        assert!(queue.lock().is_empty());assert!(local.is_empty());
    }

    #[tokio::test]
    async fn phone_ctrl_encrypts_native_commands_and_drops_expired_or_unauthenticated_input() {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();
        let state=microphone_test_session();
        let queue=Arc::new(Mutex::new(Vec::new()));
        let stop=Arc::new(Notify::new());
        let writer=tokio::spawn(control(client,queue.clone(),state.clone(),stop.clone()));
        for missing in 0..5 {
            {
                let mut session=state.lock();
                session.started=missing!=0;session.proof_ok=missing!=1;
                session.admitted=missing!=2;session.ready=missing!=3;
            }
            queue.lock().push(QueuedControl{
                bytes:build_cmd_frame(0x68008,&[8,2]),touch:None,
                expires:Some(if missing==4 {std::time::Instant::now()-Duration::from_secs(1)}
                    else {std::time::Instant::now()+Duration::from_secs(1)}),
            });
            tokio::time::timeout(Duration::from_secs(1),async {
                while !queue.lock().is_empty() {tokio::time::sleep(Duration::from_millis(1)).await;}
            }).await.unwrap();
            assert!(tokio::time::timeout(Duration::from_millis(30),peer.read_u8()).await.is_err(),
                "unready/expired telephone command was written: {missing}");
        }
        for code in [2,3] {
            queue.lock().push(QueuedControl{bytes:build_cmd_frame(0x68008,&[8,code]),touch:None,
                expires:Some(std::time::Instant::now()+Duration::from_secs(1))});
            let mut frame=[0;24];
            tokio::time::timeout(Duration::from_secs(1),peer.read_exact(&mut frame)).await.unwrap().unwrap();
            let (kind,encrypted,_)=read_cmd_frame(&frame).unwrap();
            assert_eq!(kind,0x68008);
            assert_eq!(state.lock().decrypt(&encrypted).unwrap(),[8,code]);
        }
        stop.notify_one();
        tokio::time::timeout(Duration::from_secs(1),writer).await.unwrap().unwrap();
    }
    use tokio::net::TcpListener;
    fn cfg()->CarLifeConfig {CarLifeConfig{dialect:CarLifeDialect::default(),manufacturer:String::new(),model:String::new(),width:1280,height:720,fps:60,cached_system_canvas:None,navigation_updates:false,microphone:None,vehicle_gps:None,bt_address:"00:11:22:33:44:55".into(),bt_name:"HyperCar".into()}}

    #[test]
    fn vehicle_gps_requires_this_command_sessions_gps_subscription_and_encryption() {
        let mut session = Session::default();
        let mut config = cfg();
        assert!(session.receive(0x10031, &[], &config).unwrap().is_empty());
        config.vehicle_gps = Some(Arc::new(super::super::vehicle_gps::PendingLocation::default()));
        assert_eq!(session.receive(0x10031, &[], &config).unwrap(),
            vec![(0x18032, vec![8, 1, 18, 4, 8, 0, 16, 1])]);
        session.receive(0x10033, &[8, 1, 16, 1], &config).unwrap();
        assert!(!session.vehicle_gps_subscribed);
        session.receive(0x10033, &[8, 0, 16, 1], &config).unwrap();
        assert!(!session.vehicle_gps_ready());
        session.proof_ok = true;
        session.admitted = true;
        session.ready = true;
        session.started = true;
        assert!(session.vehicle_gps_ready());
        session.receive(0x10034, &[8, 0], &config).unwrap();
        assert!(!session.vehicle_gps_ready());
        assert!(!Session::default().vehicle_gps_ready());
    }

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
            stop.clone(), None, |_| panic!("retired owner reopened")).unwrap();
        assert!(output.recv().await.is_none());
        assert!(tokio::time::timeout(Duration::from_millis(1), listener.accept()).await.is_err());
    }

    #[tokio::test]
    async fn prewarm_hint_cannot_supply_touch_bounds_before_encoder_ack() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ports = [listener.local_addr().unwrap().port(); 7];
        let stop = Arc::new(DialStop::default());
        let cached = DisplayRequest::new(ReceiverDisplay { size: (1920, 1080), ..Default::default() });
        let (hub, _events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            stop.clone(), Some(cached), |_| {}).unwrap();
        hub.inject_touch(TOUCH_DOWN, 40, 40);
        stop.cancel();
        let early_touch = hub.input_queue.lock().pop().unwrap().touch.unwrap();
        let QueuedTouch::Report(report, size) = early_touch else { panic!("missing touch report"); };
        assert!(TouchGesture::default().accept_report(&report, size).is_empty(),
            "prewarm dimensions cannot accept a touch before the phone acknowledges its canvas");
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
            old_stop.clone(), None, |_| {}).unwrap();
        let mut peers = Vec::new();
        for listener in &listeners { peers.push(listener.accept().await.unwrap().0); }
        assert!(matches!(old_events.recv().await, Some(CarLifeEvent::SourceReset { .. })));
        // An intermediate cancelled invitation must not let its successor bypass the old task.
        let skipped_stop = Arc::new(DialStop::default());
        let (_skipped, mut skipped_events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            skipped_stop.clone(), None, |_| {}).unwrap();
        skipped_stop.cancel();
        let (_new, _events) = CarLifeHub::connect_to_ports(cfg(), "127.0.0.1".into(), ports,
            Arc::new(DialStop::default()), None, |_| {}).unwrap();
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

    #[tokio::test]
    async fn late_rate_only_keeps_carwith_source_and_touch_canvas() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let canvas = (2400, 1080);
        let size = Arc::new(Mutex::new((0, 0)));
        let display = DisplayRequest::new(ReceiverDisplay { size: canvas, ..Default::default() });
        let reset = Arc::new(Notify::new());
        let (events, _rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), reset.clone(), true));
        assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        let (kind, body) = late_display_frame(&mut phone).await.unwrap();
        assert_eq!(kind, CMD_VIDEO_ENCODER_INIT);
        let fields = state.lock().decrypt(&body).unwrap();
        assert_eq!(first_int(&fields, 3), Some(60));
        let ack = state.lock().encrypt(&proto_encoder_info_payload(canvas.0, canvas.1, 60)).unwrap();
        phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &ack)).await.unwrap();
        assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_START);

        display.send_replace(ReceiverDisplay { size: canvas, max_fps: Some(30), confirmed: true });
        let message = tokio::time::timeout(Duration::from_millis(50), late_display_frame(&mut phone)).await;
        assert!(message.is_err(), "a rate-only update sent a new INIT or closed the live CMD socket: {message:?}");
        assert_eq!(*size.lock(), canvas);
        reset.notify_one();
        let reset_command = tokio::time::timeout(Duration::from_secs(1), late_display_frame(&mut phone)).await.unwrap().unwrap();
        assert_eq!(reset_command, (0x1800b, vec![]));
        drop(phone);
        assert!(task.await.unwrap().is_err());
    }

    async fn late_display_frame(phone: &mut TcpStream) -> std::io::Result<(u32, Vec<u8>)> {
        let mut header = [0; 8];
        phone.read_exact(&mut header).await?;
        let mut body = vec![0; u16::from_be_bytes([header[0], header[1]]) as usize];
        phone.read_exact(&mut body).await?;
        Ok((u32::from_be_bytes(header[4..8].try_into().unwrap()), body))
    }

    #[tokio::test(start_paused = true)]
    async fn late_lower_rate_recovers_when_airplay_cannot_pace_the_source() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        let canvas = (1920, 720);
        let sync = watch::Sender::new(true);
        let size = Arc::new(Mutex::new((0, 0)));
        {
            let mut session = state.lock();
            session.started = false;
            session.video_sync_received = Some(sync);
        }
        let display = DisplayRequest::new(ReceiverDisplay { size: canvas, ..Default::default() });
        let (events, _rx) = mpsc::unbounded_channel();
        let mut task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new()), true));
        assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_INIT);
        let ack = state.lock().encrypt(&proto_encoder_info_payload(canvas.0, canvas.1, 60)).unwrap();
        phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &ack)).await.unwrap();
        assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_START);

        display.send_replace(ReceiverDisplay { size: canvas, max_fps: Some(30), confirmed: true });
        state.lock().video_rate_failure.send_replace(Some(45));
        assert!(tokio::time::timeout(Duration::from_millis(100), &mut task).await.is_err());
        assert_eq!(*size.lock(), canvas);
        state.lock().video_rate_failure.send_replace(Some(30));
        tokio::time::timeout(Duration::from_secs(1), &mut task).await
            .expect("the source must reopen at the confirmed cap after the hardware path fails").unwrap().unwrap();
        assert_eq!(*size.lock(), (0, 0));
    }

    #[tokio::test(start_paused = true)]
    async fn late_lower_rate_waits_for_source_sync_after_a_keyframe_request() {
        for receives_sync in [false, true] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut phone, _) = listener.accept().await.unwrap();
            let state = microphone_test_session();
            let canvas = (1920, 720);
            let sync = watch::Sender::new(false);
            let display = DisplayRequest::new(ReceiverDisplay { size: canvas, ..Default::default() });
            {
                let mut session = state.lock();
                session.started = false;
                session.video_sync_received = Some(sync.clone());
            }
            let size = Arc::new(Mutex::new((0, 0)));
            let (events, _rx) = mpsc::unbounded_channel();
            let mut task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new()), true));
            assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_HU_PROTOCOL_VERSION);
            phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
            assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_INIT);
            let ack = state.lock().encrypt(&proto_encoder_info_payload(canvas.0, canvas.1, 60)).unwrap();
            phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &ack)).await.unwrap();
            assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_START);

            // Prevent Tokio from auto-advancing while the RESET is in flight on TCP.
            let (release_clock, hold_clock) = std::sync::mpsc::channel();
            let clock = tokio::task::spawn_blocking(move || hold_clock.recv_timeout(Duration::from_secs(2)));
            display.send_replace(ReceiverDisplay { size: canvas, max_fps: Some(30), confirmed: true });
            assert_eq!(late_display_frame(&mut phone).await.unwrap(), (0x1800b, vec![]));
            tokio::time::advance(Duration::from_secs(7)).await;
            assert!(!task.is_finished());
            if receives_sync { sync.send_replace(true); }
            release_clock.send(()).unwrap();
            clock.await.unwrap().unwrap();
            let result = tokio::time::timeout(Duration::from_secs(2), &mut task).await;
            if receives_sync {
                assert!(result.is_err(), "the configured source frame should keep the existing CMD stream");
                assert_eq!(*size.lock(), canvas);
                drop(phone);
                assert!(task.await.unwrap().is_err());
            } else {
                result.expect("the late cap and missing configured video need one new INIT").unwrap().unwrap();
                assert_eq!(*size.lock(), (0, 0));
            }
        }
    }

    #[tokio::test]
    async fn late_confirmation_recovers_stale_cached_carwith_init() {
        for (cached, actual, confirmed, reopen) in [
            (((1920, 720), 30), ((1920, 720), 30), ((1920, 720), 60), true),
            (((1280, 720), 60), ((1280, 720), 60), ((1920, 720), 60), true),
            (((1280, 720), 60), ((1920, 720), 60), ((1920, 720), 60), false),
            (((1920, 720), 30), ((1920, 720), 60), ((1920, 720), 60), false),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut phone, _) = listener.accept().await.unwrap();
            let state = microphone_test_session();
            state.lock().started = false;
            let size = Arc::new(Mutex::new((0, 0)));
            let display = DisplayRequest::new(ReceiverDisplay { size: cached.0, max_fps: Some(cached.1), confirmed: false });
            let (events, _rx) = mpsc::unbounded_channel();
            let mut task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new()), true));
            assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_HU_PROTOCOL_VERSION);
            phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
            let (kind, body) = late_display_frame(&mut phone).await.unwrap();
            assert_eq!(kind, CMD_VIDEO_ENCODER_INIT);
            let fields = state.lock().decrypt(&body).unwrap();
            assert_eq!(first_int(&fields, 3), Some(u64::from(cached.1)));
            let ack = state.lock().encrypt(&proto_encoder_info_payload(actual.0.0, actual.0.1, actual.1)).unwrap();
            phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &ack)).await.unwrap();
            assert_eq!(late_display_frame(&mut phone).await.unwrap().0, CMD_VIDEO_ENCODER_START);

            display.send_replace(ReceiverDisplay { size: confirmed.0, max_fps: Some(confirmed.1), confirmed: true });
            let result = tokio::time::timeout(Duration::from_millis(100), &mut task).await;
            if reopen {
                result.expect("late confirmation must repair a stale source INIT").unwrap().unwrap();
                assert_eq!(*size.lock(), (0, 0));
            } else {
                assert!(result.is_err(), "the encoder ACK already satisfies the live display");
                assert_eq!(*size.lock(), actual.0);
                drop(phone);
                assert!(task.await.unwrap().is_err());
            }
        }
    }

    #[tokio::test]
    async fn late_uncached_same_aspect_display_requests_once_and_uses_the_returned_canvas() {
        async fn frame(phone: &mut TcpStream) -> (u32, Vec<u8>) {
            late_display_frame(phone).await.unwrap()
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let size = Arc::new(Mutex::new((0, 0)));
        // The live dimensions differ from the uncached 16:9 fallback and its ACK.
        let display = DisplayRequest::new(ReceiverDisplay::default());
        let (events, _rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new()), true));
        assert_eq!(frame(&mut phone).await.0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_INIT);
        let reply = |w,h| build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,
            &state.lock().encrypt(&proto_encoder_info_payload(w,h,60)).unwrap());
        phone.write_all(&reply(1024,576)).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_START);
        display.send_modify(|d| d.size = (2880,1620));
        assert!(tokio::time::timeout(Duration::from_millis(30),frame(&mut phone)).await.is_err());
        assert_eq!(*size.lock(), (1024, 576));
        display.send_modify(|d| d.confirmed = true);
        tokio::time::timeout(Duration::from_secs(1),task).await.expect("late display did not restart source").unwrap().unwrap();
        assert_eq!(*size.lock(), (0, 0));
        drop(phone);
        let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let state = microphone_test_session();
        state.lock().started = false;
        let (events, mut rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(run(stream, events, size.clone(), display.clone(), cfg(), state.clone(), Arc::new(Notify::new()), false));
        assert_eq!(frame(&mut phone).await.0, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(0x10026, &[])).await.unwrap();
        let (kind, bytes) = frame(&mut phone).await;
        assert_eq!(kind, CMD_VIDEO_ENCODER_INIT);
        let fields = state.lock().decrypt(&bytes).unwrap();
        assert_eq!((first_int(&fields,1), first_int(&fields,2), first_int(&fields,3)), (Some(2560),Some(1440),Some(60)));
        assert_eq!(*size.lock(),(0,0), "the fallback touch canvas survived the renegotiation gap");
        let actual = state.lock().encrypt(&proto_encoder_info_payload(2400,1350,60)).unwrap();
        phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &actual)).await.unwrap();
        assert_eq!(frame(&mut phone).await.0, CMD_VIDEO_ENCODER_START);
        assert_eq!(*size.lock(),(2400,1350));
        let events: Vec<_> = std::iter::from_fn(||rx.try_recv().ok()).collect();
        assert!(events.iter().any(|e|matches!(e,CarLifeEvent::EncoderResolution{width:2400,height:1350})));
        assert!(events.iter().any(|e|matches!(e,CarLifeEvent::EncoderFrameRate(60))));
        display.send_replace(ReceiverDisplay { size:(1920,1080), max_fps:Some(12), confirmed:true });
        // The same attempt has already consumed its local reopen. A later AirPlay engine
        // can pace the source in hardware without closing these live seven sockets.
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

    #[test]
    fn carwith_touch_sends_both_pointer_positions_and_rebases_the_remaining_finger() {
        fn event(action: u32, points: &[(u32, u32, u32)]) -> crate::airplay::TouchInput {
            let pointers = points.iter().map(|&(id, x, y)| crate::airplay::TouchPoint { id, x, y }).collect();
            crate::airplay::TouchInput { action, x: 0, y: 0, pointers, grant: None }
        }
        fn composite(frame: &[u8]) -> (u64, u64, u64, Option<u64>, Option<u64>) {
            let (kind, body, _) = read_cmd_frame(frame).unwrap();
            assert_eq!(kind, 0x68001);
            (first_int(&body, 1).unwrap(), first_int(&body, 2).unwrap(), first_int(&body, 3).unwrap(),
                first_int(&body, 4), first_int(&body, 5))
        }

        let mut gesture = TouchGesture::default();
        let size = (300, 200);
        let down = gesture.accept_report(&event(0, &[(5, 40, 60)]), size);
        assert_eq!(read_cmd_frame(&down[0]).unwrap().0, 0x68002);
        let second = gesture.accept_report(&event(261, &[(5, 40, 60), (9, 80, 120)]), size);
        assert_eq!(composite(&second[0]), (261, 40, 60, Some(80), Some(120)));
        let moved = gesture.accept_report(&event(2, &[(5, 35, 55), (9, 310, 125)]), size);
        assert_eq!(composite(&moved[0]), (2, 35, 55, Some(299), Some(125)));
        let primary_up = gesture.accept_report(&event(6, &[(5, 35, 55), (9, 95, 125)]), size);
        assert_eq!(primary_up.iter().map(|frame| composite(frame)).collect::<Vec<_>>(), vec![
            (6, 35, 55, Some(95), Some(125)), (1, 95, 125, None, None), (0, 95, 125, None, None),
        ]);
        let moved = gesture.accept_report(&event(2, &[(9, 100, 125)]), size);
        assert_eq!(composite(&moved[0]), (2, 100, 125, None, None));
        let cancelled = gesture.release();
        assert_eq!(composite(&cancelled[0]), (3, 100, 125, None, None));
        assert!(gesture.release().is_empty());
    }

    #[test]
    fn carwith_touch_single_finger_cancel_cannot_send_a_legacy_up() {
        use crate::airplay::{TouchInput, TouchPoint};
        let point = TouchPoint { id: 8, x: 40, y: 60 };
        let report = |action| TouchInput { action, x: point.x, y: point.y, pointers: vec![point], grant: None };
        let mut gesture = TouchGesture::default();
        let size = (300, 200);

        gesture.accept_report(&report(TOUCH_DOWN), size);
        let ended = gesture.accept_report(&report(TOUCH_CANCEL), size);
        assert_eq!(ended.len(), 1);
        let (kind, body, _) = read_cmd_frame(&ended[0]).unwrap();
        assert_eq!(kind, 0x68001);
        assert_eq!(first_int(&body, 1), Some(u64::from(TOUCH_CANCEL)));
        assert_eq!(first_int(&body, 2), Some(40));
        assert_eq!(first_int(&body, 3), Some(60));
        assert!(gesture.accept_report(&report(TOUCH_UP), size).is_empty());

        gesture.accept_report(&report(TOUCH_DOWN), size);
        let up = gesture.accept_report(&report(TOUCH_UP), size);
        assert_eq!(read_cmd_frame(&up[0]).unwrap().0, 0x68003);
    }

    #[test]
    fn carwith_touch_release_lifts_the_secondary_before_cancelling_the_primary() {
        use crate::airplay::{TouchInput, TouchPoint};
        let primary = TouchPoint { id: 2, x: 15, y: 20 };
        let second = TouchPoint { id: 7, x: 200, y: 110 };
        let mut gesture = TouchGesture::default();
        let size = (300, 200);
        gesture.accept_report(&TouchInput { action: 0, x: 15, y: 20, pointers: vec![primary], grant: None }, size);
        gesture.accept_report(&TouchInput { action: 261, x: 200, y: 110, pointers: vec![primary, second], grant: None }, size);
        let ended = gesture.release();
        assert_eq!(ended.len(), 2);
        let (kind, pointer_up, _) = read_cmd_frame(&ended[0]).unwrap();
        assert_eq!(kind, 0x68001);
        assert_eq!(first_int(&pointer_up, 1), Some(262));
        assert_eq!(first_int(&pointer_up, 4), Some(200));
        let (kind, cancel, _) = read_cmd_frame(&ended[1]).unwrap();
        assert_eq!(kind, 0x68001);
        assert_eq!(first_int(&cancel, 1), Some(3));
        assert!(gesture.release().is_empty());
    }

    #[test]
    fn carwith_touch_legacy_down_closes_a_previous_compound_gesture() {
        use crate::airplay::{TouchInput, TouchPoint};
        let first = TouchPoint { id: 1, x: 30, y: 50 };
        let second = TouchPoint { id: 4, x: 150, y: 80 };
        let size = (300, 200);
        let mut gesture = TouchGesture::default();
        gesture.accept_report(&TouchInput {
            action: TOUCH_DOWN, x: first.x, y: first.y, pointers: vec![first], grant: None,
        }, size);
        gesture.accept_report(&TouchInput {
            action: TOUCH_POINTER_DOWN | TOUCH_SECOND_INDEX, x: second.x, y: second.y,
            pointers: vec![first, second], grant: None,
        }, size);
        let next = gesture.accept_report(&TouchInput {
            action: TOUCH_DOWN, x: 190, y: 45, pointers: vec![], grant: None,
        }, size);
        let frames: Vec<_> = next.iter().map(|f| read_cmd_frame(f).unwrap()).collect();
        assert_eq!(frames.iter().map(|f| f.0).collect::<Vec<_>>(), [0x68001, 0x68001, 0x68002]);
        assert_eq!(first_int(&frames[0].1, 1), Some(u64::from(TOUCH_POINTER_UP | TOUCH_SECOND_INDEX)));
        assert_eq!(first_int(&frames[1].1, 1), Some(u64::from(TOUCH_CANCEL)));
        assert_eq!(first_int(&frames[2].1, 1), Some(190));
        assert_eq!(first_int(&frames[2].1, 2), Some(45));
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
        let task = tokio::spawn(run(stream,events,size.clone(),DisplayRequest::new(ReceiverDisplay::default()),cfg(),state.clone(),Arc::new(Notify::new()), true));
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
        let task = tokio::spawn(run(stream, events, size.clone(), DisplayRequest::new(ReceiverDisplay::default()), cfg(), state.clone(), reset.clone(), true));
        assert_eq!(next(&mut phone).await, CMD_HU_PROTOCOL_VERSION);
        phone.write_all(&build_cmd_frame(CMD_VERSION_MATCH_STATUS, &proto_authen_result_payload(true))).await.unwrap();
        assert_eq!(next(&mut phone).await, CMD_HU_STATISTIC_INFO);
        reset.notify_one();
        assert!(tokio::time::timeout(Duration::from_millis(30), next(&mut phone)).await.is_err());
        state.lock().init_sent = true;
        phone.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE, &proto_encoder_info_payload(1280, 720, 30))).await.unwrap();
        assert_eq!(next(&mut phone).await, CMD_VIDEO_ENCODER_START);
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), next(&mut phone)).await.unwrap(), 0x1800b);
        reset.notify_one();
        assert!(tokio::time::timeout(Duration::from_millis(30), next(&mut phone)).await.is_err());
        // The recovery request can arrive after the first RESET produced an IDR
        // but before the command cooldown ends. Multiple requests use one slot.
        reset.notify_one();
        reset.notify_one();
        phone.write_all(&build_cmd_frame(CMD_FOREGROUND, &[])).await.unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_millis(500), next(&mut phone)).await.unwrap(), CMD_HU_GO_TO_FOREGROUND);
        assert_eq!(tokio::time::timeout(Duration::from_secs(2), next(&mut phone)).await.unwrap(), 0x1800b);
        assert!(tokio::time::timeout(Duration::from_millis(1100), next(&mut phone)).await.is_err());
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
        let (_hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),None, |_| {}).unwrap();
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
        let display = DisplayRequest::new(ReceiverDisplay::default());
        let stop = Arc::new(DialStop::default());
        let (_hub, mut events) = CarLifeHub::connect(cfg(), "127.0.0.1".into(), stop.clone(), Some(display.clone()), |startup| {
            if startup { return; }
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
                            if value.size == receiver { return false; }
                            log::info!(target: "carwith_probe", "round={rounds} first video event; declare 2880x{height}");
                            value.size = receiver;
                            value.confirmed = true;
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
        let (_hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),None, |_| {}).unwrap();
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
    fn config(enabled:bool)->CarLifeConfig {CarLifeConfig{dialect:CarLifeDialect::default(),manufacturer:String::new(),model:String::new(),width:1280,height:720,fps:30,cached_system_canvas:None,navigation_updates:enabled,microphone:None,vehicle_gps:None,bt_address:String::new(),bt_name:String::new()}}
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

#[cfg(test)]
mod system_plain_tests {
    use super::*;
    fn cfg() -> CarLifeConfig {
        CarLifeConfig { dialect: CarLifeDialect::SystemPlain30, manufacturer: "BMW".into(), model: "ID8.5".into(),
            width:1280,height:720,fps:30,cached_system_canvas:None,navigation_updates:true,microphone:None,vehicle_gps:None,
            bt_address:"00:11:22:33:44:55".into(),bt_name:"Actual receiver".into() }
    }
    #[tokio::test]
    async fn system_canvas_hint_is_rechecked_on_the_init_socket() {
        use std::sync::atomic::AtomicBool;
        use tokio::net::TcpListener;

        let valid = Arc::new(AtomicBool::new(true));
        let guard = valid.clone();
        let mut config = cfg();
        let cached = (2736, 800);
        config.cached_system_canvas = Some(SystemCanvasHint {
            size: cached,
            is_current: Arc::new(move || guard.load(Ordering::Acquire)),
        });
        let requested = DisplayRequest::new(ReceiverDisplay::default());
        let state = Arc::new(Mutex::new(Session::new(config.dialect)));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (_, mut writer) = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap().into_split();
        let (mut peer, _) = listener.accept().await.unwrap();

        for (still_current, live, expected) in [
            (true, (0, 0), cached),
            (false, (0, 0), (1280, 720)),
            (false, (1920, 1080), (1920, 1080)),
        ] {
            valid.store(still_current, Ordering::Release);
            requested.send_replace(ReceiverDisplay { size: live, ..Default::default() });
            assert!(write_guarded_init(&mut writer, CMD_VIDEO_ENCODER_INIT, &requested, &config, &state).await.unwrap());
            let canvas = canvas_for_receiver(expected.0, expected.1);
            let expected_frame = build_cmd_frame(CMD_VIDEO_ENCODER_INIT, &proto_encoder_info_payload(canvas.0, canvas.1, 30));
            let mut actual = vec![0; expected_frame.len()];
            peer.read_exact(&mut actual).await.unwrap();
            assert_eq!(actual, expected_frame);
            assert_eq!(state.lock().init_canvas, Some(canvas));
        }
    }
    #[tokio::test]
    async fn system_plain_early_audio_format_and_reconfiguration_pcm_survive_video_epochs() {
        use tokio::net::TcpListener;
        for (kind,init,data,stop,tts) in [(MediaKind::Media,MEDIA_INIT,MEDIA_DATA,MEDIA_STOP,false),(MediaKind::Tts,TTS_INIT,TTS_DATA,TTS_STOP,true)] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let stream=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut peer,_)=listener.accept().await.unwrap();
            let mut session=Session::new(cfg().dialect);
            session.receive(CMD_MD_INFO,&[],&cfg()).unwrap();session.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg()).unwrap();
            let state=Arc::new(Mutex::new(session));let (tx,mut rx)=mpsc::unbounded_channel();
            let task=tokio::spawn(handle_media_client(stream,tx,kind,"audio-test",Some(state.clone()),Arc::new(DiagnosticPhase::default())));
            peer.write_all(&build_media_frame(init,0,&[8,128,247,2,16,1])).await.unwrap(); // 48000/mono
            assert!(matches!(tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap(),
                Some(CarLifeEvent::AudioInit{tts:t,rate:48000,channels:1}) if t==tts));
            { let mut s=state.lock();s.receive(0x10022,&[],&cfg()).unwrap();s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg()).unwrap();
                s.reconfigure(ReceiverDisplay::default(),&cfg()).unwrap(); }
            peer.write_all(&build_media_frame(data,0,&[1,0,2,0])).await.unwrap();
            assert!(matches!(tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap(),
                Some(CarLifeEvent::AudioData{tts:t,rate:48000,channels:1,data,..}) if t==tts && data==[1,0,2,0]));
            peer.write_all(&build_media_frame(stop,0,&[])).await.unwrap();
            assert!(matches!(tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap(),
                Some(CarLifeEvent::AudioStop{tts:t}) if t==tts));
            task.abort();let _=task.await;
        }
    }
    #[tokio::test]
    async fn system_plain_encoder_epoch_cancels_touch_and_new_down_stays_down() {
        use tokio::net::TcpListener;
        let cfg=cfg();let mut session=Session::new(cfg.dialect);
        session.receive(CMD_MD_INFO,&[],&cfg).unwrap();session.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        session.receive(0x10022,&[],&cfg).unwrap();session.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        let state=Arc::new(Mutex::new(session));
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let (mut peer,_)=listener.accept().await.unwrap();
        let queue=Arc::new(Mutex::new(Vec::new()));let cancel=Arc::new(Notify::new());
        let writer=tokio::spawn(control(stream,queue.clone(),state.clone(),cancel.clone()));
        let touch=|action,size,x| QueuedControl{bytes:Vec::new(),expires:None,touch:Some(QueuedTouch::Report(
            crate::airplay::TouchInput{action,x,y:20,pointers:Vec::new(),grant:None},size))};
        queue.lock().push(touch(TOUCH_DOWN,(1280,720),10));assert_eq!(next_frame(&mut peer).await.0,0x68002);
        state.lock().reconfigure(ReceiverDisplay{size:(1600,600),confirmed:true,max_fps:None},&cfg).unwrap();
        queue.lock().push(touch(TOUCH_UP,(1280,720),10));
        let (id,body)=next_frame(&mut peer).await;assert_eq!(id,0x68001);assert_eq!(first_int(&body,1),Some(3));
        {let mut s=state.lock();s.receive(0x10022,&[],&cfg).unwrap();s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1600,600,30),&cfg).unwrap();}
        queue.lock().push(touch(TOUCH_DOWN,(1280,720),10)); // stale old-canvas report
        queue.lock().push(touch(TOUCH_DOWN,(1600,600),100));
        let (id,body)=next_frame(&mut peer).await;assert_eq!(id,0x68002);assert_eq!(first_int(&body,1),Some(100));
        cancel.notify_one();writer.await.unwrap();
    }
    #[test]
    fn system_plain_module_control_preserves_idle_voice_and_ignores_malformed_lists() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        let list=vec![8,4,18,4,8,1,16,0,18,4,8,4,16,0,18,4,8,6,16,0,18,4,8,2,16,1];
        assert_eq!(s.receive(0x10026,&list,&cfg).unwrap(),
            vec![(0x18028,vec![8,2,18,4,8,1,16,1,18,4,8,2,16,1])]);
        for invalid in [&[18,255][..],&[18,4,8,1,16][..],&[8,1,18,2,8,1][..],&[18,4,8,99,16,0][..]] {
            assert!(s.receive(0x10026,invalid,&cfg).unwrap().is_empty());
        }
        let mut legacy=Session::default();
        assert!(legacy.receive(0x10026,&list,&cfg).unwrap().is_empty());
    }
    #[test]
    fn system_plain_features_use_frame_interval_and_current_hu_identity() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        let actions=s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        let feature=&actions.iter().find(|(id,_)|*id==CMD_HU_FEATURE_CONFIG_RESPONSE).unwrap().1;
        let mut interval=Vec::new();proto::string(&mut interval,1,"I_FRAME_INTERVAL");proto::varint(&mut interval,2,20);
        assert!(feature.windows(interval.len()).any(|part|part==interval));
        assert_eq!(first_int(feature,3),Some(0));
        assert_eq!(pb_first_string(feature,4).as_deref(),Some("Actual receiver"));
        assert_eq!(pb_first_string(feature,5).as_deref(),Some("00:11:22:33:44:55"));
    }
    #[test]
    fn system_plain_version_first_sends_clear_wifi_features_and_hu_info_once() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        assert!(!s.navigation_ready());
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        let actions=s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        let features=&actions.iter().find(|(id,_)|*id==CMD_HU_FEATURE_CONFIG_RESPONSE).unwrap().1;
        for entry in [vec![10,18,b'C',b'O',b'N',b'T',b'E',b'N',b'T',b'_',b'E',b'N',b'C',b'R',b'Y',b'P',b'T',b'I',b'O',b'N',16,0],
            vec![10,12,b'C',b'O',b'N',b'N',b'E',b'C',b'T',b'_',b'T',b'Y',b'P',b'E',16,5]] {
            assert!(features.windows(entry.len()).any(|part| part==entry));
        }
        assert!(s.receive(CMD_MD_INFO,&[],&cfg).unwrap().is_empty());
        assert!(s.receive(CMD_MD_FEATURE_CONFIG_REQUEST,&[],&cfg).unwrap().is_empty());
        assert!(s.receive(CMD_MD_AES_SEED,&[],&cfg).is_err());
        assert!(s.receive(CMD_MD_ENCRYPT_READY,&[],&cfg).is_err());
        assert!(!s.navigation_ready());
    }
    #[tokio::test(start_paused=true)]
    async fn system_plain_feature_deadline_does_not_need_incoming_traffic() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        tokio::time::advance(Duration::from_millis(1999)).await;
        assert!(s.receive(u32::MAX,&[],&cfg).unwrap().is_empty());
        tokio::time::advance(Duration::from_millis(1)).await;
        assert_eq!(s.receive(u32::MAX,&[],&cfg).unwrap()[0].0,CMD_VIDEO_ENCODER_INIT);
        assert!(s.feature_timeout().is_none());
        assert!(s.receive(0x10022,&[],&cfg).unwrap().is_empty());
    }
    #[test]
    fn system_plain_gps_without_subscription_survives_video_reconfiguration() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        assert!(!s.vehicle_gps_ready());
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        s.receive(0x10022,&[],&cfg).unwrap();
        assert!(!s.vehicle_gps_ready());
        s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        assert!(s.vehicle_gps_ready(), "public System GPS must not wait for private MD subscription");
        s.receive(0x10034,&[8,0],&cfg).unwrap();
        assert!(s.vehicle_gps_ready());
        s.reconfigure(ReceiverDisplay{size:(1600,600),confirmed:true,max_fps:None},&cfg).unwrap();
        assert!(!s.started);
        assert!(s.vehicle_gps_ready(), "GPS must continue during video reconfiguration");
        assert!(!Session::new(cfg.dialect).vehicle_gps_ready());
    }

    #[tokio::test]
    async fn system_plain_voice_uses_current_cmd_without_ctrl_and_drops_before_ready() {
        use tokio::net::TcpListener;
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let unavailable=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let absent=unavailable.local_addr().unwrap().port();drop(unavailable);
        let ports=[listener.local_addr().unwrap().port(),absent,absent,absent,absent,absent,absent];
        let stop=Arc::new(DialStop::default());
        let display=DisplayRequest::new(ReceiverDisplay::default());
        let (hub,mut events)=CarLifeHub::connect_to_ports(cfg(),"127.0.0.1".into(),ports,stop.clone(),Some(display.clone()),|_|{}).unwrap();
        let mut cmd=tokio::time::timeout(Duration::from_secs(2),listener.accept()).await.unwrap().unwrap().0;
        assert_eq!(next_frame(&mut cmd).await,(CMD_HU_PROTOCOL_VERSION,vec![8,3,16,0]));
        hub.voice_start();
        cmd.write_all(&build_cmd_frame(CMD_MD_INFO,&[])).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_INFO);
        cmd.write_all(&build_cmd_frame(CMD_VERSION_MATCH_STATUS,&[8,1])).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_STATISTIC_INFO);
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE);
        cmd.write_all(&build_cmd_frame(0x10022,&[])).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_INIT);
        cmd.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30))).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_START);
        assert_eq!(next_frame(&mut cmd).await.0,0x18043);
        while !matches!(events.recv().await,Some(CarLifeEvent::Handshake)) {}
        let mut byte=[0];
        assert!(tokio::time::timeout(Duration::from_millis(100),cmd.read(&mut byte)).await.is_err(), "early voice must not replay on ready");
        hub.voice_start();hub.release_touch();
        assert_eq!(next_frame(&mut cmd).await,(0x68008,vec![8,33]));
        assert!(!hub.system_voice_key(0));
        assert!(hub.system_voice_key(0x22));
        assert!(hub.system_voice_key(0x21));
        assert_eq!(next_frame(&mut cmd).await,(0x68008,vec![8,34]));
        assert_eq!(next_frame(&mut cmd).await,(0x68008,vec![8,33]));
        display.send_replace(ReceiverDisplay{size:(1600,600),max_fps:None,confirmed:true});
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE);
        assert!(hub.system_voice_key(0x22), "the lease STOP must remain available during video reconfiguration");
        assert_eq!(next_frame(&mut cmd).await,(0x68008,vec![8,34]));
        drop(cmd);
        let mut new_cmd=tokio::time::timeout(Duration::from_secs(3),listener.accept()).await.unwrap().unwrap().0;
        assert_eq!(next_frame(&mut new_cmd).await.0,CMD_HU_PROTOCOL_VERSION);
        assert!(tokio::time::timeout(Duration::from_millis(100),new_cmd.read(&mut byte)).await.is_err());
        stop.cancel();
    }

    #[tokio::test]
    async fn system_plain_voice_expiry_and_session_ownership_prevent_replay() {
        use tokio::net::TcpListener;
        let cfg=cfg();
        let ready=|| {
            let mut s=Session::new(cfg.dialect);
            s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
            s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
            s.receive(0x10022,&[],&cfg).unwrap();
            s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
            Arc::new(Mutex::new(s))
        };
        let target=SystemVoiceTarget::default();
        assert!(!target.queue(0x21));
        let old=ready();target.bind(&old);assert!(target.queue(0x21));
        let old_epoch=target.epoch().unwrap();
        let new=ready();target.bind(&new);
        assert_ne!(target.epoch(),Some(old_epoch));
        assert!(!target.queue_for_epoch(old_epoch,0x22), "a previous connection's lease STOP must be rejected");
        assert!(new.lock().take_voice().is_none(), "old CMD request must not migrate to its successor");
        assert!(target.queue(0x21));
        {
            let mut s=new.lock();
            s.matched=false;
            assert!(s.take_voice().is_none(), "a queued request loses CMD readiness before it is consumed");
            assert!(!s.queue_voice(0x21));
        }
        target.clear();assert!(target.epoch().is_none());assert!(!target.queue(0x21));
        let state=ready();target.bind(&state);assert!(target.queue(0x21));
        {
            let mut s=state.lock();
            let deadline=s.voice_pending.front().unwrap().1;
            assert!(deadline<=std::time::Instant::now()+Duration::from_secs(2));
            s.voice_pending.front_mut().unwrap().1=std::time::Instant::now()-Duration::from_millis(1);
        }
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();
        let (events,_rx)=mpsc::unbounded_channel();
        let task=tokio::spawn(run(socket,events,Arc::new(Mutex::new((1280,720))),
            DisplayRequest::new(ReceiverDisplay::default()),cfg,state.clone(),Arc::new(Notify::new()),true));
        assert_eq!(next_frame(&mut peer).await,(CMD_HU_PROTOCOL_VERSION,vec![8,3,16,0]));
        let mut byte=[0];
        assert!(tokio::time::timeout(Duration::from_millis(100),peer.read(&mut byte)).await.is_err(), "expired voice must not reach CMD");
        assert!(state.lock().voice_pending.is_empty());
        task.abort();let _=task.await;
        let mut legacy=Session::default();
        legacy.started=true;legacy.proof_ok=true;legacy.admitted=true;legacy.ready=true;
        assert!(!legacy.queue_voice(0x21), "CarWith must retain its original CTRL hard key path");
    }

    async fn source_voice_writer() -> (Shared,TcpStream,tokio::task::JoinHandle<Result<(),String>>) {
        use tokio::net::TcpListener;
        let cfg=cfg();let mut session=Session::new(cfg.dialect);
        session.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        session.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        session.receive(0x10022,&[],&cfg).unwrap();
        session.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        let state=Arc::new(Mutex::new(session));
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();
        let (events,_rx)=mpsc::unbounded_channel();
        let task=tokio::spawn(run(socket,events,Arc::new(Mutex::new((1280,720))),
            DisplayRequest::new(ReceiverDisplay::default()),cfg,state.clone(),Arc::new(Notify::new()),true));
        assert_eq!(next_frame(&mut peer).await,(CMD_HU_PROTOCOL_VERSION,vec![8,3,16,0]));
        (state,peer,task)
    }

    #[tokio::test(start_paused=true)]
    async fn system_plain_source_voice_lease_stops_without_an_engine_actor() {
        let (state,mut peer,task)=source_voice_writer().await;
        assert!(state.lock().queue_voice(0x21));
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,33]));
        tokio::time::advance(Duration::from_secs(60)).await;
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,34]), "CMD must own the 60-second fallback after its media actor has gone");
        // A delayed old actor STOP must not create a duplicate after source fallback.
        assert!(state.lock().queue_voice(0x22));
        let mut byte=[0];
        assert!(tokio::time::timeout(Duration::from_millis(100),peer.read(&mut byte)).await.is_err());
        task.abort();let _=task.await;
    }

    #[tokio::test(start_paused=true)]
    async fn system_plain_source_voice_restart_stops_the_previous_actor_lease() {
        let (state,mut peer,task)=source_voice_writer().await;
        assert!(state.lock().queue_voice(0x21));
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,33]));
        // A replacement media actor has no local knowledge of this CMD's old lease.
        assert!(state.lock().queue_voice(0x21));
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,34]));
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,33]));
        assert!(state.lock().queue_voice(0x22));
        assert_eq!(next_frame(&mut peer).await,(0x68008,vec![8,34]));
        tokio::time::advance(Duration::from_secs(60)).await;
        let mut byte=[0];
        assert!(tokio::time::timeout(Duration::from_millis(100),peer.read(&mut byte)).await.is_err(), "an explicit STOP clears the source lease");
        task.abort();let _=task.await;
    }

    #[test]
    fn system_plain_queued_voice_stop_survives_video_reconfiguration() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        s.receive(0x10022,&[],&cfg).unwrap();
        s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        assert!(s.queue_voice(0x21));assert_eq!(s.take_voice(),Some(0x21));
        assert!(s.queue_voice(0x22));
        s.reconfigure(ReceiverDisplay::default(),&cfg).unwrap();
        assert!(!s.started);
        assert_eq!(s.take_voice(),Some(0x22), "queued lease STOP belongs to CMD, not the video epoch");
        assert!(s.queue_voice(0x21));assert_eq!(s.take_voice(),Some(0x21));
        assert!(s.queue_voice(0x22));assert_eq!(s.take_voice(),Some(0x22));
    }

    #[test]
    fn system_plain_voice_queue_is_bounded_ordered_and_skips_expired_requests() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        s.receive(0x10022,&[],&cfg).unwrap();
        s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        assert!(!s.queue_voice(0));
        for i in 0..16 { assert!(s.queue_voice(if i%2==0 {0x22} else {0x21})); }
        assert!(!s.queue_voice(0x21));
        s.voice_pending.front_mut().unwrap().1=std::time::Instant::now()-Duration::from_millis(1);
        for i in 1..16 { assert_eq!(s.take_voice(),Some(if i%2==0 {0x22} else {0x21})); }
        assert!(s.take_voice().is_none());
    }

    async fn next_frame(peer:&mut TcpStream)->(u32,Vec<u8>) {
        let mut header=[0;8];
        tokio::time::timeout(Duration::from_secs(4),peer.read_exact(&mut header)).await.unwrap().unwrap();
        let count=u16::from_be_bytes([header[0],header[1]]) as usize;
        let mut body=vec![0;count];peer.read_exact(&mut body).await.unwrap();
        (u32::from_be_bytes(header[4..8].try_into().unwrap()),body)
    }
    #[tokio::test]
    async fn system_plain_loopback_starts_on_cmd_reconnects_late_video_and_optional_eof() {
        use tokio::net::TcpListener;
        let mut listeners=Vec::new();let mut ports=[0;7];
        for port in &mut ports {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            *port=listener.local_addr().unwrap().port();listeners.push(Some(listener));
        }
        // Some OEM engines open VIDEO only after processing the CMD handshake.
        drop(listeners[1].take());
        let stop=Arc::new(DialStop::default());
        let display=DisplayRequest::new(ReceiverDisplay::default());
        let (_hub,mut events)=CarLifeHub::connect_to_ports(cfg(),"127.0.0.1".into(),ports,stop.clone(),Some(display.clone()),|_|{}).unwrap();
        let mut cmd=tokio::time::timeout(Duration::from_secs(2),listeners[0].as_ref().unwrap().accept()).await.unwrap().unwrap().0;
        assert_eq!(next_frame(&mut cmd).await,(CMD_HU_PROTOCOL_VERSION,vec![8,3,16,0]));
        assert!(matches!(events.recv().await,Some(CarLifeEvent::SourceReset{..})));
        let mut peers=Vec::new();
        for listener in &listeners[2..] {
            peers.push(tokio::time::timeout(Duration::from_secs(2),listener.as_ref().unwrap().accept()).await.unwrap().unwrap().0);
        }
        drop(peers.remove(0)); // MEDIA EOF must reconnect its own lane, preserving CMD.
        let _media_again=tokio::time::timeout(Duration::from_secs(2),listeners[2].as_ref().unwrap().accept()).await.unwrap().unwrap();
        cmd.write_all(&build_cmd_frame(CMD_MD_INFO,&[])).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_INFO);
        cmd.write_all(&build_cmd_frame(CMD_VERSION_MATCH_STATUS,&[8,1])).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_STATISTIC_INFO);
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE);
        let wait=tokio::time::Instant::now();
        // No packet is sent by the peer: a real timer must issue INIT.
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_INIT);
        assert!(wait.elapsed()>=Duration::from_millis(1800));
        let video=TcpListener::bind(("127.0.0.1",ports[1])).await.unwrap();
        let video_peer=tokio::time::timeout(Duration::from_secs(2),video.accept()).await.unwrap().unwrap();
        cmd.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30))).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_START);
        assert_eq!(next_frame(&mut cmd).await.0,0x18043);
        loop { match tokio::time::timeout(Duration::from_secs(2),events.recv()).await.unwrap().unwrap() {
            CarLifeEvent::Handshake=>break,CarLifeEvent::SourceReset{..}=>panic!("optional lane reset CMD"),_=>{}
        }}
        display.send_replace(ReceiverDisplay{size:(1600,600),max_fps:Some(24),confirmed:true});
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE,
            "late display must reconfigure on the existing CMD socket");
        cmd.write_all(&build_cmd_frame(0x10022,&[])).await.unwrap();
        let (id,init)=next_frame(&mut cmd).await;assert_eq!(id,CMD_VIDEO_ENCODER_INIT);
        assert_eq!(first_int(&init,1),Some(1600));assert_eq!(first_int(&init,2),Some(600));assert_eq!(first_int(&init,3),Some(24));
        cmd.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,&init)).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_START);
        assert_eq!(next_frame(&mut cmd).await.0,0x18043);
        drop(video_peer);
        let _video_again=tokio::time::timeout(Duration::from_secs(2),video.accept()).await.unwrap().unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_HU_FEATURE_CONFIG_RESPONSE,
            "a reconnected VIDEO lane must request a new encoder epoch over CMD");
        cmd.write_all(&build_cmd_frame(0x10022,&[])).await.unwrap();
        let (id,init)=next_frame(&mut cmd).await;assert_eq!(id,CMD_VIDEO_ENCODER_INIT);
        cmd.write_all(&build_cmd_frame(CMD_VIDEO_ENCODER_INIT_DONE,&init)).await.unwrap();
        assert_eq!(next_frame(&mut cmd).await.0,CMD_VIDEO_ENCODER_START);
        assert_eq!(next_frame(&mut cmd).await.0,0x18043);
        drop(peers.remove(2)); // CTRL EOF is observed even with no queued writes.
        let _ctrl_again=tokio::time::timeout(Duration::from_secs(2),listeners[5].as_ref().unwrap().accept()).await.unwrap().unwrap();
        cmd.write_all(&build_cmd_frame(CMD_GO_TO_DESKTOP,&[])).await.unwrap();
        loop { match tokio::time::timeout(Duration::from_secs(2),events.recv()).await.unwrap().unwrap() {
            CarLifeEvent::GoToDesktop=>break,CarLifeEvent::SourceReset{..}=>panic!("optional lane reset CMD"),_=>{}
        }}
        stop.cancel();
    }
    #[test]
    fn system_plain_accepts_md_info_before_version_without_proprietary_fields() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        let actions=s.receive(CMD_MD_INFO,&[],&cfg).expect("public MD_INFO may arrive first");
        assert!(actions.iter().any(|(id,_)|*id==CMD_HU_INFO));
        let hu=&actions.iter().find(|(id,_)|*id==CMD_HU_INFO).unwrap().1;
        assert_eq!(pb_first_string(hu,13).as_deref(),Some("BMW"));
        assert_eq!(pb_first_string(hu,4).as_deref(),Some("BMW"));
        assert_eq!(pb_first_string(hu,7).as_deref(),Some("ID8.5"));
        assert_eq!(pb_first_string(hu,8).as_deref(),Some("1280x720"));
        assert_eq!(pb_first_string(hu,14).as_deref(),Some("ID8.5"));
        assert!(!actions.iter().any(|(id,_)|*id==CMD_HU_AUTHEN_REQUEST));
        let actions=s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        assert!(actions.iter().any(|(id,_)|*id==CMD_HU_FEATURE_CONFIG_RESPONSE));
        assert!(!s.proof_ok && !s.admitted && !s.ready && s.seed.is_none());
    }
    #[test]
    fn system_plain_ack_initializes_once_and_starts_without_fake_auth() {
        let cfg=cfg();let mut s=Session::new(cfg.dialect);
        s.receive(CMD_VERSION_MATCH_STATUS,&[8,1],&cfg).unwrap();
        s.receive(CMD_MD_INFO,&[],&cfg).unwrap();
        let actions=s.receive(0x10022,&[],&cfg).unwrap();
        assert_eq!(actions.iter().filter(|(id,_)|*id==CMD_VIDEO_ENCODER_INIT).count(),1);
        assert!(s.receive(0x10022,&[],&cfg).unwrap().is_empty());
        s.receive(CMD_VIDEO_ENCODER_INIT_DONE,&proto_encoder_info_payload(1280,720,30),&cfg).unwrap();
        assert!(s.navigation_ready());
        assert!(!s.proof_ok && !s.admitted && !s.ready && s.seed.is_none());
        assert!(s.receive(CMD_MD_RSA_KEY_REQUEST,&[],&cfg).is_err());
        assert!(s.seed.is_none());
    }
}
