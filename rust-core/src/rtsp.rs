//! AirPlay RTSP 控制通道（加密帧 + CSeq 异步派发）。
//!
//! 协议依据（docs/PROTOCOL-NOTES.md §9c/§9f 与 cardeck 实测）：
//!  - 控制通道密文帧 = [2B LE 长度][密文][16B Poly1305 tag]，AAD = 前 2 字节，
//!    nonce = 4 字节零 + 8 字节小端计数；收发方向各自的密钥与计数独立递增。
//!  - 单帧明文上限 **1422**（1440 MTU - 2B AAD - 16B tag；catplay 解码侧对
//!    len > 1422 直接判协议违规）；读方向保持宽松（车机的分片长度由它决定）。
//!  - 应答必须按 CSeq 异步派发：车机的音频 SETUP 实测可耗时 ~2.7s，
//!    同步读会把 /feedback 心跳饿死，3s 看门狗即拆会话（cardeck 2026-10-17 实测）。
//!  - 事件通道是双向 RTSP：车机的每个来向请求必须及时应答（echo CSeq），
//!    不应答 → 车机塞 3 条就断通道（§9c「触摸无报文（三）」）。

use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::{mpsc, oneshot, watch};

use crate::crypto::{chacha_open, chacha_seal, nonce64};

/// 1440 MTU - 2B AAD - 16B tag（catplay AIRPLAY_CIPHER_MTU_MAX 同口径）。
pub const CIPHER_MAX_PAYLOAD: usize = 1422;

const HEADER_SIZE: usize = 2;
const TAG_SIZE: usize = 16;
static CHANNEL_IDS: AtomicU64 = AtomicU64::new(1);

/// 控制通道密文帧编解码（每方向独立密钥与计数器）。
pub struct ControlCipher {
    read_key: [u8; 32],
    write_key: [u8; 32],
    read_counter: u64,
    write_counter: u64,
}

impl ControlCipher {
    pub fn new(read_key: [u8; 32], write_key: [u8; 32]) -> Self {
        Self {
            read_key,
            write_key,
            read_counter: 0,
            write_counter: 0,
        }
    }

    /// 明文 → 密文帧序列（按 1422B 分片，每片独立封帧）。
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(plaintext.len() + 32);
        let mut offset = 0usize;
        loop {
            let end = (offset + CIPHER_MAX_PAYLOAD).min(plaintext.len());
            let chunk = &plaintext[offset..end];
            let header = [(chunk.len() & 0xFF) as u8, ((chunk.len() >> 8) & 0xFF) as u8];
            let sealed = chacha_seal(&self.write_key, &nonce64(self.write_counter), chunk, &header);
            out.extend_from_slice(&header);
            out.extend_from_slice(&sealed);
            self.write_counter = self.write_counter.wrapping_add(1);
            if end >= plaintext.len() {
                break;
            }
            offset = end;
        }
        out
    }

    /// 从 buffer 里解出所有完整密文帧的明文拼接；返回 (明文, 已消费字节数)。
    /// 单帧 tag 校验失败视为通道被污染，返回 Err。
    pub fn decrypt(&mut self, buffer: &[u8]) -> Result<(Vec<u8>, usize), String> {
        let mut out = Vec::with_capacity(buffer.len());
        let mut offset = 0usize;
        while buffer.len() - offset >= HEADER_SIZE {
            let len =
                (buffer[offset] as usize) | ((buffer[offset + 1] as usize) << 8);
            let frame_end = offset + HEADER_SIZE + len + TAG_SIZE;
            if buffer.len() < frame_end {
                break;
            }
            let aad = &buffer[offset..offset + HEADER_SIZE];
            let sealed = &buffer[offset + HEADER_SIZE..frame_end];
            let plain =
                chacha_open(&self.read_key, &nonce64(self.read_counter), sealed, aad)
                    .map_err(|e| format!("控制通道解密失败: {e}"))?;
            out.extend_from_slice(&plain);
            self.read_counter = self.read_counter.wrapping_add(1);
            offset = frame_end;
        }
        Ok((out, offset))
    }
}

/// 一条已解析的 RTSP 消息（请求或应答）。
#[derive(Debug, Clone)]
pub struct RtspMessage {
    /// Local monotonic time when the complete message was parsed, before CSeq dispatch.
    pub received_at: std::time::Instant,
    /// Local screen grant observed when the event reader accepted a HID report.
    pub(crate) hid_screen_grant: Option<u64>,
    /// 应答："RTSP/1.0 200 OK"；请求："POST /command RTSP/1.0"。
    pub first_line: String,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl RtspMessage {
    pub(crate) fn is_empty_server_error(&self) -> bool {
        self.status() == 500 && self.body.is_empty()
    }

    pub fn is_response(&self) -> bool {
        self.first_line.starts_with("RTSP/") || self.first_line.starts_with("HTTP/1.0 ") || self.first_line.starts_with("HTTP/1.1 ")
    }

    fn response_protocol(&self) -> &'static str {
        match self.first_line.split_whitespace().nth(2) {
            Some("HTTP/1.1") => "HTTP/1.1",
            Some("HTTP/1.0") => "HTTP/1.0",
            _ => "RTSP/1.0",
        }
    }

    pub fn status(&self) -> u16 {
        self.first_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse().ok())
            .unwrap_or(0)
    }

    pub fn cseq(&self) -> Option<i32> {
        self.headers.get("cseq").and_then(|s| s.parse().ok())
    }

}

/// 增量重组：从裸字节流切出完整 RTSP 消息，残余留在 buf。
// Bounds apply to each message, not to a batch of pipelined messages.
const MAX_RTSP_HEADER: usize = 64 * 1024;
const MAX_RTSP_BODY: usize = 16 * 1024 * 1024;

#[cfg(test)]
pub fn parse_rtsp_messages(buf: &mut Vec<u8>) -> Result<Vec<RtspMessage>, String> {
    parse_rtsp_messages_with(buf, |msg, _| msg)
}

fn parse_rtsp_messages_with<T>(buf: &mut Vec<u8>, mut collect: impl FnMut(RtspMessage, &[u8]) -> T) -> Result<Vec<T>, String> {
    let mut messages = Vec::new();
    loop {
        let head_end = match find_head_separator(buf) {
            Some(idx) => idx,
            None if buf.len() > MAX_RTSP_HEADER => return Err("RTSP header too large".into()),
            None => break,
        };
        if head_end > MAX_RTSP_HEADER { return Err("RTSP header too large".into()); }
        let head_text = String::from_utf8_lossy(&buf[..head_end]).to_string();
        let mut lines = head_text.split("\r\n");
        let first_line = lines.next().unwrap_or("").trim().to_string();
        let mut headers: HashMap<String, String> = HashMap::new();
        for line in lines {
            if let Some((k, v)) = line.split_once(':') {
                let key = k.trim().to_ascii_lowercase();
                let value = v.trim().to_string();
                if key == "content-length" {
                    if let Some(previous) = headers.get(&key) {
                        if previous != &value { return Err("RTSP conflicting Content-Length".into()); }
                    }
                }
                headers.insert(key, value);
            }
        }
        let content_length = match headers.get("content-length") {
            None => 0,
            Some(value) if !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()) =>
                value.parse::<usize>().map_err(|_| "RTSP invalid Content-Length")?,
            Some(_) => return Err("RTSP invalid Content-Length".into()),
        };
        if content_length > MAX_RTSP_BODY { return Err("RTSP body too large".into()); }
        let total = head_end.checked_add(4).and_then(|n| n.checked_add(content_length))
            .ok_or("RTSP message length overflow")?;
        if buf.len() < total {
            break;
        }
        let body = buf[head_end + 4..total].to_vec();
        let message = RtspMessage {
            received_at: std::time::Instant::now(),
            hid_screen_grant: None,
            first_line,
            headers,
            body,
        };
        messages.push(collect(message, &buf[..head_end + 4]));
        buf.drain(..total);
    }
    Ok(messages)
}

fn find_head_separator(buf: &[u8]) -> Option<usize> {
    if buf.len() < 4 {
        return None;
    }
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
}

/// 控制通道：一条 RTSP 连接 + 可选密文帧 + CSeq 派发 + 入向请求应答。
///
/// - `request()`：串行化写、按 CSeq 登记 oneshot、读线程派发（心跳不会被慢应答饿死）。
/// - 入向请求（车机发来的 POST /command 等）：及时应答，非法 `/command` 回 400、未知命令回 501，
///   `/command` 的 body 经 [RtspChannel::inbound] 交给上层分发。
pub struct RtspChannel {
    pub(crate) cluster_display: std::sync::OnceLock<crate::cluster_screen::Display>,
    trace_id: u64,
    event_trace: Option<Arc<EventTrace>>,
    session_end: Arc<SessionEnd>,
    /// 写半部：必须用 tokio Mutex——写+flush 是 await 点，parking_lot 的 guard
    /// 跨 await 会让 future 非 Send，tokio::spawn 拒收（CI 首编实证）。
    write: Arc<tokio::sync::Mutex<OwnedWriteHalf>>,
    cipher: Arc<Mutex<Option<ControlCipher>>>,
    cseq: Arc<Mutex<i32>>,
    pending: PendingMap,
    hud_replies: HudReplies,
    reader: tokio::task::JoinHandle<()>,
    closed: watch::Sender<bool>,
    pub(crate) nbt_recorded: tokio::sync::Mutex<bool>,
    pub(crate) udp_media: crate::udp_keepalive::MediaActivity,
    pub(crate) buffered_ownership: crate::buffered_ownership::BufferedOwnership,
    pub(crate) audio_unborrow_generation: Arc<AtomicU64>,
    pub(crate) audio_unborrow_wake: tokio::sync::Notify,
    pub(crate) buffered_cleanup_pending: AtomicBool,
    pub(crate) buffered_commands: tokio::sync::Mutex<()>,
    session_uri: Arc<std::sync::OnceLock<crate::rtsp_uri::SessionUri>>,
    session_header: Arc<SessionHeader>,
    pub(crate) view_areas: SharedViewAreas,
    pub(crate) id9_trace: std::sync::OnceLock<String>,
    first_screen_cseq: std::sync::OnceLock<i32>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum RtspEndCause { EventClosed, ControlClosed, FeedbackFailed, PeerTeardown, PeerTeardownIncomplete }

#[derive(Clone, Copy, Default, PartialEq, Eq)]
enum SessionEndState {
    #[default]
    Live,
    TeardownPending,
    Ended(RtspEndCause),
}

// Each control connection and its event reader share the first end cause.
// Session termination is owned by the Wi-Fi iAP2 generation's end gate.
#[derive(Default)]
struct SessionEnd {
    uri: Arc<std::sync::OnceLock<crate::rtsp_uri::SessionUri>>,
    session_header: Arc<SessionHeader>,
    state: watch::Sender<SessionEndState>,
}

impl SessionEnd {
    fn matches_teardown(&self, msg: &RtspMessage) -> bool {
        let Some(request) = msg.first_line.strip_prefix("TEARDOWN ") else { return false; };
        // A stream list (even an empty one) is distinct from our zero-body session TEARDOWN.
        if !msg.body.is_empty() || !msg.cseq().is_some_and(|cseq| cseq >= 0) { return false; }
        let mut line = request.split_ascii_whitespace();
        let (Some(target), Some("RTSP/1.0"), None) = (line.next(), line.next(), line.next()) else { return false; };
        let Some(uri) = self.uri.get() else { return false; };
        if target != "/" && uri.as_str() != target { return false; }
        if let Some(raw) = msg.headers.get("session") {
            let received = raw.split(';').next().unwrap_or("").trim();
            return self.session_header.state.lock().id.as_deref() == Some(received);
        }
        true
    }

    fn begin_teardown(&self) {
        self.transition(SessionEndState::Live, SessionEndState::TeardownPending);
    }

    fn record_end(&self, cause: RtspEndCause) {
        self.transition(SessionEndState::Live, SessionEndState::Ended(cause));
    }

    fn finish_teardown(&self, cause: RtspEndCause) {
        self.transition(SessionEndState::TeardownPending, SessionEndState::Ended(cause));
    }

