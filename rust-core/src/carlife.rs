//! Local CarWith/CarLife transport. Xiaomi requires seven owned channels and an
//! authenticated CONTENT_ENCRYPTION handshake. System CarLife probes this flow
//! and public 3.0 or 3.2 in isolated rounds; package versions never determine encryption.
//! CMD/CTRL/DATA use 8-byte headers; VIDEO/MEDIA/TTS/VR use 12-byte headers.
//! CarWith DATA is port 9440. Do not mix generic CarLife assumptions with this path.

pub(crate) mod navigation;
mod session;
mod protocol_selection;
use protocol_selection::RoundEnd;
pub(crate) mod vehicle_gps;
mod log_sampling;
mod video;
pub(crate) mod microphone;
pub(super) mod channel_diagnostics;
mod voice_audio;

use std::sync::Arc;
use std::future::{poll_fn, Future};
use std::task::Poll;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, watch, Notify};

use crate::engine::CarLifeEvent;
use crate::emit_log;

// ---------------------------------------------------------------- 端口

pub const PORT_CMD: u16 = 7240;
pub const PORT_VIDEO: u16 = 8240;
pub const PORT_MEDIA: u16 = 9240;
pub const PORT_TTS: u16 = 9241;
pub const PORT_VR: u16 = 9242;
pub const PORT_CTRL: u16 = 9340;
/// CarWith（小米 CarLife）额外开的数据通道；公版 CarLife 没有这一路。
pub const PORT_DATA: u16 = 9440;

// ---------------------------------------------------------------- CMD 消息 ID

const CMD_HU_PROTOCOL_VERSION: u32 = 0x0001_8001;
const CMD_VERSION_MATCH_STATUS: u32 = 0x0001_0002;
const CMD_HU_INFO: u32 = 0x0001_8003;
const CMD_MD_INFO: u32 = 0x0001_0004;
const CMD_VIDEO_ENCODER_INIT: u32 = 0x0001_8007;
const CMD_VIDEO_ENCODER_INIT_DONE: u32 = 0x0001_0008;
const CMD_VIDEO_ENCODER_START: u32 = 0x0001_8009;
/// HU→MD：暂停编码（断开前发，让手机正常收尾，不留在半开状态）
/// HU→MD：让手机 CarLife 进入 Home（投屏）界面。**没有这条它就一直停在自己的落地页**。
/// HU→MD：进入 Phone 界面。
/// HU→MD：进入 Map 界面。
/// HU→MD：进入 Music 界面。
/// HU→MD：把 CarLife 切回前台（AOA 场景用）。
const CMD_HU_GO_TO_FOREGROUND: u32 = 0x0001_8025;
const CMD_SCREEN_ON: u32 = 0x0001_0018;
const CMD_FOREGROUND: u32 = 0x0001_001B;
const CMD_GO_TO_DESKTOP: u32 = 0x0001_0021;
const CMD_HU_STATISTIC_INFO: u32 = 0x0001_8027;
/// 模块级控制（文档 §10.2.2）：CarlifeModuleStatusList{cnt, moduleStatus{moduleID,statusID}}
const CMD_HU_AUTHEN_REQUEST: u32 = 0x0001_8048;
const CMD_MD_AUTHEN_RESPONSE: u32 = 0x0001_0049;
const CMD_HU_AUTHEN_RESULT: u32 = 0x0001_804A;
const CMD_MD_AUTHEN_RESULT: u32 = 0x0001_004B;
const CMD_MD_FEATURE_CONFIG_REQUEST: u32 = 0x0001_0051;
const CMD_HU_FEATURE_CONFIG_RESPONSE: u32 = 0x0001_8052;
const CMD_MD_EXIT: u32 = 0x0001_0059;
// ---- CONTENT_ENCRYPTION（协商能力，不由 Android 包名/版本决定）----
const CMD_MD_RSA_KEY_REQUEST: u32 = 0x0001_006A;
const CMD_MD_AES_SEED: u32 = 0x0001_006C;
const CMD_MD_ENCRYPT_READY: u32 = 0x0001_006E;
const CMD_HU_MD_AUTH_RESULT_ACK: u32 = 0x0001_804C;
const CMD_HU_RSA_PUBLIC_KEY: u32 = 0x0001_806B;
const CMD_HU_AES_ACK: u32 = 0x0001_806D;

/// 取 protobuf 里第一个指定 field 的 length-delimited 字符串。
fn pb_first_string(payload: &[u8], want_field: u32) -> Option<String> {
    let mut i = 0usize;
    while i < payload.len() {
        let (key, n) = pb_read_varint(payload, i)?;
        i = n;
        let field = (key >> 3) as u32;
        let wire = (key & 7) as u8;
        match wire {
            0 => { let (_, n) = pb_read_varint(payload, i)?; i = n; }
            2 => {
                let (len, n) = pb_read_varint(payload, i)?;
                i = n;
                let end = i.checked_add(len as usize)?;
                if end > payload.len() { return None; }
                if field == want_field {
                    return Some(String::from_utf8_lossy(&payload[i..end]).to_string());
                }
                i = end;
            }
            1 => i = i.checked_add(8)?,
            5 => i = i.checked_add(4)?,
            _ => return None,
        }
    }
    None
}

fn pb_read_varint(buf: &[u8], mut i: usize) -> Option<(u64, usize)> {
    let mut shift = 0u32;
    let mut out = 0u64;
    while i < buf.len() && shift < 64 {
        let b = buf[i];
        out |= ((b & 0x7f) as u64) << shift;
        i += 1;
        if b & 0x80 == 0 { return Some((out, i)); }
        shift += 7;
    }
    None
}

// 媒体/视频/TTS 通道消息（12 字节头内的 service_type）
const VIDEO_DATA: u32 = 0x0002_0001;
const VIDEO_HEARTBEAT: u32 = 0x0002_0002;
const MEDIA_INIT: u32 = 0x0003_0001;
const MEDIA_STOP: u32 = 0x0003_0002;
const MEDIA_PAUSE: u32 = 0x0003_0003;
const MEDIA_RESUME: u32 = 0x0003_0004;
const MEDIA_DATA: u32 = 0x0003_0006;
const TTS_INIT: u32 = 0x0004_0001;
const TTS_STOP: u32 = 0x0004_0002;
const TTS_DATA: u32 = 0x0004_0003;

// CTRL 通道（8 字节头）
const CTRL_CAR_HARD_KEY_CODE: u32 = 0x68008;
const SELECTOR_NEXT: u64 = 6;
const SELECTOR_PREVIOUS: u64 = 7;

/// 触摸动作（CarLife 语义：0=DOWN / 1=UP / 2=MOVE，**不是** Android 的 1/2/3）。
pub const TOUCH_DOWN: u32 = 0;
pub const TOUCH_UP: u32 = 1;
pub const TOUCH_MOVE: u32 = 2;

/// 协议版本（CarLifeWirelessBox 默认 v3.2，实测 CarWith 接受）。
    const PROTOCOL_MAJOR: u32 = 4;   // mcarplay 实测：CarWith 用 4.1
    const PROTOCOL_MINOR: u32 = 1;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CarLifeDialect {
    #[default]
    CarWithEncrypted41,
    SystemPlain30,
    SystemPlain32,
    /// Automatic selection is resolved to a concrete dialect before a round starts.
    SystemAuto,
}

impl CarLifeDialect {
    pub(crate) fn is_system_plain(self) -> bool {
        matches!(self, Self::SystemPlain30 | Self::SystemPlain32)
    }
}

#[derive(Clone)]
pub struct CarLifeConfig {
    pub dialect: CarLifeDialect,
    pub manufacturer: String,
    pub model: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub navigation_updates: bool,
    pub microphone: Option<Arc<crate::mic_probe::ProbeControl>>,
    pub(crate) vehicle_gps: Option<Arc<vehicle_gps::PendingLocation>>,
    /// `HU_INFO` 里 required 的 `btaddress`（车机蓝牙 MAC）。
    pub bt_address: String,
    /// `HU_INFO` 里 required 的 `btname`（车机蓝牙名）。
    pub bt_name: String,
}

/// Authenticated seven-channel CarWith gateway with video/audio events and touch input.
struct QueuedControl {
    bytes: Vec<u8>,
    expires: Option<std::time::Instant>,
    touch: Option<QueuedTouch>,
}

