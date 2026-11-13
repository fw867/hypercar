//! CarPlay 主音频发送端（RTP over UDP + ChaCha20-Poly1305，对照 cardeck
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
use crate::timing::ntp_be;
use tokio::net::UdpSocket;

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

/// CarPlay 音频发送器：RTP over UDP，ChaCha20-Poly1305 密封。
pub struct AudioOut {
    /** 音频数据报套接字（调用方 connect 好到车机 dataPort）。 */
    udp: UdpSocket,
    /** 同步包套接字（调用方 connect 好到车机 controlPort；车机没下发时为 None）。 */
    sync: Option<UdpSocket>,
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
    payload_bytes: u64,
    /** 同步包自己的序号（与音频 RTP 序号无关，独立递增）。 */
    sync_seq: u16,
    /** 首包 Marker 待打标记（打完即清）。 */
    marker_pending: bool,
    ownership: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    blocked_samples: u64,
    aac: Option<crate::aac::AacEncoder>,
    aac_needs_reset: bool,
}

impl AudioOut {
    /// 构造。data/sync 两个套接字都由调用方 connect 好（本类型只用 `send`）。
    pub fn new(
        data: UdpSocket,
        sync: Option<UdpSocket>,
        stream_key: [u8; 32],
        payload_type: u8,
    ) -> Self {
        Self {
            udp: data,
            sync,
            key: stream_key,
            seq: 0,
            ts: 0,
            nonce: 0,
            pt: payload_type,
            packets: 0,
            payload_bytes: 0,
            sync_seq: 0,
            marker_pending: true,
            ownership: None,
            blocked_samples: 0,
            aac: None,
            aac_needs_reset: false,
        }
    }

    pub fn new_aac(data: UdpSocket, sync: Option<UdpSocket>, key: [u8;32], encoder: crate::aac::AacEncoder) -> Self {
        let mut output = Self::new(data, sync, key, 102);
        output.aac = Some(encoder);
        output
    }
    pub fn stream_type(&self) -> i64 { self.pt as i64 }
    pub fn codec_name(&self) -> &'static str { match self.pt {100=>"PCM",102=>"AAC",_=>""} }
    pub fn payload_bytes_sent(&self) -> u64 { self.payload_bytes }
    pub fn samples_per_packet(&self) -> usize { if self.pt == 102 { crate::aac::FRAMES } else { SAMPLES_PER_PACKET } }

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

    pub async fn send_pcm(&mut self, frames: &[i16]) -> std::io::Result<()> {
        if !self.owns_audio() {
            self.blocked_samples = self.blocked_samples.saturating_add(frames.len() as u64);
            self.marker_pending = true;
            if self.pt == 102 { self.aac.take(); self.aac_needs_reset = true; }
            return Ok(());
        }
        if self.pt == 102 {
            if self.aac_needs_reset {
                self.aac_needs_reset = false;
                self.aac = Some(crate::aac::AacEncoder::new()?);
            }
            let encoded = self.aac.as_mut().ok_or_else(|| std::io::Error::other("AAC encoder stopped"))?.encode(frames);
            let encoded = match encoded { Ok(frames) => frames, Err(error) => { self.aac.take(); return Err(error); } };
            for payload in encoded { self.send_payload(&payload, crate::aac::FRAMES as u32).await?; }
        } else {
            for chunk in frames.chunks_exact(SAMPLES_PER_PACKET * CHANNELS) {
                let mut payload = Vec::with_capacity(chunk.len() * 2);
                for sample in chunk { payload.extend_from_slice(&sample.to_be_bytes()); }
                self.send_payload(&payload, SAMPLES_PER_PACKET as u32).await?;
            }
        }
        Ok(())
    }

