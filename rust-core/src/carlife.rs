//! Local CarWith/CarLife transport. The active Android source uses seven loopback
//! channels, fresh per-round state and the authenticated CONTENT_ENCRYPTION
//! handshake in session.rs. Legacy listener helpers below are not the active source.
//! CMD/CTRL/DATA use 8-byte headers; VIDEO/MEDIA/TTS/VR use 12-byte headers.
//! CarWith DATA is port 9440. Do not mix generic CarLife assumptions with this path.

mod session;
mod video;

use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Notify};

use crate::engine::CarLifeEvent;
use crate::{emit_log, emit_state};

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
const CMD_VIDEO_ENCODER_PAUSE: u32 = 0x0001_800A;
/// HU→MD：让手机 CarLife 进入 Home（投屏）界面。**没有这条它就一直停在自己的落地页**。
const CMD_LAUNCH_MODE_NORMAL: u32 = 0x0001_801D;
/// HU→MD：进入 Phone 界面。
const CMD_LAUNCH_MODE_PHONE: u32 = 0x0001_801E;
/// HU→MD：进入 Map 界面。
const CMD_LAUNCH_MODE_MAP: u32 = 0x0001_801F;
/// HU→MD：进入 Music 界面。
const CMD_LAUNCH_MODE_MUSIC: u32 = 0x0001_8020;
/// HU→MD：把 CarLife 切回前台（AOA 场景用）。
const CMD_HU_GO_TO_FOREGROUND: u32 = 0x0001_8025;
const CMD_SCREEN_ON: u32 = 0x0001_0018;
const CMD_FOREGROUND: u32 = 0x0001_001B;
const CMD_MD_MODULE_STATUS: u32 = 0x0001_0026;
const CMD_HU_STATISTIC_INFO: u32 = 0x0001_8027;
/// 模块级控制（文档 §10.2.2）：CarlifeModuleStatusList{cnt, moduleStatus{moduleID,statusID}}
const CMD_HU_MODULE_CONTROL: u32 = 0x0001_8028;
const CMD_MD_CAR_DATA_SUBSCRIBE: u32 = 0x0001_0031;
const CMD_HU_CAR_DATA_SUBSCRIBE_DONE: u32 = 0x0001_8032;
const CMD_HU_AUTHEN_REQUEST: u32 = 0x0001_8048;
const CMD_MD_AUTHEN_RESPONSE: u32 = 0x0001_0049;
const CMD_HU_AUTHEN_RESULT: u32 = 0x0001_804A;
const CMD_MD_AUTHEN_RESULT: u32 = 0x0001_004B;
const CMD_MD_FEATURE_CONFIG_REQUEST: u32 = 0x0001_0051;
const CMD_HU_FEATURE_CONFIG_RESPONSE: u32 = 0x0001_8052;
const CMD_MD_EXIT: u32 = 0x0001_0059;
// ---- CONTENT_ENCRYPTION（CarWith 需要；公版 CarLife 没有这几步）----
const CMD_MD_RSA_KEY_REQUEST: u32 = 0x0001_006A;
const CMD_MD_AES_SEED: u32 = 0x0001_006C;
const CMD_MD_ENCRYPT_READY: u32 = 0x0001_006E;
const CMD_HU_MD_AUTH_RESULT_ACK: u32 = 0x0001_804C;
const CMD_HU_RSA_PUBLIC_KEY: u32 = 0x0001_806B;
const CMD_HU_AES_ACK: u32 = 0x0001_806D;

/// 加密握手状态（与 mcarplay 的 CarLifeSeedExchange/CarLifeStateMachine 对齐）。
#[derive(Default)]
struct ClEncState {
    key: Option<rsa::RsaPrivateKey>,
    seed: Option<Vec<u8>>,
    rsa_sent: bool,
    aes_ack_sent: bool,
    encrypted: bool,
    md_result_acked: bool,
}

static CL_ENC: once_cell::sync::Lazy<parking_lot::Mutex<ClEncState>> =
    once_cell::sync::Lazy::new(|| parking_lot::Mutex::new(ClEncState::default()));

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

/// 往 protobuf 输出追加一个 length-delimited 字段（varint 长度）。
fn put_len_delimited(out: &mut Vec<u8>, field: u32, bytes: &[u8]) {
    let mut v = (field as u64) << 3 | 2;
    loop {
        let mut b = (v & 0x7f) as u8;
        v >>= 7;
        if v != 0 { b |= 0x80; }
        out.push(b);
        if v == 0 { break; }
    }
    let mut len = bytes.len() as u64;
    loop {
        let mut b = (len & 0x7f) as u8;
        len >>= 7;
        if len != 0 { b |= 0x80; }
        out.push(b);
        if len == 0 { break; }
    }
    out.extend_from_slice(bytes);
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
const MEDIA_DATA: u32 = 0x0003_0006;
const TTS_INIT: u32 = 0x0004_0001;
const TTS_STOP: u32 = 0x0004_0002;
const TTS_DATA: u32 = 0x0004_0003;

// CTRL 通道（8 字节头）
const CTRL_TOUCH_ACTION: u32 = 0x0006_8001;
const CTRL_HARD_KEY: u32 = 0x0006_8008;

/// 触摸动作（CarLife 语义：0=DOWN / 1=UP / 2=MOVE，**不是** Android 的 1/2/3）。
pub const TOUCH_DOWN: u32 = 0;
pub const TOUCH_UP: u32 = 1;
pub const TOUCH_MOVE: u32 = 2;

/// 协议版本（CarLifeWirelessBox 默认 v3.2，实测 CarWith 接受）。
    const PROTOCOL_MAJOR: u32 = 4;   // mcarplay 实测：CarWith 用 4.1
    const PROTOCOL_MINOR: u32 = 1;

// ---------------------------------------------------------------- 硬按键码（CarLife）
//
// `CarlifeCarHardKeyCode { int32 keycode = 1; }` 的 keycode 取值。
// ⚠ 取值来自 CarLife 协议文档/社区逆向，**尚未在真车上逐条验证**；
// 与旧代码相比关键修正：BACK=0x11（17），旧值 0x02 其实是电话键。
pub const KEYCODE_HOME: u32 = 0x01;
pub const KEYCODE_PHONE_CALL: u32 = 0x02;
pub const KEYCODE_PHONE_END: u32 = 0x03;
pub const KEYCODE_VR_START: u32 = 0x09;
pub const KEYCODE_VR_STOP: u32 = 0x0A;
pub const KEYCODE_MEDIA_NEXT: u32 = 0x0E;
pub const KEYCODE_MEDIA_PREV: u32 = 0x0F;
pub const KEYCODE_PLAY_PAUSE: u32 = 0x10;
pub const KEYCODE_BACK: u32 = 0x11;
pub const KEYCODE_MOVE_UP: u32 = 0x19;
pub const KEYCODE_MOVE_DOWN: u32 = 0x1A;
pub const KEYCODE_MOVE_LEFT: u32 = 0x1B;
pub const KEYCODE_MOVE_RIGHT: u32 = 0x1C;
pub const KEYCODE_OK: u32 = 0x1D;

#[derive(Clone)]
pub struct CarLifeConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// 特性配置里的 `voice`：1 = 车机提供语音/麦克风（需要 HU 推 VR 麦克风数据），
    /// 0 = 车机不提供语音 → MD 回落到**手机自己的麦克风**。
    pub voice: u32,
    /// `HU_INFO` 里 required 的 `btaddress`（车机蓝牙 MAC）。
    pub bt_address: String,
    /// `HU_INFO` 里 required 的 `btname`（车机蓝牙名）。
    pub bt_name: String,
}

/// CarLife 网关：监听 6 条标准通道，产出结构化事件；接受外部触摸/按键注入。
pub struct CarLifeHub {
    owned_tasks: Vec<tokio::task::JoinHandle<()>>,
    touch_state: Arc<Mutex<session::TouchGesture>>,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    touch_tx: mpsc::UnboundedSender<Vec<u8>>,
    cfg: CarLifeConfig,
    /// 当前编码分辨率共享单元（与 AirPlay 协商/触摸换算共用）。
    encoder_size: Arc<Mutex<(u32, u32)>>,
    /// VR 通道麦克风帧（HU → MD）
    mic_tx: mpsc::UnboundedSender<Vec<u8>>,
    /// 麦克风帧时间戳基准
    mic_start: std::time::Instant,
    keyframe_request: Option<Arc<Notify>>,
}

