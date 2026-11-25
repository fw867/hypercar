//! CarPlay 主音频发送端。type103 的 TCP 分帧和播放控制见 buffered 子模块。
//! 以下为实时音频（RTP over UDP + ChaCha20-Poly1305，对照 cardeck
//! `audio/AudioSender.kt` 与 `audio/AudioSyncPacket.kt`）。
//!
//! 线格式：`[12B RTP 头][ChaCha20-Poly1305 密封 PCM + 16B tag][8B 小端 nonce]`
//!  - 加密：ChaCha20-Poly1305；AAD = RTP 头字节 4..12（timestamp + SSRC 段，
//!    catplay "modern AAD"，DiPlay 接收端解密取同一范围）；
//!  - nonce(12B) = 4 字节零 + 8 字节小端计数（crate::crypto::nonce64，逐包递增，
//!    与 RTP seq/timestamp 相互独立）；尾部再附同样的 8 字节小端计数，供接收端
//!    重组 12 字节 nonce；
//!  - RTP 字段：v=2、PT=流类型（PCM 通路 100 / AAC 通路 102，catplay
//!    `payload_type = stream_type`）、seq 逐包递增、timestamp = 累计样本帧数、
//!    **SSRC=0 写死**——部分车机按 SSRC 关联 SETUP 声明的流，用随机值会被当
//!    未知流丢弃 → 无声（2026-10-17 真车实测）；
//!  - 首个音频包置 Marker 位：RECORD/FLUSH 之后的首包要打标记，接收端据此切分
//!    播放边界（参考实现同款；catplay 恒 false，但我们的会话是 RECORD 之后才加入
//!    音频流，首包标记帮接收端重新对齐）。
//!
//! 每包 352 样本帧（AirPlay 标准 SPF，catplay `MAX_FRAMES_PER_PACKET_PCM`；此前用
//! 512 在真车无声——接收端可能按固定 SPF 缓冲而丢弃与真 iPhone 不一致的包长），
//! 2 通道 16bit = 1408B 负载。
//!
//! 旁路同步包：每 1 秒发往该流的 controlPort（AudioSyncPacket，PT=84），建立
//! RTP↔NTP 锚点——接收端需要它才能算出「第 N 个样本应在什么时刻送 DAC」；缺了
//! 它车机可能照收 UDP、/feedback 的 sampleTime 一路推进，但喇叭一个样本都不出
//! （2026-10-20 实测；视频不受影响，因为每个视频帧自带呈现时间戳）。

use crate::crypto::{chacha_seal, nonce64};
mod buffered;
mod aac_worker;
#[cfg(all(test, target_os = "android"))]
pub(crate) use buffered::tests::{accept_start, control_pair};
use crate::timing::ntp_be;
use std::sync::{Arc, atomic::{AtomicU64, Ordering}};
use std::time::{Duration, Instant};
use tokio::net::{TcpStream, UdpSocket};

/** 每包样本帧数 = AirPlay 标准 SPF 352（48000/352 ≈ 136 包/秒）。 */
pub const SAMPLES_PER_PACKET: usize = 352;
/** 立体声。 */
pub const CHANNELS: usize = 2;

/** RTP 头定长（无 CSRC/扩展）。 */
const RTP_HEADER_LEN: usize = 12;
/** 包尾小端 nonce 计数长度。 */
const NONCE_TAIL_LEN: usize = 8;
/** 同步包定长（8B 头 + 8B NTP + 4B 下一包时间戳）。 */
const SYNC_PACKET_LEN: usize = 20;

enum AudioTransport {
    Realtime(UdpSocket),
    Buffered(buffered::BufferedTransport),
}

#[derive(Clone, Copy)]
struct RtpHeader {
    marker: bool,
    seq: u16,
    rtp_ts: u32,
}

#[derive(Clone, Copy)]
struct SyncHeader {
    seq: u16,
    rtp_ts: u32,
    ntp_nanos: u64,
}

struct AudioResumeTrace {
    label: String,
    unborrow: Arc<AtomicU64>,
    observed: u64,
    last_rtp: Option<RtpHeader>,
    last_sync: Option<SyncHeader>,
    first_rtp_pending: bool,
    first_sync_pending: bool,
    logged_sync_error: bool,
}

impl AudioResumeTrace {
    fn emit(&self, fields: std::fmt::Arguments<'_>) {
        crate::emit_log(&format!("AUDIO-RESUME: {} unborrow={} monoNs={} {fields}",
            self.label, self.observed, crate::timing::monotonic_nanos()));
    }

    fn generation(&self) -> u64 {
        self.unborrow.load(Ordering::Acquire)
    }

    fn observe_unborrow(&mut self, generation: u64, has_sync_socket: bool) {
        if generation == self.observed { return; }
        self.observed = generation;
        self.first_rtp_pending = true;
        self.first_sync_pending = has_sync_socket;
        self.logged_sync_error = false;
        if let Some(RtpHeader { marker, seq, rtp_ts }) = self.last_rtp {
            self.emit(format_args!("phase=last_before_rtp written=true marker={marker} seq={seq} rtpTimestamp={rtp_ts}"));
        } else {
            self.emit(format_args!("phase=last_before_rtp written=false"));
        }
        if let Some(SyncHeader { seq, rtp_ts, ntp_nanos }) = self.last_sync {
            self.emit(format_args!("phase=last_before_sync written=true syncSeq={seq} rtpTimestamp={rtp_ts} ntpMonoNs={ntp_nanos}"));
        } else {
            self.emit(format_args!("phase=last_before_sync written=false"));
        }
        if !has_sync_socket {
            self.emit(format_args!("phase=first_after_sync written=false reason=no_control_socket"));
        }
    }

    fn rtp_written(&mut self, packet: RtpHeader) {
        if std::mem::take(&mut self.first_rtp_pending) {
            let RtpHeader { marker, seq, rtp_ts } = packet;
            self.emit(format_args!("phase=first_after_rtp written=true marker={marker} seq={seq} rtpTimestamp={rtp_ts}"));
        }
        self.last_rtp = Some(packet);
    }

    fn sync_result(&mut self, packet: SyncHeader, result: &std::io::Result<usize>) {
        let SyncHeader { seq, rtp_ts, ntp_nanos } = packet;
        match result {
            Ok(_) => {
                if std::mem::take(&mut self.first_sync_pending) {
                    self.emit(format_args!("phase=first_after_sync written=true syncSeq={seq} rtpTimestamp={rtp_ts} ntpMonoNs={ntp_nanos}"));
                }
                self.last_sync = Some(packet);
            }
            Err(error) if self.first_sync_pending && !self.logged_sync_error => {
                self.logged_sync_error = true;
                self.emit(format_args!("phase=sync_after_unborrow_attempt written=false syncSeq={seq} errorKind={:?} osError={:?}",
                    error.kind(), error.raw_os_error()));
            }
            Err(_) => {}
        }
    }
}

#[derive(Default)]
struct UdpDropWindow {
    first_mono_ns: u64,
    last_mono_ns: u64,
    would_block: u64,
    would_block_errno: Option<i32>,
    enobufs: u64,
}

impl UdpDropWindow {
    fn record(&mut self, error: &std::io::Error, mono_ns: u64) {
        if self.would_block == 0 && self.enobufs == 0 {
            self.first_mono_ns = mono_ns;
        }
        self.last_mono_ns = mono_ns;
        if error.raw_os_error() == Some(libc::ENOBUFS) {
            self.enobufs += 1;
        } else {
            self.would_block += 1;
            self.would_block_errno = error.raw_os_error();
        }
    }
}

struct PendingPcm {
    wire: Vec<u8>,
    has_signal: bool,
    seq: u16,
    diagnostic: Option<PcmWriteFailure>,
    payload_bytes: usize,
    expires: Instant,
    ownership_generation: Option<u64>,
    cache_generation: Option<u64>,
}

#[derive(Clone, Copy)]
struct PcmWriteFailure {
    seq: u16,
    rtp_ts: u32,
    mono_ns: u64,
    errno: Option<i32>,
}

// The next report can also receive the outcome of one pending packet from the prior window.
const PCM_DETAIL_LIMIT: usize = 4;
struct PcmPacketOutcome {
    write: PcmWriteFailure,
    mono_ns: u64,
    reason: &'static str,
    retry_errno: Option<i32>,
}

#[derive(Default)]
struct PcmRetryWindow {
    new_writes: u64,
    max_new_delay_us: u128,
    saved: u64,
    replaced: u64,
    expired: u64,
    no_slack: u64,
    discarded: u64,
    sent: u64,
    failed: u64,
    last_errno: Option<i32>,
    sampled: usize,
    suppressed: u64,
    outcomes: [Option<PcmPacketOutcome>; PCM_DETAIL_LIMIT + 1],
}

impl PcmRetryWindow {
    fn sample(&mut self, rtp: RtpHeader, mono_ns: u64, errno: Option<i32>) -> Option<PcmWriteFailure> {
        if self.sampled == PCM_DETAIL_LIMIT {
            self.suppressed += 1;
            return None;
        }
        self.sampled += 1;
        Some(PcmWriteFailure { seq: rtp.seq, rtp_ts: rtp.rtp_ts, mono_ns, errno })
    }

    fn finish(&mut self, write: Option<PcmWriteFailure>, reason: &'static str, retry_errno: Option<i32>) {
        if let Some(write) = write {
            if let Some(slot) = self.outcomes.iter_mut().find(|slot| slot.is_none()) {
                *slot = Some(PcmPacketOutcome { write, mono_ns: crate::timing::monotonic_nanos(), reason, retry_errno });
            }
        }
    }
}

/// CarPlay 音频发送器。
pub struct AudioOut {
    transport: AudioTransport,
    /** 同步包套接字（调用方 connect 好到车机 controlPort；车机没下发时为 None）。 */
    sync: Option<std::sync::Arc<UdpSocket>>,
    control_observer: Option<crate::audio_diagnostics::ControlObserver>,
    d4: Option<crate::audio_diagnostics::D4Trace>,
    rtp_position: Option<(Arc<crate::audio_diagnostics::RtpPosition>, tokio::sync::watch::Receiver<crate::buffered_ownership::Ownership>)>,
    #[cfg(test)]
    before_rtp_write: Option<Box<dyn FnOnce() + Send + Sync>>,
    #[cfg(test)]
    before_aac_worker_admission: Option<Box<dyn FnOnce() + Send + Sync>>,
    pub(crate) pacing_id: Option<crate::audio_pacing::StreamId>,
    pub(crate) high_accuracy_timestamps: bool,
    pub(crate) io_costs: Option<crate::audio_pacing::AudioIoCosts>,
    resume_trace: Option<AudioResumeTrace>,
    /** 流密钥（pair-verify 派生，32 字节）。 */
    key: [u8; 32],
    /** RTP 序号（逐包 wrapping 递增）。 */
    seq: u16,
    /** RTP 时间戳 = 累计样本帧数。 */
    ts: u32,
    /** ChaCha nonce 计数器（逐包递增，独立于 seq/ts）。 */
    nonce: u64,
    /** RTP 负载类型（流类型，调用方按 SETUP 结果传入，PCM 通路 100）。 */
    pt: u8,
    /** 已发包数（对外统计）。 */
    packets: u64,
    pub(crate) congestion_drops: u64,
    udp_drop_window: UdpDropWindow,
    pcm_tick_due: Option<Instant>,
    pcm_tick_signal_packets: u64,
    pending_pcm: Option<PendingPcm>,
    pcm_retry: PcmRetryWindow,
    pcm_ownership: Option<tokio::sync::watch::Receiver<crate::buffered_ownership::Ownership>>,
    payload_bytes: u64,
    /** 同步包自己的序号（与音频 RTP 序号无关，独立递增）。 */
    sync_seq: u16,
    /** 首包 Marker 待打标记（打完即清）。 */
    marker_pending: bool,
    ownership: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    blocked_samples: u64,
    aac: Option<crate::aac::AacEncoder>,
    aac_worker: Option<aac_worker::AacWorker>,
    aac_input_ts: u32,
    pub(crate) expired_aac_frames: u64,
    pub(crate) expired_pcm_frames: u64,
    pub(crate) aac_queue_dropped_frames: u64,
    opus: Option<crate::opus_encode::OpusEncoder>,
    aac_needs_reset: bool,
    sample_rate: u32,
}

