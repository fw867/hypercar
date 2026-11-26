//! AirPlay 发送端会话（协商整体下沉 Rust —— 与参考文档的关键差异，见 docs/NEGOTIATION-GAPS.md）。
//!
//! 流程（参照 CatPlay bootstrap，保留已验证的配对与流参数）：
//!  1. 配对前 /info best-effort 读取身份；pair-setup / pair-verify 建立加密控制通道。
//!  2. 会话 SETUP /setup → 会话内 /info（真实显示 UUID / 能力）。
//!  3. 连接事件通道，启动 UDP keepAlive（立即首发，后续周期取决于车机档案和活动媒体流）及 /feedback（1s）。
//!  4. RECORD 激活 → modesChanged 声明资源 → 屏幕流 SETUP / → 连接数据口。
//!  5. 激活后协商音频；视频 pump 维持独立的屏幕流 keepAlive（1s）。
//!  6. 事件通道 hidSendReport → 触摸坐标换算 → 本机自绘界面。

use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;

use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::{mpsc, Notify};

use crate::audio_out::AudioOut;
use crate::bplist::{self, Value};
use crate::pairing::{ControllerIdentity, PairSetupClient, PairVerifyClient, TlvTransport};
use crate::rtsp::{ControlCipher, FeedbackFailure, RtspChannel, RtspEndCause};
use crate::timing::TimingServer;
use crate::{emit_log, emit_state};

/// Rust 需要新 socket 时回调 Kotlin（必须绑定车机网络，否则流量走手机）。
pub trait SocketProvider: Send + Sync {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream>;
    // Port zero binds an ephemeral local port without connecting to a remote port.
    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket>;
    fn bind_timing_network(&self, _socket: &std::net::UdpSocket) -> std::io::Result<()> {
        Err(std::io::Error::new(std::io::ErrorKind::Unsupported, "timing network binding unavailable"))
    }
}

#[derive(Clone, Debug)]
pub struct AirplayParams {
    pub car_ip: String,
    pub airplay_port: u16,
    /// 本机真实蓝牙 MAC（WinPlay 等接收端按 SETUP deviceID 过滤，占位值必 403）。
    pub device_id: String,
    pub wifi_mac_address: Option<String>,
    pub device_name: String,
    pub identity_path: String,
    pub receiver_id: Option<String>,
    pub receiver_model: Option<String>,
    pub receiver_previously_connected: bool,
    pub enable_audio: bool,
    pub audio_codec: String,
    pub video_codec: String,
    pub bmw_capabilities: bool,
    pub microphone_probe: Option<Arc<crate::mic_probe::ProbeControl>>,
    pub keep_alive_stats: bool,
    pub request_receiver_stats: bool,
    pub bmw_box_bootstrap: bool,
    pub skip_bluetooth_handoff: bool,
    pub head_unit_profile: String,
    pub mbux_screen_bootstrap: bool,
    pub id9_screen_bootstrap: bool,
    pub nbt_audio_first: bool,
    pub mbux_media_compat: bool,
    pub desktop_receiver: bool,
    /// 设置里的编码分辨率（仅作 `/info` 无显示器信息时的兜底）。
    pub width: u32,
    pub height: u32,
    /// **触摸的显示空间**（接收端屏幕像素）。`/info` 报 0x0 时用它；
    /// 真车会给真实尺寸，未提供有效像素尺寸的接收端按手动指定的触摸空间换算。
    /// 给错会让触摸整体缩放偏移——越靠右/下越明显（"按钮很难点上"）。
    pub touch_display: Option<(u32, u32)>,
    /// PC 接收端窗口在桌面上的矩形（left,top,right,bottom），HID 全局坐标；
    /// 仅当 `/info` 没有有效显示器尺寸时使用。
    pub touch_rect: Option<(u32, u32, u32, u32)>,
    /// 当前编码分辨率（共享单元）：
    /// `/info` 报到车机真实显示尺寸后由协商写入，手机在 `VIDEO_ENCODER_INIT_DONE`
    /// 里回报实际选择后由编码侧覆盖。触摸坐标换算与 VideoConfig 都读它。
    pub encoder_size: Arc<Mutex<(u32, u32)>>,
    /// 手机实际编码尺寸（`nativeSetVideoSize` 由 JNI 直接写入）。触摸坐标换算用它，
    /// 避免被 `/info` 的车机显示尺寸覆盖后把 HID 坐标缩放到画布外。
    pub touch_size: Arc<Mutex<(u32, u32)>>,
    /// CarWith-only requested canvas. None leaves the self-rendered display path unchanged.
    pub carwith_display: Option<crate::carlife::DisplayRequest>,
    pub carwith_media_rate: Option<tokio::sync::watch::Receiver<Option<u32>>>,
    pub carwith_stretch: bool,
    pub screen_resource_lifecycle: bool,
    pub pipeline: Arc<Mutex<crate::pipeline::Pipeline>>,
}

/// 车机触摸（已是编码画布坐标）。
#[derive(Debug, Clone)]
pub struct TouchInput {
    pub action: u32,
    pub x: u32,
    pub y: u32,
    pub pointers: Vec<TouchPoint>,
    pub(crate) grant: Option<TouchGrant>,
}

#[derive(Debug, Clone)]
pub(crate) struct TouchGrant {
    modes: std::sync::Weak<Mutex<crate::modes::Modes>>,
    pub(crate) generation: u64,
}

impl TouchGrant {
    pub(crate) fn new(modes: &Arc<Mutex<crate::modes::Modes>>, generation: u64) -> Self {
        Self { modes: Arc::downgrade(modes), generation }
    }

    pub(crate) fn same_as(&self, other: &Self) -> bool {
        self.generation == other.generation && self.modes.ptr_eq(&other.modes)
    }

    pub(crate) fn with_current<T>(&self, action: impl FnOnce() -> T) -> Option<T> {
        let modes = self.modes.upgrade()?;
        let current = modes.lock();
        current.can_restore_screen(self.generation).then(action)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TouchPoint {
    pub id: u32,
    pub x: u32,
    pub y: u32,
}

pub struct AirplayOutcome {
    pub audio_resource: crate::audio_resource::AudioResource,
    pub aux_audio: Option<crate::aux_audio::Session>,
    pub control: Arc<RtspChannel>,
    pub event: Arc<RtspChannel>,
    pub audio: Option<AudioOut>,
    pub keepalive: KeepaliveTasks,
    pub teardown_streams: Vec<Value>,
    pub resource_session: Option<Arc<crate::screen_session::ScreenSession>>,
}

/// URI ownership is per control connection, independent of vehicle profiles.
fn install_session_uri(channel:&RtspChannel,host:&str)->Result<(),String> {
    let id=uuid::Uuid::new_v4();
    let session_id=u64::from_be_bytes(std::array::from_fn(|i|id.as_bytes()[i]));
    channel.set_session_uri(crate::rtsp_uri::SessionUri::new(host,session_id)?)?;
    emit_log("SESSION-URI: absolute target enabled for all profiles; one UUID-derived u64; no explicit port; shared SETUP/screen/RECORD/TEARDOWN");
    Ok(())
}

/// 跑完整个 AirPlay 协商，返回屏幕/音频发送器（交给 pump 任务）。
pub async fn negotiate(
    read: OwnedReadHalf,
    write: OwnedWriteHalf,
    p: &AirplayParams,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    shutdown: Arc<crate::shutdown::EngineShutdown>,
    screen_ready: tokio::sync::mpsc::Sender<crate::screen_session::ScreenCommand>,
    pending_cleanup: &mut Option<crate::negotiation_cleanup::NegotiationCleanup>,
    receiver_audio_generation: Arc<std::sync::atomic::AtomicU64>,
) -> Result<AirplayOutcome, String> {
    let stop = shutdown.signal.clone();
    let mbux_media_compat = p.mbux_media_compat && !p.desktop_receiver;
    let spawn_control = |read, write| {
        let (control, inbound) = RtspChannel::spawn(read, write, None);
        p.pipeline.lock().control_trace_id = Some(control.trace_id());
        if mbux_media_compat { control.enable_mbux_session_header(); }
        (control, inbound)
    };
    let (mut channel, mut control_inbound) = spawn_control(read, write);
    emit_log(&format!("AIRPLAY: 控制连接 RTSP#{} 开始", channel.trace_id()));

    // 保留配对前的 best-effort 身份读取，供旧凭证公钥索引兼容使用。
    // 此阶段的 455 不表示缺少显示能力；显示信息在会话 SETUP 成功后重新读取。
    let mut info = if p.id9_screen_bootstrap {
        emit_log("ID9: pair first; defer /info until authenticated session SETUP");
        Value::dict(vec![])
    } else { match fetch_info(&channel).await {
        Ok(v) => v,
        Err(e) => {
            emit_log(&format!(
                "AIRPLAY: /info 不可用（{e}）——未配对连接上被 455 拒是已知行为，\
                 继续走 pair-verify（显示尺寸/能力先用设置值），会话 SETUP 成功后再读取能力"
            ));
            Value::dict(vec![])
        }
    }
    };
    // CarWith prewarms before pairing. Give its encoder INIT this receiver's
    // declaration as soon as we have it, even on the first uncached connection.
    let early_connection = channel.trace_id();
    let early_display = if p.id9_screen_bootstrap { select_id9_display(&info) } else { select_display(&info) };
    let early_rate = early_display.and_then(|d| Some((uuid_text(d.get("uuid")), display_max_fps(d)?)));
    let early_carwith_size = p.carwith_display.as_ref().zip(early_display).map(|(requested, display)| {
        let size = read_display_size(display);
        let max_fps = early_rate.as_ref().map(|(_, fps)| *fps);
        requested.send_modify(|d| {
            if size.0 >= 2 && size.1 >= 2 { d.size = size; }
            d.max_fps = max_fps;
            d.confirmed = false;
        });
        emit_log(&format!("CARWITH-SIZE: pre-SETUP /info display={}x{} maxFPS={max_fps:?}", size.0, size.1));
        size
    });
    let receiver_pk = info
        .get("pk")
        .and_then(Value::as_bytes)
        .and_then(|b| <[u8; 32]>::try_from(b).ok());

    // ---- 2. 配对：**先试 pair-verify（快路径），不行再 pair-setup** ----
    //
    // 为什么顺序要反过来：pair-setup 会在车机上**注册一条新配对**。我们以前每次都先
    // 做 pair-setup，第二次上车车机那边已经有我们的记录、又不接受重复注册，结果
    // 车机报"连接失败"、手机报"没发现 CarPlay 车机"，只有两边都删除配对才能重来
    // （2026-10-25 现场）。真 iPhone 的第二次连接只走 pair-verify，不碰 pair-setup。
    //
    // 因此：有身份就先 verify；verify 失败（未配对/车机重置过）才 pair-setup 再 verify。
    let identity = ControllerIdentity::load_or_create(std::path::Path::new(&p.identity_path))
        .map_err(|e| format!("身份载入失败: {e}"))?;
    let pair_log = |s: &str| emit_log(&format!("PAIR: {s}"));

    let mut shared_secret: Option<[u8; 32]> = None;

    // 与**这个接收端**是否配对过：pair-verify 只能验证"已存在的配对"。
    // 对 WinPlay/DiPlay 这类全新接收端，我们此前直接跳过快路径 verify，对方根本没有我们的
    // 配对记录，于是 verify 表面走完、派生的密钥却对不上，之后每个加密请求都被静默丢弃
    // （2026-10-25 23:19 DiPlay 现场：POST /command 与 SETUP /setup 一律无响应）。
    //
    // 标记的键 = **接收端的公钥指纹**（/info.pk 前 4 字节），拿不到才退回 IP：
    //   同一台手机可以连多台接收端，IP 常常一样（192.168.1.35 / 172.16.222.97），
    //   用 IP 当键会把"另一台没配过的接收端"误判成已配对；公钥指纹则是每台接收端唯一且稳定。
    // 标记文件与身份文件同目录（每台手机各自一份），不会跨设备互相覆盖。
    let legacy_receiver_tag = match receiver_pk {
        Some(pk) => format!("{:02x}{:02x}{:02x}{:02x}", pk[0], pk[1], pk[2], pk[3]),
        None => p.car_ip.replace('.', "_"),
    };
    let receiver_tag = credential_tag(p.receiver_id.as_deref(), &legacy_receiver_tag);
    let paired_marker = format!("{}.paired-{}", p.identity_path, receiver_tag);
    let legacy_marker = format!("{}.paired-{}", p.identity_path, legacy_receiver_tag);
    // 旧 IP 标记不能证明当前车机已授权。只有匹配到当前 /info 公钥时才可使用旧标记。
    let paired_before = std::path::Path::new(&paired_marker).exists()
        || ((receiver_pk.is_some() || p.receiver_previously_connected) && std::path::Path::new(&legacy_marker).exists());
    emit_log(&format!("PAIR-EVIDENCE: 本机身份=存在 本车凭证={} 曾成功会话={} 来源={}；凭证仅是本地历史，仍须 verify 通过", paired_before, p.receiver_previously_connected,
        if p.receiver_id.is_some() { "蓝牙车机身份" } else { "公钥/IP兼容键" }));
    emit_log(&format!(
        "PAIR: 身份 {}… / 接收端 {}（{}）——{}",
        format!("{:02x}{:02x}", identity.public_key[0], identity.public_key[1]),
        receiver_tag,
        p.car_ip,
        if paired_before { "已配对，走 verify 快路径" } else { "未配对，先 pair-setup" }
    ));

    // 2a. 快路径：只在本机与这个接收端配对过时才直接 verify
    if paired_before {
        let mut transport = RtspTransport { channel: &channel };
        match PairVerifyClient::run(&identity, &mut transport, receiver_pk.as_ref(), &pair_log) {
            Ok(keys) => {
                channel.set_cipher(ControlCipher::new(keys.read_key, keys.write_key));
                emit_log("AIRPLAY: ✓ pair-verify 直接用上次配对完成（跳过 pair-setup，不再重复注册）");
                    let _ = std::fs::write(&paired_marker, b"1");
                shared_secret = Some(keys.shared_secret);
            }
            Err(e) => {
                emit_log(&format!(
                    "PAIR: pair-verify 未通过（{e}）——按真 iPhone 逻辑先做 pair-setup 注册本机"
                ));
                // 失败会污染这条连接的状态机：换一条新的再做 pair-setup
                match factory.open_tcp(&p.car_ip, p.airplay_port, 5_000) {
                    Some(stream) => {
                        let (r, w) = stream.into_split();
                        let (fresh, rx) = spawn_control(r, w);
                        channel = fresh;
                        control_inbound = rx;
                        emit_log("PAIR: 已换新控制连接");
                    }
                    None => emit_log("PAIR: ⚠ 新控制连接打开失败，沿用旧连接"),
                }
            }
        }
    }

    // 2b. 慢路径：pair-setup → pair-verify
    if shared_secret.is_none() {
        let mut pairing_forbidden = false;
        let mut pairing_unsupported = false;
        {
            let mut transport = RtspTransport { channel: &channel };
            match PairSetupClient::run(&identity, &mut transport, &pair_log) {
                Ok(_) => {
                    emit_log("PAIR: ✓ pair-setup 完成（长期公钥已交换）");
                    let _ = std::fs::write(&paired_marker, b"1");
                }
                Err(e) => {
                    let lower = e.to_ascii_lowercase();
                    if lower.contains("403") {
                        pairing_forbidden = true;
                        emit_log(
                            "PAIR: pair-setup 403（接收端已通过 MFi 信任本机）→ 换新连接走 pair-verify 快路径",
                        );
                    } else if ["404", "405", "455", "501"].iter().any(|s| lower.contains(s)) {
                        pairing_unsupported = true;
                        emit_log(&format!(
                            "PAIR: pair-setup 不被支持（{e}）→ pre-HomeKit 老车机，走明文 et=NONE"
                        ));
                    } else {
                        emit_log(&format!("PAIR: pair-setup 失败（{e}）→ 仍尝试 pair-verify"));
                    }
                }
            }
        }

        if pairing_forbidden {
            match factory.open_tcp(&p.car_ip, p.airplay_port, 5_000) {
                Some(stream) => {
                    let (r, w) = stream.into_split();
                    let (fresh, rx) = spawn_control(r, w);
                    channel = fresh;
                    control_inbound = rx;
                    emit_log("PAIR: 已换新控制连接");
                }
                None => emit_log("PAIR: ⚠ 新控制连接打开失败，沿用旧连接（可能仍被 403 污染）"),
            }
        }

        if !pairing_unsupported {
            let mut transport = RtspTransport { channel: &channel };
            match PairVerifyClient::run(&identity, &mut transport, receiver_pk.as_ref(), &pair_log) {
                Ok(keys) => {
                    channel.set_cipher(ControlCipher::new(keys.read_key, keys.write_key));
                    emit_log("AIRPLAY: ✓ pair-verify 完成，控制通道已加密");
                    let _ = std::fs::write(&paired_marker, b"1");
                    shared_secret = Some(keys.shared_secret);
                }
                Err(e) => {
                    emit_log(&format!(
                        "AIRPLAY: pair-verify 未通过（{e}）——按 et=NONE 明文会话继续"
                    ));
                }
            }
        }
    }

    // Pairing retries above can replace the control connection. Bind the URI only to
    // the final channel; every shared stream/cleanup then reuses it without another ID.
    install_session_uri(&channel,&p.car_ip)?;
    let mut box_bootstrap = !p.nbt_audio_first && !p.id9_screen_bootstrap && use_box_bootstrap(p.bmw_box_bootstrap, &info);
    if box_bootstrap { crate::auth_setup::run(&channel).await?; }
    else if p.mbux_screen_bootstrap {
        emit_log("MBUX: screen-before-RECORD bootstrap; auth-setup=true minimal_features=true latencyMs=100 deferred_audio=true");
        if shared_secret.is_none() {
            return Err("MBUX requires successful pair-verify; legacy SAP-only media is not enabled".into());
        }
        crate::auth_setup::run_with_policy(&channel, true).await?;
    }

    if p.id9_screen_bootstrap && shared_secret.is_none() {
        return Err("ID9 requires successful pair-verify; no plaintext media fallback".into());
    }

    let cleanup = pending_cleanup.insert(crate::negotiation_cleanup::NegotiationCleanup::new(channel.clone(), p.id9_screen_bootstrap));
    let result = async {
    // CatPlay bootstrap: 会话 SETUP → /info → 事件通道/保活 → 资源声明 → 屏幕流。
    let control_local = channel.local_addr().await.map_err(|e| format!("读取控制连接本地地址失败: {e}"))?;
    let timing = TimingServer::start(control_local, |socket| factory.bind_timing_network(socket), p.pipeline.clone())
        .map_err(|e| format!("计时服务启动失败 ({control_local}): {e}"))?;
    let propose_buffered = p.proposes_buffered_media(shared_secret.is_some(), &info);
    let cluster = crate::cluster_screen::Offer::prepare(&info, shared_secret.is_some(), p.mbux_screen_bootstrap);
    let (root, session_info, hevc_proposed, buffered_offered) = setup_and_read_info(
        &channel, &p.device_id, &p.device_name, timing.port, shared_secret.is_some(), info, p.mbux_screen_bootstrap, p.id9_screen_bootstrap, mbux_media_compat, p.bmw_capabilities, p.video_codec == "hevc", propose_buffered, Some(&mut *cleanup), p.wifi_mac_address.as_deref(), cluster, p.request_receiver_stats,
    ).await?;
    let same_connection = early_connection == channel.trace_id();
    info = session_info.value;
    let desktop = effective_desktop(p, &info);
    let id9_modes = p.id9_screen_bootstrap.then(|| {
        crate::modes::Modes::id9_initial(&info, p.enable_audio,
            channel.id9_trace.get().map(String::as_str).unwrap_or("ID9"))
    });
    let hevc_allowed=negotiated_hevc(hevc_proposed,&root);
    crate::emit_video_capabilities(hevc_allowed);
    emit_log(&format!("VIDEO-CODEC: preferred={} hevc_proposed={hevc_proposed} hevc_accepted={hevc_allowed}",p.video_codec));
    for line in audio_capability_lines(&info) { emit_log(&line); }
    let nbt_mode = p.nbt_audio_first && !desktop;
    if nbt_mode && !crate::nbt::matches(&info) {
        return Err("ID6 profile requires NBT Evo receiver identity; select the MGU/IDC23 profile for those platforms".into());
    }
    if nbt_mode && p.enable_audio && shared_secret.is_none() {
        return Err("ID6 audio path requires pair-verify; no plaintext audio fallback".into());
    }
    // SETUP may be the first point at which /info reveals the real receiver identity.
    box_bootstrap = use_box_bootstrap(box_bootstrap, &info);
    emit_log(&format!("AIRPLAY: effective BMW box bootstrap={box_bootstrap} requested={}", p.bmw_box_bootstrap));
    let event_port = port_of(root.get("eventPort"));
    let keep_alive_port = port_of(root.get("keepAlivePort"));
    emit_log(&format!(
        "AIRPLAY: ✓ 会话 SETUP eventPort={event_port} keepAlive={keep_alive_port:?} enabledFeatures={:?}",
        root.get("enabledFeatures").and_then(Value::as_array)
    ));

    // 按会话 SETUP 后的 /info 选择显示 UUID、坐标空间和屏幕保活格式。
    let keep_alive_stats = p.keep_alive_stats
        || info
            .get("keepAliveSendStatsAsBody")
            .and_then(Value::as_bool)
            .unwrap_or(false);
    let display = if p.id9_screen_bootstrap { select_id9_display(&info) } else { select_display(&info) };
    let display_uuid = display.and_then(|d| uuid_text(d.get("uuid")));
    if display.is_none() && info.get("displays").and_then(Value::as_array)
        .is_some_and(|entries| entries.iter().any(|d| d.get("type").and_then(Value::as_int) == Some(111))) {
        return Err("车机声明了副屏，但缺少可用主屏，已取消主屏 SETUP".into());
    }
    let mut limited_ui = crate::receiver_ui::LimitedUi::from_info(&info);
    if let Some(initial) = limited_ui.update(&info) {
        crate::emit_receiver_ui("setLimitedUI", initial);
    }
    let area_source = display.filter(|d| d.get("viewAreas").and_then(Value::as_array).is_some()).unwrap_or(&info);
    crate::display_layout::log_declaration(area_source, display_uuid.as_deref(), emit_log);
    if p.id9_screen_bootstrap && display_uuid.is_none() {
        return Err("ID9 /info lacks a valid main display UUID/size; refusing guessed screen SETUP".into());
    }
    let (disp_w, disp_h) = display.map(read_display_size)
        .or_else(|| early_carwith_size.filter(|_| same_connection && info.get("displays").is_none()))
        .unwrap_or((0, 0));
    let early_rate = early_rate.as_ref().filter(|_| same_connection).map(|(uuid, fps)| (uuid.as_deref(), *fps));
    let max_fps = session_display_max_fps(&info, display, session_info.fresh, early_rate);
    {
        let mut pipeline = p.pipeline.lock();
        pipeline.display_size = (disp_w >= 2 && disp_h >= 2).then_some((disp_w,disp_h));
        pipeline.display_max_fps = max_fps;
    }
    if let Some(requested) = &p.carwith_display {
        requested.send_modify(|d| {
            // An unknown live canvas leaves the prewarm dimensions available as
            // the source's INIT fallback. It is not a confirmed resize target.
            d.size = (disp_w, disp_h);
            d.max_fps = max_fps;
            d.confirmed = true;
        });
    }
    // 车机能力位图：判断是否支持 HEVC 等能力，只能靠它（codec 由本机编码器决定）
    let car_features = info.get("features").and_then(Value::as_int).unwrap_or(0);
    emit_log(&format!(
        "AIRPLAY: /info display uuid={:?} {}x{} features=0x{:x}",
        display_uuid, disp_w, disp_h, car_features
    ));

    // The receiver defines the viewport ratio, not the phone's encoding workload.
    // Kotlin chooses a proportional capped canvas and reports the actual size before its first frame.
    crate::emit_display_size(disp_w, disp_h, max_fps);
    if let Some(layout) = crate::display_layout::read(area_source, disp_w, disp_h, display_uuid.as_deref()) {
        emit_log(&format!("DISPLAY: canvas={disp_w}x{disp_h} view={:?} safe={:?} drawOutside={}", layout.view,layout.safe,layout.draw_outside));
        let negotiated = root.get("enabledFeatures").and_then(Value::as_array)
            .is_some_and(|features| features.iter().any(|f| f.as_str() == Some("viewAreas")));
        let areas = channel.view_areas.get_or_init(|| crate::display_layout::ViewAreas::new(
            area_source, disp_w, disp_h, display_uuid.as_deref(), negotiated,
            crate::display_layout::right_hand_drive(&info)));
        areas.publish_initial(&layout);
    }

    // ---- 5. 事件通道 ----
    if event_port == 0 {
        return Err("SETUP 未返回 eventPort".to_string());
    }
    let event_stream = factory
        .open_tcp(&p.car_ip, event_port, 5_000)
        .ok_or_else(|| format!("事件通道连接失败 {}:{event_port}", p.car_ip))?;
    let (ev_r, ev_w) = event_stream.into_split();
    let initial_modes = if let Some(initial) = id9_modes {
        initial
    } else if p.mbux_screen_bootstrap {
        crate::modes::Modes::from_info_with_audio_policy(&info, p.enable_audio, false, true)
    } else { crate::modes::Modes::from_info(&info, p.enable_audio, box_bootstrap) };
    let modes = Arc::new(Mutex::new(initial_modes));
    p.pipeline.lock().touch_modes = Arc::downgrade(&modes);
    let screen_owned = Arc::new(std::sync::atomic::AtomicBool::new(modes.lock().screen_owned()));
    let audio_owned = Arc::new(std::sync::atomic::AtomicBool::new(modes.lock().audio_owned()));
    channel.buffered_ownership.publish(modes.lock().audio_can_send());
    let reply_buffered = channel.buffered_ownership.clone();
    let reply_audio = audio_owned.clone();
    let reply_audio_generation = receiver_audio_generation.clone();
    let audio_unborrow = (!desktop && p.enable_audio).then(|| channel.audio_unborrow_generation.clone());
    let unborrow_control = Arc::downgrade(&channel);
    let rtsp_id = channel.trace_id();
    let reply_modes = modes.clone();
    let main_screen_id = display_uuid.clone().unwrap_or_else(|| "6E4E3B31-0E5B-4A8F-9F1B-2C5D7E9A0C11".into());
    let reply_display = main_screen_id.clone();
    let reply_owner = screen_owned.clone();
    // Startup claims also update the pump gate. Track the last UI notification separately
    // so a local regrant is published before the next incoming focus or page request.
    let last_ui_owner = std::sync::atomic::AtomicBool::new(screen_owned.load(std::sync::atomic::Ordering::Acquire));
    let reply_pipeline=p.pipeline.clone();
    let event_trace = channel.id9_trace.get().cloned();
    let event_health = p.pipeline.clone();
    let event_owner = crate::callback_context::current();
    let observe_event: crate::rtsp::EventObserver = Arc::new(move |id, cause| {
        let (at, mut sample) = {
            let health = event_health.lock();
            (crate::timing::monotonic_nanos() / 1_000_000, health.last_video_tcp().cloned())
        };
        if let Some(tcp) = &mut sample { tcp.age_ms = at.saturating_sub(tcp.sampled_ms); }
        if let Some(owner) = &event_owner {
            crate::callback_context::sync_scope(owner.clone(), || {
                crate::emit_event_channel(id, at, cause, sample.as_ref());
            });
        }
    });
    let apply_receiver_modes: crate::rtsp::CommandReply = Arc::new(move |message| {
        if !message.first_line.contains("/command") { return None; }
        let command = bplist::parse(&message.body).ok()?;
        let mut modes = reply_modes.lock();
        capture_hid_screen_grant(message, &command, &modes);
        if command.get("type").and_then(Value::as_str) == Some("requestUI")
            && crate::receiver_ui::main_target(&command, Some(&reply_display)) { modes.request_ui(); }
        let response = modes.response(&command, &reply_audio_generation);
        // Publish the Take/return to the pause gate before another task can observe the new modes.
        if let Some(taken) = response.as_ref().and_then(|reply| reply.receiver_audio_take) {
            crate::emit_receiver_audio_take(taken);
        }
        let unborrow = audio_unborrow.as_ref().filter(|_| response.as_ref().is_some_and(|reply| reply.audio_unborrowed))
            .map(|generation| generation.fetch_add(1, std::sync::atomic::Ordering::AcqRel).wrapping_add(1));
        let screen_owned = modes.screen_owned();
        reply_owner.store(screen_owned, std::sync::atomic::Ordering::Release);
        reply_buffered.publish_with_gate(&reply_audio, modes.audio_can_send());
        if let Some(unborrow) = unborrow {
            emit_log(&format!("AUDIO-RESUME: RTSP#{rtsp_id} unborrow={unborrow} event=main_audio_returned monoNs={}", crate::timing::monotonic_nanos()));
            if let Some(control) = unborrow_control.upgrade() { control.audio_unborrow_wake.notify_one(); }
        }
        let mut health=reply_pipeline.lock();
        health.screen_owned=Some(modes.screen_owned());
        if command.get("type").and_then(Value::as_str)==Some("requestUI") { health.ui_requests+=1; }
        drop(health);
        if matches!(command.get("type").and_then(Value::as_str), Some("changeModes") | Some("requestUI")) {
            let prefix = event_trace.as_deref().unwrap_or("MODES:");
            emit_log(&format!("{prefix} event applied type={} {}; reply_bytes={}",
                command.get("type").and_then(Value::as_str).unwrap_or("?"),modes.diagnostics(),response.as_ref().map_or(0, |r| r.body.len())));
        }
        drop(modes);
        if last_ui_owner.swap(screen_owned, std::sync::atomic::Ordering::Relaxed) != screen_owned {
            crate::emit_receiver_ui("screenOwned", serde_json::json!({"owned": screen_owned}));
        }
        response.map(|reply| reply.body)
    });
    let (event_channel, event_rx) = channel.spawn_event(ev_r, ev_w, Some(apply_receiver_modes.clone()), observe_event);

    emit_log(&format!("AIRPLAY: 事件连接 RTSP#{} 建立，port={event_port}", event_channel.trace_id()));
    if let Some(trace) = channel.id9_trace.get() {
        emit_log(&format!("{trace} control=RTSP#{} event=RTSP#{}", channel.trace_id(), event_channel.trace_id()));
    }
    if let Some(shared) = &shared_secret {
        let (read_key, write_key) = derive_event_keys(shared);
        event_channel.set_cipher(ControlCipher::new(read_key, write_key));
    }
    let event_for_bootstrap = event_channel.clone();
    let mut keepalive = KeepaliveTasks(Vec::new(), None, None);
    // Both rollback and manual pause keep the event socket until control teardown finishes.
    cleanup.event = Some(event_channel.clone());
    let tunnel = shared_secret.filter(|_| is_diplay_receiver(&info) && root.get("enabledFeatures")
        .and_then(Value::as_array).is_some_and(|features| features.iter().any(|feature| feature.as_str() == Some("iAPChannel"))))
        .map(|shared| crate::iap2_tunnel::Target { host: p.car_ip.clone(), factory: factory.clone(), shared });
    let wifi_iap = crate::wifi_iap2::WifiIap2::start(channel.clone(), &p.device_id, &p.device_name, &p.head_unit_profile, stop.clone(), tunnel);
    let wifi_iap_id = wifi_iap.id;
    p.pipeline.lock().wifi_id=wifi_iap_id;
    let initially_owned=modes.lock().screen_owned();
    p.pipeline.lock().screen_owned=Some(initially_owned);
    let screen_session=Arc::new(crate::screen_session::ScreenSession {
        control:channel.clone(), shutdown, wifi_iap_id, factory:factory.clone(), params:p.clone(), shared:shared_secret,
        software_receiver:p.desktop_receiver || !use_box_bootstrap(true, &info),
        uuid:main_screen_id.clone(),
        hevc_allowed, keep_stats:keep_alive_stats, display:(disp_w,disp_h), owned:screen_owned.clone(),
        commands:screen_ready, transport_failures:tokio::sync::watch::channel(None).0,
        bootstrap_event: if p.id9_screen_bootstrap { Some(event_for_bootstrap) } else { None },
        bootstrap_hids: if p.id9_screen_bootstrap { id9_hid_ids(&info, display_uuid.as_deref()) } else { Vec::new() },
        activated:std::sync::atomic::AtomicBool::new(false), resource_state:tokio::sync::Mutex::new(Default::default()),
    });
    let cluster = crate::cluster_screen::Commands::new(&info, &root, screen_session.clone(), &mut keepalive.0);
    let (control_ui_tx, control_ui_rx) = mpsc::unbounded_channel();
    let current = screen_session.clone();
    keepalive.0.push(crate::wifi_iap2::spawn_control_ingress(control_inbound, wifi_iap_id, stop.clone(), session_info.setup_accepted_at,
        move || current.session_is_live(), control_ui_tx));
    keepalive.0.push(spawn_event_dispatcher(
        event_rx,
        control_ui_rx,
        apply_receiver_modes,
        channel.clone(),
        p.pipeline.clone(),
        cluster,
        wifi_iap_id,
        modes.clone(),
        p.screen_resource_lifecycle.then(|| screen_session.clone()),
        touch_tx,
        key_tx,
        crate::consumer_hid::ConsumerHid::for_session(wifi_iap_id, &info, &main_screen_id),
        limited_ui,
        Some(main_screen_id),
        // 触摸的「显示空间」分母，优先级：
        //  ① /info 报的显示器尺寸（真车会给真实值）
        //  ② `--touch-display` 手动指定（仅无有效上报时使用；历史 WinPlay 按窗口
        //     2732x794 算 HID 坐标；这个历史兜底不是宝马的固定尺寸）
        //  ③ 协商的屏幕尺寸
        // 绝不能用 max(1) 当分母：x_raw*编码宽/1 溢出后被 clamp 到边界，
        // 表现是「每一下都落在右下角」（2026-10-23 23:38 定位）。
        {
            let (dw, dh) = match (disp_w, disp_h) {
                (0, _) | (_, 0) => p.touch_display.unwrap_or((p.width, p.height)),
                v => v,
            };
            (dw.max(1), dh.max(1))
        },
        TouchSurface::for_receiver(&info, box_bootstrap && p.bmw_capabilities && !p.desktop_receiver
            && p.carwith_display.is_some(), (disp_w,disp_h), p.touch_rect, &p.pipeline),
        p.touch_size.clone(),
        stop.clone(),
    ));

    // 激活等待与后续音频协商期间也必须保持会话存活。
    // 句柄由协商结果持有；协商失败/取消时 Drop 自动停止本轮任务。
    let receiver_model = info.get("model").and_then(Value::as_str).unwrap_or("");
    let idle_only = crate::udp_keepalive::idle_only(&p.head_unit_profile, p.id9_screen_bootstrap, mbux_media_compat, desktop);
    let mut heartbeat_tasks = spawn_keepalive_loops(
        channel.clone(), factory.clone(), keep_alive_port, p.car_ip.clone(), stop.clone(), Some(wifi_iap_id), idle_only,
        p.pipeline.clone(),
    );
    keepalive.0.append(&mut heartbeat_tasks.0);
    emit_log("AIRPLAY: 事件通道已建立，UDP/feedback 保活已启动（RECORD 前）");
    keepalive.1 = Some(wifi_iap);
    keepalive.2 = Some(timing);
    // BMW box activates the session before resource negotiation and video SETUP.
    let (declaration, initial_screen_revision) = {
        let m = modes.lock();
        (m.declaration(),m.screen_generation())
    };
    emit_log(&format!("TIMING: before RECORD {}",p.pipeline.lock().timing.summary()));
    let media_plan = || media_setup_candidates(&info, &p.audio_codec, nbt_mode,
        p.id9_screen_bootstrap || mbux_media_compat, p.carwith_media_rate.as_ref());
    let mut audio = None;
    if nbt_mode {
        emit_log("NBT: screen TCP -> audio SETUP -> single RECORD; media waits for activation");
        audio = screen_session.bootstrap_before_record_with(&modes, async {
            if !p.enable_audio { return Ok(None); }
            for codec in media_plan().await {
                if let Some(audio) = negotiate_audio(&channel,&factory,shared_secret.as_ref(),p,false,&info,codec).await {
                    return Ok(Some(audio));
                }
            }
            Err("NBT: no declared media format could be opened; RECORD not sent".into())
        }).await?;
    } else if p.mbux_screen_bootstrap || p.id9_screen_bootstrap {
        screen_session.bootstrap_before_record(&modes).await?;
    } else {
        activate_session(&channel, &declaration, &p.pipeline).await?;
    }
    emit_log(&format!("TIMING: after RECORD/resource response {}",p.pipeline.lock().timing.summary()));
    p.pipeline.lock().control_ready=true;

    let request_handoff = should_request_bluetooth_handoff(box_bootstrap, p.desktop_receiver, p.skip_bluetooth_handoff);
    let mgu_handoff = request_handoff && p.bmw_capabilities
        && (receiver_model == "MGU" || receiver_model.starts_with("MGU-"));
    if box_bootstrap && !p.desktop_receiver {
        if p.skip_bluetooth_handoff {
            emit_log("HANDOFF: disableBluetooth skipped by profile");
        } else if request_handoff && !mgu_handoff {
            let result=request_bluetooth_handoff(&channel, &p.device_id,
                || crate::wifi_iap2::is_active(wifi_iap_id), stop.clone()).await;
            match result {
                Some(Ok(reply)) => {
                    let business=bplist::parse(&reply.body).ok().and_then(|v|v.get("status").and_then(Value::as_int));
                    emit_log(&format!("HANDOFF: disableBluetooth rtsp_status={} business_status={business:?}; request only, keep_session_allowed={} wifi_phase={} {}",
                        reply.status(),crate::wifi_iap2::can_keep_generation(wifi_iap_id),crate::wifi_iap2::phase(wifi_iap_id),crate::wifi_iap2::ingress_summary(wifi_iap_id)));
                }
                Some(Err(error)) => emit_log(&format!("HANDOFF: disableBluetooth failed: {error}; no automatic resend")),
                None => return Err("交接请求取消或会话代次已失效".into()),
            }
        }
        if let Err(error) = screen_session.claim_box_startup_screen(&modes,initial_screen_revision).await {
            emit_log(&format!("BMW-MGU: startup screen claim failed: {error}; preserve control/audio and wait for receiver UI"));
        }
    }

    // On MGU the iPhone publishes the initial screen owner before asking the receiver
    // to retire Bluetooth. The receiver may retain the screen and may initiate iAP only later.
    let handoff = mgu_handoff.then(|| async {
        emit_log(&format!("HANDOFF: MGU initial screen decision complete; {}; wifi_phase={} {}",
            modes.lock().diagnostics(), crate::wifi_iap2::phase(wifi_iap_id), crate::wifi_iap2::ingress_summary(wifi_iap_id)));
        start_mgu_handoff(&mut keepalive.0, channel.clone(), p.device_id.clone(),
            move || crate::wifi_iap2::is_active(wifi_iap_id), stop.clone()).await?;
        if !crate::wifi_iap2::is_active(wifi_iap_id) { return Err("MGU handoff session expired".into()); }
        Ok(())
    });
    let retry_screen = activate_screen_before_handoff(&screen_session, &modes, handoff, mbux_media_compat).await?;
    // ---- 9. 音频流 SETUP（会话已激活后再试；失败只记录）----
    // The phone is the resource arbiter. CatPlay marks changeModes FromAccessory;
    // sending it to BMW returned 422. Initial local ownership was already published via
    // modesChanged before stream SETUP. Receiver borrow/take requests still gate packets.
    {
        let modes = modes.lock();
        channel.buffered_ownership.publish_with_gate(&audio_owned, modes.audio_can_send());
    }
    emit_log(&format!("AUDIO: enabled={} preference={} owner_controller={}", p.enable_audio, p.audio_codec, modes.lock().audio_owned()));
    let mut audio_retries = Vec::new();
    if p.enable_audio && !nbt_mode {
        let mut candidates = media_plan().await;
        if buffered_offered {
            prioritize_buffered_media(&info, &root, &mut candidates);
            emit_log(&format!("BUFFERED-AUDIO: session_features={:?} type103_media_output_aac48={} candidates={candidates:?}",
                root.get("enabledFeatures"), declares_media(&info, AudioCodec::BufferedAac)));
        }
        let mut buffered_failed = false;
        for codec in candidates {
            if buffered_failed { emit_log(&format!("BUFFERED-AUDIO: after type103 failure trying {codec:?} type={}", codec.stream_type())); }
            let (result, crossed_readiness) = crate::wifi_iap2::watch_media_attempt(
                || mbux_media_compat.then_some(wifi_iap_id).and_then(crate::wifi_iap2::transport_readiness),
                negotiate_audio(&channel, &factory, shared_secret.as_ref(), p, desktop, &info, codec),
            ).await;
            audio = result;
            if audio.is_some() { break; }
            if crossed_readiness && codec != AudioCodec::BufferedAac { audio_retries.push(codec); }
            buffered_failed |= codec == AudioCodec::BufferedAac;
        }
        if audio.is_none() { emit_log("AUDIO: no usable media stream; retain video/control"); }
    }

    // Retry only streams that failed before readiness or across a link reset.
    // A rejection entirely within one ready generation stays final.
    if retry_screen || (audio.is_none() && !audio_retries.is_empty()) {
        let ready = crate::wifi_iap2::wait_transport_ready(wifi_iap_id, stop.clone(), Duration::from_secs(3)).await;
        emit_log(&format!("MBUX-MEDIA: retry_barrier={ready} screen={retry_screen} audio={audio_retries:?}; no extra RECORD"));
        if ready {
            if retry_screen && crate::wifi_iap2::transport_ready(wifi_iap_id) {
                if let Err(error)=screen_session.reconcile_resource(&modes).await {
                    p.pipeline.lock().video_stream="FAILED".into();
                    emit_log(&format!("MBUX-MEDIA: one screen retry rejected: {error}; await real resource/UI event"));
                }
            }
            if audio.is_none() {
                for codec in audio_retries {
                    if !crate::wifi_iap2::transport_ready(wifi_iap_id) { break; }
                    audio = negotiate_audio(&channel, &factory, shared_secret.as_ref(), p, desktop, &info, codec).await;
                    if audio.is_some() {break;}
                }
            }
        }
    }

    if let Some(task) = crate::mic_probe::spawn(channel.clone(), factory.clone(), shared_secret, p, &info, stop.clone()) {
        keepalive.0.push(task);
    }
    p.pipeline.lock().audio_ready=audio.is_some();
    if let Some(audio) = audio.as_mut() {
        audio.set_ownership(audio_owned.clone());
        emit_log(&format!("AUDIO: actual sender rate={}Hz timestamp clock; SPF={}",audio.sample_rate(),audio.samples_per_packet()));
    }

    // Resource-managed screens can be returned or rejected. The stop path snapshots them
    // after the workers exit. Only the legacy path has an unconditional successful SETUP.
    let mut streams = Vec::new();
    if !p.screen_resource_lifecycle {
        streams.push(Value::dict(vec![("type", Value::int(110)), ("uuid", Value::str(&screen_session.uuid))]));
    }
    if let Some(audio) = audio.as_ref() { streams.push(Value::dict(vec![("type", Value::int(audio.stream_type()))])); }
    let speech_resource=Arc::new(crate::speech_resource::SpeechResource::new(channel.clone(),modes.clone(),audio_owned.clone()));
    let kinds = independent_audio_kinds(p, &info);
    let buffered_media = audio.as_ref().is_some_and(|out| out.stream_type() == 103);
    let realtime_media_rebuild = supports_realtime_media_rebuild(p, &info, desktop);
    let realtime_media = realtime_media_rebuild && audio.as_ref().is_some_and(|out| out.stream_type() == 102);
    let shared_input = p.microphone_probe.is_some() && crate::mic_probe::shared_format(&info).is_some();
    let call_input = p.carwith_media_rate.is_some() && crate::call_audio::formats(&info).next().is_some();
    let aux_audio=if shared_input || p.enable_audio && (!kinds.is_empty() || buffered_media || realtime_media || call_input) {
        shared_secret.map(|shared|crate::aux_audio::Session {
            kinds,
            control:channel.clone(),factory:factory.clone(),params:p.clone(),shared,info:info.clone(),resource:speech_resource.clone(),
            receiver_audio_generation: receiver_audio_generation.clone(),
            realtime_media_rebuild,
            media_codec:Mutex::new(audio.as_ref().map(|a|match (a.stream_type(), a.sample_rate()) {
                (103, _) => AudioCodec::BufferedAac, (102, 44100) => AudioCodec::Aac441, (102, _) => AudioCodec::Aac,
                (100, 44100) => AudioCodec::Pcm441, _ => AudioCodec::Pcm,
            })),
        })
    }else{None};
    Ok(AirplayOutcome {
        audio_resource: crate::audio_resource::AudioResource { receiver_audio_generation, control: channel.clone(), modes, owned: audio_owned, serial:speech_resource.serial() },
        aux_audio,
        control: channel,
        event: event_channel,
        audio,
        keepalive,
        teardown_streams: streams,
        resource_session: p.screen_resource_lifecycle.then(|| screen_session.clone()),
    })
    }.await;
    match result {
        Ok(outcome)=>{cleanup.transfer();Ok(outcome)}
        Err(error)=>{cleanup.finish().await;Err(error)}
    }
}

pub(crate) async fn activate_screen_before_handoff(
    screen_session: &crate::screen_session::ScreenSession,
    modes: &Arc<Mutex<crate::modes::Modes>>,
    handoff: Option<impl std::future::Future<Output = Result<(), String>>>,
    retry_on_readiness: bool,
) -> Result<bool, String> {
    let mut retry_screen = false;
    // ---- 6. 屏幕流 SETUP ----
    screen_session.activated.store(true, std::sync::atomic::Ordering::Release);
    emit_log(&format!("SCREEN-LIFECYCLE: session enabled={} initial={}", screen_session.params.screen_resource_lifecycle,modes.lock().diagnostics()));
    if screen_session.params.screen_resource_lifecycle {
        let (result, crossed_readiness) = crate::wifi_iap2::watch_media_attempt(
            || retry_on_readiness.then_some(screen_session.wifi_iap_id).and_then(crate::wifi_iap2::transport_readiness),
            screen_session.reconcile_resource(modes),
        ).await;
        if let Err(error) = result {
            retry_screen = crossed_readiness;
            screen_session.params.pipeline.lock().video_stream="FAILED".into();
            emit_log(&format!("SCREEN-LIFECYCLE: initial video failed: {error}; preserve audio/control"));
        }
        emit_state("connected", "控制会话就绪；视频通道随主屏使用权建立");
    } else {
        let screen = screen_session.open().await?;
        screen_session.install(screen).await?;
        emit_state("connected", "控制会话与视频通道就绪；车机显示尚未确认");
    }
    if let Some(request) = handoff { request.await?; }
    Ok(retry_screen)
}

pub(crate) fn independent_audio_kinds(_p: &AirplayParams, info: &Value) -> &'static [crate::aux_setup::Kind] {
    use crate::aux_setup::{supports, Kind};
    // Navigation has the same format and resource lifecycle for every PCM source.
    // Only this session's declared output capability authorizes its independent lane.
    let navigation = supports(info, Kind::Navigation);
    // Speech still borrows/restores main audio through SpeechResource. Select
    // that implemented lane from its exact output declaration, not identity.
    let speech = supports(info, Kind::Speech);
    match (speech, navigation) {
        (true, true) => &[Kind::Speech, Kind::Navigation],
        (true, false) => &[Kind::Speech],
        (false, true) => &[Kind::Navigation],
        (false, false) => &[],
    }
}

pub(crate) fn is_diplay_receiver(info: &Value) -> bool {
    ["name", "model", "manufacturer"].into_iter().any(|key|
        info.get(key).and_then(Value::as_str).is_some_and(|v| v.to_ascii_lowercase().contains("diplay")))
}
fn should_request_bluetooth_handoff(box_bootstrap: bool, desktop_receiver: bool, skip: bool) -> bool {
    box_bootstrap && !desktop_receiver && !skip
}

/// Request the receiver's CarPlay Bluetooth retirement once. Never disable Android Bluetooth.
async fn request_bluetooth_handoff(
    control: &RtspChannel, device: &str, current: impl Fn() -> bool, stop: Arc<Notify>,
) -> Option<Result<crate::rtsp::RtspMessage, String>> {
    bluetooth_handoff_transaction(control, device, current, stop, None).await
}

pub(crate) async fn start_mgu_handoff(
    tasks: &mut Vec<tokio::task::JoinHandle<()>>, control: Arc<RtspChannel>, device: String,
    current: impl Fn() -> bool + Send + 'static, stop: Arc<Notify>,
) -> Result<(), String> {
    let (written, flushed) = tokio::sync::oneshot::channel();
    tasks.push(crate::callback_context::spawn(async move {
        match bluetooth_handoff_transaction(&control, &device, current, stop, Some(written)).await {
            Some(Ok(reply)) => {
                let status = bplist::parse(&reply.body).ok().and_then(|v| v.get("status").and_then(Value::as_int));
                emit_log(&format!("HANDOFF: MGU disableBluetooth rtsp_status={} business_status={status:?}", reply.status()));
            }
            Some(Err(e)) => emit_log(&format!("HANDOFF: MGU disableBluetooth failed: {e}")),
            None => {}
        }
    }));
    flushed.await.map_err(|_| "MGU disableBluetooth cancelled or write failed".to_string())?;
    emit_log("HANDOFF: MGU disableBluetooth written after initial screen decision");
    Ok(())
}

async fn bluetooth_handoff_transaction(
    control: &RtspChannel, device: &str, current: impl Fn() -> bool, stop: Arc<Notify>,
    written: Option<tokio::sync::oneshot::Sender<()>>,
) -> Option<Result<crate::rtsp::RtspMessage, String>> {
    if !current() || control.is_closed() { return None; }
    let body=bplist::encode(&Value::dict(vec![
        ("type",Value::str("disableBluetooth")),
        ("params",Value::dict(vec![("deviceID",Value::str(device))])),
    ]));
    let reply=tokio::select! {
        biased;
        _=stop.notified()=>return None,
        reply=control.request_with_write_signal("POST","/command",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(3),written)=>reply,
    };
    if current() { Some(reply) } else { None }
}

/// Retire active resources while control is writable. Reserve a separate deadline for the
/// session request even when a receiver ignores all stream releases.
pub(crate) async fn finish_session(control: &RtspChannel, streams: &[Value], graceful: bool) {
    if graceful && !control.is_closed() {
        let buffered = control.buffered_cleanup_pending.load(std::sync::atomic::Ordering::Acquire)
            || streams.iter().any(|s| s.get("type").and_then(Value::as_int) == Some(103));
        // Let an already-written buffered command retire before the final close. A slow
        // or gone control peer still has a bounded shutdown path.
        let _buffered_guard = if buffered {
            tokio::time::timeout(Duration::from_secs(3), control.buffered_commands.lock()).await.ok()
        } else { None };
        let stream_deadline = tokio::time::Instant::now() + Duration::from_millis(900);
        let pending = Value::dict(vec![("type", Value::int(103))]);
        let mut ordered: Vec<_> = streams.iter().collect();
        if control.buffered_cleanup_pending.load(std::sync::atomic::Ordering::Acquire)
            && !streams.iter().any(|s| s.get("type").and_then(Value::as_int) == Some(103)) {
            ordered.push(&pending);
        }
        ordered.sort_by_key(|stream| stream.get("type").and_then(Value::as_int) == Some(110));
        for stream in ordered {
            let remaining = stream_deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() || control.is_closed() { break; }
            let kind = stream.get("type").and_then(Value::as_int);
            let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![stream.clone()]))]));
            send_close_request(control, &format!("stream type={kind:?}"), &body,
                remaining.min(Duration::from_millis(400))).await;
        }
        if !control.is_closed() {
            send_close_request(control, "session", &[], Duration::from_millis(800)).await;
        } else {
            emit_log("SESSION-CLOSE: peer closed before session TEARDOWN");
        }
    } else {
        emit_log("SESSION-CLOSE: 跳过 TEARDOWN（强制停止/控制通道已关闭）");
    }
    let _ = tokio::time::timeout(Duration::from_millis(200), control.shutdown()).await;
}