impl CarLifeHub {
    pub fn start(
        cfg: CarLifeConfig,
        stop: Arc<Notify>,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        let (events, event_rx) = mpsc::unbounded_channel();
        let (touch_tx, touch_rx) = mpsc::unbounded_channel();
        let (mic_tx, mut mic_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let voice = cfg.voice;
        // 初始值取设置；AirPlay 的 /info 报到车机真实尺寸后会覆盖它，
        // 手机在 INIT_DONE 里回报实际编码尺寸后再次覆盖。
        let encoder_size: Arc<Mutex<(u32, u32)>> =
            Arc::new(Mutex::new((cfg.width.max(2), cfg.height.max(2))));

        // ---- CMD 7240：14 步握手 ----
        let cmd_listener =
            bind_tcp(PORT_CMD).map_err(|e| format!("CarLife CMD 7240 绑定失败: {e}"))?;
        spawn_single_client_server(cmd_listener, stop.clone(), "CMD", {
            let events = events.clone();
            let fps = cfg.fps;
            let bt_address = cfg.bt_address.clone();
            let bt_name = cfg.bt_name.clone();
            let size = encoder_size.clone();
            let stop_c = stop.clone();
            move |stream| {
                handle_cmd_client(
                    stream,
                    events.clone(),
                    size.clone(),
                    fps,
                    voice,
                    bt_address.clone(),
                    bt_name.clone(),
                    stop_c.clone(),
                )
            }
        });

        // ---- VIDEO 8240 ----
        spawn_media_channel(PORT_VIDEO, "VIDEO", events.clone(), stop.clone(), MediaKind::Video);
        // ---- MEDIA 9240 ----
        spawn_media_channel(PORT_MEDIA, "MEDIA", events.clone(), stop.clone(), MediaKind::Media);
        // ---- TTS 9241 ----
        spawn_media_channel(PORT_TTS, "TTS", events.clone(), stop.clone(), MediaKind::Tts);
        // ---- VR 9242：只读保活，不消费 ----
        spawn_vr_listener(PORT_VR, "VR", mic_rx, stop.clone());

        // ---- CTRL 9340：触摸/硬按键出向（8 字节 CMD 头）----
        spawn_ctrl_channel(PORT_CTRL, touch_rx, stop.clone());

        emit_state("carlife_waiting", "CarLife 网关就绪，等待本机 app 接入");
        emit_log(&format!(
            "CARLIFE: 回环网关就绪 127.0.0.1:{} CMD / {} VIDEO / {} MEDIA / {} TTS / {} VR / {} CTRL",
            PORT_CMD, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VR, PORT_CTRL
        ));

        // ---- UDP 7999 发现广播（尽力而为，见函数注释）----
        spawn_discovery_beacon(stop.clone());

        Ok((
            Self {
                owned_tasks: Vec::new(),
                touch_state: Arc::new(Mutex::new(session::TouchGesture::default())),
                events,
                touch_tx,
                cfg,
                encoder_size,
                mic_tx,
                mic_start: std::time::Instant::now(),
                keyframe_request: None,
            },
            event_rx,
        ))
    }

    /// 编码分辨率共享单元的句柄（AirPlay 协商据此请求编码尺寸，触摸换算读它）。
    pub fn encoder_size_handle(&self) -> Arc<Mutex<(u32, u32)>> {
        self.encoder_size.clone()
    }

    /// **主动连接模式**（盒子角色）：MD（手机上的 CarLife/CarWith）在标准端口上监听，
    /// 由 HU 侧逐个拨过去。
    ///
    /// 为什么需要它：小米 CarLife 走「车辆热点 → 开始连接」时，方向是反的——
    /// 现场 2026-10-23 22:56 实测，点「开始连接」后手机 `com.baidu.carlife.xiaomi`
    /// （uid 10345）开始 **LISTEN** `7240/8240/9240/9241/9242/9340`，等车机连入；
    /// 这与参考实现 `CarLifeWirelessBox` 的 `HuRole.connect(phoneIp, port)` 一致。
    /// Android CarWith 同机也使用本方法主动连接 127.0.0.1；Self::start 仅保留旧监听模式。
    pub fn connect(
        cfg: CarLifeConfig, md_host: String, stop: Arc<Notify>, requested: Option<Arc<Mutex<(u32,u32)>>>,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        let keyframe_request = Arc::new(Notify::new());
        let reset_request = keyframe_request.clone();
        let (events, event_rx) = mpsc::unbounded_channel();
        let (touch_tx, mut touch_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let (mic_tx, mic_rx) = mpsc::unbounded_channel::<Vec<u8>>();
        let encoder_size = Arc::new(Mutex::new((cfg.width.max(2),cfg.height.max(2))));
        let requested = requested.unwrap_or_else(|| encoder_size.clone());
        let touch_state=Arc::new(Mutex::new(session::TouchGesture::default()));
        let round_touch=touch_state.clone();
        let queue = Arc::new(Mutex::new(Vec::<Vec<u8>>::new()));
        let mic_rx = Arc::new(tokio::sync::Mutex::new(mic_rx));
        let queue_fill=queue.clone(); let stop_fill=stop.clone();
        let fill_task = tokio::spawn(async move {
            loop { tokio::select! {
                _=stop_fill.notified()=>return,
                value=touch_rx.recv()=>match value {
                    Some(value)=>{ let mut q=queue_fill.lock(); if q.len()>=256 {q.remove(0);} q.push(value); }
                    None=>return,
                }
            }}
        });
        let events_task=events.clone(); let size=encoder_size.clone(); let config=cfg.clone();
        let connection_task = tokio::spawn(async move {
            let ports=[PORT_CMD,PORT_VIDEO,PORT_MEDIA,PORT_TTS,PORT_VR,PORT_CTRL,PORT_DATA];
            let mut throttle=DialLogThrottle::new();
            loop {
                // Own all seven sockets together. A failed/closed CMD invalidates the whole round.
                let mut sockets=Vec::new();
                for port in ports {
                    let result=tokio::select! { _=stop.notified()=>return, result=dial(&md_host,port)=>result };
                    match result {
                        Ok(socket)=>sockets.push(socket),
                        Err(error)=>{ if throttle.ready() {emit_log(&format!("CARWITH-HS: waiting for all seven channels; port={port} error={error}"));} break; }
                    }
                }
                if sockets.len()!=ports.len() {
                    drop(sockets);
                    tokio::select! { _=stop.notified()=>return, _=tokio::time::sleep(Duration::from_secs(1))=>{} }
                    continue;
                }
                emit_log("CARWITH-HS: all seven channels connected; start fresh 4.1 handshake");
                queue.lock().clear();
                *round_touch.lock()=session::TouchGesture::default();
                let _ = events_task.send(CarLifeEvent::SourceReset);
                let state=session::Shared::new(Mutex::new(session::Session::default()));
                let mut sockets=sockets.into_iter();
                let cmd=sockets.next().unwrap();
                let mut children=tokio::task::JoinSet::new();
                for (stream,kind,label) in [
                    (sockets.next().unwrap(),MediaKind::Video,"VIDEO"),
                    (sockets.next().unwrap(),MediaKind::Media,"MEDIA"),
                    (sockets.next().unwrap(),MediaKind::Tts,"TTS"),
                ] {
                    children.spawn(handle_media_client(stream,events_task.clone(),kind,label,Some(state.clone())));
                }
                let vr=sockets.next().unwrap(); let mic=mic_rx.clone(); let stop_vr=stop.clone();
                children.spawn(async move { let mut rx=mic.lock().await; vr_loop(vr,&mut *rx,"VR",stop_vr).await; });
                let ctrl=sockets.next().unwrap(); let q=queue.clone(); let cipher=state.clone(); let stop_ctrl=stop.clone();
                children.spawn(async move { session::control(ctrl,q,cipher,stop_ctrl).await; });
                children.spawn(drain_stream(sockets.next().unwrap()));
                let outcome=tokio::select! {
                    _=stop.notified()=>{children.abort_all(); return;},
                    _=children.join_next()=>Err("CarWith media/control channel closed".into()),
                    result=session::run(cmd,events_task.clone(),size.clone(),requested.clone(),config.clone(),state.clone(),reset_request.clone())=>result,
                };
                children.abort_all(); while children.join_next().await.is_some() {}
                queue.lock().clear();
                emit_log(&format!("CARWITH-HS: round closed: {}",outcome.err().unwrap_or_else(||"ended".into())));
                tokio::select! { _=stop.notified()=>return, _=tokio::time::sleep(Duration::from_secs(1))=>{} }
            }
        });
        Ok((Self {owned_tasks:vec![fill_task,connection_task],touch_state,events,touch_tx,cfg,encoder_size,mic_tx,mic_start:std::time::Instant::now(),keyframe_request:Some(keyframe_request)},event_rx))
    }

    pub fn request_keyframe(&self) {
        if let Some(request) = &self.keyframe_request { request.notify_one(); }
    }

    /// 外部（AirPlay 事件通道）注入触摸 → CarLife CTRL 通道 TOUCH_ACTION。
    pub fn inject_touch(&self, action: u32, x: u32, y: u32) {
        let size=*self.encoder_size.lock();
        if let Some(frame)=self.touch_state.lock().accept(action,x,y,size) {
            let _=self.touch_tx.send(frame);
        }
    }

    /// 麦克风 PCM → CarLife VR 通道（HU → MD，`VR_MIC_DATA 0x00058001`）。
    ///
    /// PCM 为 **16kHz 单声道 16-bit 小端**（ASR 通用格式；CarLife 的 MEDIA 侧
    /// 我们按小端解析，麦克风沿用同一约定）。语音助手靠这条数据做识别，
    /// 没有它手机上会报「麦克风有问题」。
    pub fn inject_mic(&self, pcm: &[i16]) {
        if pcm.is_empty() {
            return;
        }
        let mut payload = Vec::with_capacity(pcm.len() * 2);
        for s in pcm {
            payload.extend_from_slice(&s.to_le_bytes());
        }
        let ts = self.mic_start.elapsed().as_millis() as u32;
        let _ = self.mic_tx.send(build_media_frame(VR_MIC_DATA, ts, &payload));
    }
    /// iDrive 旋钮/媒体键 → CarLife CTRL 通道 HARD_KEY (0x00068008)。
    ///
    /// 载荷是 protobuf `CarlifeCarHardKeyCode { int32 keycode = 1; }`（tag 0x08 + varint），
    /// **不是**裸 `[u32 BE keycode]`——和触摸一样，CarLife 按 protobuf 解，裸结构会被丢弃
    /// （触摸那边踩过同样的坑，见 `inject_touch`）。
    pub fn inject_hard_key(&self, code: u32) {
        let mut payload = Vec::with_capacity(4);
        proto::varint(&mut payload, 1, code as u64);
        emit_log(&format!(
            "CTRL: 注入硬按键 keycode={code}（0x{code:02X}）protobuf={:02X?}",
            payload
        ));
        let _ = self
            .touch_tx
            .send(build_cmd_frame(CTRL_HARD_KEY, &payload));
    }

    pub fn resolution(&self) -> (u32, u32) {
        (self.cfg.width, self.cfg.height)
    }
}

// ---------------------------------------------------------------- 帧编解码

/// CarLife 触摸载荷 protobuf（`CarlifeTouchAction`）：
/// `field1=action`、`field2=x`、`field3=y`、`field4=pointerx`、`field5=pointery`，
/// 全部 `int32` → 线格式是 varint，tag 依次 0x08/0x10/0x18/0x20/0x28。
///
/// 依据：参考实现 `app/src/main/proto/carlife_ctrl.proto` 与
/// `MainActivity.forwardTouchEvent`（那里明确写着 CarWith 期望 protobuf、不是原始字节）。
impl Drop for CarLifeHub {
    fn drop(&mut self) {
        for task in &self.owned_tasks { task.abort(); }
    }
}

fn touch_proto(action: u32, x: u32, y: u32) -> Vec<u8> {
    fn varint(mut v: u64, out: &mut Vec<u8>) {
        while v >= 0x80 {
            out.push((v as u8) | 0x80);
            v >>= 7;
        }
        out.push(v as u8);
    }
    let mut out = Vec::with_capacity(16);
    out.push(0x08);
    varint(action as u64, &mut out);
    out.push(0x10);
    varint(x as u64, &mut out);
    out.push(0x18);
    varint(y as u64, &mut out);
    out.push(0x20); // pointerx = 0（单指）
    varint(0, &mut out);
    out.push(0x28); // pointery = 0
    varint(0, &mut out);
    out
}

/// VR 通道「麦克风数据」（HU → MD）：`[u32 BE len][u32 BE ts_ms][u32 BE type][PCM]`。
/// 车道侧把车机麦克风 PCM 推给手机，CarLife 的语音助手用它做识别；
/// 协议表见 CarLifeWirelessBox `docs/通讯协议分析报告.md`（VR: HU → MD 语音识别）。
const VR_MIC_DATA: u32 = 0x0005_8001;

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
    if buf.len() < 8 {
        return None;
    }
    let len = u16::from_be_bytes([buf[0], buf[1]]) as usize;
    let service_type = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let total = 8 + len;
    if buf.len() < total {
        return None;
    }
    Some((service_type, buf[8..total].to_vec(), total))
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

// ---------------------------------------------------------------- CMD 握手状态机

/// 握手进度（每个 HU→MD 消息只发一次，避免重复消息扰动车机）。
#[derive(Default)]
struct Handshake {
    statistic_sent: bool,
    hu_info_sent: bool,
    feature_sent: bool,
    authen_req_sent: bool,
    authen_result_sent: bool,
    encoder_init_sent: bool,
    encoder_start_sent: bool,
}

impl Handshake {
    fn phase_name(&self) -> &'static str {
        if self.encoder_start_sent {
            "Phase 7: 已开始推流"
        } else if self.encoder_init_sent {
            "Phase 6: 等编码器初始化完成"
        } else if self.authen_result_sent {
            "Phase 5-6: 等鉴权结果"
        } else if self.authen_req_sent {
            "Phase 4: 等鉴权响应"
        } else if self.feature_sent {
            "Phase 3.5: 等特性配置后的鉴权"
        } else if self.hu_info_sent {
            "Phase 3: 等特性配置请求"
        } else if self.statistic_sent {
            "Phase 2: 等 MD_INFO"
        } else {
            "Phase 1: 等版本匹配"
        }
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

/// CMD 通道：14 步握手状态机（严格按 HU 侧事件驱动）。
async fn handle_cmd_client(
    stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    encoder_size: Arc<Mutex<(u32, u32)>>,
    fps: u32,
    voice: u32,
    bt_address: String,
    bt_name: String,
    stop: Arc<Notify>,
) {
    let (mut rd, mut wr) = stream.into_split();
    emit_log("CARLIFE: CMD handler 开始（准备握手）");
    // 实测（2026-11-03）：CarWith 的 7240 只开约 1~2 秒，等七路齐备会错过窗口。
    // 因此只在必要时极短等待 CMD，然后立刻握手；其余通道由各自的重连循环跟进。
    // 注：不要用 /proc/net/tcp 判断监听（untrusted_app 被 SELinux 拒绝，恒为 false）。
    // 既然是"我们刚 connect 成功"才走到这里，直接握手即可。
    let mut hs = Handshake::default();
    let mut start_retries: u32 = 0;
    // 本会话重新计"首帧"：上一轮的 true 不能让这一轮的补发逻辑失效
    VIDEO_FIRST_FRAME_RECEIVED.store(false, std::sync::atomic::Ordering::Relaxed);
    SESSION_STARTED.store(false, std::sync::atomic::Ordering::Relaxed);

    // Phase 1：HU 主动发协议版本
    if !send_cmd(&mut wr, CMD_HU_PROTOCOL_VERSION, &proto_version_payload()).await {
        return;
    }
    emit_log(&format!(
        "CARLIFE: [Phase 1] → HU_PROTOCOL_VERSION ({PROTOCOL_MAJOR}.{PROTOCOL_MINOR})"
    ));

    let mut buf: Vec<u8> = Vec::with_capacity(4096);
    let mut chunk = [0u8; 2048];
    // 1.5s tick：握手完成后若 8240 还没出帧，用它补发 VIDEO_ENCODER_START。
    // （5s 太粗：CarLife 在前台就绪后等 START，我们晚 5s 才催，用户看到的是"黑屏几秒"）
    let mut tick = tokio::time::interval(Duration::from_millis(1500));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        let n = tokio::select! {
            _ = stop.notified() => {
                // 收尾：先让 MD 暂停编码（它据此走 onDisconnect），再发 FIN 关写半部，
                // 避免留下"半开"的 CMD 连接、下次连进去不回 Phase 1。
                if hs.encoder_start_sent {
                    let _ = send_cmd(&mut wr, CMD_VIDEO_ENCODER_PAUSE, &[]).await;
                }
                let _ = tokio::io::AsyncWriteExt::shutdown(&mut wr).await;
                emit_log("CARLIFE: 停止信号——已发 VIDEO_ENCODER_PAUSE 并关闭 CMD 连接");
                return;
            }
            _ = tick.tick() => {
                if !hs.encoder_start_sent {
                    emit_log(&format!("CARLIFE: 握手进行中（{}）", hs.phase_name()));
                } else if !VIDEO_FIRST_FRAME_RECEIVED.load(std::sync::atomic::Ordering::Relaxed)
                    && start_retries < 12
                {
                    // START 可能发在"CarLife 还在后台、Surface 未就绪"的时刻被吞掉，
                    // 首帧到达前每 1.5s 补一次（最多 12 次 ≈ 18s）。
                    start_retries += 1;
                    // 官方文档（V2.1.1 §10.2.2）：`MSG_CMD_LAUNCH_MODE_NORMAL(0x1801D)`
                    // 才是"让手机 CarLife 进入 Home 投屏界面"的命令。缺了它，手机 CarLife
                    // 永远停在自己的落地页，编码器空转 → 车机侧 Qin:0（我们踩了一整天）。
                    // 不再发 LAUNCH_MODE_NORMAL：CarLife 收到它会 ProviderManager.openHome()，
                    // 把 CarLife 自己的首页/设置页拉到前台（我们冒充宿主时不需要它，见 §26）。
                    let _ = send_cmd(&mut wr, CMD_VIDEO_ENCODER_START, &[]).await;
                    emit_log(&format!(
                        "CARLIFE: 首帧视频未到——第 {start_retries} 次补发 LAUNCH_MODE_NORMAL + VIDEO_ENCODER_START"
                    ));
                    // 实验 A（2026-10-27）：除了 LAUNCH_MODE，再补两条"把投屏拉起来"的命令：
                    //   ① `MSG_CMD_GO_TO_FOREGROUND(0x18025)`：把 CarLife 切回前台（AOA 场景用，无线场景也归档在文档里）
                    //   ② `MSG_CMD_MODULE_CONTROL(0x18028)`：显式 enable CARLIFE_PHONE 模块（moduleID=1, status=1）
                    // 目的：验证 Qin:0 是不是"渲染源没被启用"而不是"没有 Surface"。
                    let _ = send_cmd(&mut wr, CMD_HU_GO_TO_FOREGROUND, &[]).await;
                    let _ = send_cmd(
                        &mut wr,
                        CMD_HU_MODULE_CONTROL,
                        &proto_module_control_payload(1, 1),
                    )
                    .await;
                }
                continue;
            }
            r = rd.read(&mut chunk) => match r {
                Ok(0) => {
                    emit_log(&format!("CARLIFE: CMD 通道关闭（停在 {}）", hs.phase_name()));
                    return;
                }
                Ok(n) => n,
                Err(_) => return,
            }
        };
        buf.extend_from_slice(&chunk[..n]);

        while let Some((service_type, payload, consumed)) = read_cmd_frame(&buf) {
            buf.drain(..consumed);
            match service_type {
                CMD_VERSION_MATCH_STATUS => {
                    emit_log("CARLIFE: [Phase 1] ← 版本匹配");
                    if !hs.statistic_sent {
                        // Phase 1.5：统计信息
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_STATISTIC_INFO,
                            &proto_statistic_payload(),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.statistic_sent = true;
                        emit_log("CARLIFE: [Phase 1.5] → HU_STATISTIC_INFO");
                    }
                }
                CMD_MD_INFO => {
                    emit_log("CARLIFE: [Phase 2] ← MD_INFO");
                    if !hs.hu_info_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_INFO,
                            &proto_hu_info_payload(&bt_address, &bt_name),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.hu_info_sent = true;
                        emit_log("CARLIFE: [Phase 2] → HU_INFO");
                    }
                }
                CMD_MD_FEATURE_CONFIG_REQUEST => {
                    emit_log("CARLIFE: [Phase 3] ← MD_FEATURE_CONFIG_REQUEST");
                    if !hs.feature_sent {
                        // **把 MD 请求的特性项原样回一遍**（官方 HU 的 FeaturesHandler 就是
                        // `setFeature(key,value)` 后把 listFeatures() 回给 MD）。
                        // 我们以前只回自己写死的 6 个 key，等于把 MD 想要的能力
                        // （可能包含投屏/焦点/仪表盘等解锁项）全丢了。
                        let asked_raw = parse_feature_config(&payload);
                        // 实验 A（2026-10-27）：把 MD 请求里值为 0 的项**提升为 1**再回显。
                        // 依据：官方 HU 是 setFeature(key,value) 后回 listFeatures()，
                        // 如果 MD 请求里某些"能力位"是 0，原样回 0 等于告诉它"车机没有这个能力"，
                        // 它可能因此不建渲染源（Qin:0 的另一条候选解释）。
                        let mut asked: Vec<(String, u32)> = asked_raw
                            .into_iter()
                            .map(|(k, v)| (k, if v == 0 { 1 } else { v }))
                            .collect();
                        if !asked.is_empty() {
                            let keys: Vec<String> =
                                asked.iter().map(|(k, v)| format!("{k}={v}")).collect();
                            emit_log(&format!(
                                "CARLIFE: MD 请求特性 {} 项：{}",
                                asked.len(),
                                keys.join(", ")
                            ));
                        } else {
                            emit_log(&format!(
                                "CARLIFE: MD 特性请求解析为空（{}B）——回默认集合",
                                payload.len()
                            ));
                        }
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_FEATURE_CONFIG_RESPONSE,
                            &proto_feature_config_response(voice, &asked),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.feature_sent = true;
                        emit_log("CARLIFE: [Phase 3.5] → HU_FEATURE_CONFIG_RESPONSE");
                    }
                    if !hs.authen_req_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_AUTHEN_REQUEST,
                            &proto_authen_request_payload(),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.authen_req_sent = true;
                        emit_log("CARLIFE: [Phase 4] → HU_AUTHEN_REQUEST");
                    }
                }
                CMD_MD_AUTHEN_RESPONSE => {
                    emit_log(&format!("CARLIFE: [Phase 4] ← MD_AUTHEN_RESPONSE {}B", payload.len()));
                    if !hs.authen_result_sent {
                        let ok = send_cmd(
                            &mut wr,
                            CMD_HU_AUTHEN_RESULT,
                            &proto_authen_result_payload(true),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.authen_result_sent = true;
                        emit_log("CARLIFE: [Phase 4.5] → HU_AUTHEN_RESULT(true)");
                    }
                }
                // ---- CarWith：RSA 公钥 → AES seed → ENCRYPT_READY（CONTENT_ENCRYPTION）----
                CMD_MD_RSA_KEY_REQUEST => {
                    use rsa::pkcs8::EncodePublicKey as _;
                    use rsa::RsaPrivateKey;
                    // 生成密钥较重且 ThreadRng/MutexGuard 都不是 Send：
                    // 先在独立作用域里算好 base64，出块后再 await 发送。
                    let made: Option<(RsaPrivateKey, String)> = {
                        let mut st = CL_ENC.lock();
                        if st.rsa_sent {
                            None
                        } else {
                            let mut rng = rand::thread_rng();
                            match RsaPrivateKey::new(&mut rng, 2048) {
                                Ok(key) => {
                                    let b64 = key.to_public_key().to_public_key_der().ok()
                                        .map(|d| base64::Engine::encode(&base64::engine::general_purpose::STANDARD, d.as_bytes()))
                                        .unwrap_or_default();
                                    Some((key, b64))
                                }
                                Err(e) => { emit_log(&format!("CARLIFE: [Enc] 生成 RSA 密钥失败: {e}")); None }
                            }
                        }
                    };
                    if let Some((key, b64)) = made {
                        {
                            let mut st = CL_ENC.lock();
                            st.key = Some(key);
                            st.rsa_sent = true;
                        }
                        let mut body = Vec::new();
                        put_len_delimited(&mut body, 1, b64.as_bytes());
                        let ok = send_cmd(&mut wr, CMD_HU_RSA_PUBLIC_KEY, &body).await;
                        if !ok { return; }
                        emit_log("CARLIFE: [Enc] → HU_RSA_PUBLIC_KEY（RSA-2048 公钥，CarWith）");
                    }
                }
                CMD_MD_AES_SEED => {
                    // 解密在锁内完成，然后把 guard 释放掉再 await（Guard 不是 Send）
                    let decided: Option<bool> = {
                        let mut st = CL_ENC.lock();
                        if st.aes_ack_sent {
                            None
                        } else {
                            let b64 = pb_first_string(&payload, 1).unwrap_or_default();
                            let mut seed: Option<Vec<u8>> = None;
                            if let Some(key) = st.key.clone() {
                                if !b64.is_empty() {
                                    if let Ok(enc) = base64::Engine::decode(
                                        &base64::engine::general_purpose::STANDARD,
                                        b64.as_bytes(),
                                    ) {
                                        if let Ok(plain) = key.decrypt(rsa::Pkcs1v15Encrypt, &enc) {
                                            if matches!(plain.len(), 16 | 24 | 32) {
                                                seed = Some(plain);
                                            }
                                        }
                                    }
                                }
                            }
                            match seed {
                                Some(bytes) => {
                                    st.seed = Some(bytes);
                                    st.aes_ack_sent = true;
                                    Some(true)
                                }
                                None => Some(false),
                            }
                        }
                    };
                    match decided {
                        Some(true) => {
                            let _ = send_cmd(&mut wr, CMD_HU_AES_ACK, &[]).await;
                            emit_log("CARLIFE: [Enc] ← MD_AES_SEED 已解密 → HU_AES_ACK（AES 已建立）");
                        }
                        Some(false) => emit_log("CARLIFE: [Enc] AES seed 解密失败/长度非法"),
                        None => {}
                    }
                }
                CMD_MD_ENCRYPT_READY => {
                    let mut st = CL_ENC.lock();
                    if payload.is_empty() && st.seed.is_some() && !st.encrypted {
                        st.encrypted = true;
                        emit_log("CARLIFE: [Enc] ← MD_ENCRYPT_READY —— 加密通道就绪");
                    }
                }
                CMD_MD_AUTHEN_RESULT => {
                    emit_log("CARLIFE: [Phase 5] ← MD_AUTHEN_RESULT —— 连接建立");
                    // 先回 ACK（guard 必须在 await 之前释放，否则 Future 不是 Send）
                    let need_ack = {
                        let mut st = CL_ENC.lock();
                        if st.md_result_acked { false } else { st.md_result_acked = true; true }
                    };
                    if need_ack {
                        let _ = send_cmd(&mut wr, CMD_HU_MD_AUTH_RESULT_ACK, &[]).await;
                        emit_log("CARLIFE: [Phase 5.1] → HU_MD_AUTH_RESULT_ACK");
                    }
                    // CarWith 要等 MD_ENCRYPT_READY 之后才允许 VIDEO_ENCODER_INIT；
                    // 公版 CarLife 不做加密握手（rsa_sent=false），这里照旧立即发。
                    let (enc_required, enc_ready) = {
                        let st = CL_ENC.lock();
                        (st.rsa_sent, st.encrypted)
                    };
                    if enc_required && !enc_ready {
                        emit_log("CARLIFE: 等待加密就绪后再发 VIDEO_ENCODER_INIT（CarWith）");
                    } else if !hs.encoder_init_sent && !SESSION_STARTED.load(std::sync::atomic::Ordering::Relaxed) {
                        // 用**当前**编码分辨率（/info 已按车机真实尺寸写入），
                        // 但要先规整成手机编码器更稳的尺寸：宽度封顶 1920、宽高 16 对齐。
                        // 2560×744（744/16=46.5）这类值在部分机型上会让 MediaCodec 静默失败，
                        // 表现为"握手全过、8240 一帧不出"。
                        let (raw_w, raw_h) = *encoder_size.lock();
                        let (w, h) = safe_carlife_encoder_size(raw_w, raw_h);
                        if (w, h) != (even(raw_w.max(2)), even(raw_h.max(2))) {
                            *encoder_size.lock() = (w, h);
                            // 画布必须跟着规整后的尺寸走，否则车机端缩放/触摸坐标全偏
                            let _ = events.send(CarLifeEvent::EncoderResolution {
                                width: w,
                                height: h,
                            });
                            emit_log(&format!(
                                "CARLIFE: 请求分辨率 {raw_w}x{raw_h} → 规整为 {w}x{h}（16 宏块对齐，保留原生宽度）"
                            ));
                        }
                        let ok = send_cmd(
                            &mut wr,
                            CMD_VIDEO_ENCODER_INIT,
                            &proto_encoder_info_payload(w, h, fps),
                        )
                        .await;
                        if !ok {
                            return;
                        }
                        hs.encoder_init_sent = true;
                        emit_log(&format!(
                            "CARLIFE: [Phase 6] → VIDEO_ENCODER_INIT {w}x{h}@{fps}"
                        ));
                    }
                }
                CMD_VIDEO_ENCODER_INIT_DONE => {
                    // 手机在这里回报**实际**采用的分辨率（可能把我们请求的值压缩到
                    // 其编码器上限）。以它为准，否则 VideoConfig 画布与真实码流不一致，
                    // 车机端会缩放/错位，触摸坐标也会整体偏移。
                    let (actual_w, actual_h) = parse_encoder_info(&payload);
                    if actual_w >= 2 && actual_h >= 2 {
                        let actual = (even(actual_w), even(actual_h));
                        let requested = *encoder_size.lock();
                        *encoder_size.lock() = actual;
                        if actual != requested {
                            emit_log(&format!(
                                "CARLIFE: 手机实际编码 {}x{}（请求 {}x{}，已按其实际值同步画布）",
                                actual.0, actual.1, requested.0, requested.1
                            ));
                        } else {
                            emit_log(&format!(
                                "CARLIFE: 手机确认编码 {}x{}",
                                actual.0, actual.1
                            ));
                        }
                        let _ = events.send(CarLifeEvent::EncoderResolution {
                            width: actual.0,
                            height: actual.1,
                        });
                    } else {
                        emit_log(&format!(
                            "CARLIFE: [Phase 6] ← VIDEO_ENCODER_INIT_DONE {}B（未含尺寸，沿用请求值）",
                            payload.len()
                        ));
                    }
                    if !hs.encoder_start_sent {
                        let ok = send_cmd(&mut wr, CMD_VIDEO_ENCODER_START, &[]).await;
                        if !ok {
                            return;
                        }
                        hs.encoder_start_sent = true;
                        SESSION_STARTED.store(true, std::sync::atomic::Ordering::Relaxed);
                        emit_log("CARLIFE: [Phase 7] → VIDEO_ENCODER_START —— 握手完成，开始推流");
                        emit_state("carlife_active", "CarLife 已开始推流");
                        // 紧接着要求手机 CarLife 进入 Home（投屏）界面：
                        // 官方文档 §10.2.2 MSG_CMD_LAUNCH_MODE_NORMAL(0x1801D)。
                        // 注意：这条会让 CarLife `ProviderManager.openHome()` 把它的主页/设置页
                        // 弹到前台（反编译 CarlifeConnectHsgHandler:293）。画面不受影响（我们自己也往
                        // CastConfig.surface 里画），但用户会看到 CarLife 界面——后续再想别的办法压掉。
                        if send_cmd(&mut wr, CMD_LAUNCH_MODE_NORMAL, &[]).await {
                            emit_log("CARLIFE: [Phase 8] → LAUNCH_MODE_NORMAL（让 CarLife 进入投屏界面）");
                    // Phase 8.5（2026-11-03）：CarWith 要 HU 明确"回到前台"才收起启动浮层
                    let _ = send_cmd(&mut wr, CMD_HU_GO_TO_FOREGROUND, &[]).await;
                    emit_log("CARLIFE: [Phase 8.5] → HU_GO_TO_FOREGROUND（撤启动画面）");
                        }
                        let _ = events.send(CarLifeEvent::Handshake);
                    }
                }
                CMD_MD_CAR_DATA_SUBSCRIBE => {
                    // 手机订阅车辆数据：回一个空车辆信息列表
                    let _ = send_cmd(
                        &mut wr,
                        CMD_HU_CAR_DATA_SUBSCRIBE_DONE,
                        &proto_car_data_subscribe_done_payload(),
                    )
                    .await;
                    emit_log("CARLIFE: → HU_CAR_DATA_SUBSCRIBE_DONE");
                }
                CMD_SCREEN_ON | CMD_FOREGROUND => {
                    let name = if service_type == CMD_SCREEN_ON {
                        "SCREEN_ON"
                    } else {
                        "FOREGROUND"
                    };
                    emit_log(&format!("CARLIFE: ← {name}（手机进入前台/亮屏）"));
                    // mcarplay: onForeground() → HU_GO_TO_FOREGROUND（撤掉 CarWith 的启动浮层）
                    let _ = send_cmd(&mut wr, CMD_HU_GO_TO_FOREGROUND, &[]).await;
                    emit_log("CARLIFE: → HU_GO_TO_FOREGROUND（回应前台请求）");
                    // CarLife 的 Activity 被 `am start` 推回前台时才会真正 Resume 编码器；
                    // 如果 START 是在后台时发的，它已经被吞掉了——这里必须补发一次。
                    if hs.encoder_start_sent {
                        match send_cmd(&mut wr, CMD_VIDEO_ENCODER_START, &[]).await {
                            true => emit_log("CARLIFE: 已补发 VIDEO_ENCODER_START（前台就绪后重新触发编码）"),
                            false => return,
                        }
                    }
                }
                CMD_MD_MODULE_STATUS => {
                    emit_log("CARLIFE: ← MD_MODULE_STATUS");
                }
                CMD_MD_EXIT => {
                    emit_log("CARLIFE: ← MD_EXIT（手机会话主动退出）");
                    return;
                }
                other => {
                    // **捕获模式诊断**：真 HU（盒子）连进来时，它发的消息我们这套 MD 侧代码
                    // 大多不认识——把类型 + 前 48 字节打出来，就能逐字节对照真 HU 发了什么。
                    let hex: String = payload
                        .iter()
                        .take(48)
                        .map(|b| format!("{b:02X}"))
                        .collect::<Vec<_>>()
                        .join(" ");
                    let _ = (&hex, other);   // 逐包 hex dump 已禁用（2026-10-28）
                }
            }
        }
        // 异常长度保护：读循环卡住时避免缓冲无限增长
        if buf.len() > 4 * 1024 * 1024 {
            buf.clear();
        }
    }
}

// ---------------------------------------------------------------- 握手载荷

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
fn proto_hu_info_payload(bt_address: &str, bt_name: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(96);
    proto::string(&mut out, 1, "Android"); // os
    proto::string(&mut out, 13, "HyperCar"); // manufacturer
    proto::string(&mut out, 14, "HyperCar HU"); // model
    proto::string(&mut out, 19, "14"); // release
    proto::string(&mut out, 23, bt_address); // btaddress（required）
    proto::string(&mut out, 24, bt_name); // btname（required）
    out
}

/// 解析 MD 发来的 `CarlifeFeatureConfigList`（1=cnt, 2=repeated{1=key, 2=value}）。
fn parse_feature_config(bytes: &[u8]) -> Vec<(String, u32)> {
    let mut out: Vec<(String, u32)> = Vec::new();
    let mut i = 0usize;
    while i < bytes.len() {
        let (tag, used) = read_varint(&bytes[i..]);
        let Some(tag) = tag else { break };
        i += used;
        let field = (tag >> 3) as u32;
        let wire = (tag & 0x07) as u8;
        match wire {
            0 => {
                let (_, used) = read_varint(&bytes[i..]);
                i += used;
            }
            2 if field == 2 => {
                let (len, used) = read_varint(&bytes[i..]);
                let Some(len) = len else { break };
                i += used;
                let end = (i + len as usize).min(bytes.len());
                let item = &bytes[i..end];
                i = end;
                let mut key = String::new();
                let mut value: u32 = 0;
                let mut j = 0usize;
                while j < item.len() {
                    let (t, u) = read_varint(&item[j..]);
                    let Some(t) = t else { break };
                    j += u;
                    let f = (t >> 3) as u32;
                    let w = (t & 0x07) as u8;
                    match (f, w) {
                        (1, 2) => {
                            let (l, u2) = read_varint(&item[j..]);
                            let Some(l) = l else { break };
                            j += u2;
                            let e2 = (j + l as usize).min(item.len());
                            key = String::from_utf8_lossy(&item[j..e2]).to_string();
                            j = e2;
                        }
                        (2, 0) => {
                            let (v, u2) = read_varint(&item[j..]);
                            let Some(v) = v else { break };
                            j += u2;
                            value = v as u32;
                        }
                        (_, 0) => {
                            let (_, u2) = read_varint(&item[j..]);
                            j += u2;
                        }
                        (_, 2) => {
                            let (l, u2) = read_varint(&item[j..]);
                            let Some(l) = l else { break };
                            j += u2 + l as usize;
                        }
                        _ => break,
                    }
                }
                if !key.is_empty() {
                    out.push((key, value));
                }
            }
            2 => {
                let (len, used) = read_varint(&bytes[i..]);
                let Some(len) = len else { break };
                i += used + len as usize;
            }
            5 => i += 4,
            1 => i += 8,
            _ => break,
        }
    }
    out
}

/// `CarlifeFeatureConfigList`（HU 的特性配置应答）。
///
/// 官方 HU 的做法（`FeaturesHandler.handleFeatureRequest`）是：先 `setFeature(key,value)`
/// 记录 **MD 请求的每一条**，再把整个列表回给 MD。所以这里也以 MD 的请求为准回显；
/// 只有解析不出任何项时才退回我们自己的默认集合。
fn proto_feature_config_response(voice: u32, asked: &[(String, u32)]) -> Vec<u8> {
    // 1=cnt, 2=repeated {1=key string, 2=value int32}, 3=hu_bt_audio_support, 4/5=bt name/mac
    let mut features: Vec<(String, u32)> = asked.to_vec();
    if features.is_empty() {
        features = vec![
            ("video".to_string(), 1),
            ("audio".to_string(), 1),
            ("touch".to_string(), 1),
            ("music".to_string(), 1),
            ("navigation".to_string(), 1),
            ("voice".to_string(), voice.clamp(0, 1)),
        ];
    }
    let mut out = Vec::with_capacity(96);
    proto::varint(&mut out, 1, features.len() as u64);
    for (key, value) in &features {
        let mut item = Vec::with_capacity(16);
        proto::string(&mut item, 1, key);
        proto::varint(&mut item, 2, *value as u64);
        proto::bytes(&mut out, 2, &item);
    }
    proto::boolean(&mut out, 3, false);
    out
}

fn proto_authen_request_payload() -> Vec<u8> {
    // CarlifeAuthenRequest: 1=random_value(string)
    let mut out = Vec::with_capacity(48);
    proto::string(&mut out, 1, &uuid::Uuid::new_v4().to_string());
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
fn proto_module_control_payload(module_id: u32, status: u32) -> Vec<u8> {
    let mut item = Vec::with_capacity(16);
    proto::varint(&mut item, 1, module_id as u64);
    proto::varint(&mut item, 2, status as u64);
    let mut out = Vec::with_capacity(24);
    proto::varint(&mut out, 1, 1);
    proto::bytes(&mut out, 2, &item);
    out
}

fn proto_car_data_subscribe_done_payload() -> Vec<u8> {
    // CarlifeVehicleInfoList: 1=cnt
    let mut out = Vec::with_capacity(4);
    proto::varint(&mut out, 1, 0);
    out
}

// ---------------------------------------------------------------- 媒体通道

#[derive(Clone, Copy)]
enum MediaKind {
    Video,
    Media,
    Tts,
}

fn spawn_media_channel(
    port: u16,
    name: &'static str,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    stop: Arc<Notify>,
    kind: MediaKind,
) {
    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(e) => {
            emit_log(&format!("CARLIFE: {name} {port} 绑定失败: {e}"));
            return;
        }
    };
    spawn_single_client_server(listener, stop, name, move |stream| {
        handle_media_client(stream, events.clone(), kind, "VIDEO", None)
    });
}

/// VIDEO/MEDIA/TTS 通道：12 字节头 + 裸数据（视频 H.264 AnnexB / 音频 PCM）。
async fn handle_media_client(
    stream: TcpStream,
    events: mpsc::UnboundedSender<CarLifeEvent>,
    kind: MediaKind,
    label: &'static str,
    encryption: Option<session::Shared>,
) {
    let (mut rd, mut wr) = stream.into_split();
    let is_video = matches!(kind, MediaKind::Video);
    let mut buf: Vec<u8> = Vec::with_capacity(256 * 1024);
    let mut chunk = [0u8; 65536];
    let mut rate: u32 = 44_100;   // CarLife 音乐默认 44100Hz（实测 43~44 块/秒 × 4096B）
    let mut channels: u8 = 2;
    // 视频通道诊断：CarLife 握手全过、音频 136/s 正常，但车机上一直是待机卡片
    // （2026-10-26 00:24 现场：没有任何 VideoFrame 产出）。这里把前几次读到的东西打出来，
    // 一眼就能分清「8240 上一个字节都没有」还是「有数据但帧头解析不出来」。
    let mut diag_reads: u32 = 0;
    let mut parser = video::VideoParser::default();
    let mut access_units = 0u64;
    // **VIDEO 心跳**：官方 HU 实现（apollo-DuerOS `ConnectionEstablishHandler` 的
    // heartBeatTask）每秒往 VIDEO 通道发一条 `MSG_VIDEO_HEARTBEAT (0x20002)`；
    // 我们之前只读不发，CarLife 可能因此认为视频通道"没人要"，编码器空转不给帧。
    // 这里按官方节奏 1s 一条（空载荷，12 字节头）。
    let mut hb = tokio::time::interval(Duration::from_secs(1));
    hb.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut hb_sent: u64 = 0;

    loop {
        let read = tokio::select! {
            _ = hb.tick(), if is_video => {
                let timestamp = if encryption.is_some() {
                    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as u32
                } else { now_ms() };
                let frame = build_media_frame(VIDEO_HEARTBEAT, timestamp, &[]);
                if wr.write_all(&frame).await.is_err() {
                    return;
                }
                hb_sent += 1;
                if hb_sent <= 3 || hb_sent % 30 == 0 {
                    emit_log(&format!("CARLIFE: → VIDEO 心跳 #{hb_sent}（0x20002）"));
                }
                continue;
            }
            r = rd.read(&mut chunk) => r,
        };
        match read {
            Ok(0) => {
                let _ = events.send(CarLifeEvent::ClientLeft { video: is_video });
                return;
            }
            Ok(n) => {
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
            Err(_) => return,
        }
        if buf.len() > 8 * 1024 * 1024 {
            buf.clear();
        }
        while let Some((service_type, ts_ms, payload, consumed)) = read_media_frame(&buf) {
            buf.drain(..consumed);
            if service_type == VIDEO_DATA && encryption.as_ref().is_some_and(|s| !s.lock().started) {
                continue; // A landing-page frame is not proof of a completed CarWith handshake.
            }
            // Xiaomi mixed MEDIA_DATA remains PCM; video, TTS and audio INIT use AES-ECB.
            let raw_pcm = matches!(kind, MediaKind::Media) && service_type == MEDIA_DATA;
            let payload = if let Some(state) = encryption.as_ref().filter(|_| !raw_pcm) {
                match state.lock().decrypt(&payload) {
                    Ok(bytes) => bytes,
                    Err(error) => { emit_log(&format!("CARWITH-HS: {label} decrypt failed: {error}")); return; }
                }
            } else { payload };
            match kind {
                MediaKind::Video => match service_type {
                    VIDEO_DATA => {
                        if !payload.is_empty() {
                            // 首帧到达：CMD 侧的 START 补发逻辑据此停止
                            VIDEO_FIRST_FRAME_RECEIVED
                                .store(true, std::sync::atomic::Ordering::Relaxed);
                            if encryption.is_some() {
                                let units = match parser.push(&payload) {
                                    Ok(units) => units,
                                    Err(error) => { emit_log(&format!("CARWITH-VIDEO: {error}")); return; }
                                };
                                for data in units {
                                    access_units += 1;
                                    let types: Vec<_> = crate::screen::annexb_nals(&data).iter().map(|n| n[0] & 31).collect();
                                    if access_units == 1 || types.contains(&5) {
                                        emit_log(&format!("CARWITH-VIDEO: AU#{access_units} bytes={} nals={types:?} sourceUnixSeconds={ts_ms}; AirPlay PTS=monotonic",data.len()));
                                    }
                                    let _ = events.send(CarLifeEvent::VideoFrame {
                                        pts_us: crate::timing::monotonic_nanos() / 1000, data,
                                    });
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
                    let mut media_probe: u32 = 0;
                    match service_type {
                        MEDIA_INIT | TTS_INIT => {
                            let (r, c) = parse_audio_init(&payload);
                            if (8_000..=96_000).contains(&r) {
                                rate = r;
                            }
                            if (1..=2).contains(&c) {
                                channels = c as u8;
                            }
                            let _ = events.send(CarLifeEvent::AudioInit {
                                tts,
                                rate,
                                channels,
                            });
                            emit_log(&format!(
                                "CARLIFE: {} 音频初始化 {rate}Hz {channels}ch",
                                if tts { "TTS" } else { "MEDIA" }
                            ));
                        }
                        MEDIA_STOP | TTS_STOP => {
                            let _ = events.send(CarLifeEvent::AudioStop { tts });
                        }
                        MEDIA_DATA | TTS_DATA => {
                            if !payload.is_empty() {
                                // **剥掉 12 字节 PCM 包头**（2026-10-27 23:56 定案）：
                                // 反编译 `PCMPackageHead` 明确 mMediaHeadLen=12/mTTSHeadLen=12，
                                // 布局 [0..4]=dataLen(BE) [4..8]=timeStamp(BE) [8..12]=type(BE)。
                                // 现场取证 `00 00 00 00 00 00 00 00 01 00 00 00` 正是 type=1 的包头；
                                // 不剥掉就会被当样本播 → 每包一次"咔"（破音）。
                                {
                                    let _ = events.send(CarLifeEvent::AudioData {
                                        tts,
                                        rate,
                                        channels,
                                        data: payload.to_vec(),
                                    });
                                }
                            }
                        }
                        other => {
                            if other != 0 {
                                emit_log(&format!(
                                    "CARLIFE: {} 未处理 0x{other:08x}",
                                    if tts { "TTS" } else { "MEDIA" }
                                ));
                            }
                        }
                    }
                }
            }
        }
    }
}

/// 偶数对齐：H.264 4:2:0 要求宽高为偶数。
fn even(v: u32) -> u32 {
    (v / 2) * 2
}

/// 是否已经从 8240 收到过至少一帧 H.264（没收到前，CMD 侧会周期性补发
/// VIDEO_ENCODER_START，避免"START 发出时 CarLife 还在后台、Surface 未就绪"被吞掉）。
pub(crate) static VIDEO_FIRST_FRAME_RECEIVED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// 本轮会话是否已经发出过 VIDEO_ENCODER_START：
/// CMD 通道被 MD 关掉后我们会重连，**重连时不能再发 INIT/START**——
/// 那会重置 CarLife 的编码器（GL/渲染源跟着重建），表现为帧刚来就断、车机退回待机卡片。
pub(crate) static SESSION_STARTED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// 把请求的编码分辨率规整成手机硬件编码器更稳的尺寸：
///  **宽高都向 16 取整**（H.264 宏块 16×16；744 这种 744/16=46.5 的非整数高度，
///  在部分机型上会让 `MediaCodec.configure()` 静默失败——握手全过但 8240 一帧不出）。
///
/// **不再压到 1920 宽**：CarWith 自己的分辨率设置里最大支持 2560×2560（2026-10-26 实测），
/// 说明手机侧能直接出 2560 宽的流；压到 1920 反而白丢清晰度。
/// 例：2560×744 → 2560×752（只做 16 对齐，宽高比 3.41 与 2560/744 基本一致）。
const CARLIFE_ENCODER_MAX_WIDTH: u32 = 1920;   // 公版 CarLife 实测只支持 1920 宽（2026-10-28）
/// 高度上限（CarWith 设置界面同样支持到 2560）。只用于防御异常值。
const CARLIFE_ENCODER_MAX_HEIGHT: u32 = 2560;

/// 本机某端口是否处于 LISTEN（读 /proc/net/tcp，不需要任何权限）。
fn local_listening(port: u16) -> bool {
    let hex = format!(":{:04X}", port);
    for path in ["/proc/net/tcp", "/proc/net/tcp6"] {
        if let Ok(text) = std::fs::read_to_string(path) {
            for line in text.lines().skip(1) {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 3 && cols[1].ends_with(&hex) && cols[3] == "0A" {
                    return true;
                }
            }
        }
    }
    false
}

/// 等到所有 CarWith 通道都开始 LISTEN（对齐 mcarplay 的 connectAll 语义：
/// **七路都就绪后才开始握手**，否则 CarWith 会在启动画面等我们）。
async fn wait_cmd_listening(timeout: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if local_listening(PORT_CMD) {
            emit_log("CARLIFE: CMD 7240 已在监听 → 立即握手");
            return true;
        }
        if tokio::time::Instant::now() >= deadline {
            return false;
        }
        tokio::time::sleep(Duration::from_millis(120)).await;
    }
}

fn safe_carlife_encoder_size(w: u32, h: u32) -> (u32, u32) {
    let mut sw = w.max(320);
    let mut sh = h.max(240);
    if sw > CARLIFE_ENCODER_MAX_WIDTH {
        // 离谱的宽（>2560）才等比缩回来
        sh = ((sh as u64 * CARLIFE_ENCODER_MAX_WIDTH as u64) / sw as u64).max(240) as u32;
        sw = CARLIFE_ENCODER_MAX_WIDTH;
    }
    // 向最近的 16 取整（+8 再整除），并夹回安全范围
    sw = ((((sw + 8) / 16) * 16).clamp(320, CARLIFE_ENCODER_MAX_WIDTH)).max(320);
    sh = ((((sh + 8) / 16) * 16).clamp(240, CARLIFE_ENCODER_MAX_HEIGHT)).max(240);
    (sw, sh)
}

/// 解析 `CarlifeVideoEncoderInfo{1:width, 2:height, 3:frameRate}` 的宽高。
/// 手机在 VIDEO_ENCODER_INIT_DONE 里用同一结构回报它**实际**采用的尺寸；缺失则返回 (0,0)。
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

fn spawn_ctrl_channel(
    port: u16,
    mut touch_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    stop: Arc<Notify>,
) {
    // 注入帧先进队列，客户端在线时按 8ms 节拍刷出
    let queue: Arc<Mutex<Vec<Vec<u8>>>> = Arc::new(Mutex::new(Vec::new()));
    {
        let queue = queue.clone();
        let stop_fill = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_fill.notified() => return,
                    frame = touch_rx.recv() => match frame {
                        Some(f) => {
                            let mut q = queue.lock();
                            if q.len() > 256 {
                                q.remove(0);
                            }
                            q.push(f);
                        }
                        None => return,
                    },
                }
            }
        });
    }

    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(e) => {
            emit_log(&format!("CARLIFE: CTRL {port} 绑定失败: {e}"));
            return;
        }
    };
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => {
                        let _ = stream.set_nodelay(true);
                        emit_log("CARLIFE: ✓ CTRL 通道已接入（触摸/硬按键就绪）");
                        // 与主动连接模式共用同一个出向写循环
                        ctrl_write_loop(stream, queue.clone(), stop.clone()).await;
                    }
                    Err(_) => return,
                },
            }
        }
    });
}

