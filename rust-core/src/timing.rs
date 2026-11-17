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
use std::net::SocketAddr;
use std::sync::Arc;
use parking_lot::Mutex;

use once_cell::sync::Lazy;

/// 非 Android 测试环境的时间基。Android 直接使用 CLOCK_MONOTONIC。
/// timesync、音频同步包和视频 PTS **必须共用同一原点**，
/// 否则车机算出的媒体时钟锚点会带一个常量偏移（音画不同步）。
static PROCESS_START: Lazy<std::time::Instant> = Lazy::new(std::time::Instant::now);

#[derive(Clone,Debug,Default,serde::Serialize)]
pub struct TimingDiagnostics {
    pub requests:u64,
    pub replies:u64,
    pub invalid_datagrams:u64,
    pub send_errors:u64,
    pub receive_errors:u64,
    pub last_request_ms:Option<u64>,
    pub last_reply_ms:Option<u64>,
    pub request_age_ms:Option<u64>,
    pub reply_age_ms:Option<u64>,
    pub last_local_reply_us:Option<u64>,
}
impl TimingDiagnostics {
    pub fn refresh_age(&mut self,now_ms:u64) {
        self.request_age_ms=self.last_request_ms.map(|t|now_ms.saturating_sub(t));
        self.reply_age_ms=self.last_reply_ms.map(|t|now_ms.saturating_sub(t));
    }
    pub fn summary(&self)->String {
        format!("requests={} replies={} invalid={} send_errors={} receive_errors={} request_age_ms={:?} reply_age_ms={:?} local_reply_us={:?}",
            self.requests,self.replies,self.invalid_datagrams,self.send_errors,self.receive_errors,self.request_age_ms,self.reply_age_ms,self.last_local_reply_us)
    }
}

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

/// Use the accepted or outbound control socket's local address, including its IPv6 interface.
/// Android can represent IPv4 control sockets as IPv4-mapped IPv6.
fn timing_bind_addr(mut local: SocketAddr) -> SocketAddr {
    if let SocketAddr::V6(v6) = local {
        if let Some(ipv4) = v6.ip().to_ipv4_mapped() {
            return SocketAddr::new(ipv4.into(), 0);
        }
    }
    local.set_port(0);
    local
}

/// 会话 SETUP 通告本地 UDP 端口，后台任务应答校时请求。
pub struct TimingServer {
    /** 实际绑定的本地 UDP 端口（通告给车机的 timingPort）。 */
    pub port: u16,
    /** 应答循环任务句柄（`stop()` 时 abort）。 */
    task: tokio::task::JoinHandle<()>,
    #[cfg(test)]
    diagnostics: Arc<Mutex<TimingDiagnostics>>,
}