impl AudioOut {
    /// 构造。data/sync 两个套接字都由调用方 connect 好（本类型只用 `send`）。
    pub fn new(
        data: UdpSocket,
        sync: Option<UdpSocket>,
        stream_key: [u8; 32],
        payload_type: u8,
    ) -> Self {
        Self::with_transport(AudioTransport::Realtime(data), sync, stream_key, payload_type)
    }

    fn with_transport(transport: AudioTransport, sync: Option<UdpSocket>, stream_key: [u8; 32], payload_type: u8) -> Self {
        Self {
            transport,
            sync: sync.map(std::sync::Arc::new),
            control_observer: None,
            d4: None,
            rtp_position: None,
            #[cfg(test)]
            before_rtp_write: None,
            #[cfg(test)]
            before_aac_worker_admission: None,
            pacing_id: None,
            high_accuracy_timestamps: false,
            io_costs: None,
            resume_trace: None,
            key: stream_key,
            seq: 0,
            ts: 0,
            nonce: 0,
            pt: payload_type,
            packets: 0,
            congestion_drops: 0,
            udp_drop_window: UdpDropWindow::default(),
            pcm_tick_due: None,
            pcm_tick_signal_packets: 0,
            pending_pcm: None,
            pcm_retry: Default::default(),
            pcm_ownership: None,
            payload_bytes: 0,
            sync_seq: 0,
            marker_pending: true,
            ownership: None,
            blocked_samples: 0,
            aac: None,
            aac_worker: None,
            aac_input_ts: 0,
            expired_aac_frames: 0,
            expired_pcm_frames: 0,
            aac_queue_dropped_frames: 0,
            opus: None,
            aac_needs_reset: false,
            sample_rate: 48000,
        }
    }

    pub fn new_pcm(data: UdpSocket, sync: Option<UdpSocket>, key: [u8;32], rate: u32) -> Self {
        let mut output = Self::new(data, sync, key, 100);
        output.sample_rate = rate;
        output
    }

    pub fn new_aac(data: UdpSocket, sync: Option<UdpSocket>, key: [u8;32], encoder: crate::aac::AacEncoder) -> Self {
        let mut output = Self::new(data, sync, key, 102);
        output.sample_rate = encoder.sample_rate();
        output.aac = Some(encoder);
        output
    }
    pub fn new_buffered_aac(data: TcpStream, key: [u8;32], encoder: crate::aac::AacEncoder,
        control: crate::buffered_control::BufferedControl, buffer_bytes: u32) -> Self {
        let buffered = buffered::BufferedTransport::new(data, control, buffer_bytes);
        let mut output = Self::with_transport(AudioTransport::Buffered(buffered), None, key, 103);
        output.sample_rate = encoder.sample_rate();
        output.aac = Some(encoder);
        output
    }
    pub fn new_opus(data: UdpSocket, sync: Option<UdpSocket>, key: [u8;32], sample_rate:u32, samples_per_packet:usize, stream_type:u8) -> std::io::Result<Self> {
        let encoder=crate::opus_encode::OpusEncoder::new(sample_rate,samples_per_packet)?;
        let mut output=Self::new(data,sync,key,stream_type);
        output.sample_rate=encoder.sample_rate();output.opus=Some(encoder);Ok(output)
    }
    pub(crate) fn offload_aac(&mut self, ownership: &crate::buffered_ownership::BufferedOwnership) -> std::io::Result<()> {
        if let Some(encoder) = self.aac.take() {
            self.aac_input_ts = self.ts;
            self.aac_worker = Some(aac_worker::AacWorker::start(encoder, ownership.subscribe())?);
        }
        Ok(())
    }

    #[cfg(test)]
    pub(crate) fn echo_pcm_through_aac_worker(&mut self, ownership: &crate::buffered_ownership::BufferedOwnership) {
        self.aac_worker = Some(aac_worker::AacWorker::spawn(ownership.subscribe(), |_, pcm| {
            Ok(pcm.chunks_exact(crate::aac::FRAMES * CHANNELS)
                .map(|unit| unit.iter().flat_map(|sample| sample.to_be_bytes()).collect()).collect())
        }).unwrap());
    }

    pub(crate) fn observe_main_stream(&mut self, rtsp: u64, stream_id: i64, peer_port: u16,
        setup_accepted_at: std::time::Instant, ownership: &crate::buffered_ownership::BufferedOwnership,
        listening: Option<(UdpSocket, std::net::IpAddr)>) {
        self.pacing_id = Some(crate::audio_pacing::StreamId { rtsp, stream_id, setup_accepted_at });
        self.track_rtp_ownership(ownership);
        if self.pt == 100 && self.opus.is_none() {
            self.pcm_ownership = Some(ownership.subscribe());
        }
        let label = format!("RTSP#{rtsp} type={} streamConnectionID={stream_id} negotiatedHz={}", self.pt, self.sample_rate);
        if self.pt == 103 {
            crate::emit_log(&format!("AUDIO-RTCP: {label} observation inapplicable transport=TCP"));
            return;
        }
        if !matches!(self.pt, 100 | 102) { return; }
        self.observe_d4(rtsp, stream_id, peer_port);
        match (&self.sync, listening) {
            (Some(socket), _) => {
                self.control_observer = Some(crate::audio_diagnostics::ControlObserver::start(
                    socket.clone(), label, ownership.subscribe(), self.pt == 100, None));
            }
            (None, Some((socket, peer))) => {
                self.control_observer = Some(crate::audio_diagnostics::ControlObserver::start(
                    Arc::new(socket), label, ownership.subscribe(), self.pt == 100, Some(peer)));
            }
            (None, None) => {
                let reason = if peer_port == 0 { "no_control_port" } else { "socket_unavailable" };
                crate::emit_log(&format!("AUDIO-RTCP: {label} observation unavailable reason={reason} peerControlPort={peer_port}"));
            }
        }
    }

    pub(crate) fn observe_d4(&mut self, rtsp: u64, stream_id: i64, peer_port: u16) {
        self.d4 = crate::audio_diagnostics::D4Trace::start(rtsp, stream_id, self.pt, peer_port, self.sync.is_some());
    }

    pub(crate) fn feedback_source(&self) -> Option<crate::audio_diagnostics::FeedbackSource> {
        let id = self.pacing_id?;
        let (position, _) = self.rtp_position.as_ref()?;
        Some(crate::audio_diagnostics::FeedbackSource {
            target: crate::audio_diagnostics::ClockTarget { rtsp: id.rtsp, stream_id: id.stream_id,
                stream_type: self.stream_type(), rate: self.sample_rate },
            high_accuracy: self.high_accuracy_timestamps,
            position: Arc::downgrade(position),
        })
    }

    pub(crate) fn rtp_ownership(&self) -> Option<crate::buffered_ownership::Ownership> {
        let (_, ownership) = self.rtp_position.as_ref()?;
        Some(*ownership.borrow())
    }

    pub(crate) fn track_rtp_ownership(&mut self, ownership: &crate::buffered_ownership::BufferedOwnership) {
        self.rtp_position = Some((Arc::default(), ownership.subscribe()));
    }

    fn rtp_ownership_generation(&self) -> Option<u64> {
        self.rtp_ownership().filter(|state| state.owned).map(|state| state.losses)
    }

    fn record_media_rtp(&self, generation: Option<u64>, timestamp: u32) {
        if let (Some((position, _)), Some(generation)) = (&self.rtp_position, generation) {
            position.record(generation, timestamp);
        }
    }
    pub fn sample_rate(&self) -> u32 { self.sample_rate }
    pub(crate) fn observe_id9_resume(&mut self, unborrow: Arc<AtomicU64>) {
        if self.pt != 102 { return; }
        let Some(id) = self.pacing_id else { return; };
        self.resume_trace = Some(AudioResumeTrace {
            label: format!("RTSP#{} type=102 streamConnectionID={}", id.rtsp, id.stream_id),
            observed: unborrow.load(Ordering::Acquire),
            unborrow,
            last_rtp: None,
            last_sync: None,
            first_rtp_pending: false,
            first_sync_pending: false,
            logged_sync_error: false,
        });
    }
    pub fn stream_type(&self) -> i64 { self.pt as i64 }

    pub(crate) fn take_udp_drop_report(&mut self) -> Option<String> {
        let window = std::mem::take(&mut self.udp_drop_window);
        if window.would_block == 0 && window.enobufs == 0 { return None; }
        let stream = self.pacing_id.map_or_else(|| "RTSP#NA streamConnectionID=NA".to_owned(),
            |id| format!("RTSP#{} streamConnectionID={}", id.rtsp, id.stream_id));
        let anchor = crate::audio_pacing::boot_clock_anchor()
            .map_or_else(|| "NA".to_owned(), |(mono, boot)| format!("{mono},{boot}"));
        let would_block_errno = window.would_block_errno.map_or_else(|| "NA".to_owned(), |n| n.to_string());
        Some(format!(
            "AUDIO-UDP-DROP: {stream} type={} firstMonoNs={} lastMonoNs={} clockAnchorMonoBootNs={anchor} wouldBlock={} enobufs={} wouldBlockErrno={would_block_errno} enobufsErrno={} totalDrops={}",
            self.pt, window.first_mono_ns, window.last_mono_ns, window.would_block, window.enobufs,
            libc::ENOBUFS, self.congestion_drops,
        ))
    }
    pub(crate) fn take_pcm_retry_report(&mut self) -> Option<String> {
        let w = std::mem::take(&mut self.pcm_retry);
        if w.new_writes == 0 && w.discarded == 0 && w.expired == 0 && w.outcomes[0].is_none() { return None; }
        let stream = self.pacing_id.map(|id| format!("RTSP#{} streamConnectionID={}", id.rtsp, id.stream_id)).unwrap_or_default();
        let mut line = format!("AUDIO-UDP-RETRY: {stream} type=100 monoNs={} newWrites={} maxNewWriteDelayUs={} saved={} replaced={} expired={} noSlack={} discarded={} retrySent={} retryFailed={} lastRetryErrno={:?} detailSuppressed={}",
            crate::timing::monotonic_nanos(), w.new_writes, w.max_new_delay_us, w.saved, w.replaced, w.expired, w.no_slack, w.discarded, w.sent, w.failed, w.last_errno, w.suppressed);
        for detail in w.outcomes.into_iter().flatten() {
            use std::fmt::Write;
            let p = detail.write;
            let _ = write!(line, " [seq={} rtpTs={} writeMonoNs={} writeErrno={} outcome={} outcomeMonoNs={}",
                p.seq, p.rtp_ts, p.mono_ns, p.errno.map_or_else(|| "NA".into(), |n| n.to_string()), detail.reason, detail.mono_ns);
            if let Some(errno) = detail.retry_errno { let _ = write!(line, " retryErrno={errno}"); }
            line.push(']');
        }
        Some(line)
    }