    fn transition(&self, previous: SessionEndState, next: SessionEndState) {
        self.state.send_if_modified(|state| {
            if *state != previous { return false; }
            *state = next;
            true
        });
    }
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum FeedbackFailure { Timeout, Rejected, EmptyServerError }

#[derive(Clone, Copy, PartialEq)]
enum RequestPurpose { Ordinary, SessionSetup, FeedbackRetry(FeedbackFailure) }

impl RtspEndCause {
    fn detail(self) -> &'static str {
        match self {
            Self::EventClosed => "事件通道关闭",
            Self::ControlClosed => "控制通道关闭",
            Self::FeedbackFailed => "控制保活失败",
            Self::PeerTeardown => "车机主动结束",
            Self::PeerTeardownIncomplete => "车机请求结束，应答未完成",
        }
    }
}

#[derive(Default)]
struct SessionHeaderState {
    id: Option<String>,
    // Ordinary transactions already have pending waiters. Remember recent asynchronous
    // writes too, so an unrelated or expired CSeq cannot replace the current identifier.
    async_cseqs: VecDeque<i32>,
}

#[derive(Default)]
struct SessionHeader {
    state: Mutex<SessionHeaderState>,
    mbux: AtomicBool,
}

impl SessionHeader {
    fn observe_response(&self, msg: &RtspMessage, cseq: i32, pending: Option<&PendingReply>, trace_id: u64) {
        let mbux = self.mbux.load(Ordering::Relaxed);
        if !mbux && !pending.is_some_and(|reply| reply.is_setup) { return; }

        let mut state = self.state.lock();
        let async_reply = state.async_cseqs.iter().position(|&seq| seq == cseq)
            .and_then(|pos| state.async_cseqs.remove(pos));
        let Some(raw) = msg.headers.get("session") else { return; };
        let id = raw.split(';').next().unwrap_or("").trim();
        let accepted = (pending.is_some() || async_reply.is_some()) && (200..300).contains(&msg.status())
            && !id.is_empty() && id.bytes().all(|b| b.is_ascii_graphic());
        let changed = accepted && state.id.as_deref() != Some(id);
        if changed { state.id = Some(id.to_owned()); }
        drop(state);

        if mbux {
            crate::emit_log(&format!("MBUX-SESSION RTSP#{trace_id} CSeq={cseq} status={} RX Session={:?} retained={accepted}",
                msg.status(), raw.chars().take(256).collect::<String>()));
        } else if changed {
            crate::emit_log(&format!("RTSP-SESSION RTSP#{trace_id} CSeq={cseq} SETUP status={} carry=enabled", msg.status()));
        }
    }
}

type HudReplies = Arc<Mutex<VecDeque<(i32, HudPostTrace)>>>;

#[derive(Clone, Copy)]
pub(crate) struct HudPostTrace {
    pub link_id: u64,
    pub message_id: u16,
    pub seq: u8,
    pub serial: u64,
}

impl std::fmt::Display for HudPostTrace {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "WIFI-IAP#{} id=0x{:04x} seq={} hud_serial={}",
            self.link_id, self.message_id, self.seq, self.serial)
    }
}
type SharedViewAreas = Arc<std::sync::OnceLock<crate::display_layout::ViewAreas>>;

#[derive(Debug)]
pub(crate) enum RequestError {
    ReplyTimeout(String),
    Other(String),
}

impl From<String> for RequestError {
    fn from(detail: String) -> Self { Self::Other(detail) }
}

impl std::fmt::Display for RequestError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self { Self::ReplyTimeout(detail) | Self::Other(detail) => f.write_str(detail) }
    }
}

// Removal is also required when a request future is cancelled by its caller. A partially
// written encrypted transaction cannot be resumed: its cipher counter has already advanced.
struct TransactionGuard<'a> { channel: &'a RtspChannel, cseq: i32, writing: bool }
impl Drop for TransactionGuard<'_> {
    fn drop(&mut self) {
        self.channel.pending.lock().remove(&self.cseq);
        if self.writing {
            self.channel.record_close("local_write_cancelled");
            self.channel.close();
        }
    }
}

impl RtspChannel {
    pub(crate) fn session_is_live(&self) -> bool {
        !self.is_closed() && *self.session_end.state.borrow() == SessionEndState::Live
    }