async fn send_close_request(control: &RtspChannel, scope: &str, body: &[u8], timeout: Duration) {
    let content_type = if body.is_empty() { "" } else { "application/x-apple-binary-plist" };
    match control.request("TEARDOWN", "/", body, content_type, &[], timeout).await {
        Ok(reply) => emit_log(&format!("SESSION-CLOSE: {scope} TEARDOWN status={} CSeq={:?}", reply.status(), reply.cseq())),
        Err(error) => emit_log(&format!("SESSION-CLOSE: {scope} TEARDOWN failed: {error}")),
    }
}

#[cfg(test)]
mod shutdown_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    async fn pair() -> (Arc<RtspChannel>, tokio::net::TcpStream) {
        let server = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(server.local_addr().unwrap()).await.unwrap();
        let (peer, _) = server.accept().await.unwrap();
        let (r, w) = client.into_split();
        (RtspChannel::spawn(r, w, None).0, peer)
    }
    #[tokio::test]
    async fn id9_diagnostics_match_wire_transactions_on_one_session() {
        // This current-thread loopback records just this test's log messages.
        struct Capture(std::thread::ThreadId, Mutex<Vec<String>>);
        impl log::Log for Capture {
            fn enabled(&self, _: &log::Metadata<'_>) -> bool { std::thread::current().id() == self.0 }
            fn log(&self, r: &log::Record<'_>) {
                if self.enabled(r.metadata()) { self.1.lock().push(r.args().to_string()); }
            }
            fn flush(&self) {}
        }
        let capture = Box::leak(Box::new(Capture(std::thread::current().id(), Mutex::new(Vec::new()))));
        log::set_logger(capture).unwrap();
        log::set_max_level(log::LevelFilter::Info);
        let (control, mut peer) = pair().await;
        install_session_uri(&control, "127.0.0.1").unwrap();
        let server = crate::callback_context::spawn(async move {
            let mut captures = Vec::new();
            for seq in 1..=4 {
                let mut wire = Vec::new();
                let mut chunk = [0; 2048];
                loop {
                    let n = peer.read(&mut chunk).await.unwrap();
                    assert!(n > 0);
                    wire.extend_from_slice(&chunk[..n]);
                    if !crate::rtsp::parse_rtsp_messages(&mut wire.clone()).unwrap().is_empty() { break; }
                }
                let body = if seq == 4 { vec![] } else { bplist::encode(&Value::dict(vec![])) };
                let status = if seq == 4 { "400 Bad Request" } else { "200 OK" };
                let head = format!("RTSP/1.0 {status}\r\ncSeQ: {seq}\r\nX-Test: first\r\nX-Test: second\r\nContent-Length: {}\r\n\r\n", body.len());
                peer.write_all(head.as_bytes()).await.unwrap();
                peer.write_all(&body).await.unwrap();
                captures.push((wire, head.into_bytes(), body));
            }
            captures
        });
        setup_and_read_info(&control, "phone", "test", 1234, true, Value::dict(vec![]),
            false, true, false, true, false, true, None, None, None, false).await.unwrap();
        for (method, path, body) in [
            ("POST", "/command", crate::modes::Modes::initial().declaration()),
            ("SETUP", "/", build_screen_setup(42, "display")),
        ] {
            control.request(method, path, &body, "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await.unwrap();
        }
        let packets = server.await.unwrap();
        let setup = crate::rtsp::parse_rtsp_messages(&mut packets[0].0.clone()).unwrap().remove(0);
        let plist = bplist::parse(&setup.body).unwrap();
        let sid = plist.get("sessionCorrelationUUID").unwrap().as_str().unwrap();
        let trace = format!("ID9 sid={sid}");
        assert_eq!(control.id9_trace.get(), Some(&trace));
        let initial = |raw: Option<Value>| {
            let info = Value::dict(raw.into_iter().map(|value| ("modes", value)).collect());
            crate::modes::Modes::id9_initial(&info, true, &trace)
        };
        assert!(!initial(None).screen_owned());
        assert!(!initial(Some(Value::str("bad-modes"))).screen_owned());
        assert!(initial(Some(Value::dict(vec![]))).screen_owned());
        let loan = Value::dict(vec![("resourceID",Value::int(1)),("transferType",Value::int(3)),
            ("unborrowConstraint",Value::int(1000))]);
        assert!(!initial(Some(Value::dict(vec![("resources",Value::array(vec![loan]))]))).screen_owned());
        let logs = capture.1.lock();
        for detail in ["INFO_MODES absent", "INFO_MODES part=1 text=String(\"bad-modes\")",
            "INITIAL_MODES source=default_missing", "INITIAL_MODES source=default_invalid",
            "INITIAL_MODES source=receiver screen_defaults=current,permanent,take,borrow,unborrow screen=1/1",
            "INITIAL_MODES source=receiver screen_defaults=permanent,take,borrow screen=2/1 revision=0 take=100 borrow=100 unborrow=1000 borrows=1"] {
            assert!(logs.iter().any(|line| line.starts_with(&format!("{trace} {detail}"))), "{detail}");
        }
        for (seq, kind) in [(1,"SESSION_SETUP"), (3,"RESOURCE"), (4,"FIRST_SCREEN_SETUP")] {
            let prefix = format!("ID9 sid={sid} {kind} RTSP#{} CSeq={seq}", control.trace_id());
            let (tx, rx_head, rx_body) = &packets[seq-1];
            for (tag, expected) in [("TX_PREPARED_PLAINTEXT",tx), ("RX_HEAD",rx_head), ("RX_BODY",rx_body)] {
                let tag = format!("{prefix} {tag}");
                assert!(logs.iter().any(|s| s.starts_with(&format!("{tag} bytes={} ",expected.len()))), "{tag}");
                let mut bytes = Vec::new();
                for line in logs.iter().filter(|s|s.starts_with(&format!("{tag} offset="))) {
                    assert!(line.contains(&format!("offset={} ",bytes.len())));
                    let hex = line.split_once(" hex=").unwrap().1;
                    bytes.extend((0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i+2],16).unwrap()));
                }
                assert_eq!(&bytes, expected, "{tag}");
            }
        }
    }

    #[tokio::test]
    async fn wifi_station_mac_is_scoped_to_real_bmw_and_invalid_addresses_fall_back() {
        for (bmw,mbux,id9,model,mac,expected) in [
            (true,false,false,"MGU",Some("06:12:34:56:78:90"),"06:12:34:56:78:90"),
            (false,false,false,"MGU",Some("06:12:34:56:78:90"),"phone"),
            (true,true,false,"MGU",Some("06:12:34:56:78:90"),"phone"),
            (true,false,true,"MGU",Some("06:12:34:56:78:90"),"phone"),
            (true,false,false,"DiPlay",Some("06:12:34:56:78:90"),"phone"),
            (true,false,false,"MGU",Some("02:00:00:00:00:00"),"phone"),
            (true,false,false,"MGU",Some("ff:ff:ff:ff:ff:ff"),"phone"),
            (true,false,false,"MGU",Some("not-a-mac"),"phone"),
            (true,false,false,"MGU",None,"phone"),
        ] {
            let (control,mut peer)=pair().await;
            let server=crate::callback_context::spawn(async move {
                let setup=read_request(&mut peer).await;
                assert!(setup.first_line.starts_with("SETUP "));
                let value=bplist::parse(&setup.body).unwrap();
                assert_eq!(value.get("deviceID").and_then(Value::as_str),Some("phone"));
                assert_eq!(value.get("macAddress").and_then(Value::as_str),Some(expected));
                for request in [Some(setup),None] {
                    let request=match request {Some(v)=>v,None=>read_request(&mut peer).await};
                    let body=bplist::encode(&Value::dict(vec![]));
                    peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",request.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();
                    peer.write_all(&body).await.unwrap();
                }
            });
            setup_and_read_info(&control,"phone","name",1234,true,Value::dict(vec![("model",Value::str(model))]),mbux,id9,false,bmw,false,false,None,mac, None, false).await.unwrap();
            server.await.unwrap();
        }
    }

    #[tokio::test]
    async fn common_session_uri_initialization_is_profile_independent_and_fresh_per_connection() {
        let mut targets=std::collections::HashSet::new();
        for _ in 0..3 {
            let (control,mut peer)=pair().await;
            install_session_uri(&control,"fe80::1%wlan0").unwrap();
            let server=crate::callback_context::spawn(async move {
                let mut target=None;
                for method in ["SETUP","SETUP","RECORD","TEARDOWN"] {
                    let request=read_request(&mut peer).await;
                    let parts:Vec<_>=request.first_line.split_whitespace().collect();assert_eq!(parts[0],method);
                    assert!(parts[1].starts_with("rtsp://[fe80::1%25wlan0]/"),"{}",request.first_line);
                    assert!(parts[1].rsplit('/').next().unwrap().parse::<u64>().is_ok());
                    if let Some(uri)=&target {assert_eq!(parts[1],uri);}else{target=Some(parts[1].to_string());}
                    peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",request.cseq().unwrap()).as_bytes()).await.unwrap();
                }
                target.unwrap()
            });
            for (method,path) in [("SETUP","/setup"),("SETUP","/"),("RECORD","/"),("TEARDOWN","/")] {
                control.request(method,path,&[],"application/x-apple-binary-plist",&[],Duration::from_secs(2)).await.unwrap();
            }
            assert!(targets.insert(server.await.unwrap()),"new control must allocate a fresh URI");
        }
    }
    async fn read_request(peer: &mut tokio::net::TcpStream) -> crate::rtsp::RtspMessage {
        read_request_with_timeout(peer, Duration::from_secs(1)).await
    }
    async fn read_request_with_timeout(peer: &mut TcpStream, timeout: Duration) -> crate::rtsp::RtspMessage {
        tokio::time::timeout(timeout, async {
            let mut data=Vec::new();
            loop {
                let mut buf=[0;4096]; let n=peer.read(&mut buf).await.unwrap(); assert!(n>0);
                data.extend_from_slice(&buf[..n]);
                if let Some(msg)=crate::rtsp::parse_rtsp_messages(&mut data).unwrap().pop() { return msg; }
            }
        }).await.unwrap()
    }

    #[tokio::test]
    async fn feedback_timeout_retries_with_new_cseq_and_ignores_late_ok() {
        let (control, mut peer) = pair().await;
        let mut tasks = spawn_keepalive_loops(
            control, Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
            Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        );
        let mut previous = 0;
        for attempt in 1..=4 {
            let request = read_request_with_timeout(&mut peer, Duration::from_secs(6)).await;
            assert_eq!(request.first_line, "POST /feedback RTSP/1.0");
            let cseq = request.cseq().unwrap();
            assert!(cseq > previous);
            // A timely 200 on attempt 2 renews the allowance. On attempt 4 only
            // the late response for attempt 3 arrives, so the worker must exit.
            let response = match attempt { 2 => Some(cseq), 4 => Some(previous), _ => None };
            if let Some(seq) = response {
                peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {seq}\r\nContent-Length: 0\r\n\r\n").as_bytes())
                    .await.unwrap();
            }
            previous = cseq;
        }
        tokio::time::timeout(Duration::from_secs(4), &mut tasks.0[0]).await
            .expect("two consecutive unanswered feedback requests must end the worker").unwrap();
    }

    #[tokio::test]
    async fn empty_feedback_500_leaves_control_open_until_transport_ends() {
        let (control, mut peer) = pair().await;
        let mut tasks = spawn_keepalive_loops(
            control.clone(), Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
            Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        );
        for cseq in 1..=2 {
            let request = read_request_with_timeout(&mut peer, Duration::from_secs(2)).await;
            assert_eq!(request.first_line, "POST /feedback RTSP/1.0");
            assert_eq!(request.cseq(), Some(cseq));
            peer.write_all(format!("RTSP/1.0 500 Internal Server Error\r\nCSeq: {cseq}\r\nContent-Length: 0\r\n\r\n").as_bytes())
                .await.unwrap();
        }
        let mut extra = [0];
        assert!(tokio::time::timeout(Duration::from_millis(1300), peer.read(&mut extra)).await.is_err());
        assert!(control.session_is_live(), "empty /feedback 500 is not a closed control transport");
        assert!(!tasks.0[0].is_finished());

        let (reply, ()) = tokio::join!(
            control.request("POST", "/command", &[], "application/octet-stream", &[], Duration::from_secs(1)),
            async {
                let request = read_request(&mut peer).await;
                assert_eq!(request.first_line, "POST /command RTSP/1.0");
                peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap()).as_bytes())
                    .await.unwrap();
            },
        );
        assert_eq!(reply.unwrap().status(), 200);
        peer.shutdown().await.unwrap();
        tokio::time::timeout(Duration::from_millis(500), &mut tasks.0[0]).await
            .expect("the worker must still observe control EOF").unwrap();
        assert!(!control.session_is_live());
    }

    #[tokio::test]
    async fn empty_feedback_500_after_success_still_observes_peer_teardown() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        let mut tasks = spawn_keepalive_loops(
            control.clone(), Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
            Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        );
        for (cseq, status) in [(1, 200), (2, 500), (3, 500)] {
            let request = read_request_with_timeout(&mut peer, Duration::from_secs(2)).await;
            assert_eq!(request.first_line, "POST /feedback RTSP/1.0");
            assert_eq!(request.cseq(), Some(cseq));
            peer.write_all(format!("RTSP/1.0 {status} Reply\r\nCSeq: {cseq}\r\nContent-Length: 0\r\n\r\n").as_bytes())
                .await.unwrap();
        }
        let mut extra = [0];
        assert!(tokio::time::timeout(Duration::from_millis(1300), peer.read(&mut extra)).await.is_err());
        assert!(control.session_is_live());
        peer.write_all(b"TEARDOWN rtsp://127.0.0.1/42 RTSP/1.0\r\nCSeq: 72\r\n\r\n").await.unwrap();
        let ack = read_request(&mut peer).await;
        assert_eq!((ack.status(), ack.cseq()), (200, Some(72)));
        tokio::time::timeout(Duration::from_millis(500), &mut tasks.0[0]).await
            .expect("peer teardown must still end the session").unwrap();
        assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "车机主动结束");
    }

    #[tokio::test]
    async fn feedback_rejection_retries_and_success_restores_the_retry() {
        let (control, mut peer) = pair().await;
        let mut tasks = spawn_keepalive_loops(
            control.clone(), Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
            Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        );
        for (cseq, status, body) in [(1, 500, ""), (2, 200, ""), (3, 500, ""), (4, 500, "error")] {
            let request = read_request_with_timeout(&mut peer, Duration::from_secs(2)).await;
            assert_eq!(request.first_line, "POST /feedback RTSP/1.0");
            assert_eq!(request.cseq(), Some(cseq));
            assert!(control.session_is_live());
            peer.write_all(format!("RTSP/1.0 {status} Reply\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n{body}", body.len()).as_bytes())
                .await.unwrap();
        }
        tokio::time::timeout(Duration::from_millis(500), &mut tasks.0[0]).await
            .expect("two consecutive rejections end the session").unwrap();
        assert!(!control.session_is_live());
        assert_eq!(control.end_detail(RtspEndCause::EventClosed), "控制保活失败");
    }

    #[tokio::test]
    async fn acknowledged_peer_teardown_ends_even_with_feedback_pending_and_sockets_open() {
        for on_event in [false, true] {
            let (control, mut control_peer) = pair().await;
            control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let event = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut event_peer, _) = listener.accept().await.unwrap();
            let (read, write) = event.into_split();
            let (_event, _inbound) = control.spawn_event(read, write, None, Arc::new(|_, _| {}));
            let mut tasks = spawn_keepalive_loops(
                control.clone(), Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
                Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
            );
            let feedback = read_request_with_timeout(&mut control_peer, Duration::from_secs(2)).await;
            assert_eq!(feedback.first_line, "POST /feedback RTSP/1.0");
            let peer = if on_event { &mut event_peer } else { &mut control_peer };
            peer.write_all(b"TEARDOWN rtsp://127.0.0.1/42 RTSP/1.0\r\nCSeq: 72\r\n\r\n").await.unwrap();
            let ack = read_request(peer).await;
            assert_eq!(ack.cseq(), Some(72));
            assert_eq!(ack.status(), 200);
            tokio::time::timeout(Duration::from_millis(500), &mut tasks.0[0]).await
                .expect("peer teardown must end without waiting for feedback or EOF").unwrap();
            assert_eq!(control.end_detail(RtspEndCause::FeedbackFailed), "车机主动结束");
            assert!(!control.is_closed());
        }
    }

    #[tokio::test]
    async fn invalid_control_batch_does_not_leave_teardown_pending() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        let mut tasks = spawn_keepalive_loops(
            control.clone(), Arc::new(super::display_size_tests::LocalSockets), 0, "127.0.0.1".into(),
            Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
        );
        let feedback = read_request_with_timeout(&mut peer, Duration::from_secs(2)).await;
        assert_eq!(feedback.first_line, "POST /feedback RTSP/1.0");
        peer.write_all(b"TEARDOWN / RTSP/1.0\r\nCSeq: 72\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: invalid\r\n\r\n").await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), &mut tasks.0[0]).await
            .expect("closing the control reader must finish the session").unwrap();
        assert!(!control.peer_teardown_pending());
        assert!(control.is_closed());
    }

    #[tokio::test]
    async fn rejected_hevc_session_retries_once_without_changing_other_fields() {
        let (control,mut peer)=pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("fe80::1%wlan0",66).unwrap()).unwrap();
        let server=crate::callback_context::spawn(async move {
            let first=read_request(&mut peer).await;
            assert_eq!(first.first_line,"SETUP rtsp://[fe80::1%25wlan0]/66 RTSP/1.0");
            let first_body=bplist::parse(&first.body).unwrap();
            assert_eq!(first_body.get("statsCollectionEnabled").and_then(Value::as_bool), Some(true));
            assert!(!first_body.get("features").unwrap().as_array().unwrap().iter()
                .any(|feature| feature.as_str() == Some("mainBuffered")));
            assert!(first_body.get("features").unwrap().as_array().unwrap().iter().any(|f|f.as_str()==Some("hevc")));
            peer.write_all(format!("RTSP/1.0 400 Bad Request\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",first.cseq().unwrap()).as_bytes()).await.unwrap();
            let retry=read_request(&mut peer).await;
            assert_eq!(retry.first_line,first.first_line);
            let mut expected=first_body.clone();
            if let Value::Dict(fields)=&mut expected {let entry=fields.iter_mut().find(|(k,_)|k.as_str()==Some("features")).unwrap();entry.1=Value::array(entry.1.as_array().unwrap().iter().filter(|f|f.as_str()!=Some("hevc")).cloned().collect());}
            assert_eq!(bplist::parse(&retry.body).unwrap(),expected);
            let accepted=bplist::encode(&Value::dict(vec![("enabledFeatures",Value::array(vec![Value::str("hevc")]))]));
            peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",retry.cseq().unwrap(),accepted.len()).as_bytes()).await.unwrap();peer.write_all(&accepted).await.unwrap();
            let info=read_request(&mut peer).await;assert!(info.first_line.starts_with("GET /info "));
            let body=bplist::encode(&Value::dict(vec![("model",Value::str("test"))]));
            peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",info.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();peer.write_all(&body).await.unwrap();
        });
        let result=setup_and_read_info(&control,"phone","name",1234,true,Value::dict(vec![]),false,true,false,true,true,false,None,None, None, true).await;
        assert!(result.is_ok(),"{result:?}");assert!(!result.unwrap().2);server.await.unwrap();
    }
    #[tokio::test]
    async fn session_setup_offers_buffered_even_if_early_info_says_realtime_only() {
        let early_info = Value::dict(vec![("audioFormats", Value::array(vec![Value::dict(vec![
            ("type", Value::int(102)), ("audioType", Value::str("media")), ("audioOutputFormats", Value::int(1 << 23)),
        ])]))]);
        let p = buffered_profile_params_for_test();
        let offer = p.proposes_buffered_media(true, &early_info);
        assert!(offer);
        let (channel, mut peer) = pair().await;
        let receiver = tokio::spawn(async move {
            for stage in ["SETUP", "GET"] {
                let msg = read_request(&mut peer).await;
                assert!(msg.first_line.starts_with(stage));
                if stage == "SETUP" {
                    let body = bplist::parse(&msg.body).unwrap();
                    assert!(body.get("features").unwrap().as_array().unwrap().contains(&Value::str("mainBuffered")));
                }
                let body = bplist::encode(&Value::dict(vec![("audioFormats", Value::array(vec![Value::dict(vec![
                    ("type", Value::int(103)), ("audioType", Value::str("media")), ("audioOutputFormats", Value::int(1 << 23)),
                ])]))]));
                peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
                    msg.cseq().unwrap(), body.len()).as_bytes()).await.unwrap();
                peer.write_all(&body).await.unwrap();
            }
        });
        let (setup, info, _, offered) = setup_and_read_info(&channel,"phone","name",1234,true,early_info,
            false,false,true,false,false,offer,None,None, None, false).await.unwrap();
        receiver.await.unwrap();
        assert!(offered);
        let mut candidates = vec![AudioCodec::Aac];
        prioritize_buffered_media(&info.value, &setup, &mut candidates);
        assert_eq!(candidates, [AudioCodec::BufferedAac, AudioCodec::Aac]);
    }

    #[tokio::test]
    async fn late_instrument_declaration_requires_an_accepted_offer_and_fresh_info() {
        for (withdraw, peer_accepts, fresh_info) in [
            (false, true, true), (false, false, true), (true, true, true), (false, true, false),
        ] {
            let screen = |kind,id| Value::dict(vec![("type", Value::int(kind)), ("uuid", Value::str(id)),
                ("widthPixels", Value::int(800)), ("heightPixels", Value::int(480))]);
            let cached = Value::dict(vec![("displays", Value::array(vec![
                screen(110,"71c97aaf-48eb-4d9a-91b6-fdd5e39a94b5"), screen(111,"1af5fe8c-b86a-4ede-8d08-2e29f0240980")
            ]))]);
            let info = cached.clone();
            let (channel, mut peer) = pair().await;
            let receiver = tokio::spawn(async move {
                let first = read_request(&mut peer).await;
                let mut expected = bplist::parse(&first.body).unwrap();
                assert!(expected.get("features").unwrap().as_array().unwrap().contains(&Value::str("altScreen")));
                assert_eq!(expected.get("altScreenURLs"), Some(&Value::array(vec![Value::str(crate::cluster_screen::MAP_URL)])));
                let accepted = if withdraw {
                    peer.write_all(format!("RTSP/1.0 400 Bad Request\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", first.cseq().unwrap()).as_bytes()).await.unwrap();
                    let retry = read_request(&mut peer).await;
                    assert_eq!(first.first_line, retry.first_line);
                    let Value::Dict(fields) = &mut expected else { panic!() };
                    fields.retain(|(k,_)| k.as_str() != Some("altScreenURLs"));
                    let (_,Value::Array(features)) = fields.iter_mut().find(|(k,_)| k.as_str()==Some("features")).unwrap() else { panic!() };
                    features.retain(|f| f.as_str()!=Some("altScreen"));
                    assert_eq!(bplist::parse(&retry.body).unwrap(), expected);
                    retry
                } else { first };
                let enabled = if peer_accepts { vec![Value::str("altScreen")] } else { vec![] };
                let setup = Value::dict(vec![("enabledFeatures", Value::array(enabled))]);
                for (i, body) in [setup, info].iter().enumerate() {
                    let cseq = if i == 0 { accepted.cseq().unwrap() } else {
                        let get = read_request(&mut peer).await;
                        assert!(get.first_line.starts_with("GET /info "));
                        get.cseq().unwrap()
                    };
                    if i == 1 && !fresh_info {
                        let rejection = |seq| format!("RTSP/1.0 455 Method Not Valid in This State\r\nCSeq: {seq}\r\nContent-Length: 0\r\n\r\n");
                        peer.write_all(rejection(cseq).as_bytes()).await.unwrap();
                        for method in ["POST /info ", "GET /info "] {
                            let next = read_request(&mut peer).await;
                            assert!(next.first_line.starts_with(method));
                            peer.write_all(rejection(next.cseq().unwrap()).as_bytes()).await.unwrap();
                        }
                        break;
                    }
                    let bytes = bplist::encode(body);
                    peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", bytes.len()).as_bytes()).await.unwrap();
                    peer.write_all(&bytes).await.unwrap();
                }
            });
            let previous = if fresh_info { Value::dict(vec![]) } else { cached };
            setup_and_read_info(&channel,"phone","name",1234,true,previous,false,false,false,true,false,false,
                None,None,Some(crate::cluster_screen::Offer::AwaitingDisplays), false).await.unwrap();
            receiver.await.unwrap();
            assert_eq!(channel.cluster_display.get().is_some(), !withdraw && peer_accepts && fresh_info);
        }
    }
    #[tokio::test]
    async fn session_rejection_withdraws_buffered_offer_before_other_features() {
        let (channel, mut peer) = pair().await;
        let server = tokio::spawn(async move {
            let first = read_request(&mut peer).await;
            let mut expected = bplist::parse(&first.body).unwrap();
            if let Value::Dict(fields) = &mut expected {
                let (_, Value::Array(offer)) = fields.iter_mut().find(|(k,_)| k.as_str() == Some("features")).unwrap() else { panic!() };
                assert!(offer.contains(&Value::str("hevc")));
                assert!(offer.contains(&Value::str("mainBuffered")));
                offer.retain(|f| f.as_str() != Some("mainBuffered"));
            }
            peer.write_all(format!("RTSP/1.0 400 Bad Request\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", first.cseq().unwrap()).as_bytes()).await.unwrap();
            let retry = read_request(&mut peer).await;
            assert_eq!(retry.first_line, first.first_line);
            assert_eq!(bplist::parse(&retry.body).unwrap(), expected);
            for request in [Some(retry), None] {
                let request = match request { Some(msg) => msg, None => read_request(&mut peer).await };
                let body = bplist::encode(&Value::dict(vec![("model",Value::str("IDC23"))]));
                peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",request.cseq().unwrap(),body.len()).as_bytes()).await.unwrap();
                peer.write_all(&body).await.unwrap();
            }
        });
        let outcome = setup_and_read_info(&channel,"phone","name",1234,true,Value::dict(vec![]),false,true,false,true,true,true,None,None, None, false).await.unwrap();
        assert!(outcome.2, "HEVC offer survives the buffered-only retry");
        assert!(!outcome.3, "withdrawn buffered feature cannot select type103 later");
        server.await.unwrap();
    }
    #[cfg(target_os = "android")]
    #[tokio::test]
    async fn buffered_tcp_or_anchor_failure_releases_103_before_fallback_to_102() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        struct Network {
            fail_tcp: bool,
            tcp_opens: AtomicUsize,
            udp_opens: AtomicUsize,
        }
        impl SocketProvider for Network {
            fn open_tcp(&self, host: &str, port: u16, timeout: u32) -> Option<TcpStream> {
                assert_eq!(host, "127.0.0.1");
                assert!((1..=3000).contains(&timeout));
                self.tcp_opens.fetch_add(1, Ordering::Relaxed);
                if self.fail_tcp { return None; }
                let tcp = std::net::TcpStream::connect((host, port)).unwrap();
                tcp.set_nonblocking(true).unwrap();
                Some(TcpStream::from_std(tcp).unwrap())
            }
            fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
                self.udp_opens.fetch_add(1, Ordering::Relaxed);
                let udp = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
                if port != 0 { udp.connect((host, port)).unwrap(); }
                udp.set_nonblocking(true).unwrap();
                Some(UdpSocket::from_std(udp).unwrap())
            }
        }
        async fn reply(peer: &mut TcpStream, msg: &crate::rtsp::RtspMessage, status: u16, body: Value) {
            let bytes = bplist::encode(&body);
            peer.write_all(format!("RTSP/1.0 {status} Test\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
                msg.cseq().unwrap(), bytes.len()).as_bytes()).await.unwrap();
            peer.write_all(&bytes).await.unwrap();
        }
        let mut p = buffered_profile_params_for_test();
        let info = Value::dict(vec![("model",Value::str("MB_E-P-4")), ("audioFormats", Value::array(vec![
            Value::dict(vec![("type", Value::int(103)), ("audioType", Value::str("media")), ("audioOutputFormats", Value::int(0xc00000))])
        ]))]);
        let cases = ["bmw_id9", "mercedes_mbux"].into_iter().flat_map(|profile|
            ["setup", "wrong_stream", "tcp", "anchor501", "anchor500", "anchor_missing", "none"].map(|failure| (profile, failure)));
        for (profile, failure) in cases {
            let deferred = profile == "bmw_id9" && failure == "anchor501";
            let stays_buffered = failure == "none" || deferred;
            p.head_unit_profile = profile.into();
            p.id9_screen_bootstrap = profile == "bmw_id9";
            p.mbux_media_compat = profile == "mercedes_mbux";
            let (channel, mut peer) = pair().await;
            let session_uri = "rtsp://127.0.0.1/42";
            channel.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
            let audio_uri = "rtsp://127.0.0.1:7000/";
            let anchor_uri = if profile == "bmw_id9" { session_uri } else { audio_uri };
            let data = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let port = data.local_addr().unwrap().port();
            let net = Arc::new(Network { fail_tcp:failure == "tcp", tcp_opens:0.into(), udp_opens:0.into() });
            let factory: Arc<dyn SocketProvider> = net.clone();
            channel.buffered_ownership.publish(true);
            let (pause_tx,pause_rx) = tokio::sync::oneshot::channel();
            let (play_tx,play_rx) = tokio::sync::oneshot::channel();
            let (wire_ids, mut proposed) = tokio::sync::mpsc::unbounded_channel();
            let (idle_tx, idle_rx) = tokio::sync::oneshot::channel();
            let (anchor_time_tx, anchor_time_rx) = tokio::sync::oneshot::channel();
            let server = tokio::spawn(async move {
                let streams = |fields| Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]);
                let record = read_request(&mut peer).await;
                assert_eq!(record.first_line, format!("RECORD {session_uri} RTSP/1.0"));
                reply(&mut peer, &record, 200, Value::dict(vec![])).await;
                let request = read_request(&mut peer).await;
                assert_eq!(request.first_line, format!("SETUP {audio_uri} RTSP/1.0"));
                let root = bplist::parse(&request.body).unwrap();
                let s = &root.get("streams").unwrap().as_array().unwrap()[0];
                wire_ids.send(s.get("streamConnectionID").unwrap().as_int().unwrap()).unwrap();
                for (k,v) in [("type",103),("audioFormat",1<<23),("spf",1024),("sr",48000),("ct",4),("audioLatencyMs",1000)] {
                    assert_eq!(s.get(k).and_then(Value::as_int),Some(v),"{k}");
                }
                assert_eq!(s.get("input"), Some(&Value::bool(false)));
                assert!(s.get("audioBufferSize").is_none());
                let selected = if failure == "wrong_stream" { 102 } else { 103 };
                reply(&mut peer,&request,if failure == "setup" {400} else {200},streams(vec![
                    ("type",Value::int(selected)), ("dataPort",Value::int(i64::from(port))),
                    ("audioBufferSize",Value::int(8388608))
                ])).await;
                if matches!(failure, "anchor501" | "anchor500" | "anchor_missing" | "none") {
                    let (mut tcp,_) = data.accept().await.unwrap();
                    let anchor = read_request(&mut peer).await;
                    assert_eq!(anchor.first_line, format!("GETANCHOR {anchor_uri} RTSP/1.0"));
                    assert_eq!(bplist::parse(&anchor.body).unwrap(), Value::dict(vec![("rate", Value::int(1))]));
                    anchor_time_tx.send(anchor.received_at).unwrap();
                    let status = match failure { "anchor501" => 501, "anchor500" => 500, _ => 200 };
                    if status != 200 {
                        peer.write_all(format!("RTSP/1.0 {status} Error\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",
                            anchor.cseq().unwrap()).as_bytes()).await.unwrap();
                    } else {
                        let clock = if failure == "anchor_missing" { vec![] } else { vec![
                            ("networkTimeSecs",Value::int(42)),("networkTimeFrac",Value::int(0)),
                            ("networkTimeTimelineID",Value::int(0)),("networkTimeFlags",Value::int(0)),
                        ] };
                        reply(&mut peer, &anchor, status, Value::dict(clock)).await;
                    }
                    if stays_buffered {
                        // The client withholds PCM until this gate. Don't accidentally allow
                        // a SETRATE queued during preflight to pass as post-write control.
                        if deferred {
                            assert!(tokio::time::timeout(Duration::from_millis(50),peer.read(&mut [0u8;1])).await.is_err());
                            idle_tx.send(()).unwrap();
                        }
                        let size = usize::from(tcp.read_u16().await.unwrap());
                        assert!(size > 38);
                        let mut packet = vec![0; size - 2];
                        tcp.read_exact(&mut packet).await.unwrap();
                        let key = stream_key(&[7;32], s.get("streamConnectionID").unwrap().as_int().unwrap() as u64);
                        let end = packet.len() - 8;
                        let counter = u64::from_le_bytes(packet[end..].try_into().unwrap());
                        let au = crate::crypto::chacha_open(&key, &crate::crypto::nonce64(counter), &packet[12..end], &packet[4..12]).unwrap();
                        assert!(!au.is_empty());
                        assert_eq!(packet[8],23);
                        let rtp = u32::from_be_bytes(packet[4..8].try_into().unwrap());
                        if deferred {
                            let start = read_request(&mut peer).await;
                            assert_eq!(start.first_line, format!("SETRATE {audio_uri} RTSP/1.0"));
                            let body = bplist::parse(&start.body).unwrap();
                            assert_eq!(body.as_dict().unwrap().len(), 3);
                            for (key, n) in [("rate",1),("mediaTimeValue",i64::from(rtp)),("mediaTimeScale",48_000)] {
                                assert_eq!(body.get(key).and_then(Value::as_int), Some(n));
                            }
                            reply(&mut peer,&start,200,Value::dict(vec![
                                ("mediaTimeValue", Value::int(i64::from(rtp))), ("mediaTimeScale", Value::int(48_000)),
                                ("networkTimeSecs",Value::int(43)),("networkTimeFrac",Value::int(0)),
                                ("networkTimeTimelineID",Value::int(0)),("networkTimeFlags",Value::int(0)),
                            ])).await;
                        } else {
                            let anchor = read_request(&mut peer).await;
                            assert_eq!(anchor.first_line, format!("GETANCHOR {anchor_uri} RTSP/1.0"));
                            assert_eq!(bplist::parse(&anchor.body).unwrap(), Value::dict(vec![("rate", Value::int(1))]));
                            reply(&mut peer,&anchor,200,Value::dict(vec![
                                ("networkTimeSecs",Value::int(43)),("networkTimeFrac",Value::int(0)),
                                ("networkTimeTimelineID",Value::int(0)),("networkTimeFlags",Value::int(0)),
                            ])).await;
                            let start = read_request(&mut peer).await;
                            assert_eq!(start.first_line, format!("SETRATEANCHORTIME {audio_uri} RTSP/1.0"));
                            let body = bplist::parse(&start.body).unwrap();
                            assert_eq!(body.get("rtpTime"),Some(&Value::int(i64::from(rtp))));
                            assert_eq!(body.get("networkTimeSecs"),Some(&Value::int(43)));
                            reply(&mut peer,&start,200,Value::dict(vec![])).await;
                        }
                        let _ = play_tx.send(());
                        let flush = read_request(&mut peer).await;
                        assert_eq!(flush.first_line, format!("FLUSHBUFFERED {audio_uri} RTSP/1.0"));
                        reply(&mut peer,&flush,200,Value::dict(vec![])).await;
                        let pause = read_request(&mut peer).await;
                        let pause_method = if deferred { "SETRATE" } else { "SETRATEANCHORTIME" };
                        assert_eq!(pause.first_line, format!("{pause_method} {audio_uri} RTSP/1.0"));
                        assert_eq!(bplist::parse(&pause.body).unwrap().get("rate"),Some(&Value::int(0)));
                        reply(&mut peer,&pause,200,Value::dict(vec![])).await;
                        let _ = pause_tx.send(());
                    } else {
                        let mut byte = [0;1];
                        assert_eq!(tcp.read(&mut byte).await.unwrap(),0);
                    }
                }
                if stays_buffered { return; }
                if failure != "setup" {
                    let cleanup = read_request(&mut peer).await;
                    assert_eq!(cleanup.first_line, format!("TEARDOWN {session_uri} RTSP/1.0"));
                    assert_eq!(bplist::parse(&cleanup.body).unwrap(),streams(vec![("type",Value::int(103))]));
                    reply(&mut peer,&cleanup,200,Value::dict(vec![])).await;
                }
                let fallback = read_request(&mut peer).await;
                assert_eq!(fallback.first_line, format!("SETUP {audio_uri} RTSP/1.0"));
                let root = bplist::parse(&fallback.body).unwrap();
                let stream = &root.get("streams").unwrap().as_array().unwrap()[0];
                assert_eq!(stream.get("type"), Some(&Value::int(102)));
                assert_ne!(port_of(stream.get("controlPort")), 0);
                wire_ids.send(stream.get("streamConnectionID").unwrap().as_int().unwrap()).unwrap();
                reply(&mut peer,&fallback,200,streams(vec![("type",Value::int(102)),("dataPort",Value::int(i64::from(port)))])).await;
            });
            record_session(&channel).await.unwrap();
            let mut buffered = negotiate_audio(&channel, &factory, Some(&[7;32]), &p, false, &info, AudioCodec::BufferedAac).await;
            let buffered_id = proposed.try_recv().unwrap();
            assert_eq!(buffered.as_ref().map(AudioOut::stream_type), stays_buffered.then_some(103), "{profile}: {failure}");
            if let Some(output) = buffered.as_mut() {
                assert_eq!(output.pacing_id.map(|id| (id.rtsp, id.stream_id)), Some((channel.trace_id(), buffered_id)));
                assert!(output.pacing_id.unwrap().setup_accepted_at <= anchor_time_rx.await.unwrap(),
                    "classify from the SETUP response, before TCP/GETANCHOR finishes");
                p.pipeline.lock().observe_main_sender(Some(output));
                let owned = Arc::new(std::sync::atomic::AtomicBool::new(true));
                output.set_ownership(owned.clone());
                if deferred { tokio::time::timeout(Duration::from_secs(2), idle_rx).await.unwrap().unwrap(); }
                let pcm:Vec<i16> = (0..2048).map(|i| ((i as f32 * 0.0576).sin() * 6000.) as i16).collect();
                for _ in 0..24 {
                    output.send_pcm(&pcm).await.unwrap();
                    if output.packets_sent() > 0 { break; }
                    tokio::time::sleep(Duration::from_millis(22)).await;
                }
                assert!(output.packets_sent() > 0);
                let sent = output.packets_sent();
                tokio::time::timeout(Duration::from_secs(2),play_rx).await.unwrap().unwrap();
                owned.store(false, Ordering::Release);
                channel.buffered_ownership.publish(false);
                output.send_pcm(&pcm).await.unwrap();
                assert_eq!(output.packets_sent(),sent);
                tokio::time::timeout(Duration::from_secs(2),pause_rx).await.unwrap().unwrap();
                for stage in ["SCREEN_SETUP", "VIDEO_CHANNEL_READY"] {
                    let snap = p.pipeline.lock().activation_snapshot(stage).unwrap();
                    assert_eq!(serde_json::to_value(snap).unwrap()["main_audio_stream_type"], 103);
                }
            }
            drop(buffered);
            p.pipeline.lock().observe_main_sender(None);
            assert!(p.pipeline.lock().activation_snapshot("SCREEN_SETUP").unwrap().main_audio_stream_type.is_none());
            assert!(!channel.buffered_cleanup_pending.load(Ordering::Acquire));
            if !stays_buffered {
                let udp = negotiate_audio(&channel, &factory, Some(&[7;32]), &p, false, &info, AudioCodec::Aac).await.unwrap();
                let udp_id = proposed.try_recv().unwrap();
                assert_eq!(udp.pacing_id.map(|id| (id.rtsp, id.stream_id)), Some((channel.trace_id(), udp_id)));
                assert_eq!(udp.stream_type(), 102);
                p.pipeline.lock().observe_main_sender(Some(&udp));
                for stage in ["SCREEN_SETUP", "VIDEO_CHANNEL_READY"] {
                    let snap = p.pipeline.lock().activation_snapshot(stage).unwrap();
                    assert_eq!(serde_json::to_value(snap).unwrap()["main_audio_stream_type"], 102);
                }
            }
            assert_eq!(net.tcp_opens.load(Ordering::Relaxed), usize::from(!matches!(failure,"setup"|"wrong_stream")));
            assert_eq!(net.udp_opens.load(Ordering::Relaxed), 2 * usize::from(!stays_buffered));
            server.await.unwrap();
        }
    }
    #[tokio::test]
    async fn hevc_preference_never_retries_authorization_failures() {
        for status in [401,403] {
            let (control,mut peer)=pair().await;
            let server=crate::callback_context::spawn(async move {
                let request=read_request(&mut peer).await;
                peer.write_all(format!("RTSP/1.0 {status} Denied\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",request.cseq().unwrap()).as_bytes()).await.unwrap();
                assert!(tokio::time::timeout(Duration::from_millis(50),peer.read(&mut [0u8;1])).await.is_err());
            });
            assert!(setup_and_read_info(&control,"phone","name",1234,true,Value::dict(vec![]),false,true,false,true,true,true,None,None, None, false).await.is_err());
            server.await.unwrap();
        }
    }
    #[tokio::test]
    async fn handoff_requests_before_wifi_auth_and_rejection_is_not_replayed() {
        for status in [200,503] {
            let (control,mut peer)=pair().await;
            let server=crate::callback_context::spawn(async move {
                let msg=read_request(&mut peer).await;
                assert_eq!(msg.first_line,"POST /command RTSP/1.0");
                let body=bplist::parse(&msg.body).unwrap();
                assert_eq!(body.get("type").and_then(Value::as_str),Some("disableBluetooth"));
                assert_eq!(body.get("params").unwrap().get("deviceID").and_then(Value::as_str),Some("current-phone"));
                peer.write_all(format!("RTSP/1.0 {status} Result\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",msg.headers.get("cseq").unwrap()).as_bytes()).await.unwrap();
                let mut byte=[0];
                assert!(tokio::time::timeout(Duration::from_millis(60),peer.read(&mut byte)).await.is_err());
            });
            let reply=request_bluetooth_handoff(&control,"current-phone",||true,Arc::new(Notify::new())).await.unwrap().unwrap();
            assert_eq!(reply.status(),status); server.await.unwrap();
        }
    }
    #[tokio::test]
    async fn stale_or_cancelled_handoff_never_writes() {
        for stale in [true,false] {
            let (control,mut peer)=pair().await; let stop=Arc::new(Notify::new());
            if !stale { stop.notify_one(); }
            assert!(request_bluetooth_handoff(&control,"test",||!stale,stop).await.is_none());
            assert!(tokio::time::timeout(Duration::from_millis(30),peer.read(&mut [0])).await.is_err());
        }
    }
    #[tokio::test]
    async fn silent_handoff_is_bounded_and_inflight_request_can_be_cancelled() {
        for cancel in [true,false] {
            let (control,mut peer)=pair().await; let stop=Arc::new(Notify::new()); let signal=stop.clone();
            let server=crate::callback_context::spawn(async move {
                let _=read_request(&mut peer).await;
                if cancel {signal.notify_one();}
                tokio::time::sleep(Duration::from_millis(3300)).await;
            });
            let started=std::time::Instant::now();
            let reply=tokio::time::timeout(Duration::from_millis(3200),request_bluetooth_handoff(&control,"test",||true,stop)).await.unwrap();
            if cancel {assert!(reply.is_none());assert!(started.elapsed()<Duration::from_secs(1));}
            else {assert!(reply.unwrap().is_err());}
            server.abort();
        }
    }
    #[tokio::test]
    async fn late_handoff_reply_cannot_confirm_replaced_session() {
        use std::sync::atomic::{AtomicBool,Ordering};
        let (control,mut peer)=pair().await; let current=Arc::new(AtomicBool::new(true)); let flag=current.clone();
        let server=crate::callback_context::spawn(async move {
            let msg=read_request(&mut peer).await; flag.store(false,Ordering::SeqCst);
            peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",msg.headers.get("cseq").unwrap()).as_bytes()).await.unwrap();
        });
        assert!(request_bluetooth_handoff(&control,"test",||current.load(Ordering::SeqCst),Arc::new(Notify::new())).await.is_none());
        server.await.unwrap();
    }
    #[tokio::test]
    async fn malformed_accepted_session_still_gets_global_cleanup_before_original_error_returns() {
        let (control,mut peer)=pair().await;
        let c=control.clone();
        let task=crate::callback_context::spawn(async move {
            let mut cleanup=crate::negotiation_cleanup::NegotiationCleanup::new(c.clone(),true);
            let result=setup_and_read_info(&c,"phone","name",1234,true,Value::dict(vec![]),false,true,false,false,false,true,Some(&mut cleanup),None, None, false).await;
            cleanup.finish().await;result
        });
        let setup=read_request(&mut peer).await;assert!(setup.first_line.starts_with("SETUP "));
        peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 3\r\n\r\nbad",setup.cseq().unwrap()).as_bytes()).await.unwrap();
        let exit=read_request(&mut peer).await;assert_eq!(exit.first_line,"TEARDOWN / RTSP/1.0");assert!(exit.body.is_empty());
        peer.write_all(format!("RTSP/1.0 500 Failed\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",exit.cseq().unwrap()).as_bytes()).await.unwrap();
        let error=task.await.unwrap().unwrap_err();assert!(error.contains("SETUP 应答解析失败"));assert!(control.is_closed());
    }
    #[tokio::test]
    async fn manual_pause_releases_audio_then_screen_then_the_session_on_its_own_uri() {
        let (control, mut peer) = pair().await;
        control.set_session_uri(crate::rtsp_uri::SessionUri::new("127.0.0.1", 42).unwrap()).unwrap();
        let screen = Value::dict(vec![("type", Value::int(110)), ("uuid", Value::str("main"))]);
        let audio = Value::dict(vec![("type", Value::int(100))]);
        let body = vec![screen.clone(), audio.clone()];
        let task = crate::callback_context::spawn(async move { finish_session(&control, &body, true).await });
        for (index, stream) in [Some(audio), Some(screen), None].into_iter().enumerate() {
            let request = read_request(&mut peer).await;
            assert_eq!(request.first_line, "TEARDOWN rtsp://127.0.0.1/42 RTSP/1.0");
            assert_eq!(request.cseq().unwrap() as usize, index + 1);
            if let Some(stream) = stream {
                let body = bplist::parse(&request.body).unwrap();
                assert_eq!(body.get("streams"), Some(&Value::array(vec![stream])));
            } else {
                assert!(request.body.is_empty());
                assert!(!request.headers.contains_key("content-type"));
                assert!(tokio::time::timeout(Duration::from_millis(30), peer.read(&mut [0])).await.is_err());
            }
            // A receiver may have already retired its audio stream.
            let status = if index == 0 { 455 } else { 200 };
            peer.write_all(format!("RTSP/1.0 {status} Result\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",
                request.cseq().unwrap()).as_bytes()).await.unwrap();
        }
        task.await.unwrap();
        assert_eq!(peer.read(&mut [0]).await.unwrap(), 0);
    }
    #[tokio::test]
    async fn unanswered_stream_release_still_leaves_time_to_end_session() {
        let (control, mut peer) = pair().await;
        let audio = Value::dict(vec![("type", Value::int(100))]);
        let task = crate::callback_context::spawn(async move { finish_session(&control, &[audio], true).await });
        let stream = read_request(&mut peer).await;
        assert!(!stream.body.is_empty());
        let session = read_request(&mut peer).await;
        assert_eq!(session.first_line, stream.first_line);
        assert!(session.body.is_empty());
        // A late stream reply must not complete the final session transaction.
        peer.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\n\r\n", stream.cseq().unwrap()).as_bytes()).await.unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(30), peer.read(&mut [0])).await.is_err());
        drop(peer);
        tokio::time::timeout(Duration::from_secs(1), task).await.unwrap().unwrap();
    }
    #[tokio::test]
    async fn silent_receiver_cannot_block_stop() {
        let (control, mut peer) = pair().await;
        tokio::time::timeout(Duration::from_millis(1_500), finish_session(&control, &[], true)).await.unwrap();
        let mut bytes = Vec::new();
        tokio::time::timeout(Duration::from_millis(500), peer.read_to_end(&mut bytes)).await.unwrap().unwrap();
        assert!(bytes.starts_with(b"TEARDOWN"));
    }
    #[tokio::test]
    async fn forced_stop_closes_without_sending_protocol_messages() {
        let (control, mut peer) = pair().await;
        finish_session(&control, &[], false).await;
        let mut buf = [0; 1];
        assert_eq!(tokio::time::timeout(Duration::from_millis(500), peer.read(&mut buf)).await.unwrap().unwrap(), 0);
    }
}