// ---------------------------------------------------------------- 通用 accept / 保活

/// 拨号失败日志节流：同一通道每 5s 最多一条。
///
/// 不节流的话，主动连接模式下 MD 还没开始监听时每条通道每秒一条、共 6 条/秒，
/// 几秒钟就把日志刷得没法看（现场 2026-10-23 22:55 就是这么炸的）。
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

/// 只读消费一个连接（VR 通道：保活即可，不消费语义）。
async fn drain_stream(mut stream: TcpStream) {
    let mut buf = [0u8; 2048];
    loop {
        match stream.read(&mut buf).await {
            Ok(0) => return,
            Ok(_) => {}
            Err(_) => return,
        }
    }
}

/// CTRL 出向写循环：注入帧进队列，客户端在线时按 8ms 节拍刷出。
async fn ctrl_write_loop(
    mut stream: TcpStream,
    queue: Arc<Mutex<Vec<Vec<u8>>>>,
    stop: Arc<Notify>,
) {
    loop {
        let frame = {
            let mut q = queue.lock();
            if q.is_empty() {
                None
            } else {
                Some(q.remove(0))
            }
        };
        match frame {
            Some(f) => {
                if stream.write_all(&f).await.is_err() {
                    return;
                }
            }
            None => {
                tokio::select! {
                    _ = stop.notified() => return,
                    _ = tokio::time::sleep(Duration::from_millis(8)) => {}
                }
            }
        }
    }
}