enum QueuedTouch {
    Report(crate::airplay::TouchInput, (u32, u32)),
    Release,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ReceiverDisplay {
    pub size: (u32, u32),
    pub max_fps: Option<u32>,
    // A prepared encoder can use preliminary values for INIT. Delay its one permitted
    // local reopen until the post-SETUP display selection has delivered both fields.
    pub confirmed: bool,
}
impl ReceiverDisplay {
    pub fn effective_fps(&self, requested: u32) -> u32 { self.max_fps.unwrap_or(requested).min(requested) }
}
pub type DisplayRequest = tokio::sync::watch::Sender<ReceiverDisplay>;

/// Retirement persists across the gaps between the timer, the JNI callback and each dial.
#[derive(Default)]
pub(crate) struct DialStop {
    retired: Mutex<bool>,
    signal: Arc<Notify>,
    diagnostic_phase: Arc<DiagnosticPhase>,
    video_rate_failure: watch::Sender<Option<u32>>,
}
impl DialStop {
    pub(crate) fn report_video_rate_failure(&self, fps: u32) {
        self.video_rate_failure.send_replace(Some(fps));
    }

    pub(crate) fn cancel(&self) {
        *self.retired.lock() = true;
        self.signal.notify_waiters();
    }
    pub(crate) fn diagnostic_phase(&self) -> Arc<DiagnosticPhase> { self.diagnostic_phase.clone() }
    fn is_retired(&self) -> bool { *self.retired.lock() }
    async fn cancelled(&self) {
        let notified = self.signal.notified();
        if !self.is_retired() { notified.await; }
    }

    async fn dial(&self, host: &str, port: u16) -> Option<std::io::Result<TcpStream>> {
        let connect = dial(host, port);
        tokio::pin!(connect);
        tokio::select! {
            biased;
            _ = self.cancelled() => None,
            result = poll_fn(|cx| {
                // A tap between select's first branch and connect's first poll must also win.
                // Hold the lock for a nonblocking poll only, never while waiting for TCP.
                let retired = self.retired.lock();
                if *retired { Poll::Ready(None) } else { connect.as_mut().poll(cx).map(Some) }
            }) => result,
        }
    }
}

/// Diagnostic metadata only. The video pump opens the gate after writing a real frame.
#[derive(Default)]
pub(crate) struct DiagnosticPhase(std::sync::atomic::AtomicBool);
impl DiagnosticPhase {
    pub(crate) fn set_streaming(&self, active: bool) {
        self.0.store(active, std::sync::atomic::Ordering::Relaxed);
    }
    pub(crate) fn streaming(&self) -> bool {
        self.0.load(std::sync::atomic::Ordering::Relaxed)
    }
    fn inspect_video_unit(&self, units: u64) -> bool { units == 1 || self.streaming() }
}

#[cfg(test)]
#[test]
fn carwith_preflight_logs_only_first_access_unit_until_streaming() {
    let phase = DiagnosticPhase::default();
    assert!(phase.inspect_video_unit(1));
    assert!(!phase.inspect_video_unit(2));
    assert!(!phase.inspect_video_unit(1000));
    phase.set_streaming(true);
    assert!(phase.inspect_video_unit(1001));
    phase.set_streaming(false);
    assert!(!phase.inspect_video_unit(1002));
}

// All CarWith entries share the same local ports. Each task joins its predecessor, including
// a predecessor cancelled before it got a turn, so a third attempt cannot overtake the first.
static LAST_DIALER: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);

pub struct CarLifeHub {
    stop: Arc<DialStop>,
    input_queue: Arc<Mutex<Vec<QueuedControl>>>,
    system_voice: Option<Arc<session::SystemVoiceTarget>>,
    /// 当前编码分辨率共享单元（与 AirPlay 协商/触摸换算共用）。
    encoder_size: Arc<Mutex<(u32, u32)>>,
    keyframe_request: Arc<Notify>,
}