/// Receiver declarations only: outgoing SETUP is never evidence of supported codecs.
fn audio_capability_lines(info: &Value) -> Vec<String> {
    let model = info.get("model").and_then(Value::as_str).unwrap_or("unknown");
    let Some(formats) = info.get("audioFormats").and_then(Value::as_array).filter(|f| !f.is_empty()) else {
        return vec![format!("AUDIO-CAPS: receiver={model} audioFormats absent/empty; AAC/PCM support unknown")];
    };
    let mut lines = vec![format!("AUDIO-CAPS: receiver={model} declared_formats={}", formats.len())];
    for (index, f) in formats.iter().take(64).enumerate() {
        let output = f.get("audioOutputFormats").and_then(Value::as_int);
        let input = f.get("audioInputFormats").and_then(Value::as_int);
        let bits = |v: Option<i64>| v.map(|n| format!("0x{n:x}")).unwrap_or_else(|| "unknown".into());
        let flag = |bit| output.map(|n| (n & bit != 0).to_string()).unwrap_or_else(|| "unknown".into());
        lines.push(format!("AUDIO-CAPS: [{index}] type={:?} audioType={} output={} input={} PCM48={} AAC44={} AAC48={}",
            f.get("type").and_then(Value::as_int), f.get("audioType").and_then(Value::as_str).unwrap_or("unknown"),
            bits(output), bits(input), flag(1 << 15), flag(1 << 22), flag(1 << 23)));
    }
    lines
}