    pub(crate) fn end_detail(&self, fallback: RtspEndCause) -> &'static str {
        match *self.session_end.state.borrow() {
            SessionEndState::Ended(cause) => cause.detail(),
            _ => fallback.detail(),
        }
    }

    pub(crate) fn record_feedback_failure(&self) {
        self.session_end.record_end(RtspEndCause::FeedbackFailed);
    }

    pub(crate) fn peer_teardown_pending(&self) -> bool {
        *self.session_end.state.borrow() == SessionEndState::TeardownPending
    }

    pub(crate) async fn wait_for_peer_teardown(&self) -> RtspEndCause {
        let mut end = self.session_end.state.subscribe();
        let is_teardown = |state: &SessionEndState| matches!(state,
            SessionEndState::Ended(RtspEndCause::PeerTeardown | RtspEndCause::PeerTeardownIncomplete));
        let _ = end.wait_for(|state| *state == SessionEndState::TeardownPending || is_teardown(state)).await;
        // The reader normally completes the ACK within one second. Bound the
        // wait even if the reader was cancelled or never reached that write.
        if tokio::time::timeout(Duration::from_secs(2), end.wait_for(is_teardown)).await.is_err() {
            self.session_end.finish_teardown(RtspEndCause::PeerTeardownIncomplete);
        }
        let state = *end.borrow();
        match state {
            SessionEndState::Ended(cause) => cause,
            _ => RtspEndCause::PeerTeardownIncomplete,
        }
    }

    pub(crate) async fn wait_for_control_close(&self) {
        if !self.is_closed() {
            let _ = self.closed.subscribe().wait_for(|closed| *closed).await;
        }
    }

    pub(crate) fn spawn_event(&self, read: OwnedReadHalf, write: OwnedWriteHalf,
        reply: Option<CommandReply>, observer: EventObserver,
    ) -> (Arc<Self>, mpsc::UnboundedReceiver<RtspMessage>) {
        Self::spawn_with_reply(read, write, None, reply, self.view_areas.clone(),
            Some((observer, self.session_end.clone())))
    }

    pub async fn local_addr(&self) -> std::io::Result<std::net::SocketAddr> {
        self.write.lock().await.local_addr()
    }

    /// 建立通道：返回共享句柄 + 入向请求接收器（车机 POST 来的 /command 等；
    /// reader 自动应答并仅分发合法命令，上层只需消费消息体）。
    pub fn spawn(
        read: OwnedReadHalf,
        write: OwnedWriteHalf,
        cipher: Option<ControlCipher>,
    ) -> (Arc<Self>, mpsc::UnboundedReceiver<RtspMessage>) {
        Self::spawn_with_reply(read, write, cipher, None, Default::default(), None)
    }

    fn spawn_with_reply(
        read: OwnedReadHalf, write: OwnedWriteHalf, cipher: Option<ControlCipher>,
        reply: Option<CommandReply>, view_areas: SharedViewAreas,
        event: Option<(EventObserver, Arc<SessionEnd>)>,
    ) -> (Arc<Self>, mpsc::UnboundedReceiver<RtspMessage>) {
        let write = Arc::new(tokio::sync::Mutex::new(write));
        let cipher = Arc::new(Mutex::new(cipher));
        let cseq = Arc::new(Mutex::new(0));
        let pending = Arc::new(Mutex::new(HashMap::new()));
        let hud_replies: HudReplies = Arc::default();
        let (inbound_tx, inbound) = mpsc::unbounded_channel();
        let trace_id = CHANNEL_IDS.fetch_add(1, Ordering::Relaxed);
        let session_uri = Arc::<std::sync::OnceLock<crate::rtsp_uri::SessionUri>>::default();
        let session_header = Arc::new(SessionHeader::default());
        let session_end = event.as_ref().map(|(_, end)| end.clone())
            .unwrap_or_else(|| Arc::new(SessionEnd {
                uri: session_uri.clone(), session_header: session_header.clone(), ..Default::default()
            }));
        let event_trace = event.map(|(observe, _)| {
            observe(trace_id, "open");
            Arc::new(EventTrace { id: trace_id, observe, closed: AtomicBool::new(false) })
        });

        let closed = watch::Sender::new(false);
        let read = reader_loop(
            read,
            write.clone(),
            cipher.clone(),
            pending.clone(),
            hud_replies.clone(),
            inbound_tx,
            trace_id,
            reply,
            session_header.clone(),
            view_areas.clone(),
            event_trace.clone(),
            session_end.clone(),
        );
        let reader_closed = closed.clone();
        let reader = crate::callback_context::spawn(async move {
            read.await;
            reader_closed.send_replace(true);
        });

        let this = Arc::new(Self {
            trace_id,
            event_trace,
            session_end,
            write,
            cipher,
            cseq,
            pending,
            hud_replies,
            reader,
            closed,
            nbt_recorded: tokio::sync::Mutex::new(false),
            udp_media: Default::default(),
            buffered_ownership: Default::default(),
            audio_unborrow_generation: Default::default(),
            audio_unborrow_wake: Default::default(),
            buffered_cleanup_pending: AtomicBool::new(false),
            buffered_commands: Default::default(),
            session_uri,
            session_header,
            view_areas,
            cluster_display: Default::default(),
            id9_trace: std::sync::OnceLock::new(),
            first_screen_cseq: std::sync::OnceLock::new(),
        });
        (this, inbound)
    }

    /// pair-verify 完成后切换到密文帧（读写计数从 0 开始）。
    pub fn set_cipher(&self, cipher: ControlCipher) {
        *self.cipher.lock() = Some(cipher);
        crate::emit_log(&format!("RTSP#{} ENCRYPT enabled（不记录密钥）", self.trace_id));
    }

    pub fn trace_id(&self) -> u64 { self.trace_id }
    pub(crate) fn enable_mbux_session_header(&self) {
        self.session_header.mbux.store(true, Ordering::Relaxed);
    }

    fn session_header_line(&self) -> String {
        self.session_header.state.lock().id.as_deref()
            .map(|id| format!("Session: {id}\r\n")).unwrap_or_default()
    }
    pub(crate) fn set_session_uri(&self,uri:crate::rtsp_uri::SessionUri)->Result<(),String> {
        self.session_uri.set(uri).map_err(|_|"session URI already belongs to this control channel".into())
    }

    pub(crate) fn absolute_session_uri(&self) -> Option<&str> {
        self.session_uri.get().map(crate::rtsp_uri::SessionUri::as_str)
    }

    pub fn next_cseq(&self) -> i32 {
        let mut c = self.cseq.lock();
        *c += 1;
        *c
    }

    /// 发起一次 RTSP 事务；[extra_headers] 为 (名, 值) 追加头。
    pub async fn request(
        &self,
        method: &str,
        path: &str,
        body: &[u8],
        content_type: &str,
        extra_headers: &[(&str, String)],
        timeout: Duration,
    ) -> Result<RtspMessage, String> {
        self.request_with_write_signal(method, path, body, content_type, extra_headers, timeout, None).await
    }

    pub(crate) async fn request_with_write_signal(
        &self, method: &str, path: &str, body: &[u8], content_type: &str,
        extra_headers: &[(&str, String)], timeout: Duration, written: Option<oneshot::Sender<()>>,
    ) -> Result<RtspMessage, String> {
        let purpose = if method == "SETUP" && path == "/setup" { RequestPurpose::SessionSetup } else { RequestPurpose::Ordinary };
        let path=self.session_uri.get().map(|uri|uri.resolve(method,path)).unwrap_or(path);
        self.request_resolved(method, path, body, content_type, extra_headers, timeout, written, purpose).await
            .map_err(|error| error.to_string())
    }

    pub(crate) async fn request_feedback(&self, previous_failure: Option<FeedbackFailure>) -> Result<RtspMessage, RequestError> {
        let purpose = previous_failure.map_or(RequestPurpose::Ordinary, RequestPurpose::FeedbackRetry);
        self.request_resolved("POST", "/feedback", &[], "application/octet-stream", &[],
            Duration::from_secs(3), None, purpose).await
    }

    pub(crate) async fn screen_setup(&self, body: &[u8]) -> Result<RtspMessage, String> {
        let path = self.session_uri.get().map_or("/", |uri| uri.as_str());
        self.request_resolved("SETUP", path, body, "application/x-apple-binary-plist", &[],
            Duration::from_secs(8), None, RequestPurpose::Ordinary).await.map_err(|error| error.to_string())
    }

    async fn request_resolved(&self, method: &str, path: &str, body: &[u8], content_type: &str,
        extra_headers: &[(&str, String)], timeout: Duration, written: Option<oneshot::Sender<()>>,
        purpose: RequestPurpose) -> Result<RtspMessage, RequestError> {
        let feedback = path == "/feedback";
        if self.is_closed() {
            if feedback {
                crate::emit_media_trace(crate::timing::monotonic_nanos() / 1_000_000,
                    "feedback_error", &format!("RTSP#{} CSeq=NA control_closed_before_queue", self.trace_id));
            }
            return Err(RequestError::Other("控制通道已关闭".into()));
        }
        let deadline = tokio::time::Instant::now() + timeout;
        let cseq = self.next_cseq();
        if feedback {
            crate::emit_media_trace(crate::timing::monotonic_nanos() / 1_000_000, "feedback_queued",
                &format!("RTSP#{} CSeq={cseq} afterRejection={} afterTimeout={}", self.trace_id,
                    matches!(purpose, RequestPurpose::FeedbackRetry(FeedbackFailure::Rejected | FeedbackFailure::EmptyServerError)),
                    purpose == RequestPurpose::FeedbackRetry(FeedbackFailure::Timeout)));
        }
        let result = self.request_transaction(method, path, body, content_type, extra_headers,
            timeout, written, purpose, deadline, cseq).await;
        if feedback {
            let (event, detail) = match &result {
                Ok(reply) if reply.status() == 200 => ("feedback_ok", "status=200".to_owned()),
                Ok(reply) => ("feedback_error", format!("status={}", reply.status())),
                Err(RequestError::ReplyTimeout(detail)) => ("feedback_timeout", detail.clone()),
                Err(error) => ("feedback_error", error.to_string()),
            };
            crate::emit_media_trace(crate::timing::monotonic_nanos() / 1_000_000, event,
                &format!("RTSP#{} CSeq={cseq} {detail}", self.trace_id));
        }
        result
    }

    async fn request_transaction(&self, method: &str, path: &str, body: &[u8], content_type: &str,
        extra_headers: &[(&str, String)], timeout: Duration, written: Option<oneshot::Sender<()>>,
        purpose: RequestPurpose, deadline: tokio::time::Instant, cseq: i32) -> Result<RtspMessage, RequestError> {
        let started = std::time::Instant::now();
        let trace = path != "/feedback";
        let kind = crate::resource_trace::trace_kind(method, path, body);
        let id9_kind = self.id9_trace.get().and_then(|session| {
            let stage = match kind {
                _ if purpose == RequestPurpose::SessionSetup => "SESSION_SETUP",
                Some("RESOURCE") => "RESOURCE",
                Some("SCREEN_SETUP") if *self.first_screen_cseq.get_or_init(|| cseq) == cseq => "FIRST_SCREEN_SETUP",
                _ => return None,
            };
            Some(format!("{session} {stage}"))
        });
        let diagnostic_kind = id9_kind.as_deref().or(kind);
        if trace {
            let encrypted = self.cipher.lock().is_some();
            crate::emit_log(&format!("RTSP#{} TXQ CSeq={cseq} {method} {path} body={}B encrypted={} timeout={}ms", self.trace_id, body.len(), encrypted, timeout.as_millis()));
        }
        let (tx, rx) = oneshot::channel();
        let mut transaction = TransactionGuard { channel: self, cseq, writing: false };

        // Snapshot after taking the writer so queued requests can use the latest SETUP reply.
        let mut w = tokio::time::timeout_at(deadline, self.write.lock()).await
            .map_err(|_| format!("RTSP 等待写锁超时 CSeq={cseq} {method} {path}"))?;
        if self.is_closed() { return Err(RequestError::Other("控制通道已关闭".into())); }
        let session_header = if extra_headers.iter().any(|(key,_)| key.eq_ignore_ascii_case("session")) {
            String::new()
        } else { self.session_header_line() };
        let content_header = if content_type.is_empty() { String::new() }
            else { format!("Content-Type: {content_type}\r\n") };
        let head = format!(
            "{method} {path} RTSP/1.0\r\n\
             CSeq: {cseq}\r\n\
             User-Agent: AirPlay/770.8.1\r\n\
             {content_header}Content-Length: {}\r\n\
             {session_header}{extra}\r\n",
            body.len(),
            extra = extra_headers
                .iter()
                .map(|(k, v)| format!("{k}: {v}\r\n"))
                .collect::<String>(),
        );
        let mut plaintext = Vec::with_capacity(head.len() + body.len());
        plaintext.extend_from_slice(head.as_bytes());
        plaintext.extend_from_slice(body);
        if let Some(kind) = &id9_kind {
            crate::resource_trace::log_head(&format!("{kind} RTSP#{} CSeq={cseq} TX_HEAD_TEXT", self.trace_id), head.as_bytes());
        }
        if let Some(kind) = diagnostic_kind {
            for line in crate::resource_trace::outbound(kind, self.trace_id, cseq, &plaintext, body) {
                crate::emit_log(&line);
            }
        }

        {
            self.pending.lock().insert(cseq, PendingReply { tx, trace_head: id9_kind.is_some(), is_setup: method == "SETUP" });
            transaction.writing = true;
            // 心跳与 RECORD 可并发等待应答；加密 nonce 的分配顺序必须等于上线路序。
            let wire = {
                let mut guard = self.cipher.lock();
                match guard.as_mut() {
                    Some(cipher) => cipher.encrypt(&plaintext),
                    None => plaintext,
                }
            };
            let written = tokio::time::timeout_at(deadline, async {
                w.write_all(&wire).await?;
                w.flush().await
            }).await.map_err(|_| {
                self.record_close("write_timeout");
                format!("RTSP 发送超时 CSeq={cseq} {method} {path}")
            })?;
            if let Err(error) = written {
                if self.event_trace.is_some() { self.record_close(&io_close_reason("write_error", &error)); }
                let detail = format!("RTSP#{} CSeq={cseq} {method} {path} 写失败: {error}", self.trace_id);
                crate::emit_log(&detail);
                return Err(detail.into());
            }
            transaction.writing = false;
        }
        drop(w);
        if let Some(written) = written { let _ = written.send(()); }

        if matches!(kind, Some("AUDIO_SETUP" | "MEDIA_SETUP")) {
            for line in crate::resource_trace::media_setup_written_latency(self.trace_id, cseq, body) {
                crate::emit_log(&line);
            }
        }
        if let Some(kind) = diagnostic_kind {
            crate::emit_log(&format!("{kind} RTSP#{} CSeq={cseq} TX_WRITTEN bytes={} elapsed={}ms; socket write is not receiver acceptance",
                self.trace_id, head.len() + body.len(), started.elapsed().as_millis()));
        }
        let result = match tokio::time::timeout_at(deadline, rx).await {
            Ok(Ok((msg, raw_head))) => {
                if self.session_header.mbux.load(Ordering::Relaxed) && trace && !msg.headers.contains_key("session") {
                    crate::emit_log(&format!("MBUX-SESSION RTSP#{} CSeq={cseq} {method} {path} status={} RX Session=absent TX Session={:?}",
                        self.trace_id, msg.status(), session_header.trim_end().strip_prefix("Session: ")));
                }
                if let (Some(kind), Some(head)) = (&id9_kind, raw_head) {
                    let prefix = format!("{kind} RTSP#{} CSeq={cseq} RX_HEAD", self.trace_id);
                    crate::resource_trace::log_head(&format!("{prefix}_TEXT"), &head);
                    for line in crate::resource_trace::bytes_lines(&prefix, &head) { crate::emit_log(&line); }
                }
                Ok(msg)
            }
            Ok(Err(_)) => {
                Err(format!("控制通道已关闭（等待 CSeq={cseq} {method} {path}）").into())
            }
            Err(_) => {
                Err(RequestError::ReplyTimeout(format!("RTSP 超时（{timeout:?}，CSeq={cseq} {method} {path}）")))
            }
        };
        if let RequestPurpose::FeedbackRetry(previous) = purpose {
            let transport_confirmed = result.as_ref().is_ok_and(|reply| reply.status() == 200 ||
                (previous == FeedbackFailure::EmptyServerError && reply.is_empty_server_error()));
            if !transport_confirmed {
                self.record_feedback_failure();
            }
        }
        if path == "/info" || method == "RECORD" {
            if let Some(trace) = self.id9_trace.get() {
                let outcome = match &result {
                    Ok(message) => format!("status={}", message.status()),
                    Err(error) => format!("error={:?}", error.to_string()),
                };
                crate::emit_log(&format!("{trace} RTSP#{} CSeq={cseq} {method} {path} {outcome}", self.trace_id));
            }
        }
        match &result {
            Ok(message) if trace || message.status() != 200 => crate::emit_log(&format!("RTSP#{} RX CSeq={cseq} status={} body={}B elapsed={}ms", self.trace_id, message.status(), message.body.len(), started.elapsed().as_millis())),
            Err(error) => crate::emit_log(&format!("RTSP#{} ERROR CSeq={cseq} elapsed={}ms {error}", self.trace_id, started.elapsed().as_millis())),
            _ => {}
        }
        if let Some(kind) = diagnostic_kind {
            match &result {
                Ok(message) => for line in crate::resource_trace::response(kind, self.trace_id, cseq, message) {
                    crate::emit_log(&line);
                },
                Err(error) => crate::emit_log(&format!("{kind} RTSP#{} CSeq={cseq} RX_NONE rtsp_status=unavailable business_status=unavailable error={:?}; no rejection code received", self.trace_id, error.to_string())),
            }
        }
        result
    }

    /// 事件通道 fire-and-forget：POST /command（不等应答，DiPlay 同款）。
    pub async fn post_fire_and_forget(
        &self,
        path: &str,
        body: &[u8],
        content_type: &str,
    ) -> Result<i32, String> {
        self.post_traced(path, body, content_type, None).await
    }

    pub(crate) async fn post_traced(
        &self, path: &str, body: &[u8], content_type: &str, hud: Option<HudPostTrace>,
    ) -> Result<i32, String> {
        self.post_traced_if(path, body, content_type, hud, || true).await
            .map(|cseq| cseq.expect("unconditional RTSP write"))
    }

    pub(crate) async fn post_traced_if(
        &self, path: &str, body: &[u8], content_type: &str, hud: Option<HudPostTrace>,
        should_write: impl FnOnce() -> bool,
    ) -> Result<Option<i32>, String> {
        if self.is_closed() { return Err("控制通道已关闭".into()); }
        let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
        let cseq = self.next_cseq();
        let mut transaction = TransactionGuard { channel: self, cseq, writing: false };
        let mut w = tokio::time::timeout_at(deadline, self.write.lock()).await
            .map_err(|_| "事件通道等待写锁超时".to_string())?;
        if self.is_closed() { return Err("控制通道已关闭".into()); }
        if !should_write() { return Ok(None); }
        // Register before writing so a reply racing flush still has its CSeq metadata.
        if let Some(hud) = hud {
            let mut replies = self.hud_replies.lock();
            if replies.len() >= 256 { replies.pop_front(); }
            replies.push_back((cseq, hud));
        }
        if self.session_header.mbux.load(Ordering::Relaxed) {
            let mut state = self.session_header.state.lock();
            if state.async_cseqs.len() >= 128 { state.async_cseqs.pop_front(); }
            state.async_cseqs.push_back(cseq);
        }
        let session_header = self.session_header_line();
        let head = format!(
            "POST {path} RTSP/1.0\r\n\
             Content-Type: {content_type}\r\n\
             Content-Length: {}\r\n\
             CSeq: {cseq}\r\n{session_header}\r\n",
            body.len()
        );
        let mut plaintext = Vec::with_capacity(head.len() + body.len());
        plaintext.extend_from_slice(head.as_bytes());
        plaintext.extend_from_slice(body);
        transaction.writing = true;
        let wire = {
            let mut guard = self.cipher.lock();
            match guard.as_mut() {
                Some(cipher) => cipher.encrypt(&plaintext),
                None => plaintext,
            }
        };
        tokio::time::timeout_at(deadline, async {
            w.write_all(&wire).await?;
            w.flush().await
        }).await.map_err(|_| { self.record_close("write_timeout"); "事件通道发送超时".to_string() })?
            .map_err(|e| {
                if self.event_trace.is_some() { self.record_close(&io_close_reason("write_error", &e)); }
                format!("事件通道写失败: {e}")
            })?;
        transaction.writing = false;
        if let Some(hud) = hud {
            crate::emit_log(&format!("HUD {hud} RTSP#{} CSeq={cseq} written", self.trace_id));
        }
        Ok(Some(cseq))
    }

    fn record_close(&self, cause: &str) {
        if let Some(trace) = &self.event_trace { trace.close(cause); }
    }

    pub fn close(&self) {
        self.record_close("local_close");
        self.closed.send_replace(true);
        self.reader.abort();
        self.pending.lock().clear();
    }

    pub fn is_closed(&self) -> bool { *self.closed.borrow() || self.reader.is_finished() }

    pub async fn shutdown(&self) {
        self.close();
        let _ = self.write.lock().await.shutdown().await;
    }
}

impl Drop for RtspChannel {
    fn drop(&mut self) {
        self.record_close("local_drop");
        self.reader.abort();
    }
}

pub type CommandReply = Arc<dyn Fn(&mut RtspMessage) -> Option<Vec<u8>> + Send + Sync>;
pub type EventObserver = Arc<dyn Fn(u64, &str) + Send + Sync>;
struct EventTrace {
    id: u64,
    observe: EventObserver,
    closed: AtomicBool,
}
impl EventTrace {
    fn close(&self, cause: &str) {
        if !self.closed.swap(true, Ordering::AcqRel) { (self.observe)(self.id, cause); }
    }
}

