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
    CarWithTouch(TouchInput),
    RequestCarWithKeyFrame,
    ResetVideoSource,
    RequestHostUi,
    CancelSelfAsr(u64),
    RequestAudioOwnership,
    /// 视频编码器产出一帧（H.264 Annex B，可能含 SPS/PPS/IDR）。
    VideoFrame { pts_us: u64, data: Vec<u8> },
    VideoFrameTagged { pts_us:u64, data:Vec<u8>, codec:crate::video_format::VideoCodec },
    /// 编码器画布尺寸变化（对齐 AirPlay VideoConfig 宽高）。
    EncoderResolution { width: u32, height: u32 },
    /// 音频流开始（tts=true 表示提示音，参与闪避混音）。
    AudioInit { tts: bool, rate: u32, channels: u8 },
    VoiceAudioInit { rate:u32, channels:u8 },
    VoiceAudioData { rate:u32, channels:u8, data:Vec<u8> },
    VoiceAudioStop,
    AuxFallback { kind:crate::aux_setup::Kind, samples:Vec<i16> },
    /// 音频流停止。
    AudioStop { tts: bool },
    /// 一包 PCM（16-bit 小端，交错）。
    AudioData { tts: bool, rate: u32, channels: u8, data: Vec<u8> },
}

/// 引擎配置（SettingsStore.engineConfigJson 的反序列化目标）。
#[derive(Debug, Clone, serde::Deserialize)]
pub struct EngineConfig {
    pub device_id: String,
    #[serde(default)]
    pub wifi_mac_address: Option<String>,
    #[serde(default)]
    pub device_name: String,
    pub car_ip: String,
    pub airplay_port: u16,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    #[serde(default = "default_true")]
    pub enable_audio: bool,
    #[serde(default)]
    pub audio_codec: String,
    #[serde(default)]
    pub video_codec: String,
    #[serde(default)]
    pub bmw_capabilities: bool,
    #[serde(default)]
    pub microphone_probe: bool,
    #[serde(default)]
    pub self_microphone_asr: bool,
    #[serde(skip)]
    pub input_generation: u64,
    #[serde(default)]
    pub carwith_microphone: bool,
    #[serde(default)]
    pub keep_alive_stats: bool,
    #[serde(default)]
    pub bmw_box_bootstrap: bool,
    #[serde(default)]
    pub mbux_screen_bootstrap: bool,
    #[serde(default)]
    pub id9_screen_bootstrap: bool,
    #[serde(default)]
    pub id9_disable_buffered_audio: bool,
    #[serde(default)]
    pub nbt_audio_first: bool,
    #[serde(default)]
    pub mbux_media_compat: bool,
    /// CarWith 模式（2026-11-03）：视频/音频改由本机 CarLife（CarWith）的 H.264/PCM 提供，
    /// 不再使用自绘 UI 的编码器帧。
    #[serde(default)]
    pub carlife_source: bool,
    #[serde(default)]
    pub carwith_navigation_token: u64,
    #[serde(default)]
    pub carwith_navigation_enabled: bool,
    #[serde(default)]
    pub carwith_stretch: bool,
    #[serde(default)]
    pub screen_resource_lifecycle: bool,
    #[serde(default)]
    pub desktop_receiver: bool,
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
    mut key_rx: mpsc::UnboundedReceiver<u32>,
    mut source_rx: mpsc::UnboundedReceiver<SourceEvent>,
    shutdown: Arc<crate::shutdown::EngineShutdown>,
    touch_size: Arc<Mutex<(u32, u32)>>,
) -> Result<(), String> {
    let stop = shutdown.signal.clone();
    let mut tasks = EngineTasks::default();
    let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
    emit_state("negotiating", "AirPlay 会话启动");
    // 触摸/按键通道：CarPlay 事件通道把车机操作送到这里，P1 起交给自绘 UI 处理。
    let _ = (&touch_tx, &key_tx);

    // ---- 分发器：外部事件 → 视频泵 / 音频泵 ----
    let (host_ui_tx, mut host_ui_rx) = mpsc::channel::<()>(1);
    let (audio_claim_tx, mut audio_claim_rx) = mpsc::channel::<(bool, std::time::Instant)>(4);
    let (video_tx, video_rx) = mpsc::unbounded_channel::<SourceEvent>();
    let (audio_tx, audio_rx) = mpsc::unbounded_channel::<SourceEvent>();
    let fallback_audio_tx=audio_tx.clone();
    let auxiliary_input=Arc::new(crate::aux_audio::Input::default());

    // ---- CarWith 模式：把本机 CarLife（CarWith）的 H.264 / PCM 灌进同一条事件流 ----
    // 与自绘 UI 的区别只在"源"：视频帧来源换成 CarLife 的 8240 通道，音频换成 9240/9241/9242。
    // 引擎下游（screen/audio pump、触摸反向）完全复用，不需要第二套实现。
    // EngineTasks owns the hub. Restarting creates a fresh source immediately;
    // a time-based global guard also suppressed valid reconnects and mode switches.
    let carwith_display = cfg.carlife_source.then(|| Arc::new(Mutex::new((cfg.width, cfg.height))));
    let microphone_probe = if cfg.carlife_source {
        cfg.carwith_microphone.then(|| Arc::new(crate::mic_probe::ProbeControl::for_carwith()))
    } else if cfg.self_microphone_asr {
        Some(Arc::new(crate::mic_probe::ProbeControl::for_self_asr(cfg.input_generation)))
    } else { cfg.microphone_probe.then(|| Arc::new(crate::mic_probe::ProbeControl::new())) };
    let mut carwith_hub = None;
    if cfg.carlife_source {
        let life_cfg = crate::carlife::CarLifeConfig {
            width: cfg.width,
            height: cfg.height,
            fps: cfg.fps,
            bt_address: cfg.device_id.clone(),
            bt_name: "HyperCar".to_string(),
            navigation_updates: cfg.carwith_navigation_enabled,
            microphone: microphone_probe.clone(),
        };
        match crate::carlife::CarLifeHub::connect(life_cfg, "127.0.0.1".to_string(), stop.clone(), carwith_display.clone()) {
            Ok((hub, mut life_events)) => {
                let hub = Arc::new(hub);
                carwith_hub = Some(hub.clone());
                let carwith_touch_size = touch_size.clone();
                let vtx = video_tx.clone();
                let atx = audio_tx.clone();
                let voice_keys = key_tx.clone();
                let navigation_token = cfg.carwith_navigation_token;
                tasks.spawn(async move {
                    // Keep channel senders and the hub alive for the entire source task.
                    let _hub = hub;
                    while let Some(ev) = life_events.recv().await {
                        match ev {
                            CarLifeEvent::SourceReset => {
                                crate::emit_carwith_navigation(navigation_token,None);
                                let _ = voice_keys.send(crate::voice_key::RESET); let _ = vtx.send(SourceEvent::ResetVideoSource);
                                let _=atx.send(SourceEvent::AudioStop{tts:true});
                                let _=atx.send(SourceEvent::VoiceAudioStop);
                            }
                            CarLifeEvent::Navigation(update) => crate::emit_carwith_navigation(navigation_token,update.as_ref()),
                            CarLifeEvent::VideoFrame { pts_us, data } => {
                                let _ = vtx.send(SourceEvent::VideoFrame { pts_us, data });
                            }
                            CarLifeEvent::VideoFrameTagged { pts_us, data, codec } => {
                                let _=vtx.send(SourceEvent::VideoFrameTagged {pts_us,data,codec});
                            }
                            CarLifeEvent::EncoderResolution { width, height } => {
                                *carwith_touch_size.lock() = (width, height);
                                let _ = vtx.send(SourceEvent::EncoderResolution { width, height });
                            }
                            CarLifeEvent::VoiceAudioInit {rate,channels} => {
                                let _=atx.send(SourceEvent::VoiceAudioInit {rate,channels});
                            }
                            CarLifeEvent::VoiceAudioData {rate,channels,data} => {
                                let _=atx.send(SourceEvent::VoiceAudioData {rate,channels,data});
                            }
                            CarLifeEvent::VoiceAudioStop => {let _=atx.send(SourceEvent::VoiceAudioStop);}
                            CarLifeEvent::AudioInit { tts, rate, channels } => {
                                let _ = atx.send(SourceEvent::AudioInit { tts, rate, channels });
                            }
                            CarLifeEvent::AudioStop { tts } => {
                                let _ = atx.send(SourceEvent::AudioStop { tts });
                            }
                            CarLifeEvent::AudioData { tts, rate, channels, data } => {
                                let _ = atx.send(SourceEvent::AudioData { tts, rate, channels, data });
                            }
                            CarLifeEvent::Handshake => emit_log("CARLIFE: 握手完成（CarWith）"),
                            CarLifeEvent::ClientLeft { video } => {
                                emit_log(&format!("CARLIFE: 源端断开 video={video}"));
                            }
                        }
                    }
                });
            }
            Err(error) => emit_log(&format!("CARLIFE: CarWith 模式连接失败：{error}")),
        }
    }
    let voice_probe = if cfg.carlife_source { None } else { microphone_probe.clone() };
    let key_stop=stop.clone();
    let voice_hub=carwith_hub.clone();
    let carwith_voice=cfg.carlife_source;
    let voice_auxiliary_input=auxiliary_input.clone();
    tasks.spawn(async move {
        let mut voice=crate::voice_key::VoiceKey::default();
        let clock=std::time::Instant::now();
        loop {
            tokio::select! {
                biased;
                _=key_stop.notified()=>return,
                key=key_rx.recv()=>match key {
                    Some(crate::voice_key::RESET)=>voice.reset(),
                    Some(key)=>{
                        if let Some(action)=crate::voice_key::action(key) {
                            if voice.receive(action,clock.elapsed().as_millis() as u64) {
                                crate::emit_log(&format!("VOICE-KEY: action=Start source={}",if voice_probe.is_some() {"car-microphone-probe"} else if carwith_voice {"CarWith"} else {"phone-assistant"}));
                                if let Some(probe) = voice_probe.as_ref() {
                                    probe.toggle();
                                } else if carwith_voice {
                                    if let Some(hub)=voice_hub.as_ref(){voice_auxiliary_input.mark_user_intent();hub.voice_start();}
                                } else {crate::emit_voice_assistant();}
                            }
                        } else {crate::emit_media_key(key);}
                    },
                    None=>return,
                }
            }
        }
    });
    {
        let stop_d = stop.clone();
        let claims = audio_claim_tx.clone();
        let microphone_cancel=microphone_probe.clone();
        tasks.spawn(async move {
            loop {
                tokio::select! {
                    _ = stop_d.notified() => return,
                    ev = source_rx.recv() => match ev {
                        Some(SourceEvent::RequestCarWithKeyFrame) => {
                            if let Some(hub) = &carwith_hub { hub.request_keyframe(); }
                        }
                        Some(SourceEvent::CarWithTouch(t)) => {
                            if let Some(hub) = &carwith_hub { hub.inject_touch(t.action,t.x,t.y); }
                        }
                        Some(SourceEvent::CancelSelfAsr(id)) => {
                            if let Some(mic)=&microphone_cancel {mic.cancel_asr(id);}
                        }
                        Some(SourceEvent::RequestHostUi) => { let _ = host_ui_tx.try_send(()); }
                        Some(SourceEvent::RequestAudioOwnership) => { let _ = claims.try_send((true, std::time::Instant::now())); }
                        Some(ev) => {
                            let to_audio = matches!(
                                ev,
                                SourceEvent::AudioInit { .. }
                                    | SourceEvent::VoiceAudioInit { .. }
                                    | SourceEvent::VoiceAudioData { .. }
                                    | SourceEvent::VoiceAudioStop
                                    | SourceEvent::AuxFallback{..}
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
    let (screen_ready_tx, screen_ready_rx) = mpsc::channel::<crate::screen_session::ScreenCommand>(2);
    let video_stats = Arc::new(Mutex::new(PumpStat::default()));
    tasks.spawn(video_pump(stop.clone(), video_stats.clone(), cfg.screen_resource_lifecycle,
        pipeline.clone(), screen_ready_rx, video_rx, cfg.carlife_source && cfg.video_codec == "hevc", cfg.fps));

    // ---- 音频泵：重采样 + 闪避混音 + RTP ----
    let (audio_out_tx, audio_out_rx) = tokio::sync::oneshot::channel::<AudioOut>();
    let audio_stats = Arc::new(Mutex::new(AudioPumpStat::default()));
    let (main_audio_tx, main_audio_rx) = mpsc::unbounded_channel();
    tasks.spawn(audio_pump(stop.clone(),audio_stats.clone(),audio_claim_tx.clone(),audio_out_rx,audio_rx,main_audio_rx,auxiliary_input.clone()));

    // ---- AirPlay 协商（控制 FD 已连接车机端口）----
    {
        let health=pipeline.clone(); let vs=video_stats.clone(); let aus=audio_stats.clone(); let signal=stop.clone();
        tasks.spawn(async move {
            let mut last_key=String::new();
            let mut last_audio_bytes=0u64;
            let mut last_audio_tick=tokio::time::Instant::now();
            let mut last_log=tokio::time::Instant::now()-Duration::from_secs(10);
            loop {
                let mut snapshot=health.lock().clone();
                { let v=vs.lock(); snapshot.video_source_units=v.source_units; snapshot.video_frames_written=v.frames; }
                {
                    let a=aus.lock();
                    snapshot.audio_packets_written=a.packets; snapshot.audio_signal_packets=a.signal_packets;
                    snapshot.audio_codec=a.codec.into();
                    snapshot.audio_kbps=(a.payload_bytes.saturating_sub(last_audio_bytes) as f64 * 8.0 /
                        last_audio_tick.elapsed().as_secs_f64().max(0.001) / 1000.0).round() as u64;
                    last_audio_bytes=a.payload_bytes;
                    last_audio_tick=tokio::time::Instant::now();
                }
                snapshot.wifi_iap2=crate::wifi_iap2::phase(snapshot.wifi_id);
                let now_ms=crate::timing::monotonic_nanos()/1_000_000;
                snapshot.timing.refresh_age(now_ms);
                if let Some(tcp)=snapshot.video_tcp.as_mut() {tcp.age_ms=now_ms.saturating_sub(tcp.sampled_ms);}
                if let Ok(json)=serde_json::to_string(&snapshot) { crate::emit_pipeline(&json); }
                let key=snapshot.stage_key();
                if key!=last_key || last_log.elapsed()>=Duration::from_secs(10) {
                    emit_log(&snapshot.summary()); last_key=key; last_log=tokio::time::Instant::now();
                }
                tokio::select! { _=signal.notified()=>return, _=tokio::time::sleep(Duration::from_secs(1))=>{} }
            }
        });
    }
    emit_state("negotiating", "AirPlay 协商开始");
    let (ctrl_r, ctrl_w) = control_stream.into_split();
    // 编码画布尺寸：/info 报到车机真实尺寸后写入，编码器回报后覆盖
    let encoder_size: Arc<Mutex<(u32, u32)>> =
        Arc::new(Mutex::new((cfg.width.max(2), cfg.height.max(2))));
    let params = AirplayParams {
        car_ip: cfg.car_ip.clone(),
        airplay_port: cfg.airplay_port,
        device_id: cfg.device_id.clone(),
        wifi_mac_address: cfg.wifi_mac_address.clone(),
        device_name: if cfg.device_name.trim().is_empty() { "HyperCar".into() } else { cfg.device_name.clone() },
        identity_path: cfg.identity_path.clone(),
        receiver_id: cfg.receiver_id.clone(),
        receiver_previously_connected: cfg.receiver_previously_connected,
        enable_audio: cfg.enable_audio,
        audio_codec: cfg.audio_codec.clone(),
        video_codec: cfg.video_codec.clone(),
        bmw_capabilities: cfg.bmw_capabilities,
        microphone_probe,
        keep_alive_stats: cfg.keep_alive_stats,
        bmw_box_bootstrap: cfg.bmw_box_bootstrap,
        mbux_screen_bootstrap: cfg.mbux_screen_bootstrap,
        id9_screen_bootstrap: cfg.id9_screen_bootstrap,
        id9_disable_buffered_audio: cfg.id9_disable_buffered_audio,
        nbt_audio_first: cfg.nbt_audio_first,
        mbux_media_compat: cfg.mbux_media_compat,
        desktop_receiver: cfg.desktop_receiver,
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
        carwith_display,
        carwith_stretch: cfg.carlife_source && cfg.carwith_stretch,
        screen_resource_lifecycle: cfg.screen_resource_lifecycle,
        pipeline: pipeline.clone(),
    };
    let outcome = tokio::select! {
        biased;
        _ = shutdown.wait() => return Ok(()),
        result = airplay::negotiate(
        ctrl_r,
        ctrl_w,
        &params,
        factory,
        touch_tx,
        key_tx,
        stop.clone(),
        screen_ready_tx,
        ) => result?,
    };

    // Requests share the encrypted control channel, with their own CSeq; feedback continues independently.
    let mut auxiliary=outcome.aux_audio.map(|session|crate::aux_audio::Controller::spawn(session,auxiliary_input,main_audio_tx,fallback_audio_tx));
    let keepalive = outcome.keepalive;
    let mut teardown = outcome.teardown;
    let resource_session = outcome.resource_session;
    let audio_stream_type = outcome.audio.as_ref().map(|audio| audio.stream_type());
    let had_audio = audio_stream_type.is_some();
    let audio_resource = outcome.audio_resource;
    let stop_audio_claim = stop.clone();
    tasks.spawn(async move {
        loop {
            tokio::select! {
                _ = stop_audio_claim.notified() => return,
                request = audio_claim_rx.recv() => {
                    let Some(first) = request else { return; };
                    let intent = fresh_audio_claim(first, &mut audio_claim_rx);
                    if let Some(user) = intent.filter(|_| had_audio) {
                        tokio::select! {
                            _ = stop_audio_claim.notified() => return,
                            _ = audio_resource.request(user) => {}
                        }
                    }
                }
            }
        }
    });
    let control = outcome.control;
    let close_control = control.clone();
    let stop_ui = stop.clone();
    tasks.spawn(async move {
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
    if let Some(audio) = outcome.audio {
        let _ = audio_out_tx.send(audio);
    }

    // ---- 统计心跳（1s）----
    {
        let stop_s = stop.clone();
        let vstats = video_stats.clone();
        let astats = audio_stats.clone();
        tasks.spawn(async move {
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
    shutdown.wait().await;
    emit_state("stopping", "引擎停止");
    // 停媒体泵后才发业务 TEARDOWN，控制通道保留到应答/超时，最后再解绑网络。
    let remaining_auxiliary=if let Some(auxiliary)=auxiliary.as_mut() {auxiliary.shutdown().await} else {Vec::new()};
    tasks.shutdown().await;
    drop(keepalive);
    if let Some(session) = resource_session {
        if let Ok(current) = tokio::time::timeout(Duration::from_millis(250), session.resource_teardown(audio_stream_type)).await {
            teardown = current;
        }
    }
    teardown=merge_auxiliary_teardown(&teardown,&remaining_auxiliary);
    airplay::finish_session(&close_control, &teardown, shutdown.graceful()).await;
    Ok(())
}

fn merge_auxiliary_teardown(body:&[u8],types:&[i64])->Vec<u8> {
    if types.is_empty() {return body.to_vec();}
    let parsed=crate::bplist::parse(body).ok();
    let mut streams=parsed.as_ref().and_then(|v|v.get("streams")).and_then(crate::bplist::Value::as_array).cloned().unwrap_or_default();
    for &typ in types {
        if !streams.iter().any(|v|v.get("type").and_then(crate::bplist::Value::as_int)==Some(typ)) {
            streams.push(crate::bplist::Value::dict(vec![("type",crate::bplist::Value::int(typ))]));
        }
    }
    crate::bplist::encode(&crate::bplist::Value::dict(vec![("streams",crate::bplist::Value::array(streams))]))
}

pub use crate::aux_audio::MainCommand;

async fn audio_pump(
    stop_a:Arc<Notify>, stats:Arc<Mutex<AudioPumpStat>>, claims:mpsc::Sender<(bool,std::time::Instant)>,
    audio_out_rx:tokio::sync::oneshot::Receiver<AudioOut>, audio_rx:mpsc::UnboundedReceiver<SourceEvent>,
    mut commands:mpsc::UnboundedReceiver<MainCommand>, auxiliary:Arc<crate::aux_audio::Input>,
) {
    let mut out: Option<AudioOut> = None;
    let mut paused = false;
    let mut commands_open = true;
    let mut pending_events = VecDeque::new();
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
    let mut voice = VoicePcm::default();
    let mut duck_hold: u32 = 0;
    let mut last_audio_at: Option<std::time::Instant> = None;
    let mut gain: f32 = 1.0;
    // Retain absolute pacing. A stalled worker skips expired source blocks and
    // advances the RTP clock instead of flooding the receiver with catch-up audio.
    let mut tick = crate::audio_pacing::interval();
    let mut sync_tick = tokio::time::interval(Duration::from_secs(1));
    sync_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    // 发包速率自测：每秒记一行，联调时能直接看出节拍对不对。
    let mut sent_packets: u64 = 0;
    let mut audio_errors: u64 = 0;
    let mut rate_mark = std::time::Instant::now();
    let mut mixed = vec![0i16; SPF * 2];
    let mut pacing_recoveries=0u64;
    let mut skipped_source_frames=0u64;
    let mut dropped_media_frames=0u64;
    let mut dropped_tts_frames=0u64;
    let mut dropped_voice_frames=0u64;
    let mut max_late_us=0u128;
    let mut pacing_log=std::time::Instant::now();
    loop {
        tokio::select! {
            _ = stop_a.notified() => return,
            command = commands.recv(), if commands_open => match command {
                Some(MainCommand::Pause(reply)) => {
                    if reply.is_closed() {continue;}
                    drop(out.take()); paused = true;
                    media_mix.clear(); media_remain.clear();
                    // Retire queued main media but preserve assistant/navigation boundaries.
                    while let Ok(event)=audio_rx.try_recv() {
                        if !matches!(event, SourceEvent::AudioInit{tts:false,..}|SourceEvent::AudioData{tts:false,..}|SourceEvent::AudioStop{tts:false}) {
                            pending_events.push_back(event);
                        }
                    }
                    let _=reply.send(());
                    emit_log("AUDIO-RESOURCE: main sender released; pause acknowledged");
                }
                Some(MainCommand::Install(next)) => {
                    ready = None;
                    media_mix.clear(); media_remain.clear();
                    stats.lock().codec=next.as_ref().map(AudioOut::codec_name).unwrap_or("");
                    out=next; paused=false;
                }
                None => commands_open=false,
            },
            r = async {
                match ready.as_mut() {
                    Some(rx) => Some(rx.await.ok()),
                    None => std::future::pending().await,
                }
            } => {
                if let Some(a) = r.flatten() {
                    if !paused { stats.lock().codec = a.codec_name(); out = Some(a); }
                }
                ready = None;
            }
            ev = async { if let Some(event)=pending_events.pop_front() {Some(event)} else {audio_rx.recv().await} } => match ev {
                Some(SourceEvent::AuxFallback{kind,samples}) => {
                    let queue=if kind==crate::aux_setup::Kind::Speech {&mut voice.mix} else {&mut tts_mix};
                    queue.extend(samples);
                    let overflow=queue.len().saturating_sub(TARGET_RATE as usize);
                    if overflow>0 {queue.drain(..overflow);}
                }
                Some(SourceEvent::VoiceAudioInit {rate,channels}) => {
                    auxiliary.init(crate::aux_setup::Kind::Speech,rate,channels);
                    voice.init(rate,channels);
                    emit_log(&format!("VOICE-AUDIO: initialized {rate}Hz {channels}ch; independent PCM lane, media resource unchanged"));
                }
                Some(SourceEvent::VoiceAudioData {rate,channels,data}) => {
                    let accepted=match auxiliary.push(crate::aux_setup::Kind::Speech,rate,channels,&data) {
                        crate::aux_audio::Route::Mix=>voice.push(rate,channels,&data),
                        crate::aux_audio::Route::Independent=>true,
                        crate::aux_audio::Route::Drop=>false,
                    };
                    if accepted && pcm_has_signal(&data) {
                        stats.lock().playing=true;
                        last_audio_at=Some(std::time::Instant::now());
                    }
                }
                Some(SourceEvent::VoiceAudioStop) => {
                    auxiliary.stop(crate::aux_setup::Kind::Speech);
                    voice.stop();
                    emit_log(&format!("VOICE-AUDIO: input ended; queued={}ms tail retained",voice.mix.len()*1000/TARGET_RATE as usize/2));
                }
                Some(SourceEvent::AudioInit { tts, rate, channels }) => {
                    // A new source session may reclaim only an ANYTIME grant. TTS and
                    // continuous PCM never masquerade as a user-initiated media request.
                    if !tts {
                        auxiliary.media_restart();
                        let _ = claims.try_send((false, std::time::Instant::now()));
                    }
                    if tts {
                        auxiliary.init(crate::aux_setup::Kind::Navigation,rate,channels);
                        tts_fmt = (rate.max(1), channels.max(1));
                    }
                    else { media_fmt = (rate.max(1), channels.max(1)); }
                    stats.lock().playing = true;
                }
                Some(SourceEvent::AudioStop { tts }) => {
                    if tts {
                        auxiliary.stop(crate::aux_setup::Kind::Navigation);
                        tts_mix.clear();
                    } else {
                        media_mix.clear();
                        if tts_mix.is_empty() && voice.mix.is_empty() { last_audio_at = None; }
                    }
                }
                Some(SourceEvent::AudioData { tts, rate, channels, data }) => {
                    if tts {
                        let route=auxiliary.push(crate::aux_setup::Kind::Navigation,rate,channels,&data);
                        if route!=crate::aux_audio::Route::Drop && pcm_has_signal(&data) {
                            stats.lock().playing=true;last_audio_at=Some(std::time::Instant::now());
                        }
                        if route!=crate::aux_audio::Route::Mix {continue;}
                    } else if paused {continue;}
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
            scheduled = tick.tick() => {
                let now=tokio::time::Instant::now();
                let skipped=crate::audio_pacing::missed_source_frames(scheduled,now);
                max_late_us=max_late_us.max(now.saturating_duration_since(scheduled).as_micros());
                if skipped>0 {
                    pacing_recoveries+=1; skipped_source_frames=skipped_source_frames.saturating_add(skipped);
                    let media_drop=((skipped as u128*2).min(media_mix.len() as u128)) as usize;
                    let tts_drop=((skipped as u128*2).min(tts_mix.len() as u128)) as usize;
                    media_mix.drain(..media_drop); tts_mix.drain(..tts_drop);
                    dropped_media_frames+=(media_drop/2) as u64; dropped_tts_frames+=(tts_drop/2) as u64;
                    let voice_drop=((skipped as u128*2).min(voice.mix.len() as u128)) as usize;
                    voice.mix.drain(..voice_drop); dropped_voice_frames+=(voice_drop/2) as u64;
                    media_remain.clear(); tts_remain.clear(); voice.remain.clear();
                    if let Some(a)=out.as_mut() {
                        a.skip_source_frames(skipped);
                        let _=a.send_sync_now(crate::timing::monotonic_nanos()).await;
                    }
                }
                if pacing_log.elapsed()>=Duration::from_secs(5) {
                    emit_log(&format!("AUDIO-PACING: recoveries={pacing_recoveries} skipped_source_frames={skipped_source_frames} dropped_media_frames={dropped_media_frames} dropped_tts_frames={dropped_tts_frames} dropped_voice_frames={dropped_voice_frames} max_late_us={max_late_us}; cumulative, no catch-up burst"));
                    pacing_log=std::time::Instant::now();
                }
                if duck_hold > 0 { duck_hold -= 1; }
                if auxiliary.independent(crate::aux_setup::Kind::Navigation) {tts_mix.clear();tts_remain.clear();}
                if auxiliary.independent(crate::aux_setup::Kind::Speech) {voice.mix.clear();voice.remain.clear();}
                let tts_active = !tts_mix.is_empty() || !voice.mix.is_empty();
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
                    let vl = take_or_zero(&mut voice.mix);
                    let vr = take_or_zero(&mut voice.mix);
                    mixed[i * 2] = mix_audio_sample(ml,tl,vl,gain);
                    mixed[i * 2 + 1] = mix_audio_sample(mr,tr,vr,gain);
                }
                stats.lock().ducking = duck_hold > 0;
                if let Some(a) = out.as_mut() {
                    // 静音时也发包：保持 RTP 时钟连续（车机按包排播）
                    let before = a.packets_sent();
                    if let Err(error)=a.send_pcm(&mixed).await {
                        audio_errors+=1;
                        if audio_errors==1 || audio_errors%100==0 { emit_log(&format!("AUDIO: RTP write failed count={audio_errors}: {error}")); }
                    }
                    let written=a.packets_sent().saturating_sub(before);
                    sent_packets += written;
                    let mut counters=stats.lock();
                    counters.packets += written;
                    counters.payload_bytes = a.payload_bytes_sent();
                    if mixed.iter().any(|s| *s != 0) { counters.signal_packets += written; }
                }
                // 每秒报一次实际发包速率：48000/352 ≈ 136/s。低于它就是漏拍，
                // 接收端会表现为 late/discontinuity 增长、听感断续。
                if rate_mark.elapsed() >= Duration::from_secs(1) {
                    let secs = rate_mark.elapsed().as_secs_f64().max(0.001);
                    emit_log(&format!(
                        "AUDIO: 发包 {:.0}/s（应 {:.0}/s） 队列 media={}ms tts={}ms vr={}ms owner_phone={} blocked_samples={}",
                        sent_packets as f64 / secs,
                        TARGET_RATE as f64 / out.as_ref().map(|a| a.samples_per_packet()).unwrap_or(SPF) as f64,
                        media_mix.len() * 1000 / (TARGET_RATE as usize).max(1) / 2,
                        tts_mix.len() * 1000 / (TARGET_RATE as usize).max(1) / 2,
                        voice.mix.len() * 1000 / TARGET_RATE as usize / 2,
                        out.as_ref().map(|a| a.owns_audio()).unwrap_or(false),
                        out.as_ref().map(|a| a.blocked_samples()).unwrap_or(0)
                    ));
                    sent_packets = 0;
                    rate_mark = std::time::Instant::now();
                }
            }
        }
    }
}

/// Optional screen lifecycle commands are a separate control plane. Its producer may finish after
/// negotiation with resource lifecycle disabled; that does not end the active media source/stream.
pub(crate) async fn video_pump(
    stop_v: Arc<Notify>,
    stats: Arc<Mutex<PumpStat>>,
    resource_lifecycle: bool,
    video_pipeline: Arc<Mutex<crate::pipeline::Pipeline>>,
    mut screen_ready_rx: mpsc::Receiver<crate::screen_session::ScreenCommand>,
    video_rx: mpsc::UnboundedReceiver<SourceEvent>,
    transcode:bool, fps:u32,
) {
    let target=Arc::new(Mutex::new((1280,720)));
    let mut converter=crate::video_transcode::Router::new(transcode,fps.min(30).max(1),crate::video_transcode::hardware_factory(target.clone()));
    let mut converted=std::collections::VecDeque::new();
    let mut sender: Option<ScreenSender> = None;
    let mut commands_open = true;
    // 屏幕流就绪前收到的编码尺寸先存着，就绪时补上
    let mut pending_size: Option<(u32, u32)> = None;
    // 编码器真实尺寸（最后一次 EncoderResolution）。VideoConfig/画布都以它为准；
    // /info 可能先写入车机尺寸，编码器回报后覆盖。
    let mut encoder_size: Option<(u32, u32)> = None;
    let mut before_ready_units = 0u64;
    let mut video_rx = video_rx;
    // 16ms tick：仅用于 keepAlive 节拍（真帧由视频泵推送）
    let mut tick = tokio::time::interval(Duration::from_millis(16));
    loop {
        tokio::select! {
            _ = stop_v.notified() => return,
            command = screen_ready_rx.recv(), if commands_open => match command {
                Some(crate::screen_session::ScreenCommand::Install(mut s)) => {
                    *target.lock()=s.receiver_size();
                    if let Some((w, h)) = pending_size.take() {
                        s.set_size(w, h);
                    }
                    sender = Some(s);
                    crate::emit_force_keyframe();
                    emit_log(&format!("VIDEO: sender ready; discarded_before_ready_units={before_ready_units}; request fresh SPS/PPS/IDR"));
                }
                Some(crate::screen_session::ScreenCommand::InstallIfOwned { sender: mut next, modes, revision, reply }) => {
                    *target.lock()=next.receiver_size();
                    let accepted = {
                        let state = modes.lock();
                        if state.can_restore_screen(revision) {
                            next.set_resource_grant(modes.clone(),revision);
                            if let Some((w,h)) = pending_size.take() { next.set_size(w,h); }
                            video_pipeline.lock().video_stream="READY".into();
                            sender = Some(next);
                            true
                        } else { false }
                    };
                    let _ = reply.send(accepted);
                    emit_log(&format!("SCREEN-LIFECYCLE: video pump install revision={revision} accepted={accepted}"));
                    if accepted { crate::emit_force_keyframe(); }
                }
                Some(crate::screen_session::ScreenCommand::Pause(reply)) => {
                    converter.pause(); converted.clear();
                    drop(sender.take());
                    let _=reply.send(());
                    emit_log("VIDEO: old sender released; screen lifecycle pause acknowledged");
                }
                None => {
                    commands_open = false;
                    emit_log("VIDEO: screen command channel closed; keep current stream/source and keepAlive active");
                },
            },
            ev = async {
                if let Some(ev)=converted.pop_front() {(true,Some(ev))}else{(false,video_rx.recv().await)}
            } => { let (processed,ev)=ev; match ev {
                Some(SourceEvent::ResetVideoSource) => {
                    encoder_size = None; pending_size = None;
                    converter.reset();converted.clear();
                    if let Some(s) = sender.as_mut() { s.reset_source(); }
                    emit_log("CARWITH-VIDEO: fresh source round; waiting for current configuration and IDR");
                }
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
                Some(ev @ (SourceEvent::VideoFrame { .. } | SourceEvent::VideoFrameTagged { .. })) => {
                    let (pts_us,data,codec)=match ev {
                        SourceEvent::VideoFrame {pts_us,data}=>(pts_us,data,crate::video_format::VideoCodec::H264),
                        SourceEvent::VideoFrameTagged {pts_us,data,codec}=>(pts_us,data,codec),
                        _=>unreachable!(),
                    };
                    if !processed {
                        stats.lock().source_units += 1;
                        if transcode {
                            let allowed=sender.as_ref().map(|s|s.hevc_allowed()).unwrap_or(false);
                            // Do not initialize a codec until the current screen transport is installed.
                            if sender.is_none() {before_ready_units+=1;continue;}
                            let pts=if pts_us==0 {crate::timing::monotonic_nanos()/1000}else{pts_us};
                            for unit in converter.input(allowed,pts,data,codec) {
                                converted.push_back(SourceEvent::VideoFrameTagged {pts_us:unit.pts,data:unit.data,codec:unit.codec});
                            }
                            continue;
                        }
                    }
                    if let Some(s) = sender.as_mut() {
                        if let Err(error)=s.set_video_codec(codec) {
                            // CarWith has no evidenced source codec request. Do not silently
                            // keep a black screen or invent a transcoding fallback.
                            emit_log(&format!("VIDEO-CODEC: rejected {}: {error}",codec.as_str()));
                            emit_state("ended", "视频源为 HEVC，但本次车机会话未允许 HEVC；请改用 H.264 视频源，或选择 HEVC 优先后重新连接");
                            return;
                        }
                        let before_frames = s.frames_sent();
                        let before_bytes = s.bytes_per_second();
                        // 编码尺寸变化时先切画布，保证随后 send_config 标注正确。
                        let output_size=if transcode && processed && codec==crate::video_format::VideoCodec::Hevc {
                            Some(*target.lock())
                        }else{encoder_size};
                        if let Some((w, h)) = output_size {
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
                        let written=if resource_lifecycle {
                            let connection = video_pipeline.lock().video_tcp.as_ref().map(|s|s.connection_id);
                            crate::screen_write::with_progress(s.send_video_annexb(&data,pts), ||
                                video_pipeline.lock().video_tcp.as_ref()
                                    .filter(|s|Some(s.connection_id)==connection).and_then(|s|s.acked_bytes)).await
                        } else { s.send_video_annexb(&data,pts).await };
                        if let Err(error) = written {
                            emit_log(&format!("VIDEO: write failed: {error}; {}; tcp_at_failure=[{}]", s.diagnostics(),s.transport_diagnostics()));
                            video_pipeline.lock().video_stream="FAILED".into();
                            if resource_lifecycle {
                                drop(sender.take());
                                video_pipeline.lock().video_tcp=None;
                                emit_log("SCREEN-LIFECYCLE: video write failed; keep audio/control, await next allowed requestUI");
                                continue;
                            }
                            emit_state("ended", "屏幕流写失败（请检查网络及车机会话）");
                            return;
                        }
                        let mut st = stats.lock();
                        st.frames += s.frames_sent() - before_frames;
                        st.bytes += s.bytes_per_second() - before_bytes;
                    } else {
                        before_ready_units += 1;
                        if before_ready_units == 1 {
                            emit_log("VIDEO: source arrived before screen TCP sender ready; discarding until ready");
                        }
                    }
                }
                Some(_) => {}
                None => return,
            }},
            _ = tick.tick() => {
                if transcode && sender.is_some() {
                    for unit in converter.poll() {
                        converted.push_back(SourceEvent::VideoFrameTagged {pts_us:unit.pts,data:unit.data,codec:unit.codec});
                    }
                }
                // 屏幕流 keepAlive 帧（明文、1s 节流；缺它 Audi MMI 3315 10.2s 拆轮）
                if let Some(s) = sender.as_mut() {
                    let written=if resource_lifecycle {
                        let connection = video_pipeline.lock().video_tcp.as_ref().map(|s|s.connection_id);
                        crate::screen_write::with_progress(s.maybe_send_keep_alive(), ||
                            video_pipeline.lock().video_tcp.as_ref()
                                .filter(|s|Some(s.connection_id)==connection).and_then(|s|s.acked_bytes)).await
                    } else { s.maybe_send_keep_alive().await };
                    if let Err(error) = written {
                        emit_log(&format!("VIDEO: keepAlive failed: {error}; {}; tcp_at_failure=[{}]", s.diagnostics(),s.transport_diagnostics()));
                        video_pipeline.lock().video_stream="FAILED".into();
                        if resource_lifecycle {
                            drop(sender.take());
                            video_pipeline.lock().video_tcp=None;
                            emit_log("SCREEN-LIFECYCLE: video keepAlive failed; preserve other channels");
                            continue;
                        }
                        emit_state("ended", "屏幕保活写失败");
                        return;
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod video_pump_tests {
    use super::*;
    #[test]
    fn obsolete_recovery_flags_do_not_enable_the_new_experiment() {
        let mut json=serde_json::json!({
            "device_id":"test","car_ip":"127.0.0.1","airplay_port":7000,
            "width":1920,"height":720,"fps":60,"identity_path":"unused",
            "ui_retake_recovery":true,"screen_stream_recovery":true,"bmw_absolute_session_uri":false,
        });
        let config:EngineConfig=serde_json::from_value(json.clone()).unwrap();
        assert!(!config.screen_resource_lifecycle);assert!(config.enable_audio);
        assert!(!config.mbux_screen_bootstrap);
        assert!(!config.id9_screen_bootstrap);assert!(!config.id9_disable_buffered_audio);
        assert!(!config.nbt_audio_first);assert!(!config.mbux_media_compat);
        assert!(!config.self_microphone_asr);
        json["screen_resource_lifecycle"]=serde_json::json!(true);
        let config:EngineConfig=serde_json::from_value(json).unwrap();
        assert!(config.screen_resource_lifecycle);assert!(config.enable_audio);
    }
    use tokio::io::AsyncReadExt;
    #[tokio::test]
    async fn negotiation_command_sender_drop_does_not_stop_video_or_keepalive() {
        for recovery in [false,true] {
            let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let phone=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut car,_)=listener.accept().await.unwrap();let (_,write)=phone.into_split();
            let (commands,rx)=mpsc::channel(2);let (source,video_rx)=mpsc::unbounded_channel();
            let stats=Arc::new(Mutex::new(PumpStat::default()));let stop=Arc::new(Notify::new());
            let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
            let pump=tokio::spawn(video_pump(stop.clone(),stats.clone(),recovery,pipeline,rx,video_rx,false,30));
            commands.send(crate::screen_session::ScreenCommand::Install(ScreenSender::new(
                write,crate::screen::ScreenCipher::ChaCha([7;32]),1920,720,false,
            ))).await.unwrap();
            // Exactly the default production lifecycle: negotiate returns, its local
            // ScreenSession drops, and no experimental recovery dispatcher retains it.
            drop(commands);tokio::task::yield_now().await;
            let access_unit=vec![0,0,0,1,0x67,0x42,0,0x1f,0,0,0,1,0x68,1,0,0,0,1,0x65,2];
            source.send(SourceEvent::VideoFrame {pts_us:2000,data:access_unit}).unwrap();
            source.send(SourceEvent::VideoFrame {pts_us:3000,data:vec![0,0,0,1,0x41,3]}).unwrap();
            let mut frames=0;let mut config=false;let mut keepalive=false;
            tokio::time::timeout(Duration::from_secs(3),async {
                while frames<2 || !keepalive {
                    let mut header=[0u8;128];car.read_exact(&mut header).await.unwrap();
                    let len=u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
                    let mut body=vec![0u8;len];car.read_exact(&mut body).await.unwrap();
                    match header[4] {
                        1=>{config=true;},
                        0=>{
                            assert!(config);
                            let plain=crate::crypto::chacha_open(&[7;32],&crate::crypto::nonce64(frames),&body,&header).unwrap();
                            assert_eq!(plain,if frames==0 {vec![0,0,0,2,0x65,2]} else {vec![0,0,0,2,0x41,3]});
                            frames+=1;
                        },
                        2=>{keepalive=true;},
                        op=>panic!("unexpected video opcode {op}"),
                    }
                }
            }).await.unwrap();
            assert_eq!(stats.lock().frames,2);assert_eq!(stats.lock().source_units,2);
            assert!(!pump.is_finished());
            stop.notify_waiters();drop(source);
            tokio::time::timeout(Duration::from_secs(1),pump).await.unwrap().unwrap();
            let mut eof=[0];assert_eq!(car.read(&mut eof).await.unwrap(),0);
        }
    }
}

#[derive(Default)]
struct EngineTasks(Vec<tokio::task::JoinHandle<()>>);
impl EngineTasks {
    fn spawn(&mut self, future: impl std::future::Future<Output = ()> + Send + 'static) {
        self.0.push(tokio::spawn(future));
    }
    async fn shutdown(&mut self) {
        for task in &self.0 { task.abort(); }
        let tasks = std::mem::take(&mut self.0);
        let _ = tokio::time::timeout(Duration::from_millis(250), async {
            for task in tasks { let _ = task.await; }
        }).await;
    }
}
impl Drop for EngineTasks {
    fn drop(&mut self) { for task in &self.0 { task.abort(); } }
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
pub(crate) fn push_resampled(
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

// Keep VR replies separate from navigation TTS: ending either source must not
// erase the other's queued audio. A normal VR STOP closes input, then drains.
#[derive(Default)]
struct VoicePcm {
    format:Option<(u32,u8)>,
    remain:Vec<i16>,
    mix:VecDeque<i16>,
}
impl VoicePcm {
    fn init(&mut self,rate:u32,channels:u8) {
        self.format=if (4000..=96000).contains(&rate) && (1..=2).contains(&channels) {Some((rate,channels))} else {None};
        self.remain.clear(); self.mix.clear();
    }
    fn stop(&mut self) {self.format=None; self.remain.clear();}
    fn push(&mut self,rate:u32,channels:u8,data:&[u8])->bool {
        if self.format!=Some((rate,channels)) {return false;}
        push_resampled(data,rate,channels,&mut self.remain,&mut self.mix);
        // Match the existing half-second limit and retire whole stereo frames.
        let overflow=self.mix.len().saturating_sub(TARGET_RATE as usize);
        if overflow>0 {self.mix.drain(..overflow);}
        true
    }
}

fn mix_audio_sample(media:i16,tts:i16,voice:i16,gain:f32)->i16 {
    ((media as f32*gain) as i32+tts as i32+voice as i32).clamp(i16::MIN as i32,i16::MAX as i32) as i16
}

#[cfg(test)]
mod voice_mix_tests {
    use super::*;
    #[test]
    fn voice_end_preserves_tail_and_new_init_cannot_replay_old_reply() {
        let mut voice=VoicePcm::default();
        let pcm=1500i16.to_le_bytes().repeat(160);
        assert!(!voice.push(16000,1,&pcm));
        voice.init(16000,1);
        assert!(voice.push(16000,1,&pcm));
        let queued=voice.mix.len(); assert!(queued>0);
        voice.stop();
        assert_eq!(voice.mix.len(),queued);
        assert!(!voice.push(16000,1,&pcm));
        assert_eq!(mix_audio_sample(0,0,take_or_zero(&mut voice.mix),0.25),1500);
        voice.init(4000,1);
        assert!(voice.push(4000,1,&pcm));
        assert!(voice.mix.len()>queued);
        voice.init(48000,2);
        assert!(voice.mix.is_empty());
        assert!(!voice.push(16000,1,&pcm));
        assert!(voice.push(48000,2,&[1;500000]));
        assert_eq!(voice.mix.len(),TARGET_RATE as usize);
    }
    #[test]
    fn independent_voice_is_audible_with_silent_media_and_saturates_with_tts() {
        assert_eq!(mix_audio_sample(0,0,1500,0.25),1500);
        assert_eq!(mix_audio_sample(1000,1000,1500,0.25),2750);
        assert_eq!(mix_audio_sample(32767,32767,32767,1.0),32767);
        assert_eq!(mix_audio_sample(-32768,-32768,-32768,1.0),-32768);
    }
}

fn take_or_zero(q: &mut VecDeque<i16>) -> i16 {
    q.pop_front().unwrap_or(0)
}

// ---------------------------------------------------------------- 统计

#[derive(Default)]
pub(crate) struct PumpStat {
    source_units: u64,
    frames: u64,
    bytes: u64,
}

#[derive(Default)]
struct AudioPumpStat {
    codec: &'static str,
    payload_bytes: u64,
    packets: u64,
    signal_packets: u64,
    playing: bool,
    ducking: bool,
}






/// CarLife（HU 侧）向引擎上报的事件（2026-11-03 从 archive 分支取回，供 CarWith 模式使用）。
#[derive(Debug, Clone)]
pub enum CarLifeEvent {
    Navigation(Option<crate::carlife::navigation::NavigationUpdate>),
    SourceReset,
    Handshake,
    VideoFrame { pts_us: u64, data: Vec<u8> },
    VideoFrameTagged { pts_us:u64, data:Vec<u8>, codec:crate::video_format::VideoCodec },
    AudioInit { tts: bool, rate: u32, channels: u8 },
    VoiceAudioInit { rate:u32, channels:u8 },
    VoiceAudioData { rate:u32, channels:u8, data:Vec<u8> },
    VoiceAudioStop,
    AudioStop { tts: bool },
    AudioData { tts: bool, rate: u32, channels: u8, data: Vec<u8> },
    /// 手机在 VIDEO_ENCODER_INIT_DONE 里回报的实际编码分辨率。
    EncoderResolution { width: u32, height: u32 },
    ClientLeft { video: bool },
}

fn fresh_audio_claim(first: (bool, std::time::Instant), pending: &mut mpsc::Receiver<(bool, std::time::Instant)>) -> Option<bool> {
    let mut intent = None;
    let mut next = Some(first);
    while let Some((user, at)) = next {
        if at.elapsed() < Duration::from_secs(2) { intent = Some(intent.unwrap_or(false) || user); }
        next = pending.try_recv().ok();
    }
    intent
}

#[cfg(test)]
mod audio_intent_tests {
    use super::*;
    #[test]
    fn expired_user_action_cannot_promote_a_fresh_automatic_start() {
        let (tx, mut rx) = mpsc::channel(4);
        tx.try_send((false, std::time::Instant::now())).unwrap();
        assert_eq!(fresh_audio_claim((true, std::time::Instant::now()-Duration::from_secs(3)), &mut rx), Some(false));
        assert_eq!(fresh_audio_claim((true, std::time::Instant::now()-Duration::from_secs(3)), &mut rx), None);
    }
    #[test]
    fn coalescing_preserves_a_fresh_explicit_play_action() {
        let (tx, mut rx) = mpsc::channel(4);
        tx.try_send((true, std::time::Instant::now())).unwrap();
        tx.try_send((false, std::time::Instant::now())).unwrap();
        assert_eq!(fresh_audio_claim((false, std::time::Instant::now()), &mut rx), Some(true));
    }
}

#[cfg(test)]
mod main_audio_command_tests {
    use super::*;
    use tokio::net::UdpSocket;
    async fn output() -> (AudioOut,UdpSocket,std::net::SocketAddr) {
        let rx=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let tx=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let local=tx.local_addr().unwrap();tx.connect(rx.local_addr().unwrap()).await.unwrap();
        (AudioOut::new(tx,None,[7;32],100),rx,local)
    }
    #[tokio::test]
    async fn pause_ack_releases_media_socket_and_install_resumes_with_fallback_audio() {
        let(stop,stats)=(Arc::new(Notify::new()),Arc::new(Mutex::new(AudioPumpStat::default())));
        let(claims,_)=mpsc::channel(4);let(ready,rx)=tokio::sync::oneshot::channel();
        let(events,audio)=mpsc::unbounded_channel();let(commands,command_rx)=mpsc::unbounded_channel();
        let worker=tokio::spawn(audio_pump(stop,stats,claims,rx,audio,command_rx,Arc::new(crate::aux_audio::Input::default())));
        let(first,peer,local)=output().await;assert!(ready.send(first).is_ok());
        let mut packet=[0;4096];tokio::time::timeout(Duration::from_secs(1),peer.recv(&mut packet)).await.unwrap().unwrap();
        events.send(SourceEvent::AudioData{tts:false,rate:48000,channels:2,data:10000i16.to_le_bytes().repeat(12000)}).unwrap();
        let(ack,done)=tokio::sync::oneshot::channel();commands.send(MainCommand::Pause(ack)).unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(250),done).await.is_ok(),"pause must finish before lifecycle allocates speech");
        let rebound=UdpSocket::bind(local).await;assert!(rebound.is_ok(),"pause acknowledgment must follow physical sender drop");
        let(next,peer,_)=output().await;commands.send(MainCommand::Install(Some(next))).unwrap();
        events.send(SourceEvent::AuxFallback{kind:crate::aux_setup::Kind::Speech,samples:vec![2000;7040]}).unwrap();
        let mut heard=false;
        for _ in 0..20 {
            let n=tokio::time::timeout(Duration::from_secs(1),peer.recv(&mut packet)).await.unwrap().unwrap();
            let nonce=u64::from_le_bytes(packet[n-8..n].try_into().unwrap());
            let pcm=crate::crypto::chacha_open(&[7;32],&crate::crypto::nonce64(nonce),&packet[12..n-8],&packet[4..12]).unwrap();
            if pcm.chunks_exact(2).any(|p|i16::from_be_bytes([p[0],p[1]])!=0) {
                assert!(pcm.chunks_exact(2).all(|p|i16::from_be_bytes([p[0],p[1]])==2000),"old queued media must not survive Pause");
                heard=true;break;
            }
        }
        assert!(heard,"independent stream fallback must enter the main mix once");
        worker.abort();let _=worker.await;
    }
    #[tokio::test]
    async fn paused_pump_drops_late_initial_sender_until_explicit_install() {
        let(claims,_)=mpsc::channel(4);let(ready,rx)=tokio::sync::oneshot::channel();
        let(_events,audio)=mpsc::unbounded_channel();let(commands,command_rx)=mpsc::unbounded_channel();
        let worker=tokio::spawn(audio_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(AudioPumpStat::default())),claims,rx,audio,command_rx,Arc::new(crate::aux_audio::Input::default())));
        let(ack,done)=tokio::sync::oneshot::channel();commands.send(MainCommand::Pause(ack)).unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(250),done).await.is_ok(),"pause before negotiation must be acknowledged");
        let(initial,peer,_)=output().await;assert!(ready.send(initial).is_ok());
        assert!(tokio::time::timeout(Duration::from_millis(40),peer.recv(&mut [0;4096])).await.is_err(),"late negotiation cannot restart paused media");
        let(next,peer,_)=output().await;commands.send(MainCommand::Install(Some(next))).unwrap();
        assert!(tokio::time::timeout(Duration::from_secs(1),peer.recv(&mut [0;4096])).await.is_ok(),"explicit installation must resume media pump");
        worker.abort();let _=worker.await;
    }
    #[tokio::test]
    async fn cancelled_queued_pause_cannot_silence_a_newly_installed_sender() {
        let(claims,_)=mpsc::channel(4);let(_ready,rx)=tokio::sync::oneshot::channel();
        let(_events,audio)=mpsc::unbounded_channel();let(commands,command_rx)=mpsc::unbounded_channel();
        let(next,peer,local)=output().await;
        commands.send(MainCommand::Install(Some(next))).unwrap();
        let(ack,done)=tokio::sync::oneshot::channel();drop(done);
        commands.send(MainCommand::Pause(ack)).unwrap();
        let worker=tokio::spawn(audio_pump(Arc::new(Notify::new()),Arc::new(Mutex::new(AudioPumpStat::default())),claims,rx,audio,command_rx,Arc::new(crate::aux_audio::Input::default())));
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert!(UdpSocket::bind(local).await.is_err(),"cancelled pause cannot release a current sender");
        assert!(tokio::time::timeout(Duration::from_millis(100),peer.recv(&mut [0;4096])).await.is_ok(),"cancelled pause cannot stop current RTP");
        worker.abort();let _=worker.await;
    }

    #[tokio::test]
    async fn independent_lanes_do_not_duplicate_pcm_on_media_and_stops_reach_input() {
        use crate::{aux_setup::Kind,aux_audio::{Input,Route},bplist::Value};
        let input=Arc::new(Input::default());
        let info=Value::dict(vec![("audioFormats",Value::array(vec![
            Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("speechRecognition")),("audioOutputFormats",Value::int(1<<29))]),
            Value::dict(vec![("type",Value::int(101)),("audioType",Value::str("default")),("audioOutputFormats",Value::int(1<<30))]),
        ]))]);
        let(claims,_)=mpsc::channel(4);let(ready,rx)=tokio::sync::oneshot::channel();
        let(events,audio)=mpsc::unbounded_channel();let(_commands,command_rx)=mpsc::unbounded_channel();
        let stats=Arc::new(Mutex::new(AudioPumpStat::default()));
        let worker=tokio::spawn(audio_pump(Arc::new(Notify::new()),stats.clone(),claims,rx,audio,command_rx,input.clone()));
        // Queue mirrored audio before capabilities arrive. The next independent tick
        // must retire these samples as well as bypassing subsequent source DATA.
        events.send(SourceEvent::VoiceAudioInit{rate:48000,channels:2}).unwrap();
        events.send(SourceEvent::VoiceAudioData{rate:48000,channels:2,data:1000i16.to_le_bytes().repeat(12000)}).unwrap();
        events.send(SourceEvent::AudioInit{tts:true,rate:48000,channels:2}).unwrap();
        events.send(SourceEvent::AudioData{tts:true,rate:48000,channels:2,data:2000i16.to_le_bytes().repeat(12000)}).unwrap();
        tokio::time::timeout(Duration::from_secs(1),async {while !stats.lock().playing {tokio::task::yield_now().await;}}).await.unwrap();
        input.configure(&info);
        let(out,peer,_)=output().await;assert!(ready.send(out).is_ok());
        let mut packet=[0;4096];
        for _ in 0..5 {
            let n=tokio::time::timeout(Duration::from_secs(1),peer.recv(&mut packet)).await.unwrap().unwrap();
            let nonce=u64::from_le_bytes(packet[n-8..n].try_into().unwrap());
            let pcm=crate::crypto::chacha_open(&[7;32],&crate::crypto::nonce64(nonce),&packet[12..n-8],&packet[4..12]).unwrap();
            assert!(pcm.iter().all(|v|*v==0),"independent speech/navigation cannot also play in the media mix");
        }
        events.send(SourceEvent::VoiceAudioStop).unwrap();events.send(SourceEvent::AudioStop{tts:true}).unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(input.push(Kind::Speech,48000,2,&[0;4]),Route::Drop);
        assert_eq!(input.push(Kind::Navigation,48000,2,&[0;4]),Route::Drop);
        assert!(stats.lock().playing,"independent signal still contributes to activity diagnostics");
        worker.abort();let _=worker.await;
    }
    #[test]
    fn final_teardown_keeps_screen_and_main_entries_and_deduplicates_aux_types() {
        use crate::bplist::{self,Value};
        let old=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![
            Value::dict(vec![("type",Value::int(110)),("streamConnectionID",Value::int(42))]),
            Value::dict(vec![("type",Value::int(100))]),
        ]))]));
        let merged=bplist::parse(&merge_auxiliary_teardown(&old,&[100,101,101])).unwrap();
        let streams=merged.get("streams").unwrap().as_array().unwrap();
        assert_eq!(streams.len(),3);
        assert_eq!(streams[0].get("streamConnectionID").unwrap().as_int(),Some(42));
        assert_eq!(streams.iter().map(|v|v.get("type").unwrap().as_int().unwrap()).collect::<Vec<_>>(),vec![110,100,101]);
    }
}