/// Match the explicit main screen before considering the legacy type=0/unspecified form.
fn select_id9_display(info: &Value) -> Option<&Value> {
    let displays = info.get("displays")?.as_array()?;
    let valid = |d: &&Value| {
        let (w,h) = read_display_size(d);
        w >= 2 && h >= 2 && w <= 16384 && h <= 16384 && uuid_text(d.get("uuid")).is_some()
    };
    for kind in [Some(110), Some(0), None] {
        if let Some(display) = displays.iter().filter(valid).find(|d| d.get("type").and_then(Value::as_int) == kind) {
            return Some(display);
        }
    }
    None
}

fn id9_hid_ids(info: &Value, display_uuid: Option<&str>) -> Vec<Value> {
    let mut ids = Vec::new();
    for hid in info.get("hidDevices").and_then(Value::as_array).into_iter().flatten().take(32) {
        if hid.get("displayUUID").and_then(Value::as_str).is_some_and(|id| Some(id) != display_uuid) { continue; }
        let Some(id) = hid.get("uuid") else { continue; };
        if !(id.as_str().is_some_and(|s| !s.is_empty() && s.len() <= 128) || id.as_int().is_some_and(|v| v >= 0)) { continue; }
        if !ids.contains(id) { ids.push(id.clone()); }
    }
    ids
}

#[cfg(test)]
mod id9_tests {
    use super::*;
    fn display(id: &str, kind: i64, w: i64) -> Value {
        Value::dict(vec![("uuid",Value::str(id)),("type",Value::int(kind)),("widthPixels",Value::int(w)),("heightPixels",Value::int(960))])
    }
    #[test]
    fn explicit_main_screen_wins_when_auxiliary_and_legacy_entries_come_first() {
        let info=Value::dict(vec![("displays",Value::array(vec![display("aux",111,2240),display("legacy",0,1024),display("main",110,2880)]))]);
        let selected=select_id9_display(&info).unwrap();
        assert_eq!(selected.get("uuid").and_then(Value::as_str),Some("main"));
        assert_eq!(read_display_size(selected),(2880,960));
        for displays in [vec![display("aux",111,2240)],vec![display("bad",110,0)],vec![]] {
            assert!(select_id9_display(&Value::dict(vec![("displays",Value::array(displays))])).is_none());
        }
    }
    #[test]
    fn nested_id9_view_area_is_kept_separate_from_canvas() {
        let display=Value::dict(vec![("viewAreas",Value::array(vec![Value::dict(vec![
            ("widthPixels",Value::int(2880)),("heightPixels",Value::int(840)),
            ("safeArea",Value::dict(vec![("widthPixels",Value::int(2860)),("heightPixels",Value::int(836)),("originXPixels",Value::int(10)),("originYPixels",Value::int(4))])),
        ])]))]);
        let layout=crate::display_layout::read(&display,2880,960,Some("main")).unwrap();
        assert_eq!((layout.view.w,layout.view.h),(2880,840));
        assert_eq!((layout.safe.x,layout.safe.y,layout.safe.w,layout.safe.h),(10,4,2860,836));
    }
    #[test]
    fn input_initialization_only_uses_declared_main_hid_devices() {
        let hid=|id:&str,display:&str| Value::dict(vec![("uuid",Value::str(id)),("displayUUID",Value::str(display))]);
        let info=Value::dict(vec![("hidDevices",Value::array(vec![hid("150B","main"),hid("150B","main"),hid("150C","main"),hid("other","aux")]))]);
        assert_eq!(id9_hid_ids(&info,Some("main")),vec![Value::str("150B"),Value::str("150C")]);
    }
    #[test]
    fn capability_diagnostics_distinguish_declarations_from_missing_info() {
        let missing=audio_capability_lines(&Value::dict(vec![])).join("\n");
        assert!(missing.contains("support unknown"));
        assert!(!missing.contains("AAC48=false"));
        let info=Value::dict(vec![("model",Value::str("MGU-fixture")),("audioFormats",Value::array(vec![Value::dict(vec![
            ("type",Value::int(102)),("audioType",Value::str("media")),("audioOutputFormats",Value::int(0x800000)),
        ])]))]);
        let lines=audio_capability_lines(&info).join("\n");
        assert!(lines.contains("receiver=MGU-fixture"));
        assert!(lines.contains("type=Some(102) audioType=media output=0x800000"));
        assert!(lines.contains("PCM48=false AAC44=false AAC48=true"));
        // This is a synthetic capability fixture, not evidence from a real MGU head unit.
    }
    #[test]
    fn buffered_priority_requires_declared_media_output_and_classic_framing() {
        let empty = Value::dict(vec![]);
        let format = |kind, label, direction, bits, apap| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(label)), (direction, Value::int(bits)),
            ("packetFormatAPAP", Value::bool(apap)),
        ]);
        let tcp = || format(103, "media", "audioOutputFormats", 1 << 23, false);
        let udp = || format(102, "media", "audioOutputFormats", 1 << 23, false);
        let plan = |formats, session: &Value| {
            let info = Value::dict(vec![("audioFormats", Value::array(formats))]);
            let mut plan = audio_candidates(&info, "auto", true);
            prioritize_buffered_media(&info, session, &mut plan);
            plan
        };
        let features = |names| Value::dict(vec![("enabledFeatures", Value::array(names))]);
        assert_eq!(plan(vec![tcp(), udp()], &features(vec![Value::str("mainBuffered")])),
            [AudioCodec::BufferedAac, AudioCodec::Aac]);
        assert_eq!(plan(vec![tcp()], &empty), [AudioCodec::BufferedAac]);
        assert_eq!(plan(vec![tcp(), udp()], &features(vec![])), [AudioCodec::BufferedAac, AudioCodec::Aac]);
        let pcm = format(100, "media", "audioOutputFormats", 1 << 15, false);
        assert_eq!(plan(vec![pcm, udp()], &features(vec![Value::str("mainBuffered")])),
            [AudioCodec::Pcm, AudioCodec::Aac]);
        for bad in [format(103,"media","audioInputFormats",1<<23,false),
                    format(103,"media","audioOutputFormats",1<<22,false),
                    format(103,"media","audioOutputFormats",-1,false),
                    format(103,"media","audioOutputFormats",1<<23,true),
                    format(103,"speechRecognition","audioOutputFormats",1<<23,false)] {
            assert_eq!(plan(vec![bad, udp()], &empty), [AudioCodec::Aac]);
        }
    }

    #[test]
    fn buffered_offer_respects_audio_encryption_and_nbt_path() {
        let empty = Value::dict(vec![]);
        let diplay = Value::dict(vec![("model", Value::str("DiPlay"))]);
        let mut p = buffered_profile_params_for_test();
        assert!(p.proposes_buffered_media(true, &empty));
        assert!(!p.proposes_buffered_media(false, &empty));
        p.nbt_audio_first = true;
        assert!(!p.proposes_buffered_media(true, &empty));
        assert!(effective_desktop(&p, &diplay));
        assert!(p.proposes_buffered_media(true, &diplay));
        p.audio_codec = "pcm".into();
        assert!(!p.proposes_buffered_media(true, &diplay));
        p.audio_codec = "auto".into();
        p.enable_audio = false;
        assert!(!p.proposes_buffered_media(true, &diplay));
    }

    #[test]
    fn buffered_setup_sends_the_stream_key_and_realtime_aac_does_not() {
        let id = 42;
        let key = stream_key(&[9; 32], id as u64);
        let buffered = bplist::parse(&build_audio_setup_request(id, 250, AudioCodec::BufferedAac, Some(&key), None, false)).unwrap();
        let stream = &buffered.get("streams").unwrap().as_array().unwrap()[0];
        assert_eq!(stream.get("shk").and_then(Value::as_bytes), Some(key.as_slice()));
        assert_eq!(stream.get("ct").and_then(Value::as_int), Some(4));
        assert_eq!(stream.get("spf").and_then(Value::as_int), Some(1024));
        let realtime = bplist::parse(&build_audio_setup_for_codec(id, 250, AudioCodec::Aac)).unwrap();
        assert!(realtime.get("streams").unwrap().as_array().unwrap()[0].get("shk").is_none());
    }

    #[test]
    fn buffered_response_rejects_invalid_echoes_endpoints_and_capacity() {
        let response = |field, value| {
            let mut fields = vec![("dataPort",Value::int(5555)),("audioBufferSize",Value::int(8388608))];
            fields.retain(|(k,_)| *k != field);
            fields.push((field,value));
            Value::dict(fields)
        };
        assert_eq!(buffered_endpoints(&response("controlPort", Value::int(65000)), 42).unwrap(), (5555,8388608));
        for (field, value) in [("dataPort",Value::int(-1)), ("dataPort", Value::int(65536)),
            ("controlPort",Value::str("1234")), ("audioBufferSize",Value::int(0)),
            ("audioBufferSize",Value::int(38)), ("audioBufferSize", Value::int(i64::MAX)),
            ("input",Value::bool(true)), ("audioType",Value::str("default")), ("sr",Value::int(44100)),
            ("status", Value::int(2)), ("audioFormat",Value::int(1<<22)), ("spf",Value::int(480)),
            ("ct",Value::int(3)), ("streamConnectionID",Value::int(5)), ("packetFormatAPAP",Value::bool(true))] {
            assert!(buffered_endpoints(&response(field, value), 42).is_err(), "{field}");
        }
    }

    #[test]
    fn aac_only_receiver_is_never_sent_unadvertised_pcm_media() {
        let fmt=|kind,typ:&str,bits| Value::dict(vec![("type",Value::int(kind)),("audioType",Value::str(typ)),("audioOutputFormats",Value::int(bits))]);
        let info=Value::dict(vec![("audioFormats",Value::array(vec![fmt(102,"media",0x800000),fmt(100,"default",0x30000000)]))]);
        assert!(!declares_media(&info, AudioCodec::Pcm));
        assert!(!declares_media(&Value::dict(vec![]), AudioCodec::Pcm));
        assert!(!declares_media(&Value::dict(vec![("audioFormats",Value::array(vec![fmt(100,"telephony",0x8000)]))]), AudioCodec::Pcm));
        assert!(declares_media(&Value::dict(vec![("audioFormats",Value::array(vec![fmt(100,"media",0x8000)]))]), AudioCodec::Pcm));
    }
}

/// Encoders here accept whole frames per second. Round a finite numeric ceiling down.
pub(crate) fn display_max_fps(display: &Value) -> Option<u32> {
    let n = match display.get("maxFPS")? {
        Value::Int(n) => i32::try_from(*n).ok()?,
        Value::Real(n) if n.is_finite() && *n >= 1.0 && *n <= f64::from(i32::MAX) => n.floor() as i32,
        _ => return None,
    };
    (n > 0).then_some(n as u32)
}

fn session_display_max_fps(info: &Value, display: Option<&Value>, fresh: bool, early: Option<(Option<&str>, u32)>) -> Option<u32> {
    // A failed /info can return a plist from a retired pairing socket. Only use the
    // early declaration supplied by the owner of the current control connection.
    if !fresh { return early.map(|(_, fps)| fps); }
    if let Some(fps) = display.and_then(display_max_fps) { return Some(fps); }
    let (early_uuid, fps) = early?;
    if info.get("displays").is_none() { return Some(fps); }
    let uuid = uuid_text(display?.get("uuid"))?;
    early_uuid?.eq_ignore_ascii_case(&uuid).then_some(fps)
}

/// Preserve BOX-OFF's type-zero preference while keeping instruments out of the main stream.
fn select_display(info: &Value) -> Option<&Value> {
    let displays = info.get("displays")?.as_array()?;
    displays.iter()
        .find(|display| display.get("type").and_then(Value::as_int) == Some(0))
        .or_else(|| displays.iter().find(|display| display.get("type").and_then(Value::as_int) != Some(111)))
}

#[cfg(test)]
mod display_selection_tests {
    #[test]
    fn session_display_keeps_its_own_early_ceiling_until_a_valid_update() {
        let early = Some((Some("MAIN"), 30));
        for (id, cap, expected) in [
            ("main", None, Some(30)),
            ("main", Some(Value::str("invalid")), Some(30)),
            ("main", Some(Value::int(24)), Some(24)),
            ("other", None, None),
            ("", None, None),
        ] {
            let mut fields = vec![("type", Value::int(110)), ("uuid", Value::str(id))];
            if let Some(cap) = cap { fields.push(("maxFPS", cap)); }
            let fresh = info(vec![Value::dict(fields)]);
            assert_eq!(session_display_max_fps(&fresh, select_display(&fresh), true, early), expected, "{id}");
            if expected == Some(30) {
                assert_eq!(session_display_max_fps(&fresh, select_display(&fresh), true, None), None);
            }
        }
        let prior = info(vec![Value::dict(vec![("uuid", Value::str("main")), ("maxFPS", Value::int(30))])]);
        assert_eq!(session_display_max_fps(&prior, select_display(&prior), false, early), Some(30));
        assert_eq!(session_display_max_fps(&prior, select_display(&prior), false, None), None);
        assert_eq!(session_display_max_fps(&Value::dict(vec![]), None, true, early), Some(30));
        assert_eq!(session_display_max_fps(&info(vec![]), None, true, early), None);
    }

    #[test]
    fn selected_display_frame_limit_is_numeric_positive_and_local_to_main() {
        let aux = Value::dict(vec![("type",Value::int(111)), ("maxFPS",Value::int(1))]);
        for (value, expected) in [(Value::int(30),Some(30)), (Value::real(29.97),Some(29)),
            (Value::int(-1),None), (Value::int(0),None), (Value::str("30"),None)] {
            let main = Value::dict(vec![("type",Value::int(110)), ("maxFPS",value)]);
            let info = info(vec![aux.clone(), main]);
            assert_eq!(select_display(&info).and_then(display_max_fps),expected);
        }
        assert_eq!(display_max_fps(&Value::dict(vec![])),None);
    }

    use super::*;

    fn display(uuid: &str, type_value: Option<i64>) -> Value {
        let mut fields = vec![("uuid", Value::str(uuid))];
        if let Some(type_value) = type_value {
            fields.push(("type", Value::int(type_value)));
        }
        Value::dict(fields)
    }

    fn info(displays: Vec<Value>) -> Value {
        Value::dict(vec![("displays", Value::array(displays))])
    }

    fn selected_uuid(info: &Value) -> Option<String> {
        select_display(info).and_then(|display| uuid_text(display.get("uuid")))
    }

    #[test]
    fn type_zero_later_in_the_list_replaces_the_first_uuid() {
        let parsed = info(vec![
            display("first-screen", Some(110)),
            display("type-zero-screen", Some(0)),
        ]);
        assert_eq!(selected_uuid(&parsed).as_deref(), Some("type-zero-screen"));
        let chosen = build_screen_setup(42, "type-zero-screen");
        let previous_first = build_screen_setup(42, "first-screen");
        assert_ne!(chosen, previous_first);
        let body = bplist::parse(&chosen).unwrap();
        let stream = &body.get("streams").unwrap().as_array().unwrap()[0];
        assert_eq!(stream.get("uuid").and_then(Value::as_str), Some("type-zero-screen"));
        assert_eq!(stream.get("type").and_then(Value::as_int), Some(110));
        assert!(stream.get("viewAreas").is_none());
    }

    #[test]
    fn first_type_zero_keeps_the_same_screen_setup_bytes() {
        let parsed = info(vec![
            display("first-type-zero", Some(0)),
            display("later-screen", Some(110)),
        ]);
        assert_eq!(selected_uuid(&parsed).as_deref(), Some("first-type-zero"));
        assert_eq!(
            build_screen_setup(42, selected_uuid(&parsed).as_deref().unwrap()),
            build_screen_setup(42, "first-type-zero"),
        );
    }

    #[test]
    fn auxiliary_display_cannot_be_the_main_stream_target() {
        let list = info(vec![display("instrument", Some(111)), display("main", Some(110))]);
        assert_eq!(selected_uuid(&list).as_deref(), Some("main"));
        assert!(select_display(&info(vec![display("instrument", Some(111))])).is_none());
    }

    #[test]
    fn missing_type_zero_keeps_the_first_display_and_the_uuid_constant() {
        let parsed = info(vec![
            display("only-screen", Some(110)),
            display("another-screen", None),
        ]);
        assert_eq!(selected_uuid(&parsed).as_deref(), Some("only-screen"));
        let without_uuid = info(vec![Value::dict(vec![("type", Value::int(0))])]);
        assert_eq!(selected_uuid(&without_uuid), None);
        let uuid = selected_uuid(&without_uuid)
            .unwrap_or_else(|| "6E4E3B31-0E5B-4A8F-9F1B-2C5D7E9A0C11".into());
        assert_eq!(uuid, "6E4E3B31-0E5B-4A8F-9F1B-2C5D7E9A0C11");
        assert_eq!(select_display(&Value::dict(vec![])), None);
    }
}

/// CarPlay uses widthPixels/heightPixels; older receivers may use width/height.
fn read_display_size(display: &Value) -> (u32, u32) {
    fn dimension(display: &Value, pixels: &str, legacy: &str) -> u32 {
        [pixels, legacy].iter().filter_map(|key| display.get(key).and_then(Value::as_int))
            .find(|&value| (1..=16384).contains(&value)).unwrap_or(0) as u32
    }
    (dimension(display, "widthPixels", "width"), dimension(display, "heightPixels", "height"))
}

#[cfg(test)]
mod display_size_tests {
    use super::*;
    fn handle_hid_report(report: &[u8], touch_tx: &mpsc::UnboundedSender<TouchInput>, display: (u32,u32),
        surface: &TouchSurface, target_size: &Arc<Mutex<(u32,u32)>>, contact: &mut TouchContact, logged: &mut u32) {
        let sample = crate::consumer_hid::TouchSample::legacy(report).unwrap();
        handle_touch_sample(sample, touch_tx, display, surface, target_size, contact, logged, true, None);
    }