impl CarLifeHub {
    /// **主动连接模式**（盒子角色）：MD（手机上的 CarLife/CarWith）在标准端口上监听，
    /// 由 HU 侧逐个拨过去。
    ///
    /// 为什么需要它：小米 CarLife 走「车辆热点 → 开始连接」时，方向是反的——
    /// 现场 2026-10-23 22:56 实测，点「开始连接」后手机 `com.baidu.carlife.xiaomi`
    /// （uid 10345）开始 **LISTEN** `7240/8240/9240/9241/9242/9340`，等车机连入；
    /// 这与参考实现 `CarLifeWirelessBox` 的 `HuRole.connect(phoneIp, port)` 一致。
    /// Android CarWith uses this active seven-channel loopback connection.
    pub fn connect(
        cfg: CarLifeConfig, md_host: String, stop: Arc<DialStop>, requested: Option<DisplayRequest>,
        reopen_source: impl Fn(bool) + Send + 'static,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        Self::connect_to_ports(cfg, md_host, [PORT_CMD,PORT_VIDEO,PORT_MEDIA,PORT_TTS,PORT_VR,PORT_CTRL,PORT_DATA],
            stop, requested, reopen_source)
    }

    fn connect_to_ports(
        cfg: CarLifeConfig, md_host: String, ports: [u16; 7], stop: Arc<DialStop>, requested: Option<DisplayRequest>,
        reopen_source: impl Fn(bool) + Send + 'static,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        let system_voice = (cfg.dialect != CarLifeDialect::CarWithEncrypted41).then(|| Arc::new(session::SystemVoiceTarget::default()));
        let voice_target = system_voice.clone();
        let keyframe_request = Arc::new(Notify::new());
        let reset_request = keyframe_request.clone();
        let (events, event_rx) = mpsc::unbounded_channel();
        // The INIT hint does not define touch bounds. Wait for VIDEO_INIT_DONE.
        let encoder_size = Arc::new(Mutex::new((0, 0)));
        let requested = requested.unwrap_or_else(|| DisplayRequest::new(ReceiverDisplay::default()));
        let queue = Arc::new(Mutex::new(Vec::<QueuedControl>::new()));
        // One synchronous bounded queue: a round retirement clears every accepted
        // input under the same lock, with no unconsumed forwarding-channel backlog.
        let input_queue=queue.clone();
        let events_task=events.clone(); let size=encoder_size.clone(); let config=cfg.clone();
        let hub_stop = stop.clone();
        let mut last_dialer = LAST_DIALER.lock();
        let previous = last_dialer.take();
        *last_dialer = Some(tokio::spawn(async move {
            if let Some(previous) = previous { let _ = previous.await; }
            let automatic=config.dialect==CarLifeDialect::SystemAuto;
            let mut selection=protocol_selection::Selection::new(config.dialect);
            let mut config=config;
            let mut throttle=DialLogThrottle::new();
            let mut startup_refusals = 0;
            let mut first_round_pending = true;
            let mut can_reopen_display = true;
            loop {
                config.dialect=selection.current();
                // Own all seven sockets together. A failed/closed CMD invalidates the whole round.
                let mut sockets=Vec::new();
                for (index, port) in ports.into_iter().enumerate() {
                    if (automatic || config.dialect.is_system_plain()) && index > 0 { break; }
                    let Some(result) = stop.dial(&md_host, port).await else { return; };
                    match result {
                        Ok(socket) => {
                            if sockets.is_empty() { startup_refusals = 0; }
                            sockets.push(socket);
                        }
                        Err(error) => {
                            if sockets.is_empty() && error.kind() == std::io::ErrorKind::ConnectionRefused {
                                selection.command_listener_unavailable();
                            }
                            if first_round_pending && sockets.is_empty() &&
                                error.kind() == std::io::ErrorKind::ConnectionRefused {
                                startup_refusals += 1;
                                if startup_refusals == 8 {
                                    startup_refusals = 0;
                                    // The Android attempt gate submits at most one startup retry.
                                    // Keep checking until the AirPlay listener is available.
                                    if !stop.is_retired() { reopen_source(true); }
                                }
                            } else {
                                startup_refusals = 0;
                            }
                            if throttle.ready() {
                                emit_log(&format!("CARWITH-HS: waiting for all seven channels; port={port} error={error}"));
                            }
                            break;
                        }
                    }
                }
                if sockets.len()!=if automatic || config.dialect.is_system_plain() { 1 } else { ports.len() } {
                    drop(sockets);
                    tokio::select! { biased; _=stop.cancelled()=>return, _=tokio::time::sleep(selection.reconnect_delay())=>{} }
                    continue;
                }
                if stop.is_retired() { return; }
                first_round_pending = false;
                if config.dialect.is_system_plain() {
                    emit_log(&format!("CARLIFE-HS: CMD connected; start {:?} handshake and owned channel supervisors", config.dialect));
                } else {
                    emit_log(if automatic { "CARLIFE-HS: CMD connected; probe 4.1 handshake with owned channel supervisors" } else { "CARWITH-HS: all seven channels connected; start fresh 4.1 handshake" });
                }
                emit_log(&format!("CARLIFE-PROTOCOL: mode={:?} automatic={automatic}; package version is not protocol version",config.dialect));
                queue.lock().clear();
                let _ = events_task.send(CarLifeEvent::SourceReset { cache_cleared: None });
                let mut round = session::Session::new(config.dialect);
                round.video_rate_failure = stop.video_rate_failure.clone();
                let video_sync_received = if automatic || can_reopen_display {
                    let (ready, observed) = watch::channel(false);
                    round.video_sync_received = Some(ready);
                    Some(observed)
                } else { None };
                let protocol_progress = round.protocol_progress.clone();
                let video_start_written = round.video_start_written.clone();
                let state = session::Shared::new(Mutex::new(round));
                if let Some(target) = &voice_target { target.bind(&state); }
                // Keep a probe RESET permit local to these seven sockets if this round terminates.
                #[cfg(test)]
                let reset_request = if std::env::var_os("HC_PROBE_VIDEO_RECOVERY").is_some() {
                    Arc::new(Notify::new())
                } else { reset_request.clone() };
                let mut sockets=sockets.into_iter();
                let cmd=sockets.next().unwrap();
                let mut children=tokio::task::JoinSet::new();
                if automatic || config.dialect.is_system_plain() {
                    // Attempt the secondary lanes without making their availability a
                    // precondition for CMD. Plaintext lanes also reconnect independently.
                    let initial_barrier=Arc::new(tokio::sync::Barrier::new(7));
                    for (index, lane) in [SystemLane::Video, SystemLane::Media, SystemLane::Tts,
                        SystemLane::Vr, SystemLane::Ctrl, SystemLane::Data].into_iter().enumerate() {
                        children.spawn(supervise_system_lane(md_host.clone(), ports[index+1], lane,
                            config.clone(), state.clone(), events_task.clone(), queue.clone(), stop.clone(),initial_barrier.clone()));
                    }
                    tokio::select! {
                        biased;
                        _=stop.cancelled()=>{children.shutdown().await;return;},
                        _=initial_barrier.wait()=>{}
                    }
                } else {
                for (stream,kind,label) in [
                    (sockets.next().unwrap(),MediaKind::Video,"VIDEO"),
                    (sockets.next().unwrap(),MediaKind::Media,"MEDIA"),
                    (sockets.next().unwrap(),MediaKind::Tts,"TTS"),
                ] {
                    children.spawn(handle_media_client(stream,events_task.clone(),kind,label,Some(state.clone()),stop.diagnostic_phase()));
                }
                let vr = sockets.next().unwrap();
                if let Some(mic) = config.microphone.clone() {
                    mic.request(false);
                    children.spawn(microphone::run(vr, state.clone(), mic, stop.signal.clone(), events_task.clone(), stop.diagnostic_phase()));
                } else { children.spawn(drain_stream(vr, state.clone(), events_task.clone(), stop.diagnostic_phase())); }
                let ctrl=sockets.next().unwrap(); let q=queue.clone(); let cipher=state.clone(); let stop_ctrl=stop.signal.clone();
                children.spawn(async move { session::control(ctrl,q,cipher,stop_ctrl).await; });
                children.spawn(session::data(sockets.next().unwrap(),state.clone(),events_task.clone()));
                }
                let (outcome, cause) = tokio::select! {
                    biased;
                    _=stop.cancelled()=>{children.shutdown().await; return;},
                    result=session::run(cmd,events_task.clone(),size.clone(),requested.clone(),config.clone(),state.clone(),reset_request.clone(),can_reopen_display && !automatic)=>(result, RoundEnd::Command),
                    _=children.join_next()=>(Err("CarWith media/control channel closed".into()), RoundEnd::Transport),
                    _=video_start_deadline(video_start_written, video_sync_received.clone()), if automatic =>
                        (Err("VIDEO missing a sync picture after START".into()), RoundEnd::VideoStartup),
                    _=session::handshake_deadline(&protocol_progress), if automatic =>
                        (Err("automatic CarLife handshake deadline; no complete source handshake".into()), RoundEnd::Command),
                };
                *size.lock() = (0, 0);
                if let Some(target) = &voice_target { target.clear(); }
                children.abort_all(); while children.join_next().await.is_some() {}
                queue.lock().clear();
                if let Some(mic) = config.microphone.as_ref() { mic.request(false); }
                let _ = events_task.send(CarLifeEvent::Navigation(None));
                if stop.is_retired() {return;}
                let mut evidence = protocol_progress.evidence();
                evidence.video_sync = video_sync_received.as_ref().is_some_and(|ready| *ready.borrow());
                if evidence.started {
                    emit_log(&format!("CARLIFE-PROTOCOL: START sent mode={:?} authentication={} video_sync={}",config.dialect,evidence.authentication,evidence.video_sync));
                }
                if automatic {
                    // Every owned lane has stopped. Invalidate formats and replay
                    // state before waiting for a new CMD, even in the same mode.
                    let _=events_task.send(CarLifeEvent::SourceReset {cache_cleared:None});
                }
                if selection.round_ended(evidence, cause) {
                    emit_log(&format!("CARLIFE-PROTOCOL: previous mode={:?}; next fresh round={:?}; old children and queued input retired",config.dialect,selection.current()));
                }
                if outcome.is_ok() {
                    // All producers for the old round have exited. Flush their queued events
                    // and the replay cache before letting a reattached engine see the waiting period.
                    if !wait_for_canvas_reopen(&events_task, &stop).await || stop.is_retired() { return; }
                    can_reopen_display = false;
                    reopen_source(false);
                    continue;
                }
                emit_log(&format!("CARWITH-HS: round closed: {}",outcome.unwrap_err()));
                tokio::select! { biased; _=stop.cancelled()=>return, _=tokio::time::sleep(selection.reconnect_delay())=>{} }
            }
        }));
        Ok((Self {stop:hub_stop,input_queue,system_voice,encoder_size,keyframe_request},event_rx))
    }

    pub fn request_keyframe(&self) {
        self.keyframe_request.notify_one();
    }

    /// Single-point input used by the source transport probes.
    #[cfg(test)]
    pub fn inject_touch(&self, action: u32, x: u32, y: u32) {
        self.inject_touch_report(&crate::airplay::TouchInput { action, x, y, pointers: Vec::new(), grant: None });
    }

    pub(crate) fn inject_touch_report(&self, report: &crate::airplay::TouchInput) {
        let size = *self.encoder_size.lock();
        let _ = self.enqueue_control(QueuedControl {
            bytes: Vec::new(), expires: None, touch: Some(QueuedTouch::Report(report.clone(), size)),
        });
    }

    /// A media subscriber can disappear while a finger is down; the source survives it.
    pub fn release_touch(&self) {
        let expires = Some(std::time::Instant::now() + Duration::from_secs(1));
        let _ = self.enqueue_control(QueuedControl { bytes: Vec::new(), expires, touch: Some(QueuedTouch::Release) });
    }

    fn queue_hard_key(&self, code: u64, lifetime: Duration) -> bool {
        let mut body = Vec::new();
        proto::varint(&mut body, 1, code);
        self.enqueue_control(QueuedControl {
            bytes: build_cmd_frame(CTRL_CAR_HARD_KEY_CODE, &body),
            expires: Some(std::time::Instant::now() + lifetime),
            touch: None,
        })
    }
    fn enqueue_control(&self, value:QueuedControl) -> bool {
        let mut queue=self.input_queue.lock();
        if self.stop.is_retired() {return false;}
        if queue.len()>=256 {queue.remove(0);}
        queue.push(value);
        true
    }

    pub fn system_voice_epoch(&self) -> Option<u64> {
        if self.stop.is_retired() { return None; }
        self.system_voice.as_ref().and_then(|target| target.epoch())
    }

    pub fn system_voice_key_for_epoch(&self, epoch:u64, key:u64) -> bool {
        let queued = matches!(key, 0x21 | 0x22) && !self.stop.is_retired() &&
            self.system_voice.as_ref().is_some_and(|target| target.queue_for_epoch(epoch,key));
        emit_log(&format!("VOICE-KEY: System CMD 0x68008 epoch={epoch} key=0x{key:x} queued={queued} (recognition unconfirmed)"));
        queued
    }

    /// System's current CMD connection owns these short-lived VR_START/VR_STOP requests.
    pub fn system_voice_key(&self, key: u64) -> bool {
        let queued = matches!(key, 0x21 | 0x22) && !self.stop.is_retired() &&
            self.system_voice.as_ref().is_some_and(|target| target.queue(key));
        emit_log(&format!("VOICE-KEY: System CMD 0x68008 key=0x{key:x} queued={queued} (recognition unconfirmed)"));
        queued
    }

    pub fn voice_start(&self) {
        if self.system_voice_epoch().is_some() { let _ = self.system_voice_key(0x21); return; }
        let queued = self.queue_hard_key(0x21, Duration::from_secs(2));
        emit_log(&format!("VOICE-KEY: CarWith key=0x21 queued={queued} (not recognition confirmation)"));
    }

    pub fn handle_phone_key(&self, key: u32) {
        let code = match key {
            crate::consumer_hid::PHONE_HOOK => 2,
            crate::consumer_hid::PHONE_DROP => 3,
            _ => return,
        };
        // Only the explicitly selected CarWith 4.1 source has no system voice target.
        // SystemAuto can probe that dialect too; it still must not gain phone control.
        if self.system_voice.is_some() {
            emit_log(&format!("PHONE-KEY: usage=0x{key:x} dropped source=system-carlife"));
            return;
        }
        let queued = self.queue_hard_key(code, Duration::from_secs(1));
        emit_log(&format!("PHONE-KEY: usage=0x{key:x} CarWith key={code} queued={queued} (execution unconfirmed)"));
    }

    pub fn handle_console_key(&self, key: u32) -> bool {
        use crate::consumer_hid::*;
        // CarLife CommonParams.KEYCODE_* on the phone's CTRL channel.
        let code = match key {
            CONSOLE_BACK => 14,
            CONSOLE_SELECT => 20,
            CONSOLE_LEFT => 21,
            CONSOLE_RIGHT => 22,
            CONSOLE_UP => 23,
            CONSOLE_DOWN => 24,
            CONSOLE_PREV => SELECTOR_PREVIOUS,
            CONSOLE_NEXT => SELECTOR_NEXT,
            _ => return false,
        };
        if !self.queue_hard_key(code, Duration::from_secs(1)) {
            emit_log("CARWITH-KEY: CTRL queue closed");
        }
        // The input belongs to this source even when its CTRL writer has closed.
        true
    }
}

