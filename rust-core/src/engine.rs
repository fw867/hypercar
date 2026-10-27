//! 网关引擎编排：CarLife 回环 ⇄ AirPlay 推流 的粘合层。
//!
//! 数据面（全部零转码）：
//!  - 视频：CarLife 8240 的 H.264 Annex B 裸流 → ScreenSender（128B 头 + ChaCha20-Poly1305）
//!  - 音频：CarLife 9240/9241 的 PCM → 线性重采样 48kHz 立体声 → TTS 闪避混音 → RTP
//!  - 触摸：AirPlay 事件通道 HID → CarLife 9340；iDrive 旋钮 → 硬按键 0x68008
//!
//! 控制面：Kotlin 交来「已连上车机 AirPlay 端口的 control FD」+ socket 供给器，
//! 其余协商（/info、pair-verify、SETUP、RECORD、三条心跳）全部在本层闭环。
//!
//! 任务拓扑（单分发器 → 双泵，避免多消费者抢同一接收器）：
//!   CarLifeHub ─events─▶ demux ─video─▶ video pump ─▶ ScreenSender
//!                          └──audio─▶ audio pump ─▶ AudioOut(RTP)

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::{mpsc, Notify};

use crate::airplay::{self, AirplayParams, SocketProvider, TouchInput};
use crate::audio_out::AudioOut;
use crate::carlife::{CarLifeConfig, CarLifeHub};
use crate::screen::ScreenSender;
use crate::{emit_log, emit_stats, emit_state};

/// CarLife 通道产出的事件。
pub enum CarLifeEvent {
    Handshake,
    VideoFrame { pts_us: u64, data: Vec<u8> },
    AudioInit { tts: bool, rate: u32, channels: u8 },
    AudioStop { tts: bool },
    AudioData { tts: bool, rate: u32, channels: u8, data: Vec<u8> },
    /// 手机在 VIDEO_ENCODER_INIT_DONE 里回报的**实际**编码分辨率
    /// （可能与我们请求的不同：机型编码器会自行压缩到支持的上限）。
    EncoderResolution { width: u32, height: u32 },
    ClientLeft { video: bool },
}

/// 引擎配置（SettingsStore.engineConfigJson 的反序列化目标）。
#[derive(Debug, Clone, serde::Deserialize)]
pub struct EngineConfig {
    pub device_id: String,
    pub car_ip: String,
    pub airplay_port: u16,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    #[serde(default = "default_true")]
    pub enable_audio: bool,
    #[serde(default)]
    pub keep_alive_stats: bool,
    pub identity_path: String,
    /// 非空 = CarLife 走**主动连接模式**：不发监听，改为逐个拨 `md_host` 的标准端口。
    ///
    /// 小米 CarLife/CarWith「车辆热点 → 开始连接」后是 MD 在监听（7240/8240/…），
    /// 等车机连入；Android 上（HU 与 MD 同机）保持 `None`，仍用回环监听。
    #[serde(default)]
    pub md_host: Option<String>,
    /// 接收端 `/info` 报 0x0 时的**触摸显示空间**宽/高（0 = 用协商的屏幕尺寸）。
    ///
    /// WinPlay 这类接收端固定报 0x0，但它的 HID 坐标按自己窗口像素算
    /// （实测 2732x794）；不指定就会整体缩放偏移，表现为「按钮很难点上」。
    #[serde(default)]
    pub touch_display_w: u32,
    #[serde(default)]
    pub touch_display_h: u32,
    /// 特性配置里的 `voice`：0 = 车机不提供语音（MD 回落到**手机自己的麦克风**），
    /// 1 = HU 通过 VR 通道推麦克风数据。
    ///
    /// 默认 0：我们没有可靠的车机麦克风来源；声明 1 会让手机等我们喂麦克风，
    /// 语音助手于是报「麦克风有问题」（现场 2026-10-24 定位）。
    #[serde(default)]
    pub voice: u32,
    /// 只跑 CarLife 通道（不做蓝牙/iAP2/AirPlay）：调试广播 PROBE_CARLIFE_ONLY 用。
    #[serde(default)]
    pub carlife_only: bool,
}

fn default_true() -> bool {
    true
}