    fn is_aac(&self) -> bool { matches!(self.pt, 102 | 103) && self.opus.is_none() }
    pub fn codec_name(&self) -> &'static str { if self.opus.is_some() { "OPUS" } else if self.is_aac() { "AAC" } else if self.pt==100 { "PCM" } else { "" } }
    pub fn payload_bytes_sent(&self) -> u64 {
        match &self.transport {
            AudioTransport::Buffered(link) => link.payload_bytes,
            AudioTransport::Realtime(_) => self.payload_bytes,
        }
    }
    pub fn samples_per_packet(&self) -> usize { if let Some(opus)=self.opus.as_ref() {opus.samples_per_packet()} else if self.is_aac() { crate::aac::FRAMES } else { SAMPLES_PER_PACKET } }
    pub fn buffered_loss_generation(&self) -> Option<u64> {
        match &self.transport {
            AudioTransport::Realtime(_) => None,
            AudioTransport::Buffered(link) => link.generation(),
        }
    }
    pub fn buffered_failed(&self) -> bool {
        matches!(&self.transport, AudioTransport::Buffered(link) if link.failed())
    }

    /// 发送交错 PCM（48kHz 立体声 i16）。内部按 352 帧切包逐包发送，
    /// **尾部不足一包的余数不发**（由调用方留存到下一批）。
    ///
    /// 负载样本是 **16bit 大端**：CarPlay LPCM 线格式即大端，接收端收到后
    /// byteSwap 回小端喂 AudioTrack（DiPlay AndroidMediaSink 同款）；Android
    /// 采集是原生小端，漏换序实测得到「沙沙声」（cardeck 2026-10-10 真车验证）。
    /// AAC receives little-endian PCM at the encoder input; its raw output is never byte-swapped.
    pub fn set_ownership(&mut self, ownership: std::sync::Arc<std::sync::atomic::AtomicBool>) { self.ownership = Some(ownership); }
    pub fn owns_audio(&self) -> bool {
        self.ownership.as_ref().map(|v| v.load(std::sync::atomic::Ordering::Acquire)).unwrap_or(true)
    }
    pub fn blocked_samples(&self) -> u64 { self.blocked_samples }

    /// Retire elapsed source time without sending late packets. Sequence and AEAD nonce
    /// count transmitted attempts only; the sample clock advances across the discontinuity.
    /// Returns whether the wire timestamp moved immediately. Offloaded AAC
    /// keeps in-flight AUs on their original clock and moves only its input cursor.
    pub(crate) fn skip_source_frames(&mut self, source_frames: u64) -> bool {
        if source_frames == 0 { return false; }
        if self.aac_worker.is_some() {
            self.aac_input_ts = self.aac_input_ts.wrapping_add(source_frames as u32);
            return false;
        }
        // Independent Opus takes 48 kHz input. Main media takes mixer frames at its RTP rate.
        let frames = if self.opus.is_some() {
            (u128::from(source_frames) * u128::from(self.sample_rate) / 48_000) as u32
        } else { source_frames as u32 };
        self.ts = self.ts.wrapping_add(frames);
        self.marker_pending=true;
        // Retain the codec's partial frame. stop/create/start here
        // can itself miss the next deadline and repeatedly starve AAC before 1024 frames.
        // Expired PCM is retired in the mix queues; no elapsed ticks enter this encoder.
        true
    }

    pub async fn send_pcm(&mut self, frames: &[i16]) -> std::io::Result<()> {
        self.send_pcm_for_generation(frames, self.buffered_loss_generation()).await
    }

    pub(crate) async fn send_call_audio(&mut self, payload: &[u8], frames: u32, loss_generation: u64) -> std::io::Result<()> {
        self.send_payload(payload, frames, Some(loss_generation)).await
    }

    pub async fn send_pcm_for_generation(&mut self, frames: &[i16], expected_generation: Option<u64>) -> std::io::Result<()> {
        let rtp_generation = self.rtp_ownership_generation();
        self.send_pcm_recovering(frames, expected_generation, None, rtp_generation).await
    }

    // Only the paced main sender has a trustworthy boundary for the next new packet.
    pub(crate) async fn send_main_tick(&mut self, frames: &[i16], generation: Option<u64>,
        recover_until: Option<Instant>, due: Instant, rtp_generation: Option<u64>) -> std::io::Result<()> {
        if self.pt != 100 || self.opus.is_some() {
            return self.send_pcm_recovering(frames, generation, recover_until, rtp_generation).await;
        }
        let previous = self.pending_pcm.take();
        self.pcm_tick_signal_packets = 0;
        self.pcm_tick_due = Some(due);
        let before = self.packets;
        let initial_drops = self.congestion_drops;
        let result = self.send_pcm_recovering(frames, generation, recover_until, rtp_generation).await;
        self.pcm_tick_due = None;
        if let Some(old) = previous {
            let period = crate::audio_pacing::period(self.sample_rate);
            let now = Instant::now();
            if now >= old.expires {
                self.pcm_retry.expired += 1;
                self.pcm_retry.finish(old.diagnostic, "expired", None);
            } else if self.pending_pcm.is_some() {
                self.pcm_retry.replaced += 1;
                self.pcm_retry.finish(old.diagnostic, "replaced", None);
            } else if let Some(reason) = self.pcm_discard_reason(result.is_err(), initial_drops, before) {
                self.pcm_retry.discarded += 1;
                self.pcm_retry.finish(old.diagnostic, reason, None);
            } else if let AudioTransport::Realtime(socket) = &self.transport {
                let write = self.with_pcm_retry_ownership(old.ownership_generation, || {
                    // A handover may have held the watch lock while this tick was running.
                    let now = Instant::now();
                    if now >= old.expires { return Err("expired"); }
                    if (due + period).saturating_duration_since(now) <= Duration::from_millis(1) { return Err("no_slack"); }
                    Ok(socket2::SockRef::from(socket).send_with_flags(&old.wire, libc::MSG_DONTWAIT))
                });
                match write {
                    Err(reason) => {
                        match reason {
                            "expired" => self.pcm_retry.expired += 1,
                            "no_slack" => self.pcm_retry.no_slack += 1,
                            _ => self.pcm_retry.discarded += 1,
                        }
                        self.pcm_retry.finish(old.diagnostic, reason, None);
                    }
                    Ok(Ok(_)) => {
                        self.pcm_retry.sent += 1;
                        self.pcm_retry.finish(old.diagnostic, "retry_sent", None);
                        self.packets += 1;
                        self.pcm_tick_signal_packets += u64::from(old.has_signal);
                        self.payload_bytes += old.payload_bytes as u64;
                        if let (Some(observer), Some(gen)) = (&self.control_observer, old.cache_generation) {
                            observer.remember_sent(old.seq, gen, old.wire);
                        }
                    }
                    Ok(Err(e)) => {
                        self.pcm_retry.failed += 1;
                        self.pcm_retry.last_errno = e.raw_os_error();
                        self.pcm_retry.finish(old.diagnostic, "retry_failed", e.raw_os_error());
                    }
                }
            }
        }
        result
    }