impl Drop for CarLifeHub {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

#[derive(Clone, Copy)]
enum SystemLane { Video, Media, Tts, Vr, Ctrl, Data }

async fn video_start_deadline(start_written: Arc<Notify>, sync_received: Option<watch::Receiver<bool>>) {
    let Some(mut sync_received) = sync_received else { return std::future::pending().await; };
    start_written.notified().await;
    let ready = matches!(tokio::time::timeout(Duration::from_secs(8), sync_received.wait_for(|ready| *ready)).await, Ok(Ok(_)));
    if ready {
        // Once video starts, plaintext lane recovery remains independent.
        std::future::pending::<()>().await;
    }
}

async fn supervise_system_lane(host:String, port:u16, lane:SystemLane, cfg:CarLifeConfig,
    state:session::Shared, events:mpsc::UnboundedSender<CarLifeEvent>,
    queue:Arc<Mutex<Vec<QueuedControl>>>, stop:Arc<DialStop>, initial_barrier:Arc<tokio::sync::Barrier>) {
    let progress = state.lock().protocol_progress.clone();
    let mut first_attempt=true;
    loop {
        if stop.is_retired() { return; }
        // Attempt the actual owned connection, never a disposable readiness probe.
        let socket = match tokio::time::timeout(Duration::from_millis(250), stop.dial(&host, port)).await {
            Ok(Some(Ok(socket))) => Some(socket),
            Ok(None) => return,
            _ => None,
        };
        if first_attempt {
            first_attempt=false;
            tokio::select! { biased; _=stop.cancelled()=>return, _=initial_barrier.wait()=>{} }
        }
        if let Some(socket) = socket {
            if matches!(lane,SystemLane::Video) { state.lock().request_reconfigure(); }
            tokio::select! {
                biased;
                _ = stop.cancelled() => return,
                _ = async {
                    match lane {
                        SystemLane::Video => handle_media_client(socket,events.clone(),MediaKind::Video,"VIDEO",Some(state.clone()),stop.diagnostic_phase()).await,
                        SystemLane::Media => handle_media_client(socket,events.clone(),MediaKind::Media,"MEDIA",Some(state.clone()),stop.diagnostic_phase()).await,
                        SystemLane::Tts => handle_media_client(socket,events.clone(),MediaKind::Tts,"TTS",Some(state.clone()),stop.diagnostic_phase()).await,
                        SystemLane::Vr => if let Some(mic)=cfg.microphone.clone() {
                            microphone::run(socket,state.clone(),mic,stop.signal.clone(),events.clone(),stop.diagnostic_phase()).await;
                        } else { drain_stream(socket,state.clone(),events.clone(),stop.diagnostic_phase()).await; },
                        SystemLane::Ctrl => session::control(socket,queue.clone(),state.clone(),stop.signal.clone()).await,
                        SystemLane::Data => session::data(socket,state.clone(),events.clone()).await,
                    }
                } => {}
            }
            match lane {
                SystemLane::Vr => {
                    if let Some(mic)=cfg.microphone.as_ref() { mic.request(false); }
                    let _=events.send(CarLifeEvent::VoiceAudioStop);
                }
                SystemLane::Media | SystemLane::Tts => { let _=events.send(CarLifeEvent::AudioStop{tts:matches!(lane,SystemLane::Tts)}); }
                _=>{}
            }
            // Before authentication, a secondary socket can be reopened without
            // losing the CMD probe. After authentication, retire the whole round.
            if cfg.dialect == CarLifeDialect::CarWithEncrypted41 &&
                progress.evidence().authentication {
                return;
            }
        }
        tokio::select! { biased; _=stop.cancelled()=>return, _=tokio::time::sleep(Duration::from_millis(250))=>{} }
    }
}

pub(crate) async fn wait_for_canvas_reopen(events: &mpsc::UnboundedSender<CarLifeEvent>, stop: &DialStop) -> bool {
    let cleared = Arc::new(Notify::new());
    if events.send(CarLifeEvent::SourceReset { cache_cleared: Some(cleared.clone()) }).is_err() { return false; }
    tokio::select! {
        biased;
        _ = stop.cancelled() => false,
        _ = async {
            cleared.notified().await;
            tokio::time::sleep(Duration::from_secs(4)).await;
        } => true,
    }
}

/// 单调毫秒时间戳（媒体帧头用；只需相对值）。
fn now_ms() -> u32 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u32)
        .unwrap_or(0)
}

