//! AirPlay 计时服务（NTP over RTCP，timesync 应答）。
//!
//! 协议依据（对照参考实现 cardeck `airplay/TimingServer.kt`）：
//! 初始 SETUP 通告本端口（timingPort）；车机（媒体时钟主）向它发 32 字节 timesync
//! 请求，我们按 NTP 语义应答：t1=回显请求的 t3（客户端发送时刻）、t2=本地接收时刻、
//! t3=本地发送时刻。缺失时序（曾通告 timingPort=0）时车机无法与我们同步媒体时钟，
//! 是屏幕流 SETUP 被 400 的头号嫌疑（catplay 真车实现始终绑定真实端口）。
//!
//! 线格式（大端）：`[0x80][pt][length=7][rtp_ts=0][t1(8)][t2(8)][t3(8)]`，
//! pt：0xD2 请求 / 0xD3 应答；时间 = NTP 64 位（秒<<32 | 2^32 分数），取自单调时钟
//! ——NTP 差值计算只用相对量，任意起点均可（与 Kotlin 的 System.nanoTime 同语义）。

use tokio::net::UdpSocket;

use once_cell::sync::Lazy;

/// 非 Android 测试环境的时间基。Android 直接使用 CLOCK_MONOTONIC。
/// timesync、音频同步包和视频 PTS **必须共用同一原点**，
/// 否则车机算出的媒体时钟锚点会带一个常量偏移（音画不同步）。
static PROCESS_START: Lazy<std::time::Instant> = Lazy::new(std::time::Instant::now);

/// Android 与 Java System.nanoTime/MediaCodec PTS 同源的单调纳秒数。
pub fn monotonic_nanos() -> u64 {
    #[cfg(target_os = "android")]
    {
        // MediaCodec Surface PTS and Java System.nanoTime use CLOCK_MONOTONIC.
        // A process-relative Instant here makes timing/audio disagree with video by device uptime.
        let mut time = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut time) } == 0 {
            return (time.tv_sec as u64).saturating_mul(1_000_000_000) + time.tv_nsec as u64;
        }
    }
    PROCESS_START.elapsed().as_nanos() as u64
}

/** timesync 请求 payload type（RTCP）。 */
const PT_TIMESYNC_REQUEST: u8 = 0xD2;
/** timesync 应答 payload type。 */
const PT_TIMESYNC_RESPONSE: u8 = 0xD3;
/** timesync 报文定长（8 × 4 字节字）。 */
const PACKET_BYTES: usize = 32;

/// 单调纳秒 → NTP 64 位（秒<<32 | 分数），大端 8 字节。
///
/// 分数 = (纳秒分数 << 32) / 1e9（对照 TimingServer.ntpFromNanos 的 floorDiv/floorMod
/// 语义；单调时钟 nanos 非负，直接用 `/`、`%` 即可）。
/// 秒字段截断到低 32 位（与 Kotlin `(secs shl 32)` 的静默截断一致）——单调时钟
/// 秒数超过 2^32（约 136 年）才会触发，无实际影响。
pub fn ntp_be(nanos: u64) -> [u8; 8] {
    let secs = nanos / 1_000_000_000;
    let frac_nanos = nanos % 1_000_000_000;
    let frac = (frac_nanos << 32) / 1_000_000_000;
    let packed = ((secs & 0xFFFF_FFFF) << 32) | (frac & 0xFFFF_FFFF);
    packed.to_be_bytes()
}

/// 构造 timesync 应答；非 timesync 请求返回 None。
///
/// 校验（对照 TimingServer.buildResponse 逐条）：
///  - 报文 ≥ 32 字节；
///  - 版本位（request[0] >> 6）== 2；
///  - pt == 0xD2；
///  - length 字段（request[2..4] 大端）== 7（= 32/4 - 1，RTCP 长度以 4 字节字计）。
///
/// `now_nanos`：单调时钟当前值（任意起点）。
/// 应答布局：`[0]=0x80 [1]=0xD3 [2..4]=00 07`、rtp_ts(4..8)=0、
/// t1(8..16)=回显请求的 t3(request[24..32))、t2(16..24)=t3(24..32)=now 的 NTP。
pub fn build_timesync_response(request: &[u8], now_nanos: u64) -> Option<[u8; PACKET_BYTES]> {
    if request.len() < PACKET_BYTES {
        return None;
    }
    if (request[0] >> 6) != 2 {
        return None;
    }
    if request[1] != PT_TIMESYNC_REQUEST {
        return None;
    }
    let length = ((request[2] as u16) << 8) | (request[3] as u16);
    if length as usize != PACKET_BYTES / 4 - 1 {
        return None;
    }

    let mut response = [0u8; PACKET_BYTES];
    response[0] = 2 << 6; // V=2
    response[1] = PT_TIMESYNC_RESPONSE;
    response[2] = 0;
    response[3] = (PACKET_BYTES / 4 - 1) as u8; // length = 7
    // rtp_ts(4..8) 保持 0
    response[8..16].copy_from_slice(&request[24..32]); // t1 = 请求的 t3（ntp_xmit 回显）
    let now = ntp_be(now_nanos);
    response[16..24].copy_from_slice(&now); // t2 = 接收时刻
    response[24..32].copy_from_slice(&now); // t3 = 发送时刻
    Some(response)
}

/// AirPlay 计时服务：绑定 0.0.0.0:0 UDP（端口由 OS 分配， SETUP 应答里把这个
/// 端口作为 timingPort 通告给车机），后台任务应答 timesync 请求直到 `stop()`。
pub struct TimingServer {
    /** 实际绑定的本地 UDP 端口（通告给车机的 timingPort）。 */
    pub port: u16,
    /** 应答循环任务句柄（`stop()` 时 abort）。 */
    task: tokio::task::JoinHandle<()>,
}

impl TimingServer {
    /// 绑定 0.0.0.0:0 并启动永久应答循环（tokio::spawn）。
    ///
    /// 非 async 签名下无法直接 await 绑定，故走「std 绑定 → 非阻塞 →
    /// `UdpSocket::from_std`」路径；`from_std` 需要注册到 reactor，因此**必须在
    /// tokio runtime 上下文中调用**（会话流程本就跑在 async 里，天然满足）。
    pub fn start() -> std::io::Result<Self> {
        let std_socket = std::net::UdpSocket::bind("0.0.0.0:0")?;
        std_socket.set_nonblocking(true)?; // tokio 要求非阻塞模式
        let port = std_socket.local_addr()?.port();
        let socket = UdpSocket::from_std(std_socket)?;

        let task = tokio::spawn(async move {
            let mut buf = [0u8; 256];
            loop {
                let (len, peer) = match socket.recv_from(&mut buf).await {
                    Ok(v) => v,
                    Err(_) => break, // socket 关闭/致命错误：退出循环
                };
                // 与音频/视频共用时间基（见 monotonic_nanos）
                let now_nanos = monotonic_nanos();
                // 非 timesync 报文静默丢弃（Kotlin 侧只计数不回包）
                if let Some(response) = build_timesync_response(&buf[..len], now_nanos) {
                    let _ = socket.send_to(&response, peer).await; // 发送失败不值得终止循环
                }
            }
        });

        Ok(Self { port, task })
    }

    /// 停止应答循环（abort 后台任务；socket 随任务一起丢弃）。
    pub fn stop(&mut self) {
        self.task.abort();
    }
}

impl Drop for TimingServer {
    fn drop(&mut self) { self.task.abort(); }
}
