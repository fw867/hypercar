//! AirPlay 发送端会话（协商整体下沉 Rust —— 与参考文档的关键差异，见 docs/NEGOTIATION-GAPS.md）。
//!
//! 流程（参照 CatPlay bootstrap，保留已验证的配对与流参数）：
//!  1. 配对前 /info best-effort 读取身份；pair-setup / pair-verify 建立加密控制通道。
//!  2. 会话 SETUP /setup → 会话内 /info（真实显示 UUID / 能力）。
//!  3. 连接事件通道，启动 UDP keepAlive（首发立即、后续 5s）及 /feedback（1s）。
//!  4. modesChanged 声明资源 → 屏幕流 SETUP / → 连接数据口 → 无请求体 RECORD /。
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
use crate::rtsp::{ControlCipher, RtspChannel};
use crate::screen::{ScreenCipher, ScreenSender};
use crate::timing::TimingServer;
use crate::{emit_log, emit_state};

/// Rust 需要新 socket 时回调 Kotlin（必须绑定车机网络，否则流量走手机）。
pub trait SocketProvider: Send + Sync {
    fn open_tcp(&self, host: &str, port: u16, timeout_ms: u32) -> Option<TcpStream>;
    fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket>;
}

#[derive(Clone, Debug)]
pub struct AirplayParams {
    pub car_ip: String,
    pub airplay_port: u16,
    /// 本机真实蓝牙 MAC（WinPlay 等接收端按 SETUP deviceID 过滤，占位值必 403）。
    pub device_id: String,
    pub identity_path: String,
    pub receiver_id: Option<String>,
    pub receiver_previously_connected: bool,
    pub enable_audio: bool,
    pub keep_alive_stats: bool,
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
}

/// 车机触摸（已是编码画布坐标）。
#[derive(Debug, Clone, Copy)]
pub struct TouchInput {
    pub action: u32,
    pub x: u32,
    pub y: u32,
}

pub struct AirplayOutcome {
    pub screen: ScreenSender,
    pub control: Arc<RtspChannel>,
    pub audio: Option<AudioOut>,
    pub keep_alive_stats: bool,
    pub keepalive: KeepaliveTasks,
}