/// 媒体帧（12 字节头）：`[u32 BE len][u32 BE timestamp][u32 BE type][payload]`。CarWith VIDEO 时间为 Unix 秒。
fn build_media_frame(service_type: u32, timestamp_ms: u32, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(12 + payload.len());
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(&timestamp_ms.to_be_bytes());
    out.extend_from_slice(&service_type.to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// CMD/CTRL 帧（8 字节头）：`[u16 BE len][u16 reserved][u32 BE type][payload]`。
fn build_cmd_frame(service_type: u32, payload: &[u8]) -> Vec<u8> {
    let len = payload.len().min(u16::MAX as usize);
    let mut out = Vec::with_capacity(8 + len);
    out.extend_from_slice(&(len as u16).to_be_bytes());
    out.extend_from_slice(&[0u8, 0u8]);
    out.extend_from_slice(&service_type.to_be_bytes());
    out.extend_from_slice(&payload[..len]);
    out
}

/// 从缓冲切一条 CMD/CTRL 帧：返回 (service_type, payload, 消费字节数)。
fn read_cmd_frame(buf: &[u8]) -> Option<(u32, Vec<u8>, usize)> {
    let (kind, payload, consumed) = cmd_frame_parts(buf)?;
    Some((kind, payload.to_vec(), consumed))
}

fn cmd_frame_parts(buf: &[u8]) -> Option<(u32, &[u8], usize)> {
    if buf.len() < 8 {
        return None;
    }
    let len = u16::from_be_bytes([buf[0], buf[1]]) as usize;
    let service_type = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let total = 8 + len;
    if buf.len() < total {
        return None;
    }
    Some((service_type, &buf[8..total], total))
}

/// 媒体帧（12 字节头）：`[u32 BE len][u32 BE timestamp][u32 BE type][payload]`。CarWith VIDEO 时间为 Unix 秒。
fn read_media_frame(buf: &[u8]) -> Option<(u32, u32, Vec<u8>, usize)> {
    if buf.len() < 12 {
        return None;
    }
    let len = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if len > 32 * 1024 * 1024 {
        // 非法长度：无法安全重同步，整块丢弃由调用方处理
        return Some((0, 0, Vec::new(), buf.len()));
    }
    let total = 12 + len;
    if buf.len() < total {
        return None;
    }
    let ts = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let service_type = u32::from_be_bytes([buf[8], buf[9], buf[10], buf[11]]);
    Some((service_type, ts, buf[12..total].to_vec(), total))
}

// ---------------------------------------------------------------- protobuf 最小写入器

mod proto {
    fn put_varint(out: &mut Vec<u8>, mut value: u64) {
        loop {
            let mut b = (value & 0x7F) as u8;
            value >>= 7;
            if value != 0 {
                b |= 0x80;
            }
            out.push(b);
            if value == 0 {
                break;
            }
        }
    }

    pub fn varint(out: &mut Vec<u8>, field: u32, value: u64) {
        put_varint(out, ((field as u64) << 3) | 0);
        put_varint(out, value);
    }

    pub fn string(out: &mut Vec<u8>, field: u32, s: &str) {
        put_varint(out, ((field as u64) << 3) | 2);
        put_varint(out, s.len() as u64);
        out.extend_from_slice(s.as_bytes());
    }

    pub fn bytes(out: &mut Vec<u8>, field: u32, b: &[u8]) {
        put_varint(out, ((field as u64) << 3) | 2);
        put_varint(out, b.len() as u64);
        out.extend_from_slice(b);
    }

    pub fn boolean(out: &mut Vec<u8>, field: u32, v: bool) {
        varint(out, field, if v { 1 } else { 0 });
    }
}

async fn send_cmd(wr: &mut tokio::net::tcp::OwnedWriteHalf, ty: u32, payload: &[u8]) -> bool {
    let frame = build_cmd_frame(ty, payload);
    match wr.write_all(&frame).await {
        Ok(()) => {
            let _ = wr.flush().await;
            true
        }
        Err(e) => {
            emit_log(&format!("CARLIFE: CMD 写失败（0x{ty:08x}）: {e}"));
            false
        }
    }
}

fn proto_version_payload() -> Vec<u8> {
    let mut out = Vec::with_capacity(8);
    proto::varint(&mut out, 1, PROTOCOL_MAJOR as u64);
    proto::varint(&mut out, 2, PROTOCOL_MINOR as u64);
    out
}

fn proto_statistic_payload() -> Vec<u8> {
    // CarlifeStatisticsInfo: 1=cuid, 2=version_name, 3=version_code, 4=channel,
    // 5=connect_count, 6=connect_success_count, 7=connect_time
    let mut out = Vec::with_capacity(64);
    proto::string(&mut out, 1, "hypercar");
    proto::string(&mut out, 2, "4.0.0");
    proto::varint(&mut out, 3, 16);
    proto::string(&mut out, 4, "20352101");
    proto::varint(&mut out, 5, 0);
    proto::varint(&mut out, 6, 0);
    proto::varint(&mut out, 7, 0);
    out
}

/// `CarlifeDeviceInfo`（HU 的设备信息）——**字段必须与官方 proto 一致**。
///
/// 以前这里错得离谱：按"1=device_type(int) 2=os_type(int) 9=screen_width…"自己编了一套，
/// 而官方 proto（`apollo-DuerOS/CarLife-Vehicle-Lib/LibSource/protobuf/proto/
/// CarlifeDeviceInfoProto.proto`）是：
/// `1=os(string) 2=board 3=bootloader 4=brand 5=cpu_abi 6=cpu_abi2 7=device 8=display
///  9=fingerprint 10=hardware 11=host 12=cid 13=manufacturer 14=model 15=product
///  16=serial 17=codename 18=incremental 19=release 20=sdk 21=sdk_int(int32) 24=carlifeversion`。
/// 字段 1/2 我们发了 varint，而 proto 声明是 string——手机端按 protobuf 解析这条消息会失败。
/// `MSG_CMD_HU_INFO`(0x18003) 的 PB：`CarlifeDeviceInfo`（HU 方向只允许这几个字段）
///
/// 依据《百度 CarLife 车机手机互联方案接入详细指南 V2.1.1》10.2.2 表：
/// ```text
/// message CarlifeDeviceInfo {
///     optional string os           = 1;   // Android / QNX / Linux / Wince
///     optional string manufacturer = 13;
///     optional string model        = 14;
///     optional string release      = 19;
///     required string btaddress    = 23;  // 车机蓝牙 MAC
///     required string btname       = 24;  // 车机蓝牙名
/// }
/// ```
/// 之前这里塞的是**手机侧**的全套字段（还把 24 号当成 carlifeversion 放 "7.0.0"，
/// 那是 MD_INFO 的语义），并且缺了 required 的 btaddress —— MD 解析必然异常。
fn proto_hu_info_payload(cfg: &CarLifeConfig) -> Vec<u8> {
    let mut out = Vec::with_capacity(96);
    proto::string(&mut out, 1, "Android"); // os
    if cfg.dialect.is_system_plain() {
        // Legacy OEM parsers also inspect the Android device identity fields.
        // Do not claim an unrelated reference handset board or receiver identity.
        proto::string(&mut out,2,"unknown");
        proto::string(&mut out,3,"unknown");
        proto::string(&mut out,4,if cfg.manufacturer.is_empty(){"HyperCar"}else{&cfg.manufacturer});
        proto::string(&mut out,5,"arm64-v8a");
        proto::string(&mut out,6,"arm64-v8a");
        proto::string(&mut out,7,if cfg.model.is_empty(){"HyperCar HU"}else{&cfg.model});
        proto::string(&mut out,8,&format!("{}x{}",cfg.width,cfg.height));
    }
    proto::string(&mut out, 13, if cfg.manufacturer.is_empty() { "HyperCar" } else { &cfg.manufacturer }); // manufacturer
    proto::string(&mut out, 14, if cfg.model.is_empty() { "HyperCar HU" } else { &cfg.model }); // model
    proto::string(&mut out, 19, "14"); // release
    proto::string(&mut out, 23, &cfg.bt_address); // btaddress（required）
    proto::string(&mut out, 24, &cfg.bt_name); // btname（required）
    out
}

fn proto_authen_result_payload(ok: bool) -> Vec<u8> {
    // CarlifeAuthenResult: 1=authen_result(bool)
    let mut out = Vec::with_capacity(4);
    proto::boolean(&mut out, 1, ok);
    out
}

fn proto_encoder_info_payload(width: u32, height: u32, fps: u32) -> Vec<u8> {
    // CarlifeVideoEncoderInfo: 1=width, 2=height, 3=frameRate（均 int32）
    let mut out = Vec::with_capacity(16);
    proto::varint(&mut out, 1, width as u64);
    proto::varint(&mut out, 2, height as u64);
    proto::varint(&mut out, 3, fps as u64);
    out
}

/// `CarlifeModuleStatusList`：`1=cnt, 2=repeated{1=moduleID, 2=statusID}`。
///
/// 官方文档 §10.2.2 `MSG_CMD_MODULE_CONTROL(0x18028)`：HU 用它启/停某个模块
/// （`ModuleId{CARLIFE_PHONE=1, NAVI=2, MUSIC=3, VR=4, CONNECT=5, MIC=6, MEDIAPCM=7, EDOG=8, CRUISE=9}`），
/// status=1 表示 enable。CarLife 若把"投屏模块"当成未启用，就不会建渲染源（Qin:0 的一种可能）。
#[derive(Clone, Copy)]
enum MediaKind {
    Video,
    Media,
    Tts,
}

/// VIDEO/MEDIA/TTS 通道：12 字节头 + 裸数据（视频 H.264 AnnexB / 音频 PCM）。
async fn handle_media_client(
    stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    kind: MediaKind,
    label: &'static str,
    encryption: Option<session::Shared>,
    diagnostic_phase: Arc<DiagnosticPhase>,
) {
    let (mut rd, mut wr) = stream.into_split();
    let is_video = matches!(kind, MediaKind::Video);
    let mut first_sync = if is_video {
        encryption.as_ref().and_then(|state| state.lock().video_sync_received.clone())
            .filter(|ready| !*ready.borrow())
    } else {
        None
    };
    let mut buf: Vec<u8> = Vec::with_capacity(256 * 1024);
    let mut chunk = [0u8; 65536];
    let mut rate: u32 = 44_100;   // CarLife 音乐默认 44100Hz（实测 43~44 块/秒 × 4096B）
    let mut channels: u8 = 2;
    // 视频通道诊断：CarLife 握手全过、音频 136/s 正常，但车机上一直是待机卡片
    // （2026-10-26 00:24 现场：没有任何 VideoFrame 产出）。这里把前几次读到的东西打出来，
    // 一眼就能分清「8240 上一个字节都没有」还是「有数据但帧头解析不出来」。
    let mut diag_reads: u32 = 0;
    #[cfg(test)]
    let mut video_probe = session::VideoPacketProbe::default();
    let mut parser = video::VideoParser::default();
    let mut access_units = 0u64;
    // **VIDEO 心跳**：官方 HU 实现（apollo-DuerOS `ConnectionEstablishHandler` 的
    // heartBeatTask）每秒往 VIDEO 通道发一条 `MSG_VIDEO_HEARTBEAT (0x20002)`；
    // 我们之前只读不发，CarLife 可能因此认为视频通道"没人要"，编码器空转不给帧。
    // 这里按官方节奏 1s 一条（空载荷，12 字节头）。
    let mut hb = tokio::time::interval(Duration::from_secs(1));
    hb.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut hb_sent: u64 = 0;
    let mut periodic = log_sampling::Repeats::default();
    let mut channel_stats = channel_diagnostics::Stats::default();
    let mut diagnostics = tokio::time::interval(Duration::from_secs(5));
    diagnostics.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        let socket_read = rd.read(&mut chunk);
        #[cfg(test)]
        let socket_read = video_probe.read_until_deadline(socket_read);
        let read = tokio::select! {
            _ = diagnostics.tick(), if !is_video => {
                if diagnostic_phase.streaming() {
                    periodic.periodic(channel_stats.report(label,"periodic",buf.len()), |line| emit_log(line));
                }
                continue;
            }
            _ = hb.tick(), if is_video => {
                let timestamp = if encryption.is_some() {
                    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as u32
                } else { now_ms() };
                let frame = build_media_frame(VIDEO_HEARTBEAT, timestamp, &[]);
                if let Err(error) = wr.write_all(&frame).await {
                    emit_log(&format!("CARLIFE: VIDEO heartbeat write failed after {hb_sent} successful sends: {error}"));
                    return;
                }
                hb_sent += 1;
                if log_sampling::heartbeat_sample(hb_sent) {
                    emit_log(&format!("CARLIFE: → VIDEO 心跳 #{hb_sent}（0x20002）"));
                }
                continue;
            }
            r = socket_read => r,
        };
        match read {
            Ok(0) => {
                if !is_video {emit_log(&channel_stats.report(label,"eof",buf.len()));}
                let _ = events.send(CarLifeEvent::ClientLeft { video: is_video });
                return;
            }
            Ok(n) => {
                #[cfg(test)]
                if is_video { video_probe.read(n); }
                if !is_video {channel_stats.rx_bytes+=n as u64;}
                if is_video && diag_reads < 5 {
                    diag_reads += 1;
                    let hex: String = chunk[..n.min(24)]
                        .iter()
                        .map(|b| format!("{b:02X}"))
                        .collect::<Vec<_>>()
                        .join(" ");
                    emit_log(&format!(
                        "CARLIFE: {label} 读到 {n}B（首次 {diag_reads} 次之一）：{hex}"
                    ));
                }
                buf.extend_from_slice(&chunk[..n])
            }
            Err(_) => {if !is_video {emit_log(&channel_stats.report(label,"ioError",buf.len()));}return;},
        }
        if buf.len() > 8 * 1024 * 1024 {
            if !is_video {channel_stats.parse_fail+=1;}
            buf.clear();
        }
        while let Some((service_type, ts_ms, payload, consumed)) = read_media_frame(&buf) {
            #[cfg(test)]
            let mut payload = payload;
            #[cfg(test)]
            if is_video {
                if let Some(state) = &encryption {
                    video_probe.packet(&buf, consumed, access_units, &state.lock());
                    if !video_probe.check_packet(service_type, &mut payload, &state.lock()) {
                        return;
                    }
                    if video_probe.inspect_next && !video_probe.recovery_enabled() {
                        video_probe.next_packet(&mut rd, &buf[consumed..], state).await;
                    }
                }
            }
            buf.drain(..consumed);
            if !is_video {
                channel_stats.frame(service_type);
                if service_type==0 {channel_stats.parse_fail+=1;}
            }
            if matches!(kind, MediaKind::Media) {
                let marker = match service_type {
                    MEDIA_INIT => Some("INIT"),
                    MEDIA_PAUSE => Some("PAUSE"),
                    MEDIA_RESUME => Some("RESUME"),
                    _ => None,
                };
                if let Some(marker) = marker {
                    channel_stats.awaiting_signal_after = Some(marker);
                    emit_log(&format!("CARWITH-MEDIA: 9240 rx={marker} service=0x{service_type:x} frame={}", channel_stats.frames));
                }
            }
            if encryption.as_ref().is_some_and(|s| {
                let s=s.lock();
                (service_type==VIDEO_DATA && !s.started) ||
                    (s.is_system_plain() && matches!(service_type,MEDIA_DATA|TTS_DATA) && !s.media_ready())
            }) {
                continue; // A landing-page frame is not proof of a completed CarWith handshake.
            }
            // Xiaomi mixed MEDIA_DATA remains PCM; video, TTS and audio INIT use AES-ECB.
            let raw_pcm = matches!(kind, MediaKind::Media) && service_type == MEDIA_DATA;
            let payload = if let Some(state) = encryption.as_ref().filter(|_| !raw_pcm) {
                match state.lock().decrypt(&payload) {
                    Ok(bytes) => {if !is_video {channel_stats.decrypt_ok+=1;}bytes},
                    Err(error) => {
                        #[cfg(test)]
                        if is_video && video_probe.drop_first(service_type, &error, access_units) { continue; }
                        if !is_video {channel_stats.decrypt_fail+=1;emit_log(&channel_stats.report(label,"decryptFailed",buf.len()));}
                        emit_log(&format!("CARWITH-HS: {label} decrypt failed: {error}")); return;
                    }
                }
            } else {if !is_video {channel_stats.plain+=1;}payload};
            match kind {
                MediaKind::Video => match service_type {
                    VIDEO_DATA => {
                        #[cfg(test)]
                        if video_probe.expired() { return; }
                        #[cfg(test)]
                        if video_probe.recovery_enabled() && !video_probe.feed_after_reset(encryption.as_ref().unwrap()) {
                            continue;
                        }
                        if !payload.is_empty() {
                            if encryption.is_some() {
                                let units = match parser.push_tagged(&payload) {
                                    Ok(units) => units,
                                    Err(error) => { emit_log(&format!("CARWITH-VIDEO: {error}")); return; }
                                };
                                #[cfg(test)]
                                video_probe.observe_parameters(&parser);
                                for unit in units {
                                    let video::AccessUnit { data, codec } = unit;
                                    #[cfg(test)]
                                    if !video_probe.accept_unit(&data, codec).await { continue; }
                                    access_units += 1;
                                    if diagnostic_phase.inspect_video_unit(access_units) {
                                        let types: Vec<_> = crate::screen::annexb_nals(&data).iter().map(|n|
                                            if codec == crate::video_format::VideoCodec::Hevc { (n[0]>>1)&63 } else { n[0]&31 }).collect();
                                        if access_units == 1 || types.iter().any(|kind| if codec == crate::video_format::VideoCodec::Hevc { matches!(kind,19..=21) } else { *kind==5 }) {
                                            emit_log(&format!("CARWITH-VIDEO: AU#{access_units} codec={} bytes={} nals={types:?} sourceUnixSeconds={ts_ms}; AirPlay PTS=monotonic",codec.as_str(),data.len()));
                                        }
                                    }
                                    let first_picture = first_sync.is_some() && video::is_configured_sync(codec, &data);
                                    let _ = events.send(CarLifeEvent::VideoFrameTagged {
                                        pts_us: crate::timing::monotonic_nanos() / 1000, data, codec,
                                    });
                                    if first_picture {
                                        if let Some(ready) = first_sync.take() { ready.send_replace(true); }
                                    }
                                }
                            } else {
                                let _ = events.send(CarLifeEvent::VideoFrame {
                                    pts_us: ts_ms as u64 * 1000, data: payload,
                                });
                            }
                        }
                    }
                    VIDEO_HEARTBEAT => { /* 流级心跳，忽略 */ }
                    other => {
                        if other != 0 {
                            emit_log(&format!("CARLIFE: VIDEO 未处理 0x{other:08x}"));
                        }
                    }
                },
                MediaKind::Media | MediaKind::Tts => {
                    let tts = matches!(kind, MediaKind::Tts);
                    match service_type {
                        MEDIA_INIT | TTS_INIT => {
                            let (r, c) = parse_audio_init(&payload);
                            if (8_000..=96_000).contains(&r) {
                                rate = r;
                            }
                            if (1..=2).contains(&c) {
                                channels = c as u8;
                            }
                            if events.send(CarLifeEvent::AudioInit {
                                tts,
                                rate,
                                channels,
                            }).is_err() {channel_stats.event_closed+=1;}
                            emit_log(&format!(
                                "CARLIFE: {} 音频初始化 {rate}Hz {channels}ch",
                                if tts { "TTS" } else { "MEDIA" }
                            ));
                        }
                        MEDIA_STOP | TTS_STOP => {
                            if events.send(CarLifeEvent::AudioStop { tts }).is_err() {channel_stats.event_closed+=1;}
                        }
                        MEDIA_PAUSE | MEDIA_RESUME if !tts => {} // Source notifications have no response.
                        MEDIA_DATA | TTS_DATA => {
                            if let Some(marker) = channel_stats.pcm(&payload) {
                                emit_log(&format!("CARWITH-MEDIA: 9240 rx=firstNonSilentPCM after={marker} frame={} bytes={} rate={rate} channels={channels}", channel_stats.frames, payload.len()));
                            }
                            if !payload.is_empty() {
                                // **剥掉 12 字节 PCM 包头**（2026-10-27 23:56 定案）：
                                // 反编译 `PCMPackageHead` 明确 mMediaHeadLen=12/mTTSHeadLen=12，
                                // 布局 [0..4]=dataLen(BE) [4..8]=timeStamp(BE) [8..12]=type(BE)。
                                // 现场取证 `00 00 00 00 00 00 00 00 01 00 00 00` 正是 type=1 的包头；
                                // 不剥掉就会被当样本播 → 每包一次"咔"（破音）。
                                {
                                    if events.send(CarLifeEvent::AudioData {
                                        received_at: std::time::Instant::now(),
                                        tts,
                                        rate,
                                        channels,
                                        data: payload.to_vec(),
                                    }).is_ok() {channel_stats.forwarded+=1;} else {channel_stats.event_closed+=1;}
                                }
                            } else {channel_stats.empty+=1;}
                        }
                        other => {
                            let _=other;
                            channel_stats.unknown+=1;
                        }
                    }
                }
            }
        }
    }
}

fn parse_encoder_info(bytes: &[u8]) -> (u32, u32) {
    let mut width = 0u32;
    let mut height = 0u32;
    let mut i = 0usize;
    while i < bytes.len() {
        let (tag, used) = read_varint(&bytes[i..]);
        let Some(tag) = tag else { break };
        i += used;
        let field = (tag >> 3) as u32;
        let wire = (tag & 0x07) as u8;
        match wire {
            0 => {
                let (v, used) = read_varint(&bytes[i..]);
                let Some(v) = v else { break };
                i += used;
                match field {
                    1 => width = v as u32,
                    2 => height = v as u32,
                    _ => {}
                }
            }
            2 => {
                let (len, used) = read_varint(&bytes[i..]);
                let Some(len) = len else { break };
                i += used;
                i += len as usize;
            }
            5 => i += 4,
            1 => i += 8,
            _ => break,
        }
        if i > bytes.len() {
            break;
        }
    }
    (width, height)
}

/// 解析音频初始化 protobuf：优先按 CarlifeAudioStart（3=sample_rate, 4=channels），
/// 兜底按旧版 1/2 字段布局。返回 (rate, channels)；未给则 (0,0)。
fn parse_audio_init(bytes: &[u8]) -> (u32, u32) {
    let mut fields: Vec<(u32, u64)> = Vec::new();
    let mut i = 0usize;
    while i < bytes.len() {
        let (tag, used) = read_varint(&bytes[i..]);
        let Some(tag) = tag else { break };
        i += used;
        let field = (tag >> 3) as u32;
        let wire = (tag & 0x07) as u8;
        match wire {
            0 => {
                let (v, used) = read_varint(&bytes[i..]);
                let Some(v) = v else { break };
                fields.push((field, v));
                i += used;
            }
            2 => {
                let (len, used) = read_varint(&bytes[i..]);
                let Some(len) = len else { break };
                i += used;
                i += len as usize;
            }
            5 => i += 4,
            1 => i += 8,
            _ => break,
        }
        if i > bytes.len() {
            break;
        }
    }
    let get = |f: u32| fields.iter().find(|(k, _)| *k == f).map(|(_, v)| *v as u32);
    // 官方 CarlifeMusicInitProto：1=sampleRate 2=channelConfig 3=sampleFormat(16)
    let rate = get(1).filter(|r| (8_000..=96_000).contains(r)).or_else(|| get(3).filter(|r| (8_000..=96_000).contains(r))).unwrap_or(44_100);
    let raw_ch = get(2).or_else(|| get(4)).unwrap_or(2);
    let channels = match raw_ch { 1 | 4 | 16 => 1, _ => 2 };
    (rate, channels)
}

fn read_varint(bytes: &[u8]) -> (Option<u64>, usize) {
    let mut value: u64 = 0;
    let mut shift = 0u32;
    for (index, &b) in bytes.iter().enumerate() {
        value |= ((b & 0x7F) as u64) << shift;
        if b & 0x80 == 0 {
            return (Some(value), index + 1);
        }
        shift += 7;
        if shift >= 64 {
            return (None, index + 1);
        }
    }
    (None, bytes.len())
}

// ---------------------------------------------------------------- CTRL 通道

#[cfg(test)]
mod input_lease_tests {
    use super::*;
    use tokio::net::TcpListener;
    use crate::airplay::{TouchGrant, TouchInput};

    struct Pending(Arc<Mutex<Vec<QueuedControl>>>);
    impl Pending {
        fn try_recv(&mut self) -> Result<QueuedControl,()> {
            let mut queue=self.0.lock();
            if queue.is_empty() {Err(())}else{Ok(queue.remove(0))}
        }
    }
    fn hub() -> (Arc<CarLifeHub>, Pending) {
        let input_queue=Arc::new(Mutex::new(Vec::new()));
        let pending=Pending(input_queue.clone());
        (Arc::new(CarLifeHub {
            system_voice: None,
            stop: Arc::new(DialStop::default()), input_queue, encoder_size: Arc::new(Mutex::new((1920, 720))),
            keyframe_request: Arc::new(Notify::new()),
        }), pending)
    }
    #[test]
    fn round_retirement_clears_all_accepted_input_without_a_forwarding_backlog() {
        let (hub,mut pending)=hub();
        for _ in 0..300 {hub.inject_touch(TOUCH_DOWN,10,20);}
        assert_eq!(hub.input_queue.lock().len(),256);
        hub.input_queue.lock().clear();
        assert!(pending.try_recv().is_err());
        hub.voice_start();
        assert_eq!(pending.try_recv().unwrap().bytes[4..8],CTRL_CAR_HARD_KEY_CODE.to_be_bytes());
        hub.stop.cancel();
        hub.inject_touch(TOUCH_DOWN,10,20);
        assert!(pending.try_recv().is_err());
    }

    async fn read_ctrl(peer: &mut TcpStream, state: &session::Shared) -> (u32, Vec<u8>) {
        tokio::time::timeout(Duration::from_secs(1), async {
            let mut header = [0; 8];
            peer.read_exact(&mut header).await.unwrap();
            let mut body = vec![0; u16::from_be_bytes([header[0], header[1]]) as usize];
            peer.read_exact(&mut body).await.unwrap();
            let kind = u32::from_be_bytes(header[4..8].try_into().unwrap());
            (kind, state.lock().decrypt(&body).unwrap())
        }).await.unwrap()
    }

    #[tokio::test]
    async fn detaching_media_releases_finger_once_without_destroying_source() {
        let (hub, mut rx) = hub();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let queue = Arc::new(Mutex::new(Vec::new()));
        let state = session::microphone_test_session();
        let writer = tokio::spawn(session::control(socket, queue.clone(), state.clone(), Arc::new(Notify::new())));
        let touch = |action, x, y| hub.inject_touch_report(&TouchInput { action, x, y, pointers: vec![], grant: None });
        touch(TOUCH_DOWN, 33, 44);
        touch(TOUCH_MOVE, 45, 67);
        queue.lock().extend(std::iter::from_fn(|| rx.try_recv().ok()));
        assert_eq!(read_ctrl(&mut peer, &state).await.0, 0x68002);
        assert_eq!(read_ctrl(&mut peer, &state).await.0, 0x68004);
        drop(crate::carwith_source::InputLease(hub.clone()));
        hub.release_touch();
        touch(TOUCH_UP, 0, 0);
        touch(TOUCH_DOWN, 55, 66);
        queue.lock().extend(std::iter::from_fn(|| rx.try_recv().ok()));
        assert_eq!(read_ctrl(&mut peer, &state).await, (0x68001, vec![8, 3, 16, 45, 24, 67]));
        assert_eq!(read_ctrl(&mut peer, &state).await.0, 0x68002);
        writer.abort();
    }

    #[tokio::test]
    async fn carwith_writer_discards_a_queued_click_and_cancels_a_sent_down_after_screen_return() {
        use crate::bplist::Value;
        let (hub, mut rx) = hub();
        let modes = Arc::new(Mutex::new(crate::modes::Modes::initial()));
        let stamp = || TouchGrant::new(&modes, modes.lock().screen_grant_generation());
        let handover = || {
            let mut state = modes.lock();
            let borrow = Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
                ("resourceID", Value::int(1)), ("transferType", Value::int(3)),
                ("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(100)),
            ])]))]);
            assert!(state.change(&borrow));
            assert!(!state.screen_owned());
            state.request_ui();
            assert!(state.screen_owned());
        };
        let touch = |action, x, grant: &TouchGrant| hub.inject_touch_report(&TouchInput {
            action, x, y: 100, pointers: vec![], grant: Some(grant.clone()),
        });
        let old = stamp();
        touch(TOUCH_DOWN, 20, &old);
        touch(TOUCH_UP, 20, &old);
        handover();
        let current = stamp();
        touch(TOUCH_DOWN, 40, &current);
        let queue = Arc::new(Mutex::new(std::iter::from_fn(|| rx.try_recv().ok()).collect()));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let state = session::microphone_test_session();
        let writer = tokio::spawn(session::control(socket, queue.clone(), state.clone(), Arc::new(Notify::new())));
        assert_eq!(read_ctrl(&mut peer, &state).await, (0x68002, vec![8, 40, 16, 100]));

        touch(TOUCH_UP, 40, &current);
        handover();
        let next = stamp();
        touch(TOUCH_DOWN, 60, &next);
        touch(TOUCH_UP, 60, &next);
        queue.lock().extend(std::iter::from_fn(|| rx.try_recv().ok()));
        assert_eq!(read_ctrl(&mut peer, &state).await, (0x68001, vec![8, 3, 16, 40, 24, 100]));
        assert_eq!(read_ctrl(&mut peer, &state).await, (0x68002, vec![8, 60, 16, 100]));
        assert_eq!(read_ctrl(&mut peer, &state).await, (0x68003, vec![8, 60, 16, 100]));
        writer.abort();
    }
}