    fn pcm_discard_reason(&self, new_error: bool, initial_drops: u64, before: u64) -> Option<&'static str> {
        if new_error { return Some("new_write_error"); }
        if self.congestion_drops != initial_drops { return Some("new_write_congested"); }
        if self.packets == before { return Some("no_new_packet"); }
        None
    }

    fn with_pcm_retry_ownership<T>(&self, generation: Option<u64>, write: impl FnOnce() -> Result<T, &'static str>) -> Result<T, &'static str> {
        let guard = self.pcm_ownership.as_ref().map(|rx| rx.borrow());
        if !self.owns_audio() { return Err("ownership_lost"); }
        if guard.as_ref().is_some_and(|state| !state.owned || generation != Some(state.losses)) {
            return Err("ownership_changed");
        }
        let result = write();
        drop(guard);
        result
    }

    pub(crate) fn tick_signal_packets(&self, mixed: &[i16], written: u64) -> u64 {
        if self.pt == 100 && self.opus.is_none() { return self.pcm_tick_signal_packets; }
        if mixed.iter().any(|s| *s != 0) { written } else { 0 }
    }

    pub(crate) async fn send_pcm_recovering(&mut self, mut frames: &[i16], expected_generation: Option<u64>,
        recover_until: Option<std::time::Instant>, rtp_generation: Option<u64>) -> std::io::Result<()> {
        let mut fresh = true;
        if let AudioTransport::Buffered(link) = &mut self.transport {
            let generation = link.generation().ok_or_else(buffered::closed)?;
            if link.encoder_generation_changed(generation) {
                self.aac.take();
                self.aac_needs_reset = true;
            }
            fresh = expected_generation == Some(generation);
        }
        if !fresh || !self.owns_audio() {
            self.blocked_samples = self.blocked_samples.saturating_add(frames.len() as u64);
            self.marker_pending = true;
            if self.is_aac() { self.aac.take(); self.aac_needs_reset = true; }
            if let Some(encoder)=self.opus.as_mut() { encoder.reset()?; }
            return Ok(());
        }
        if self.aac_worker.is_some() {
            #[cfg(test)]
            if let Some(handover) = self.before_aac_worker_admission.take() { handover(); }
            return self.pump_encoded_aac(frames, expected_generation, recover_until, rtp_generation).await;
        }
        if let Some(encoder)=self.opus.as_mut() {
            let sample_frames=encoder.samples_per_packet() as u32;
            let encoded=encoder.encode(frames)?;
            for payload in encoded { self.send_payload(&payload, sample_frames, rtp_generation).await?; }
        } else if self.is_aac() {
            if self.aac_needs_reset {
                self.aac_needs_reset = false;
                self.aac = Some(crate::aac::AacEncoder::at_rate(self.sample_rate)?);
            }
            let encoder = self.aac.as_mut().ok_or_else(|| std::io::Error::other("AAC encoder stopped"))?;
            let started = self.io_costs.as_ref().map(|_| crate::timing::monotonic_nanos());
            let encoded = encoder.encode(frames);
            if let (Some(costs), Some(started)) = (&mut self.io_costs, started) {
                costs.aac.observe(crate::audio_pacing::StageSpan::since(started));
            }
            let encoded = match encoded { Ok(frames) => frames, Err(error) => { self.aac.take(); return Err(error); } };
            for payload in encoded {
                if !self.write_aac_frame(&payload, expected_generation, rtp_generation).await? { break; }
            }
        } else {
            if frames.len() >= SAMPLES_PER_PACKET * CHANNELS
                && recover_until.is_some_and(|deadline| std::time::Instant::now() >= deadline)
            {
                self.expired_pcm_frames += SAMPLES_PER_PACKET as u64;
                self.skip_source_frames(SAMPLES_PER_PACKET as u64);
                frames = &frames[SAMPLES_PER_PACKET * CHANNELS..];
            }
            let current_due = self.pcm_tick_due;
            let mut chunks = frames.chunks_exact(SAMPLES_PER_PACKET * CHANNELS);
            while let Some(chunk) = chunks.next() {
                // The last remaining block belongs to the current slot, even if an expired head was trimmed.
                self.pcm_tick_due = current_due.map(|due| due - crate::audio_pacing::period(self.sample_rate) * chunks.len() as u32);
                let mut payload = Vec::with_capacity(chunk.len() * 2);
                for sample in chunk { payload.extend_from_slice(&sample.to_be_bytes()); }
                self.send_payload(&payload, SAMPLES_PER_PACKET as u32, rtp_generation).await?;
            }
        }
        Ok(())
    }

    async fn pump_encoded_aac(&mut self, frames: &[i16], expected_generation: Option<u64>,
        recover_until: Option<std::time::Instant>, rtp_generation: Option<u64>) -> std::io::Result<()> {
        let Some(generation) = self.aac_worker.as_ref().unwrap().generation() else { return Ok(()); };
        // A take/return between mixing and worker admission must not relabel old PCM.
        // Once submitted, the worker also checks the original generation on both sides of encoding.
        let track_ownership = self.rtp_position.is_some();
        if track_ownership && rtp_generation != Some(generation) {
            self.blocked_samples = self.blocked_samples.saturating_add(frames.len() as u64);
            self.aac_input_ts = self.aac_input_ts.wrapping_add((frames.len() / CHANNELS) as u32);
            return Ok(());
        }
        for _ in 0..aac_worker::QUEUED_BLOCKS {
            let Some(output) = self.aac_worker.as_ref().unwrap().try_output()? else { break; };
            if output.generation != generation { continue; }
            if self.aac_worker.as_ref().unwrap().generation() != Some(generation) { return Ok(()); }
            if let Some(costs) = self.io_costs.as_mut() { costs.aac.observe(output.encode_span); }
            if let Some(payload) = output.payload? {
                let prepared_generation = output.timing.and_then(|timing| timing.rtp_generation);
                if track_ownership && prepared_generation != Some(generation) { continue; }
                if let Some(timing) = output.timing {
                    if timing.recover_until.is_some_and(|deadline| std::time::Instant::now() >= deadline) {
                        self.expired_aac_frames += crate::aac::FRAMES as u64;
                        continue;
                    }
                    if timing.ts != self.ts {
                        self.ts = timing.ts;
                        self.marker_pending = true;
                        if self.pt == 102 {
                            let _ = self.send_sync_now(crate::timing::monotonic_nanos()).await;
                        }
                    }
                }
                if self.aac_worker.as_ref().unwrap().generation() != Some(generation)
                    || !self.write_aac_frame(&payload, expected_generation, prepared_generation).await? { return Ok(()); }
                // At most one realtime AU per mixer tick even if MediaCodec
                // returns a batch. The buffered TCP stream can drain the batch.
                if self.pt == 102 { break; }
            }
        }
        if frames.is_empty() { return Ok(()); }
        let frames_count = (frames.len() / CHANNELS) as u32;
        let timing = aac_worker::AacTiming {
            ts: self.aac_input_ts,
            recover_until: if self.pt == 102 { recover_until } else { None },
            rtp_generation,
        };
        let accepted = self.aac_worker.as_ref().unwrap().submit(generation, frames, timing)?;
        self.aac_input_ts = self.aac_input_ts.wrapping_add(frames_count);
        if !accepted {
            self.aac_queue_dropped_frames = self.aac_queue_dropped_frames.saturating_add(u64::from(frames_count));
        }
        Ok(())
    }

    async fn write_aac_frame(&mut self, payload: &[u8], expected_generation: Option<u64>,
        rtp_generation: Option<u64>) -> std::io::Result<bool> {
        if let AudioTransport::Buffered(link) = &mut self.transport {
            let generation = expected_generation.ok_or_else(buffered::closed)?;
            if self.rtp_position.is_some() && rtp_generation != Some(generation) { return Ok(false); }
            if link.send(&self.key, payload, self.ts, self.sample_rate, generation).await? {
                self.record_media_rtp(rtp_generation, self.ts);
            } else if link.generation() != Some(generation) {
                self.aac.take();
                self.aac_needs_reset = true;
                return Ok(false);
            }
            // Priming can suppress a packet. The AU still occupies source time.
            self.ts = self.ts.wrapping_add(crate::aac::FRAMES as u32);
        } else {
            self.send_payload(payload, crate::aac::FRAMES as u32, rtp_generation).await?;
        }
        Ok(true)
    }

    async fn send_payload(&mut self, payload: &[u8], sample_frames: u32, rtp_generation: Option<u64>) -> std::io::Result<()> {
        let ownership_generation = self.pcm_tick_due.and_then(|_| self.pcm_ownership.as_ref().map(|rx| rx.borrow().losses));
        // Recheck ownership after encoding; a resource handover may occur concurrently.
        if !self.owns_audio() {
            self.marker_pending = true;
            if self.pt == 102 { self.aac.take(); self.aac_needs_reset = true; }
            if let Some(encoder)=self.opus.as_mut() { encoder.reset()?; }
            return Ok(());
        }
        let mut header = [0u8; RTP_HEADER_LEN];
        header[0] = 0x80;
        header[1] = (self.pt & 0x7f) | if self.marker_pending { 0x80 } else { 0 };
        header[2..4].copy_from_slice(&self.seq.to_be_bytes());
        header[4..8].copy_from_slice(&self.ts.to_be_bytes());
        let rtp = RtpHeader { marker: header[1] & 0x80 != 0, seq: self.seq, rtp_ts: self.ts };
        let sealed = chacha_seal(&self.key, &nonce64(self.nonce), payload, &header[4..12]);
        let mut wire = Vec::with_capacity(RTP_HEADER_LEN + sealed.len() + NONCE_TAIL_LEN);
        wire.extend_from_slice(&header);
        wire.extend_from_slice(&sealed);
        wire.extend_from_slice(&self.nonce.to_le_bytes());
        // The connected Tokio socket is nonblocking. Attempt one kernel write per RTP
        // packet, even on the first tick before Tokio has observed writable readiness.
        let AudioTransport::Realtime(udp) = &self.transport else {
            return Err(std::io::Error::other("UDP payload on a buffered stream"));
        };
        let generation = self.resume_trace.as_ref().map(AudioResumeTrace::generation);
        let resend_generation = self.control_observer.as_ref().and_then(|observer| observer.generation());
        #[cfg(test)]
        if let Some(before) = self.before_rtp_write.take() { before(); }
        let started = self.io_costs.as_ref().map(|_| crate::timing::monotonic_nanos());
        let sent = {
            // Publish take/return and authorize this actual syscall under the same watch lock.
            let ownership = self.rtp_position.as_ref().map(|(_, rx)| rx.borrow());
            if !self.owns_audio() || ownership.as_ref().is_some_and(|state|
                !state.owned || rtp_generation != Some(state.losses)) {
                self.marker_pending = true;
                return Ok(());
            }
            socket2::SockRef::from(udp).send_with_flags(&wire, libc::MSG_DONTWAIT)
        };
        // A rejected generation never reaches the kernel. Consume the nonce only
        // on an actual attempt, even if that attempt reports a send error.
        self.nonce = self.nonce.wrapping_add(1);
        self.seq = self.seq.wrapping_add(1);
        self.ts = self.ts.wrapping_add(sample_frames);
        if sent.as_ref().is_ok_and(|bytes| *bytes == wire.len()) {
            self.record_media_rtp(rtp_generation, rtp.rtp_ts);
        }
        if let Some(due) = self.pcm_tick_due {
            self.pcm_retry.new_writes += 1;
            self.pcm_retry.max_new_delay_us = self.pcm_retry.max_new_delay_us.max(due.elapsed().as_micros());
        }
        if let (Some(costs), Some(started)) = (&mut self.io_costs, started) {
            costs.udp.observe(crate::audio_pacing::StageSpan::since(started));
        }
        if let Err(error) = sent {
            if error.kind() == std::io::ErrorKind::WouldBlock || error.raw_os_error() == Some(libc::ENOBUFS) {
                self.congestion_drops = self.congestion_drops.saturating_add(1);
                let mono_ns = crate::timing::monotonic_nanos();
                self.udp_drop_window.record(&error, mono_ns);
                if let Some(due) = self.pcm_tick_due {
                    let expires = due + crate::audio_pacing::period(self.sample_rate) * 2;
                    let fresh = Instant::now() < expires;
                    let diagnostic = self.pcm_retry.sample(rtp, mono_ns, error.raw_os_error());
                    if fresh {
                        let pending = PendingPcm { wire, has_signal: payload.iter().any(|b| *b != 0), seq: rtp.seq, diagnostic, payload_bytes: payload.len(), expires,
                            ownership_generation, cache_generation: resend_generation };
                        if let Some(old) = self.pending_pcm.replace(pending) {
                            self.pcm_retry.replaced += 1;
                            self.pcm_retry.finish(old.diagnostic, "replaced", None);
                        }
                        self.pcm_retry.saved += 1;
                    } else {
                        self.pcm_retry.expired += 1;
                        self.pcm_retry.finish(diagnostic, "expired", None);
                    }
                }
                return Ok(());
            }
            if self.pcm_tick_due.is_some() {
                let diagnostic = self.pcm_retry.sample(rtp, crate::timing::monotonic_nanos(), error.raw_os_error());
                self.pcm_retry.finish(diagnostic, "non_retryable_error", None);
            }
            return Err(error);
        }
        self.payload_bytes = self.payload_bytes.saturating_add(payload.len() as u64);
        if self.pcm_tick_due.is_some() && payload.iter().any(|b| *b != 0) {
            self.pcm_tick_signal_packets += 1;
        }
        if let (Some(observer), Some(generation)) = (&self.control_observer, resend_generation) {
            observer.remember_sent(rtp.seq, generation, wire);
        }
        if self.packets == 0 && self.pt == 102 {
            crate::emit_log(&format!("AUDIO: first AAC RTP type=102 SPF=1024 raw_bytes={}", payload.len()));
        }
        self.packets = self.packets.wrapping_add(1);
        self.marker_pending = false;
        if let (Some(trace), Some(generation)) = (&mut self.resume_trace, generation) {
            trace.observe_unborrow(generation, self.sync.is_some());
            trace.rtp_written(rtp);
        }
        Ok(())
    }

    /// 发送 RTP↔NTP 同步包（调用方每秒调用一次；sync 套接字未配置时静默跳过）。
    ///
    /// `rtp_ts`：期望锚定的当前 RTP 时间戳；`ntp_nanos`：单调时钟纳秒——与
    /// TimingServer 应答给车机的是同一时间基，否则车机算出的锚点会整体偏移。
    pub async fn send_sync(&mut self, rtp_ts: u32, ntp_nanos: u64) -> std::io::Result<()> {
        self.send_sync_with_frames(rtp_ts, ntp_nanos, self.samples_per_packet() as u32).await
    }

    pub(crate) async fn send_call_sync(&mut self, ntp_nanos: u64, frames: u32) -> std::io::Result<()> {
        self.send_sync_with_frames(self.ts, ntp_nanos, frames).await
    }

    async fn send_sync_with_frames(&mut self, rtp_ts: u32, ntp_nanos: u64, frames: u32) -> std::io::Result<()> {
        // Classify an in-flight write by the generation sampled before the send.
        let generation = self.resume_trace.as_ref().map(AudioResumeTrace::generation);
        let Some(sync) = self.sync.as_ref() else {
            if let (Some(trace), Some(generation)) = (&mut self.resume_trace, generation) {
                trace.observe_unborrow(generation, false);
            }
            return Ok(()); // 车机没下发 controlPort（旧车机）：只发音频
        };
        let packet = build_sync_packet_for_frames(self.sync_seq, rtp_ts, ntp_nanos, frames);
        // Sync shares the audio pump with media. A full socket must not stall the next tick.
        let result = socket2::SockRef::from(sync).send_with_flags(&packet, libc::MSG_DONTWAIT);
        if let Some(d4) = &mut self.d4 { d4.written(&result, packet.len()); }
        if let (Some(trace), Some(generation)) = (&mut self.resume_trace, generation) {
            trace.observe_unborrow(generation, true);
            trace.sync_result(SyncHeader { seq: self.sync_seq, rtp_ts, ntp_nanos }, &result);
        }
        result?;
        self.sync_seq = self.sync_seq.wrapping_add(1);
        Ok(())
    }

    /// 便捷版：用内部当前 RTP 时间戳发同步包（引擎每秒调用）。
    pub async fn send_sync_now(&mut self, ntp_nanos: u64) -> std::io::Result<()> {
        let ts = self.ts;
        self.send_sync(ts, ntp_nanos).await
    }

    /// 已发送音频包数（对外统计）。
    pub fn packets_sent(&self) -> u64 {
        match &self.transport {
            AudioTransport::Buffered(link) => link.written,
            AudioTransport::Realtime(_) => self.packets,
        }
    }
}