    #[tokio::test]
    async fn dropping_worker_while_feedback_pending_releases_control_without_stop_notification() {
        use tokio::io::AsyncReadExt;
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car,_)=listener.accept().await.unwrap();let (r,w)=phone.into_split();
        let (control,_)=RtspChannel::spawn(r,w,None);let weak=Arc::downgrade(&control);
        let worker=spawn_mode_feedback(control,Arc::new(Notify::new()),Arc::new(Mutex::new(crate::modes::Modes::initial())),None);
        worker.send(Some(ModeFeedback::Snapshot)).unwrap();
        let mut bytes=[0;4096];assert!(car.read(&mut bytes).await.unwrap()>0);
        drop(worker);
        tokio::time::timeout(Duration::from_secs(1),async {
            while weak.upgrade().is_some() {tokio::task::yield_now().await;}
        }).await.unwrap();
        assert_eq!(car.read(&mut bytes).await.unwrap(),0);
    }
    #[test]
    fn descriptor_touch_coordinates_use_bit_ranges_and_logical_bounds() {
        let descriptor = vec![
            0x05,0x0d,0x09,0x04,0xa1,1,0x85,7,0x09,0x22,0xa1,2,
            0x09,0x42,0x15,0,0x25,1,0x75,1,0x95,1,0x81,2,
            0x75,7,0x95,1,0x81,1,
            0x05,1,0x09,0x30,0x09,0x31,0x15,0,0x26,0xff,0x0f,
            0x75,12,0x95,2,0x81,2,0xc0,0xc0,
        ];
        let id = Value::int(4);
        let mut hid = crate::consumer_hid::ConsumerHid::from_info(&Value::dict(vec![("hidDevices", Value::array(vec![
            Value::dict(vec![("uuid", id.clone()), ("hidDescriptor", Value::Data(descriptor))]),
        ]))]));
        let (tx, mut rx) = mpsc::unbounded_channel();
        let source = Arc::new(Mutex::new((2000, 1000)));
        let mut contact = TouchContact::Idle;
        let mut logged = 0;
        let report = [7, 1, 0, 8, 0x40]; // X=2048, Y=1024, both out of 4095.
        assert!(hid.report(Some(&id), &report).is_none());
        let sample = hid.touch_sample(Some(&id), &report, (2000, 1000)).unwrap();
        handle_touch_sample(sample, &tx, (2000, 1000),
            &TouchSurface::Display, &source, &mut contact, &mut logged, true, None);
        let touch = rx.try_recv().unwrap();
        assert_eq!((touch.action, touch.x, touch.y), (0, 1000, 250));
    }

    fn touch_contacts(device: &str, points: &[(u32, u32, u32)]) -> MultiTouchReport {
        MultiTouchReport {
            source: (device.to_owned(), 0),
            contacts: points.iter().map(|&(id, x, y)| (
                TouchIdentity::Identifier(id),
                crate::consumer_hid::TouchSample { pressed: true, x, y },
            )).collect(),
        }
    }

    #[test]
    fn multi_touch_keeps_android_ids_and_pointer_up_index_when_hid_slots_reorder() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let mut touch = TouchContacts::new(tx);
        let viewport = Some(TouchMapping::new((0, 0, 100, 100), (100, 100)));
        let drain = |rx: &mut mpsc::UnboundedReceiver<TouchInput>| {
            std::iter::from_fn(|| rx.try_recv().ok()).map(|input| (
                input.action, input.x, input.pointers.iter().map(|p| p.id).collect::<Vec<_>>(),
            )).collect::<Vec<_>>()
        };
        touch.update(touch_contacts("main", &[(9, 20, 10), (4, 70, 60)]), viewport, false);
        assert_eq!(drain(&mut rx), vec![(0, 20, vec![0]), (261, 70, vec![0, 1])]);
        touch.update(touch_contacts("main", &[(4, 71, 60), (9, 21, 10)]), viewport, false);
        assert_eq!(drain(&mut rx), vec![(2, 21, vec![0, 1])]);
        touch.update(touch_contacts("main", &[(4, 74, 60)]), viewport, false);
        assert_eq!(drain(&mut rx), vec![(6, 21, vec![0, 1])]);
        touch.update(touch_contacts("main", &[]), viewport, false);
        assert_eq!(drain(&mut rx), vec![(1, 74, vec![1])]);
    }

    #[test]
    fn multi_touch_captures_source_and_cancels_on_viewport_change_without_restarting_held_fingers() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let mut touch = TouchContacts::new(tx);
        let viewport = Some(TouchMapping::new((10, 10, 80, 80), (80, 80)));
        touch.update(touch_contacts("main", &[(1, 8, 10), (2, 20, 20)]), viewport, true);
        let down = rx.try_recv().unwrap();
        assert_eq!((down.action, down.x, down.pointers.len()), (0, 10, 1));
        // The first contact entered dead space and cannot start by sliding in.
        touch.update(touch_contacts("main", &[(1, 50, 50), (2, 8, 20)]), viewport, true);
        let moved = rx.try_recv().unwrap();
        assert_eq!((moved.action, moved.x, moved.pointers.len()), (2, 0, 1));
        assert_eq!(touch.update(touch_contacts("other", &[]), viewport, true), 0);
        assert_eq!(touch.update(touch_contacts("main", &[(2, 20, 20)]), None, true), 1);
        assert_eq!(rx.try_recv().unwrap().action, 3);
        assert_eq!(touch.update(touch_contacts("main", &[(2, 20, 20)]), viewport, true), 0);
        touch.update(touch_contacts("main", &[]), viewport, true);
        touch.update(touch_contacts("main", &[(2, 20, 20)]), viewport, true);
        assert_eq!(rx.try_recv().unwrap().action, 0);
        drop(touch);
        assert_eq!(rx.try_recv().unwrap().action, 3);
    }

    #[test]
    fn held_single_touch_cancels_before_using_replacement_carwith_canvas() {
        let (tx,mut rx)=mpsc::unbounded_channel();
        let source=Arc::new(Mutex::new((1280,372)));
        let mut down=TouchContact::Idle;let mut logged=0;
        let report=[0,1,0x56,0x05,0x8d,0x01]; // receiver (1366,397), half of 2732x794.
        handle_hid_report(&report,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        let touch=rx.try_recv().unwrap();assert_eq!((touch.action,touch.x,touch.y),(0,640,186));
        *source.lock()=(1920,720); // A negotiated source change is applied once, at the HID mapper.
        handle_hid_report(&report,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        let touch=rx.try_recv().unwrap();assert_eq!((touch.action,touch.x,touch.y),(3,640,186));
        handle_hid_report(&report,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        assert!(rx.try_recv().is_err());
        let release=[0,0,0x56,0x05,0x8d,0x01];
        handle_hid_report(&release,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        assert!(rx.try_recv().is_err());
        handle_hid_report(&report,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        let touch=rx.try_recv().unwrap();assert_eq!((touch.action,touch.x,touch.y),(0,960,360));
        handle_hid_report(&release,&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
        assert_eq!(rx.try_recv().unwrap().action,1);
        // A stretched 16:9 source on a wide receiver still maps both axes independently.
        for ((x,y), expected) in [((0u16,0u16),(0,0)), ((2049,198),(1440,179))] {
            let [xl,xh]=x.to_le_bytes(); let [yl,yh]=y.to_le_bytes();
            handle_hid_report(&[0,1,xl,xh,yl,yh],&tx,(2732,794),&TouchSurface::Display,&source,&mut down,&mut logged);
            let touch=rx.try_recv().unwrap();assert_eq!((touch.x,touch.y),expected);
        }
    }
    #[tokio::test]
    async fn mgu55_carwith_contacts_follow_the_current_written_video_config() {
        use crate::screen::{build_avcc, ScreenCipher, ScreenSender};
        use crate::pipeline::Pipeline;
        let pipeline = Arc::new(Mutex::new(Pipeline::new()));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let connect = |id| {
            let pipeline = pipeline.clone();
            let addr = listener.local_addr().unwrap();
            async move {
                let socket = TcpStream::connect(addr).await.unwrap();
                let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([7;32]), 2736, 800, false);
                sender.attach_diagnostics(pipeline, id);
                sender
            }
        };
        let mut sender = connect(1).await;
        let (tx, mut rx) = mpsc::unbounded_channel();
        let surface = TouchSurface::for_receiver(&Value::dict(vec![("model",Value::str("MGU-55"))]),
            true, (2736,800), None, &pipeline);
        let source = Arc::new(Mutex::new((2560,744)));
        let mut down = TouchContact::Idle;
        let mut logged = 0;
        let mut touch = |pressed: bool, x: u16, y: u16| {
            let [xl,xh] = x.to_le_bytes();
            let [yl,yh] = y.to_le_bytes();
            handle_hid_report(&[0,pressed as u8,xl,xh,yl,yh], &tx, (2736,800),
                &surface, &source, &mut down, &mut logged);
            rx.try_recv().ok().map(|t| (t.action,t.x,t.y))
        };
        // The 2560x744 SPS overrides the sender's 2736x800 size hint (passthrough/fallback).
        let native = build_avcc(&[0x67,0x42,0x0,0x28,0xf4,0x1,0x40,0xb,0xff,0x2a], &[0x68,1]);
        sender.send_config(&native).await.unwrap();
        assert_eq!(touch(true,176,283), Some((0,88,255)));
        assert_eq!(touch(true,2700,790), Some((2,2559,743)));
        assert_eq!(touch(false,2700,790), Some((1,2559,743)));
        for (x,y) in [(87,28), (88,27), (2648,28), (88,772)] {
            assert_eq!(touch(true,x,y), None);
            assert_eq!(touch(true,1368,400), None);
            assert_eq!(touch(false,1368,400), None);
        }
        assert_eq!(touch(true,88,28), Some((0,0,0)));
        assert_eq!(touch(false,2647,771), Some((1,2559,743)));

        // First config of the replacement sender is pending. A stale sender cannot publish again.
        let mut replacement = connect(2).await;
        sender.send_config(&native).await.unwrap();
        assert_eq!(touch(true,176,283), None);
        assert_eq!(touch(false,176,283), None);
        // Without parseable SPS the header uses the sender's output size (full hardware stretch).
        let full = build_avcc(&[0x67,0x42,0,0x1f], &[0x68,1]);
        replacement.send_config(&full).await.unwrap();
        drop(sender);
        assert_eq!(touch(true,0,0), Some((0,0,0)));
        assert_eq!(touch(true,1368,400), Some((2,1280,372)));
        assert_eq!(touch(false,2735,799), Some((1,2559,743)));
        replacement.send_config(&native).await.unwrap();
        assert_eq!(touch(true,176,283), Some((0,88,255)));
        replacement.reset_source();
        assert_eq!(touch(false,0,0), Some((3,88,255)));
        assert_eq!(touch(true,176,283), None);
        replacement.send_config(&native).await.unwrap();
        assert_eq!(touch(true,176,283), None);
        assert_eq!(touch(false,176,283), None);
        *source.lock() = (1280,372);
        assert_eq!(touch(true,176,284), Some((0,44,128)));
        replacement.send_config(&native).await.unwrap();
        assert_eq!(touch(true,176,284), Some((2,44,128)));
        replacement.reset_source();
        replacement.send_config(&native).await.unwrap();
        // Config disappeared and returned before the next HID report.
        assert_eq!(touch(true,176,284), Some((3,44,128)));
        assert_eq!(touch(true,176,284), None);
        assert_eq!(touch(false,176,284), None);
        assert_eq!(touch(true,176,284), Some((0,44,128)));
    }

    #[test]
    fn single_touch_cancels_at_resource_loss_and_waits_for_physical_release() {
        use crate::consumer_hid::TouchSample;
        let mut modes = crate::modes::Modes::initial();
        modes.request_ui();
        let resource = Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
            ("resourceID", Value::int(1)), ("transferType", Value::int(3)),
            ("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(100)),
        ])]))]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let mut contact = TouchContact::Idle;
        let mut multi = TouchContacts::new(tx.clone());
        let mut grant = modes.screen_grant_generation();
        let mut logged = 0;
        let target = Arc::new(Mutex::new((1920, 720)));
        let mut handle = |modes: &crate::modes::Modes, pressed: Option<bool>| {
            let owned = sync_touch_grant(modes, &mut grant, &mut contact, &mut multi, &tx);
            if let Some(pressed) = pressed {
                handle_touch_sample(TouchSample { pressed, x: 88, y: 255 },
                    &tx, (1920, 720), &TouchSurface::Display, &target, &mut contact, &mut logged, owned, None);
            }
            rx.try_recv().ok().map(|t| (t.action, t.x, t.y))
        };

        assert_eq!(handle(&modes, Some(true)), Some((0, 88, 255)));
        assert!(modes.change(&resource));
        assert!(!modes.screen_owned());
        assert_eq!(handle(&modes, None), Some((3, 88, 255)));
        assert_eq!(handle(&modes, Some(true)), None);
        assert_eq!(handle(&modes, Some(false)), None);
        // A new finger placed while the car owns the main screen stays suppressed.
        assert_eq!(handle(&modes, Some(true)), None);
        modes.request_ui();
        assert!(modes.screen_owned());
        assert_eq!(handle(&modes, Some(true)), None);
        assert_eq!(handle(&modes, Some(false)), None);
        assert_eq!(handle(&modes, Some(true)), Some((0, 88, 255)));
        // Resource messages may both be applied before the dispatcher reads the next HID report.
        assert!(modes.change(&resource));
        modes.request_ui();
        assert!(modes.screen_owned());
        assert_eq!(handle(&modes, Some(false)), Some((3, 88, 255)));
        assert_eq!(handle(&modes, Some(true)), Some((0, 88, 255)));
        assert_eq!(handle(&modes, Some(false)), Some((1, 88, 255)));
    }

    #[tokio::test]
    async fn queued_hid_does_not_click_after_the_event_reader_applies_a_take_and_return() {
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};

        async fn request(peer: &mut TcpStream, cseq: i32, command: Value) {
            let body = bplist::encode(&command);
            let header = format!("POST /command RTSP/1.0\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", body.len());
            peer.write_all(header.as_bytes()).await.unwrap();
            peer.write_all(&body).await.unwrap();
            let result = tokio::time::timeout(Duration::from_secs(1), async {
                let mut buffer = Vec::new();
                let mut chunk = [0; 1024];
                loop {
                    let bytes = peer.read(&mut chunk).await.unwrap();
                    assert!(bytes > 0);
                    buffer.extend_from_slice(&chunk[..bytes]);
                    if let Some(reply) = crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap().pop() {
                        break reply;
                    }
                }
            }).await.unwrap();
            assert_eq!(result.cseq(), Some(cseq));
            assert_eq!(result.status(), 200);
        }

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let _control_peer = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (phone, _) = listener.accept().await.unwrap();
        let (read, write) = phone.into_split();
        let (control, _control_input) = RtspChannel::spawn(read, write, None);
        let mut peer = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (phone, _) = listener.accept().await.unwrap();
        let (read, write) = phone.into_split();
        let modes = Arc::new(Mutex::new(crate::modes::Modes::initial()));
        modes.lock().request_ui();
        let reply_modes = modes.clone();
        let reply: crate::rtsp::CommandReply = Arc::new(move |message| {
            let command = bplist::parse(&message.body).ok()?;
            let mut modes = reply_modes.lock();
            capture_hid_screen_grant(message, &command, &modes);
            if command.get("type").and_then(Value::as_str) == Some("requestUI") { modes.request_ui(); }
            modes.response(&command, &Default::default()).map(|reply| reply.body)
        });
        let (_event, inbound) = control.spawn_event(read, write, Some(reply.clone()), Arc::new(|_, _| {}));
        let report = |pressed, x| Value::dict(vec![
            ("type", Value::str("hidSendReport")), ("hidReport", Value::Data(vec![pressed, x, 0, 100, 0])),
        ]);
        request(&mut peer, 1, report(1, 20)).await;
        request(&mut peer, 2, report(0, 20)).await;
        request(&mut peer, 3, Value::dict(vec![
            ("type", Value::str("changeModes")), ("params", Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
                ("resourceID", Value::int(1)), ("transferType", Value::int(3)),
                ("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(100)),
            ])]))])),
        ])).await;
        assert!(!modes.lock().screen_owned());
        request(&mut peer, 4, report(1, 30)).await;
        request(&mut peer, 5, Value::dict(vec![("type", Value::str("requestUI"))])).await;
        assert!(modes.lock().screen_owned());
        request(&mut peer, 6, report(0, 30)).await;
        request(&mut peer, 7, report(1, 40)).await;
        request(&mut peer, 8, report(0, 40)).await;

        // The dispatcher first sees the old HID messages after the newer grant is already live.
        let (_ui, ui_rx) = mpsc::unbounded_channel();
        let (touch, mut touch_rx) = mpsc::unbounded_channel();
        let (keys, _key_rx) = mpsc::unbounded_channel();
        let info = Value::dict(vec![]);
        let dispatcher = spawn_event_dispatcher(inbound, ui_rx, reply, control,
            Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
            crate::cluster_screen::Commands::default(), 0, modes, None, touch, keys,
            crate::consumer_hid::ConsumerHid::for_session(0, &info, "main"),
            crate::receiver_ui::LimitedUi::from_info(&info), Some("main".into()),
            (1920, 720), TouchSurface::Display, Arc::new(Mutex::new((1920, 720))), Arc::default());
        for action in [0, 1] {
            let input = tokio::time::timeout(Duration::from_secs(1), touch_rx.recv()).await.unwrap().unwrap();
            assert_eq!((input.action, input.x, input.y), (action, 40, 100));
        }
        assert!(touch_rx.try_recv().is_err());
        dispatcher.abort();
    }

    #[test]
    fn mgu55_touch_viewport_requires_the_confirmed_receiver_and_carwith_profile() {
        let pipeline = Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        pipeline.lock().set_screen_config_size(Some((2560,744)));
        for (model,name,carwith_profile) in [
            ("MGU-55","BMW",false), ("MGU-55","DiPlay",true), ("MGU-55","WinPlay",true),
            ("MGU-86","BMW",true), ("IDC23","BMW",true), ("MB_D-PP","Mercedes",true),
        ] {
            let info = Value::dict(vec![("model",Value::str(model)),("name",Value::str(name))]);
            let surface = TouchSurface::for_receiver(&info,carwith_profile,(2736,800),None,&pipeline);
            assert_eq!(surface.mapping((2736,800), (1280,720)).map(|mapping| mapping.viewport), Some((0,0,2736,800)));
        }
        let info = Value::dict(vec![("model",Value::str("MGU-55"))]);
        let surface = TouchSurface::for_receiver(&info,true,(0,0),Some((10,20,1610,920)),&pipeline);
        assert_eq!(surface.mapping((1920,1080), (1280,720)).map(|mapping| mapping.viewport), Some((10,20,1600,900)));
    }
    #[test]
    fn media_latency_diagnostics_preserve_raw_values_and_mark_missing_entries() {
        let info = Value::dict(vec![("audioLatencies", Value::array(vec![
            Value::dict(vec![("type", Value::int(100)), ("audioType", Value::str("default")),
                ("outputLatencyMicros", Value::int(72_000))]),
            Value::dict(vec![("type", Value::int(102)), ("audioType", Value::str("media")),
                ("outputLatencyMicros", Value::int(214_000))]),
            Value::dict(vec![("streamType", Value::int(103)), ("audioType", Value::str("MEDIA"))]),
            Value::dict(vec![("type", Value::int(103)), ("audioType", Value::str("media")),
                ("outputLatencyMicros", Value::int(0))]),
        ]))]);
        let lines = media_info_latency_lines(31, "session_info", &info);
        assert_eq!(lines, [
            "AUDIO-LATENCY: RTSP#31 INFO source=session_info type=100 audioType=media outputLatencyMicros=missing_entry",
            "AUDIO-LATENCY: RTSP#31 INFO source=session_info type=102 audioType=media outputLatencyMicros=214000",
            "AUDIO-LATENCY: RTSP#31 INFO source=session_info type=103 audioType=media outputLatencyMicros=missing_field,0",
        ]);
        assert!(media_info_latency_lines(32, "fallback_error", &Value::dict(vec![])).iter()
            .all(|line| line.contains("RTSP#32 INFO source=fallback_error") && line.ends_with("missing_entry")));
    }

    #[test]
    fn media_setup_uses_300_ms_for_realtime_and_1000_ms_for_buffered_tcp() {
        let entry = |kind, role, micros| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(role)),
            ("outputLatencyMicros", Value::int(micros)),
        ]);
        let info = Value::dict(vec![("audioLatencies", Value::array(vec![
            entry(100, "speechRecognition", 900_000),
            entry(101, "media", 750_000),
            entry(100, "media", 350_900),
            entry(102, "media", 35_000),
            entry(103, "media", 410_000),
        ]))]);
        use AudioCodec::*;
        for (codec, expected, default_ms) in [(Aac, 300, 300), (Aac441, 300, 300),
            (Pcm, 350, 300), (Pcm441, 350, 300), (BufferedAac, 1000, 1000)] {
            let body = bplist::parse(&build_audio_setup_for_codec(42, media_latency_ms(&info, codec), codec)).unwrap();
            let stream = &body.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("audioLatencyMs").and_then(Value::as_int), Some(expected), "{codec:?}");
            assert_eq!(stream.get("audioType").and_then(Value::as_str), Some("media"));
            assert_eq!(media_latency_ms(&Value::dict(vec![]), codec), default_ms);
        }
        let larger = Value::dict(vec![("audioLatencies", Value::array(vec![entry(103, "media", 1_500_000)]))]);
        assert_eq!(media_latency_ms(&larger, AudioCodec::BufferedAac), 1500);
        let invalid = Value::dict(vec![("audioLatencies", Value::array(vec![entry(100, "media", -500_000)]))]);
        assert_eq!(media_latency_ms(&invalid, AudioCodec::Pcm), 300);
    }

    #[test]
    fn media_latency_matches_rate_sample_size_channels_and_compression() {
        let entry = |kind, conditions: &[(&str, i64)], micros| {
            let mut fields = vec![("type", Value::int(kind)), ("audioType", Value::str("media")),
                ("outputLatencyMicros", Value::int(micros))];
            fields.extend(conditions.iter().map(|&(key, value)| (key, Value::int(value))));
            Value::dict(fields)
        };
        let info = Value::dict(vec![("audioLatencies", Value::array(vec![
            entry(102, &[("sr", 44_100), ("ss", 16), ("ch", 2), ("ct", 4)], 510_000),
            entry(102, &[("ss", 24)], 520_000),
            entry(102, &[("ch", 1)], 530_000),
            entry(102, &[("ct", 1)], 540_000),
            entry(102, &[("sr", 48_000), ("ss", 16), ("ch", 2), ("ct", 4)], 550_000),
            entry(100, &[("ct", 4)], 560_000),
            entry(100, &[("ct", 1), ("ss", 16)], 570_000),
            entry(103, &[("ct", 8)], 2_100_000),
            entry(103, &[("ct", 4)], 1_400_000),
        ]))]);
        for (codec, expected) in [
            (AudioCodec::Aac441, 510), (AudioCodec::Aac, 550),
            (AudioCodec::Pcm, 570), (AudioCodec::BufferedAac, 1400),
        ] {
            let setup = bplist::parse(&build_audio_setup_for_codec(42, media_latency_ms(&info, codec), codec)).unwrap();
            let stream = &setup.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("audioLatencyMs").and_then(Value::as_int), Some(expected), "{codec:?}");
        }
    }

    #[test]
    fn media_latency_uses_first_matching_entry_with_optional_selectors() {
        let info = Value::dict(vec![("audioLatencies", Value::array(vec![
            Value::dict(vec![("sr", Value::str("48000")), ("outputLatencyMicros", Value::int(800_000))]),
            Value::dict(vec![("audioType", Value::str("default")), ("outputLatencyMicros", Value::int(700_000))]),
            Value::dict(vec![("ch", Value::int(2)), ("outputLatencyMicros", Value::int(600_000))]),
            Value::dict(vec![("type", Value::int(100)), ("audioType", Value::str("media")),
                ("sr", Value::int(48_000)), ("outputLatencyMicros", Value::int(500_000))]),
        ]))]);
        assert_eq!(media_latency_ms(&info, AudioCodec::Pcm), 600);
    }

    #[test]
    fn pcm_setup_with_no_declared_latency_preserves_wire_format() {
        let codec = AudioCodec::Pcm;
        let body = bplist::parse(&build_audio_setup_for_codec(42, media_latency_ms(&Value::dict(vec![]), codec), codec)).unwrap();
        let stream = &body.get("streams").unwrap().as_array().unwrap()[0];
        assert_eq!(stream.get("audioLatencyMs").and_then(Value::as_int), Some(300));
        assert_eq!(stream.get("spf").and_then(Value::as_int), Some(352));
        assert_eq!(stream.get("audioFormat").and_then(Value::as_int), Some(1 << 15));
        assert_eq!(stream.get("streamConnectionID").and_then(Value::as_int), Some(42));
    }
    #[tokio::test(start_paused = true)]
    async fn carwith_media_init_orders_only_declared_matching_output_rates() {
        use AudioCodec::*;
        let formats = |aac,pcm| Value::dict(vec![("audioFormats",Value::array(vec![
            Value::dict(vec![("type",Value::int(102)),("audioType",Value::str("media")),
                ("audioOutputFormats",Value::int(aac)),("audioInputFormats",Value::int(1<<22))]),
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("media")),
                ("audioOutputFormats",Value::int(pcm))]),
        ]))]);
        let both=formats((1<<22)|(1<<23),1<<15);
        let (tx,rx)=tokio::sync::watch::channel(None);
        let (plan,())=tokio::join!(media_setup_candidates(&both,"auto",false,true,Some(&rx)),async {
            tokio::time::sleep(Duration::from_millis(100)).await;
            tx.send_replace(Some(44100));
        });
        assert_eq!(plan, [Aac441,Pcm,Aac]);
        assert_eq!(media_setup_candidates(&formats(1<<23,0),"auto",false,true,Some(&rx)).await,[Aac]);
        assert_eq!(media_setup_candidates(&formats(1<<23,1<<11),"pcm",false,true,Some(&rx)).await,[Pcm441]);
        assert_eq!(media_setup_candidates(&both,"pcm",false,true,Some(&rx)).await,[Pcm]);
        tx.send_replace(Some(48000));
        assert_eq!(media_setup_candidates(&both,"auto",true,true,Some(&rx)).await,[Aac,Pcm,Aac441]);
        tx.send_replace(None);
        assert_eq!(media_setup_candidates(&both,"auto",false,true,Some(&rx)).await,[Pcm,Aac,Aac441]);
        assert_eq!(media_setup_candidates(&both,"auto",false,true,None).await,[Pcm,Aac,Aac441]);
    }

    #[test]
    fn nbt_selects_only_declared_output_and_setup_matches_encoder_rate() {
        let info=Value::dict(vec![("audioFormats",Value::array(vec![
            Value::dict(vec![("type",Value::int(102)),("audioType",Value::str("media")),
                ("audioOutputFormats",Value::int((1<<22)|(1<<23)))]),
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("media")),
                ("audioOutputFormats",Value::int(1<<15))]),
        ]))]);
        assert_eq!(nbt_audio_candidates(&info,"auto"),vec![AudioCodec::Aac441,AudioCodec::Aac,AudioCodec::Pcm]);
        assert_eq!(nbt_audio_candidates(&info,"pcm"),vec![AudioCodec::Pcm]);
        assert!(nbt_audio_candidates(&Value::dict(vec![]),"auto").is_empty());
        let input=Value::dict(vec![("audioFormats",Value::array(vec![Value::dict(vec![
            ("type",Value::int(102)),("audioType",Value::str("media")),("audioInputFormats",Value::int(1<<22))])]))]);
        assert!(nbt_audio_candidates(&input,"auto").is_empty());
        for codec in [AudioCodec::Aac441,AudioCodec::Aac] {
            let setup=bplist::parse(&build_audio_setup_for_codec(42,0,codec)).unwrap();
            let stream=&setup.get("streams").unwrap().as_array().unwrap()[0];
            for (key,value) in [("type",102),("sr",codec.rate() as i64),("audioFormat",codec.format()),("spf",1024),("ct",4)] {
                assert_eq!(stream.get(key).and_then(Value::as_int),Some(value));
            }
        }
        assert_eq!(audio_candidates(&info, "auto", false), [AudioCodec::Pcm, AudioCodec::Aac, AudioCodec::Aac441]);
        assert_eq!(audio_candidates(&info, "auto", true), [AudioCodec::Pcm, AudioCodec::Aac, AudioCodec::Aac441]);
    }
    #[test]
    fn aac_setup_and_candidates_follow_declared_media_output_before_pcm_default() {
        let format=|kind,audio_type,bits| Value::dict(vec![("type",Value::int(kind)),
            ("audioType",Value::str(audio_type)),("audioOutputFormats",Value::int(bits))]);
        let info=|formats| Value::dict(vec![("audioFormats",Value::array(formats))]);
        let aac_only=info(vec![format(102,"media",1<<23)]);
        assert_eq!(audio_candidates(&aac_only,"auto",false),vec![AudioCodec::Aac,AudioCodec::Pcm]);
        assert_eq!(audio_candidates(&aac_only,"auto",true),vec![AudioCodec::Aac]);
        assert_eq!(audio_candidates(&aac_only,"pcm",false),vec![AudioCodec::Pcm]);
        assert_eq!(audio_candidates(&aac_only,"pcm",true),vec![]);
        assert_eq!(audio_candidates(&aac_only,"aac",false),vec![AudioCodec::Aac,AudioCodec::Pcm]);
        assert_eq!(audio_candidates(&info(vec![format(102,"telephony",1<<23)]),"auto",true),vec![]);
        assert_eq!(audio_candidates(&info(vec![format(100,"media",1<<23)]),"auto",true),vec![]);
        assert_eq!(audio_candidates(&info(vec![]),"auto",false),vec![AudioCodec::Pcm]);
        let setup=bplist::parse(&build_audio_setup_for_codec(42,250,AudioCodec::Aac)).unwrap();
        let stream=&setup.get("streams").unwrap().as_array().unwrap()[0];
        for (key,value) in [("type",102),("audioFormat",1<<23),("spf",1024),("ct",4),("sr",48000)] {
            assert_eq!(stream.get(key).and_then(Value::as_int),Some(value));
        }
    }

    #[test]
    fn automatic_legacy_media_accepts_only_same_row_output_at_supported_rates() {
        use AudioCodec::*;
        let format = |kind, role, direction, bits| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(role)), (direction, Value::int(bits)),
        ]);
        let info = Value::dict(vec![("audioFormats", Value::array(vec![
            format(102, "media", "audioInputFormats", 1 << 23),
            format(102, "default", "audioOutputFormats", 1 << 23),
            format(101, "media", "audioOutputFormats", 1 << 23),
            format(102, "media", "audioOutputFormats", -1),
            format(100, "media", "audioOutputFormats", 1 << 11),
            format(102, "media", "audioOutputFormats", 1 << 22),
        ]))]);
        assert_eq!(audio_candidates(&info, "auto", false), [Pcm441, Aac441, Pcm]);
        assert_eq!(audio_candidates(&info, "auto", true), [Pcm441, Aac441]);
    }
    #[test]
    fn desktop_info_overrides_bmw_bootstrap_without_weakening_real_car_grants() {
        for key in ["name", "model", "manufacturer"] {
            for identity in ["DiPlay", "WINPLAY"] {
                assert!(!use_box_bootstrap(true, &Value::dict(vec![(key, Value::str(identity))])));
            }
        }
        assert!(use_box_bootstrap(true, &Value::dict(vec![])));
        assert!(use_box_bootstrap(true, &Value::dict(vec![("manufacturer", Value::str("BMW"))])));
        assert!(!use_box_bootstrap(false, &Value::dict(vec![])));
    }
    #[test]
    fn motorcycle_keeps_box_bootstrap_without_requesting_bluetooth_handoff() {
        let info = Value::dict(vec![("model", Value::str("Angle"))]);
        let box_bootstrap = use_box_bootstrap(true, &info);
        assert!(box_bootstrap);
        assert!(!should_request_bluetooth_handoff(box_bootstrap, false, true));
        assert!(should_request_bluetooth_handoff(box_bootstrap, false, false));
        assert!(!should_request_bluetooth_handoff(box_bootstrap, true, false));
        assert!(!should_request_bluetooth_handoff(false, false, false));
    }
    #[test]
    fn bluetooth_credential_key_stays_stable_when_receiver_ip_changes() {
        assert_eq!(credential_tag(Some("bt_aabbccddee01"), "172_16_1_1"), "bt_aabbccddee01");
        assert_eq!(credential_tag(Some("bt_aabbccddee01"), "192_168_1_1"), "bt_aabbccddee01");
        assert_ne!(credential_tag(Some("bt_aabbccddee02"), "192_168_1_1"), "bt_aabbccddee01");
        assert_eq!(credential_tag(Some("../../other"), "receiver_pk"), "receiver_pk");
    }
    #[test]
    fn pixel_dimensions_take_precedence_over_legacy_fields() {
        let display = Value::dict(vec![("widthPixels", Value::Int(2880)), ("heightPixels", Value::Int(1080)),
            ("width", Value::Int(0)), ("height", Value::Int(0))]);
        assert_eq!(read_display_size(&display), (2880, 1080));
    }
    #[test]
    fn legacy_dimensions_and_invalid_values() {
        assert_eq!(read_display_size(&Value::dict(vec![("width", Value::Int(2560)), ("height", Value::Int(744))])), (2560, 744));
        assert_eq!(read_display_size(&Value::dict(vec![("widthPixels", Value::Int(-1)), ("heightPixels", Value::Int(999999))])), (0, 0));
    }

    #[test]
    fn local_audio_is_declared_and_receiver_can_borrow_it() {
        let mut modes = crate::modes::Modes::initial();
        let command = bplist::parse(&modes.declaration()).unwrap();
        assert_eq!(command.get("type").and_then(Value::as_str), Some("modesChanged"));
        assert!(modes.audio_owned());
        let request = Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
            ("resourceID", Value::int(2)), ("transferType", Value::int(3)),
            ("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(100)),
        ])]))]);
        assert!(modes.change(&request)); assert!(!modes.audio_owned());
        let release = Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
            ("resourceID", Value::int(2)), ("transferType", Value::int(4)),
        ])]))]);
        assert!(modes.change(&release)); assert!(modes.audio_owned());
    }

    #[tokio::test]
    async fn slow_modes_feedback_does_not_block_iap_and_coalesces_latest_state() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car,_) = listener.accept().await.unwrap();
        let (r,w) = socket.into_split(); let (control,_) = RtspChannel::spawn(r,w,None);
        let stop = Arc::new(Notify::new());
        let modes = Arc::new(Mutex::new(crate::modes::Modes::initial()));
        let feedback = spawn_mode_feedback(control.clone(), stop.clone(), modes.clone(), None);
        let snapshot = modes.lock().declaration();
        feedback.send(Some(ModeFeedback::Snapshot)).unwrap();
        let read_request = |mut car: tokio::net::TcpStream| async move {
            let mut buffer=Vec::new(); let mut chunk=[0;4096];
            loop { let n=car.read(&mut chunk).await.unwrap(); assert!(n>0); buffer.extend_from_slice(&chunk[..n]);
                let messages=crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap();
                if !messages.is_empty() { assert_eq!(messages.len(),1); return (car,messages.into_iter().next().unwrap()); }
            }
        };
        let (socket,first)=tokio::time::timeout(Duration::from_secs(1),read_request(car)).await.unwrap(); car=socket;
        // Keep the first mode reply pending while subsequent state updates and iAP output arrive.
        feedback.send(Some(ModeFeedback::Snapshot)).unwrap(); feedback.send(Some(ModeFeedback::Snapshot)).unwrap();
        let cseq=control.post_fire_and_forget("/command",b"iap-probe","application/octet-stream").await.unwrap();
        let (socket,iap)=tokio::time::timeout(Duration::from_secs(1),read_request(car)).await.unwrap(); car=socket;
        assert_eq!(iap.cseq(),Some(cseq)); assert_eq!(iap.body,b"iap-probe");
        let reply=format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",first.cseq().unwrap());
        car.write_all(reply.as_bytes()).await.unwrap();
        let (socket,latest)=tokio::time::timeout(Duration::from_secs(1),read_request(car)).await.unwrap(); car=socket;
        assert_eq!(latest.body,snapshot);
        drop(feedback); stop.notify_waiters(); drop(car);
    }

    #[tokio::test]
    async fn record_success_survives_peer_close_during_resource_declaration() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 4096];
            loop {
                let n = socket.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap() {
                    if request.first_line.starts_with("POST /command ") {
                        return; // BMW's observed EOF immediately following RECORD 200.
                    }
                    assert_eq!(request.first_line, "RECORD / RTSP/1.0");
                    let response = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
                    socket.write_all(response.as_bytes()).await.unwrap();
                }
            }
        });
        let socket = TcpStream::connect(address).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        let pipeline = Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        let error = activate_session(&channel, &crate::modes::Modes::initial().declaration(), &pipeline).await.unwrap_err();
        assert!(error.contains("控制通道已关闭"), "{error}");
        let p = pipeline.lock();
        assert!(p.record_accepted);
        assert!(!p.resources_accepted);
        assert!(!p.video_channel_ready);
        drop(p);
        peer.await.unwrap();
    }

    #[tokio::test]
    async fn session_setup_and_info_precede_record_then_resource_declaration() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut requests = Vec::new();
            let mut chunk = [0u8; 4096];
            while let Ok(n) = socket.read(&mut chunk).await {
                if n == 0 { break; }
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap() {
                    requests.push(request.first_line.clone());
                    let body = match requests.len() - 1 {
                        0 => {
                            assert_eq!(request.first_line, "POST /auth-setup RTSP/1.0");
                            assert_eq!(request.body.len(), 33); assert_eq!(request.body[0], 1);
                            let mut body = crate::crypto::X25519KeyPair::generate().public.to_vec();
                            body.extend(3u32.to_be_bytes()); body.extend([1,2,3]);
                            body.extend(64u32.to_be_bytes()); body.extend([0x37;64]); body
                        }
                        1 => {
                            assert_eq!(request.first_line, "SETUP /setup RTSP/1.0");
                            let body=bplist::parse(&request.body).unwrap();
                            assert_eq!(body.get("deviceID").and_then(Value::as_str), Some("84:AE:DE:56:D3:76"));
                            assert_eq!(body.get("macAddress").and_then(Value::as_str), Some("06:12:34:56:78:90"));
                            let features=body.get("features").unwrap().as_array().unwrap();
                            for absent in ["hevc","mainBuffered","sessionManagement","logTransfer","vehicleStateProtocol"] {
                                assert!(!features.iter().any(|f|f.as_str()==Some(absent)));
                            }
                            assert!(features.iter().any(|f|f.as_str()==Some("iAPChannel")));
                            bplist::encode(&Value::dict(vec![("eventPort", Value::int(5001))]))
                        }
                        2 => {
                            assert_eq!(request.first_line, "GET /info RTSP/1.0");
                            bplist::encode(&Value::dict(vec![("displays", Value::array(vec![Value::dict(vec![
                                ("uuid", Value::str("actual-bmw-display")),
                                ("widthPixels", Value::int(2560)), ("heightPixels", Value::int(744)),
                            ])]))]))
                        }
                        3 => {
                            assert_eq!(request.first_line, "RECORD / RTSP/1.0");
                            assert!(request.body.is_empty());
                            Vec::new()
                        }
                        4 => {
                            assert_eq!(request.first_line, "POST /command RTSP/1.0");
                            assert_eq!(bplist::parse(&request.body).unwrap().get("type").and_then(Value::as_str), Some("modesChanged"));
                            Vec::new()
                        }
                        _ => panic!("unexpected extra request"),
                    };
                    let response = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n", request.cseq().unwrap(), body.len());
                    socket.write_all(response.as_bytes()).await.unwrap();
                    socket.write_all(&body).await.unwrap();
                }
            }
            requests
        });
        let socket = TcpStream::connect(addr).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        crate::auth_setup::run(&channel).await.unwrap();
        let (_, info, _, _) = setup_and_read_info(&channel, "84:AE:DE:56:D3:76", "Test phone", 12345, true, Value::dict(vec![]), false, false, false, true, false, false, None, Some("06:12:34:56:78:90"), None, false).await.unwrap();
        let display = &info.value.get("displays").unwrap().as_array().unwrap()[0];
        assert_eq!(uuid_text(display.get("uuid")).as_deref(), Some("actual-bmw-display"));
        assert_eq!(read_display_size(display), (2560, 744));
        activate_session(&channel, &crate::modes::Modes::initial().declaration(), &Arc::new(Mutex::new(crate::pipeline::Pipeline::new()))).await.unwrap();
        drop(channel);
        assert_eq!(peer.await.unwrap().len(), 5);
    }

    pub(super) struct LocalSockets;
    impl SocketProvider for LocalSockets {
        fn open_tcp(&self, _: &str, _: u16, _: u32) -> Option<TcpStream> { None }
        fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
            let socket = std::net::UdpSocket::bind("127.0.0.1:0").ok()?;
            if port != 0 { socket.connect((host, port)).ok()?; }
            socket.set_nonblocking(true).ok()?;
            UdpSocket::from_std(socket).ok()
        }
    }

    #[tokio::test]
    async fn realtime_media_advertises_its_actual_control_listener() {
        let factory = LocalSockets;
        let car = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let car_addr = car.local_addr().unwrap();
        for (codec, expected) in [
            (AudioCodec::Aac, true),
            (AudioCodec::Aac441, true),
            (AudioCodec::Pcm, true),
            (AudioCodec::Pcm441, true),
            (AudioCodec::BufferedAac, false),
        ] {
            let control = bind_main_audio_control(&factory, "127.0.0.1", codec);
            let port = control.as_ref().map(|s| s.local_addr().unwrap().port());
            let body = bplist::parse(&build_audio_setup_request(42, 300, codec, None, port, expected)).unwrap();
            let stream = &body.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("controlPort").and_then(Value::as_int), port.map(i64::from));
            assert_eq!(stream.get("supportsHighAccuracyTimestamps").and_then(Value::as_bool), expected.then_some(true));
            assert_eq!(control.is_some(), expected, "{codec:?}");
            if let Some(socket) = control {
                let local = socket.local_addr().unwrap();
                socket.connect(car_addr).await.unwrap();
                car.send_to(&[0x80, 0xd5, 0, 1, 0, 2, 0, 1], local).await.unwrap();
                let mut packet = [0u8; 8];
                let size = tokio::time::timeout(Duration::from_secs(1), socket.recv(&mut packet)).await.unwrap().unwrap();
                assert_eq!(&packet[..size], &[0x80, 0xd5, 0, 1, 0, 2, 0, 1]);
                socket.send(&[0x80, 0xd4]).await.unwrap();
                let (_, source) = car.recv_from(&mut packet).await.unwrap();
                assert_eq!(source.port(), port.unwrap());
            }
        }
    }

    #[tokio::test(start_paused = true)]
    async fn idle_udp_keepalive_sends_immediately_then_every_ten_seconds() {
        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let stop = Arc::new(Notify::new());
        let media = crate::udp_keepalive::MediaActivity::default();
        let worker = crate::callback_context::spawn(crate::udp_keepalive::run(Arc::new(LocalSockets),
            "127.0.0.1".into(), udp.local_addr().unwrap().port(), stop.clone(), 42, Some(media.subscribe())));
        tokio::task::yield_now().await;
        let mut bytes = [0; 4];
        for sequence in [1u32, 2] {
            if sequence == 2 {
                tokio::time::advance(Duration::from_millis(9999)).await;
                tokio::task::yield_now().await;
                assert!(matches!(udp.try_recv(&mut bytes), Err(e) if e.kind() == std::io::ErrorKind::WouldBlock));
                tokio::time::advance(Duration::from_millis(1)).await;
            }
            let size = tokio::time::timeout(Duration::from_millis(5), udp.recv(&mut bytes)).await
                .expect("UDP packet missing at scheduled tick").unwrap();
            assert_eq!(size, 4);
            assert_eq!(bytes, sequence.to_be_bytes());
        }
        stop.notify_one();
        worker.await.unwrap();
    }

    #[test]
    fn idle_udp_keepalive_scope() {
        for (id9, mbux, desktop, expected) in [
            (true, false, false, true), (false, true, false, true),
            (false, false, false, false), (true, false, true, false), (false, true, true, false),
        ] {
            assert_eq!(crate::udp_keepalive::idle_only("", id9, mbux, desktop), expected);
        }
        for (profile, desktop, expected) in [
            ("bmw_id85", false, true), ("bmw_id85", true, false),
            ("bmw_id6", false, false), ("bmw_custom", false, false),
        ] {
            assert_eq!(crate::udp_keepalive::idle_only(profile, false, false, desktop), expected);
        }
    }

    #[tokio::test(start_paused = true)]
    async fn udp_keepalive_only_rearms_after_last_media_teardown() {
        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let stop = Arc::new(Notify::new());
        let activity = crate::udp_keepalive::MediaActivity::default();
        let media = activity.subscribe();
        activity.set(110, true); // A fast SETUP must not suppress the first packet.
        let worker = crate::callback_context::spawn(crate::udp_keepalive::run(Arc::new(LocalSockets),
            "127.0.0.1".into(), udp.local_addr().unwrap().port(), stop.clone(), 42, Some(media)));
        let mut bytes = [0; 4];
        assert_eq!(tokio::time::timeout(Duration::from_millis(5), udp.recv(&mut bytes)).await.unwrap().unwrap(), 4);
        assert_eq!(bytes, 1u32.to_be_bytes());

        activity.set(100, true);
        activity.set(101, true);
        activity.set(110, false);
        activity.set(100, false); // Navigation still active.
        assert!(tokio::time::timeout(Duration::from_secs(25), udp.recv(&mut bytes)).await.is_err());
        activity.set(101, false);
        tokio::time::advance(Duration::from_secs(9)).await;
        activity.set(102, true); // MainHigh cancels the pending idle tick.
        assert!(tokio::time::timeout(Duration::from_secs(15), udp.recv(&mut bytes)).await.is_err());
        activity.set(102, false);
        tokio::time::advance(Duration::from_secs(7)).await;
        activity.set(110, false); // Repeated teardown does not restart the clock.
        assert!(tokio::time::timeout(Duration::from_millis(2998), udp.recv(&mut bytes)).await.is_err());
        assert_eq!(tokio::time::timeout(Duration::from_millis(5), udp.recv(&mut bytes)).await.unwrap().unwrap(), 4);
        assert_eq!(bytes, 2u32.to_be_bytes());
        activity.set(110, true);
        stop.notify_one();
        worker.await.unwrap();
        assert!(tokio::time::timeout(Duration::from_secs(12), udp.recv(&mut bytes)).await.is_err());
    }

    #[tokio::test]
    async fn keepalive_runs_while_record_waits_and_stops_with_its_owner() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let udp_port = udp.local_addr().unwrap().port();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 4096];
            let mut record_cseq = None;
            loop {
                let n = socket.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap() {
                    match request.first_line.as_str() {
                        "RECORD / RTSP/1.0" => {
                            assert!(request.body.is_empty(), "CatPlay RECORD has no plist body");
                            record_cseq = request.cseq();
                        }
                        "POST /feedback RTSP/1.0" => {
                            let record_cseq = record_cseq.expect("feedback must run during pending RECORD");
                            let response = format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\nRTSP/1.0 200 OK\r\nCSeq: {record_cseq}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
                            socket.write_all(response.as_bytes()).await.unwrap();
                            // Hold the socket until the test aborts the receiver task.
                            std::future::pending::<()>().await;
                        }
                        other => panic!("unexpected request: {other}"),
                    }
                }
            }
        });
        let socket = TcpStream::connect(addr).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        let tasks = spawn_keepalive_loops(channel.clone(), Arc::new(LocalSockets), udp_port, "127.0.0.1".to_string(), Arc::new(Notify::new()), None, false, Arc::new(Mutex::new(crate::pipeline::Pipeline::new())));
        let handles: Vec<_> = tasks.0.iter().map(|task| task.abort_handle()).collect();
        let record = record_session(&channel);
        let ping = async {
            let mut bytes = [0u8; 8];
            let (len, _) = tokio::time::timeout(Duration::from_secs(1), udp.recv_from(&mut bytes)).await.unwrap().unwrap();
            assert_eq!(len, 4, "UDP keepalive must precede RECORD completion");
        };
        let (record_result, ()) = tokio::join!(record, ping);
        record_result.unwrap();
        drop(tasks);
        tokio::task::yield_now().await;
        assert!(handles.iter().all(|handle| handle.is_finished()));
        peer.abort();
    }

    #[tokio::test]
    async fn rejected_info_never_sends_state_changing_pairing_probes() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = crate::callback_context::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut methods = Vec::new();
            let mut chunk = [0u8; 4096];
            while let Ok(n) = socket.read(&mut chunk).await {
                if n == 0 { break; }
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap() {
                    methods.push(request.first_line.clone());
                    assert!(!request.first_line.contains("/pair-"), "diagnostic changed pairing state");
                    let response = format!("RTSP/1.0 455 Method Not Valid In This State\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
                    socket.write_all(response.as_bytes()).await.unwrap();
                }
            }
            methods
        });
        let socket = TcpStream::connect(addr).await.unwrap();
        let (read, write) = socket.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        assert!(fetch_info(&channel).await.is_err());
        drop(channel);
        let methods = peer.await.unwrap();
        assert_eq!(methods, vec!["GET /info RTSP/1.0", "POST /info RTSP/1.0", "GET /info RTSP/1.0"]);
    }
}