/// HU 侧「UDP 7999 发现广播」——**尽力而为**，不是公开协议。
///
/// 公开资料里无线 CarLife **没有**发现协议：CarLife/CarWith 只是把六条 TCP 端口打开、
/// 等车机去连（Limo/xcertplay 的 `SubnetScanner.kt` 注释与实测都是这个结论），所以
/// 我们真正的发现手段是「探 127.0.0.1:7240」（见 `HyperCarRepo.probeLocalCarLifeMd`）。
///
/// 这条 UDP 广播是给「个别车机版 MD 会等一条广播才开始回连」的情况兜底：
///  - 每 1s（前 12 次）→ 每 5s，往 `127.0.0.1:7999` 与 `255.255.255.255:7999` 发一条
///    **可读文本** `127.0.0.1:7240`（含 NUL）。不认识它的版本只会丢弃 UDP 包，无害。
///  - 同时尝试 bind `0.0.0.0:7999` 收包：如果 MD 真的往这里广播，把前几包打成 hex
///    打进日志——真抓到包就能反推它到底要什么格式（到时候再按真实报文改）。
fn spawn_discovery_beacon(stop: Arc<Notify>) {
    tokio::spawn(async move {
        const DISCOVERY_PORT: u16 = 7999;
        // 广播里必须放**局域网 IP**，不能放 127.0.0.1：com.miui.carlink 拿到 127.0.0.1
        // 会认为主机就是自己、直接忽略（2026-10-26 00:04 现场：它只在 UDP 7999 反复问，
        // 永不连本机网关）。取法是典型的「UDP connect 选路」：不真正发包，只让内核告诉我们
        // 出网用的是哪块网卡的地址。
        let payload = {
            let mut v = format!("{}:{PORT_CMD}", lan_ipv4_hint()).into_bytes();
            v.push(0);
            v
        };
        // 收：优先占 7999；被 CarLife 自己占了就只发不收（别去抢它的 socket）
        let recv_sock = match tokio::net::UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await {
            Ok(s) => Some(s),
            Err(e) => {
                emit_log(&format!(
                    "CARLIFE: UDP {DISCOVERY_PORT} 绑定失败（{e}）——只发发现广播、不收包"
                ));
                None
            }
        };
        let send_sock = match tokio::net::UdpSocket::bind(("0.0.0.0", 0)).await {
            Ok(s) => {
                // 255.255.255.255 需要 SO_BROADCAST，否则 EACCES（loopback 不受影响）
                if let Err(e) = s.set_broadcast(true) {
                    emit_log(&format!("CARLIFE: UDP 广播开关设置失败（{e}）——只发回环"));
                }
                s
            }
            Err(e) => {
                emit_log(&format!("CARLIFE: 发现广播 socket 创建失败（{e}）——跳过"));
                return;
            }
        };
        emit_log(&format!(
            "CARLIFE: 已启动 UDP {DISCOVERY_PORT} 发现广播（payload={}，并会应答 MD 的发现包）",
            String::from_utf8_lossy(&payload)
        ));

        let mut buf = [0u8; 2048];
        let mut sent: u32 = 0;
        let mut received_logged: u32 = 0;
        let mut replied: u32 = 0;
        let mut next = tokio::time::Instant::now();
        loop {
            let gap = if sent < 12 {
                Duration::from_secs(1)
            } else {
                Duration::from_secs(5)
            };
            let sleep = tokio::time::sleep_until(next);
            tokio::pin!(sleep);
            tokio::select! {
                _ = stop.notified() => return,
                _ = &mut sleep => {
                    for dst in [("127.0.0.1", DISCOVERY_PORT), ("255.255.255.255", DISCOVERY_PORT)] {
                        if let Err(e) = send_sock.send_to(&payload, dst).await {
                            if sent == 0 {
                                emit_log(&format!("CARLIFE: 发现广播发往 {}:{} 失败（{e}）", dst.0, dst.1));
                            }
                        }
                    }
                    sent = sent.saturating_add(1);
                    next = tokio::time::Instant::now() + gap;
                }
                r = recv_from_opt(&recv_sock, &mut buf) => {
                    if let Some((n, from)) = r {
                        // 我们自己发的那条（回环单播/广播回环）也会被本 socket 收到，
                        // 按 payload 原样过滤掉，免得把自问自答当成 MD 的发现包。
                        let own = n == payload.len() && buf[..n] == payload[..];
                        if !own && received_logged < 5 {
                            received_logged += 1;
                            let hex: String = buf[..n.min(64)].iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(" ");
                            emit_log(&format!(
                                "CARLIFE: UDP {DISCOVERY_PORT} 收到 {n}B ← {from}：{hex}"
                            ));
                        }
                        // **关键**：CarLife 的无线发现是「MD 广播问、HU 单播答」。
                        // 以前我们只广播不应答，com.miui.carlink 就一直在问、永远拿不到主机地址
                        // （2026-10-26 00:04 现场：每 2s 收到一次 "CarLifeHost" 11B，之后再无动作）。
                        //
                        // 应答必须**从 7999 端口发出**：第一版用了一个随机端口的 socket 回，
                        // 手机端显然只认"来自 7999 的应答"，于是依旧每 2s 重问
                        // （2026-10-26 00:09 现场：我们回了 4 次，对方毫无反应）。
                        // 这里改用绑在 7999 的 recv_sock 回，并同时试两种 payload：
                        //   ① "<lan_ip>:7240\0"（主机地址）
                        //   ② 把收到的报文原样回显（有些实现是"问什么答什么"）
                        if !own {
                            let reply_sock = recv_sock.as_ref().unwrap_or(&send_sock);
                            match reply_sock.send_to(&payload, from).await {
                                Ok(_) => {
                                    replied = replied.saturating_add(1);
                                    if replied <= 3 {
                                        emit_log(&format!(
                                            "CARLIFE: 已应答发现包（源端口 7999）→ {from}（payload={}）",
                                            String::from_utf8_lossy(&payload).trim_end_matches('\u{0}')
                                        ));
                                    }
                                }
                                Err(e) => {
                                    if replied == 0 {
                                        emit_log(&format!("CARLIFE: 应答发现包给 {from} 失败（{e}）"));
                                    }
                                }
                            }
                            // 变体②：不带结尾 NUL 的 "<lan_ip>:7240"（有的实现按字符串精确比对）
                            let _ = reply_sock.send_to(&payload[..payload.len() - 1], from).await;
                            // 变体③：把询问原样回显（"问什么答什么"型实现）
                            let _ = reply_sock.send_to(&buf[..n], from).await;
                        }
                    }
                }
            }
        }
    });
}