#[cfg(test)]
mod opus_output_tests {
    use super::*;
    use std::sync::{Arc,atomic::{AtomicBool,Ordering}};
    #[tokio::test]
    async fn opus_rtp_uses_negotiated_rate_spf_payload_type_aead_and_sync() {
        for (rate,frames,pt) in [(24000,480,100),(48000,960,101)] {
            let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let data=UdpSocket::bind("127.0.0.1:0").await.unwrap();data.connect(receiver.local_addr().unwrap()).await.unwrap();
            let sync_receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let sync=UdpSocket::bind("127.0.0.1:0").await.unwrap();sync.connect(sync_receiver.local_addr().unwrap()).await.unwrap();
            let key=[9;32];let mut out=AudioOut::new_opus(data,Some(sync),key,rate,frames,pt).unwrap();
            assert_eq!(out.codec_name(),"OPUS");assert_eq!(out.sample_rate(),rate);assert_eq!(out.samples_per_packet(),frames);
            out.send_pcm(&[1000,1000].repeat(1920)).await.unwrap();
            let mut packet=[0;4096];
            for index in 0..2 {
                let n=tokio::time::timeout(std::time::Duration::from_secs(1),receiver.recv(&mut packet)).await.unwrap().unwrap();
                assert_eq!(packet[1]&127,pt);assert_eq!(packet[1]&128,if index==0 {128}else{0});
                assert_eq!(u16::from_be_bytes(packet[2..4].try_into().unwrap()),index as u16);
                assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),index*frames as u32);
                assert_eq!(&packet[8..12],&[0;4]);assert_eq!(&packet[n-8..n],&u64::from(index).to_le_bytes());
                let plain=crate::crypto::chacha_open(&key,&nonce64(u64::from(index)),&packet[12..n-8],&packet[4..12]).unwrap();
                assert_eq!(unsafe{audiopus_sys::opus_packet_get_nb_samples(plain.as_ptr(),plain.len() as i32,rate as i32)},frames as i32);
                assert_eq!(unsafe{audiopus_sys::opus_packet_get_nb_channels(plain.as_ptr())},1);
            }
            assert_eq!(out.packets_sent(),2);
            out.send_sync_now(1_000_000_000).await.unwrap();
            let n=sync_receiver.recv(&mut packet).await.unwrap();assert_eq!(n,20);
            assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),frames as u32*2);
            assert_eq!(u32::from_be_bytes(packet[16..20].try_into().unwrap()),frames as u32*3);
        }
    }
    #[tokio::test]
    async fn opus_resource_loss_discards_buffered_audio_without_reusing_nonce() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let data=UdpSocket::bind("127.0.0.1:0").await.unwrap();data.connect(receiver.local_addr().unwrap()).await.unwrap();
        let mut out=AudioOut::new_opus(data,None,[3;32],24000,480,100).unwrap();
        let owned=Arc::new(AtomicBool::new(true));out.set_ownership(owned.clone());
        out.send_pcm(&[10000,10000].repeat(960)).await.unwrap();
        let mut packet=[0;4096];receiver.recv(&mut packet).await.unwrap();
        out.send_pcm(&[10000,10000].repeat(600)).await.unwrap();
        owned.store(false,Ordering::Release);out.send_pcm(&[10000,10000].repeat(352)).await.unwrap();
        owned.store(true,Ordering::Release);out.skip_source_frames(960);
        out.send_pcm(&[0;1920]).await.unwrap();
        let n=receiver.recv(&mut packet).await.unwrap();
        assert_eq!(&packet[n-8..n],&1u64.to_le_bytes());assert_eq!(packet[1]&128,128);
        assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),960);
        let actual=crate::crypto::chacha_open(&[3;32],&nonce64(1),&packet[12..n-8],&packet[4..12]).unwrap();
        let expected=crate::opus_encode::OpusEncoder::new(24000,480).unwrap().encode(&[0;1920]).unwrap();
        assert_eq!(actual,expected[0]);assert_eq!(out.packets_sent(),2);
    }
}

/// 组 RTP↔NTP 同步包（格式对齐 AudioSyncPacket.kt，20 字节、无 SSRC 字段）：
/// ```text
/// 0      1      2..3        4..7                8..15      16..19
/// 0x80   0xD4   seq(BE)     RTP ts(BE,当前)     NTP(BE)    RTP ts(BE,下一包)
/// ```
///  - 字节 0：V=2（0x80）；**首个同步包（seq==0，RECORD/FLUSH 之后）置扩展位 X = 0x90**
///  - 字节 1：M=1 + PT=84 → 0xD4（AirPlay 私有 RTCP 时间同步类型）
///  - 字节 8..15：当前 NTP 时间（秒<<32 | 2^32 分数，大端），与 TimingServer 应答
///    给车机的同一时间基（单调时钟）
///  - 「下一包」时间戳 = 当前 + 每包样本帧数（352），对照 AudioSyncPacket.build
fn build_sync_packet_for_frames(seq: u16, rtp_ts: u32, ntp_nanos: u64, sample_frames: u32) -> Vec<u8> {
    let mut packet = vec![0u8; SYNC_PACKET_LEN];
    packet[0] = 0x80 | if seq == 0 { 0x10 } else { 0x00 };
    packet[1] = 0xD4;
    packet[2..4].copy_from_slice(&seq.to_be_bytes());
    packet[4..8].copy_from_slice(&rtp_ts.to_be_bytes());
    packet[8..16].copy_from_slice(&ntp_be(ntp_nanos));
    let next = rtp_ts.wrapping_add(sample_frames);
    packet[16..20].copy_from_slice(&next.to_be_bytes());
    packet
}

impl Drop for AudioOut {
    fn drop(&mut self) {
        if let Some(old) = self.pending_pcm.take() {
            self.pcm_retry.discarded += 1;
            self.pcm_retry.finish(old.diagnostic, "stream_retired", None);
        }
        if let Some(report) = self.take_pcm_retry_report() { crate::emit_log(&report); }
        if let Some(report) = self.take_udp_drop_report() {
            crate::emit_log(&report);
        }
    }
}

#[cfg(test)]
mod ownership_tests {
    use super::*;
    use super::buffered::tests::{accept_start, control_pair};
    use std::sync::{Arc, atomic::{AtomicBool, Ordering}};

    #[tokio::test]
    async fn buffered_aac_queue_full_preserves_pending_packet_timestamps() {
        use std::{sync::mpsc, time::Duration};
        use tokio::{io::AsyncReadExt, net::TcpListener, time::timeout};

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let data = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut receiver, _) = listener.accept().await.unwrap();
        let (control, channel, peer) = control_pair(48000).await;
        let control_peer = tokio::spawn(accept_start(peer));
        let transport = buffered::BufferedTransport::new(data, control, 100_000);
        let mut sender = AudioOut::with_transport(AudioTransport::Buffered(transport), None, [7; 32], 103);
        let (started, begun) = mpsc::channel();
        let (release, blocked) = mpsc::channel();
        let mut first = true;
        sender.aac_worker = Some(aac_worker::AacWorker::spawn(channel.buffered_ownership.subscribe(), move |_, pcm| {
            if std::mem::take(&mut first) {
                started.send(()).unwrap();
                blocked.recv_timeout(Duration::from_secs(5)).unwrap();
            }
            Ok(pcm.chunks_exact(crate::aac::FRAMES * CHANNELS).map(|_| vec![42]).collect())
        }).unwrap());

        let block = [1; crate::aac::FRAMES * CHANNELS];
        sender.send_pcm(&block).await.unwrap();
        begun.recv_timeout(Duration::from_secs(2)).unwrap();
        for _ in 0..aac_worker::QUEUED_BLOCKS {
            sender.send_pcm(&block).await.unwrap();
        }
        sender.send_pcm(&block).await.unwrap();
        assert_eq!(sender.aac_queue_dropped_frames, crate::aac::FRAMES as u64);
        assert_eq!(sender.ts, 0);
        release.send(()).unwrap();