const TARGET_RATE: u32 = 48_000;
const SPF: usize = 352; // AirPlay 每包样本帧（512 在真车无声）

/// 引擎主入口：control FD → AirPlay 协商 → 双向泵。
/// 触摸/按键注入通道由 JNI 层创建（nativeSendTouch/nativeSendHardKey 持发送端），
/// 发送端同时交给 AirPlay 事件分发器，接收端在此转发给 CarLife 触摸通道。
#[allow(clippy::too_many_arguments)]
pub async fn run_engine(
    cfg: EngineConfig,
    control_stream: tokio::net::TcpStream,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    mut touch_rx: mpsc::UnboundedReceiver<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    mut key_rx: mpsc::UnboundedReceiver<u32>,
    // 麦克风 PCM 来源（PC 侧联调注入；Android 上为 None）
    mut mic_rx: Option<mpsc::UnboundedReceiver<Vec<i16>>>,
    stop: Arc<Notify>,
) -> Result<(), String> {
    emit_state("negotiating", "CarLife 网关启动");

    // ---- CarLife 网关先行：协商期间 CarLife app 即可接入回环 ----
    // （触摸接收端已内化给通道任务，hub 只保留注入 Sender）
    let life_cfg = CarLifeConfig {
        width: cfg.width,
        height: cfg.height,
        fps: cfg.fps,
        voice: cfg.voice,
        // HU_INFO 里 required 的 btaddress/btname：用 iAP2 的稳定设备号当车机蓝牙 MAC
        bt_address: cfg.device_id.clone(),
        bt_name: "BMW".to_string(),
    };
    let (hub, mut life_events) = match cfg.md_host.clone() {
        Some(host) => CarLifeHub::connect(life_cfg, host, stop.clone())?,
        None => match CarLifeHub::start(life_cfg.clone(), stop.clone()) {
            Ok(v) => v,
            // **端口被本机 CarLife 占了 = 它才是监听方**：小米 CarLife/CarWith 在
            // 「车辆热点 → 开始连接」后自己 bind 7240~9340 等车机连入。此时我们改当客户端，
            // 直接拨 127.0.0.1 即可同机打通（2026-10-25 验证：两个进程抢 7240 会双双卡死）。
            Err(e) if e.contains("Address already in use") || e.contains("os error 98") => {
                emit_log(
                    "CARLIFE: 7240 已被本机 CarLife/CarWith 监听占用——自动改走「主动拨号 127.0.0.1」",
                );
                CarLifeHub::connect(life_cfg, "127.0.0.1".to_string(), stop.clone())?
            }
            Err(e) => return Err(e),
        },
    };
    let hub = Arc::new(hub);

    // 触摸/按键注入桥：AirPlay 事件通道 → CarLife 触摸通道
    {
        let hub = hub.clone();
        let stop_t = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_t.notified() => return,
                    t = touch_rx.recv() => match t {
                        Some(t) => hub.inject_touch(t.action, t.x, t.y),
                        None => return,
                    },
                    k = key_rx.recv() => match k {
                        Some(k) => hub.inject_hard_key(k),
                        None => return,
                    },
                }
            }
        });
    }

    // 麦克风上行：外部 PCM（PC 侧采集）→ CarLife VR 通道
    if let Some(mut mic_rx) = mic_rx.take() {
        let hub = hub.clone();
        let stop_m = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_m.notified() => return,
                    pcm = mic_rx.recv() => match pcm {
                        Some(pcm) => hub.inject_mic(&pcm),
                        None => return,
                    },
                }
            }
        });
    }
    // ---- 分发器：CarLife 事件 → 视频泵 / 音频泵 ----
    let (video_tx, mut video_rx) = mpsc::unbounded_channel::<CarLifeEvent>();
    let (audio_tx, audio_rx) = mpsc::unbounded_channel::<CarLifeEvent>();
    {
        let stop_d = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_d.notified() => return,
                    ev = life_events.recv() => match ev {
                        Some(ev) => {
                            let to_audio = matches!(
                                ev,
                                CarLifeEvent::AudioInit { .. }
                                    | CarLifeEvent::AudioStop { .. }
                                    | CarLifeEvent::AudioData { .. }
                            );
                            let to_video = matches!(
                                ev,
                                CarLifeEvent::VideoFrame { .. }
                                    | CarLifeEvent::EncoderResolution { .. }
                            );
                            if to_audio {
                                if audio_tx.send(ev).is_err() { return; }
                            } else if to_video {
                                if video_tx.send(ev).is_err() { return; }
                            } else {
                                match ev {
                                    CarLifeEvent::Handshake => emit_log("CARLIFE: 握手完成"),
                                    CarLifeEvent::ClientLeft { video: true } =>
                                        emit_log("CARLIFE: 视频客户端断开"),
                                    _ => {}
                                }
                            }
                        }
                        None => return,
                    },
                }
            }
        });
    }

    // ---- CARLIFE-ONLY：只验证 CarLife 通道（绑宿主 → 握手 → 8240 帧），完全不碰 AirPlay ----
    if cfg.carlife_only {
        emit_log("CARLIFE-ONLY: 进入只跑 CarLife 模式（不做蓝牙/AirPlay）");
        let mut frames: u64 = 0;
        let mut last_log = std::time::Instant::now();
        loop {
            tokio::select! {
                _ = stop.notified() => return Ok(()),
                ev = video_rx.recv() => match ev {
                    Some(CarLifeEvent::VideoFrame { data, .. }) => {
                        frames += 1;
                        if frames <= 3 || last_log.elapsed() >= Duration::from_secs(2) {
                            last_log = std::time::Instant::now();
                            emit_log(&format!("CARLIFE-ONLY: 累计 {frames} 帧，最近一帧 {}B", data.len()));
                        }
                    }
                    Some(CarLifeEvent::EncoderResolution { width, height }) =>
                        emit_log(&format!("CARLIFE-ONLY: 编码画布 {width}x{height}")),
                    Some(_) => {}
                    None => return Ok(()),
                },
            }
        }
    }

    // ---- 视频泵 ----
    let (screen_ready_tx, screen_ready_rx) = tokio::sync::oneshot::channel::<ScreenSender>();
    let video_stats = Arc::new(Mutex::new(PumpStat::default()));
    {
        let stop_v = stop.clone();
        let stats = video_stats.clone();
        tokio::spawn(async move {
            let mut sender: Option<ScreenSender> = None;
            // 屏幕流就绪前收到的编码尺寸先存着，就绪时补上
            let mut pending_size: Option<(u32, u32)> = None;
            let mut ready = Some(screen_ready_rx);
            let mut video_rx = video_rx;
            // 16ms tick：CarLife 没推流时用它按 ~60fps 发待机循环帧（真帧来了就以真帧为主）
            let mut tick = tokio::time::interval(Duration::from_millis(16));
            // CarLife 还没开始推流时，用待机卡片按 1 帧/秒占住屏幕流（避免车机黑屏/提前关通道）
            // 真帧自带的画布尺寸 / 是否已按真帧重发过 VideoConfig
            let mut real_size: Option<(u32, u32)> = None;
            let mut real_config_sent = false;
            // 缓存的真帧 SPS/PPS（屏幕流就绪前收到的帧被丢弃，但参数要留下）
            let mut cached_sps: Option<Vec<u8>> = None;
            let mut cached_pps: Option<Vec<u8>> = None;
            // 屏幕流就绪时刻（待机卡片的 1.2s 宽限从此刻算起）
            let mut stream_ready_at: Option<std::time::Instant> = None;
            let mut last_real_frame: Option<std::time::Instant> = None;
            let mut standby_idx: usize = 0;
            let mut standby_size_sent: Option<(u32, u32)> = None;
            // 待机节奏：前 2s 全速（60fps）让接收端锁住流，之后降到 15fps 省带宽；
            // 每 5s 从 IDR 重放一次，迟到的/卡住的解码器能自己恢复。
            let mut standby_started: Option<std::time::Instant> = None;
            let mut standby_tick: u32 = 0;
            let mut standby_last_idr = std::time::Instant::now();
            loop {
                tokio::select! {
                    _ = stop_v.notified() => return,
                    r = async {
                        match ready.as_mut() {
                            Some(rx) => Some(rx.await.ok()),
                            None => std::future::pending().await,
                        }
                    } => {
                        if let Some(mut s) = r.flatten() {
                            if let Some((w, h)) = pending_size.take() {
                                s.set_size(w, h);
                            }
                            sender = Some(s);
                            // 记录屏幕流就绪时刻：待机卡片要延后 1.2s 才考虑，
                            // 否则会把画布从目标尺寸切到母版尺寸（真帧来了再切回来）→ 尺寸抖动。
                            stream_ready_at = Some(std::time::Instant::now());
                        }
                        ready = None;
                    }
                    ev = video_rx.recv() => match ev {
                        Some(CarLifeEvent::EncoderResolution { width, height }) => {
                            // 手机回报实际编码尺寸 → 同步 AirPlay 画布（VideoConfig 宽高）。
                            // 必须在首帧引出 VideoConfig 之前生效，否则车机按旧画布解码。
                            if let Some(s) = sender.as_mut() {
                                s.set_size(width, height);
                                real_size = Some((width, height));
                            } else {
                                pending_size = Some((width, height));
                            }
                        }
                        Some(CarLifeEvent::VideoFrame { pts_us, data }) => {
                            // 先把 SPS/PPS 缓存下来：**即使此刻屏幕流还没就绪（帧被丢）也要缓存**，
                            // 否则等屏幕流就绪后收到的 P 帧里没有 SPS/PPS，就永远不会补发 VideoConfig，
                            // 画布会一直停在待机卡片的母版尺寸 → 车机永远母帧（2026-10-27 21:07 现场）。
                            if cached_sps.is_none() {
                                let nals = crate::screen::annexb_nals(&data);
                                let sps = nals.iter().find(|n| crate::screen::nal_type(n) == 7).copied();
                                let pps = nals.iter().find(|n| crate::screen::nal_type(n) == 8).copied();
                                if let (Some(sps), Some(pps)) = (sps, pps) {
                                    cached_sps = Some(sps.to_vec());
                                    cached_pps = Some(pps.to_vec());
                                }
                            }
                            if let Some(s) = sender.as_mut() {
                                // 首次真正拿到屏幕流时，按真帧 SPS/PPS + 实际编码尺寸重发 VideoConfig
                                if !real_config_sent {
                                    if let (Some(sps), Some(pps)) = (cached_sps.clone(), cached_pps.clone()) {
                                        if let Some((w, h)) = real_size { s.set_size(w, h); }
                                        let avcc = crate::screen::build_avcc(&sps, &pps);
                                        let _ = s.send_config(&avcc).await;
                                        emit_log(&format!("VIDEO: 按真帧重发 VideoConfig（画布 {:?}）", real_size));
                                        real_config_sent = true;
                                    }
                                }
                                let len = data.len() as u64;
                                // CarLife 媒体头的时间戳偶有 0/非单调，为 0 时用本机单调钟
                                let pts = if pts_us == 0 {
                                    crate::timing::monotonic_nanos() / 1_000
                                } else {
                                    pts_us
                                };
                                if s.send_video_annexb(&data, pts).await.is_err() {
                                    emit_state("ended", "屏幕流写失败（车机侧断开）");
                                    return;
                                }
                                last_real_frame = Some(std::time::Instant::now());
                                let mut st = stats.lock();
                                st.frames += 1;
                                st.bytes += len;
                            }
                            // 屏幕流未就绪：丢帧（SPS/PPS 已缓存，就绪后会补发 config）
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = tick.tick() => {
                        // 屏幕流 keepAlive 帧（明文、1s 节流；缺它 Audi MMI 3315 10.2s 拆轮）
                        if let Some(s) = sender.as_mut() {
                            let _ = s.maybe_send_keep_alive().await;
                            // CarLife 还没推流 → 按 ~60fps 播放待机循环（1 IDR + 59 个极小 P 帧）。
                            // 真帧到达后自动停用（1s 内有过真帧就不再插卡片）。
                            // 只在"从未收到过真帧、且屏幕流就绪已超过 1.2s"时插待机卡片：
                            //  · 真帧一来就永久停用，否则与真帧画布尺寸来回抖（解码器反复重建 → 母帧）；
                            //  · 1.2s 宽限是为了**根本不发卡片**——CarLife 正常 0.3s 内就出帧，
                            //    这样画布始终停在目标尺寸，日志也不会出现 2400x1080→1280x720→2400x1088 的抖动。
                            let stale = last_real_frame.is_none()
                                && stream_ready_at
                                    .map(|t| t.elapsed() >= Duration::from_millis(1200))
                                    .unwrap_or(false);
                            if stale {
                                let (w, h) = s.size();
                                if let Some(set) =
                                    crate::airplay::standby_for_aspect(w, h)
                                {
                                    if !set.frames.is_empty() {
                                        if standby_size_sent != Some((set.width, set.height)) {
                                            // 切到母版尺寸并重发 VideoConfig（画面就是母版分辨率）
                                            s.set_size(set.width, set.height);
                                            if let Some(first) = set.frames.first() {
                                                let nals = crate::screen::annexb_nals(first);
                                                let sps = nals.iter().find(|n| crate::screen::nal_type(n) == 7).copied();
                                                let pps = nals.iter().find(|n| crate::screen::nal_type(n) == 8).copied();
                                                if let (Some(sps), Some(pps)) = (sps, pps) {
                                                    let avcc = crate::screen::build_avcc(sps, pps);
                                                    let _ = s.send_config(&avcc).await;
                                                }
                                            }
                                            standby_size_sent = Some((set.width, set.height));
                                            standby_idx = 0;
                                            standby_tick = 0;
                                            standby_started = Some(std::time::Instant::now());
                                            standby_last_idr = std::time::Instant::now();
                                            emit_log(&format!(
                                                "AIRPLAY: 待机循环开始（母版 {}x{}，{} 帧；前 2s 60fps，之后 15fps，每 5s 补 IDR）",
                                                set.width,
                                                set.height,
                                                set.frames.len()
                                            ));
                                        }
                                        standby_tick = standby_tick.wrapping_add(1);
                                        let warmup = standby_started
                                            .map(|t| t.elapsed() < Duration::from_secs(2))
                                            .unwrap_or(true);
                                        // tick 是 16ms（≈60fps）：每 4 拍发一帧 = 15fps
                                        let mut due = warmup || standby_tick % 4 == 0;
                                        if standby_last_idr.elapsed() >= Duration::from_secs(5) {
                                            // 强制从 IDR 重放：接收端中途重启/丢包也能恢复画面
                                            standby_idx = 0;
                                            standby_last_idr = std::time::Instant::now();
                                            due = true;
                                        }
                                        if due {
                                            let frame = set.frames[standby_idx % set.frames.len()];
                                            let pts = crate::timing::monotonic_nanos() / 1_000;
                                            let _ = s.send_video_annexb(frame, pts).await;
                                            standby_idx = standby_idx.wrapping_add(1);
                                        }
                                    }
                                }
                                    }
                        }
                    }
                }
            }
        });
    }

    // ---- 音频泵：重采样 + 闪避混音 + RTP ----
    let (audio_out_tx, audio_out_rx) = tokio::sync::oneshot::channel::<AudioOut>();
    let audio_stats = Arc::new(Mutex::new(AudioPumpStat::default()));
    {
        let stop_a = stop.clone();
        let stats = audio_stats.clone();
        tokio::spawn(async move {
            let mut out: Option<AudioOut> = None;
            let mut ready = Some(audio_out_rx);
            let mut audio_rx = audio_rx;
            // 源格式余数缓冲（重采样跨包连续）
            let mut media_remain: Vec<i16> = Vec::new();
            let mut tts_remain: Vec<i16> = Vec::new();
            let mut media_fmt: (u32, u8) = (44_100, 2);
            let mut tts_fmt: (u32, u8) = (16_000, 1);
            // 48k 立体声混音缓冲
            let mut media_mix: VecDeque<i16> = VecDeque::with_capacity(48_000 * 2);
            let mut tts_mix: VecDeque<i16> = VecDeque::with_capacity(48_000 * 2);
            let mut duck_hold: u32 = 0;
            let mut gain: f32 = 1.0;
            // 音频发包节拍：**绝不漂移**。
            //
            // cardeck 的节奏天然正确——它由 AudioRecord.read() 的阻塞读取驱动，
            // 硬件按 48k 实时吐 352 帧，一包一发，永不累计误差。
            // 我们这里是「收 CarLife PCM → 自己重排节奏」，所以必须用绝对时刻对齐：
            //  - Burst：漏掉的拍**立刻补发**（Delay 会永久丢拍 → RTP 时钟持续落后于
            //    墙钟，接收端表现为 late/discontinuity 一直涨、听感断续）。
            //  - 补发仍受混音队列上限（~0.5s）约束，不会无限喷包。
            // 现场 2026-10-23 23:45：WinPlay 侧统计 packets 只有 ~68/s（应为 48000/352≈136/s），
            // 正好少一半，印证漏拍。
            let mut tick =
                tokio::time::interval(Duration::from_nanos((SPF as u64) * 1_000_000_000 / TARGET_RATE as u64));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Burst);
            let mut sync_tick = tokio::time::interval(Duration::from_secs(1));
            sync_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            // 发包速率自测：每秒记一行，联调时能直接看出节拍对不对。
            let mut sent_packets: u64 = 0;
            let mut rate_mark = std::time::Instant::now();
            let mut mixed = vec![0i16; SPF * 2];
            // 抗抖动预缓冲状态：false = 正在攒数据（发静音），true = 正在播放
            let mut audio_started = false;
            loop {
                tokio::select! {
                    _ = stop_a.notified() => return,
                    r = async {
                        match ready.as_mut() {
                            Some(rx) => Some(rx.await.ok()),
                            None => std::future::pending().await,
                        }
                    } => {
                        if let Some(a) = r.flatten() { out = Some(a); }
                        ready = None;
                    }
                    ev = audio_rx.recv() => match ev {
                        Some(CarLifeEvent::AudioInit { tts, rate, channels }) => {
                            if tts { tts_fmt = (rate.max(1), channels.max(1)); }
                            else { media_fmt = (rate.max(1), channels.max(1)); }
                            stats.lock().playing = true;
                        }
                        Some(CarLifeEvent::AudioStop { tts }) => {
                            if tts { tts_mix.clear(); } else { media_mix.clear(); }
                        }
                        Some(CarLifeEvent::AudioData { tts, rate, channels, data }) => {
                            let (fmt, remain, mix) = if tts {
                                (&mut tts_fmt, &mut tts_remain, &mut tts_mix)
                            } else {
                                (&mut media_fmt, &mut media_remain, &mut media_mix)
                            };
                            if rate > 0 && rate != fmt.0 { fmt.0 = rate; }
                            if channels >= 1 && channels <= 2 && channels != fmt.1 { fmt.1 = channels; }
                            push_resampled(&data, fmt.0, fmt.1, remain, mix);
                            // 缓冲上限 ~0.5s：过载丢旧（导航长语音不至于拖垮媒体）
                            let cap = (TARGET_RATE as usize) * 2 / 2;
                            while mix.len() > cap { mix.pop_front(); }
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = sync_tick.tick() => {
                        // RTP↔NTP 同步包（每秒；缺它车机照收 UDP 但喇叭无声）。
                        // 时间基与 TimingServer 的 timesync 应答同源（进程级单调钟）。
                        if let Some(a) = out.as_mut() {
                            let _ = a.send_sync_now(crate::timing::monotonic_nanos()).await;
                        }
                    }
                    _ = tick.tick() => {
                        if duck_hold > 0 { duck_hold -= 1; }
                        let tts_active = !tts_mix.is_empty();
                        if tts_active { duck_hold = 40; } // ~410ms 尾音保持
                        let target = if duck_hold > 0 { 0.25f32 } else { 1.0f32 };
                        gain += (target - gain) * 0.15;

                        // **抗抖动预缓冲**（2026-10-28 00:00）：CarLife 的 PCM 是突发到达的
                        // （一次 4096B ≈ 21ms），边到边播会让队列周期性见底 → 车机端欠载 =
                        // "稍微有点卡"。做法：
                        //   · 队列攒够 ~40ms 才开始消费（prebuf）；
                        //   · 播放中掉到 ~10ms 以下 → 回到"重新攒"状态（继续发静音，不消耗）；
                        // 发送节奏本来就是每 tick 一包（7.33ms），等于给接收端一个稳定流。
                        const SAMPLES_PER_MS: usize = (TARGET_RATE as usize / 1000) * 2; // 立体声
                        let prebuf = 40 * SAMPLES_PER_MS;
                        let low_water = 10 * SAMPLES_PER_MS;
                        if !audio_started {
                            if media_mix.len() >= prebuf {
                                audio_started = true;
                            }
                        } else if media_mix.len() < low_water && tts_mix.is_empty() {
                            audio_started = false;
                        }

                        for i in 0..SPF {
                            // 混音队列里是**交错立体声**（push_resampled 每帧压 L、R 两个样本）。
                            // 之前每帧只取一个样本再左右都填它——只消费了一半数据、还把立体声
                            // 压成单声道，听感就是沙沙声 + 断续（现场 2026-10-23 23:10 定位）。
                            let ml = if audio_started { take_or_zero(&mut media_mix) } else { 0 };
                            let mr = if audio_started { take_or_zero(&mut media_mix) } else { 0 };
                            let tl = take_or_zero(&mut tts_mix);
                            let tr = take_or_zero(&mut tts_mix);
                            let l = (ml as f32 * gain) as i32 + tl as i32;
                            let r = (mr as f32 * gain) as i32 + tr as i32;
                            mixed[i * 2] = l.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                            mixed[i * 2 + 1] = r.clamp(i16::MIN as i32, i16::MAX as i32) as i16;
                        }
                        stats.lock().ducking = duck_hold > 0;
                        if let Some(a) = out.as_mut() {
                            // 静音时也发包：保持 RTP 时钟连续（车机按包排播）
                            let before = a.packets_sent();
                            let _ = a.send_pcm(&mixed).await;
                            sent_packets += a.packets_sent().saturating_sub(before);
                        }
                        // 每秒报一次实际发包速率：48000/352 ≈ 136/s。低于它就是漏拍，
                        // 接收端会表现为 late/discontinuity 增长、听感断续。
                        if rate_mark.elapsed() >= Duration::from_secs(1) {
                            let secs = rate_mark.elapsed().as_secs_f64().max(0.001);
                            emit_log(&format!(
                                "AUDIO: 发包 {:.0}/s（应 {:.0}/s） 队列 media={}ms tts={}ms",
                                sent_packets as f64 / secs,
                                TARGET_RATE as f64 / SPF as f64,
                                media_mix.len() * 1000 / (TARGET_RATE as usize).max(1) / 2,
                                tts_mix.len() * 1000 / (TARGET_RATE as usize).max(1) / 2
                            ));
                            sent_packets = 0;
                            rate_mark = std::time::Instant::now();
                        }
                    }
                }
            }
        });
    }

    // ---- AirPlay 协商（控制 FD 已连接车机端口）----
    emit_state("negotiating", "AirPlay 协商开始");
    let (ctrl_r, ctrl_w) = control_stream.into_split();
    let params = AirplayParams {
        car_ip: cfg.car_ip.clone(),
        airplay_port: cfg.airplay_port,
        device_id: cfg.device_id.clone(),
        identity_path: cfg.identity_path.clone(),
        enable_audio: cfg.enable_audio,
        keep_alive_stats: cfg.keep_alive_stats,
        width: cfg.width,
        height: cfg.height,
        // 接收端 /info 报 0x0 时的触摸显示空间（PC 侧联调用 --touch-display 指定）
        touch_display: match (cfg.touch_display_w, cfg.touch_display_h) {
            (w, h) if w > 0 && h > 0 => Some((w, h)),
            _ => None,
        },
        // 与 CarLife 层共用：/info 报到车机真实尺寸后写入，手机回报实际值后再覆盖
        encoder_size: hub.encoder_size_handle(),
    };
    let outcome = airplay::negotiate(
        ctrl_r,
        ctrl_w,
        &params,
        factory,
        touch_tx,
        key_tx,
        stop.clone(),
    )
    .await?;

    // 屏幕/音频发送器交给泵
    let _ = screen_ready_tx.send(outcome.screen);
    if let Some(audio) = outcome.audio {
        let _ = audio_out_tx.send(audio);
    }

    // ---- 统计心跳（1s）----
    {
        let stop_s = stop.clone();
        let vstats = video_stats.clone();
        let astats = audio_stats.clone();
        tokio::spawn(async move {
            let mut last_frames = 0u64;
            let mut last_bytes = 0u64;
            let mut last_tick = tokio::time::Instant::now();
            loop {
                tokio::select! {
                    _ = stop_s.notified() => return,
                    _ = tokio::time::sleep(Duration::from_secs(1)) => {}
                }
                let (frames, bytes) = { let s = vstats.lock(); (s.frames, s.bytes) };
                let dt = last_tick.elapsed().as_secs_f64().max(0.001);
                let fps = ((frames - last_frames) as f64 / dt).round() as i64;
                let kbps = (((bytes - last_bytes) * 8) as f64 / dt / 1000.0).round() as i64;
                last_frames = frames;
                last_bytes = bytes;
                last_tick = tokio::time::Instant::now();
                let a = astats.lock();
                emit_stats(fps.max(0) as i32, frames as i64, kbps.max(0) as i64, a.playing, a.ducking);
            }
        });
    }

    // 等待停止信号
    stop.notified().await;
    emit_state("stopping", "引擎停止");
    Ok(())
}

// ---------------------------------------------------------------- 音频重采样

/// 把一包 PCM（LE i16 交错）线性重采样为 48kHz 立体声，推入混音缓冲。
///
/// 注：参考文档用简单线性插值；44.1k→48k 的镜像抑制对车机喇叭听感足够
/// （CarLife 音源本身已是有损编码后的 PCM，抗混叠收益有限）。后续如需
/// 更好音质可换 windowed-sinc（此函数是唯一改动点）。
fn push_resampled(
    raw: &[u8],
    src_rate: u32,
    src_ch: u8,
    remain: &mut Vec<i16>,
    out: &mut VecDeque<i16>,
) {
    let shorts = raw.len() / 2;
    for i in 0..shorts {
        remain.push(i16::from_le_bytes([raw[i * 2], raw[i * 2 + 1]]));
    }
    let ch = src_ch.max(1) as usize;
    let src_frames = remain.len() / ch;
    if src_frames == 0 || src_rate == 0 {
        return;
    }
    if src_rate == TARGET_RATE && ch == 2 {
        let total = src_frames * 2;
        for i in 0..total {
            out.push_back(remain[i]);
        }
        remain.drain(..total);
        return;
    }
    let ratio = src_rate as f64 / TARGET_RATE as f64;
    let dst_frames = ((src_frames as f64) / ratio) as usize;
    let read = |idx: usize, lane: usize| -> i16 {
        if ch == 1 {
            remain[idx]
        } else {
            remain[idx * ch + lane]
        }
    };
    for i in 0..dst_frames {
        let pos = i as f64 * ratio;
        let i0 = (pos as usize).min(src_frames - 1);
        let i1 = (i0 + 1).min(src_frames - 1);
        let frac = (pos - i0 as f64) as f32;
        let l = if ch == 1 {
            let m0 = read(i0, 0) as f32;
            let m1 = read(i1, 0) as f32;
            m0 + (m1 - m0) * frac
        } else {
            let l0 = read(i0, 0) as f32;
            let l1 = read(i1, 0) as f32;
            l0 + (l1 - l0) * frac
        };
        let r = if ch == 1 {
            l
        } else {
            let r0 = read(i0, 1) as f32;
            let r1 = read(i1, 1) as f32;
            r0 + (r1 - r0) * frac
        };
        out.push_back(l.clamp(i16::MIN as f32, i16::MAX as f32) as i16);
        out.push_back(r.clamp(i16::MIN as f32, i16::MAX as f32) as i16);
    }
    // 已消费的源帧丢弃（浮点取整误差在余数里可忽略）
    let consumed = (dst_frames as f64 * ratio) as usize;
    let consumed = consumed.min(src_frames) * ch;
    remain.drain(..consumed);
}

fn take_or_zero(q: &mut VecDeque<i16>) -> i16 {
    q.pop_front().unwrap_or(0)
}

// ---------------------------------------------------------------- 统计

#[derive(Default)]
struct PumpStat {
    frames: u64,
    bytes: u64,
}

#[derive(Default)]
struct AudioPumpStat {
    playing: bool,
    ducking: bool,
}