/// `Option<UdpSocket>` 的收包：没有 socket 时永久等待（让 `select!` 的另一条腿工作）。
async fn recv_from_opt(
    sock: &Option<tokio::net::UdpSocket>,
    buf: &mut [u8],
) -> Option<(usize, std::net::SocketAddr)> {
    match sock {
        Some(s) => s.recv_from(buf).await.ok(),
        None => std::future::pending().await,
    }
}

/// 本机出网用的 IPv4（「UDP connect 选路」：不真正发包，只让内核回填 source 地址）。
/// 取不到就退回 127.0.0.1（PC 单机联调那种场景）。
fn lan_ipv4_hint() -> String {
    if let Ok(probe) = std::net::UdpSocket::bind("0.0.0.0:0") {
        // 目标不重要，只要有一条默认路由；不会真的发出报文
        if probe.connect(("8.8.8.8", 53)).is_ok() {
            if let Ok(local) = probe.local_addr() {
                let ip = local.ip();
                if !ip.is_loopback() && !ip.is_unspecified() {
                    return ip.to_string();
                }
            }
        }
    }
    "127.0.0.1".to_string()
}

fn bind_tcp(port: u16) -> Result<TcpListener, std::io::Error> {
    // 绑定地址：**0.0.0.0**——手机上 CarLife（com.miui.carlink）是以"找主机"的方式工作，
    // 它会按 UDP 7999 的应答去连 `<局域网IP>:7240`，而不是 127.0.0.1；只绑回环它就连不进来
    // （2026-10-26 00:04 现场）。PC 侧联调（rust-core/hub）行为不变，仍可用 CARLIFE_BIND 覆盖。
    let host = std::env::var("CARLIFE_BIND").unwrap_or_else(|_| "0.0.0.0".to_string());
    // 重绑竞争：上一会话刚停时端口可能还在旧任务手里，短重试几次
    let mut last_err = None;
    for _ in 0..15 {
        match std::net::TcpListener::bind((host.as_str(), port)) {
            Ok(listener) => {
                listener.set_nonblocking(true)?;
                return TcpListener::from_std(listener);
            }
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }
    Err(last_err.unwrap())
}

fn spawn_single_client_server<F, Fut>(
    listener: TcpListener,
    stop: Arc<Notify>,
    name: &'static str,
    handler: F,
) where
    F: Fn(TcpStream) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = ()> + Send + 'static,
{
    tokio::spawn(async move {
        let handler = Arc::new(handler);
        let mut current: Option<tokio::task::JoinHandle<()>> = None;
        loop {
            tokio::select! {
                _ = stop.notified() => {
                    if let Some(h) = current { h.abort(); }
                    return;
                }
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => {
                        let _ = stream.set_nodelay(true);
                        if let Some(h) = current.take() { h.abort(); }
                        emit_log(&format!("CARLIFE: {name} 客户端已接入"));
                        let handler = handler.clone();
                        current = Some(tokio::spawn(async move { handler(stream).await }));
                    }
                    Err(_) => return,
                },
            }
        }
    });
}