// ---------------------------------------------------------------- /info

fn credential_tag<'a>(receiver_id: Option<&'a str>, fallback: &'a str) -> &'a str {
    receiver_id.filter(|id| id.starts_with("bt_") && id.len() == 15 && id[3..].bytes().all(|b| b.is_ascii_hexdigit()))
        .unwrap_or(fallback)
}

async fn fetch_info(channel: &Arc<RtspChannel>) -> Result<Value, String> {
    let body = bplist::encode(&Value::dict(vec![]));
    // /info 的方法在各代固件上不一样：
    //  - AirPlay 1 / WinPlay：`GET /info`（空 bplist 体 + plist Content-Type）
    //  - AirPlay 2（真车 BMW MGU-55 实测）：只认 **POST /info**，对 GET 直接回
    //    `455 Method Not Valid In This State`（2026-10-24 真车日志）
    // 所以按 GET → POST → GET（带 AirPlay 2 头）依次试，并把每次的原始应答记下来。
    let attempts: [(&str, &[(&str, String)]); 3] = [
        ("GET", &[]),
        ("POST", &[]),
        (
            "GET",
            &[
                ("X-Apple-ProtocolVersion", "1".to_string()),
                ("X-Apple-Device-ID", "0x0".to_string()),
                ("X-Apple-Session-ID", "1".to_string()),
            ],
        ),
    ];
    let mut failures = Vec::new();
    for (method, extra) in attempts {
        let resp = channel
            .request(method, "/info", &body, "application/x-apple-binary-plist", extra, Duration::from_secs(10))
            .await?;
        let status = resp.status();
        if status != 200 {
            // 把车机原话完整记下来：状态行 + 头部（Server/X-Apple-* 头能直接看出它在跑什么）
            let mut head: Vec<String> = resp.headers.iter().map(|(k, v)| format!("{k}={v}")).collect();
            head.sort();
            let noted = format!(
                "{method} /info → [{}] 头部[{}]",
                resp.first_line,
                if head.is_empty() { "无".to_string() } else { head.join(", ") }
            );
            emit_log(&format!("AIRPLAY: ✗ {noted}"));
            failures.push(noted);
            continue;
        }
        if resp.body.is_empty() {
            return Ok(Value::dict(vec![]));
        }
        return bplist::parse(&resp.body).map_err(|e| format!("/info 解析失败: {e}"));
    }
    // /info 只做能力读取；禁止用空 pair-setup/pair-verify 请求探测状态。
    // 配对事务有状态且会注册身份，诊断不能污染当前连接。
    Err(format!("/info 三次尝试都被拒：{}", failures.join("；")))
}

// ---------------------------------------------------------------- SETUP 报文体

/// SETUP 能力清单（接收端按「车机支持 ∩ 提议」计算 enabledFeatures；
/// 只提议 videoPlayback 会让视频管线整个不启用——奥迪 400 教训）。
const CONTROLLER_FEATURES: &[&str] = &[
    "uiContext",
    "viewAreas",
    "focusTransfer",
    "h.264Level5.1",
    "enhancedSiri",
    "hevc",
    "iAPChannel",
    "videoPlayback",
];

fn session_features(mbux: bool, id9: bool, media_compat: bool) -> Vec<Value> {
    if mbux { return vec![Value::str("viewAreas")]; }
    // mainBuffered is added separately when the receiver declaration allows buffered media.
    CONTROLLER_FEATURES.iter().filter(|f| !((id9 || media_compat) && **f == "hevc"))
        .map(|f| Value::str(f)).collect()
}

// Codec preference is capability-gated across every profile. Keep all other profile
// compatibility switches independent. UDP Main/MainHigh are not MainBuffered.
fn video_session_features(mbux:bool,id9:bool,compat:bool,_bmw:bool,prefer_hevc:bool)->Vec<Value> {
    let mut features:Vec<_>=session_features(mbux,id9,compat).into_iter().filter(|feature| {
        let name=feature.as_str().unwrap_or("");
        name!="hevc" || prefer_hevc
    }).collect();
    if prefer_hevc && !features.iter().any(|f|f.as_str()==Some("hevc")) {features.push(Value::str("hevc"));}
    features
}
fn negotiated_hevc(proposed:bool,setup:&Value)->bool {
    proposed && setup.get("enabledFeatures").and_then(Value::as_array)
        .map(|features|features.iter().any(|f|f.as_str()==Some("hevc"))).unwrap_or(false)
}

#[test]
fn every_profile_proposes_hevc_only_when_effective_preference_allows_it() {
    for mbux in [false,true] {for id9 in [false,true] {for compat in [false,true] {for bmw in [false,true] {
        let off=video_session_features(mbux,id9,compat,bmw,false);
        let on=video_session_features(mbux,id9,compat,bmw,true);
        assert!(!off.iter().any(|f|f.as_str()==Some("hevc")));
        assert_eq!(on.iter().filter(|f|f.as_str()==Some("hevc")).count(),1,"profile={mbux}/{id9}/{compat}/{bmw}");
        for features in [&off, &on] {
            assert!(!features.iter().any(|feature| feature.as_str() == Some("mainBuffered")));
        }
        assert_eq!(on.into_iter().filter(|f|f.as_str()!=Some("hevc")).collect::<Vec<_>>(),off);
    }}}}
}

#[test]
fn bmw_capabilities_match_implemented_paths_and_hevc_is_opt_in() {
    let features = video_session_features(false, false, false, true, false);
    for absent in ["hevc", "mainBuffered", "sessionManagement", "logTransfer", "vehicleStateProtocol", "altScreen"] {
        assert!(!features.iter().any(|f| f.as_str() == Some(absent)));
    }
    for present in ["uiContext", "viewAreas", "focusTransfer", "iAPChannel"] {
        assert!(features.iter().any(|f| f.as_str() == Some(present)));
    }
    assert!(video_session_features(false, false, false, true, true).iter().any(|f| f.as_str()==Some("hevc")));
    assert!(video_session_features(false, true, false, true, true).iter().any(|f| f.as_str()==Some("hevc")));
    let accepted=Value::dict(vec![("enabledFeatures",Value::array(vec![Value::str("hevc")]))]);
    assert!(negotiated_hevc(true,&accepted));
    assert!(!negotiated_hevc(false,&accepted));
    assert!(!negotiated_hevc(true,&Value::dict(vec![])));
}

#[test]
fn every_profile_omits_unimplemented_controller_features() {
    for mbux in [false,true] { for id9 in [false,true] { for compat in [false,true] { for bmw in [false,true] {
        for proposed in [session_features(mbux,id9,compat), video_session_features(mbux,id9,compat,bmw,true)] {
            for absent in ["sessionManagement", "logTransfer", "vehicleStateProtocol"] {
                assert!(!proposed.iter().any(|f| f.as_str() == Some(absent)), "unsupported {absent} profile={mbux}/{id9}/{compat}/{bmw}");
            }
            assert!(proposed.iter().any(|f| f.as_str() == Some("viewAreas")));
            if !mbux { for present in ["uiContext", "focusTransfer", "iAPChannel", "videoPlayback"] {
                assert!(proposed.iter().any(|f| f.as_str() == Some(present)));
            }}
        }
    }}}}
}

fn build_session_setup(device_id: &str, device_name: &str, timing_port: u16, encrypted: bool, mbux: bool, id9: bool, media_compat: bool, request_receiver_stats: bool) -> Vec<u8> {
    let session_uuid = box_session_uuid();
    let mut fields: Vec<(&str, Value)> = vec![
        ("deviceID", Value::str(device_id)),
        // This is the resolved phone identity (real or existing stable fallback), not an AP BSSID.
        ("macAddress", Value::str(device_id)),
        ("name", Value::str(device_name)),
        ("model", Value::str("iPhone16,1")),
        ("osName", Value::str("iPhone OS")),
        ("osBuildVersion", Value::str("21F90")),
        ("osVersion", Value::str("17.5.1")),
        ("sourceVersion", Value::str("770.8.1")),
        ("timingPort", Value::int(timing_port as i64)),
        ("sessionUUID", Value::str(&session_uuid)),
        ("sessionCorrelationUUID", Value::str(&random_uuid_upper())),
        ("statsCollectionEnabled", Value::bool(request_receiver_stats)),
        ("updateSessionRequest", Value::bool(false)),
        ("keepAliveLowPower", Value::bool(true)),
        (
            "features",
            Value::array(session_features(mbux, id9, media_compat)),
        ),
    ];
    if !encrypted {
        // AirPlayEncryptionType::NONE = 1<<0（未配对会话必须显式声明，否则被当
        // 「声明加密但无密钥」拒收）。
        fields.push(("et", Value::int(1)));
    }
    bplist::encode(&Value::dict(fields))
}

pub(crate) fn supports_realtime_media_rebuild(p: &AirplayParams, info: &Value, desktop: bool) -> bool {
    // The shared MBUX policy uses a single RECORD, even without a model in /info.
    let mercedes = p.head_unit_profile == "mercedes_mbux"
        || info.get("manufacturer").and_then(Value::as_str)
            .is_some_and(|name| name.to_ascii_lowercase().contains("mercedes"))
        || info.get("model").and_then(Value::as_str).filter(|model| !model.is_empty())
            .or(p.receiver_model.as_deref())
            .is_some_and(|model| model.to_ascii_uppercase().starts_with("MB_"));
    !desktop && !mercedes
}

fn use_box_bootstrap(requested: bool, info: &Value) -> bool {
    requested && !["name", "model", "manufacturer"].iter().any(|key| {
        let value = info.get(key).and_then(Value::as_str).unwrap_or("").to_ascii_lowercase();
        value.contains("winplay") || value.contains("diplay")
    })
}

async fn activate_session(channel: &Arc<RtspChannel>, declaration: &[u8], pipeline: &Arc<Mutex<crate::pipeline::Pipeline>>) -> Result<(), String> {
    emit_log("AIRPLAY: 开始 RECORD（屏幕流建立前）");
    record_session(channel).await?;
    crate::pipeline::activation(pipeline, "RECORD");
    emit_log("AIRPLAY: ✓ RECORD 完成；声明资源后建立屏幕流");
    let reply = channel.request("POST", "/command", declaration, "application/x-apple-binary-plist", &[], Duration::from_secs(5)).await?;
    crate::resource_trace::validate_ack("RESOURCES", &reply)?;
    crate::pipeline::activation(pipeline, "RESOURCES_ACCEPTED");
    emit_log("MODES: initial resource declaration acknowledged; ownership and receiver UI remain separate");
    Ok(())
}

pub(crate) async fn record_session(channel: &Arc<RtspChannel>) -> Result<(), String> {
    // CatPlay AirPlayRtspClient::record 使用无请求体的 RECORD /。
    let reply = channel.request(
        "RECORD", "/", &[], "application/x-apple-binary-plist", &[], Duration::from_secs(5),
    ).await?;
    crate::resource_trace::validate_ack("RECORD", &reply)?;
    Ok(())
}

fn valid_station_mac(value: &str) -> Option<String> {
    let parts=value.split(':').collect::<Vec<_>>();
    if parts.len()!=6 || parts.iter().any(|part|part.len()!=2 || !part.bytes().all(|b|b.is_ascii_hexdigit())) { return None; }
    let first=u8::from_str_radix(parts[0],16).ok()?;
    let normalized=value.to_ascii_lowercase();
    if first&1!=0 || normalized=="00:00:00:00:00:00" || normalized=="02:00:00:00:00:00" { return None; }
    Some(normalized)
}

#[derive(Debug)]
struct SessionInfo {
    value: Value,
    fresh: bool,
    setup_accepted_at: std::time::Instant,
}

async fn setup_and_read_info(
    channel: &Arc<RtspChannel>, device_id: &str, device_name: &str, timing_port: u16, encrypted: bool, previous_info: Value, mbux: bool, id9: bool, media_compat: bool, bmw: bool, prefer_hevc: bool, propose_buffered: bool, cleanup: Option<&mut crate::negotiation_cleanup::NegotiationCleanup>, network_mac: Option<&str>, cluster: Option<crate::cluster_screen::Offer>, request_receiver_stats: bool,
) -> Result<(Value, SessionInfo, bool, bool), String> {
    emit_log(&format!("AIRPLAY: session device name={device_name} statsCollectionEnabled={request_receiver_stats}"));
    let mut buffered_offered = propose_buffered;
    let mut cluster_offered = cluster.is_some();
    let mut request = bplist::parse(&build_session_setup(device_id, device_name, timing_port, encrypted, mbux, id9, media_compat, request_receiver_stats)).map_err(|e|e.to_string())?;
    if let Value::Dict(fields) = &mut request {
        if bmw && !id9 && !mbux && use_box_bootstrap(true, &previous_info) {
            if let Some(mac) = network_mac.and_then(valid_station_mac) {
                if let Some((_,value)) = fields.iter_mut().find(|(key,_)|key.as_str()==Some("macAddress")) {
                    *value = Value::str(&mac);
                }
                emit_log("SESSION-IDENTITY: SETUP macAddress uses verified Wi-Fi station; deviceID unchanged");
            }
        }
        if let Some((_,features))=fields.iter_mut().find(|(key,_)|key.as_str()==Some("features")) {
            let mut offer = video_session_features(mbux,id9,media_compat,bmw,prefer_hevc);
            if cluster_offered {
                offer.push(Value::str("altScreen"));
            }
            if buffered_offered {
                offer.push(Value::str("mainBuffered"));
            }
            *features=Value::array(offer);
        }
        if cluster_offered {
            fields.push((Value::str("altScreenURLs"), Value::array(vec![Value::str(crate::cluster_screen::MAP_URL)])));
        }
    }
    let body=bplist::encode(&request);
    if media_compat {
        emit_log(&format!("MBUX-MEDIA: hevc_proposed={prefer_hevc} mainBuffered_proposed={buffered_offered}"));
    }
    if id9 {
        if let Some(sid) = request.get("sessionCorrelationUUID").and_then(Value::as_str) {
            channel.id9_trace.get_or_init(|| format!("ID9 sid={sid}"));
        }
        let features = request.get("features").and_then(Value::as_array).map(Vec::as_slice).unwrap_or_default();
        emit_log(&format!("ID9: session SETUP identity_consistent=true features={} hevc_proposed={prefer_hevc} mainBuffered_proposed={} timing_port_valid={}",
            features.len(), features.iter().any(|f|f.as_str()==Some("mainBuffered")), timing_port != 0));
    }
    let mut hevc_proposed=prefer_hevc;
    let mut reply = channel.request(
        "SETUP", "/setup", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(10),
    ).await?;
    // An explicit rejection has not created a session. Withdraw each offered feature
    // at most once, keeping the same URI and identity. Never replay an uncertain SETUP.
    for (name, offered) in [("altScreen", &mut cluster_offered), ("mainBuffered", &mut buffered_offered), ("hevc", &mut hevc_proposed)] {
        if !*offered || !matches!(reply.status(),400|406|415|451|456|461) { continue; }
        emit_log(&format!("AIRPLAY: session SETUP rejected status={}; retry without {name}",reply.status()));
        if let Value::Dict(fields)=&mut request {
            if name == "altScreen" {
                fields.retain(|(key, _)| key.as_str() != Some("altScreenURLs"));
            }
            if let Some((_,Value::Array(offer)))=fields.iter_mut().find(|(key,_)|key.as_str()==Some("features")) {
                offer.retain(|f| f.as_str() != Some(name));
            }
        }
        *offered=false;
        reply=channel.request("SETUP","/setup",&bplist::encode(&request),"application/x-apple-binary-plist",&[],Duration::from_secs(10)).await?;
    }
    if reply.status() != 200 {
        return Err(format!("会话 SETUP 被拒 [{}]（deviceID={device_id}；接收端若启用设备过滤请选择该地址）", reply.first_line));
    }
    if let Some(cleanup)=cleanup {cleanup.accepted();}
    let setup = bplist::parse(&reply.body).map_err(|e| format!("SETUP 应答解析失败: {e}"))?;
    // SETUP 前的 455 只说明阶段未就绪，不能据此认定车机没有显示能力。
    emit_log("AIRPLAY: 会话 SETUP 已接受——读取会话内 /info");
    let (info, info_source) = match fetch_info(channel).await {
        Ok(value) if value.as_dict().map(|d| !d.is_empty()).unwrap_or(false) => {
            if let Some(trace) = channel.id9_trace.get() { emit_log(&format!("{trace} INFO_RESULT source=session_info")); }
            emit_log("AIRPLAY: 会话内 /info 成功，采用本轮车机显示能力");
            (value, "session_info")
        }
        Ok(_) => {
            if let Some(trace) = channel.id9_trace.get() { emit_log(&format!("{trace} INFO_RESULT source=fallback_empty")); }
            emit_log("AIRPLAY: 会话内 /info 为空，保留先前能力或设置兜底");
            buffered_offered = false;
            (previous_info, "fallback_empty")
        }
        Err(error) => {
            if let Some(trace) = channel.id9_trace.get() { emit_log(&format!("{trace} INFO_RESULT source=fallback_error error={error:?}")); }
            emit_log(&format!("AIRPLAY: 会话内 /info 仍不可用（{error}），保留先前能力或设置兜底"));
            buffered_offered = false;
            (previous_info, "fallback_error")
        }
    };
    for line in media_info_latency_lines(channel.trace_id(), info_source, &info) {
        emit_log(&line);
    }
    if let Some(offer) = cluster.filter(|_| cluster_offered) {
        let deferred = matches!(offer, crate::cluster_screen::Offer::AwaitingDisplays);
        if info_source == "session_info" {
            if let Some(display) = offer.resolve(&info, &setup) {
                let _ = channel.cluster_display.set(display);
            }
        }
        emit_log(&format!("CLUSTER: altScreen offered=true awaited_displays={deferred} info_source={info_source} confirmed={}",
            channel.cluster_display.get().is_some()));
    }
    Ok((setup, SessionInfo { value: info, fresh: info_source == "session_info", setup_accepted_at: reply.received_at }, hevc_proposed, buffered_offered))
}

pub(crate) fn build_screen_setup(conn_id: i64, uuid: &str) -> Vec<u8> {
    build_screen_setup_with_latency(conn_id, uuid, 25)
}

pub(crate) fn build_screen_setup_with_latency(conn_id: i64, uuid: &str, latency_ms: i64) -> Vec<u8> {
    bplist::encode(&Value::dict(vec![(
        "streams",
        Value::array(vec![Value::dict(vec![
            ("type", Value::int(110)), // 110=主屏（100 是音频，会开 UDP 端口）
            ("streamConnectionID", Value::int(conn_id)),
            ("uuid", Value::str(uuid)),
            ("latencyMs", Value::int(latency_ms)),
        ])]),
    )]))
}

/// WinPlay treats zero latency as 1000 ms; 352-frame PCM then approaches its 128-packet limit.
/// At 48 kHz, 300 ms corresponds to about 41 PCM packets rather than 136.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
// Main and MainHigh use UDP. MainBuffered uses length-prefixed TCP.
pub(crate) enum AudioCodec { Pcm, Pcm441, Aac, Aac441, BufferedAac }
impl AudioCodec {
    pub(crate) fn stream_type(self) -> i64 { match self { Self::Pcm | Self::Pcm441 => 100, Self::BufferedAac => 103, Self::Aac | Self::Aac441 => 102 } }
    fn format(self) -> i64 { match self {Self::Pcm=>1<<15,Self::Pcm441=>1<<11,Self::Aac | Self::BufferedAac=>1<<23,Self::Aac441=>1<<22} }
    fn rate(self) -> u32 { if matches!(self, Self::Aac441 | Self::Pcm441) {44100} else {48000} }
    fn spf(self) -> i64 { if matches!(self, Self::Pcm | Self::Pcm441) {352} else {1024} }
    fn compression_type(self) -> i64 {
        match self {
            Self::Pcm | Self::Pcm441 => 1,
            Self::Aac | Self::Aac441 | Self::BufferedAac => 4,
        }
    }
}
pub(crate) fn declares_media(info: &Value, codec: AudioCodec) -> bool {
    info.get("audioFormats").and_then(Value::as_array).into_iter().flatten().any(|f|
        f.get("type").and_then(Value::as_int)==Some(codec.stream_type()) &&
        f.get("audioType").and_then(Value::as_str)==Some("media") &&
        f.get("audioOutputFormats").and_then(Value::as_int).is_some_and(|bits| bits >= 0 && bits & codec.format()!=0)
        && (codec != AudioCodec::BufferedAac || uses_classic_buffered_packets(f)))
}

pub(crate) fn effective_desktop(p: &AirplayParams, info: &Value) -> bool {
    p.desktop_receiver || !use_box_bootstrap(true, info)
}

impl AirplayParams {
    fn allows_buffered_media(&self) -> bool {
        self.enable_audio && self.audio_codec != "pcm"
    }

    /// The pre-SETUP /info may omit type 103 even if it lists other formats.
    /// Propose here, but select type 103 only from fresh session capabilities.
    fn proposes_buffered_media(&self, encrypted: bool, info: &Value) -> bool {
        self.allows_buffered_media() && encrypted
            && (!self.nbt_audio_first || effective_desktop(self, info))
    }
}

#[cfg(test)]
fn buffered_profile_params_for_test() -> AirplayParams {
    AirplayParams {
        car_ip:"127.0.0.1".into(), airplay_port:7000, device_id:String::new(), device_name:String::new(),
        wifi_mac_address:None, identity_path:String::new(), receiver_id:None, receiver_model:None, receiver_previously_connected:true,
        enable_audio:true, audio_codec:"auto".into(), video_codec:"h264".into(), bmw_capabilities:true,
        microphone_probe:None, keep_alive_stats:false, request_receiver_stats:false, bmw_box_bootstrap:true, skip_bluetooth_handoff:false, head_unit_profile:"bmw_id9".into(),
        mbux_screen_bootstrap:false, id9_screen_bootstrap:true, nbt_audio_first:false, mbux_media_compat:false,
        desktop_receiver:false, width:1920, height:720, touch_display:None, touch_rect:None,
        encoder_size:Arc::new(Mutex::new((1920,720))), touch_size:Arc::new(Mutex::new((1920,720))),
        carwith_display:None, carwith_media_rate:None, carwith_stretch:false, screen_resource_lifecycle:true,
        pipeline:Arc::new(Mutex::new(crate::pipeline::Pipeline::new())),
    }
}

fn uses_classic_buffered_packets(value: &Value) -> bool {
    value.get("packetFormatAPAP").is_none_or(|v| v.as_bool() == Some(false))
}

fn prioritize_buffered_media(info: &Value, session: &Value, candidates: &mut Vec<AudioCodec>) {
    // /info declares the actual stream type, direction and format; the session's
    // feature summary is logged separately. A scoped SETUP determines acceptance.
    if !uses_classic_buffered_packets(info) || !uses_classic_buffered_packets(session)
        || !declares_media(info, AudioCodec::BufferedAac) {
        return;
    }
    prioritize_realtime_aac(candidates);
    candidates.insert(0, AudioCodec::BufferedAac);
}

fn prioritize_realtime_aac(candidates: &mut [AudioCodec]) {
    if let Some(index) = candidates.iter().position(|codec| *codec == AudioCodec::Aac) {
        candidates[..=index].rotate_right(1);
    }
}

pub(crate) async fn buffered_fallback_candidates(p: &AirplayParams, info: &Value) -> Vec<AudioCodec> {
    let strict = p.id9_screen_bootstrap || (p.mbux_media_compat && !p.desktop_receiver);
    let mut candidates = media_setup_candidates(info, &p.audio_codec, false, strict, p.carwith_media_rate.as_ref()).await;
    candidates.retain(|codec| declares_media(info, *codec));
    // Before general 44.1 selection, strict/no-source recovery appended AAC44.1
    // then PCM44.1 after its 48 kHz candidates. Preserve that recovery order.
    if strict && p.carwith_media_rate.is_none() {
        candidates.sort_by_key(|codec| match codec { AudioCodec::Aac441 => 1, AudioCodec::Pcm441 => 2, _ => 0 });
    }
    // A receiver can declare only a 44.1 kHz fallback, even without a CarWith source.
    for codec in [AudioCodec::Aac441, AudioCodec::Pcm441] {
        if (p.audio_codec != "pcm" || codec.stream_type() == 100) && declares_media(info, codec)
            && !candidates.contains(&codec) {
            candidates.push(codec);
        }
    }
    prioritize_realtime_aac(&mut candidates);
    candidates
}

// Check the source at the point of SETUP, including a late INIT during screen negotiation.
// A silent CarWith source must still allow navigation and session activation to proceed.
async fn media_setup_candidates(info: &Value, preference: &str, nbt: bool, strict: bool,
    source: Option<&tokio::sync::watch::Receiver<Option<u32>>>) -> Vec<AudioCodec> {
    let defaults = if nbt { nbt_audio_candidates(info, preference) }
        else { audio_candidates(info, preference, strict) };
    let Some(source) = source else { return defaults };
    let mut declared: Vec<_> = if nbt || preference == "aac" {
        vec![AudioCodec::Aac, AudioCodec::Aac441, AudioCodec::Pcm, AudioCodec::Pcm441]
    } else {
        vec![AudioCodec::Pcm, AudioCodec::Pcm441, AudioCodec::Aac, AudioCodec::Aac441]
    };
    declared.retain(|codec| (preference != "pcm" || codec.stream_type() == 100) && declares_media(info,*codec));
    let mut source = source.clone();
    let mut rate = *source.borrow_and_update();
    if rate.is_none() && declared.iter().any(|c| c.rate()==44100) && declared.iter().any(|c| c.rate()==48000) {
        if let Ok(Ok(value)) = tokio::time::timeout(Duration::from_millis(350),source.wait_for(Option::is_some)).await {
            rate = *value;
        }
    }
    let mut plan = Vec::new();
    for codec in declared.iter().copied().filter(|c| Some(c.rate())==rate)
        .chain(defaults.iter().copied().filter(|c| declared.contains(c)))
        .chain(declared.iter().copied()).chain(defaults.iter().copied()) {
        if !plan.contains(&codec) { plan.push(codec); }
    }
    emit_log(&format!("AUDIO: CarWith MEDIA_INIT={rate:?}Hz media SETUP candidates={plan:?}"));
    plan
}
fn nbt_audio_candidates(info: &Value, preference: &str) -> Vec<AudioCodec> {
    let candidates = if preference=="pcm" { vec![AudioCodec::Pcm,AudioCodec::Pcm441] }
        else {vec![AudioCodec::Aac441,AudioCodec::Aac,AudioCodec::Pcm,AudioCodec::Pcm441]};
    candidates.into_iter().filter(|codec|declares_media(info,*codec)).collect()
}

#[cfg(test)]
mod capability_audio_selection_tests {
    use super::*;
    use crate::aux_setup::Kind;
    fn format(kind: i64, role: &str, direction: &str, bits: i64) -> Value {
        Value::dict(vec![("type",Value::int(kind)),("audioType",Value::str(role)),(direction,Value::int(bits))])
    }
    fn info(formats: Vec<Value>) -> Value { Value::dict(vec![("audioFormats",Value::array(formats))]) }

    #[test]
    fn speech_requires_declared_output_for_every_profile() {
        let output = info(vec![format(100,"speechRecognition","audioOutputFormats",1<<29)]);
        let mut p = buffered_profile_params_for_test();
        for profile in ["bmw_id85","bmw_id9","bmw_id6","mercedes_mbux","generic","desktop","remote"] {
            p.head_unit_profile=profile.into();
            p.bmw_capabilities=profile.starts_with("bmw_");
            p.id9_screen_bootstrap=profile=="bmw_id9";
            p.nbt_audio_first=profile=="bmw_id6";
            p.desktop_receiver=profile=="desktop";
            assert_eq!(independent_audio_kinds(&p,&output), &[Kind::Speech], "{profile}");
            for wrong in [info(vec![]), info(vec![format(100,"speechRecognition","audioInputFormats",1<<29)]),
                info(vec![format(100,"media","audioOutputFormats",1<<29)]),
                info(vec![format(101,"speechRecognition","audioOutputFormats",1<<29)]),
                info(vec![format(100,"speechRecognition","audioOutputFormats",1<<28)]),
                info(vec![format(100,"speechRecognition","audioOutputFormats",-1)])] {
                assert!(independent_audio_kinds(&p,&wrong).is_empty(), "{profile}");
            }
        }
    }

    #[test]
    fn declared_44100_formats_survive_strict_nbt_and_user_preferences() {
        use AudioCodec::*;
        let only441=info(vec![format(100,"media","audioOutputFormats",1<<11),format(102,"media","audioOutputFormats",1<<22)]);
        for strict in [false,true] {
            let mut auto=vec![Pcm441,Aac441];
            let mut pcm=vec![Pcm441];
            let mut aac=vec![Aac441,Pcm441];
            if !strict {auto.push(Pcm);pcm.push(Pcm);aac.push(Pcm);}
            assert_eq!(audio_candidates(&only441,"auto",strict), auto);
            assert_eq!(audio_candidates(&only441,"pcm",strict), pcm);
            assert_eq!(audio_candidates(&only441,"aac",strict), aac);
        }
        assert_eq!(nbt_audio_candidates(&only441,"auto"), [Aac441,Pcm441]);
        assert_eq!(nbt_audio_candidates(&only441,"pcm"), [Pcm441]);
        let all=info(vec![format(100,"media","audioOutputFormats",(1<<11)|(1<<15)),format(102,"media","audioOutputFormats",(1<<22)|(1<<23))]);
        for strict in [false,true] {
            assert_eq!(audio_candidates(&all,"auto",strict), [Pcm,Aac,Pcm441,Aac441]);
            assert_eq!(audio_candidates(&all,"aac",strict), [Aac,Aac441,Pcm,Pcm441]);
            assert_eq!(audio_candidates(&all,"pcm",strict), [Pcm,Pcm441]);
        }
        assert_eq!(nbt_audio_candidates(&all,"auto"), [Aac441,Aac,Pcm,Pcm441]);
        let invalid=info(vec![format(100,"media","audioInputFormats",1<<11),format(102,"default","audioOutputFormats",1<<22)]);
        assert!(audio_candidates(&invalid,"auto",true).is_empty());
        assert!(nbt_audio_candidates(&invalid,"auto").is_empty());
    }