impl TimingServer {
    pub fn start(
        control_local: SocketAddr,
        bind_network: impl FnOnce(&std::net::UdpSocket) -> std::io::Result<()>,
        pipeline: Arc<Mutex<crate::pipeline::Pipeline>>,
    ) -> std::io::Result<Self> {
        let std_socket = std::net::UdpSocket::bind(timing_bind_addr(control_local))?;
        bind_network(&std_socket)?;
        std_socket.set_nonblocking(true)?; // tokio 要求非阻塞模式
        let local = std_socket.local_addr()?;
        let port = local.port();
        let socket = UdpSocket::from_std(std_socket)?;
        crate::emit_log(&format!("TIMING: listening local={local} control_local={control_local}"));

        let diagnostics=Arc::new(Mutex::new(TimingDiagnostics::default()));
        let stats=diagnostics.clone();
        let task = tokio::spawn(async move {
            let mut buf = [0u8; 256];
            loop {
                let (len, peer) = match socket.recv_from(&mut buf).await {
                    Ok(v) => v,
                    Err(error) => {
                        stats.lock().receive_errors+=1;
                        pipeline.lock().timing=stats.lock().clone();
                        crate::emit_log(&format!("TIMING: receive failed: {error}"));
                        break;
                    }
                };
                // 与音频/视频共用时间基（见 monotonic_nanos）
                let now_nanos = monotonic_nanos();
                // 非 timesync 报文静默丢弃（Kotlin 侧只计数不回包）
                if let Some(response) = build_timesync_response(&buf[..len], now_nanos) {
                    let first_request = {
                        let mut s=stats.lock();s.requests+=1;s.last_request_ms=Some(now_nanos/1_000_000);
                        s.requests == 1
                    };
                    if first_request {
                        crate::emit_log(&format!("TIMING: first request peer={peer} local={local} bytes={len}"));
                    }
                    let sent=socket.send_to(&response, peer).await;
                    let done=monotonic_nanos();
                    let snapshot={
                        let mut s=stats.lock();
                        match sent {
                            Ok(PACKET_BYTES)=>{s.replies+=1;s.last_reply_ms=Some(done/1_000_000);s.last_local_reply_us=Some(done.saturating_sub(now_nanos)/1000);},
                            _=>s.send_errors+=1,
                        }
                        s.refresh_age(done/1_000_000);s.clone()
                    };
                    pipeline.lock().timing=snapshot.clone();
                    match &sent {
                        Ok(PACKET_BYTES) if snapshot.replies == 1 =>
                            crate::emit_log(&format!("TIMING: first reply local={local} peer={peer} bytes={PACKET_BYTES}")),
                        Err(error) => crate::emit_log(&format!("TIMING: reply failed local={local} peer={peer}: {error}")),
                        _ => {}
                    }
                    if snapshot.requests==1 || snapshot.requests%10==0 || !matches!(sent,Ok(PACKET_BYTES)) {
                        crate::emit_log(&format!("TIMING: {}; local reply cost is not network RTT",snapshot.summary()));
                    }
                } else {
                    let snapshot={let mut s=stats.lock();s.invalid_datagrams+=1;s.clone()};
                    pipeline.lock().timing=snapshot;
                }
            }
        });

        Ok(Self { port, task, #[cfg(test)] diagnostics })
    }


}

impl Drop for TimingServer {
    fn drop(&mut self) { self.task.abort(); }
}

#[cfg(test)]
mod diagnostics_tests {
    use super::*;
    #[test]
    fn control_address_preserves_ipv6_scope_and_normalizes_mapped_ipv4() {
        assert_eq!(timing_bind_addr("[fe80::5%19]:7000".parse().unwrap()),
            "[fe80::5%19]:0".parse().unwrap());
        assert_eq!(timing_bind_addr("[::ffff:192.0.2.1]:7000".parse().unwrap()),
            "192.0.2.1:0".parse().unwrap());
    }
    #[tokio::test]
    async fn live_udp_counts_replies_and_invalid_datagrams() {
        for ip in [std::net::IpAddr::from([127,0,0,1]), std::net::Ipv6Addr::LOCALHOST.into()] {
            check_round_trip(ip).await;
        }
    }

    async fn check_round_trip(ip: std::net::IpAddr) {
        let pipeline=Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        let server=TimingServer::start(SocketAddr::new(ip, 7000), |socket| {
            assert_eq!(socket.local_addr()?.ip(), ip);
            Ok(())
        }, pipeline.clone()).unwrap();
        let peer=UdpSocket::bind((ip,0)).await.unwrap();
        peer.connect((ip,server.port)).await.unwrap();
        peer.send(&[1,2,3]).await.unwrap();
        let mut request=[0u8;32];request[0]=0x80;request[1]=0xd2;request[3]=7;
        request[24..32].copy_from_slice(&123456u64.to_be_bytes());
        peer.send(&request).await.unwrap();
        let mut reply=[0u8;256];
        let len=tokio::time::timeout(std::time::Duration::from_secs(1),peer.recv(&mut reply)).await.unwrap().unwrap();
        assert_eq!(len,32);assert_eq!(reply[1],0xd3);assert_eq!(reply[8..16],request[24..32]);
        let s=server.diagnostics.lock().clone();
        assert_eq!(s.requests,1);assert_eq!(s.replies,1);assert_eq!(s.invalid_datagrams,1);
        assert_eq!(s.send_errors,0);assert!(s.last_local_reply_us.is_some());
        let mut p=pipeline.lock().timing.clone();
        p.refresh_age(p.last_reply_ms.unwrap()+5000);assert_eq!(p.reply_age_ms,Some(5000));
        drop(server);
    }
}