/// VR 通道：读空保活 + **可写**（麦克风数据 HU → MD）。
///
/// 语音助手要靠车机把麦克风 PCM 推给手机（`VR_MIC_DATA 0x00058001`，12 字节媒体头）。
/// 之前这条通道纯 drain，手机上语音助手会报「麦克风有问题」。
async fn vr_loop(
    mut stream: TcpStream,
    mic_rx: &mut mpsc::UnboundedReceiver<Vec<u8>>,
    name: &'static str,
    stop: Arc<Notify>,
) {
    let (mut rd, mut wr) = stream.split();
    let mut buf = [0u8; 2048];
    let mut vr_msgs = 0u32;
    loop {
        tokio::select! {
            _ = stop.notified() => return,
            r = rd.read(&mut buf) => match r {
                Ok(0) | Err(_) => return,
                Ok(n) => {
                    if n >= 12 && vr_msgs < 8 {
                        vr_msgs += 1;
                        let len = u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]);
                        let st = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
                        emit_log(&format!("CARLIFE: {name} ← 服务类型 0x{st:08X} {len}B"));
                    }
                }
            },
            frame = mic_rx.recv() => match frame {
                Some(f) => {
                    if wr.write_all(&f).await.is_err() {
                        return;
                    }
                }
                None => return,
            },
        }
    }
}

/// 监听版 VR 通道（Android 同机回环）：接受后进入 [`vr_loop`]。
fn spawn_vr_listener(
    port: u16,
    name: &'static str,
    mut mic_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    stop: Arc<Notify>,
) {
    let listener = match bind_tcp(port) {
        Ok(l) => l,
        Err(_) => return,
    };
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                accepted = listener.accept() => {
                    if let Ok((stream, _)) = accepted {
                        emit_log(&format!("CARLIFE: {name} 通道接入"));
                        vr_loop(stream, &mut mic_rx, name, stop.clone()).await;
                    }
                }
            }
        }
    });
}