    #[tokio::test]
    async fn buffered_recovery_retains_existing_order_and_carwith_rate_ranking() {
        use AudioCodec::*;
        let formats=info(vec![format(100,"media","audioOutputFormats",(1<<11)|(1<<15)),
            format(102,"media","audioOutputFormats",(1<<22)|(1<<23))]);
        let mut p=buffered_profile_params_for_test();
        for preference in ["auto","aac"] {
            p.audio_codec=preference.into();
            assert_eq!(buffered_fallback_candidates(&p,&formats).await, [Aac,Pcm,Aac441,Pcm441]);
        }
        p.audio_codec="pcm".into();
        assert_eq!(buffered_fallback_candidates(&p,&formats).await, [Pcm,Pcm441]);
        p.audio_codec="auto".into();
        let only441=info(vec![format(100,"media","audioOutputFormats",1<<11),format(102,"media","audioOutputFormats",1<<22)]);
        assert_eq!(buffered_fallback_candidates(&p,&only441).await, [Aac441,Pcm441]);
        let (_tx,rx)=tokio::sync::watch::channel(Some(44100));
        p.carwith_media_rate=Some(rx);
        assert_eq!(buffered_fallback_candidates(&p,&only441).await, [Pcm441,Aac441]);
    }
}
fn audio_candidates(info: &Value, preference: &str, strict: bool) -> Vec<AudioCodec> {
    use AudioCodec::*;
    let ordered: &[_] = match preference {
        "pcm" => &[Pcm, Pcm441],
        "aac" => &[Aac, Aac441, Pcm, Pcm441],
        _ => &[Pcm, Aac, Pcm441, Aac441],
    };
    let mut candidates: Vec<_> = ordered.iter().copied().filter(|&codec| declares_media(info, codec)).collect();
    // Some older receivers accept PCM48 SETUP even without a matching /info row.
    if !strict && !candidates.contains(&Pcm) {
        candidates.push(Pcm);
    }
    candidates
}
fn audio_latency_entries<'a>(info: &'a Value, stream_type: i64, audio_type: &'a str) -> impl Iterator<Item = &'a Value> {
    info.get("audioLatencies").and_then(Value::as_array).into_iter().flatten()
        .filter(move |entry| {
            let kind = entry.get("type").or_else(|| entry.get("streamType"));
            entry.as_dict().is_some()
                && kind.is_none_or(|value| value.as_int() == Some(stream_type))
                && entry.get("audioType").is_none_or(|value| {
                    value.as_str().is_some_and(|role| role.eq_ignore_ascii_case(audio_type))
                })
        })
}

fn media_info_latency_lines(rtsp: u64, source: &str, info: &Value) -> [String; 3] {
    [100, 102, 103].map(|stream_type| {
        let values: Vec<_> = audio_latency_entries(info, stream_type, "media").map(|entry| {
            let micros = match entry.get("outputLatencyMicros") {
                Some(Value::Int(micros)) => micros.to_string(),
                Some(_) => "invalid".to_owned(),
                None => "missing_field".to_owned(),
            };
            let selectors: Vec<_> = ["sr", "ss", "ch", "ct"].into_iter().filter_map(|key| {
                entry.get(key).map(|value| {
                    let value = value.as_int().map_or_else(|| "invalid".to_owned(), |n| n.to_string());
                    format!("{key}={value}")
                })
            }).collect();
            if selectors.is_empty() {
                micros
            } else {
                format!("{micros}[{}]", selectors.join(" "))
            }
        }).collect();
        let raw = if values.is_empty() { "missing_entry".into() } else { values.join(",") };
        format!("AUDIO-LATENCY: RTSP#{rtsp} INFO source={source} type={stream_type} audioType=media outputLatencyMicros={raw}")
    })
}

fn media_latency_ms(info: &Value, codec: AudioCodec) -> i64 {
    // DiPlay's recommended extra headroom is for buffered TCP. Do not inflate
    // the real-time UDP queues (WinPlay PCM in particular has a packet limit).
    let floor_ms = if codec == AudioCodec::BufferedAac { 1000 } else { 300 };
    // R14G17 uses 16-bit samples for these PCM formats and for decoded AAC-LC output.
    output_audio_latency_ms(info, codec.stream_type(), "media",
        [i64::from(codec.rate()), 16, 2, codec.compression_type()], false).unwrap_or_default().max(floor_ms)
}

/// R14G17 orders the rows and treats absent selectors as wildcards.
pub(crate) fn output_audio_latency_ms(info: &Value, stream_type: i64, audio_type: &str,
    format: [i64; 4], allow_zero: bool) -> Option<i64> {
    let selectors = ["sr", "ss", "ch", "ct"].into_iter().zip(format);
    audio_latency_entries(info, stream_type, audio_type)
        .filter(|entry| selectors.clone().all(|(key, expected)| {
            entry.get(key).is_none_or(|value| value.as_int() == Some(expected))
        }))
        .find_map(|entry| entry.get("outputLatencyMicros").and_then(Value::as_int)
            .filter(|&micros| micros > 0 || (allow_zero && micros == 0)))
        .map(|micros| micros / 1000)
}

#[cfg(test)]
fn build_audio_setup_for_codec(conn_id: i64, latency_ms: i64, codec: AudioCodec) -> Vec<u8> {
    build_audio_setup_request(conn_id, latency_ms, codec, None, None, false)
}

fn bind_main_audio_control(factory: &dyn SocketProvider, host: &str, codec: AudioCodec) -> Option<UdpSocket> {
    if !matches!(codec.stream_type(), 100 | 102) { return None; }
    let socket = factory.open_udp(host, 0).filter(|socket|
        socket.local_addr().is_ok_and(|addr| addr.port() != 0));
    if socket.is_none() {
        emit_log(&format!("AUDIO-RTCP: type={} local bind failed; using existing SETUP", codec.stream_type()));
    }
    socket
}

fn build_audio_setup_request(conn_id: i64, latency_ms: i64, codec: AudioCodec, shk: Option<&[u8; 32]>, control_port: Option<u16>, high_accuracy: bool) -> Vec<u8> {
    let mut stream = vec![
        ("type", Value::int(codec.stream_type())),
        ("streamConnectionID", Value::int(conn_id)),
        ("audioFormat", Value::int(codec.format())),
        ("audioType", Value::str("media")),
        ("audioLatencyMs", Value::int(latency_ms)),
        ("input", Value::bool(false)),
        ("supportsRTPPacketRedundancy", Value::bool(false)),
        ("spf", Value::int(codec.spf())),
    ];
    // Leave the validated PCM proposal unchanged. AAC explicitly describes compression/rate.
    if matches!(codec.stream_type(), 102 | 103) {
        stream.extend([("ct", Value::int(codec.compression_type())), ("sr", Value::int(codec.rate().into()))]);
    }
    if let Some(port) = control_port {
        stream.push(("controlPort", Value::int(i64::from(port))));
        if high_accuracy { stream.push(("supportsHighAccuracyTimestamps", Value::bool(true))); }
    }
    // DiPlay decrypts type 103 with this key directly and drops the stream when it is absent.
    if let Some(key) = shk { stream.push(("shk", Value::Data(key.to_vec()))); }
    bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(stream)]))]))
}

/// Type 103 has a TCP data port. Its optional control port is separate from the RTSP anchor.
fn buffered_endpoints(stream: &Value, connection_id: i64) -> Result<(u16, u32), String> {
    for (field, expected) in [("status", 0), ("streamConnectionID", connection_id),
        ("audioFormat", 1 << 23), ("sr", 48000), ("spf", 1024), ("ct", 4)] {
        if stream.get(field).is_some_and(|v| v.as_int() != Some(expected)) {
            return Err(format!("unexpected {field}"));
        }
    }
    if stream.get("audioType").is_some_and(|v| v.as_str() != Some("media"))
        || stream.get("input").is_some_and(|v| v.as_bool() != Some(false))
        || !uses_classic_buffered_packets(stream) {
        return Err("unsupported output direction, media type or packet format".into());
    }
    let valid_port = |v: &Value| v.as_int().and_then(|n| u16::try_from(n).ok()).filter(|n| *n != 0);
    let data = stream.get("dataPort").and_then(valid_port).ok_or("invalid TCP dataPort")?;
    if stream.get("controlPort").is_some_and(|v| valid_port(v).is_none()) {
        return Err("invalid controlPort".into());
    }
    // Two-byte length, 12-byte header, tag, nonce and a nonempty AAC access unit.
    let buffer = stream.get("audioBufferSize").and_then(Value::as_int)
        .and_then(|n| u32::try_from(n).ok()).filter(|n| *n > 38).ok_or("invalid audioBufferSize")?;
    Ok((data, buffer))
}

pub(crate) async fn retire_buffered_audio(channel: &RtspChannel) -> Result<(), String> {
    use std::sync::atomic::Ordering;
    let _ordered = channel.buffered_commands.lock().await;
    channel.buffered_cleanup_pending.store(true, Ordering::Release);
    let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![
        Value::dict(vec![("type", Value::int(103))])
    ]))]));
    let reply = channel.request("TEARDOWN", "/", &body, "application/x-apple-binary-plist",
        &[], Duration::from_secs(2)).await?;
    crate::resource_trace::validate_ack("BUFFERED_AUDIO_TEARDOWN", &reply)?;
    channel.udp_media.set(103, false);
    channel.buffered_cleanup_pending.store(false, Ordering::Release);
    Ok(())
}

async fn negotiate_buffered_audio(channel: &Arc<RtspChannel>, factory: &Arc<dyn SocketProvider>,
    shared: &[u8;32], p: &AirplayParams, desktop: bool, info: &Value) -> Option<AudioOut> {
    use std::sync::atomic::Ordering;
    use std::time::Instant;
    let allow_id9_setrate = p.head_unit_profile == "bmw_id9";
    let uri = crate::rtsp_uri::audio_uri(&p.car_ip, p.airplay_port);
    let anchor_uri = if allow_id9_setrate { channel.absolute_session_uri().map(str::to_owned) } else { None };
    let codec = AudioCodec::BufferedAac;
    let encoder = match crate::aac::AacEncoder::at_rate(codec.rate()) {
        Ok(encoder) => encoder,
        Err(error) => { emit_log(&format!("BUFFERED-AUDIO: type103 encoder unavailable: {error}")); return None; }
    };
    let id = random_positive_i64();
    let latency_ms = media_latency_ms(info, codec);
    let key = stream_key(shared, id as u64);
    let request = build_audio_setup_request(id, latency_ms, codec, Some(&key), None, false);
    emit_log(&format!("BUFFERED-AUDIO: type103 SETUP latencyMs={latency_ms} shk_bytes={}", key.len()));
    let started = Instant::now();
    channel.buffered_cleanup_pending.store(true, Ordering::Release);
    let accepted = channel.request("SETUP", &uri, &request, "application/x-apple-binary-plist",
        &[], Duration::from_secs(8)).await;
    let setup_ms = started.elapsed().as_millis();
    let result: Result<AudioOut, String> = async {
        let reply = accepted?;
        if reply.status() != 200 {
            channel.buffered_cleanup_pending.store(false, Ordering::Release);
            return Err(format!("SETUP rejected RTSP={}", reply.status()));
        }
        crate::resource_trace::validate_ack("BUFFERED_AUDIO_SETUP", &reply)?;
        let root = bplist::parse(&reply.body).map_err(|e| format!("invalid SETUP response: {e:?}"))?;
        if !uses_classic_buffered_packets(&root) { return Err("unsupported SETUP packetFormatAPAP".into()); }
        let stream = crate::screen_session::setup_stream::select(&root, 103, false)?;
        let (data_port, buffer_bytes) = buffered_endpoints(stream, id)?;
        let factory = factory.clone();
        let host = p.car_ip.clone();
        let connect_started = Instant::now();
        let connect = tokio::task::spawn_blocking(move || factory.open_tcp(&host, data_port, 2500));
        let data = tokio::time::timeout(Duration::from_secs(3), connect).await
            .map_err(|_| "TCP connect deadline")?.map_err(|e| e.to_string())?
            .ok_or("network-bound TCP connection failed")?;
        let connect_ms = connect_started.elapsed().as_millis();
        let control = crate::buffered_control::BufferedControl::new(channel.clone(), uri, anchor_uri, codec.rate(), codec.spf() as u32);
        control.probe_anchor_for_profile(allow_id9_setrate, desktop).await.map_err(|e| format!("GETANCHOR preflight: {e}"))?;
        let control_mode = control.control_mode();
        let mut out = AudioOut::new_buffered_aac(data, key, encoder, control, buffer_bytes);
        out.offload_aac(&channel.buffered_ownership).map_err(|error| format!("AAC worker: {error}"))?;
        out.observe_main_stream(channel.trace_id(), id, 0, reply.received_at, &channel.buffered_ownership, None);
        emit_log(&format!("BUFFERED-AUDIO: type103 SETUP accepted rtsp_ms={setup_ms} tcp_port={data_port} tcp_ms={connect_ms} audioBufferSize={buffer_bytes} control_mode={control_mode} elapsed_ms={} playback_unconfirmed",
            started.elapsed().as_millis()));
        Ok(out)
    }.await;
    match result {
        Ok(out) => {
            channel.udp_media.set(103, true);
            channel.buffered_cleanup_pending.store(false, Ordering::Release);
            Some(out)
        }
        Err(error) => {
            emit_log(&format!("BUFFERED-AUDIO: type103 unusable reason={error} rtsp_ms={setup_ms} elapsed_ms={}", started.elapsed().as_millis()));
            if channel.buffered_cleanup_pending.load(Ordering::Acquire) {
                let cleanup = retire_buffered_audio(channel).await;
                emit_log(&format!("BUFFERED-AUDIO: type103 partial SETUP cleanup={cleanup:?}"));
            }
            None
        }
    }
}

pub(crate) async fn negotiate_audio(
    channel: &Arc<RtspChannel>,
    factory: &Arc<dyn SocketProvider>,
    shared: Option<&[u8; 32]>,
    p: &AirplayParams,
    desktop: bool,
    info: &Value,
    codec: AudioCodec,
) -> Option<AudioOut> {
    if channel.buffered_cleanup_pending.load(std::sync::atomic::Ordering::Acquire) {
        if let Err(error) = retire_buffered_audio(channel).await {
            emit_log(&format!("BUFFERED-AUDIO: defer {codec:?}, type103 cleanup pending: {error}"));
            return None;
        }
    }
    if codec == AudioCodec::BufferedAac {
        if !p.allows_buffered_media() || !declares_media(info, codec) { return None; }
        return negotiate_buffered_audio(channel, factory, shared?, p, desktop, info).await;
    }
    negotiate_realtime_audio(channel, factory, shared, p, desktop, info, codec, false).await
}

pub(crate) async fn negotiate_realtime_audio(
    channel: &Arc<RtspChannel>,
    factory: &Arc<dyn SocketProvider>,
    shared: Option<&[u8; 32]>,
    p: &AirplayParams,
    desktop: bool,
    info: &Value,
    codec: AudioCodec,
    replacing: bool,
) -> Option<AudioOut> {
    let encoder = if codec.stream_type() == 102 {
        match crate::aac::AacEncoder::at_rate(codec.rate()) {
            Ok(encoder) => { emit_log(&format!("AUDIO: AAC-LC encoder ready {}Hz stereo 256kbps raw/SPF1024",codec.rate())); Some(encoder) }
            Err(error) => { emit_log(&format!("AUDIO: AAC encoder unavailable: {error}")); return None; }
        }
    } else { None };
    let conn_id = random_positive_i64();
    let latency_ms = media_latency_ms(info, codec);
    let mut local_control = bind_main_audio_control(factory.as_ref(), &p.car_ip, codec);
    let advertised_port = local_control.as_ref().and_then(|socket| socket.local_addr().ok()).map(|addr| addr.port());
    if let Some(port) = advertised_port {
        emit_log(&format!("AUDIO-RTCP: RTSP#{} type={} streamConnectionID={conn_id} SETUP advertisedControlPort={port}", channel.trace_id(), codec.stream_type()));
    }
    emit_log(&format!("AUDIO: SETUP {codec:?} {}Hz stereo SPF{} desktop={desktop} latencyMs={latency_ms}", codec.rate(), codec.spf()));
    // 音频 SETUP 必须用**绝对 URI**（cardeck runAudioStream 同款：`rtsp://host:port/`）；
    // 屏幕流与初始 SETUP 才用相对路径。URI 用反会 400/404。
    let audio_uri = crate::rtsp_uri::audio_uri(&p.car_ip, p.airplay_port);
    let mut offer_port = advertised_port;
    let mut high_accuracy = offer_port.is_some();
    let resp = loop {
        let body = build_audio_setup_request(conn_id, latency_ms, codec, None, offer_port, high_accuracy);
        match channel.request("SETUP", &audio_uri, &body, "application/x-apple-binary-plist", &[], Duration::from_secs(8)).await {
            Ok(reply) if high_accuracy && matches!(reply.status(), 400 | 451 | 461) => {
                emit_log(&format!("AUDIO-RTCP: type={} timestamp capability rejected status={}; retrying with the same local control port", codec.stream_type(), reply.status()));
                high_accuracy = false;
            }
            Ok(reply) if codec.stream_type() == 100 && offer_port.is_some() && matches!(reply.status(), 400 | 451 | 461) => {
                emit_log(&format!("AUDIO-RTCP: type=100 controlPort SETUP rejected status={}; retrying PCM without a local port", reply.status()));
                offer_port = None;
                local_control = None;
                high_accuracy = false;
            }
            Ok(reply) => break reply,
            Err(error) => {
                emit_log(&format!("AIRPLAY: 音频 SETUP 失败 - {error}"));
                return None;
            }
        }
    };
    if resp.status() != 200 {
        emit_log(&format!("AIRPLAY: 音频流被拒 [{}]", resp.first_line));
        return None;
    }
    if let Err(error) = crate::resource_trace::validate_ack("AUDIO_SETUP", &resp) {
        emit_log(&format!("AUDIO: {error}"));
        return None;
    }
    let result = async {
        let root = bplist::parse(&resp.body).ok()?;
        let stream = match crate::screen_session::setup_stream::select(&root, codec.stream_type(), desktop) {
            Ok(stream) => stream,
            Err(error) => { emit_log(&format!("AUDIO: {error}")); return None; }
        };
        if stream.get("audioFormat").is_some_and(|v| v.as_int()!=Some(codec.format()))
            || stream.get("sr").is_some_and(|v| v.as_int()!=Some(i64::from(codec.rate()))) {
            emit_log(&format!("AUDIO: receiver SETUP format/rate differs from {codec:?}; release stream and try next declared format"));
            return None;
        }
        if replacing && stream.get("streamConnectionID").is_some_and(|id| id.as_int() != Some(conn_id)) {
            emit_log("AUDIO: replacement SETUP returned a different streamConnectionID");
            return None;
        }
        let data_port = port_of(stream.get("dataPort"));
        let control_port = port_of(stream.get("controlPort"));
        if data_port == 0 {
            emit_log("AIRPLAY: 音频流未返回 dataPort");
            return None;
        }
        if !replacing { channel.udp_media.set(codec.stream_type(), true); }
        let key = match shared {
            Some(s) => stream_key(s, conn_id as u64),
            None => {
                emit_log("AIRPLAY: 未配对会话无法派生音频密钥，跳过音频");
                return None;
            }
        };
        let udp = factory.open_udp(&p.car_ip, data_port)?;
        if matches!(codec, AudioCodec::Pcm | AudioCodec::Pcm441) {
            let actual = socket2::SockRef::from(&udp).send_buffer_size()
                .map_or_else(|error| format!("NA error={error}"), |bytes| bytes.to_string());
            emit_log(&format!("AUDIO-UDP-SNDBUF: RTSP#{} type=100 streamConnectionID={conn_id} dataPort={data_port} soSndbufBytes={actual}", channel.trace_id()));
        }
        let mut listening = None;
        let sync = if let Some(socket) = local_control {
            match udp.peer_addr() {
                Ok(peer) if control_port == 0 => {
                    emit_log(&format!("AUDIO-RTCP: RTSP#{} type={} streamConnectionID={conn_id} advertisedControlPort={offer_port:?} peer returned no controlPort", channel.trace_id(), codec.stream_type()));
                    listening = Some((socket, peer.ip()));
                    None
                }
                Ok(mut peer) => {
                    // Preserve the resolved address family and any IPv6 scope from the data socket.
                    peer.set_port(control_port);
                    match socket.connect(peer).await {
                        Ok(()) => Some(socket),
                        Err(error) => {
                            emit_log(&format!("AUDIO-RTCP: RTSP#{} type={} streamConnectionID={conn_id} advertisedControlPort={offer_port:?} peerControlPort={control_port} connect failed: {error}", channel.trace_id(), codec.stream_type()));
                            listening = Some((socket, peer.ip()));
                            None
                        }
                    }
                }
                Err(error) => {
                    emit_log(&format!("AUDIO-RTCP: RTSP#{} streamConnectionID={conn_id} missing data socket peer: {error}", channel.trace_id()));
                    None
                }
            }
        } else if control_port != 0 {
            factory.open_udp(&p.car_ip, control_port)
        } else {
            None
        };
        emit_log(&format!(
            "AIRPLAY: ✓ 音频流就绪 data={data_port} control={control_port}（{codec:?} {}Hz/SPF{}）", codec.rate(), codec.spf()
        ));
        let mut out = match encoder { Some(encoder) => AudioOut::new_aac(udp, sync, key, encoder), None => AudioOut::new_pcm(udp, sync, key, codec.rate()) };
        if let Err(error) = out.offload_aac(&channel.buffered_ownership) {
            emit_log(&format!("AUDIO: AAC worker unavailable: {error}"));
            return None;
        }
        out.observe_main_stream(channel.trace_id(), conn_id, control_port, resp.received_at, &channel.buffered_ownership, listening);
        out.high_accuracy_timestamps = high_accuracy;
        if p.head_unit_profile == "bmw_id9" {
            out.observe_id9_resume(channel.audio_unborrow_generation.clone());
        }
        Some(out)
    }.await;
    if result.is_none() && !replacing {
        // SETUP 200 can allocate a stream even when ports are missing or local socket creation fails.
        // Retire just this audio type before trying another format; keep the screen/control alive.
        let body=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
            ("type",Value::int(codec.stream_type()))])]))]));
        channel.udp_media.set(codec.stream_type(), false);
        match channel.request("TEARDOWN","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(2)).await {
            Ok(reply) => emit_log(&format!("AUDIO: unusable {codec:?} SETUP cleanup status={}",reply.status())),
            Err(error) => emit_log(&format!("AUDIO: unusable {codec:?} SETUP cleanup failed: {error}")),
        }
    }
    result
}

// ---------------------------------------------------------------- 心跳

/// 三条心跳里的前两条（屏幕 keepAlive 帧在视频 pump 里按 1s 节流）：
///  - UDP keepAlive：首包立即，默认每 5s；指定实车档案仅在媒体空闲时每 10s 续发。
///  - RTSP /feedback：1s 空体 POST（车机 ~3s 看门狗）
pub struct KeepaliveTasks(Vec<tokio::task::JoinHandle<()>>, Option<crate::wifi_iap2::WifiIap2>, Option<TimingServer>);

impl Drop for KeepaliveTasks {
    fn drop(&mut self) {
        for task in &self.0 { task.abort(); }
    }
}

fn spawn_keepalive_loops(
    channel: Arc<RtspChannel>,
    factory: Arc<dyn SocketProvider>,
    keep_alive_port: u16,
    car_ip: String,
    stop: Arc<Notify>,
    wifi_iap_id: Option<u64>,
    idle_only: bool,
    pipeline: Arc<Mutex<crate::pipeline::Pipeline>>,
) -> KeepaliveTasks {
    let mut tasks = Vec::new();
    if keep_alive_port != 0 {
        let media = idle_only.then(|| channel.udp_media.subscribe());
        tasks.push(crate::callback_context::spawn(crate::udp_keepalive::run(factory, car_ip, keep_alive_port, stop.clone(),
            wifi_iap_id.unwrap_or_default(), media)));
    } else {
        emit_log(&format!("AIRPLAY: keepAlive UDP 跳过 session={} port=0 reason=no_receiver_port",
            wifi_iap_id.unwrap_or_default()));
    }

    tasks.push(crate::callback_context::spawn(async move {
        let teardown = channel.wait_for_peer_teardown();
        tokio::pin!(teardown);
        let cause = tokio::select! {
            biased;
            _ = stop.notified() => return,
            cause = &mut teardown => cause,
            failure = run_feedback(&channel, wifi_iap_id, &pipeline) => {
                if channel.peer_teardown_pending() {
                    tokio::select! {
                        _ = stop.notified() => return,
                        cause = &mut teardown => cause,
                    }
                } else {
                    if matches!(failure, RtspEndCause::FeedbackFailed) { channel.record_feedback_failure(); }
                    failure
                }
            }
        };
        if let Some(id) = wifi_iap_id { emit_rtsp_end(&channel, id, cause); }
    }));
    KeepaliveTasks(tasks, None, None)
}

async fn run_feedback(
    channel: &RtspChannel,
    wifi_iap_id: Option<u64>,
    pipeline: &Arc<Mutex<crate::pipeline::Pipeline>>,
) -> RtspEndCause {
    let mut first_reply = true;
    let mut previous_failure = None;
    let mut audio_trace = crate::audio_diagnostics::FeedbackTrace::default();
    let mut video_trace = crate::video_feedback::FeedbackTrace::default();
    let audio_ownership = channel.buffered_ownership.subscribe();
    let audio_snapshot = || {
        let ownership = *audio_ownership.borrow();
        let snapshot = pipeline.lock().main_audio_feedback.as_ref()
            .filter(|source| ownership.owned && source.target.rtsp == channel.trace_id())
            .map(|source| source.snapshot(ownership.losses));
        let current = audio_ownership.borrow();
        snapshot.filter(|_| current.owned && current.losses == ownership.losses)
    };
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
        let rtsp = channel.trace_id();
        let target = crate::video_feedback::FeedbackTarget::current(&pipeline.lock(), rtsp);
        let audio_before_queue = audio_snapshot();
        match channel.request_feedback(previous_failure).await {
            Ok(reply) if reply.status() == 200 => {
                if let Some(id) = wifi_iap_id { crate::wifi_iap2::feedback(id, true); }
                audio_trace.set_observation_snapshots(audio_before_queue, audio_snapshot());
                if let Some(line) = audio_trace.observe(rtsp, reply.cseq(), &reply.body) { emit_log(&line); }
                if let (Some(target), Some(cseq)) = (target, reply.cseq()) {
                    let now_ms = crate::timing::monotonic_nanos() / 1_000_000;
                    if let Some(line) = video_trace.observe(pipeline, &target, rtsp, cseq, &reply.body, now_ms) {
                        emit_log(&line);
                    }
                }
                if previous_failure.take().is_some() {
                    emit_log(&format!("AIRPLAY: feedback 应答恢复 status=200 CSeq={:?}", reply.cseq()));
                }
                if first_reply {
                    emit_log(&format!("AIRPLAY: feedback 首次成功应答 status=200 CSeq={:?}", reply.cseq()));
                    first_reply = false;
                }
            }
            Ok(reply) => {
                let retry = previous_failure.is_none() && !channel.is_closed();
                emit_log(&format!("AIRPLAY: feedback rejected status={} CSeq={:?} retry={retry}", reply.status(), reply.cseq()));
                let empty_server_error = reply.is_empty_server_error();
                if !retry {
                    if empty_server_error && previous_failure == Some(FeedbackFailure::EmptyServerError) {
                        // The receiver answered both requests, but cannot provide feedback in this session.
                        emit_log(&format!("AIRPLAY: feedback polling disabled after two empty 500 responses RTSP#{rtsp}; monitoring control transport"));
                        channel.wait_for_control_close().await;
                        return RtspEndCause::ControlClosed;
                    }
                    return RtspEndCause::FeedbackFailed;
                }
                previous_failure = Some(if empty_server_error { FeedbackFailure::EmptyServerError }
                    else { FeedbackFailure::Rejected });
            }
            Err(crate::rtsp::RequestError::ReplyTimeout(detail)) if previous_failure.is_none() && !channel.is_closed() => {
                previous_failure = Some(FeedbackFailure::Timeout);
                emit_log(&format!("AIRPLAY: feedback 首次应答超时，下个周期使用新 CSeq 再发：{detail}"));
            }
            Err(error) => {
                emit_log(&format!("AIRPLAY: feedback stopped: {error}"));
                return RtspEndCause::FeedbackFailed;
            }
        }
    }
}

fn emit_rtsp_end(control: &RtspChannel, id: u64, fallback: RtspEndCause) {
    if crate::wifi_iap2::take_rtsp_end(id) {
        crate::wifi_iap2::feedback(id, false);
        crate::wifi_iap2::mark_ready(id, false);
        emit_state("ended", control.end_detail(fallback));
    }
}

// ---------------------------------------------------------------- 事件通道分发

