//! Local CarWith/CarLife transport. The active Android source uses seven loopback
//! channels, fresh per-round state and the authenticated CONTENT_ENCRYPTION
//! handshake in session.rs. Retired generic listener/discovery paths have been removed.
//! CMD/CTRL/DATA use 8-byte headers; VIDEO/MEDIA/TTS/VR use 12-byte headers.
//! CarWith DATA is port 9440. Do not mix generic CarLife assumptions with this path.

mod session;
mod video;

use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Notify};

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
const CMD_HU_STATISTIC_INFO: u32 = 0x0001_8027;
/// 模块级控制（文档 §10.2.2）：CarlifeModuleStatusList{cnt, moduleStatus{moduleID,statusID}}
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
const MEDIA_DATA: u32 = 0x0003_0006;
const TTS_INIT: u32 = 0x0004_0001;
const TTS_STOP: u32 = 0x0004_0002;
const TTS_DATA: u32 = 0x0004_0003;

// CTRL 通道（8 字节头）

/// 触摸动作（CarLife 语义：0=DOWN / 1=UP / 2=MOVE，**不是** Android 的 1/2/3）。
pub const TOUCH_DOWN: u32 = 0;
pub const TOUCH_UP: u32 = 1;
pub const TOUCH_MOVE: u32 = 2;

/// 协议版本（CarLifeWirelessBox 默认 v3.2，实测 CarWith 接受）。
    const PROTOCOL_MAJOR: u32 = 4;   // mcarplay 实测：CarWith 用 4.1
    const PROTOCOL_MINOR: u32 = 1;

#[derive(Clone)]
pub struct CarLifeConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// `HU_INFO` 里 required 的 `btaddress`（车机蓝牙 MAC）。
    pub bt_address: String,
    /// `HU_INFO` 里 required 的 `btname`（车机蓝牙名）。
    pub bt_name: String,
}

/// Authenticated seven-channel CarWith gateway with video/audio events and touch input.
struct QueuedControl { bytes:Vec<u8>, expires:Option<std::time::Instant> }

pub struct CarLifeHub {
    owned_tasks: Vec<tokio::task::JoinHandle<()>>,
    touch_state: Arc<Mutex<session::TouchGesture>>,
    touch_tx: mpsc::UnboundedSender<QueuedControl>,
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
        cfg: CarLifeConfig, md_host: String, stop: Arc<Notify>, requested: Option<Arc<Mutex<(u32,u32)>>>,
    ) -> Result<(Self, mpsc::UnboundedReceiver<CarLifeEvent>), String> {
        let keyframe_request = Arc::new(Notify::new());
        let reset_request = keyframe_request.clone();
        let (events, event_rx) = mpsc::unbounded_channel();
        let (touch_tx, mut touch_rx) = mpsc::unbounded_channel::<QueuedControl>();
        let encoder_size = Arc::new(Mutex::new((cfg.width.max(2),cfg.height.max(2))));
        let requested = requested.unwrap_or_else(|| encoder_size.clone());
        let touch_state=Arc::new(Mutex::new(session::TouchGesture::default()));
        let round_touch=touch_state.clone();
        let queue = Arc::new(Mutex::new(Vec::<QueuedControl>::new()));
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
                // VR remains connected and drained; no upstream microphone feature is exposed.
                children.spawn(drain_stream(sockets.next().unwrap()));
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
        Ok((Self {owned_tasks:vec![fill_task,connection_task],touch_state,touch_tx,encoder_size,keyframe_request},event_rx))
    }

    pub fn request_keyframe(&self) {
        self.keyframe_request.notify_one();
    }

    /// 外部（AirPlay 事件通道）注入触摸 → CarLife CTRL 通道 TOUCH_ACTION。
    pub fn inject_touch(&self, action: u32, x: u32, y: u32) {
        let size=*self.encoder_size.lock();
        if let Some(frame)=self.touch_state.lock().accept(action,x,y,size) {
            let _=self.touch_tx.send(QueuedControl{bytes:frame,expires:None});
        }
    }

    pub fn voice_key(&self, start:bool) {
        let code=if start {0x21} else {0x22};
        let mut body=Vec::new();proto::varint(&mut body,1,code);
        let packet=QueuedControl{bytes:build_cmd_frame(0x68008,&body),expires:Some(std::time::Instant::now()+Duration::from_secs(2))};
        emit_log(&format!("VOICE-KEY: CarWith key=0x{code:x} queued={} (not recognition confirmation)",self.touch_tx.send(packet).is_ok()));
    }

}

impl Drop for CarLifeHub {
    fn drop(&mut self) {
        for task in &self.owned_tasks { task.abort(); }
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

