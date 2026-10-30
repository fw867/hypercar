//! 引擎编排：**本机自绘界面（自采自编）→ AirPlay 推流** 的粘合层。
//!
//! 数据面：
//!  - 视频：Kotlin 侧 MediaCodec 出来的 H.264 Annex B（nativePushVideoFrame）→ ScreenSender
//!  - 音频：Kotlin 侧采集的 PCM（nativePushAudioPcm）→ 重采样 48kHz 立体声 → RTP
//!
//! 控制面：Kotlin 交来「已连上车机 AirPlay 端口的 control FD」+ socket 供给器，
//! 其余协商（/info、pair-verify、SETUP、RECORD、三条心跳）全部在本层闭环。
//!
//! 任务拓扑（单分发器 → 双泵，避免多消费者抢同一接收器）：
//!   nativePush* ─events─▶ demux ─video─▶ video pump ─▶ ScreenSender
//!                         └──audio─▶ audio pump ─▶ AudioOut(RTP)

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::{mpsc, Notify};

use crate::airplay::{self, AirplayParams, SocketProvider, TouchInput};
use crate::audio_out::AudioOut;
use crate::screen::ScreenSender;
use crate::{emit_log, emit_stats, emit_state};

/// 外部（Kotlin 侧）投喂给引擎的数据事件。
pub enum SourceEvent {
    RequestHostUi,
    /// 视频编码器产出一帧（H.264 Annex B，可能含 SPS/PPS/IDR）。
    VideoFrame { pts_us: u64, data: Vec<u8> },
    /// 编码器画布尺寸变化（对齐 AirPlay VideoConfig 宽高）。
    EncoderResolution { width: u32, height: u32 },
    /// 音频流开始（tts=true 表示提示音，参与闪避混音）。
    AudioInit { tts: bool, rate: u32, channels: u8 },
    /// 音频流停止。
    AudioStop { tts: bool },
    /// 一包 PCM（16-bit 小端，交错）。
    AudioData { tts: bool, rate: u32, channels: u8, data: Vec<u8> },
    /// 视频源断开（自绘界面停止推流）。
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
    #[serde(default)]
    pub receiver_id: Option<String>,
    #[serde(default)]
    pub receiver_previously_connected: bool,
    /// 接收端 `/info` 报 0x0 时的**触摸显示空间**宽/高（0 = 用协商的屏幕尺寸）。
    ///
    /// 旧接收端未上报有效像素尺寸时，HID 坐标按其配置的窗口空间换算
    /// （实测 2732x794）；不指定就会整体缩放偏移，表现为「按钮很难点上」。
    #[serde(default)]
    pub touch_display_w: u32,
    #[serde(default)]
    pub touch_display_h: u32,
    /// PC 接收端窗口在桌面上的矩形（HID 全局坐标），用于 /info 报 0x0 的情况。
    #[serde(default)]
    pub touch_left: u32,
    #[serde(default)]
    pub touch_top: u32,
    #[serde(default)]
    pub touch_right: u32,
    #[serde(default)]
    pub touch_bottom: u32,
}

fn default_true() -> bool {
    true
}

const TARGET_RATE: u32 = 48_000;
const SPF: usize = 352; // AirPlay 每包样本帧（512 在真车无声）