/// 跑完整个 AirPlay 协商，返回屏幕/音频发送器（交给 pump 任务）。
pub async fn negotiate(
    read: OwnedReadHalf,
    write: OwnedWriteHalf,
    p: &AirplayParams,
    factory: Arc<dyn SocketProvider>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    stop: Arc<Notify>,
) -> Result<AirplayOutcome, String> {
    let (mut channel, _control_inbound) = RtspChannel::spawn(read, write, None);
    emit_log(&format!("AIRPLAY: 控制连接 RTSP#{} 开始", channel.trace_id()));

    // 保留配对前的 best-effort 身份读取，供旧凭证公钥索引兼容使用。
    // 此阶段的 455 不表示缺少显示能力；显示信息在会话 SETUP 成功后重新读取。
    let mut info = match fetch_info(&channel).await {
        Ok(v) => v,
        Err(e) => {
            emit_log(&format!(
                "AIRPLAY: /info 不可用（{e}）——未配对连接上被 455 拒是已知行为，\
                 继续走 pair-verify（显示尺寸/能力先用设置值），会话 SETUP 成功后再读取能力"
            ));
            Value::dict(vec![])
        }
    };
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
                        let (fresh, _rx) = RtspChannel::spawn(r, w, None);
                        channel = fresh;
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
                    let (fresh, _rx) = RtspChannel::spawn(r, w, None);
                    channel = fresh;
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

    // CatPlay bootstrap: 会话 SETUP → /info → 事件通道/保活 → 资源声明 → 屏幕流。
    let timing = TimingServer::start().map_err(|e| format!("计时服务启动失败: {e}"))?;
    let (root, session_info) = setup_and_read_info(
        &channel, &p.device_id, timing.port, shared_secret.is_some(), info,
    ).await?;
    info = session_info;
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
    let display = info
        .get("displays")
        .and_then(Value::as_array)
        .and_then(|a| a.first());
    let display_uuid = display.and_then(|d| uuid_text(d.get("uuid")));
    let (disp_w, disp_h) = display.map(read_display_size).unwrap_or((0, 0));
    // 车机能力位图：判断是否支持 HEVC 等能力，只能靠它（codec 由本机编码器决定）
    let car_features = info.get("features").and_then(Value::as_int).unwrap_or(0);
    emit_log(&format!(
        "AIRPLAY: /info displays[0].uuid={:?} {}x{} features=0x{:x}",
        display_uuid, disp_w, disp_h, car_features
    ));

    // The receiver defines the viewport ratio, not the phone's encoding workload.
    // Kotlin chooses a proportional capped canvas and reports the actual size before its first frame.
    crate::emit_display_size(disp_w, disp_h);

    // ---- 5. 事件通道 ----
    if event_port == 0 {
        return Err("SETUP 未返回 eventPort".to_string());
    }
    let event_stream = factory
        .open_tcp(&p.car_ip, event_port, 5_000)
        .ok_or_else(|| format!("事件通道连接失败 {}:{event_port}", p.car_ip))?;
    let (ev_r, ev_w) = event_stream.into_split();
    let (event_channel, event_rx) = RtspChannel::spawn(ev_r, ev_w, None);
    emit_log(&format!("AIRPLAY: 事件连接 RTSP#{} 建立，port={event_port}", event_channel.trace_id()));
    if let Some(shared) = &shared_secret {
        let (read_key, write_key) = derive_event_keys(shared);
        event_channel.set_cipher(ControlCipher::new(read_key, write_key));
    }
    // 事件通道必须活到会话结束：`RtspChannel` 的 `Drop` 会 abort 读任务并关闭 socket。
    // 之前这里只是临时变量，函数返回时就被丢弃 → 事件通道刚连上即被我们自己关掉，
    // 接收端（WinPlay）的 close 回调把 eventSock 清回 null，触摸全进 pendingEvents
    // 队列，攒到 64 条报「事件通道发送队列超限」（现场 2026-10-23 23:33 定位）。
    {
        let stop_ev = stop.clone();
        tokio::spawn(async move {
            let _keep_alive = event_channel;
            stop_ev.notified().await;
        });
    }
    spawn_event_dispatcher(
        event_rx,
        touch_tx,
        key_tx,
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
        // 只有 /info 报 0x0（PC 接收端）才用窗口矩形；真车用真实显示空间。
        if disp_w >= 320 && disp_h >= 240 { None } else { p.touch_rect },
        p.touch_size.clone(),
        stop.clone(),
    );

    // 激活等待与后续音频协商期间也必须保持会话存活。
    // 句柄由协商结果持有；协商失败/取消时 Drop 自动停止本轮任务。
    let keepalive = spawn_keepalive_loops(
        channel.clone(), factory.clone(), keep_alive_port, p.car_ip.clone(), stop.clone(),
    );
    emit_log("AIRPLAY: 事件通道已建立，UDP/feedback 保活已启动（RECORD 前）");
    require_modes_changed(&channel).await?;

    // ---- 6. 屏幕流 SETUP ----
    let conn_id_screen = random_positive_i64();
    let stream_uuid = display_uuid
        .clone()
        .unwrap_or_else(|| "6E4E3B31-0E5B-4A8F-9F1B-2C5D7E9A0C11".to_string());
    emit_log(&format!("AIRPLAY: 屏幕流 uuid={stream_uuid} 来源={}",
        if display_uuid.is_some() { "车机显示能力" } else { "兼容兜底（未取得有效显示 UUID）" }));
    let screen_body = build_screen_setup(conn_id_screen, &stream_uuid);
    let resp = channel
        .request(
            "SETUP",
            "/",
            &screen_body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(8),
        )
        .await?;
    if resp.status() != 200 {
        return Err(format!(
            "屏幕流 SETUP 被拒 [{}]（uuid={}）",
            resp.first_line, stream_uuid
        ));
    }
    let root = bplist::parse(&resp.body).map_err(|e| format!("屏幕流应答解析失败: {e}"))?;
    let data_port = root
        .get("streams")
        .and_then(Value::as_array)
        .and_then(|a| a.first())
        .map(|s| port_of(s.get("dataPort")))
        .unwrap_or(0);
    if data_port == 0 {
        return Err("屏幕流未返回 dataPort".to_string());
    }
    let cipher = match &shared_secret {
        Some(shared) => ScreenCipher::ChaCha(stream_key(shared, conn_id_screen as u64)),
        None => {
            let (k, iv) = aes_stream_keys_zero(conn_id_screen as u64);
            ScreenCipher::AesCtr(crate::crypto::Aes128CtrStream::new(&k, &iv))
        }
    };
    let screen_stream = factory
        .open_tcp(&p.car_ip, data_port, 5_000)
        .ok_or_else(|| format!("屏幕流连接失败 {}:{data_port}", p.car_ip))?;
    // into_split 返回 (读半部, 写半部)；屏幕流只推不收，读半部直接丢弃
    let (_screen_r, screen_w) = screen_stream.into_split();
    // 画布尺寸取当前编码分辨率（/info 已写入；编码侧回报实际值后会再覆盖）
    let (enc_w, enc_h) = *p.encoder_size.lock();
    let screen = ScreenSender::new(screen_w, cipher, enc_w, enc_h, keep_alive_stats);
    emit_log(&format!(
        "AIRPLAY: ✓ 主屏流就绪 dataPort={data_port} connId={conn_id_screen} {enc_w}x{enc_h}"
    ));
    let mut screen = screen;

    // ---- 7. （音频挪到 RECORD 之后，见第 8 步）----
    // 宝马这台（AirTunes/450.14）在屏幕流 SETUP 后约 1.5s 就关控制通道；
    // 若这段时间还在做音频 SETUP，RECORD 永远发不出去（21:13 现场）。

    // ---- 8. RECORD（会话保活已在开流前启动）----
    emit_log("AIRPLAY: 开始 RECORD（等待车机激活会话）");
    record_session(&channel).await?;
    emit_log("AIRPLAY: ✓ RECORD 完成——会话已激活");
    emit_state("connected", "AirPlay 会话已激活");

    // ---- 9. 音频流 SETUP（会话已激活后再试；失败只记录）----
    let audio = if p.enable_audio {
        negotiate_audio(&channel, &factory, shared_secret.as_ref(), p).await
    } else {
        None
    };

    Ok(AirplayOutcome {
        control: channel,
        screen,
        audio,
        keep_alive_stats,
        keepalive,
    })
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

    #[tokio::test]
    async fn session_setup_precedes_info_and_resource_declaration() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut requests = Vec::new();
            let mut chunk = [0u8; 4096];
            while let Ok(n) = socket.read(&mut chunk).await {
                if n == 0 { break; }
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer) {
                    requests.push(request.first_line.clone());
                    let body = match requests.len() {
                        1 => {
                            assert_eq!(request.first_line, "SETUP /setup RTSP/1.0");
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
        let (_, info) = setup_and_read_info(&channel, "84:AE:DE:56:D3:76", 12345, true, Value::dict(vec![])).await.unwrap();
        let display = &info.get("displays").unwrap().as_array().unwrap()[0];
        assert_eq!(uuid_text(display.get("uuid")).as_deref(), Some("actual-bmw-display"));
        assert_eq!(read_display_size(display), (2560, 744));
        require_modes_changed(&channel).await.unwrap();
        drop(channel);
        assert_eq!(peer.await.unwrap().len(), 3);
    }

    #[tokio::test]
    async fn keepalive_runs_while_record_waits_and_stops_with_its_owner() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        struct LocalSockets;
        impl SocketProvider for LocalSockets {
            fn open_tcp(&self, _: &str, _: u16, _: u32) -> Option<TcpStream> { None }
            fn open_udp(&self, host: &str, port: u16) -> Option<UdpSocket> {
                let socket = std::net::UdpSocket::bind("127.0.0.1:0").ok()?;
                socket.connect((host, port)).ok()?;
                socket.set_nonblocking(true).ok()?;
                UdpSocket::from_std(socket).ok()
            }
        }
        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let udp_port = udp.local_addr().unwrap().port();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let peer = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut chunk = [0u8; 4096];
            let mut record_cseq = None;
            loop {
                let n = socket.read(&mut chunk).await.unwrap();
                assert!(n > 0);
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer) {
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
        let tasks = spawn_keepalive_loops(channel.clone(), Arc::new(LocalSockets), udp_port, "127.0.0.1".to_string(), Arc::new(Notify::new()));
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
        let peer = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buffer = Vec::new();
            let mut methods = Vec::new();
            let mut chunk = [0u8; 4096];
            while let Ok(n) = socket.read(&mut chunk).await {
                if n == 0 { break; }
                buffer.extend_from_slice(&chunk[..n]);
                for request in crate::rtsp::parse_rtsp_messages(&mut buffer) {
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
    "cornerMasks",
    "focusTransfer",
    "h.264Level5.1",
    "mainBuffered",
    "altScreen",
    "enhancedSiri",
    "hevc",
    "sessionManagement",
    "logTransfer",
    "iAPChannel",
    "vehicleStateProtocol",
    "videoPlayback",
];

fn build_session_setup(device_id: &str, timing_port: u16, encrypted: bool) -> Vec<u8> {
    let session_uuid = random_uuid_upper();
    let mut fields: Vec<(&str, Value)> = vec![
        ("deviceID", Value::str(device_id)),
        ("macAddress", Value::str("aa:bb:cc:dd:ee:ff")),
        ("name", Value::str("iPhone")),
        ("model", Value::str("iPhone14,2")),
        ("osName", Value::str("iPhone OS")),
        ("osBuildVersion", Value::str("19A404")),
        ("osVersion", Value::str("15.0.2")),
        ("sourceVersion", Value::str("566.25.21")),
        ("timingPort", Value::int(timing_port as i64)),
        ("sessionUUID", Value::str(&session_uuid)),
        ("sessionCorrelationUUID", Value::str(&random_uuid_upper())),
        ("statsCollectionEnabled", Value::bool(false)),
        ("updateSessionRequest", Value::bool(false)),
        ("keepAliveLowPower", Value::bool(true)),
        (
            "features",
            Value::array(CONTROLLER_FEATURES.iter().map(|f| Value::str(f)).collect()),
        ),
    ];
    if !encrypted {
        // AirPlayEncryptionType::NONE = 1<<0（未配对会话必须显式声明，否则被当
        // 「声明加密但无密钥」拒收）。
        fields.push(("et", Value::int(1)));
    }
    bplist::encode(&Value::dict(fields))
}

async fn send_modes_changed(channel: &Arc<RtspChannel>) -> Option<u16> {
    match channel.request(
        "POST", "/command", &build_modes_changed(),
        "application/x-apple-binary-plist", &[], Duration::from_secs(5),
    ).await {
        Ok(resp) => {
            emit_log(&format!("AIRPLAY: modesChanged [{}]", resp.first_line));
            Some(resp.status())
        }
        Err(e) => {
            emit_log(&format!("AIRPLAY: modesChanged 失败 - {e}"));
            None
        }
    }
}

async fn require_modes_changed(channel: &Arc<RtspChannel>) -> Result<(), String> {
    match send_modes_changed(channel).await {
        Some(200) => Ok(()),
        status => Err(format!("会话 SETUP 后 modesChanged 未获确认（status={status:?}），停止流激活")),
    }
}

async fn record_session(channel: &Arc<RtspChannel>) -> Result<(), String> {
    // CatPlay AirPlayRtspClient::record 使用无请求体的 RECORD /。
    let reply = channel.request(
        "RECORD", "/", &[], "application/x-apple-binary-plist", &[], Duration::from_secs(5),
    ).await?;
    if reply.status() != 200 {
        return Err(format!("RECORD 被拒 [{}]", reply.first_line));
    }
    Ok(())
}

async fn setup_and_read_info(
    channel: &Arc<RtspChannel>, device_id: &str, timing_port: u16, encrypted: bool, previous_info: Value,
) -> Result<(Value, Value), String> {
    let body = build_session_setup(device_id, timing_port, encrypted);
    let reply = channel.request(
        "SETUP", "/setup", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(10),
    ).await?;
    if reply.status() != 200 {
        return Err(format!("会话 SETUP 被拒 [{}]（deviceID={device_id}；接收端若启用设备过滤请选择该地址）", reply.first_line));
    }
    let setup = bplist::parse(&reply.body).map_err(|e| format!("SETUP 应答解析失败: {e}"))?;
    // SETUP 前的 455 只说明阶段未就绪，不能据此认定车机没有显示能力。
    emit_log("AIRPLAY: 会话 SETUP 已接受——读取会话内 /info");
    let info = match fetch_info(channel).await {
        Ok(value) if value.as_dict().map(|d| !d.is_empty()).unwrap_or(false) => {
            emit_log("AIRPLAY: 会话内 /info 成功，采用本轮车机显示能力");
            value
        }
        Ok(_) => {
            emit_log("AIRPLAY: 会话内 /info 为空，保留先前能力或设置兜底");
            previous_info
        }
        Err(error) => {
            emit_log(&format!("AIRPLAY: 会话内 /info 仍不可用（{error}），保留先前能力或设置兜底"));
            previous_info
        }
    };
    Ok((setup, info))
}

fn build_modes_changed() -> Vec<u8> {
    let app_states = Value::array(vec![
        Value::dict(vec![("appStateID", Value::int(2)), ("entity", Value::int(0))]),
        Value::dict(vec![("appStateID", Value::int(3)), ("entity", Value::int(0))]),
        Value::dict(vec![
            ("appStateID", Value::int(1)),
            ("entity", Value::int(0)),
            ("speechMode", Value::int(-1)),
        ]),
    ]);
    let resources = Value::array(vec![
        Value::dict(vec![
            ("resourceID", Value::int(1)),
            ("entity", Value::int(1)),
            ("permanentEntity", Value::int(1)),
        ]),
        Value::dict(vec![
            ("resourceID", Value::int(2)),
            ("entity", Value::int(1)),
            ("permanentEntity", Value::int(1)),
        ]),
    ]);
    bplist::encode(&Value::dict(vec![
        ("type", Value::str("modesChanged")),
        (
            "params",
            Value::dict(vec![("appStates", app_states), ("resources", resources)]),
        ),
    ]))
}

fn build_screen_setup(conn_id: i64, uuid: &str) -> Vec<u8> {
    bplist::encode(&Value::dict(vec![(
        "streams",
        Value::array(vec![Value::dict(vec![
            ("type", Value::int(110)), // 110=主屏（100 是音频，会开 UDP 端口）
            ("streamConnectionID", Value::int(conn_id)),
            ("uuid", Value::str(uuid)),
            ("latencyMs", Value::int(25)),
        ])]),
    )]))
}

async fn negotiate_audio(
    channel: &Arc<RtspChannel>,
    factory: &Arc<dyn SocketProvider>,
    shared: Option<&[u8; 32]>,
    p: &AirplayParams,
) -> Option<AudioOut> {
    let conn_id = random_positive_i64();
    let body = bplist::encode(&Value::dict(vec![(
        "streams",
        Value::array(vec![Value::dict(vec![
            ("type", Value::int(100)),          // 主音频
            ("streamConnectionID", Value::int(conn_id)),
            ("audioFormat", Value::int(1 << 15)), // PCM 48k 立体声位图
            ("audioType", Value::str("media")),   // 必须 media（compatibility 档无声）
            ("audioLatencyMs", Value::int(0)),
            ("input", Value::bool(false)),
            ("supportsRTPPacketRedundancy", Value::bool(false)),
            ("spf", Value::int(352)), // AirPlay 标准 SPF（512 在真车无声）
        ])]),
    )]));
    // 音频 SETUP 必须用**绝对 URI**（cardeck runAudioStream 同款：`rtsp://host:port/`）；
    // 屏幕流与初始 SETUP 才用相对路径。URI 用反会 400/404。
    let audio_uri = format!("rtsp://{}:{}/", p.car_ip, p.airplay_port);
    let resp = match channel
        .request(
            "SETUP",
            &audio_uri,
            &body,
            "application/x-apple-binary-plist",
            &[],
            Duration::from_secs(8),
        )
        .await
    {
        Ok(r) => r,
        Err(e) => {
            emit_log(&format!("AIRPLAY: 音频 SETUP 失败 - {e}"));
            return None;
        }
    };
    if resp.status() != 200 {
        emit_log(&format!("AIRPLAY: 音频流被拒 [{}]", resp.first_line));
        return None;
    }
    let root = bplist::parse(&resp.body).ok()?;
    let stream = root.get("streams").and_then(Value::as_array)?.first()?;
    let data_port = port_of(stream.get("dataPort"));
    let control_port = port_of(stream.get("controlPort"));
    if data_port == 0 {
        emit_log("AIRPLAY: 音频流未返回 dataPort");
        return None;
    }
    let key = match shared {
        Some(s) => stream_key(s, conn_id as u64),
        None => {
            emit_log("AIRPLAY: 未配对会话无法派生音频密钥，跳过音频");
            return None;
        }
    };
    let udp = factory.open_udp(&p.car_ip, data_port)?;
    let sync = if control_port != 0 {
        factory.open_udp(&p.car_ip, control_port)
    } else {
        None
    };
    emit_log(&format!(
        "AIRPLAY: ✓ 音频流就绪 data={data_port} control={control_port}（PCM48k/SPF352）"
    ));
    Some(AudioOut::new(udp, sync, key, 100))
}

// ---------------------------------------------------------------- 心跳

/// 三条心跳里的前两条（屏幕 keepAlive 帧在视频 pump 里按 1s 节流）：
///  - UDP keepAlive：4B 随机、5s（10s 与车机看门狗等长必输——奥迪 3315 实测 10.001s 被拆）
///  - RTSP /feedback：1s 空体 POST（车机 ~3s 看门狗）
pub struct KeepaliveTasks(Vec<tokio::task::JoinHandle<()>>);

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
) -> KeepaliveTasks {
    let mut tasks = Vec::new();
    if keep_alive_port != 0 {
        let stop_k = stop.clone();
        tasks.push(tokio::spawn(async move {
            let Some(udp) = factory.open_udp(&car_ip, keep_alive_port) else {
                emit_log("AIRPLAY: keepAlive UDP 端口不可用");
                return;
            };
            let mut n: u32 = 0;
            // 与 CatPlay 一致，连接后立即首发；后续保留已采用的 5s 间隔。
            let mut tick = tokio::time::interval(Duration::from_secs(5));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                tokio::select! {
                    _ = stop_k.notified() => return,
                    _ = tick.tick() => {}
                }
                n = n.wrapping_add(1);
                if udp.send(&n.to_be_bytes()).await.is_err() {
                    return;
                }
                if n == 1 { emit_log("AIRPLAY: keepAlive UDP 首包已发送"); }
            }
        }));
    }

    tasks.push(tokio::spawn(async move {
        let mut first_reply = true;
        loop {
            tokio::select! {
                _ = stop.notified() => return,
                _ = tokio::time::sleep(Duration::from_secs(1)) => {}
            }
            match channel.request(
                "POST", "/feedback", &[], "application/octet-stream", &[], Duration::from_secs(3),
            ).await {
                Ok(reply) => {
                    if first_reply {
                        emit_log(&format!("AIRPLAY: feedback 首次应答 status={}", reply.status()));
                        first_reply = false;
                    }
                }
                Err(_) => return,
            }
        }
    }));
    KeepaliveTasks(tasks)
}

// ---------------------------------------------------------------- 事件通道分发

fn spawn_event_dispatcher(
    mut inbound: tokio::sync::mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>,
    touch_tx: mpsc::UnboundedSender<TouchInput>,
    key_tx: mpsc::UnboundedSender<u32>,
    display_size: (u32, u32),
    touch_rect: Option<(u32, u32, u32, u32)>,
    target_size: Arc<Mutex<(u32, u32)>>,
    stop: Arc<Notify>,
) {
    tokio::spawn(async move {
        let mut bad_commands = 0usize;
        let mut tip_down = false; // 上一次报告的按下状态（down/move/up 边沿检测）
        let mut seen_commands: Vec<String> = Vec::new();
        let mut hid_reports: u64 = 0;
    let mut touch_logged: u32 = 0; // 触摸换算诊断计数（前几次 + 每 200 次记一行）
        let mut other_lines: u64 = 0;
        loop {
            let msg = tokio::select! {
                _ = stop.notified() => return,
                m = inbound.recv() => match m {
                    Some(m) => m,
                    None => return,
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
            // 事件通道是「车机 → 手机」的唯一通道（触摸/HID/命令）。它是否在工作，
            // 现场只能靠日志判断，所以每类命令第一次出现时记一行（之后不刷屏）。
            if !seen_commands.iter().any(|c| c == cmd_type) {
                if seen_commands.len() < 32 {
                    seen_commands.push(cmd_type.to_string());
                }
                emit_log(&format!("EVENT: 车机命令 type={cmd_type}（首次）"));
            }
            match cmd_type {
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
                        hid_reports += 1;
                        if hid_reports <= 3 || hid_reports % 20 == 0 {
                            emit_log(&format!(
                                "EVENT: HID 报告 #{hid_reports} {}B {}",
                                report.len(),
                                report.iter().take(6).map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(" ")
                            ));
                        }
                        handle_hid_report(
                            report,
                            &touch_tx,
                            &key_tx,
                            display_size,
                            touch_rect,
                            &target_size,
                            &mut tip_down,
                            &mut touch_logged,
                        );
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
                    emit_log("EVENT: 车机请求关键帧 forceKeyFrame → requestSyncFrame");
                    crate::emit_force_keyframe();
                }
                "hidSetInputMode" | "ping" | "fps" => {}
                other => {
                    if bad_commands <= 8 {
                        emit_log(&format!("EVENT: 未处理的车机命令 type={other}"));
                    }
                }
            }
        }
    });
}

fn value_as_bool(v: &bplist::Value) -> Option<bool> {
    v.as_bool().or_else(|| v.as_int().map(|n| n != 0))
}

/// HID 触摸报告 → 本机界面触摸事件。
///
/// 坐标格式（cardeck 奥迪实测；BMW 按 /info hidDevices 描述符校准，见 docs）：
/// 5 字节 `[tip 1bit][X u16LE][Y u16LE]`，X/Y 为车机显示空间绝对坐标；
/// 这里缩放到编码画布空间（VideoConfig 画布）。
///
/// 动作边沿：按下沿 → DOWN(1)，保持 → MOVE(3)，抬起沿 → UP(2)——
/// 本机界面需要完整的 down/move/up 序列才认点按。
fn handle_hid_report(
    report: &[u8],
    touch_tx: &mpsc::UnboundedSender<TouchInput>,
    key_tx: &mpsc::UnboundedSender<u32>,
    display: (u32, u32),
    touch_rect: Option<(u32, u32, u32, u32)>,
    target_size: &Arc<Mutex<(u32, u32)>>,
    tip_down: &mut bool,
    logged: &mut u32,
) {
    if report.len() < 5 {
        // 短报告按 Consumer 媒体键候选处理（奥迪 uuid=0 是媒体键设备）
        if let Some(code) = report.last().and_then(|_| consumer_key_of(report)) {
            let _ = key_tx.send(code);
        }
        return;
    }
    // 每次触摸都读当前编码分辨率：手机可能把请求的分辨率压缩后回报，
    // 用旧快照会让触摸整体偏移（越靠边越明显）。
    let target = *target_size.lock();
    let target = (target.0.max(2), target.1.max(2));

    // 两种报告布局：
    //  ① **每指 6 字节** `[index][touch][X u16LE][Y u16LE]`，最多 2 指（12 字节）——
    //     苹果触摸描述符 / WinPlay 都是这个（WinPlay: TOUCH_CONTACTS=2、BYTES_PER_FINGER=6）。
    //  ② 旧的 5 字节 `[tip][X u16LE][Y u16LE]`（cardeck 奥迪实测）。
    // 之前只按 ② 解析，于是把 6 字节布局里的 `report[0]`（触点 index=0）当成按下位 →
    // 永远判成"未按下" → 触摸被整段丢弃（现场 2026-10-23 23:35 定位）。
    let (pressed, x_raw, y_raw) = if report.len() >= 6 && report.len() % 6 == 0 {
        // 取第一个真正的指（跳过 index 非 0 或未按下的）
        let mut picked = None;
        for finger in report.chunks_exact(6) {
            if finger[1] & 0x01 != 0 {
                picked = Some(finger);
                break;
            }
        }
        match picked {
            Some(f) => (
                true,
                u16::from_le_bytes([f[2], f[3]]) as u32,
                u16::from_le_bytes([f[4], f[5]]) as u32,
            ),
            // 全部手指都抬起：沿用上次坐标发 UP（坐标本身对抬起不重要）
            None => {
                let f = &report[..6];
                (
                    false,
                    u16::from_le_bytes([f[2], f[3]]) as u32,
                    u16::from_le_bytes([f[4], f[5]]) as u32,
                )
            }
        }
    } else {
        (
            report[0] & 0x01 != 0,
            u16::from_le_bytes([report[1], report[2]]) as u32,
            u16::from_le_bytes([report[3], report[4]]) as u32,
        )
    };
    // 防下溢：`target.0 - 1` 在 target 为 0 时 release 会回绕成巨大值、
    // debug 直接 panic（编码器还没回报尺寸时就是 0）。
    let (tw, th) = (target.0.max(1), target.1.max(1));
    let (dw, dh) = (display.0.max(1), display.1.max(1));
    // DiPlay 实测：画面拉伸铺满整个窗口（无黑边）。HID 是桌面全局坐标时，
    // 先用窗口矩形 (left,top,right,bottom) 归一化，再映射到画布。
    let (x, y) = if let Some((l, t, r, b)) = touch_rect {
        let rw = r.saturating_sub(l).max(1);
        let rh = b.saturating_sub(t).max(1);
        (
            x_raw.saturating_sub(l).saturating_mul(tw) / rw,
            y_raw.saturating_sub(t).saturating_mul(th) / rh,
        )
    } else {
        (
            x_raw.saturating_mul(tw) / dw,
            y_raw.saturating_mul(th) / dh,
        )
    };
    let x = x.min(tw.saturating_sub(1));
    let y = y.min(th.saturating_sub(1));
    *logged = logged.wrapping_add(1);
    if *logged <= 5 || *logged % 200 == 0 {
        crate::emit_log(&format!(
            "TOUCH: 车机({x_raw},{y_raw}) 空间 {dw}x{dh} → 画布 {tw}x{th} = ({x},{y})（第 {n} 次）",
            n = *logged
        ));
        if *logged == 1 {
            let rd = dw as f32 / dh as f32;
            let rt = tw as f32 / th as f32;
            if (rd - rt).abs() / rt.max(0.1) > 0.03 {
                crate::emit_log(&format!(
                    "TOUCH: ⚠ 触摸空间 {dw}x{dh}（{rd:.2}:1）与画布 {tw}x{th}（{rt:.2}:1）宽高比不一致——\
                     接收端拉伸显示时线性映射正确；若它加了黑边，边缘会偏",
                ));
            }
        }
    }
    // 动作边沿：按下沿 → DOWN，保持 → MOVE，抬起沿 → UP（0=DOWN/1=UP/2=MOVE）。
    // 下游由 Kotlin 侧的自绘界面消费（P1 接线），这里只负责换算坐标与边沿。
    const TOUCH_DOWN: u32 = 0;
    const TOUCH_UP: u32 = 1;
    const TOUCH_MOVE: u32 = 2;
    let action = match (*tip_down, pressed) {
        (false, true) => TOUCH_DOWN,
        (true, true) => TOUCH_MOVE,
        (true, false) => TOUCH_UP,
        (false, false) => return, // 无状态变化
    };
    *tip_down = pressed;
    let _ = touch_tx.send(TouchInput { action, x, y });
}
/// Consumer 页媒体键的极简映射。
fn consumer_key_of(report: &[u8]) -> Option<u32> {
    let usage = *report.last()?;
    let code = match usage {
        0xCD => 0x10, // play/pause
        0xB5 => 0x0E, // next
        0xB6 => 0x0F, // prev
        _ => return None,
    };
    Some(code)
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
fn aes_stream_keys_zero(conn_id: u64) -> ([u8; 16], [u8; 16]) {
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
fn uuid_text(v: Option<&Value>) -> Option<String> {
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

fn port_of(v: Option<&Value>) -> u16 {
    match v {
        Some(Value::Int(i)) => (*i).clamp(0, u16::MAX as i64) as u16,
        _ => 0,
    }
}

fn random_positive_i64() -> i64 {
    let mut b = [0u8; 8];
    getrandom::getrandom(&mut b).unwrap_or_default();
    (i64::from_le_bytes(b) & i64::MAX).max(1)
}

fn random_uuid_upper() -> String {
    uuid::Uuid::new_v4().simple().to_string().to_uppercase()
}
