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
/** ChaCha20-Poly1305 tag 长度。 */
const TAG_SIZE: usize = 16;
/** 包尾小端 nonce 计数长度。 */
const NONCE_TAIL_LEN: usize = 8;
/** 同步包定长（8B 头 + 8B NTP + 4B 下一包时间戳）。 */
const SYNC_PACKET_LEN: usize = 20;

/// CarPlay 音频发送器：RTP over UDP，ChaCha20-Poly1305 密封。
pub struct AudioOut {
    /// **跨批次的余数缓冲**：CarLife 的 MEDIA/TTS 数据块长度与 352 帧不对齐，chunks_exact 会把
    /// 每块的尾巴丢掉 → 丢样本=卡顿/破音，还可能把左右声道切错位（听起来像单声道）。
    carry: Vec<i16>,
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
    /** 同步包自己的序号（与音频 RTP 序号无关，独立递增）。 */
    sync_seq: u16,
    /** 首包 Marker 待打标记（打完即清）。 */
    marker_pending: bool,
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
            sync_seq: 0,
            marker_pending: true,
            carry: Vec::new(),
        }
    }

    /// 发送交错 PCM（48kHz 立体声 i16）。内部按 352 帧切包逐包发送，
    /// **尾部不足一包的余数不发**（由调用方留存到下一批）。
    ///
    /// 负载样本是 **16bit 大端**：CarPlay LPCM 线格式即大端，接收端收到后
    /// byteSwap 回小端喂 AudioTrack（DiPlay AndroidMediaSink 同款）；Android
    /// 采集是原生小端，漏换序实测得到「沙沙声」（cardeck 2026-10-10 真车验证）。
    /// AAC 通路不换序（比特流语义），本项目只走 PCM 故恒换序。
    pub async fn send_pcm(&mut self, frames: &[i16]) -> std::io::Result<()> {
        let samples_per_packet = SAMPLES_PER_PACKET * CHANNELS;
        // 先并入上一批余数，攒够整包才发（余数留到下一次）：
        // CarLife 的 MEDIA/TTS 数据块长度与 352 帧不对齐，`chunks_exact` 会丢掉每块尾巴 →
        // 丢样本=卡顿/破音，还可能把左右声道切错位（听起来像单声道）。
        self.carry.extend_from_slice(frames);
        let ready = self.carry.len() / samples_per_packet * samples_per_packet;
        let pending: Vec<i16> = self.carry.drain(..ready).collect();
        for chunk in pending.chunks_exact(samples_per_packet) {
            // PCM 负载（交错立体声 16bit **BE**）
            let mut payload = Vec::with_capacity(samples_per_packet * 2);
            for sample in chunk {
                payload.extend_from_slice(&sample.to_be_bytes());
            }

            let mut header = [0u8; RTP_HEADER_LEN];
            header[0] = 0x80; // V=2
            // M（首包标记）| PT（低 7 位）
            header[1] = (self.pt & 0x7F) | if self.marker_pending { 0x80 } else { 0x00 };
            header[2] = (self.seq >> 8) as u8;
            header[3] = (self.seq & 0xFF) as u8;
            header[4..8].copy_from_slice(&self.ts.to_be_bytes());
            // SSRC(8..12) 保持 0——写死，部分车机按 SSRC 关联 SETUP，随机值会无声

            // AAD = 头字节 4..12（timestamp + SSRC）
            let sealed = chacha_seal(&self.key, &nonce64(self.nonce), &payload, &header[4..12]);

            let mut wire = Vec::with_capacity(RTP_HEADER_LEN + sealed.len() + NONCE_TAIL_LEN);
            wire.extend_from_slice(&header);
            wire.extend_from_slice(&sealed); // 密文 || 16B tag
            wire.extend_from_slice(&self.nonce.to_le_bytes()); // 尾部 8B 小端 nonce

            self.udp.send(&wire).await?;

            self.seq = self.seq.wrapping_add(1);
            self.ts = self.ts.wrapping_add(SAMPLES_PER_PACKET as u32);
            self.nonce = self.nonce.wrapping_add(1);
            self.packets = self.packets.wrapping_add(1);
            self.marker_pending = false;
        }
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
        let packet = build_sync_packet(self.sync_seq, rtp_ts, ntp_nanos);
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
pub fn build_sync_packet(seq: u16, rtp_ts: u32, ntp_nanos: u64) -> Vec<u8> {
    let mut packet = vec![0u8; SYNC_PACKET_LEN];
    packet[0] = 0x80 | if seq == 0 { 0x10 } else { 0x00 };
    packet[1] = 0xD4;
    packet[2..4].copy_from_slice(&seq.to_be_bytes());
    packet[4..8].copy_from_slice(&rtp_ts.to_be_bytes());
    packet[8..16].copy_from_slice(&ntp_be(ntp_nanos));
    let next = rtp_ts.wrapping_add(SAMPLES_PER_PACKET as u32);
    packet[16..20].copy_from_slice(&next.to_be_bytes());
    packet
}