/// 引擎主入口：control FD → AirPlay 协商 → 双向泵。
///
/// 数据来源由 Kotlin 侧主动投喂（`nativePushVideoFrame` / `nativePushAudioPcm`），
/// 本层不再起任何随车 App 的回环网关：帧与音频都由 Kotlin 侧投喂。
#[allow(clippy::too_many_arguments)]
pub async fn run_engine(
    cfg: EngineConfig,
    control_stream: tokio::net::TcpStream,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    mut source_rx: mpsc::UnboundedReceiver<SourceEvent>,
    stop: Arc<Notify>,
    touch_size: Arc<Mutex<(u32, u32)>>,
) -> Result<(), String> {
    emit_state("negotiating", "AirPlay 会话启动");
    // 触摸/按键通道：CarPlay 事件通道把车机操作送到这里，P1 起交给自绘 UI 处理。
    let _ = (&touch_tx, &key_tx);

    // ---- 分发器：外部事件 → 视频泵 / 音频泵 ----
    let (host_ui_tx, mut host_ui_rx) = mpsc::channel::<()>(1);
    let (video_tx, video_rx) = mpsc::unbounded_channel::<SourceEvent>();
    let (audio_tx, audio_rx) = mpsc::unbounded_channel::<SourceEvent>();
    {
        let stop_d = stop.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_d.notified() => return,
                    ev = source_rx.recv() => match ev {
                        Some(SourceEvent::RequestHostUi) => { let _ = host_ui_tx.try_send(()); }
                        Some(ev) => {
                            let to_audio = matches!(
                                ev,
                                SourceEvent::AudioInit { .. }
                                    | SourceEvent::AudioStop { .. }
                                    | SourceEvent::AudioData { .. }
                            );
                            if to_audio {
                                if audio_tx.send(ev).is_err() { return; }
                            } else if video_tx.send(ev).is_err() {
                                return;
                            }
                        }
                        None => return,
                    },
                }
            }
        });
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
            // 编码器真实尺寸（最后一次 EncoderResolution）。VideoConfig/画布都以它为准；
            // /info 可能先写入车机尺寸，编码器回报后覆盖。
            let mut encoder_size: Option<(u32, u32)> = None;
            let mut ready = Some(screen_ready_rx);
            let mut video_rx = video_rx;
            // 16ms tick：仅用于 keepAlive 节拍（真帧由视频泵推送）
            let mut tick = tokio::time::interval(Duration::from_millis(16));
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
                        }
                        ready = None;
                    }
                    ev = video_rx.recv() => match ev {
                        Some(SourceEvent::EncoderResolution { width, height }) => {
                            // 手机回报实际编码尺寸 → 同步 AirPlay 画布（VideoConfig 宽高）。
                            // 必须在首帧引出 VideoConfig 之前生效，否则车机按旧画布解码。
                            encoder_size = Some((width, height));
                            if let Some(s) = sender.as_mut() {
                                s.set_size(width, height);
                            } else {
                                pending_size = Some((width, height));
                            }
                        }
                        Some(SourceEvent::VideoFrame { pts_us, data }) => {
                            if let Some(s) = sender.as_mut() {
                                let len = data.len() as u64;
                                // 编码尺寸变化时先切画布，保证随后 send_config 标注正确。
                                if let Some((w, h)) = encoder_size {
                                    if s.size() != (w, h) {
                                        s.set_size(w, h);
                                    }
                                }
                                // 上游时间戳可能为 0/非单调，为 0 时用本机单调钟
                                // 直接当 PTS 会让车机按 0 排播 → 卡顿/丢帧。为 0 时用本机单调钟。
                                let pts = if pts_us == 0 {
                                    crate::timing::monotonic_nanos() / 1_000
                                } else {
                                    pts_us
                                };
                                if s.send_video_annexb(&data, pts).await.is_err() {
                                    emit_state("ended", "屏幕流写失败（车机侧断开）");
                                    return;
                                }
                                let mut st = stats.lock();
                                st.frames += 1;
                                st.bytes += len;
                            }
                            // 屏幕流未就绪：丢帧（AirPlay 会话可能晚于画面就绪）
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = tick.tick() => {
                        // 屏幕流 keepAlive 帧（明文、1s 节流；缺它 Audi MMI 3315 10.2s 拆轮）
                        if let Some(s) = sender.as_mut() {
                            let _ = s.maybe_send_keep_alive().await;
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
            let mut last_audio_at: Option<std::time::Instant> = None;
            let mut gain: f32 = 1.0;
            // 音频发包节拍：**绝不漂移**。
            //
            // cardeck 的节奏天然正确——它由 AudioRecord.read() 的阻塞读取驱动，
            // 硬件按 48k 实时吐 352 帧，一包一发，永不累计误差。
    // 我们这里是「收 PCM → 自己重排节奏」，所以必须用绝对时刻对齐：
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
                        Some(SourceEvent::AudioInit { tts, rate, channels }) => {
                            if tts { tts_fmt = (rate.max(1), channels.max(1)); }
                            else { media_fmt = (rate.max(1), channels.max(1)); }
                            stats.lock().playing = true;
                        }
                        Some(SourceEvent::AudioStop { tts }) => {
                            if tts { tts_mix.clear(); } else { media_mix.clear(); last_audio_at = None; }
                        }
                        Some(SourceEvent::AudioData { tts, rate, channels, data }) => {
                            let (fmt, remain, mix) = if tts {
                                (&mut tts_fmt, &mut tts_remain, &mut tts_mix)
                            } else {
                                (&mut media_fmt, &mut media_remain, &mut media_mix)
                            };
                            if rate > 0 && rate != fmt.0 { fmt.0 = rate; }
                            if channels >= 1 && channels <= 2 && channels != fmt.1 { fmt.1 = channels; }
                            // 采集流在暂停/静音时仍会持续送 0 PCM；只有真有信号才刷新
                            // “播放中”，否则 3s 后自动回待机，音频芯片不会卡在播放中。
                            if pcm_has_signal(&data) {
                                stats.lock().playing = true;
                                last_audio_at = Some(std::time::Instant::now());
                            }
                            push_resampled(&data, fmt.0, fmt.1, remain, mix);
                            // 缓冲上限 ~0.5s：过载丢旧（导航长语音不至于拖垮媒体）
                            let cap = (TARGET_RATE as usize) * 2 / 2;
                            while mix.len() > cap { mix.pop_front(); }
                        }
                        Some(_) => {}
                        None => return,
                    },
                    _ = sync_tick.tick() => {
                        // 2s 没有新 PCM 就回待机，避免停止播放后芯片一直显示播放中
                        if last_audio_at.map(|t| t.elapsed() >= Duration::from_secs(3)).unwrap_or(true) {
                            stats.lock().playing = false;
                        }
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

                        for i in 0..SPF {
                            // 混音队列里是**交错立体声**（push_resampled 每帧压 L、R 两个样本）。
                            // 之前每帧只取一个样本再左右都填它——只消费了一半数据、还把立体声
                            // 压成单声道，听感就是沙沙声 + 断续（现场 2026-10-23 23:10 定位）。
                            let ml = take_or_zero(&mut media_mix);
                            let mr = take_or_zero(&mut media_mix);
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
    // 编码画布尺寸：/info 报到车机真实尺寸后写入，编码器回报后覆盖
    let encoder_size: Arc<Mutex<(u32, u32)>> =
        Arc::new(Mutex::new((cfg.width.max(2), cfg.height.max(2))));
    let params = AirplayParams {
        car_ip: cfg.car_ip.clone(),
        airplay_port: cfg.airplay_port,
        device_id: cfg.device_id.clone(),
        identity_path: cfg.identity_path.clone(),
        receiver_id: cfg.receiver_id.clone(),
        receiver_previously_connected: cfg.receiver_previously_connected,
        enable_audio: cfg.enable_audio,
        keep_alive_stats: cfg.keep_alive_stats,
        width: cfg.width,
        height: cfg.height,
        // 接收端 /info 报 0x0 时的触摸显示空间（PC 侧联调用 --touch-display 指定）
        touch_display: match (cfg.touch_display_w, cfg.touch_display_h) {
            (w, h) if w > 0 && h > 0 => Some((w, h)),
            _ => None,
        },
        touch_rect: if cfg.touch_right > cfg.touch_left && cfg.touch_bottom > cfg.touch_top {
            Some((cfg.touch_left, cfg.touch_top, cfg.touch_right, cfg.touch_bottom))
        } else {
            None
        },
        // 与编码侧共用：/info 报到车机真实尺寸后写入，编码器回报后再覆盖
        encoder_size: encoder_size.clone(),
        touch_size: touch_size.clone(),
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

    // Requests share the encrypted control channel, with their own CSeq; feedback continues independently.
    let _keepalive = outcome.keepalive;
    let control = outcome.control;
    let stop_ui = stop.clone();
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = stop_ui.notified() => return,
                request = host_ui_rx.recv() => {
                    if request.is_none() { return; }
                    let body = crate::bplist::encode(&crate::bplist::Value::dict(vec![
                        ("type", crate::bplist::Value::str("requestUI")),
                    ]));
                    match control.request("POST", "/command", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await {
                        Ok(reply) if reply.status() == 200 => emit_log("AIRPLAY: requestUI accepted; CarPlay session/audio retained"),
                        Ok(reply) => emit_log(&format!("AIRPLAY: requestUI rejected: {}", reply.first_line)),
                        Err(e) => emit_log(&format!("AIRPLAY: requestUI failed: {e}")),
                    }
                }
            }
        }
    });

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

/// 判断一段 PCM16LE 是否有实际信号（忽略采集链路的底噪/全 0 静音）。
fn pcm_has_signal(raw: &[u8]) -> bool {
    raw.chunks_exact(2).any(|c| {
        let s = i16::from_le_bytes([c[0], c[1]]);
        s == i16::MIN || s.unsigned_abs() > 300
    })
}

/// 把一包 PCM（LE i16 交错）线性重采样为 48kHz 立体声，推入混音缓冲。
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