fn io_close_reason(operation: &str, error: &std::io::Error) -> String {
    format!("{operation} kind={:?} errno={:?}", error.kind(), error.raw_os_error())
}

type SharedWrite = Arc<tokio::sync::Mutex<OwnedWriteHalf>>;
type SharedCipher = Arc<Mutex<Option<ControlCipher>>>;
struct PendingReply {
    tx: oneshot::Sender<(RtspMessage, Option<Vec<u8>>)>,
    trace_head: bool,
    is_setup: bool,
}
type PendingMap = Arc<Mutex<HashMap<i32, PendingReply>>>;

async fn reader_loop(
    mut read: OwnedReadHalf,
    write: SharedWrite,
    cipher: SharedCipher,
    pending: PendingMap,
    hud_replies: HudReplies,
    inbound_tx: mpsc::UnboundedSender<RtspMessage>,
    trace_id: u64,
    reply: Option<CommandReply>,
    session_header: Arc<SessionHeader>,
    view_areas: SharedViewAreas,
    event_trace: Option<Arc<EventTrace>>,
    session_end: Arc<SessionEnd>,
) {
    let mut raw: Vec<u8> = Vec::with_capacity(8192);
    let mut plain: Vec<u8> = Vec::with_capacity(8192);
    let mut chunk = [0u8; 4096];
    let mut unsolicited_responses = 0u64;
    let mut inbound_requests = 0u64;
    let mut rejected_requests = 0u64;
    let mut accepted_trace = 0u64;
    let mut rejected_trace = 0u64;
    loop {
        let n = match read.read(&mut chunk).await {
            Ok(0) => {
                if let Some(trace) = &event_trace {
                    // Capture read order before diagnostics or queued event dispatch can yield.
                    session_end.record_end(RtspEndCause::EventClosed);
                    trace.close("peer_eof");
                }
                crate::emit_log(&format!("RTSP#{trace_id}: 对端 EOF，未完成事务={}，残留密文={}B 明文={}B", pending.lock().len(), raw.len(), plain.len()));
                break;
            }
            Ok(n) => n,
            Err(error) => {
                if let Some(trace) = &event_trace { trace.close(&io_close_reason("read_error", &error)); }
                crate::emit_log(&format!("RTSP#{trace_id}: 读取失败：{error}，未完成事务={}", pending.lock().len()));
                break;
            }
        };
        raw.extend_from_slice(&chunk[..n]);

        // 1) 若通道已加密，把所有完整密文帧解到 plain
        let decrypted = {
            let mut guard = cipher.lock();
            match guard.as_mut() {
                Some(c) => match c.decrypt(&raw) {
                    Ok((p, consumed)) => {
                        raw.drain(..consumed);
                        Some(p)
                    }
                    Err(e) => {
                        if let Some(trace) = &event_trace { trace.close("decrypt_error"); }
                        log::error!("EVENT/CTRL: {e}——关闭通道");
                        crate::emit_log(&format!("RTSP#{trace_id}: 本地解密失败：{e}，关闭通道，未完成事务={}", pending.lock().len()));
                        break;
                    }
                },
                None => None,
            }
        };
        let stream: &mut Vec<u8> = if let Some(p) = decrypted {
            plain.extend_from_slice(&p);
            &mut plain
        } else {
            &mut raw
        };

        // 2) 切出完整 RTSP 消息并派发
        let messages = match parse_rtsp_messages_with(stream, |message, head| {
            let teardown = session_end.matches_teardown(&message);
            let capture = message.is_response() && message.cseq().is_some_and(|cseq|
                pending.lock().get(&cseq).is_some_and(|p| p.trace_head));
            (message, capture.then(|| head.to_vec()), teardown)
        }) {
            Ok(messages) => messages,
            Err(error) => {
                if let Some(trace) = &event_trace { trace.close(&format!("invalid_framing: {error}")); }
                crate::emit_log(&format!("RTSP#{trace_id}: invalid framing: {error}; closing reader"));
                break;
            }
        };
        // Publish the pending end only for a batch we can dispatch. Before waking
        // any response waiter, pause media recovery until TEARDOWN is acknowledged.
        if messages.iter().any(|(_, _, teardown)| *teardown) {
            session_end.begin_teardown();
        }
        for (mut msg, raw_head, teardown) in messages {
            if msg.is_response() {
                if let Some(c) = msg.cseq() {
                    let reply = pending.lock().remove(&c);
                    session_header.observe_response(&msg, c, reply.as_ref(), trace_id);
                    if let Some(reply) = reply {
                        let _ = reply.tx.send((msg, raw_head));
                    } else {
                        // iAP output intentionally does not await RTSP replies; still expose rejection.
                        unsolicited_responses += 1;
                        let business_status = crate::bplist::parse(&msg.body).ok()
                            .and_then(|v| v.get("status").and_then(crate::bplist::Value::as_int));
                        let hud = {
                            let mut replies = hud_replies.lock();
                            replies.iter().position(|(seq, _)| *seq == c)
                                .and_then(|index| replies.remove(index)).map(|(_, trace)| trace)
                        };
                        if let Some(hud) = hud {
                            crate::emit_log(&format!("HUD {hud} RTSP#{trace_id} CSeq={c} response status={} business_status={business_status:?} body={}B",
                                msg.status(), msg.body.len()));
                        } else if msg.status() != 200 || business_status.is_some_and(|s|s != 0) || unsolicited_responses <= 128 {
                            crate::emit_log(&format!("RTSP#{trace_id} async RX CSeq={c} status={} business_status={business_status:?} body={}B; link ACK separate", msg.status(), msg.body.len()));
                        }
                    }
                }
            } else {
                let cseq = msg.cseq();
                let protocol = msg.response_protocol();
                let validation = validate_inbound_command(&msg, view_areas.get());
                inbound_requests += 1;
                if validation.is_err() { rejected_requests += 1; }
                let trace_count=if validation.is_err() { &mut rejected_trace } else { &mut accepted_trace };
                if *trace_count < 32 {
                    *trace_count += 1;
                    let status=validation.as_ref().err().map(|v|v.0).unwrap_or(200);
                    crate::emit_log(&format!("RTSP#{trace_id} IN request={inbound_requests} {} CSeq={cseq:?} validation_status={status}; pre-dispatch, response write separate",inbound_command_metadata(&msg)));
                }
                if let Err((status, reason)) = validation {
                    if let Err(error) = respond(&write, &cipher, protocol, cseq, status, reason, &[]).await {
                        crate::emit_log(&format!("RTSP#{trace_id}: rejection write failed CSeq={cseq:?} status={status}: {error}"));
                    }
                    continue;
                }
                if teardown || session_end.matches_teardown(&msg) {
                    session_end.begin_teardown();
                    let cause = match tokio::time::timeout(Duration::from_secs(1), respond_ok(&write, &cipher, protocol, cseq, &[])).await {
                        Ok(Ok(())) => {
                            crate::emit_log(&format!("RTSP#{trace_id}: peer TEARDOWN ACK written CSeq={cseq:?} status=200"));
                            let _ = inbound_tx.send(msg);
                            RtspEndCause::PeerTeardown
                        }
                        Ok(Err(error)) => {
                            crate::emit_log(&format!("RTSP#{trace_id}: peer TEARDOWN ACK failed CSeq={cseq:?}: {error}"));
                            RtspEndCause::PeerTeardownIncomplete
                        }
                        Err(_) => {
                            crate::emit_log(&format!("RTSP#{trace_id}: peer TEARDOWN ACK timeout CSeq={cseq:?}"));
                            RtspEndCause::PeerTeardownIncomplete
                        }
                    };
                    session_end.finish_teardown(cause);
                    continue;
                }
                let body = reply.as_ref().and_then(|f| f(&mut msg)).unwrap_or_default();
                let ui_type = if msg.first_line.contains("/command") {
                    crate::bplist::parse(&msg.body).ok().and_then(|v| match v.get("type").and_then(crate::bplist::Value::as_str) {
                        Some("requestUI") => Some("requestUI"),
                        Some("changeUIContext") => Some("changeUIContext"),
                        _ => None,
                    })
                } else { None };
                if let Some(ui_type) = ui_type {
                    // Finish the UI command's acknowledgement before feedback can be scheduled.
                    // iAP and touch keep their existing immediate dispatch path below.
                    match respond_ok(&write, &cipher, protocol, cseq, &body).await {
                        Ok(()) => {
                            crate::emit_log(&format!("RTSP#{trace_id}: UI response TCP written type={ui_type} CSeq={cseq:?} status=200 body={}B",body.len()));
                            let _ = inbound_tx.send(msg);
                        }
                        Err(error) => crate::emit_log(&format!("RTSP#{trace_id}: UI response failed type={ui_type} CSeq={cseq:?}: {error}")),
                    }
                } else if body.is_empty() {
                    // Touch/iAP delivery remains immediate; it must not wait for a socket write.
                    let _ = inbound_tx.send(msg);
                    if let Err(error)=respond_ok(&write, &cipher, protocol, cseq, &body).await {
                        crate::emit_log(&format!("RTSP#{trace_id}: response write failed CSeq={cseq:?}: {error}"));
                    }
                } else {
                    // Complete changeModes' business reply before dispatch can schedule the next
                    // modesChanged. CSeq/status are metadata; never dump private command bodies.
                    match respond_ok(&write, &cipher, protocol, cseq, &body).await {
                        Ok(()) => crate::emit_log(&format!("RTSP#{trace_id}: business response written CSeq={cseq:?} body={}B",body.len())),
                        Err(error) => crate::emit_log(&format!("RTSP#{trace_id}: business response failed CSeq={cseq:?}: {error}")),
                    }
                    let _ = inbound_tx.send(msg);
                }
            }
        }
    }
    // 读循环退出：唤醒所有等待者
    if event_trace.is_some() { session_end.record_end(RtspEndCause::EventClosed); }
    crate::emit_log(&format!("RTSP#{trace_id} IN summary requests={inbound_requests} rejected={rejected_requests} accepted_samples={accepted_trace}/32 rejected_samples={rejected_trace}/32; iAP delivery counted separately"));
    pending.lock().clear();
}

fn inbound_command_metadata(message: &RtspMessage) -> String {
    let mut line=message.first_line.split_whitespace();
    let method=match line.next() {Some("POST")=>"POST",Some("GET")=>"GET",Some("OPTIONS")=>"OPTIONS",_=>"other"};
    let target=if line.next()==Some("/command") {"command"}else{"other"};
    let value=crate::bplist::parse(&message.body).ok();
    let kind=value.as_ref().and_then(|v|v.get("type")).and_then(crate::bplist::Value::as_str)
        .filter(|s|!s.is_empty() && s.len()<=48 && s.bytes().all(|b|b.is_ascii_alphanumeric() || b==b'_' || b==b'-')).unwrap_or("unparsed_or_nonstandard");
    let iap_bytes=value.as_ref().and_then(crate::wifi_iap2::command_data).map(|data|data.len());
    format!("method={method} target={target} protocol={} type={kind} body={}B iap_bytes={iap_bytes:?}",message.response_protocol(),message.body.len())
}

#[cfg(test)]
mod ingress_metadata_tests {
    use super::*;
    use crate::bplist::{encode,Value};

    #[test]
    fn rejected_and_accepted_commands_have_metadata_without_private_bodies_or_urls() {
        for (kind,expected) in [("futureCommand",501),("iAPSendMessage",200)] {
            let message=RtspMessage {
                received_at: std::time::Instant::now(),
                hid_screen_grant: None,
                first_line:"POST /command RTSP/1.0".into(),headers:HashMap::new(),
                body:encode(&Value::dict(vec![("type",Value::str(kind)),("params",Value::dict(vec![
                    ("data",Value::Data(b"PRIVATE_PAYLOAD".to_vec())),("url",Value::str("scheme:PRIVATE_URL")),
                ]))])),
            };
            let validation=validate_inbound_command(&message, None);
            assert_eq!(validation.err().map(|v|v.0).unwrap_or(200),expected);
            let note=inbound_command_metadata(&message);
            assert!(note.contains(&format!("type={kind}")));
            assert!(note.contains("target=command"));
            assert!(!note.contains("PRIVATE"));
            assert!(!note.contains("scheme:"));
        }
        let message=RtspMessage {received_at: std::time::Instant::now(),hid_screen_grant:None,first_line:"POST /PRIVATE_URL?secret=PRIVATE RTSP/1.0".into(),headers:HashMap::new(),body:b"PRIVATE_BODY".to_vec()};
        let note=inbound_command_metadata(&message);
        assert!(note.contains("target=other"));
        assert!(!note.contains("PRIVATE"));
    }
}