        let queued = aac_worker::QUEUED_BLOCKS + 1;
        timeout(Duration::from_secs(2), async {
            while sender.packets_sent() < queued as u64 {
                sender.send_pcm(&[]).await.unwrap();
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
            sender.send_pcm(&block).await.unwrap();
            while sender.packets_sent() < (queued + 1) as u64 {
                sender.send_pcm(&[]).await.unwrap();
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
            for index in (0..queued).chain(std::iter::once(queued + 1)) {
                let len = receiver.read_u16().await.unwrap();
                let mut record = vec![0; usize::from(len) - 2];
                receiver.read_exact(&mut record).await.unwrap();
                let ts = u32::from_be_bytes(record[4..8].try_into().unwrap());
                assert_eq!(ts, (index * crate::aac::FRAMES) as u32);
            }
            control_peer.await.unwrap();
        }).await.unwrap();
    }

    #[tokio::test]
    async fn missed_pcm_time_is_skipped_without_sending_or_reusing_nonce() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let mut sender=AudioOut::new(socket,None,[3;32],100);
        let pcm=vec![100;704];
        let mut packet=[0;2048];
        sender.send_pcm(&pcm).await.unwrap();
        receiver.recv(&mut packet).await.unwrap();
        sender.skip_source_frames(352*13);
        assert_eq!(sender.packets_sent(),1);
        sender.send_pcm(&pcm).await.unwrap();
        let n=receiver.recv(&mut packet).await.unwrap();
        assert_eq!(u16::from_be_bytes(packet[2..4].try_into().unwrap()),1);
        assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),4928);
        assert_eq!(packet[1]&0x80,0x80);
        assert_eq!(&packet[n-8..n],&1u64.to_le_bytes());
        assert!(crate::crypto::chacha_open(&[3;32],&nonce64(1),&packet[12..n-8],&packet[4..12]).is_ok());
        assert_eq!(sender.packets_sent(),2);
    }

    #[test]
    fn missed_aac441_mix_frames_advance_rtp_in_the_negotiated_clock() {
        let runtime=tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let mut sender=AudioOut::new(socket,None,[0;32],102);
            sender.sample_rate=44100;
            sender.ts=u32::MAX-100;
            for _ in 0..10 { sender.skip_source_frames(352); }
            assert_eq!(sender.ts,(u32::MAX-100).wrapping_add(3520));
            assert_eq!(sender.seq,0);
            assert_eq!(sender.nonce,0);
            assert!(sender.marker_pending);
        });
    }
    #[tokio::test]
    async fn pcm_recovers_only_fresh_block_without_changing_its_rtp_timestamp() {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(peer.local_addr().unwrap()).await.unwrap();
        let mut out = AudioOut::new_pcm(socket, None, [1; 32], 48000);
        let pcm = [42; SAMPLES_PER_PACKET * CHANNELS * 2];
        let now = std::time::Instant::now();
        out.send_pcm_recovering(&pcm, None, Some(now + std::time::Duration::from_secs(1)), None).await.unwrap();
        out.send_pcm_recovering(&pcm, None, Some(now), None).await.unwrap();
        let mut buf = [0; 2048];
        for (seq, expected) in [0u32, 352, 1056].into_iter().enumerate() {
            let size = tokio::time::timeout(std::time::Duration::from_secs(1), peer.recv(&mut buf)).await.unwrap().unwrap();
            assert!(size >= 12);
            assert_eq!(u16::from_be_bytes(buf[2..4].try_into().unwrap()), seq as u16);
            assert_eq!(u32::from_be_bytes(buf[4..8].try_into().unwrap()), expected);
        }
        assert_eq!(out.packets_sent(), 3);
        assert_eq!(out.expired_pcm_frames, 352);
    }

    #[tokio::test]
    async fn aac_d5_replays_cached_encrypted_rtp_in_order_across_sequence_wrap() {
        use crate::buffered_ownership::BufferedOwnership;
        let data_peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let control_peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let data = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sync = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let control_addr = sync.local_addr().unwrap();
        data.connect(data_peer.local_addr().unwrap()).await.unwrap();
        sync.connect(control_peer.local_addr().unwrap()).await.unwrap();
        let ownership = BufferedOwnership::default();
        ownership.publish(true);
        let mut audio = AudioOut::new(data, Some(sync), [7; 32], 102);
        audio.seq = u16::MAX;
        audio.observe_main_stream(9, 23, control_peer.local_addr().unwrap().port(), std::time::Instant::now(), &ownership, None);

        let mut packet = [0u8; 256];
        let mut originals = Vec::new();
        for payload in [&[1, 2, 3][..], &[4, 5, 6, 7][..]] {
            audio.send_payload(payload, 1024, Some(0)).await.unwrap();
            let n = data_peer.recv(&mut packet).await.unwrap();
            originals.push(packet[..n].to_vec());
        }
        control_peer.send_to(&[0x80, 0xd5, 0, 1, 0xff, 0xff, 0, 3], control_addr).await.unwrap();
        for original in &originals {
            let (n, source) = tokio::time::timeout(std::time::Duration::from_secs(1),
                control_peer.recv_from(&mut packet)).await.unwrap().unwrap();
            assert_eq!(source, control_addr);
            assert_eq!(&packet[..2], &[0x80, 0xd6]);
            assert_eq!(&packet[2..4], &(original.len() as u16).to_le_bytes());
            assert_eq!(&packet[4..n], original);
        }
        assert_eq!((audio.seq, audio.ts, audio.nonce, audio.packets_sent()), (1, 2048, 2, 2));
        let n = tokio::time::timeout(std::time::Duration::from_secs(1), control_peer.recv(&mut packet)).await.unwrap().unwrap();
        assert_eq!(&packet[..n], &[0x80, 0xd6, 4, 0, 0, 1, 0, 0]);

        ownership.publish(false);
        ownership.publish(true);
        control_peer.send_to(&[0x80, 0xd5, 0, 1, 0xff, 0xff, 0, 1], control_addr).await.unwrap();
        let n = tokio::time::timeout(std::time::Duration::from_secs(1), control_peer.recv(&mut packet)).await.unwrap().unwrap();
        assert_eq!(&packet[..n], &[0x80, 0xd6, 4, 0, 0xff, 0xff, 0, 0]);
    }

    #[tokio::test]
    async fn aac_rtp_preserves_raw_payload_and_advances_1024_with_unique_nonces() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let key=[3u8;32];
        let mut sender=AudioOut::new(socket,None,key,102);
        let payload=[0x21,0x10,0x56,0xab];
        let mut packet=[0u8;2048];
        for i in 0..2u32 {
            sender.send_payload(&payload,1024,None).await.unwrap();
            let n=receiver.recv(&mut packet).await.unwrap();
            assert_eq!(packet[1]&0x7f,102);
            assert_eq!(packet[1]&0x80,if i==0 {0x80} else {0});
            assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),i*1024);
            assert_eq!(&packet[n-8..n],&(i as u64).to_le_bytes());
            assert_eq!(crate::crypto::chacha_open(&key,&nonce64(i as u64),&packet[12..n-8],&packet[4..12]).unwrap(),payload);
        }
        let sync=build_sync_packet_for_frames(0,1024,123456,1024);
        assert_eq!(u32::from_be_bytes(sync[16..20].try_into().unwrap()),2048);
    }
    #[cfg(target_os="android")]
    #[tokio::test]
    async fn repeated_late_ticks_preserve_aac_encoder_and_still_emit_audio() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let mut sender=AudioOut::new_aac(socket,None,[4;32],crate::aac::AacEncoder::at_rate(44100).unwrap());
        sender.send_pcm(&vec![1200;704]).await.unwrap();
        assert_eq!(sender.packets_sent(),0);
        sender.skip_source_frames(352);
        assert!(sender.aac.is_some(),"recovery must not synchronously destroy/recreate the codec on every late tick");
        assert_eq!(sender.ts,352);
        assert!(sender.marker_pending);
        for _ in 0..60 {
            sender.send_pcm(&vec![0;704]).await.unwrap();
            sender.skip_source_frames(352);
            tokio::time::sleep(std::time::Duration::from_millis(3)).await;
        }
        assert!(sender.packets_sent()>=12,"AAC output must make progress despite repeated late worker ticks");
        assert!(sender.packets_sent()<=22);
    }
    #[cfg(target_os="android")]
    #[tokio::test]
    async fn aac441_sender_encodes_negotiated_mix_and_keeps_rtp_sample_clock() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let key=[7;32];
        let mut sender=AudioOut::new_aac(socket,None,key,crate::aac::AacEncoder::at_rate(44100).unwrap());
        let pcm=vec![500;882]; // 441 negotiated frames / 10 ms.
        for _ in 0..100 {
            sender.send_pcm(&pcm).await.unwrap();
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        assert_eq!(sender.sample_rate(),44100);
        assert!((39..=44).contains(&sender.packets_sent()),"{} packets for 1s of source audio",sender.packets_sent());
        let mut packet=[0;2048];
        for index in 0..sender.packets_sent() {
            let n=tokio::time::timeout(std::time::Duration::from_secs(1),receiver.recv(&mut packet)).await.unwrap().unwrap();
            assert_eq!(packet[1]&0x7f,102);
            assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()),index as u32*1024);
            assert!(!crate::crypto::chacha_open(&key,&nonce64(index),&packet[12..n-8],&packet[4..12]).unwrap().is_empty());
        }
    }
    #[cfg(target_os="android")]
    #[tokio::test]
    async fn aac_owner_loss_discards_encoder_and_buffered_audio() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let owner=Arc::new(AtomicBool::new(true));
        let mut sender=AudioOut::new_aac(socket,None,[0;32],crate::aac::AacEncoder::at_rate(44100).unwrap());
        sender.set_ownership(owner.clone());
        sender.send_pcm(&vec![500;704]).await.unwrap(); // Fractional AAC frame must not survive handover.
        owner.store(false,Ordering::Release);
        sender.send_pcm(&vec![500;704]).await.unwrap();
        assert!(sender.aac.is_none()); assert_eq!(sender.packets_sent(),0);
        owner.store(true,Ordering::Release);
        sender.send_pcm(&vec![0;704]).await.unwrap();
        assert!(sender.aac.is_some()); assert_eq!(sender.packets_sent(),0);
        assert_eq!(sender.aac.as_ref().unwrap().sample_rate(),44100);
        assert!(sender.marker_pending);
    }
    #[tokio::test]
    async fn pcm_sync_and_d5_use_the_same_control_socket() {
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = receiver.local_addr().unwrap();
        let data = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sync = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        data.connect(peer).await.unwrap();
        sync.connect(peer).await.unwrap();
        let sync_local = sync.local_addr().unwrap();
        let mut audio = AudioOut::new_pcm(data, Some(sync), [0; 32], 48000);
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish(true);
        audio.observe_main_stream(9, 23, peer.port(), std::time::Instant::now(), &ownership, None);
        assert!(audio.control_observer.is_some());
        assert!(audio.pacing_id.is_some());
        audio.send_sync_now(1_000_000_000).await.unwrap();
        let mut packet = [0; 64];
        let (len, source) = receiver.recv_from(&mut packet).await.unwrap();
        assert!(len > 2);
        assert_eq!(packet[1] & 0x7f, 84);
        assert_eq!(source, sync_local);
        audio.send_pcm(&[23; SAMPLES_PER_PACKET * CHANNELS]).await.unwrap();
        let mut media = [0u8; 1600];
        let len = tokio::time::timeout(std::time::Duration::from_secs(1), receiver.recv(&mut media)).await.unwrap().unwrap();
        let seq = [media[2], media[3]];
        receiver.send_to(&[0x80, 0xd5, 0, 1, seq[0], seq[1], 0, 1], sync_local).await.unwrap();
        let mut resent = [0u8; 1600];
        let (n, from) = tokio::time::timeout(std::time::Duration::from_secs(1), receiver.recv_from(&mut resent)).await.unwrap().unwrap();
        assert_eq!(from, sync_local);
        assert_eq!(&resent[..4], &[0x80, 0xd6, len as u8, (len >> 8) as u8]);
        assert_eq!(&resent[4..n], &media[..len]);
    }

    #[tokio::test]
    async fn realtime_udp_rejects_pcm_and_aac_prepared_before_take_and_return() {
        use crate::{audio_diagnostics::FeedbackTrace, bplist::{self, Value}, buffered_ownership::BufferedOwnership};

        for (kind, samples_per_packet, before_worker) in [
            (100, SAMPLES_PER_PACKET, false), (102, crate::aac::FRAMES, false), (102, crate::aac::FRAMES, true),
        ] {
            let data_peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let data = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            data.connect(data_peer.local_addr().unwrap()).await.unwrap();
            let ownership = BufferedOwnership::default();
            let gate = Arc::new(AtomicBool::new(false));
            ownership.publish_with_gate(&gate, true);
            let mut audio = AudioOut::new(data, None, [7; 32], kind);
            audio.set_ownership(gate.clone());
            audio.observe_main_stream(9, 23, 0, Instant::now(), &ownership, None);
            if kind == 102 {
                audio.aac_worker = Some(aac_worker::AacWorker::spawn(ownership.subscribe(), |_, pcm| {
                    Ok(pcm.chunks_exact(crate::aac::FRAMES * CHANNELS)
                        .map(|frame| frame[0].to_be_bytes().to_vec()).collect())
                }).unwrap());
            }

            let handover = ownership.clone();
            let change_owner = Some(Box::new(move || {
                handover.publish_with_gate(&gate, false);
                handover.publish_with_gate(&gate, true);
            }) as Box<dyn FnOnce() + Send + Sync>);
            if before_worker { audio.before_aac_worker_admission = change_owner; }
            else { audio.before_rtp_write = change_owner; }
            let feedback = |audio: &AudioOut| {
                let source = audio.feedback_source().unwrap();
                let snapshot = source.snapshot(1);
                let mut trace = FeedbackTrace::default();
                trace.set_observation_snapshots(Some(snapshot), Some(source.snapshot(1)));
                let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
                    ("type", Value::int(i64::from(kind))), ("streamConnectionID", Value::int(23)), ("sampleTime", Value::int(0)),
                ])]))]));
                trace.observe(9, Some(1), &body).unwrap()
            };

            let stale = vec![11i16; samples_per_packet * CHANNELS];
            let fresh = vec![22i16; samples_per_packet * CHANNELS];
            audio.send_main_tick(&stale, None, None, Instant::now(), Some(0)).await.unwrap();
            tokio::time::timeout(Duration::from_secs(1), async {
                while audio.before_rtp_write.is_some() {
                    tokio::task::yield_now().await;
                    audio.send_main_tick(&[], None, None, Instant::now(), Some(0)).await.unwrap();
                }
            }).await.unwrap();
            assert_eq!(audio.rtp_ownership_generation(), Some(1));
            let mut wire = [0; 2048];
            assert!(tokio::time::timeout(Duration::from_millis(10), data_peer.recv(&mut wire)).await.is_err(),
                "stale type {kind} reached UDP (before_worker={before_worker})");
            assert!(!feedback(&audio).contains("writtenRtpBeforeQueue="));

            audio.send_main_tick(&fresh, None, None, Instant::now(), Some(1)).await.unwrap();
            let n = tokio::time::timeout(Duration::from_secs(1), async {
                while audio.packets_sent() == 0 {
                    tokio::task::yield_now().await;
                    audio.send_main_tick(&[], None, None, Instant::now(), Some(1)).await.unwrap();
                }
                data_peer.recv(&mut wire).await.unwrap()
            }).await.unwrap();
            assert_eq!(u16::from_be_bytes(wire[2..4].try_into().unwrap()), 0);
            let nonce = u64::from_le_bytes(wire[n-8..n].try_into().unwrap());
            assert_eq!(nonce, 0);
            let raw = crate::crypto::chacha_open(&[7; 32], &nonce64(nonce), &wire[12..n-8], &wire[4..12]).unwrap();
            let expected = 22i16.to_be_bytes().repeat(if kind == 100 { fresh.len() } else { 1 });
            assert_eq!(raw, expected, "first type {kind} packet must contain only fresh PCM");
            let written = u32::from_be_bytes(wire[4..8].try_into().unwrap());
            assert!(feedback(&audio).contains(&format!("writtenRtpBeforeQueue={written} ")));
        }
    }

    #[tokio::test]
    async fn buffered_tcp_rejects_pcm_prepared_before_take_and_return_at_worker_admission() {
        use crate::{audio_diagnostics::FeedbackTrace, bplist::{self, Value}};
        use tokio::{io::AsyncReadExt, net::TcpListener};

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let data = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (control, channel, _control_peer) = buffered::tests::control_pair(48000).await;
        let owner = channel.buffered_ownership.clone();
        let mut audio = AudioOut::with_transport(
            AudioTransport::Buffered(buffered::BufferedTransport::new(data, control, 100_000)),
            None, [7; 32], 103);
        audio.observe_main_stream(23, 70, 0, Instant::now(), &owner, None);
        audio.aac_worker = Some(aac_worker::AacWorker::spawn(owner.subscribe(), |_, pcm| {
            Ok(pcm.chunks_exact(crate::aac::FRAMES * CHANNELS).map(|frame| frame[0].to_be_bytes().to_vec()).collect())
        }).unwrap());
        let handover = owner.clone();
        audio.before_aac_worker_admission = Some(Box::new(move || {
            handover.publish(false);
            handover.publish(true);
        }));
        let feedback = |audio: &AudioOut| {
            let source = audio.feedback_source().unwrap();
            let mut trace = FeedbackTrace::default();
            trace.set_observation_snapshots(Some(source.snapshot(1)), Some(source.snapshot(1)));
            let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
                ("type", Value::int(103)), ("streamConnectionID", Value::int(70)), ("sampleTime", Value::int(0)),
            ])]))]));
            trace.observe(23, Some(1), &body).unwrap()
        };

        let stale = vec![11i16; crate::aac::FRAMES * CHANNELS];
        let fresh = vec![22i16; crate::aac::FRAMES * CHANNELS];
        // Ownership rolls over after the transport check, just before AAC admission.
        audio.send_main_tick(&stale, Some(0), None, Instant::now(), Some(0)).await.unwrap();
        assert_eq!(audio.buffered_loss_generation(), Some(1));
        // Let the control observer adopt generation 1 before draining the
        // queued AAC output. With no previous TCP write there is no flush to acknowledge.
        tokio::time::sleep(Duration::from_millis(1)).await;
        audio.send_main_tick(&fresh, Some(1), None, Instant::now(), Some(1)).await.unwrap();
        let record = tokio::time::timeout(Duration::from_secs(1), async {
            while audio.packets_sent() == 0 {
                audio.send_main_tick(&[], Some(1), None, Instant::now(), Some(1)).await.unwrap();
                tokio::task::yield_now().await;
            }
            assert_eq!(car.read_u16().await.unwrap(), 40);
            let mut record = [0; 38];
            car.read_exact(&mut record).await.unwrap();
            record
        }).await.unwrap();
        assert_eq!(&record[30..38], &0u64.to_le_bytes());
        let raw = crate::crypto::chacha_open(&[7; 32], &nonce64(0), &record[12..30], &record[4..12]).unwrap();
        assert_eq!(raw, 22i16.to_be_bytes(), "first TCP record must contain only fresh PCM");
        let written = u32::from_be_bytes(record[4..8].try_into().unwrap());
        assert!(feedback(&audio).contains(&format!("writtenRtpBeforeQueue={written} ")));
    }

    #[tokio::test]
    async fn id9_resume_tracks_actual_rtp_and_sync_writes_on_the_same_stream() {
        use std::sync::atomic::AtomicU64;
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sync_receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sync = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        sync.connect(sync_receiver.local_addr().unwrap()).await.unwrap();
        let owner = Arc::new(AtomicBool::new(true));
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish_with_gate(&owner, true);
        let unborrow = Arc::new(AtomicU64::new(0));
        let mut audio = AudioOut::new(socket, Some(sync), [0; 32], 102);
        audio.set_ownership(owner.clone());
        audio.observe_main_stream(9, 23, 1, std::time::Instant::now(), &ownership, None);
        assert!(audio.control_observer.is_some());
        audio.observe_id9_resume(unborrow.clone());
        audio.seq = u16::MAX - 1;
        audio.send_payload(&[1; 8], 1024, Some(0)).await.unwrap();
        audio.send_payload(&[2; 8], 1024, Some(0)).await.unwrap();
        ownership.publish_with_gate(&owner, false);
        audio.send_payload(&[3; 8], 1024, Some(0)).await.unwrap();
        audio.send_sync_now(1_000_000_000).await.unwrap();
        unborrow.store(1, Ordering::Release);
        ownership.publish_with_gate(&owner, true);
        audio.send_sync_now(2_000_000_000).await.unwrap();
        let trace = audio.resume_trace.as_ref().unwrap();
        assert!(trace.first_rtp_pending);
        assert!(!trace.first_sync_pending);
        assert_eq!((trace.last_rtp.unwrap().marker, trace.last_rtp.unwrap().seq, trace.last_rtp.unwrap().rtp_ts), (false, u16::MAX, 1024));
        audio.send_payload(&[4; 8], 1024, Some(1)).await.unwrap();
        let trace = audio.resume_trace.as_ref().unwrap();
        assert!(!trace.first_rtp_pending);
        assert_eq!((trace.last_rtp.unwrap().marker, trace.last_rtp.unwrap().seq, trace.last_rtp.unwrap().rtp_ts), (true, 0, 2048));
        let mut wire = [0; 256];
        let timeout = std::time::Duration::from_secs(1);
        for _ in 0..3 { tokio::time::timeout(timeout, receiver.recv(&mut wire)).await.unwrap().unwrap(); }
        assert_eq!(&wire[..8], &[0x80, 0xe6, 0, 0, 0, 0, 8, 0]);
        for ntp in [1_000_000_000, 2_000_000_000] {
            assert_eq!(tokio::time::timeout(timeout, sync_receiver.recv(&mut wire)).await.unwrap().unwrap(), SYNC_PACKET_LEN);
            assert_eq!(&wire[8..16], &ntp_be(ntp));
        }
        let new_socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut replacement = AudioOut::new(new_socket, None, [0; 32], 102);
        replacement.observe_main_stream(9, 24, 0, std::time::Instant::now(), &Default::default(), None);
        replacement.observe_id9_resume(unborrow.clone());
        replacement.send_sync_now(3_000_000_000).await.unwrap();
        assert!(!replacement.resume_trace.as_ref().unwrap().first_rtp_pending);
        unborrow.store(2, Ordering::Release);
        replacement.send_sync_now(4_000_000_000).await.unwrap();
        let replacement_trace = replacement.resume_trace.as_ref().unwrap();
        assert!(replacement_trace.first_rtp_pending);
        assert!(!replacement_trace.first_sync_pending);
        assert!(replacement_trace.last_sync.is_none());
    }

    #[tokio::test]
    async fn muted_owner_sends_no_udp_and_a_new_grant_resumes_with_marker() {
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let owner = Arc::new(AtomicBool::new(false));
        let mut audio = AudioOut::new(socket, None, [0u8;32], 100);
        audio.set_ownership(owner.clone());
        let samples = vec![100i16; SAMPLES_PER_PACKET * CHANNELS];
        audio.send_pcm(&samples).await.unwrap();
        assert_eq!(audio.packets_sent(), 0);
        assert_eq!(audio.payload_bytes_sent(),0);
        assert_eq!(audio.blocked_samples(), samples.len() as u64);
        let mut packet = [0u8;2048];
        assert!(tokio::time::timeout(std::time::Duration::from_millis(20), receiver.recv(&mut packet)).await.is_err());
        owner.store(true, Ordering::Release);
        audio.send_pcm(&samples).await.unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(1), receiver.recv(&mut packet)).await.unwrap().unwrap();
        assert_eq!(audio.packets_sent(), 1);
        assert_eq!(audio.codec_name(),"PCM");
        assert_eq!(audio.payload_bytes_sent(),(samples.len()*2) as u64);
        assert_ne!(packet[1] & 0x80, 0);
    }

    #[tokio::test]
    async fn pcm_diagnostics_bound_new_samples_without_losing_the_previous_window_outcome() {
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut out = AudioOut::new_pcm(socket, None, [0; 32], 48000);
        let mut rtp = RtpHeader { seq: u16::MAX, rtp_ts: 123, marker: false };
        out.pcm_retry.new_writes = 1;
        let carry = out.pcm_retry.sample(rtp, 100, Some(libc::ENOBUFS));
        out.take_pcm_retry_report().unwrap();
        out.pcm_retry.finish(carry, "expired", None);
        for i in 0..12 {
            rtp.seq = i;
            rtp.rtp_ts = 475 + u32::from(i) * 352;
            let sampled = out.pcm_retry.sample(rtp, 200 + u64::from(i), Some(libc::ENOBUFS));
            out.pcm_retry.finish(sampled, "no_slack", None);
        }
        let report = out.take_pcm_retry_report().unwrap();
        assert_eq!(report.matches("outcomeMonoNs=").count(), PCM_DETAIL_LIMIT + 1);
        assert!(report.contains("detailSuppressed=8"));
        assert!(report.contains(&format!("seq=65535 rtpTs=123 writeMonoNs=100 writeErrno={} outcome=expired", libc::ENOBUFS)));
        assert!(report.contains("seq=0 rtpTs=475"));
        assert!(out.take_pcm_retry_report().is_none());
    }

    #[tokio::test]
    async fn pcm_retry_uses_each_slot_after_a_late_wake() {
        use std::os::fd::OwnedFd;
        use std::os::unix::net::UnixDatagram;
        let (tx, _rx) = UnixDatagram::pair().unwrap();
        tx.set_nonblocking(true).unwrap();
        while tx.send(&[0; 2048]).is_ok() {}
        let fd: OwnedFd = tx.into();
        let rate = 44100;
        let mut audio = AudioOut::new_pcm(UdpSocket::from_std(fd.into()).unwrap(), None, [4;32], rate);
        let period = crate::audio_pacing::period(rate);
        let now = Instant::now();
        let scheduled = now - period * 2;
        let current = crate::audio_pacing::current_pcm_due(scheduled, 704, rate);
        assert_eq!(current, now);
        let samples = [1; SAMPLES_PER_PACKET * CHANNELS * 2];
        audio.send_main_tick(&samples[..samples.len()/2], None, None, current, None).await.unwrap();
        assert_eq!(audio.pending_pcm.take().unwrap().expires, now + period * 2);
        audio.send_main_tick(&samples, None, Some(now + Duration::from_secs(1)), current, None).await.unwrap();
        assert_eq!(audio.pending_pcm.take().unwrap().expires, now + period * 2);
        assert!(audio.pcm_retry.max_new_delay_us >= period.as_micros());
    }

    #[tokio::test]
    async fn pcm_retry_serializes_generation_change_with_write() {
        use std::sync::mpsc;
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        let gate = Arc::new(AtomicBool::new(false));
        ownership.publish_with_gate(&gate, true);
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(peer.local_addr().unwrap()).await.unwrap();
        let mut audio = AudioOut::new_pcm(socket, None, [0;32], 48000);
        audio.pcm_ownership = Some(ownership.subscribe());
        audio.set_ownership(gate.clone());
        let (start, wait) = mpsc::channel();
        let (entered, attempting) = mpsc::channel();
        let (done, finished) = mpsc::channel();
        let writer_gate = gate.clone();
        let writer = std::thread::spawn(move || {
            wait.recv().unwrap();
            entered.send(()).unwrap();
            ownership.publish_with_gate(&writer_gate, false);
            assert!(!writer_gate.load(Ordering::Acquire));
            ownership.publish_with_gate(&writer_gate, true);
            done.send(()).unwrap();
        });
        let AudioTransport::Realtime(socket) = &audio.transport else { unreachable!() };
        let wire = [1, 2, 3, 4];
        audio.with_pcm_retry_ownership(Some(0), || {
            start.send(()).unwrap();
            attempting.recv().unwrap();
            assert_eq!(finished.recv_timeout(Duration::from_millis(20)), Err(mpsc::RecvTimeoutError::Timeout));
            assert!(gate.load(Ordering::Acquire), "the production gate changed during an in-flight write");
            assert_eq!(socket2::SockRef::from(socket).send_with_flags(&wire, libc::MSG_DONTWAIT).unwrap(), wire.len());
            Ok(())
        }).unwrap();
        writer.join().unwrap();
        assert!(audio.with_pcm_retry_ownership::<()>(Some(0), || panic!("stale write")).is_err());
        let mut received = [0; 4];
        let size = tokio::time::timeout(Duration::from_secs(1), peer.recv(&mut received)).await.unwrap().unwrap();
        assert_eq!(&received[..size], &wire);
    }

    #[tokio::test]
    async fn pcm_retry_writes_new_first_then_only_the_latest_original_datagram() {
        use std::os::fd::OwnedFd;
        use std::os::unix::net::UnixDatagram;
        use std::time::Instant;
        let (tx, rx) = UnixDatagram::pair().unwrap();
        tx.set_nonblocking(true).unwrap();
        rx.set_nonblocking(true).unwrap();
        while tx.send(&[0; 2048]).is_ok() {}
        let fd: OwnedFd = tx.into();
        let mut audio = AudioOut::new_pcm(UdpSocket::from_std(fd.into()).unwrap(), None, [7; 32], 48000);
        let samples = [42; SAMPLES_PER_PACKET * CHANNELS];
        for _ in 0..2 {
            audio.send_main_tick(&samples, None, None, Instant::now(), None).await.unwrap();
        }
        let mut buf = [0; 2048];
        while rx.recv(&mut buf).is_ok() {}
        let silence = [0; SAMPLES_PER_PACKET * CHANNELS];
        audio.send_main_tick(&silence, None, None, Instant::now(), None).await.unwrap();
        let size = rx.recv(&mut buf).unwrap();
        assert_eq!(&buf[2..4], &2u16.to_be_bytes());
        assert_eq!(rx.recv(&mut buf).unwrap(), size);
        let mut header = [0u8; RTP_HEADER_LEN];
        header[..4].copy_from_slice(&[0x80, 0x80 | 100, 0, 1]);
        header[4..8].copy_from_slice(&352u32.to_be_bytes());
        let pcm = 42i16.to_be_bytes().repeat(samples.len());
        let sealed = chacha_seal(&[7;32], &nonce64(1), &pcm, &header[4..]);
        assert_eq!(&buf[..size], [header.as_slice(), &sealed, &1u64.to_le_bytes()].concat());
        assert!(rx.recv(&mut buf).is_err());
        assert_eq!(audio.packets_sent(), 2);
        assert_eq!(audio.tick_signal_packets(&silence, 2), 1);
        let report = audio.take_pcm_retry_report().unwrap();
        assert!(report.contains("seq=0 rtpTs=0") && report.contains("outcome=replaced"), "{report}");
        assert!(report.contains("seq=1 rtpTs=352") && report.contains("outcome=retry_sent"), "{report}");
        assert!(report.contains(&format!("writeErrno={}", libc::EAGAIN)), "{report}");
        // Reverse the contents: the new packet is audible, the retried one silent.
        let AudioTransport::Realtime(sock) = &audio.transport else { unreachable!() };
        while socket2::SockRef::from(sock).send_with_flags(&buf, libc::MSG_DONTWAIT).is_ok() {}
        audio.send_main_tick(&silence, None, None, Instant::now(), None).await.unwrap();
        while rx.recv(&mut buf).is_ok() {}
        audio.send_main_tick(&samples, None, None, Instant::now(), None).await.unwrap();
        assert_eq!(audio.packets_sent(), 4);
        assert_eq!(audio.tick_signal_packets(&samples, 2), 1);
    }

    #[tokio::test]
    async fn pcm_retry_kernel_rejection_is_final() {
        use std::os::fd::OwnedFd;
        use std::os::unix::net::UnixDatagram;
        let (tx, rx) = UnixDatagram::pair().unwrap();
        tx.set_nonblocking(true).unwrap();
        rx.set_nonblocking(true).unwrap();
        let mut buf = [0; RTP_HEADER_LEN + SAMPLES_PER_PACKET * CHANNELS * 2 + 16 + NONCE_TAIL_LEN];
        while tx.send(&buf).is_ok() {}
        let fd: OwnedFd = tx.into();
        let mut audio = AudioOut::new_pcm(UdpSocket::from_std(fd.into()).unwrap(), None, [1; 32], 48000);
        let samples = [0; SAMPLES_PER_PACKET * CHANNELS];
        audio.send_main_tick(&samples, None, None, Instant::now(), None).await.unwrap();
        assert_eq!(audio.pcm_retry.saved, 1);
        let _ = audio.take_pcm_retry_report(); // The outcome belongs to the next report.
        // Make space for exactly the new datagram. Its predecessor must not get another turn.
        rx.recv(&mut buf).unwrap();
        audio.send_main_tick(&samples, None, None, Instant::now(), None).await.unwrap();
        assert_eq!((audio.packets_sent(), audio.pcm_retry.failed), (1, 1));
        let report = audio.take_pcm_retry_report().unwrap();
        assert!(report.contains("seq=0 rtpTs=0") && report.contains("outcome=retry_failed"), "{report}");
        assert!(report.contains(&format!("retryErrno={}", libc::EAGAIN)), "{report}");
        assert!(audio.pending_pcm.is_none());
        while rx.recv(&mut buf).is_ok() {}
        audio.send_main_tick(&samples, None, None, Instant::now(), None).await.unwrap();
        assert_eq!((audio.packets_sent(), audio.pcm_retry.sent), (2, 0));
    }

    #[tokio::test]
    async fn pcm_retry_expires_skips_without_slack_and_dies_on_handover() {
        use std::time::{Duration, Instant};
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(peer.local_addr().unwrap()).await.unwrap();
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish(true);
        let mut out = AudioOut::new_pcm(socket, None, [0; 32], 48000);
        out.pcm_ownership = Some(ownership.subscribe());
        let samples = [0; SAMPLES_PER_PACKET * CHANNELS];
        for case in 0..3 {
            let now = Instant::now();
            out.pending_pcm = Some(PendingPcm { wire: vec![42], has_signal: false, seq: 7, diagnostic: None, payload_bytes: 1,
                expires: if case == 0 { now } else { now + Duration::from_secs(1) },
                ownership_generation: Some(0), cache_generation: None });
            if case == 2 { ownership.publish(false); ownership.publish(true); }
            let due = if case == 1 { now - crate::audio_pacing::period(48000) } else { now };
            out.send_main_tick(&samples, None, None, due, None).await.unwrap();
            assert!(out.pending_pcm.is_none());
        }
        assert_eq!((out.pcm_retry.expired, out.pcm_retry.no_slack, out.pcm_retry.discarded), (1, 1, 1));
        assert_eq!(out.packets_sent(), 3);
        let report = out.take_pcm_retry_report().unwrap();
        assert!(report.contains("newWrites=3") && report.contains("retrySent=0"));
        assert!(out.take_pcm_retry_report().is_none());
    }

    #[tokio::test]
    async fn full_audio_socket_drops_one_tick_and_resumes_with_the_correct_clock() {
        use std::os::fd::OwnedFd;
        use std::os::unix::net::UnixDatagram;
        use std::{io::ErrorKind, time::Duration};

        // A connected datagram pair gives reproducible kernel backpressure without Wi-Fi loss.
        let (tx, rx) = UnixDatagram::pair().unwrap();
        tx.set_nonblocking(true).unwrap();
        rx.set_nonblocking(true).unwrap();
        while tx.send(&[0; 2048]).is_ok() {}
        let fd: OwnedFd = tx.into();
        let socket = UdpSocket::from_std(std::net::UdpSocket::from(fd)).unwrap();
        let mut sender = AudioOut::new(socket, None, [7; 32], 102);
        tokio::time::timeout(Duration::from_millis(100), sender.send_payload(&[42; 64], 1024, None))
            .await.expect("an unwritable data port must not hold the audio pump").unwrap();
        assert_eq!((sender.packets_sent(), sender.payload_bytes_sent()), (0, 0));
        assert_eq!(sender.congestion_drops, 1);
        let diagnostic = sender.take_udp_drop_report().expect("record the kernel rejection");
        assert!(diagnostic.contains("wouldBlock=1 enobufs=0"));
        assert!(diagnostic.contains(&format!("wouldBlockErrno={}", libc::EAGAIN)));
        assert!(sender.take_udp_drop_report().is_none());
        let mut wire = [0; 2048];
        loop {
            match rx.recv(&mut wire) {
                Ok(_) => continue,
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(e) => panic!("{e}"),
            }
        }
        sender.send_payload(&[43; 64], 1024, None).await.unwrap();
        let n = rx.recv(&mut wire).unwrap();
        assert_eq!(u16::from_be_bytes(wire[2..4].try_into().unwrap()), 1);
        assert_eq!(u32::from_be_bytes(wire[4..8].try_into().unwrap()), 1024);
        assert_eq!(u64::from_le_bytes(wire[n-8..n].try_into().unwrap()), 1);
        assert_ne!(wire[1] & 0x80, 0);
        assert_eq!((sender.packets_sent(), sender.payload_bytes_sent()), (1, 64));
    }

    #[test]
    fn udp_drop_report_keeps_enobufs_separate_from_would_block() {
        let mut drops = UdpDropWindow::default();
        drops.record(&std::io::Error::from_raw_os_error(libc::ENOBUFS), 12);
        drops.record(&std::io::Error::from_raw_os_error(libc::EAGAIN), 14);
        drops.record(&std::io::Error::from_raw_os_error(libc::ENOBUFS), 20);
        assert_eq!((drops.would_block, drops.enobufs), (1, 2));
        assert_eq!((drops.first_mono_ns, drops.last_mono_ns), (12, 20));
    }

    #[tokio::test]
    async fn measured_audio_bytes_exclude_rtp_crypto_overhead_and_failed_writes() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut audio=AudioOut::new(socket,None,[0;32],102);
        assert_eq!(audio.codec_name(),"AAC");
        let payload=[1u8;32];
        assert!(audio.send_payload(&payload,1024,None).await.is_err());
        assert_eq!(audio.payload_bytes_sent(),0);
        let AudioTransport::Realtime(udp) = &audio.transport else { panic!("expected UDP"); };
        udp.connect(receiver.local_addr().unwrap()).await.unwrap();
        audio.send_payload(&payload,1024,None).await.unwrap();
        let mut packet=[0u8;128];
        let received=receiver.recv(&mut packet).await.unwrap();
        assert_eq!(received,payload.len()+12+16+8);
        assert_eq!(audio.payload_bytes_sent(),32);
        assert_eq!(audio.packets_sent(),1);
    }
}