    async fn send_payload(&mut self, payload: &[u8], sample_frames: u32) -> std::io::Result<()> {
        // Recheck ownership after encoding; a resource handover may occur concurrently.
        if !self.owns_audio() {
            self.marker_pending = true;
            if self.pt == 102 { self.aac.take(); self.aac_needs_reset = true; }
            return Ok(());
        }
        let mut header = [0u8; RTP_HEADER_LEN];
        header[0] = 0x80;
        header[1] = (self.pt & 0x7f) | if self.marker_pending { 0x80 } else { 0 };
        header[2..4].copy_from_slice(&self.seq.to_be_bytes());
        header[4..8].copy_from_slice(&self.ts.to_be_bytes());
        let sealed = chacha_seal(&self.key, &nonce64(self.nonce), payload, &header[4..12]);
        let mut wire = Vec::with_capacity(RTP_HEADER_LEN + sealed.len() + NONCE_TAIL_LEN);
        wire.extend_from_slice(&header);
        wire.extend_from_slice(&sealed);
        wire.extend_from_slice(&self.nonce.to_le_bytes());
        // Never reuse an AEAD nonce after a send error (a send may have reached the peer).
        self.nonce = self.nonce.wrapping_add(1);
        self.seq = self.seq.wrapping_add(1);
        self.ts = self.ts.wrapping_add(sample_frames);
        self.udp.send(&wire).await?;
        self.payload_bytes = self.payload_bytes.saturating_add(payload.len() as u64);
        if self.packets == 0 && self.pt == 102 {
            crate::emit_log(&format!("AUDIO: first AAC RTP type=102 SPF=1024 raw_bytes={}", payload.len()));
        }
        self.packets = self.packets.wrapping_add(1);
        self.marker_pending = false;
        Ok(())
    }

    /// 发送 RTP↔NTP 同步包（调用方每秒调用一次；sync 套接字未配置时静默跳过）。
    ///
    /// `rtp_ts`：期望锚定的当前 RTP 时间戳；`ntp_nanos`：单调时钟纳秒——与
    /// TimingServer 应答给车机的是同一时间基，否则车机算出的锚点会整体偏移。
    pub async fn send_sync(&mut self, rtp_ts: u32, ntp_nanos: u64) -> std::io::Result<()> {
        let Some(sync) = self.sync.as_ref() else {
            return Ok(()); // 车机没下发 controlPort（旧车机）：只发音频
        };
        let packet = build_sync_packet_for_frames(self.sync_seq, rtp_ts, ntp_nanos, self.samples_per_packet() as u32);
        sync.send(&packet).await?;
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
        self.packets
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

#[cfg(test)]
mod ownership_tests {
    use super::*;
    use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
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
            sender.send_payload(&payload,1024).await.unwrap();
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
    async fn aac_owner_loss_discards_encoder_and_buffered_audio() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(receiver.local_addr().unwrap()).await.unwrap();
        let owner=Arc::new(AtomicBool::new(true));
        let mut sender=AudioOut::new_aac(socket,None,[0;32],crate::aac::AacEncoder::new().unwrap());
        sender.set_ownership(owner.clone());
        sender.send_pcm(&vec![500;704]).await.unwrap(); // Fractional AAC frame must not survive handover.
        owner.store(false,Ordering::Release);
        sender.send_pcm(&vec![500;704]).await.unwrap();
        assert!(sender.aac.is_none()); assert_eq!(sender.packets_sent(),0);
        owner.store(true,Ordering::Release);
        sender.send_pcm(&vec![0;704]).await.unwrap();
        assert!(sender.aac.is_some()); assert_eq!(sender.packets_sent(),0);
        assert!(sender.marker_pending);
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
    async fn measured_audio_bytes_exclude_rtp_crypto_overhead_and_failed_writes() {
        let receiver=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let socket=UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let mut audio=AudioOut::new(socket,None,[0;32],102);
        assert_eq!(audio.codec_name(),"AAC");
        let payload=[1u8;32];
        assert!(audio.send_payload(&payload,1024).await.is_err());
        assert_eq!(audio.payload_bytes_sent(),0);
        audio.udp.connect(receiver.local_addr().unwrap()).await.unwrap();
        audio.send_payload(&payload,1024).await.unwrap();
        let mut packet=[0u8;128];
        let received=receiver.recv(&mut packet).await.unwrap();
        assert_eq!(received,payload.len()+12+16+8);
        assert_eq!(audio.payload_bytes_sent(),32);
        assert_eq!(audio.packets_sent(),1);
    }
}