// Connection failures are throttled while CarWith has not opened its channels.
struct DialLogThrottle(std::time::Instant);

impl DialLogThrottle {
    fn new() -> Self {
        // 先减掉窗口，保证第一条失败一定打出来
        Self(std::time::Instant::now() - Duration::from_secs(10))
    }

    fn ready(&mut self) -> bool {
        if self.0.elapsed() >= Duration::from_secs(5) {
            self.0 = std::time::Instant::now();
            true
        } else {
            false
        }
    }
}

/// 拨 MD 的某个通道端口（主动连接模式用）。3s 超时，失败交给调用方重试。
async fn dial(host: &str, port: u16) -> Result<TcpStream, std::io::Error> {
    match tokio::time::timeout(Duration::from_secs(3), TcpStream::connect((host, port))).await {
        Ok(Ok(stream)) => {
            let _ = stream.set_nodelay(true);
            Ok(stream)
        }
        Ok(Err(e)) => Err(e),
        Err(_) => Err(std::io::Error::new(
            std::io::ErrorKind::TimedOut,
            "连接超时（MD 未在监听？）",
        )),
    }
}

/// VR playback remains available when car microphone capture is disabled.
async fn drain_stream(mut stream: TcpStream, state:session::Shared, events:mpsc::UnboundedSender<CarLifeEvent>,
                      diagnostic_phase: Arc<DiagnosticPhase>) {
    let mut buf = [0u8; 2048];
    let mut receiver=voice_audio::Receiver::default();
    let mut stats=channel_diagnostics::Stats::default();
    let mut periodic=log_sampling::Repeats::default();
    let mut diagnostics=tokio::time::interval(Duration::from_secs(5));
    diagnostics.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            _=diagnostics.tick()=>{if diagnostic_phase.streaming() {periodic.periodic(stats.report("VR/noCapture","periodic",receiver.pending()), |line| emit_log(line));}},
            received=stream.read(&mut buf)=>match received {
                Ok(0)=>{emit_log(&stats.report("VR/noCapture","eof",receiver.pending()));return;},
                Ok(n)=>receiver.push(&buf[..n],&mut stats,&state,&events),
                Err(_)=>{emit_log(&stats.report("VR/noCapture","ioError",receiver.pending()));return;},
            }
        }
    }
}