fn spawn_event_dispatcher(
    mut inbound: tokio::sync::mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>,
    mut control_ui: mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>,
    apply_receiver_modes: crate::rtsp::CommandReply,
    control: Arc<RtspChannel>,
    pipeline: Arc<Mutex<crate::pipeline::Pipeline>>,
    cluster: crate::cluster_screen::Commands,
    wifi_iap_id: u64,
    modes: Arc<Mutex<crate::modes::Modes>>,
    screen_lifecycle: Option<Arc<crate::screen_session::ScreenSession>>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    mut consumer_hid: crate::consumer_hid::ConsumerHid,
    mut limited_ui: crate::receiver_ui::LimitedUi,
    main_uuid: Option<String>,
    display_size: (u32, u32),
    touch_surface: TouchSurface,
    target_size: Arc<Mutex<(u32, u32)>>,
    stop: Arc<Notify>,
) -> tokio::task::JoinHandle<()> {
    let mode_feedback = spawn_mode_feedback(control.clone(), stop.clone(), modes.clone(), screen_lifecycle.clone());
    crate::callback_context::spawn(async move {
        // Dropping the event dispatcher (EOF, stop, or negotiation rollback) cancels
        // any pending main-screen recovery along with the mode feedback worker.
        let mut transport_worker = tokio::task::JoinSet::new();
        if let Some(session) = screen_lifecycle.clone() {
            let modes = modes.clone();
            transport_worker.spawn(crate::callback_context::inherit(async move {
                session.monitor_transport_failures(&modes).await;
            }));
        }
        let mut bad_commands = 0usize;
        let mut contact = TouchContact::Idle;
        let mut multi_touch = TouchContacts::new(touch_tx.clone());
        let mut touch_grant = modes.lock().screen_grant_generation();
        let mut seen_commands: Vec<String> = Vec::new();
        let mut hid_reports: u64 = 0;
        let mut touch_logged: u32 = 0;
        let mut last_touch_trace = None;
        let mut other_lines: u64 = 0;
        let mut control_ui_open = true;
        loop {
            let (mut msg, from_control) = tokio::select! {
                _ = stop.notified() => return,
                m = control_ui.recv(), if control_ui_open => match m {
                    Some(m) if control.session_is_live() => (m, true),
                    Some(_) => continue,
                    None => { control_ui_open = false; continue; }
                },
                m = inbound.recv() => match m {
                    Some(m) if control.session_is_live() => (m, false),
                    Some(_) => continue,
                    None => {
                        if !control.peer_teardown_pending() {
                            emit_rtsp_end(&control, wifi_iap_id, RtspEndCause::EventClosed);
                        }
                        return;
                    },
                },
            };
            if !msg.first_line.contains("/command") {
                // 事件通道上除了 /command 还会有别的东西（RTSP 应答、心跳等）。
                // 以前这里静默 continue，导致「触控一点都没有」时完全无法判断
                // 是对方没发、还是报文格式和预期不同（现场 2026-10-23 23:20：
                // WinPlay 侧已确认发出触摸，我们这边却一条 EVENT 都没有）。
                other_lines += 1;
                if other_lines <= 5 {
                    emit_log(&format!(
                        "EVENT: 非 /command 报文（第 {other_lines} 条）first_line={:?} body={}B",
                        msg.first_line,
                        msg.body.len()
                    ));
                }
                continue;
            }
            let command = match bplist::parse(&msg.body) {
                Ok(v) => v,
                Err(_) => {
                    bad_commands += 1;
                    if bad_commands <= 3 {
                        emit_log(&format!(
                            "EVENT: /command plist 解析失败 {}B（累计 {bad_commands}）",
                            msg.body.len()
                        ));
                    }
                    continue;
                }
            };
            let cmd_type = command.get("type").and_then(Value::as_str).unwrap_or("");
            // The event reader applies requestUI when answering. The control reader already
            // sent its empty ACK, so apply the same screen arbitration once before dispatch.
            if from_control && cmd_type == "requestUI" { apply_receiver_modes(&mut msg); }
            sync_touch_grant(&modes.lock(), &mut touch_grant, &mut contact, &mut multi_touch, &touch_tx);
            // 事件通道是「车机 → 手机」的唯一通道（触摸/HID/命令）。它是否在工作，
            // 现场只能靠日志判断，所以每类命令第一次出现时记一行（之后不刷屏）。
            if !seen_commands.iter().any(|c| c == cmd_type) {
                if seen_commands.len() < 32 {
                    seen_commands.push(cmd_type.to_string());
                }
                    let source = if from_control { "control" } else { "event" };
                    emit_log(&format!("EVENT: 车机命令 type={cmd_type}（首次）source={source}"));
            }
            if cluster.handle(cmd_type, &command) { continue; }
            match cmd_type {
                "requestSiri" => {
                    let action=command.get("params").and_then(|v|v.get("siriAction")).or_else(||command.get("siriAction")).and_then(Value::as_int);
                    emit_log(&format!("VOICE-KEY: requestSiri action={action:?}"));
                    if let Some(key)=action.and_then(crate::voice_key::message) {let _=key_tx.send(key);}
                }
                "iAPSendMessage" => {
                    if let Some(data)=crate::wifi_iap2::command_bytes(&command) {
                        crate::wifi_iap2::receive_bytes(wifi_iap_id, data, "event");
                    } else if crate::wifi_iap2::command_data(&command).is_none() {
                        emit_log("WIFI-IAP: missing/oversized params.data");
                    }
                }
                "setLimitedUI" => {
                    if let Some(payload) = limited_ui.update(&command) {
                        crate::emit_receiver_ui(cmd_type, payload);
                    } else {
                        emit_log("RECEIVER-UI: ignored setLimitedUI without a valid limit state");
                    }
                }
                "accessoryGiveFocus" => {
                    if crate::receiver_ui::main_target(&command, main_uuid.as_deref()) && modes.lock().screen_owned() {
                        if let Some(payload) = crate::receiver_ui::focus(&command, display_size) {
                            crate::emit_receiver_ui(cmd_type, payload);
                        } else {
                            emit_log("RECEIVER-UI: ignored invalid focus rectangle or heading");
                        }
                    }
                }
                kind if crate::rtsp::is_box_observation_command(kind) => {
                    // Match the box's observation-only branches. In particular stopUI
                    // is not a request to tear down video/Wi-Fi or change resource ownership.
                }
                "changeModes" => {
                    // Already applied synchronously while building the request's status+params reply.
                    let _ = mode_feedback.send(Some(ModeFeedback::Snapshot));
                }
                "requestUI" => {
                    log_ui_command(&command);
                    if !crate::receiver_ui::main_target(&command, main_uuid.as_deref()) { continue; }
                    if modes.lock().screen_owned() {
                        if let Some(url) = crate::receiver_ui::destination(&command) {
                            crate::emit_receiver_ui(cmd_type, serde_json::json!({"url": url}));
                        }
                    }
                    let _ = mode_feedback.send(Some(ModeFeedback::Snapshot));
                    crate::screen::observe_receiver_keyframe_request(&pipeline, control.trace_id(), "requestUI");
                    crate::emit_force_keyframe();
                }
                "changeUIContext" => {
                    // The event transport acknowledges this request. Observe context without
                    // inventing resource transfers or treating an arbitrary URL as an intent.
                    log_ui_command(&command);
                }
                "hidSendReport" => {
                    // 两种布局都要认：
                    //  - 苹果/真车常见：{ params: { report: <data> } }
                    //  - WinPlay：      { type, uuid, hidReport: <data> }（顶层）
                    // 只认前者会表现成「收到了 hidSendReport 但没有 HID 报告」，
                    // 触摸静默失效（现场 2026-10-23 23:33 定位）。
                    let report = command
                        .get("hidReport")
                        .and_then(Value::as_bytes)
                        .or_else(|| {
                            command
                                .get("params")
                                .and_then(|p| p.get("report"))
                                .and_then(Value::as_bytes)
                        });
                    if let Some(report) = report {
                        let uuid = command.get("uuid").or_else(||command.get("params").and_then(|p|p.get("uuid")));
                        hid_reports += 1;
                        if hid_reports <= 3 || hid_reports % 20 == 0 {
                            emit_log(&format!(
                                "EVENT: HID 报告 #{hid_reports} device={uuid:?} {}B",
                                report.len()
                            ));
                        }
                        if let Some(keys) = consumer_hid.report(uuid, report) {
                            for usage in keys {
                                emit_log(&format!("HID-KEY: device={uuid:?} input=0x{usage:x} pressed={}",usage!=crate::voice_key::HID_UP));
                                let _ = key_tx.send(usage);
                            }
                            continue;
                        }
                        let at = crate::timing::monotonic_nanos() / 1_000_000;
                        let touch_trace = last_touch_trace.is_none_or(|last| at.saturating_sub(last) >= 1000).then_some(at);
                        let before = touch_logged;
                        let decoded = consumer_hid.multi_touch_samples(uuid, report, display_size);
                        let state = modes.lock();
                        let owned = sync_touch_grant(&state, &mut touch_grant, &mut contact, &mut multi_touch, &touch_tx);
                        // A delayed report still carries physical press/release state, but it
                        // cannot dispatch coordinates after ownership was taken and returned.
                        let owned = owned && msg.hid_screen_grant == Some(touch_grant);
                        let grant = owned.then(|| TouchGrant::new(&modes, touch_grant));
                        match decoded {
                            Ok(Some(report)) if matches!(contact, TouchContact::Idle) => {
                                let mapping = owned.then(|| touch_surface.mapping(display_size, *target_size.lock())).flatten();
                                if multi_touch.source.is_none() { multi_touch.grant = grant; }
                                let mapped = multi_touch.update(report, mapping, matches!(touch_surface, TouchSurface::Mgu55(_)));
                                touch_logged = touch_logged.wrapping_add(mapped);
                            }
                            Ok(None) if multi_touch.source.is_none() => {
                                let Some(sample) = consumer_hid.touch_sample(uuid, report, display_size) else { continue; };
                                handle_touch_sample(
                                    sample, &touch_tx, display_size, &touch_surface, &target_size,
                                    &mut contact, &mut touch_logged, owned, grant,
                                );
                            }
                            _ => continue,
                        }
                        drop(state);
                        if let Some(at) = touch_trace {
                            last_touch_trace = Some(at);
                            crate::emit_media_trace(at, "hid_touch_in",
                                &format!("RTSP#{} bytes={} mapped={}", control.trace_id(), report.len(), touch_logged != before));
                        }
                    }
                }
                "setNightMode" => {
                    // 车机日夜信号：宝马/CarPlay 接收端会随大灯/中控日夜模式下发。
                    // 兼容 params.nightMode 与顶层 nightMode；bool / 0/1 都认。
                    let night = command
                        .get("nightMode")
                        .and_then(value_as_bool)
                        .or_else(|| {
                            command
                                .get("params")
                                .and_then(|p| p.get("nightMode"))
                                .and_then(value_as_bool)
                        });
                    if let Some(night) = night {
                        emit_log(&format!("EVENT: 车机日夜模式 nightMode={night}"));
                        crate::emit_night_mode(night);
                    } else {
                        emit_log("EVENT: setNightMode 未解析出 nightMode 字段");
                    }
                }
                "forceKeyFrame" => {
                    if !crate::receiver_ui::main_target(&command, main_uuid.as_deref()) {
                        emit_log("EVENT: ignored forceKeyFrame for an unknown display");
                        continue;
                    }
                    emit_log("EVENT: 车机请求关键帧 forceKeyFrame → requestSyncFrame");
                    crate::screen::observe_receiver_keyframe_request(&pipeline, control.trace_id(), "forceKeyFrame");
                    crate::emit_force_keyframe();
                }
                "hidSetInputMode" | "ping" | "fps" => {}
                other => {
                    bad_commands += 1;
                    if bad_commands <= 8 {
                        emit_log(&format!("EVENT: 未处理的车机命令 type={other}"));
                    }
                }
            }
        }
    })
}

/// Dirty-state worker, like CatPlay's tx_queue: waiting for a mode response must never
/// stop event dispatch (Wi-Fi iAP2 certificate exchange, touch and forceKeyFrame).
/// A watch channel keeps only the latest snapshot while a previous response is pending.
#[derive(Clone, Copy)]
enum ModeFeedback { Snapshot }

struct ModeFeedbackWorker {
    tx:tokio::sync::watch::Sender<Option<ModeFeedback>>,
    task:tokio::task::JoinHandle<()>,
}
impl std::ops::Deref for ModeFeedbackWorker {
    type Target=tokio::sync::watch::Sender<Option<ModeFeedback>>;
    fn deref(&self)->&Self::Target {&self.tx}
}
impl Drop for ModeFeedbackWorker {fn drop(&mut self) {self.task.abort();}}

fn spawn_mode_feedback(
    control: Arc<RtspChannel>, stop: Arc<Notify>, modes: Arc<Mutex<crate::modes::Modes>>,
    screen_lifecycle: Option<Arc<crate::screen_session::ScreenSession>>,
) -> ModeFeedbackWorker {
    let (tx, mut rx) = tokio::sync::watch::channel::<Option<ModeFeedback>>(None);
    let task=crate::callback_context::spawn(async move {
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                changed = rx.changed() => if changed.is_err() { return; },
            }
            if rx.borrow_and_update().is_none() { continue; }
            if let Some(session) = screen_lifecycle.as_ref() {
                let result = tokio::select! {
                    _ = stop.notified() => return,
                    result = session.reconcile_resource(&modes) => result,
                };
                if let Err(error) = result {
                    session.params.pipeline.lock().video_stream="FAILED".into();
                    emit_log(&format!("SCREEN-LIFECYCLE: failed: {error}; preserve audio/control; retry on next resource/UI event"));
                }
                continue;
            }
            let body = modes.lock().declaration();
            let response = tokio::select! {
                _ = stop.notified() => return,
                reply = control.request("POST", "/command", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(3)) => reply,
            };
            match response {
                Ok(reply) => {
                    let business_status = bplist::parse(&reply.body).ok().and_then(|v| v.get("status").and_then(Value::as_int));
                    emit_log(&format!("MODES: modesChanged feedback RTSP_status={} business_status={business_status:?} body={}B; receiver display unconfirmed", reply.status(), reply.body.len()));
                }
                Err(error) => emit_log(&format!("MODES: modesChanged feedback failed {error}")),
            }
        }
    });
    ModeFeedbackWorker {tx,task}
}

fn log_ui_command(command: &Value) {
    // Log only bounded UI metadata. URLs can contain user data: keep their scheme, not path/query.
    fn shape(v: &Value, depth: usize) -> String {
        if depth > 2 { return "…".into(); }
        match v {
            Value::String(s) => {
                let scheme = s.split_once(':').map(|(scheme,_)| scheme)
                    .filter(|s| !s.is_empty() && s.len() <= 32 && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.')));
                scheme.map(|s| format!("scheme:{s}")).unwrap_or_else(|| format!("string({}B)", s.len()))
            }
            Value::Bool(v) => v.to_string(), Value::Int(v) => v.to_string(),
            Value::Array(a) => format!("[{}]", a.iter().take(6).map(|v| shape(v,depth+1)).collect::<Vec<_>>().join(",")),
            Value::Dict(d) => format!("{{{}}}", d.iter().take(12).map(|(k,v)| format!("{}:{}", k.as_str().unwrap_or("?").chars().filter(|c| !c.is_control()).take(48).collect::<String>(), shape(v,depth+1))).collect::<Vec<_>>().join(",")),
            Value::Data(d) => format!("data({}B)",d.len()), _ => "other".into(),
        }
    }
    let params = command.get("params").map(|p| shape(p,0)).unwrap_or_else(|| "absent".into());
    let url = command.get("url").map(|v| shape(v,0)).unwrap_or_else(|| "absent".into());
    emit_log(&format!("UI-COMMAND: type={} params={params} top_url={url}; observed context, not display confirmation", command.get("type").and_then(Value::as_str).unwrap_or("?")));
}

fn value_as_bool(v: &bplist::Value) -> Option<bool> {
    v.as_bool().or_else(|| v.as_int().map(|n| n != 0))
}

enum TouchSurface {
    Display,
    DesktopWindow(u32, u32, u32, u32),
    Mgu55(Arc<Mutex<crate::pipeline::Pipeline>>),
}

impl TouchSurface {
    fn for_receiver(info: &Value, mgu_carwith: bool, display: (u32,u32),
        window: Option<(u32,u32,u32,u32)>, pipeline: &Arc<Mutex<crate::pipeline::Pipeline>>) -> Self {
        if display.0 < 320 || display.1 < 240 {
            return window.map(|(l,t,r,b)| Self::DesktopWindow(l,t,r,b)).unwrap_or(Self::Display);
        }
        if use_box_bootstrap(mgu_carwith, info) && info.get("model").and_then(Value::as_str) == Some("MGU-55") {
            return Self::Mgu55(pipeline.clone());
        }
        Self::Display
    }

    fn mapping(&self, (dw, dh): (u32, u32), target: (u32, u32)) -> Option<TouchMapping> {
        let (dw, dh) = (dw.max(1), dh.max(1));
        let (viewport, config_generation) = match *self {
            Self::Display => ((0, 0, dw, dh), 0),
            Self::DesktopWindow(l, t, r, b) => ((l, t, r.saturating_sub(l).max(1), b.saturating_sub(t).max(1)), 0),
            Self::Mgu55(ref pipeline) => {
                let pipeline = pipeline.lock();
                let (w,h) = pipeline.screen_config_size?;
                if w == 0 || h == 0 { return None; }
                // Confirmed MGU-55 presentation for an encoded canvas fitting the display.
                let viewport = if w <= dw && h <= dh { ((dw-w)/2, (dh-h)/2, w, h) }
                    else { (0, 0, dw, dh) };
                (viewport, pipeline.screen_config_generation)
            }
        };
        Some(TouchMapping { config_generation, ..TouchMapping::new(viewport, target) })
    }
}

#[derive(Clone)]
enum TouchContact {
    Idle,
    Ignored,
    Active { x: u32, y: u32, mapping: TouchMapping, grant: Option<TouchGrant> },
}

impl TouchContact {
    fn cancel(&mut self, sender: &mpsc::UnboundedSender<TouchInput>) {
        if let Self::Active { x, y, grant, .. } = self {
            let _ = sender.send(TouchInput { action: 3, x: *x, y: *y, pointers: Vec::new(), grant: grant.clone() });
            *self = Self::Ignored;
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct TouchMapping {
    viewport: (u32, u32, u32, u32),
    target: (u32, u32),
    config_generation: u64,
}

impl TouchMapping {
    fn new(viewport: (u32, u32, u32, u32), (width, height): (u32, u32)) -> Self {
        Self { viewport, target: (width.max(2), height.max(2)), config_generation: 0 }
    }

    fn contains(self, x: u32, y: u32) -> bool {
        let (left, top, width, height) = self.viewport;
        (left..left.saturating_add(width)).contains(&x) && (top..top.saturating_add(height)).contains(&y)
    }

    fn point(self, id: u32, x: u32, y: u32) -> TouchPoint {
        let (left, top, width, height) = self.viewport;
        let scale = |raw: u32, origin, extent, target| {
            let offset = raw.saturating_sub(origin);
            (u64::from(offset) * u64::from(target) / u64::from(extent)).min(u64::from(target - 1)) as u32
        };
        TouchPoint { id, x: scale(x, left, width, self.target.0), y: scale(y, top, height, self.target.1) }
    }
}

use crate::consumer_hid::{MultiTouchReport, TouchIdentity};

struct TouchContacts {
    sender: mpsc::UnboundedSender<TouchInput>,
    grant: Option<TouchGrant>,
    source: Option<(String, u8)>,
    mapping: Option<TouchMapping>,
    active: Vec<(TouchIdentity, TouchPoint)>,
    ignored: Vec<TouchIdentity>,
    suppressed: bool,
}

impl TouchContacts {
    fn new(sender: mpsc::UnboundedSender<TouchInput>) -> Self {
        Self { sender, grant: None, source: None, mapping: None, active: Vec::new(), ignored: Vec::new(), suppressed: false }
    }

    fn send(&self, action: u32, changed: usize) {
        let point = self.active[changed].1;
        let pointers = self.active.iter().map(|(_, point)| *point).collect();
        let _ = self.sender.send(TouchInput { action, x: point.x, y: point.y, pointers, grant: self.grant.clone() });
    }

    fn cancel(&mut self) {
        if !self.active.is_empty() { self.send(3, 0); }
        self.active.clear();
        self.suppressed = true;
    }

    fn update(&mut self, report: MultiTouchReport, mapping: Option<TouchMapping>, exclude_dead_space: bool) -> u32 {
        if self.source.as_ref().is_some_and(|source| source != &report.source) { return 0; }
        if self.source.is_none() && !report.contacts.is_empty() {
            self.source = Some(report.source);
            self.mapping = mapping;
        }
        let mut count = 0;
        if mapping.is_none() || self.mapping != mapping && self.source.is_some() {
            count += u32::from(!self.active.is_empty());
            self.cancel();
        }
        if let Some(mapping) = mapping.filter(|_| !self.suppressed) {
            count += self.apply_contacts(&report.contacts, mapping, exclude_dead_space);
        }
        if report.contacts.is_empty() {
            self.source = None;
            self.grant = None;
            self.mapping = None;
            self.ignored.clear();
            self.suppressed = false;
        }
        count
    }

    fn apply_contacts(&mut self, contacts: &[(TouchIdentity, crate::consumer_hid::TouchSample)],
        mapping: TouchMapping, exclude_dead_space: bool) -> u32 {
        let mut events = 0;
        let mut moved = false;
        self.ignored.retain(|id| contacts.iter().any(|(contact, _)| contact == id));
        // Slots may be reordered in the report. Android pointer order follows the gesture.
        for (identity, point) in &mut self.active {
            if let Some((_, sample)) = contacts.iter().find(|(id, _)| id == identity) {
                let next = mapping.point(point.id, sample.x, sample.y);
                moved |= next != *point;
                *point = next;
            }
        }
        for index in (0..self.active.len()).rev() {
            if !contacts.iter().any(|(id, _)| *id == self.active[index].0) {
                let action = if self.active.len() == 1 { 1 } else { 6 | (index as u32) << 8 };
                self.send(action, index);
                self.active.remove(index);
                events += 1;
            }
        }
        for (identity, sample) in contacts {
            if self.active.iter().any(|(id, _)| id == identity) || self.ignored.contains(identity) { continue; }
            if exclude_dead_space && !mapping.contains(sample.x, sample.y) {
                self.ignored.push(*identity);
                continue;
            }
            let pointer_id = (0..32).find(|id| self.active.iter().all(|(_, point)| point.id != *id))
                .expect("validated HID report has at most 16 contacts");
            let index = self.active.len();
            self.active.push((*identity, mapping.point(pointer_id, sample.x, sample.y)));
            self.send(if index == 0 { 0 } else { 5 | (index as u32) << 8 }, index);
            events += 1;
        }
        if events == 0 && moved {
            self.send(2, 0);
            events += 1;
        }
        events
    }
}

impl Drop for TouchContacts {
    fn drop(&mut self) { self.cancel(); }
}

fn capture_hid_screen_grant(message: &mut crate::rtsp::RtspMessage, command: &Value, modes: &crate::modes::Modes) {
    if command.get("type").and_then(Value::as_str) == Some("hidSendReport") {
        message.hid_screen_grant = modes.screen_owned().then(|| modes.screen_grant_generation());
    }
}

fn sync_touch_grant(
    modes: &crate::modes::Modes,
    observed: &mut u64,
    contact: &mut TouchContact,
    multi: &mut TouchContacts,
    sender: &mpsc::UnboundedSender<TouchInput>,
) -> bool {
    let current = modes.screen_grant_generation();
    let owned = modes.screen_owned();
    if !owned || *observed != current {
        contact.cancel(sender);
        if multi.source.is_some() { multi.cancel(); }
    }
    *observed = current;
    owned
}

/// Map receiver HID pixels into the local input canvas. Actions use Android DOWN/MOVE/UP/CANCEL.
fn handle_touch_sample(
    sample: crate::consumer_hid::TouchSample,
    touch_tx: &mpsc::UnboundedSender<TouchInput>,
    display: (u32, u32),
    surface: &TouchSurface,
    target_size: &Arc<Mutex<(u32, u32)>>,
    contact: &mut TouchContact,
    logged: &mut u32,
    owned: bool,
    grant: Option<TouchGrant>,
) {
    // Read the negotiated canvas for new contacts. A held contact cannot change its mapping.
    let target = *target_size.lock();
    let crate::consumer_hid::TouchSample { pressed, x: x_raw, y: y_raw } = sample;
    if matches!(contact, TouchContact::Ignored) {
        if !pressed { *contact = TouchContact::Idle; }
        return;
    }
    let (dw,dh) = (display.0.max(1), display.1.max(1));
    let mapping = owned.then(|| surface.mapping((dw, dh), target)).flatten();
    let Some(mapping) = mapping.filter(|current| !matches!(*contact, TouchContact::Active {
        mapping, ..
    } if mapping != *current)) else {
        // A screen take, a config reset or a replacement sender invalidates a held press.
        contact.cancel(touch_tx);
        *contact = if pressed { TouchContact::Ignored } else { TouchContact::Idle };
        return;
    };
    if matches!(surface, TouchSurface::Mgu55(_)) && matches!(contact, TouchContact::Idle) && pressed
        && !mapping.contains(x_raw, y_raw) {
        *contact = TouchContact::Ignored;
        return;
    }
    let TouchPoint { x, y, .. } = mapping.point(0, x_raw, y_raw);
    let action = match (&*contact, pressed) {
        (TouchContact::Idle, true) => 0,
        (TouchContact::Active { .. }, true) => 2,
        (TouchContact::Active { .. }, false) => 1,
        _ => return,
    };
    *contact = if pressed { TouchContact::Active { x, y, mapping, grant: grant.clone() } }
        else { TouchContact::Idle };
    *logged = logged.wrapping_add(1);
    if *logged <= 5 || *logged % 200 == 0 {
        let (left,top,width,height) = mapping.viewport;
        let (tw,th) = mapping.target;
        crate::emit_log(&format!("TOUCH: 车机({x_raw},{y_raw}) 空间 {dw}x{dh} 画面 ({left},{top}) {width}x{height} → 画布 {tw}x{th} = ({x},{y})（第 {logged} 次）"));
    }
    let _ = touch_tx.send(TouchInput { action, x, y, pointers: Vec::new(), grant });
}
// ---------------------------------------------------------------- 工具

/// RtspTransport 适配：pairing 层是同步 trait，跑在多线程 runtime 的 worker 上，
/// 用 block_in_place + block_on 桥接（配对只有 M1..M4 四次往返，代价可忽略）。
struct RtspTransport<'a> {
    channel: &'a Arc<RtspChannel>,
}

impl TlvTransport for RtspTransport<'_> {
    fn exchange(&mut self, path: &str, body: &[u8]) -> Result<Vec<u8>, String> {
        let channel = self.channel.clone();
        let result = tokio::task::block_in_place(|| {
            tokio::runtime::Handle::current().block_on(async move {
                channel
                    .request(
                        "POST",
                        path,
                        body,
                        "application/pairing+tlv8",
                        &[("X-Apple-HKP", "2".to_string()), ("X-Apple-PD", "1".to_string())],
                        Duration::from_secs(10),
                    )
                    .await
            })
        });
        match result {
            Ok(resp) => {
                if resp.status() != 200 {
                    Err(format!("{path} 应答 [{}]", resp.first_line))
                } else {
                    Ok(resp.body)
                }
            }
            Err(e) => Err(e),
        }
    }
}

/// 事件通道控制密钥。
///
/// **注意：事件通道有独立的 HKDF 域**，不能复用控制通道的 `Control-Salt`——
/// 对照 cardeck `connectEventChannel`：
///   phoneWriteKey = HKDF(secret, "Events-Salt", "Events-Read-Encryption-Key")
///   phoneReadKey  = HKDF(secret, "Events-Salt", "Events-Write-Encryption-Key")
///   ControlCipher(phoneReadKey, phoneWriteKey)
/// 即标签按「配件视角」命名，与我们 read/write 是交叉的。
/// 曾错用 Control-Salt 派生 → 事件通道解密必失败 → 触摸/命令全丢。
fn derive_event_keys(shared: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let write: [u8; 32] = crate::crypto::hkdf_sha512(
        shared,
        b"Events-Salt",
        b"Events-Read-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap();
    let read: [u8; 32] = crate::crypto::hkdf_sha512(
        shared,
        b"Events-Salt",
        b"Events-Write-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap();
    (read, write)
}

/// 屏幕/音频流密钥：HKDF-SHA512(shared, "DataStream-Salt<connId>", "DataStream-Output-Encryption-Key")。
pub fn stream_key(shared: &[u8; 32], conn_id: u64) -> [u8; 32] {
    let salt = format!("DataStream-Salt{conn_id}");
    crate::crypto::hkdf_sha512(
        shared,
        salt.as_bytes(),
        b"DataStream-Output-Encryption-Key",
        32,
    )
    .try_into()
    .unwrap()
}

/// et=NONE 会话的 AES 流密钥（会话密钥全零；catplay ScreenFrameCodec 同款）。
pub(crate) fn aes_stream_keys_zero(conn_id: u64) -> ([u8; 16], [u8; 16]) {
    fn digest(prefix: &str, conn_id: u64) -> [u8; 16] {
        let conn = conn_id.to_string();
        let mut out = [0u8; 16];
        out.copy_from_slice(
            &crate::crypto::sha512(&[prefix.as_bytes(), conn.as_bytes(), &[0u8; 16]])[..16],
        );
        out
    }
    (
        digest("AirPlayStreamKey", conn_id),
        digest("AirPlayStreamIV", conn_id),
    )
}

/// /info displays[].uuid 三种编码统一字符串化（字符串 / 16B UUID / UID 整数）。
pub(crate) fn uuid_text(v: Option<&Value>) -> Option<String> {
    match v? {
        Value::String(s) if !s.is_empty() => Some(s.clone()),
        Value::Data(d) if d.len() == 16 => {
            let hex: String = d.iter().map(|b| format!("{b:02x}")).collect();
            Some(format!(
                "{}-{}-{}-{}-{}",
                &hex[0..8],
                &hex[8..12],
                &hex[12..16],
                &hex[16..20],
                &hex[20..]
            ))
        }
        Value::Int(i) => Some(i.to_string()),
        _ => None,
    }
}

pub(crate) fn port_of(v: Option<&Value>) -> u16 {
    match v {
        Some(Value::Int(i)) => (*i).clamp(0, u16::MAX as i64) as u16,
        _ => 0,
    }
}

pub(crate) fn random_positive_i64() -> i64 {
    let mut b = [0u8; 8];
    getrandom::getrandom(&mut b).unwrap_or_default();
    (i64::from_le_bytes(b) & i64::MAX).max(1)
}

// Box 0x25adc uses this template with two runtime fields; do not copy a device's UUID.
fn box_session_uuid() -> String {
    format!("{:04X}8CD2-3073-4550-{:04X}-93D6385B7331", rand::random::<u16>(), rand::random::<u16>())
}

fn random_uuid_upper() -> String {
    uuid::Uuid::new_v4().simple().to_string().to_uppercase()
}

#[cfg(test)]
mod mbux_profile_tests {
    use super::*;
    #[test]
    fn box_session_uuid_changes_per_attempt_without_replacing_phone_identity() {
        let mut sessions=std::collections::HashSet::new();
        for _ in 0..16 {
            let body=bplist::parse(&build_session_setup("phone-device","phone",1234,true,false,false,false, false)).unwrap();
            assert_eq!(body.get("deviceID").and_then(Value::as_str),Some("phone-device"));
            let id=body.get("sessionUUID").and_then(Value::as_str).unwrap();
            assert_eq!(id.len(),36);
            assert_eq!(&id[4..19],"8CD2-3073-4550-");
            assert_eq!(&id[23..],"-93D6385B7331");
            assert!(uuid::Uuid::parse_str(id).is_ok());
            assert!(sessions.insert(id.to_owned()));
            assert_ne!(body.get("sessionCorrelationUUID").and_then(Value::as_str),Some(id));
        }
    }

    #[test]
    fn all_session_profiles_use_the_resolved_phone_identity_without_a_fixed_placeholder() {
        for encrypted in [true,false] {
            for mbux in [false,true] {
                for device_id in ["02:11:22:33:44:55", "02:66:77:88:99:00"] {
                    let body=bplist::parse(&build_session_setup(device_id,"test phone",1234,encrypted,mbux,false,false, false)).unwrap();
                    assert_eq!(body.get("macAddress").and_then(Value::as_str),Some(device_id));
                    assert_eq!(body.get("deviceID"),body.get("macAddress"));
                    assert_eq!(body.get("name").and_then(Value::as_str),Some("test phone"));
                    assert_eq!(body.get("timingPort").and_then(Value::as_int),Some(1234));
                    assert_eq!(body.get("statsCollectionEnabled").and_then(Value::as_bool),Some(false));
                    assert_eq!(body.get("et").and_then(Value::as_int),if encrypted {None} else {Some(1)});
                    let features=body.get("features").unwrap().as_array().unwrap();
                    assert_eq!(features.len(),if mbux {1} else {CONTROLLER_FEATURES.len()});
                    assert!(!features.contains(&Value::str("cornerMasks")));
                    if !mbux {
                        for capability in ["viewAreas","uiContext","focusTransfer","iAPChannel"] {
                            assert!(features.iter().any(|v|v.as_str()==Some(capability)));
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn mercedes_udp_audio_never_uses_buffered_type103_capabilities() {
        let formats=|kind,bits|Value::dict(vec![("audioFormats",Value::array(vec![Value::dict(vec![
            ("type",Value::int(kind)),("audioType",Value::str("media")),("audioOutputFormats",Value::int(bits))])]))]);
        assert!(audio_candidates(&formats(103,(1<<22)|(1<<23)),"auto",true).is_empty());
        assert_eq!(audio_candidates(&formats(102,1<<23),"auto",true),vec![AudioCodec::Aac]);
        assert_eq!(audio_candidates(&formats(100,1<<15),"auto",true),vec![AudioCodec::Pcm]);
        // Non-strict MGU/default behavior stays unchanged.
        assert_eq!(audio_candidates(&formats(103,1<<22),"auto",false),vec![AudioCodec::Pcm]);
    }
    #[test]
    fn mercedes_features_match_implemented_media_without_changing_other_fields() {
        let baseline=bplist::parse(&build_session_setup("phone","name",1234,true,false,false,false, false)).unwrap();
        let compat=bplist::parse(&build_session_setup("phone","name",1234,true,false,false,true, false)).unwrap();
        let features=compat.get("features").unwrap().as_array().unwrap();
        assert_eq!(features,&baseline.get("features").unwrap().as_array().unwrap().iter()
            .filter(|f| f.as_str()!=Some("hevc")).cloned().collect::<Vec<_>>());
        assert!(features.iter().any(|f|f.as_str()==Some("h.264Level5.1")));
        let stable=|v:&Value|v.as_dict().unwrap().iter().filter(|(k,_)|
            !matches!(k.as_str(),Some("features"|"sessionUUID"|"sessionCorrelationUUID"))).cloned().collect::<Vec<_>>();
        assert_eq!(stable(&baseline),stable(&compat));
        let base = session_features(false,false,false);
        assert!(!base.iter().any(|f| f.as_str()==Some("altScreen")));
        assert_eq!(session_features(false,true,false).len(),base.len()-1);
        assert_eq!(features.len(),base.len()-1);
    }
    #[test]
    fn id9_session_removes_only_hevc_and_preserves_other_profiles() {
        for encrypted in [false, true] {
            for mbux in [false, true] {
                let baseline = bplist::parse(&build_session_setup("phone", "name", 1234, encrypted, mbux, false,false, false)).unwrap();
                let id9 = bplist::parse(&build_session_setup("phone", "name", 1234, encrypted, mbux, true,false, false)).unwrap();
                let expected: Vec<_> = baseline.get("features").unwrap().as_array().unwrap().iter()
                    .filter(|f| f.as_str() != Some("hevc")).cloned().collect();
                assert_eq!(id9.get("features").unwrap().as_array().unwrap(), &expected);
                assert_eq!(expected.len(), if mbux { 1 } else { CONTROLLER_FEATURES.len()-1 });
                // These two UUIDs intentionally change on every build of a session body.
                let stable_fields = |v: &Value| v.as_dict().unwrap().iter()
                    .filter(|(k, _)| !matches!(k.as_str(), Some("features" | "sessionUUID" | "sessionCorrelationUUID")))
                    .cloned().collect::<Vec<_>>();
                assert_eq!(stable_fields(&baseline), stable_fields(&id9));
                assert_eq!(baseline.get("features").unwrap().as_array().unwrap(), &session_features(mbux, false, false));
                if !mbux {
                    assert_eq!(session_features(false, false, false), CONTROLLER_FEATURES.iter().map(|f| Value::str(f)).collect::<Vec<_>>());
                }
            }
        }
    }

    #[test]
    fn minimal_features_and_100ms_latency_are_explicit_profile_choices() {
        for mbux in [false,true] {
            let body=bplist::parse(&build_session_setup("real-mac","测试手机 \"BT\"",1234,true,mbux,false,false, false)).unwrap();
            assert_eq!(body.get("name").and_then(Value::as_str),Some("测试手机 \"BT\""));
            let features=body.get("features").unwrap().as_array().unwrap();
            assert_eq!(features.len(),if mbux {1} else {CONTROLLER_FEATURES.len()});
            assert_eq!(body.get("macAddress").and_then(Value::as_str),Some("real-mac"));
        }
        let body=bplist::parse(&build_screen_setup(42,"actual-display")).unwrap();
        assert_eq!(body.get("streams").unwrap().as_array().unwrap()[0].get("latencyMs").and_then(Value::as_int),Some(25));
    }
}