// These branches in the reference firmware only observe parameters and return empty 200.
// They do not grant screen/audio resources, start authentication or launch an arbitrary URL.
pub(crate) fn is_box_observation_command(kind: &str) -> bool {
    matches!(kind,"showUI"|"stopUI"|"setLimitedUI"|"updateDisplayPanels"|"accessoryGiveFocus")
}

// Validate only the command endpoint; other RTSP methods retain their existing behavior.
// Known no-op notifications remain accepted. Parameter rules follow our actual consumers,
// including both HID layouts and optional device UUID, rather than a stricter peer schema.
fn validate_inbound_command<'a>(message: &RtspMessage, areas: Option<&'a crate::display_layout::ViewAreas>)
    -> Result<Option<&'a crate::display_layout::Layout>, (u16, &'static str)> {
    use crate::bplist::Value;
    let mut line = message.first_line.split_whitespace();
    if line.next() != Some("POST") || line.next() != Some("/command") { return Ok(None); }
    let bad = (400, "Bad Request");
    let command = crate::bplist::parse(&message.body).map_err(|_| bad)?;
    let kind = command.get("type").and_then(Value::as_str).filter(|s| !s.is_empty()).ok_or(bad)?;
    let field = |name: &str| command.get(name).or_else(|| command.get("params").and_then(|p| p.get(name)));
    let valid = match kind {
        "updateViewArea" => {
            return crate::display_layout::match_update(areas, command.get("params"), crate::emit_log)
                .map(Some).map_err(|_| if areas.is_none() { (501, "Not Implemented") } else { bad });
        }
        "iAPSendMessage" => crate::wifi_iap2::command_data(&command).is_some(),
        kind if is_box_observation_command(kind) => command.get("params").is_none_or(|p|matches!(p,Value::Dict(_))),
        "hidSendReport" => {
            let report = command.get("hidReport").and_then(Value::as_bytes)
                .or_else(|| command.get("params").and_then(|p| p.get("report")).and_then(Value::as_bytes));
            report.is_some_and(|r| !r.is_empty()) && field("uuid").is_none_or(|id| id.as_str().is_some() || id.as_int().is_some_and(|n| n >= 0))
        }
        "requestSiri" => matches!(command.get("params").and_then(|p| p.get("siriAction")).or_else(|| command.get("siriAction")).and_then(Value::as_int), Some(1..=3)),
        "setNightMode" => {
            // The consumer tries the decoded top-level value, then params; an invalid
            // top-level type must not mask a valid nested night mode.
            let boolean = |v: &Value| v.as_bool().or_else(|| v.as_int().map(|n| n != 0));
            command.get("nightMode").and_then(boolean)
                .or_else(|| command.get("params").and_then(|p| p.get("nightMode")).and_then(boolean)).is_some()
        }
        "requestUI" | "changeUIContext" | "changeModes" | "forceKeyFrame" |
        "hidSetInputMode" | "ping" | "fps" => true,
        _ => return Err((501, "Not Implemented")),
    };
    if valid { Ok(None) } else { Err(bad) }
}

async fn respond_ok(write: &SharedWrite, cipher: &SharedCipher, protocol: &str, cseq: Option<i32>, body: &[u8]) -> std::io::Result<()> {
    respond(write, cipher, protocol, cseq, 200, "OK", body).await
}