#[cfg(test)]
mod voice_downlink_tests {
    use super::*;
    #[tokio::test]
    async fn vr_without_microphone_forwards_encrypted_init_pcm_and_stop() { exercise_downlink(false).await; }
    #[tokio::test]
    async fn vr_with_microphone_forwards_reply_without_changing_capture() { exercise_downlink(true).await; }
    async fn exercise_downlink(capture:bool) {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let(mut peer,_)=listener.accept().await.unwrap();
        let state=session::microphone_test_session();let(tx,mut rx)=mpsc::unbounded_channel();
        let control=Arc::new(crate::mic_probe::ProbeControl::for_carwith());
        if capture { control.arm_test(); control.request(true); }
        let generation=control.generation();
        let worker=if capture { tokio::spawn(microphone::run(client,state.clone(),control.clone(),Arc::new(Notify::new()),tx,Arc::default())) }
            else {tokio::spawn(drain_stream(client,state.clone(),tx,Arc::default()))};
        let init=state.lock().encrypt(&[8,128,125,16,1,24,16]).unwrap();
        let pcm=state.lock().encrypt(&[1,0,254,255]).unwrap();
        let wire=[build_media_frame(0x50002,0,&init),build_media_frame(0x50003,0,&pcm),build_media_frame(0x50004,0,&[])].concat();
        for bytes in wire.chunks(7) {peer.write_all(bytes).await.unwrap();}
        let first=tokio::time::timeout(Duration::from_millis(250),rx.recv()).await;
        assert!(matches!(first,Ok(Some(CarLifeEvent::VoiceAudioInit{rate:16000,channels:1}))),"assistant INIT must reach independent voice output: {first:?}");
        assert!(matches!(rx.recv().await,Some(CarLifeEvent::VoiceAudioData{rate:16000,channels:1,data}) if data==[1,0,254,255]));
        assert!(matches!(rx.recv().await,Some(CarLifeEvent::VoiceAudioStop)));
        assert_eq!(control.generation(),generation,"downstream audio cannot change microphone capture");
        worker.abort();let _=worker.await;
    }
}