async fn respond(write: &SharedWrite, cipher: &SharedCipher, protocol: &str, cseq: Option<i32>, status: u16, reason: &str, body: &[u8]) -> std::io::Result<()> {
    let mut head = format!("{protocol} {status} {reason}\r\nContent-Length: {}\r\n", body.len());
    if let Some(c) = cseq { head.push_str(&format!("CSeq: {c}\r\n")); }
    if !body.is_empty() { head.push_str("Content-Type: application/x-apple-binary-plist\r\n"); }
    head.push_str("\r\n");
    let mut plaintext = head.into_bytes();
    plaintext.extend_from_slice(body);
    let mut w = write.lock().await;
    let wire = {
        let mut guard = cipher.lock();
        match guard.as_mut() {
            Some(c) => c.encrypt(&plaintext),
            None => plaintext,
        }
    };
    w.write_all(&wire).await?;
    w.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn view_area_event_resolves_only_this_displays_declared_index() {
        use crate::bplist::{encode, Value};
        use crate::display_layout::{Rect, ViewAreas};
        let rect = |x, w| Value::dict(vec![("originXPixels", Value::int(x)),
            ("widthPixels", Value::int(w)), ("heightPixels", Value::int(400))]);
        let cropped = Value::dict(vec![("widthPixels", Value::int(700)), ("heightPixels", Value::int(450)),
            ("safeArea", rect(20, 650))]);
        let info = Value::dict(vec![("viewAreas", Value::array(vec![rect(0, 800), cropped]))]);
        let areas = ViewAreas::new(&info, 800, 480, Some("Main"), true, false);
        let event = |uuid, index| RtspMessage { received_at: std::time::Instant::now(), hid_screen_grant: None, first_line: "POST /command RTSP/1.0".into(),
            headers: HashMap::new(), body: encode(&Value::dict(vec![("type", Value::str("updateViewArea")),
                ("params", Value::dict(vec![("uuid", Value::str(uuid)), ("viewAreaIndex", Value::int(index))]))])) };
        let layout = validate_inbound_command(&event("main", 1), Some(&areas)).unwrap().unwrap();
        assert_eq!(layout.safe, Rect { x: 20, y: 0, w: 650, h: 400 });
        for invalid in [event("aux", 1), event("Main", 2), event("Main", -1)] {
            assert_eq!(validate_inbound_command(&invalid, Some(&areas)), Err((400, "Bad Request")));
        }
        assert_eq!(validate_inbound_command(&event("Main", 0), None), Err((501, "Not Implemented")));
        let missing_params = RtspMessage { body: encode(&Value::dict(vec![("type", Value::str("updateViewArea"))])),
            ..event("Main", 0) };
        assert_eq!(validate_inbound_command(&missing_params, Some(&areas)), Err((400, "Bad Request")));
    }

    async fn box_startup_sequence(kinds: &[&str]) {
        use crate::bplist::{encode,Value};
        for (protocol,encrypted) in [("RTSP/1.0",false),("HTTP/1.1",true)] {
            let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut car=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (phone,_)=listener.accept().await.unwrap();let(r,w)=phone.into_split();
            let modes=Arc::new(Mutex::new(crate::modes::Modes::from_info(&Value::dict(vec![]),true,true)));
            let before=modes.lock().diagnostics();
            let reply_modes=modes.clone();
            let reply=Arc::new(move |request: &mut RtspMessage| {
                let command=crate::bplist::parse(&request.body).ok()?;
                reply_modes.lock().response(&command, &Default::default()).map(|reply| reply.body)
            });
            let (_channel,mut inbound)=RtspChannel::spawn_with_reply(r,w,encrypted.then(||ControlCipher::new([1;32],[2;32])),Some(reply),Default::default(),None);
            let mut peer_cipher=ControlCipher::new([2;32],[1;32]);
            for (index,kind) in kinds.iter().chain(std::iter::once(&"iAPSendMessage")).enumerate() {
                let last=index==kinds.len();
                let data=if last {vec![0xff,0x55,2,0,0xee,0x10]}else{vec![]};
                let params=if *kind=="iAPSendMessage" {Value::dict(vec![("data",Value::Data(data.clone()))])}else{Value::dict(vec![])};
                let body=encode(&Value::dict(vec![("type",Value::str(kind)),("params",params)]));
                let mut request=format!("POST /command {protocol}\r\nCSeq: {index}\r\nContent-Length: {}\r\n\r\n",body.len()).into_bytes();request.extend_from_slice(&body);
                car.write_all(&if encrypted {peer_cipher.encrypt(&request)}else{request}).await.unwrap();
                let mut wire=Vec::new();let mut plain=Vec::new();let mut chunk=[0;2048];
                let response=loop {
                    let n=tokio::time::timeout(Duration::from_secs(1),car.read(&mut chunk)).await.unwrap().unwrap();assert!(n>0);wire.extend_from_slice(&chunk[..n]);
                    if encrypted {let (bytes,used)=peer_cipher.decrypt(&wire).unwrap();wire.drain(..used);plain.extend(bytes);}else{plain.append(&mut wire);}
                    if let Some(response)=parse_rtsp_messages(&mut plain).unwrap().pop(){break response;}
                };
                assert_eq!(response.first_line,format!("{protocol} 200 OK"),"kind={kind}");
                assert_eq!(response.cseq(),Some(index as i32));assert!(response.body.is_empty());
                let received=tokio::time::timeout(Duration::from_secs(1),inbound.recv()).await.unwrap().unwrap();
                assert_eq!(received.body,body);
                let command=crate::bplist::parse(&received.body).unwrap();
                if last {assert_eq!(crate::wifi_iap2::command_bytes(&command),Some(data.as_slice()));}
                else {assert!(crate::wifi_iap2::command_bytes(&command).is_none(),"notification/empty command must not enter link authentication");}
                assert_eq!(modes.lock().diagnostics(),before,"observation/empty command must not transfer screen or audio");
            }
        }
    }

    #[tokio::test]
    async fn box_empty_iap_envelope_is_acknowledged_before_real_detection() {
        box_startup_sequence(&["iAPSendMessage"]).await;
    }

    #[tokio::test]
    async fn box_observation_notifications_do_not_block_following_detection() {
        box_startup_sequence(&["showUI","stopUI","setLimitedUI","updateDisplayPanels","accessoryGiveFocus"]).await;
    }

    #[test]
    fn http_status_lines_are_responses_rather_than_commands() {
        for protocol in ["HTTP/1.0","HTTP/1.1","RTSP/1.0"] {
            let mut wire=format!("{protocol} 200 OK\r\nCSeq: 7\r\nContent-Length: 0\r\n\r\n").into_bytes();
            let response=parse_rtsp_messages(&mut wire).unwrap().pop().unwrap();
            assert!(response.is_response(),"{}",response.first_line);
            assert_eq!(response.status(),200);
            assert_eq!(response.cseq(),Some(7));
        }
    }

    #[tokio::test]
    async fn http_reply_resolves_pending_request_without_dispatch_as_a_command() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();let(r,w)=client.into_split();
        let (channel,mut inbound)=RtspChannel::spawn(r,w,None);
        let server=crate::callback_context::spawn(async move {
            let mut buffer=Vec::new();let mut chunk=[0;1024];
            let request=loop {
                let n=peer.read(&mut chunk).await.unwrap();assert!(n>0);buffer.extend_from_slice(&chunk[..n]);
                if let Some(request)=parse_rtsp_messages(&mut buffer).unwrap().pop(){break request;}
            };
            peer.write_all(format!("HTTP/1.1 200 OK\r\nCSeq: {}\r\nContent-Length: 2\r\n\r\nok",request.cseq().unwrap()).as_bytes()).await.unwrap();
        });
        let reply=channel.request("POST","/feedback",&[],"application/octet-stream",&[],Duration::from_secs(1)).await.unwrap();
        assert_eq!(reply.status(),200);assert_eq!(reply.body,b"ok");
        assert!(inbound.try_recv().is_err());server.await.unwrap();
    }

    #[tokio::test]
    async fn iap_command_ack_matches_request_protocol_without_changing_payload_or_validation() {
        use crate::bplist::{encode,Value};
        for protocol in ["RTSP/1.0","HTTP/1.1","HTTP/1.0"] {
            for encrypted in [false,true] {
                for (kind,valid,status) in [("iAPSendMessage",true,200),("iAPSendMessage",false,400),("futureCommand",true,501)] {
                    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
                    let mut car=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
                    let (phone,_)=listener.accept().await.unwrap();let(r,w)=phone.into_split();
                    let cipher=encrypted.then(||ControlCipher::new([1;32],[2;32]));
                    let (_channel,mut inbound)=RtspChannel::spawn(r,w,cipher);
                    let marker=vec![0xff,0x55,2,0,0xee,0x10];
                    let params=if valid {Value::dict(vec![("data",Value::Data(marker.clone()))])}else{Value::dict(vec![])};
                    let body=encode(&Value::dict(vec![("type",Value::str(kind)),("params",params)]));
                    let sequence=if encrypted {"CSeq: 7\r\n"}else{""};
                    let mut request=format!("POST /command {protocol}\r\n{sequence}Content-Length: {}\r\n\r\n",body.len()).into_bytes();request.extend_from_slice(&body);
                    let mut peer_cipher=ControlCipher::new([2;32],[1;32]);
                    let wire=if encrypted {peer_cipher.encrypt(&request)}else{request};
                    car.write_all(&wire).await.unwrap();
                    let mut buffer=Vec::new();let mut chunk=[0;2048];
                    let response=loop {
                        let n=tokio::time::timeout(Duration::from_secs(1),car.read(&mut chunk)).await.unwrap().unwrap();assert!(n>0);
                        buffer.extend_from_slice(&chunk[..n]);
                        let parsed=if encrypted {peer_cipher.decrypt(&buffer).unwrap().0}else{buffer.clone()};
                        if let Some(response)=parse_rtsp_messages(&mut parsed.clone()).unwrap().pop() {break response;}
                    };
                    assert_eq!(response.first_line,format!("{protocol} {status} {}",match status{200=>"OK",400=>"Bad Request",_=>"Not Implemented"}));
                    assert_eq!(response.cseq(),encrypted.then_some(7));
                    if status==200 {
                        let delivered=tokio::time::timeout(Duration::from_secs(1),inbound.recv()).await.unwrap().unwrap();
                        assert_eq!(delivered.body,body);
                        assert_eq!(crate::wifi_iap2::command_bytes(&crate::bplist::parse(&delivered.body).unwrap()),Some(marker.as_slice()));
                    }else{assert!(inbound.try_recv().is_err());}
                }
            }
        }
    }

    #[tokio::test]
    async fn absolute_session_uri_is_immutable_reused_and_preserves_encrypted_transactions() {
        use crate::bplist::{self,Value};
        for session_id in [None,Some(7u64),Some(8u64)] {
            let session=bplist::encode(&Value::dict(vec![("features",Value::array(vec![Value::str("hevc")])),("timingPort",Value::int(12345))]));
            let screen=crate::airplay::build_screen_setup_with_latency(987,"display",75);
            let teardown=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(110))])]))]));
            let cases=vec![("SETUP","/setup",session),("SETUP","/",screen),("RECORD","/",vec![]),
                ("TEARDOWN","/",teardown),("TEARDOWN","/",bplist::encode(&Value::dict(vec![("streams",Value::Array(vec![]))]))),
                ("SETUP","rtsp://[fe80::1]:7000/",b"audio unchanged".to_vec()),("POST","/command",b"command unchanged".to_vec()),("GET","/info",vec![])];
            let expected=cases.clone();
            let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let client=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let(mut peer,_)=listener.accept().await.unwrap();let(r,w)=client.into_split();
            let(channel,_)=RtspChannel::spawn(r,w,Some(ControlCipher::new([1;32],[2;32])));
            if let Some(id)=session_id {
                channel.set_session_uri(crate::rtsp_uri::SessionUri::new("fe80::1%wlan0",id).unwrap()).unwrap();
                assert!(channel.set_session_uri(crate::rtsp_uri::SessionUri::new("192.0.2.2",999).unwrap()).is_err());
            }
            let server=crate::callback_context::spawn(async move {
                let mut cipher=ControlCipher::new([2;32],[1;32]);let mut wire=vec![];let mut plain=vec![];
                for (index,(method,path,body)) in expected.into_iter().enumerate() {
                    let request=loop {
                        let mut buf=[0;4096];let n=peer.read(&mut buf).await.unwrap();assert!(n>0);wire.extend_from_slice(&buf[..n]);
                        let(decoded,used)=cipher.decrypt(&wire).unwrap();wire.drain(..used);plain.extend(decoded);
                        if let Some(msg)=parse_rtsp_messages(&mut plain).unwrap().pop(){break msg;}
                    };
                    let target=match session_id {Some(id) if index<5=>format!("rtsp://[fe80::1%25wlan0]/{id}"),_=>path.to_string()};
                    assert_eq!(request.first_line,format!("{method} {target} RTSP/1.0"));
                    assert_eq!(request.body,body);assert_eq!(request.cseq(),Some(index as i32+1));
                    assert_eq!(request.headers.get("user-agent").map(String::as_str),Some("AirPlay/770.8.1"));
                    peer.write_all(&cipher.encrypt(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",request.cseq().unwrap()).as_bytes())).await.unwrap();
                }
            });
            for (method,path,body) in cases {assert_eq!(channel.request(method,path,&body,"application/x-apple-binary-plist",&[],Duration::from_secs(2)).await.unwrap().status(),200);}
            server.await.unwrap();
        }
    }

    #[test]
    fn framing_rejects_invalid_and_oversized_lengths_without_consuming_input() {
        for length in ["-1".to_string(), "+1".into(), "NaN".into(), usize::MAX.to_string(),
                       (MAX_RTSP_BODY + 1).to_string(), "99999999999999999999999999999".into()] {
            let mut bytes = format!("RTSP/1.0 200 OK\r\nContent-Length: {length}\r\n\r\n").into_bytes();
            let original = bytes.clone();
            assert!(parse_rtsp_messages(&mut bytes).is_err(), "length={length}");
            assert_eq!(bytes, original);
        }
        let mut conflict = b"RTSP/1.0 200 OK\r\nContent-Length: 0\r\ncontent-length: 5\r\n\r\n".to_vec();
        assert!(parse_rtsp_messages(&mut conflict).is_err());
        assert!(parse_rtsp_messages(&mut vec![b'x'; MAX_RTSP_HEADER + 1]).is_err());
    }

    #[test]
    fn fragmented_bodies_and_pipelined_messages_keep_framing() {
        let mut bytes = b"RTSP/1.0 200 OK\r\nCSeq: 7\r\nContent-Length: 5\r\n\r\nhe".to_vec();
        assert!(parse_rtsp_messages(&mut bytes).unwrap().is_empty());
        bytes.extend_from_slice(b"lloRTSP/1.0 200 OK\r\nCSeq: 8\r\n\r\npartial");
        let messages = parse_rtsp_messages(&mut bytes).unwrap();
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].body, b"hello");
        assert_eq!(messages[1].cseq(), Some(8));
        assert_eq!(bytes, b"partial");
    }

    #[tokio::test]
    async fn malformed_response_wakes_pending_transaction_without_timeout() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r, w) = phone.into_split();
        let (channel, _) = RtspChannel::spawn(r, w, None);
        let peer = crate::callback_context::spawn(async move {
            let mut request = [0; 4096];
            assert!(car.read(&mut request).await.unwrap() > 0);
            car.write_all(b"RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: -1\r\n\r\n").await.unwrap();
            // Keep TCP open: prompt failure must come from parsing, not EOF.
            tokio::time::sleep(Duration::from_secs(2)).await;
        });
        let result = tokio::time::timeout(Duration::from_secs(1), channel.request(
            "GET", "/info", &[], "", &[], Duration::from_secs(30)
        )).await.expect("invalid response must wake its waiter");
        assert!(result.unwrap_err().contains("控制通道已关闭"));
        assert!(channel.is_closed());
        assert!(channel.pending.lock().is_empty());
        peer.abort();
    }


    #[tokio::test]
    async fn inbound_commands_reject_malformed_and_unknown_without_dispatch() {
        use crate::bplist::{encode, Value};
        let mut cases = vec![
            ("POST /command RTSP/1.0", b"broken".to_vec(), 400),
            ("POST /command RTSP/1.0", encode(&Value::array(vec![])), 400),
            ("POST /command RTSP/1.0", encode(&Value::dict(vec![])), 400),
            ("POST /command RTSP/1.0", encode(&Value::dict(vec![("type", Value::int(3))])), 400),
            ("POST /command RTSP/1.0", encode(&Value::dict(vec![("type", Value::str("futureCommand"))])), 501),
            ("OPTIONS * RTSP/1.0", vec![], 200),
            ("POST /command-extra RTSP/1.0", b"opaque".to_vec(), 200),
        ];
        for kind in ["iAPSendMessage", "hidSendReport", "requestUI", "changeUIContext", "changeModes",
            "requestSiri", "setNightMode", "forceKeyFrame", "hidSetInputMode", "ping", "fps"] {
            let params = match kind {
                "iAPSendMessage" => Value::dict(vec![("data",Value::Data(vec![1]))]),
                "hidSendReport" => Value::dict(vec![("report",Value::Data(vec![1]))]),
                "requestSiri" => Value::dict(vec![("siriAction",Value::int(2))]),
                "setNightMode" => Value::dict(vec![("nightMode",Value::bool(true))]),
                _ => Value::dict(vec![]),
            };
            cases.push(("POST /command RTSP/1.0", encode(&Value::dict(vec![("type",Value::str(kind)),("params",params)])), 200));
        }
        for (kind, params) in [
            ("iAPSendMessage", Value::dict(vec![])),
            ("iAPSendMessage", Value::dict(vec![("data",Value::str("invalid"))])),
            ("iAPSendMessage", Value::dict(vec![("data",Value::Data(vec![0;65536]))])),
            ("hidSendReport", Value::dict(vec![])),
            ("hidSendReport", Value::dict(vec![("report",Value::str("invalid"))])),
            ("requestSiri", Value::dict(vec![("siriAction",Value::int(99))])),
            ("setNightMode", Value::dict(vec![])),
        ] {
            cases.push(("POST /command RTSP/1.0",encode(&Value::dict(vec![("type",Value::str(kind)),("params",params)])),400));
        }
        for kind in ["showUI","stopUI","setLimitedUI","updateDisplayPanels","accessoryGiveFocus"] {
            cases.push(("POST /command RTSP/1.0",encode(&Value::dict(vec![("type",Value::str(kind)),("params",Value::str("not-a-dictionary"))])),400));
        }
        for (fields, status) in [
            (vec![("type",Value::str("hidSendReport")),("hidReport",Value::Data(vec![1])),("uuid",Value::str("touch"))],200),
            (vec![("type",Value::str("hidSendReport")),("hidReport",Value::Data(vec![1])),("uuid",Value::int(1))],200),
            (vec![("type",Value::str("requestSiri")),("siriAction",Value::int(3))],200),
            (vec![("type",Value::str("setNightMode")),("nightMode",Value::int(0))],200),
        ] { cases.push(("POST /command RTSP/1.0",encode(&Value::dict(fields)),status)); }
        for (id, status) in [(Value::str("touch"),200),(Value::int(0),200),(Value::int(1),200),
            (Value::int(-1),400),(Value::Data(vec![1]),400),(Value::bool(true),400)] {
            for nested in [false,true] {
                let mut fields = vec![("type",Value::str("hidSendReport"))];
                if nested { fields.push(("params",Value::dict(vec![("report",Value::Data(vec![1])),("uuid",id.clone())]))); }
                else { fields.extend([("hidReport",Value::Data(vec![1])),("uuid",id.clone())]); }
                cases.push(("POST /command RTSP/1.0",encode(&Value::dict(fields)),status));
            }
        }
        cases.push(("POST /command RTSP/1.0",encode(&Value::dict(vec![("type",Value::str("setNightMode")),
            ("nightMode",Value::str("invalid")),("params",Value::dict(vec![("nightMode",Value::bool(true))]))])),200));
        for (line, body, status) in cases {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut car = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (phone, _) = listener.accept().await.unwrap();
            let (r,w) = phone.into_split();
            let (_channel, mut inbound) = RtspChannel::spawn(r,w,None);
            car.write_all(format!("{line}\r\nCSeq: 23\r\nContent-Length: {}\r\n\r\n",body.len()).as_bytes()).await.unwrap();
            car.write_all(&body).await.unwrap();
            let mut buffer = Vec::new(); let mut chunk = [0;1024];
            let response = loop {
                let n = tokio::time::timeout(Duration::from_secs(1),car.read(&mut chunk)).await.unwrap().unwrap();
                assert!(n>0); buffer.extend_from_slice(&chunk[..n]);
                if let Some(message) = parse_rtsp_messages(&mut buffer).unwrap().pop() { break message; }
            };
            assert_eq!(response.status(),status,"{line} body={body:?}");
            assert_eq!(response.cseq(),Some(23));
            assert!(response.body.is_empty());
            if status == 200 { assert_eq!(inbound.recv().await.unwrap().body,body); }
            else { assert!(inbound.try_recv().is_err(),"rejected command was dispatched"); }
        }
    }

    #[tokio::test]
    async fn ui_commands_receive_empty_200_with_optional_cseq_before_dispatch() {
        for (kind,cseq) in [("requestUI",Some(19)),("changeUIContext",None)] {
            let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let mut car=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (phone,_)=listener.accept().await.unwrap(); let (r,w)=phone.into_split();
            let (_channel,mut inbound)=RtspChannel::spawn(r,w,None);
            let body=crate::bplist::encode(&crate::bplist::Value::dict(vec![
                ("type",crate::bplist::Value::str(kind)),
                ("params",crate::bplist::Value::dict(vec![("url",crate::bplist::Value::str("maps:private?test=1"))])),
            ]));
            let sequence=cseq.map(|c| format!("CSeq: {c}\r\n")).unwrap_or_default();
            car.write_all(format!("POST /command RTSP/1.0\r\n{sequence}Content-Length: {}\r\n\r\n",body.len()).as_bytes()).await.unwrap();
            car.write_all(&body).await.unwrap();
            let dispatched=tokio::time::timeout(Duration::from_secs(1),inbound.recv()).await.unwrap().unwrap();
            assert_eq!(dispatched.body,body);
            // At dispatch the complete reply is already writable/readable on the peer socket.
            let mut buffer=Vec::new(); let mut chunk=[0;1024];
            let response=loop {
                let n=tokio::time::timeout(Duration::from_secs(1),car.read(&mut chunk)).await.unwrap().unwrap();
                assert!(n>0); buffer.extend_from_slice(&chunk[..n]);
                if let Some(response)=parse_rtsp_messages(&mut buffer).unwrap().pop() { break response; }
            };
            assert_eq!(response.status(),200); assert_eq!(response.cseq(),cseq); assert!(response.body.is_empty());
        }
    }

    #[tokio::test]
    async fn media_setup_trace_preserves_wire_request_and_empty_400_response() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let body = crate::airplay::build_screen_setup(42, "screen-id");
        let expected = body.clone();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 1024];
            let request = loop {
                let n = socket.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                if let Some(request) = parse_rtsp_messages(&mut buffer).unwrap().pop() { break request; }
            };
            assert_eq!(request.first_line, "SETUP / RTSP/1.0");
            assert_eq!(request.body, expected);
            let reply = format!("RTSP/1.0 400 Bad Request\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
            socket.write_all(reply.as_bytes()).await.unwrap();
        });
        let socket = tokio::net::TcpStream::connect(address).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _inbound) = RtspChannel::spawn(read, write, None);
        let response = channel.request("SETUP", "/", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(2)).await.unwrap();
        assert_eq!(response.status(), 400);
        assert!(response.body.is_empty());
        peer.await.unwrap();
    }

    #[tokio::test]
    async fn command_response_contains_business_status_and_matching_cseq() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut car = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (phone, _) = listener.accept().await.unwrap();
        let (r,w) = phone.into_split();
        let body = crate::bplist::encode(&crate::bplist::Value::dict(vec![("status", crate::bplist::Value::int(0))]));
        let expected = body.clone();
        let (_channel, mut inbound) = RtspChannel::spawn_with_reply(r,w,None,Some(Arc::new(move |_| Some(body.clone()))),Default::default(),None);
        let command = crate::bplist::encode(&crate::bplist::Value::dict(vec![("type",crate::bplist::Value::str("changeModes"))]));
        car.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 19\r\nContent-Length: {}\r\n\r\n",command.len()).as_bytes()).await.unwrap();
        car.write_all(&command).await.unwrap();
        assert!(inbound.recv().await.is_some());
        let mut buffer = Vec::new(); let mut chunk = [0;1024];
        let response = loop {
            let n = car.read(&mut chunk).await.unwrap(); assert!(n>0);
            buffer.extend_from_slice(&chunk[..n]);
            if let Some(message) = parse_rtsp_messages(&mut buffer).unwrap().pop() { break message; }
        };
        assert_eq!(response.cseq(), Some(19));
        assert_eq!(response.status(), 200);
        assert_eq!(response.body, expected);
    }

    #[tokio::test]
    async fn cancelled_queued_write_does_not_consume_an_encryption_nonce() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut cipher = ControlCipher::new([2; 32], [1; 32]);
            let mut wire = Vec::new();
            let mut plain = Vec::new();
            let mut chunk = [0u8; 2048];
            loop {
                let n = socket.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                wire.extend_from_slice(&chunk[..n]);
                let (decoded, used) = cipher.decrypt(&wire).expect("wire order must match nonce order");
                wire.drain(..used);
                plain.extend_from_slice(&decoded);
                if let Some(request) = parse_rtsp_messages(&mut plain).unwrap().pop() {
                    assert_eq!(request.first_line, "RECORD / RTSP/1.0");
                    let response = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
                    socket.write_all(&cipher.encrypt(response.as_bytes())).await.unwrap();
                    break;
                }
            }
        });
        let socket = tokio::net::TcpStream::connect(addr).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, Some(ControlCipher::new([1; 32], [2; 32])));
        let busy_writer = channel.write.lock().await;
        let cancelled = tokio::time::timeout(Duration::from_millis(20), channel.request(
            "POST", "/feedback", &[], "application/octet-stream", &[], Duration::from_secs(2),
        )).await;
        assert!(cancelled.is_err());
        assert!(channel.pending.lock().is_empty());
        assert!(!channel.is_closed());
        assert_eq!(channel.cipher.lock().as_ref().unwrap().write_counter, 0);
        drop(busy_writer);
        assert_eq!(channel.request("RECORD", "/", &[], "application/octet-stream", &[], Duration::from_secs(2)).await.unwrap().status(), 200);
        peer.await.unwrap();
    }
}


#[cfg(test)]
mod cancellation_tests {
    use super::*;
    use tokio::net::{TcpListener, TcpStream};

    async fn event_eof_with_queued_request(control: &RtspChannel) -> mpsc::UnboundedReceiver<RtspMessage> {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (tx, mut observed) = mpsc::unbounded_channel();
        let (_event, inbound) = control.spawn_event(r, w, None, Arc::new(move |_, cause| {
            if cause == "peer_eof" { tx.send(()).unwrap(); }
        }));
        peer.write_all(b"OPTIONS / RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n").await.unwrap();
        peer.shutdown().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), observed.recv()).await.unwrap().unwrap();
        assert_eq!(inbound.len(), 1, "EOF must be observable before dispatch drains the queue");
        inbound
    }

    #[tokio::test]
    async fn end_detail_event_eof_before_second_feedback_timeout() {
        let (control, _peer) = pair().await;
        assert!(matches!(control.request_feedback(None).await, Err(RequestError::ReplyTimeout(_))));
        let _queued = event_eof_with_queued_request(&control).await;
        assert!(matches!(control.request_feedback(Some(FeedbackFailure::Timeout)).await, Err(RequestError::ReplyTimeout(_))));
        assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "事件通道关闭");
    }

    #[tokio::test]
    async fn end_detail_second_feedback_timeout_before_event_eof() {
        let (control, _peer) = pair().await;
        for retry in [None, Some(FeedbackFailure::Timeout)] {
            assert!(matches!(control.request_feedback(retry).await, Err(RequestError::ReplyTimeout(_))));
        }
        let _queued = event_eof_with_queued_request(&control).await;
        assert_eq!(control.end_detail(RtspEndCause::EventClosed), "控制保活失败");
    }

    #[tokio::test]
    async fn end_detail_same_address_reinvite_ignores_late_old_observations() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (old, _old_peer) = pair_on(&listener).await;
        let (new, _new_peer) = pair_on(&listener).await;
        let _queued = event_eof_with_queued_request(&old).await;
        assert_eq!(old.end_detail(RtspEndCause::FeedbackFailed), "事件通道关闭");
        assert_eq!(new.end_detail(RtspEndCause::FeedbackFailed), "控制保活失败");
        assert!(matches!(new.request_feedback(Some(FeedbackFailure::Timeout)).await, Err(RequestError::ReplyTimeout(_))));
        assert_eq!(new.end_detail(RtspEndCause::EventClosed), "控制保活失败");
        assert_eq!(old.end_detail(RtspEndCause::FeedbackFailed), "事件通道关闭");
    }

    async fn send_peer_teardown(peer: &mut TcpStream, target: &str, body: &[u8], seq: i32) {
        let mut wire = format!("TEARDOWN {target} RTSP/1.0\r\nCSeq: {seq}\r\nContent-Length: {}\r\n\r\n", body.len()).into_bytes();
        wire.extend_from_slice(body);
        peer.write_all(&wire).await.unwrap();
        let reply = tokio::time::timeout(Duration::from_secs(2), read_request(peer)).await.unwrap();
        assert_eq!((reply.status(), reply.cseq()), (200, Some(seq)));
    }

    #[tokio::test]
    async fn peer_teardown_on_control_survives_feedback_timeout_and_event_eof() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        send_peer_teardown(&mut peer, "rtsp://127.0.0.1/42", &[], 10).await;
        assert!(!control.is_closed());
        assert!(matches!(control.request_feedback(Some(FeedbackFailure::Timeout)).await, Err(RequestError::ReplyTimeout(_))));
        let _queued = event_eof_with_queued_request(&control).await;
        assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "车机主动结束");
    }

    #[tokio::test]
    async fn event_eof_cannot_strand_pending_control_teardown() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let event_socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (event_peer, _) = listener.accept().await.unwrap();
        let (read, write) = event_socket.into_split();
        let (_event, mut inbound) = control.spawn_event(read, write, None, Arc::new(|_, _| {}));
        let writer = control.write.lock().await;
        peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 77\r\n\r\n").await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while !control.peer_teardown_pending() { tokio::task::yield_now().await; }
        }).await.unwrap();
        control.record_feedback_failure();
        drop(event_peer);
        assert!(tokio::time::timeout(Duration::from_secs(1), inbound.recv()).await.unwrap().is_none());
        drop(writer);
        let ack = read_request(&mut peer).await;
        assert_eq!((ack.status(), ack.cseq()), (200, Some(77)));
        tokio::time::timeout(Duration::from_secs(1), control.wait_for_peer_teardown()).await
            .expect("acknowledged TEARDOWN must finish after the other socket closes");
        assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "车机主动结束");
    }

    #[tokio::test]
    async fn closing_reader_during_teardown_has_a_bounded_end() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        let _writer = control.write.lock().await;
        peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 77\r\n\r\n").await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while !control.peer_teardown_pending() { tokio::task::yield_now().await; }
        }).await.unwrap();
        control.close();
        tokio::time::timeout(Duration::from_secs(3), control.wait_for_peer_teardown()).await
            .expect("cancelled RTSP reader must not strand the pending end");
        assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "车机请求结束，应答未完成");
    }

    #[tokio::test]
    async fn peer_teardown_on_event_is_scoped_to_session_and_preserves_dispatch() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (old, _old_peer) = pair_on(&listener).await;
        let (new, mut new_peer) = pair_on(&listener).await;
        for (control, id) in [(&old, 41), (&new, 42)] {
            control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", id).unwrap()).unwrap();
        }
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (_event, mut inbound) = old.spawn_event(r, w, None, Arc::new(|_, _| {}));
        old.enable_mbux_session_header();
        old.session_header.state.lock().id = Some("current".into());
        let stream = crate::bplist::Value::dict(vec![("type", crate::bplist::Value::int(110))]);
        let body = crate::bplist::encode(&crate::bplist::Value::dict(vec![("streams", crate::bplist::Value::array(vec![stream]))]));
        send_peer_teardown(&mut peer, "rtsp://127.0.0.1/41", &body, 1).await;
        send_peer_teardown(&mut peer, "/stream/110", &[], 2).await;
        peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 3\r\nSession: retired\r\n\r\n").await.unwrap();
        assert_eq!(read_request(&mut peer).await.status(), 200);
        assert_eq!(old.end_detail(RtspEndCause::EventClosed), "事件通道关闭");
        peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 4\r\nSession: current;timeout=30\r\n\r\n").await.unwrap();
        let ack = read_request(&mut peer).await;
        assert_eq!((ack.status(), ack.cseq()), (200, Some(4)));
        peer.shutdown().await.unwrap();
        for seq in 1..=4 { assert_eq!(inbound.recv().await.unwrap().cseq(), Some(seq)); }
        assert!(inbound.recv().await.is_none());
        assert_eq!(old.end_detail(RtspEndCause::EventClosed), "车机主动结束");
        send_peer_teardown(&mut new_peer, "rtsp://127.0.0.1/41", &[], 4).await;
        new_peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 5\r\nSession: retired\r\n\r\n").await.unwrap();
        assert_eq!(read_request(&mut new_peer).await.status(), 200);
        new_peer.write_all(b"TEARDOWN / RTSP/1.0\r\n\r\n").await.unwrap();
        assert_eq!(read_request(&mut new_peer).await.status(), 200);
        assert_eq!(new.end_detail(RtspEndCause::FeedbackFailed), "控制保活失败");
        send_peer_teardown(&mut new_peer, "/", &[], 6).await;
        new.wait_for_peer_teardown().await;
        assert_eq!(new.end_detail(RtspEndCause::EventClosed), "车机主动结束");
    }

    #[tokio::test]
    async fn event_diagnostics_keep_first_close_cause_and_socket_id() {
        for expected in ["peer_eof", "read_error", "decrypt_error", "invalid_framing", "local_close", "local_drop"] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut peer, _) = listener.accept().await.unwrap();
            let (read, write) = client.into_split();
            let (tx, mut events) = mpsc::unbounded_channel();
            let cipher = (expected == "decrypt_error").then(|| ControlCipher::new([1;32], [2;32]));
            let (channel, mut inbound) = RtspChannel::spawn_with_reply(read, write, cipher, None, Default::default(),
                Some((Arc::new(move |id, cause| { tx.send((id, cause.to_owned())).unwrap(); }), Default::default())));
            let id = channel.trace_id();
            assert_eq!(events.recv().await.unwrap(), (id, "open".to_owned()));
            match expected {
                "peer_eof" => peer.shutdown().await.unwrap(),
                "read_error" => { socket2::SockRef::from(&peer).set_linger(Some(std::time::Duration::ZERO)).unwrap(); drop(peer); }
                "decrypt_error" => peer.write_all(&[0;18]).await.unwrap(),
                "invalid_framing" => peer.write_all(b"POST /command RTSP/1.0\r\nContent-Length: invalid\r\n\r\n").await.unwrap(),
                "local_close" => channel.close(),
                _ => {}
            }
            if expected == "local_drop" { drop(channel); }
            else {
                assert!(tokio::time::timeout(std::time::Duration::from_secs(2), inbound.recv()).await.unwrap().is_none());
                channel.close();
            }
            let (closed_id, cause) = events.recv().await.unwrap();
            assert_eq!(closed_id, id);
            assert!(cause.starts_with(expected), "{cause}");
            if expected == "read_error" { assert!(cause.contains("ConnectionReset") && cause.contains("errno=Some("), "{cause}"); }
            assert!(events.try_recv().is_err());
        }
    }

    async fn pair() -> (Arc<RtspChannel>, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        pair_on(&listener).await
    }
    async fn pair_on(listener: &TcpListener) -> (Arc<RtspChannel>, TcpStream) {
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (peer, _) = listener.accept().await.unwrap();
        let (r,w) = socket.into_split();
        let (channel,_) = RtspChannel::spawn(r,w,None);
        (channel,peer)
    }

    async fn assert_control_close_wakes_waiters(close_locally: bool) {
        use std::future::{Future, poll_fn};
        use std::pin::pin;
        use std::task::Poll;

        let (channel, mut peer) = pair().await;
        let mut first = pin!(channel.wait_for_control_close());
        let mut second = pin!(channel.wait_for_control_close());
        poll_fn(|cx| {
            assert!(first.as_mut().poll(cx).is_pending());
            assert!(second.as_mut().poll(cx).is_pending());
            Poll::Ready(())
        }).await;

        if close_locally {
            channel.close();
        } else {
            peer.shutdown().await.unwrap();
        }

        tokio::time::timeout(Duration::from_secs(1), async {
            tokio::join!(first, second);
        }).await.expect("control close must wake all registered waiters");
        assert!(channel.is_closed());
        tokio::time::timeout(Duration::from_secs(1), channel.wait_for_control_close())
            .await.expect("new waiters must observe that the control channel is closed");
    }

    #[tokio::test]
    async fn local_control_close_wakes_all_waiters() {
        assert_control_close_wakes_waiters(true).await;
    }

    #[tokio::test]
    async fn control_reader_eof_wakes_all_waiters() {
        assert_control_close_wakes_waiters(false).await;
    }

    async fn read_request(peer: &mut TcpStream) -> RtspMessage {
        let mut bytes = Vec::new();
        let mut chunk = [0; 2048];
        loop {
            let n = peer.read(&mut chunk).await.unwrap();
            assert!(n > 0);
            bytes.extend_from_slice(&chunk[..n]);
            if let Some(message) = parse_rtsp_messages(&mut bytes).unwrap().pop() { return message; }
        }
    }

    #[tokio::test]
    async fn hud_post_does_not_wait_and_only_matching_cseq_consumes_its_trace() {
        let (channel, mut peer) = pair().await;
        let message = HudPostTrace { link_id: 7, message_id: 0x5201, seq: 255, serial: 3 };
        let cseq = channel.post_traced("/command", b"hud", "application/octet-stream", Some(message)).await.unwrap();
        let request = read_request(&mut peer).await;
        assert_eq!(request.cseq(), Some(cseq));
        assert_eq!(request.body, b"hud");
        let next = channel.post_traced("/command", b"turn", "application/octet-stream",
            Some(HudPostTrace { message_id: 0x5202, seq: 0, serial: 4, ..message })).await.unwrap();
        assert_eq!(read_request(&mut peer).await.cseq(), Some(next));
        let body = crate::bplist::encode(&crate::bplist::Value::dict(vec![
            ("status", crate::bplist::Value::Int(-10)),
        ]));
        peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {next}\r\nContent-Length: {}\r\n\r\n", body.len()).as_bytes()).await.unwrap();
        peer.write_all(&body).await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), async {
            while channel.hud_replies.lock().len() != 1 {
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
        assert_eq!(channel.hud_replies.lock().front().map(|(seq, trace)| (*seq, trace.serial)), Some((cseq, 3)));
    }

    #[tokio::test]
    async fn session_header_is_negotiated_by_setup_and_scoped_to_its_connection() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (channel, mut peer) = pair_on(&listener).await;
        let responder = crate::callback_context::spawn(async move {
            for (method, status, headers, expected) in [
                ("SETUP", 200, "", None),
                ("POST", 200, "Session: premature\r\n", None),
                ("SETUP", 400, "Session: rejected\r\n", None),
                ("SETUP", 200, "Session: bad id\r\n", None),
                ("SETUP", 200, "sEsSiOn: learned-1;timeout=60\r\n", None),
                ("RECORD", 200, "Session: unrelated-command\r\n", Some("learned-1")),
                ("SETUP", 200, "Session: learned-2\r\n", Some("learned-1")),
            ] {
                let request = read_request(&mut peer).await;
                assert_eq!(request.first_line.split_ascii_whitespace().next(), Some(method));
                assert_eq!(request.headers.get("session").map(String::as_str), expected);
                if headers.is_empty() {
                    peer.write_all(b"RTSP/1.0 200 OK\r\nCSeq: 9999\r\nSession: unsolicited\r\n\r\n").await.unwrap();
                }
                let cseq = request.cseq().unwrap();
                peer.write_all(format!("RTSP/1.0 {status} Reply\r\nCSeq: {cseq}\r\n{headers}\r\n").as_bytes()).await.unwrap();
            }
            let request = read_request(&mut peer).await;
            assert_eq!(request.headers.get("session").map(String::as_str), Some("learned-2"));
        });
        for method in ["SETUP", "POST", "SETUP", "SETUP", "SETUP", "RECORD", "SETUP"] {
            channel.request(method, "/", &[], "", &[], Duration::from_secs(2)).await.unwrap();
        }
        channel.post_fire_and_forget("/command", &[], "").await.unwrap();
        responder.await.unwrap();

        let (reconnected, mut peer) = pair_on(&listener).await;
        reconnected.post_fire_and_forget("/command", &[], "").await.unwrap();
        assert!(!read_request(&mut peer).await.headers.contains_key("session"));
    }

    #[tokio::test]
    async fn queued_record_uses_session_header_learned_while_waiting_for_writer() {
        let (channel, mut peer) = pair().await;
        let setup = crate::callback_context::spawn({
            let channel = channel.clone();
            async move { channel.request("SETUP", "/setup", &[], "", &[], Duration::from_secs(2)).await }
        });
        let setup_request = read_request(&mut peer).await;
        let writer = channel.write.lock().await;
        let record = channel.request("RECORD", "/", &[], "", &[], Duration::from_secs(2));
        tokio::pin!(record);
        tokio::select! {
            biased;
            _ = &mut record => panic!("RECORD completed before it could write"),
            _ = std::future::ready(()) => {}
        }
        let cseq = setup_request.cseq().unwrap();
        peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nSession: queued\r\n\r\n").as_bytes()).await.unwrap();
        assert_eq!(setup.await.unwrap().unwrap().status(), 200);
        drop(writer);

        let (reply, ()) = tokio::join!(record, async {
            let request = read_request(&mut peer).await;
            assert_eq!(request.headers.get("session").map(String::as_str), Some("queued"));
            let cseq = request.cseq().unwrap();
            peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\n\r\n").as_bytes()).await.unwrap();
        });
        assert_eq!(reply.unwrap().status(), 200);
    }

    #[tokio::test]
    async fn mercedes_session_header_is_learned_only_from_success_on_this_control_socket() {
        for enabled in [true, false, true] {
            let (channel, mut peer) = pair().await;
            if enabled { channel.enable_mbux_session_header(); }
            let (sent, received) = oneshot::channel();
            let responder = crate::callback_context::spawn(async move {
                for (status, header, expected) in [
                    (200, "sEsSiOn: Box_1;timeout=60\r\n", None),
                    (400, "Session: rejected\r\n", Some("Box_1")),
                    (200, "", Some("Box_1")),
                    (200, "Session: Box-2\r\n", Some("Box_1")),
                ] {
                    let request = read_request(&mut peer).await;
                    assert_eq!(request.headers.get("session").map(String::as_str), expected.filter(|_| enabled));
                    if status == 400 {
                        peer.write_all(b"RTSP/1.0 200 OK\r\nCSeq: 9999\r\nSession: unrelated\r\n\r\n").await.unwrap();
                    }
                    peer.write_all(format!("RTSP/1.0 {status} Reply\r\nCSeq: {}\r\n{header}Content-Length: 0\r\n\r\n", request.cseq().unwrap()).as_bytes()).await.unwrap();
                }
                let request = read_request(&mut peer).await;
                assert_eq!(request.headers.get("session").map(String::as_str), enabled.then_some("Box-2"));
                peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nSession: Async;timeout=30\r\n\r\n", request.cseq().unwrap()).as_bytes()).await.unwrap();
                sent.send(()).unwrap();
                let request = read_request(&mut peer).await;
                assert_eq!(request.headers.get("session").map(String::as_str), enabled.then_some("Async"));
            });
            for _ in 0..4 {
                channel.request("POST", "/command", &[], "", &[], Duration::from_secs(2)).await.unwrap();
            }
            channel.post_fire_and_forget("/command", b"iap", "application/octet-stream").await.unwrap();
            received.await.unwrap();
            if enabled {
                tokio::time::timeout(Duration::from_secs(2), async {
                    while channel.session_header_line() != "Session: Async\r\n" { tokio::task::yield_now().await; }
                }).await.unwrap();
            }
            channel.post_fire_and_forget("/feedback", &[], "").await.unwrap();
            responder.await.unwrap();
        }
    }

    #[tokio::test]
    async fn request_budget_includes_waiting_for_writer() {
        let (channel,_peer) = pair().await;
        let held = channel.write.lock().await;
        let result = tokio::time::timeout(Duration::from_secs(1), channel.request(
            "RECORD", "/", &[], "application/octet-stream", &[], Duration::from_millis(20),
        )).await.unwrap();
        assert!(result.unwrap_err().contains("写锁超时"));
        assert!(channel.pending.lock().is_empty());
        assert!(!channel.is_closed());
        drop(held); channel.close();
    }

    #[tokio::test]
    async fn cancelled_response_waiter_is_removed_without_closing_healthy_channel() {
        let (channel,mut peer) = pair().await;
        let requester = channel.clone();
        let task = crate::callback_context::spawn(async move { requester.request(
            "POST", "/command", &[], "application/octet-stream", &[], Duration::from_secs(3),
        ).await });
        let _ = read_request(&mut peer).await;
        task.abort(); assert!(task.await.unwrap_err().is_cancelled());
        assert!(channel.pending.lock().is_empty());
        assert!(!channel.is_closed());
        let car = crate::callback_context::spawn(async move {
            let request = read_request(&mut peer).await;
            peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",request.cseq().unwrap()).as_bytes()).await.unwrap();
        });
        assert_eq!(channel.request("RECORD", "/", &[], "application/octet-stream", &[], Duration::from_secs(1)).await.unwrap().status(), 200);
        car.await.unwrap();
    }

    #[tokio::test]
    async fn timed_out_partial_write_invalidates_transport_and_all_pending_requests() {
        let (channel,_peer) = pair().await;
        // A peer that never reads cannot accept this body into its TCP receive window.
        let payload = vec![7; 16 * 1024 * 1024];
        let result = tokio::time::timeout(Duration::from_secs(2), channel.request(
            "POST", "/command", &payload, "application/octet-stream", &[], Duration::from_millis(30),
        )).await.unwrap();
        assert!(result.unwrap_err().contains("发送超时"));
        assert!(channel.is_closed());
        assert!(channel.pending.lock().is_empty());
        assert!(channel.request("RECORD", "/", &[], "application/octet-stream", &[], Duration::from_secs(1)).await.is_err());
    }
}