#[cfg(test)]
mod media_tts_downlink_tests {
    use super::*;

    #[tokio::test]
    async fn media_decrypts_init_but_preserves_raw_pcm_after_exactly_one_outer_header() {
        exercise_audio(MediaKind::Media, false, 48000, 2,
            &[8,128,247,2,16,2,24,16], 0x30001, 0x30006, 0x30002).await;
    }

    #[tokio::test]
    async fn tts_decrypts_init_and_pcm_preserves_format_and_accepts_empty_stop() {
        exercise_audio(MediaKind::Tts, true, 16000, 1,
            &[8,128,125,16,1,24,16], 0x40001, 0x40003, 0x40002).await;
    }

    async fn exercise_audio(kind:MediaKind, tts:bool, rate:u32, channels:u8,
        init:&[u8], init_service:u32, data_service:u32, stop_service:u32) {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let(mut peer,_)=listener.accept().await.unwrap();peer.set_nodelay(true).unwrap();
        let state=session::microphone_test_session();let(tx,mut rx)=mpsc::unbounded_channel();
        let worker=tokio::spawn(handle_media_client(client,tx,kind,if tts {"TTS"}else{"MEDIA"},Some(state.clone()),Arc::default()));
        let encrypted_init=state.lock().encrypt(init).unwrap();
        // The first twelve PCM bytes resemble an outer header. They remain samples;
        // a second header strip or incorrect MEDIA decryption must fail this assertion.
        let pcm=vec![0,0,0,4,0,0,0,7,0,4,0,3,1,0,254,255,2,0,253,255];
        let body=if tts {state.lock().encrypt(&pcm).unwrap()} else {pcm.clone()};
        let first=build_media_frame(init_service,0,&encrypted_init);
        peer.write_all(&first[..5]).await.unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(20),rx.recv()).await.is_err(),"partial header cannot yield an audio event");
        // Finish a fragmented INIT and coalesce two DATA frames plus empty STOP.
        let rest=[first[5..].to_vec(),build_media_frame(data_service,1,&body),
            build_media_frame(data_service,2,&body),build_media_frame(stop_service,3,&[])].concat();
        for bytes in rest.chunks(17) {peer.write_all(bytes).await.unwrap();tokio::task::yield_now().await;}
        let event=tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap();
        assert!(matches!(event,Some(CarLifeEvent::AudioInit{tts:t,rate:r,channels:c}) if (t,r,c)==(tts,rate,channels)),"INIT must preserve stream and source format: {event:?}");
        for _ in 0..2 {
            let event=tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap();
            assert!(matches!(event,Some(CarLifeEvent::AudioData{tts:t,rate:r,channels:c,data,..}) if (t,r,c)==(tts,rate,channels) && data==pcm),"PCM must stay byte-exact and on its own stream");
        }
        let event=tokio::time::timeout(Duration::from_secs(1),rx.recv()).await.unwrap();
        assert!(matches!(event,Some(CarLifeEvent::AudioStop{tts:t}) if t==tts),"STOP cannot affect another stream: {event:?}");
        assert!(rx.try_recv().is_err(),"headers must not become extra audio events");
        worker.abort();let _=worker.await;
    }
}
