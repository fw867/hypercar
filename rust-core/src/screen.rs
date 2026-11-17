//! CarPlay 主屏视频发送端（屏幕流 TCP 推流，对照 cardeck `video/VideoSender.kt`）。
//!
//! 线格式：每条消息 = 128 字节明文头 + body。头部布局（Little-Endian）：
//!  - `[0..4)`  u32LE body 长度（VideoFrame 含 16B Poly1305 tag）
//!  - `[4]`     u8 opcode：0=VideoFrame，1=VideoConfig，2=KeepAlive，5=KeepAliveWithBody
//!  - `[6]`     u8 small_param[1]：AVC config=0x04；HEVC config=0x1e且[7]=1
//!  - `[5]`     HEVC VideoFrame=0x10；HEVC config body为hvc1 sample entry包含hvcC
//!  - `[8..16)` params[0]：NTP 32.32 LE 时间戳（VideoFrame 用 pts 换算；config/keepAlive 归零）
//!  - `[16..20)` params[1] 低 4 字节 = width f32 LE；`[20..24)` = height f32 LE
//!    （两者同属 params[1]，对照 cardeck `CONFIG_WIDTH_HEIGHT_OFFSET = 16` + 偏移+4；
//!      仅 VideoConfig 写，VideoFrame/keepAlive 归零）
//!  - `[120..124)` 0.0f32；`[124..128)` body 长度 f32 LE（仅 KeepAliveWithBody）
//!  - 其余全零
//!
//! 加密（对照 cardeck `airplay/StreamFrameCipher.kt` 与 catplay ScreenFrameCodec）：
//!  - VideoConfig / KeepAlive 恒为**明文**（两种加密形态下都不密封），不消耗帧计数；
//!  - VideoFrame：ChaCha 形态 = ChaCha20-Poly1305 密封「4 字节 BE 长度前缀的 NAL 序列」，
//!    AAD = 最终写出的那 128 字节头（头只能构造一次——AAD 若与线上字节不一致，
//!    对端解密必然失败）、nonce = nonce64(帧计数)，每帧自增；
//!  - VideoFrame：AES-CTR 形态（未配对会话）= 无 tag、长度不变，密钥流跨帧连续
//!    （每帧新建流实例会导致对端解出乱码）。
//!
//! 真车实测依据（cardeck 文档事实，改头部字段前必读）：
//!  - VideoConfig 缺 Encrypted flag（0x04）或缺 (width,height) 两个 f32 时，
//!    部分真车（Audi 4851）**会话不断、触摸正常、屏全黑**——车机把后续密封帧按
//!    明文 NAL 解析，日志里看不出任何错误（DiPlay/WinPlay 只读 body 不校验头，
//!    所以测试台一直正常，真车才暴露）；
//!  - 屏幕流 keepAlive 必须每 1 秒发一帧（明文）——它不是视频流量的备份，而是接收端
//!    独立的存活信号；缺失时 RECORD 后约 10s 整轮会话被 reset（2026-10-19 奥迪 3315
//!    两轮实测，视频/feedback/事件通道当时全都健康）。

use crate::crypto::{chacha_seal, nonce64, Aes128CtrStream};
use crate::timing::ntp_be;
use crate::video_format::VideoCodec;
use std::time::Duration;
use std::os::fd::AsRawFd;
use tokio::io::AsyncWriteExt;
use tokio::net::tcp::OwnedWriteHalf;

/** 消息头定长。 */
const HEADER_LEN: usize = 128;
/** opcode：密封视频帧。 */
const OP_FRAME: u8 = 0;
/** opcode：明文 avcC / hvc1 配置。 */
const OP_CONFIG: u8 = 1;
/** opcode：keepAlive（空 body）。 */
const OP_KEEP_ALIVE: u8 = 2;
/** opcode：keepAlive + 统计体 body（车机 /info 声明 keepAliveSendStatsAsBody 时用）。 */
const OP_KEEP_ALIVE_WITH_BODY: u8 = 5;
/** ChaCha20-Poly1305 tag 长度。 */
const TAG_SIZE: usize = 16;

/**
 * 头部位标志（catplay `ScreenFlag`）：写进 `small_param[1]`（头部偏移 6）。
 * `Encrypted = 1<<2`：视频帧是密封的；VideoConfig 里**必须**声明，否则真车黑屏
 * （见模块文档）。
 */
const SCREEN_FLAG_ENCRYPTED: u8 = 0x04;

/** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
const KEEPALIVE_INTERVAL_MS: u64 = 1_000;

/** VideoConfig / keepAlive 的 params[0] 恒为零（只有 VideoFrame 才写 PTS）。 */
const ZERO_TS: [u8; 8] = [0u8; 8];

/** 屏幕流帧加密方式（AirPlay `et` 的两形态；未配对会话走 AesCtr）。 */
pub enum ScreenCipher {
    /** 已配对会话：ChaCha20-Poly1305 密封（16B tag）。 */
    ChaCha([u8; 32]),
    /** 未配对会话（et=NONE 时全零会话密钥）：AES-128-CTR 流式加密，密钥流跨帧连续。 */
    AesCtr(Aes128CtrStream),
}

/// 屏幕流发送器：持有 TCP 写半部（由调用方 `split_owned` 后传入）与加密形态。
pub struct ScreenSender {
    w: OwnedWriteHalf,
    cipher: ScreenCipher,
    /** ChaCha 帧计数（每帧自增；AES-CTR 形态不使用但照常计数，对齐参考实现）。 */
    frame_counter: u64,
    /** Source-reported encoded size; SPS dimensions take precedence in VideoConfig. */
    width: f32,
    height: f32,
    /// Diagnostic only: the old header-only stretch request is never applied to the wire.
    display_target: Option<(u32, u32)>,
    receiver_size: Option<(u32,u32)>,
    /** 车机 /info 声明 keepAliveSendStatsAsBody 时为 true → opcode=5 + 统计体。 */
    keep_alive_with_stats: bool,
    /** 上一次屏幕流 keepAlive 的时刻（1s 节流）。 */
    last_keep_alive: tokio::time::Instant,
    /** 累计发送帧数（对外统计）。 */
    frames: u64,
    /** 累计发送明文字节数（bytes_per_second 简化口径）。 */
    bytes: u64,
    /** 两次 keepAlive 之间的帧数/字节数（统计体用，参考实现 ScreenSenderStats 同义）。 */
    frames_since_keep_alive: u64,
    bytes_since_keep_alive: u64,
    /** 已发 keepAlive 计数（诊断用；统计体接入 bplist 后供日志输出）。 */
    #[allow(dead_code)]
    keep_alive_count: u64,
    /** 是否已发过 VideoConfig（未发前丢弃视频帧，对齐参考实现）。 */
    config_sent: bool,
    /** 当前源参数集（配置变化和分包缓存；codec切换时清空）。 */
    last_sps: Option<Vec<u8>>,
    last_pps: Option<Vec<u8>>,
    /** 首帧 NAL 类型只打印一次（诊断 H.264/H.265）。 */
    logged_first_frame: bool,
    codec: VideoCodec,
    hevc_allowed: bool,
    last_vps: Option<Vec<u8>>,
    waiting_idr: bool,
    last_sync_request: Option<tokio::time::Instant>,
    ownership: Option<std::sync::Arc<std::sync::atomic::AtomicBool>>,
    resource_grant: Option<(std::sync::Arc<parking_lot::Mutex<crate::modes::Modes>>,u64)>,
    source_frames: u64,
    ownership_skips: u64,
    sync_skips: u64,
    written_idrs: u64,
    wire_bytes: u64,
    last_source: Option<tokio::time::Instant>,
    last_write: Option<tokio::time::Instant>,
    last_owner: Option<bool>,
    tcp_diagnostics: Option<(std::sync::Arc<parking_lot::Mutex<crate::pipeline::Pipeline>>,u64)>,
    tcp_monitor: Option<crate::tcp_diagnostics::TcpMonitor>,
}

impl ScreenSender {
    /// 构造。宽高强制对齐到偶数（H.264 4:2:0 要求；奇数高度会让编码器首帧进错误态）。
    pub fn new(
        w: OwnedWriteHalf,
        cipher: ScreenCipher,
        width: u32,
        height: u32,
        keep_alive_with_stats: bool,
    ) -> Self {
        let even_width = (width / 2) * 2;
        let even_height = (height / 2) * 2;
        Self {
            w,
            cipher,
            frame_counter: 0,
            width: even_width as f32,
            height: even_height as f32,
            display_target: None,
            receiver_size: None,
            keep_alive_with_stats,
            last_keep_alive: tokio::time::Instant::now(),
            frames: 0,
            bytes: 0,
            frames_since_keep_alive: 0,
            bytes_since_keep_alive: 0,
            keep_alive_count: 0,
            config_sent: false,
            last_sps: None,
            last_pps: None,
            logged_first_frame: false,
            codec: VideoCodec::H264,
            hevc_allowed: false,
            last_vps: None,
            waiting_idr: true,
            last_sync_request: None,
            ownership: None,
            resource_grant: None,
            source_frames: 0,
            ownership_skips: 0,
            sync_skips: 0,
            written_idrs: 0,
            wire_bytes: 0,
            last_source: None,
            last_write: None,
            last_owner: None,
            tcp_diagnostics: None,
            tcp_monitor: None,
        }
    }

    pub fn attach_diagnostics(&mut self,pipeline:std::sync::Arc<parking_lot::Mutex<crate::pipeline::Pipeline>>,connection_id:u64) {
        self.tcp_monitor.take();
        let snapshot = crate::tcp_diagnostics::TcpSnapshot::capture(self.w.as_ref().as_raw_fd(),connection_id);
        {
            let mut p = pipeline.lock();
            p.video_tcp = Some(snapshot);
            p.screen_config_size = None;
            p.video_format = None;
        }
        self.tcp_monitor=Some(crate::tcp_diagnostics::TcpMonitor::start(self.w.as_ref().as_raw_fd(),connection_id,pipeline.clone()));
        self.tcp_diagnostics=Some((pipeline,connection_id));
    }
    fn publish_format(&self, sps_size: Option<(u32,u32)>, header_size: Option<(u32,u32)>) {
        if let Some((pipeline,id)) = &self.tcp_diagnostics {
            let mut p = pipeline.lock();
            if p.video_tcp.as_ref().is_some_and(|tcp| tcp.connection_id == *id) {
                p.screen_config_size = header_size;
                p.video_format = sps_size.map(|(width,height)| crate::pipeline::VideoFormat {
                    width, height, codec: self.codec.as_str()
                });
            }
        }
    }
    fn sample_tcp(&self) {
        if let Some((pipeline,id))=&self.tcp_diagnostics {
            let snapshot=crate::tcp_diagnostics::TcpSnapshot::capture(self.w.as_ref().as_raw_fd(),*id);
            let mut p=pipeline.lock();
            if p.video_tcp.as_ref().map(|s|s.connection_id)==Some(*id) {p.video_tcp=Some(snapshot);}
        }
    }

    pub fn transport_diagnostics(&self) -> String {
        self.sample_tcp();
        self.tcp_diagnostics.as_ref().and_then(|(pipeline,id)|
            pipeline.lock().video_tcp.as_ref().filter(|s|s.connection_id==*id).map(|s|s.summary()))
            .unwrap_or_else(||"TCP diagnostics unavailable".into())
    }

    /// Actual source size (also used by engine to detect encoder changes).
    pub fn size(&self) -> (u32, u32) {
        (self.width as u32, self.height as u32)
    }
    pub fn set_receiver_size(&mut self,size:(u32,u32)) {self.receiver_size=(size.0>=2 && size.1>=2).then_some(size);}
    pub fn receiver_size(&self)->Option<(u32,u32)> {self.receiver_size}

    /// Preserve old configuration for diagnostics, but never falsify encoded dimensions.
    pub fn set_display_target(&mut self, width: u32, height: u32) {
        self.display_target = if width >= 2 && height >= 2 {
            Some((width, height))
        } else {
            None
        };
        crate::emit_log(&format!("VIDEO: header-only stretch suspended requested={:?}; config follows encoded SPS/source", self.display_target));
    }

    pub fn reset_source(&mut self) {
        crate::emit_log(&format!("VIDEO: reset source {}", self.diagnostics()));
        self.publish_format(None, None);
        self.config_sent = false;
        self.last_vps = None; self.last_sps = None; self.last_pps = None;
        self.waiting_idr = true; self.last_sync_request = None;
        self.logged_first_frame = false;
    }

    pub fn hevc_allowed(&self)->bool {self.hevc_allowed}
    pub fn set_hevc_allowed(&mut self, allowed: bool) {
        self.hevc_allowed = allowed;
        if !allowed && self.codec == VideoCodec::Hevc {
            self.codec = VideoCodec::H264;
            self.reset_source();
        }
    }

    pub fn set_video_codec(&mut self, codec: VideoCodec) -> std::io::Result<()> {
        if codec == VideoCodec::Hevc && !self.hevc_allowed {
            return Err(std::io::Error::new(std::io::ErrorKind::Unsupported,"HEVC was not enabled by the receiver"));
        }
        if self.codec != codec {self.codec=codec;self.reset_source();}
        Ok(())
    }

    pub fn set_ownership(&mut self, owner: std::sync::Arc<std::sync::atomic::AtomicBool>) {
        self.ownership = Some(owner);
        self.observe_owner();
    }

    pub fn set_resource_grant(&mut self, modes:std::sync::Arc<parking_lot::Mutex<crate::modes::Modes>>, revision:u64) {
        // Do not observe here: installation validates while holding the same arbitration lock.
        self.resource_grant=Some((modes,revision));
    }

    fn observe_owner(&mut self) -> bool {
        let owned = self.ownership.as_ref().map(|v| v.load(std::sync::atomic::Ordering::Acquire)).unwrap_or(true)
            && self.resource_grant.as_ref().map(|(modes,revision)|modes.lock().can_restore_screen(*revision)).unwrap_or(true);
        if self.last_owner != Some(owned) {
            crate::emit_log(&format!("VIDEO: screen gate phone_owned={owned} written_frames={} source_units={} (local arbitration; not receiver display confirmation)", self.frames, self.source_frames));
            self.last_owner = Some(owned);
        }
        owned
    }

    pub fn diagnostics(&self) -> String {
        self.sample_tcp();
        let age = |t: Option<tokio::time::Instant>| t.map(|v| v.elapsed().as_millis() as i64).unwrap_or(-1);
        format!("source_units={} skipped_owner={} skipped_sync={} tcp_written_frames={} tcp_written_bytes={} payload_bytes={} written_idrs={} config={} waiting_idr={} phone_owned={:?} source_age_ms={} write_age_ms={} receiver_display=unknown",
            self.source_frames, self.ownership_skips, self.sync_skips, self.frames, self.wire_bytes,
            self.bytes, self.written_idrs, self.config_sent, self.waiting_idr, self.last_owner,
            age(self.last_source), age(self.last_write))
    }

    pub fn set_size(&mut self, width: u32, height: u32) {
        let w = (width / 2) * 2;
        let h = (height / 2) * 2;
        if w < 2 || h < 2 {
            return;
        }
        if w as f32 != self.width || h as f32 != self.height {
            crate::emit_log(&format!(
                "VIDEO: 编码源 {}x{} → {w}x{h} display_target={:?}",
                self.width as u32, self.height as u32, self.display_target
            ));
            self.config_sent = false;
            self.waiting_idr = true;
            self.last_sync_request = None;
            self.width = w as f32;
            self.height = h as f32;
        }
    }

    /// 发送 VideoConfig（明文 avcC body）。
    ///
    /// 头部必须与 catplay `ScreenFrame::config` 一致：`small_param[1] |= Encrypted(0x04)`
    /// 声明「后续视频帧是密封的」、params[1] = (width, height) 两个 f32 LE（车机据此
    /// 建立解码器画布）、params[0] 归零（config 不带 PTS）。缺任何一项 → 真车黑屏。
    pub async fn send_config(&mut self, avcc: &[u8]) -> std::io::Result<()> {
        if self.codec!=VideoCodec::H264 {return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput,"AVC configuration supplied for HEVC source"));}
        let size=crate::h264::avcc_dimensions(avcc);
        self.write_config(avcc,size).await
    }

    async fn write_config(&mut self, avcc: &[u8], sps_size: Option<(u32,u32)>) -> std::io::Result<()> {
        let mut header = build_header(OP_CONFIG, avcc.len(), &ZERO_TS);
        header[6] = SCREEN_FLAG_ENCRYPTED;
        if self.codec == VideoCodec::Hevc {header[6]=0x1e;header[7]=1;}
        // params[1]（偏移 16）里是**两个** f32：width 在 16..20、height 在 20..24。
        // 曾把 height 写到 24..28（误当 params[2]），车机读到的画布高度成 0 → 黑屏/错缩放。
        let (display_w, display_h) = sps_size.unwrap_or_else(|| self.size());
        header[16..20].copy_from_slice(&(display_w as f32).to_le_bytes());
        header[20..24].copy_from_slice(&(display_h as f32).to_le_bytes());
        let mut wire = Vec::with_capacity(HEADER_LEN + avcc.len());
        wire.extend_from_slice(&header);
        wire.extend_from_slice(avcc);
        self.w.write_all(&wire).await?;
        self.publish_format(sps_size, Some((display_w, display_h)));
        self.config_sent = true;
        self.waiting_idr = true;
        crate::emit_log(&format!("VIDEO: config TCP written {}B codec={} header={}x{} encoder_report={}x{} sps={sps_size:?} report_matches_sps={:?} flags=0x{:02x} header_stretch=false requested_target={:?}; receiver decode/display unconfirmed",
            avcc.len(), self.codec.as_str(), display_w, display_h, self.width, self.height, sps_size.map(|s| s == self.size()), header[6], self.display_target));
        Ok(())
    }

    fn source_nal_type(&self,nal:&[u8])->u8 {
        match self.codec {VideoCodec::H264=>nal_type(nal),VideoCodec::Hevc=>crate::hevc::nal_type(nal).unwrap_or(255)}
    }

    async fn update_hevc_config(&mut self,nals:&[&[u8]])->std::io::Result<()> {
        let get=|kind|nals.iter().copied().find(|n|crate::hevc::nal_type(n)==Some(kind));
        let (v,s,p)=(get(32),get(33),get(34));
        let changed=v.map(|n|self.last_vps.as_deref()!=Some(n)).unwrap_or(false)
            || s.map(|n|self.last_sps.as_deref()!=Some(n)).unwrap_or(false)
            || p.map(|n|self.last_pps.as_deref()!=Some(n)).unwrap_or(false);
        if changed {
            // Never combine a replacement VPS/SPS with stale dependent sets.
            if v.map(|n|self.last_vps.as_deref()!=Some(n)).unwrap_or(false) {self.last_sps=None;self.last_pps=None;}
            if s.map(|n|self.last_sps.as_deref()!=Some(n)).unwrap_or(false) {self.last_pps=None;}
            if let Some(n)=v {self.last_vps=Some(n.to_vec());}
            if let Some(n)=s {self.last_sps=Some(n.to_vec());}
            if let Some(n)=p {self.last_pps=Some(n.to_vec());}
            self.config_sent=false;self.waiting_idr=true;
        }
        if !self.config_sent {
            if let (Some(v),Some(s),Some(p))=(&self.last_vps,&self.last_sps,&self.last_pps) {
                let (config,size)=crate::hevc::build_config(v,s,p).ok_or_else(||std::io::Error::new(std::io::ErrorKind::InvalidData,"invalid or unsupported HEVC parameter sets"))?;
                self.write_config(&config,Some(size)).await?;
            }
        }
        Ok(())
    }

    /// 发送一帧视频。输入 AnnexB（可能粘连 SPS/PPS/IDR/P 多 NAL）：
    ///  - 检测到 SPS/PPS 且与上次不同时，先发 VideoConfig(avcC)；
    ///  - 其余 NAL（SPS/PPS 除外）打成一帧（4 字节 BE 长度前缀序列）按加密形态密封后
    ///    作 VideoFrame；`pts_us` 为毫秒时间戳 * 1000，换算 NTP 32.32 写进 params[0]。
    ///
    /// config 尚未发出时丢帧（对齐参考实现：车机没有 avcC 就无从解码）。
    #[cfg(test)]
    pub async fn send_video_annexb(&mut self, annexb: &[u8], pts_us: u64) -> std::io::Result<()> {
        self.send_video_annexb_from(annexb, pts_us, crate::video_format::FrameOrigin::Live).await
    }

    pub async fn send_video_annexb_from(&mut self, annexb: &[u8], pts_us: u64, origin: crate::video_format::FrameOrigin) -> std::io::Result<()> {
        self.source_frames += 1;
        self.last_source = Some(tokio::time::Instant::now());
        if !self.observe_owner() {
            self.ownership_skips += 1;
            self.waiting_idr = true;
            self.last_sync_request = None;
            return Ok(());
        }
        let nals = annexb_nals(annexb);
        if nals.is_empty() {
            return Ok(());
        }
        if !self.logged_first_frame {
            self.logged_first_frame = true;
            let types: Vec<_> = nals.iter().take(6).map(|n| self.source_nal_type(n)).collect();
            crate::emit_log(&format!("VIDEO: codec={} first NAL types={types:?}",self.codec.as_str()));
        }
        if self.codec == VideoCodec::Hevc {
            if nals.iter().any(|n|crate::hevc::nal_type(n).is_none()) {
                return Err(std::io::Error::new(std::io::ErrorKind::InvalidData,"invalid HEVC NAL header"));
            }
            self.update_hevc_config(&nals).await?;
        }
        if self.codec == VideoCodec::H264 {
        let mut sps: Option<&[u8]> = None;
        let mut pps: Option<&[u8]> = None;
        for &nal in &nals {
            match nal_type(nal) {
                7 => {
                    if sps.is_none() {
                        sps = Some(nal);
                    }
                }
                8 => {
                    if pps.is_none() {
                        pps = Some(nal);
                    }
                }
                _ => {}
            }
        }
        if let (Some(s), Some(p)) = (sps, pps) {
            let changed = self.last_sps.as_deref() != Some(s) || self.last_pps.as_deref() != Some(p);
            if changed || !self.config_sent {
                let avcc = build_avcc(s, p);
                if !avcc.is_empty() {
                    self.last_sps = Some(s.to_vec());
                    self.last_pps = Some(p.to_vec());
                    self.send_config(&avcc).await?;
                }
            }
        }
        }
        let is_idr = nals.iter().any(|nal| match self.codec {VideoCodec::H264=>nal_type(nal)==5, VideoCodec::Hevc=>matches!(crate::hevc::nal_type(nal),Some(19..=21))});
        if !self.config_sent || (self.waiting_idr && !is_idr) {
            self.sync_skips += 1;
            if self.last_sync_request.map(|t| t.elapsed() >= Duration::from_secs(1)).unwrap_or(true) {
                self.last_sync_request = Some(tokio::time::Instant::now());
                crate::emit_force_keyframe();
                crate::emit_log(&format!("VIDEO: awaiting config/IDR config={} idr={is_idr}; requested sync frame", self.config_sent));
            }
            return Ok(());
        }
        let resuming = self.waiting_idr;
        if self.waiting_idr {
            crate::emit_log(&format!("VIDEO: first IDR ready pts={pts_us}us clock={}us", crate::timing::monotonic_nanos() / 1000));
            self.waiting_idr = false;
        }

        // 打包「4 字节 BE 长度前缀 NAL 序列」（SPS/PPS 已经走 VideoConfig，不进帧体）
        let mut plain = Vec::with_capacity(annexb.len());
        for &nal in &nals {
            let kind = self.source_nal_type(nal);
            if match self.codec {VideoCodec::H264=>matches!(kind,7|8),VideoCodec::Hevc=>matches!(kind,32..=34|39|40)} {
                continue;
            }
            plain.extend_from_slice(&(nal.len() as u32).to_be_bytes());
            plain.extend_from_slice(nal);
        }
        if plain.is_empty() {
            return Ok(());
        }

        let ntp = ntp_le_from_us(pts_us);
        // 头只能构造一次：ChaCha 的 AAD 必须是最终写出的那 128 字节
        let (header, body) = match &mut self.cipher {
            ScreenCipher::ChaCha(key) => {
                let mut header = build_header(OP_FRAME, plain.len() + TAG_SIZE, &ntp);
                if self.codec==VideoCodec::Hevc {header[5]=0x10;}
                let sealed = chacha_seal(&*key, &nonce64(self.frame_counter), &plain, &header);
                (header, sealed)
            }
            ScreenCipher::AesCtr(stream) => {
                // 无 tag、长度不变；密钥流状态跨帧连续（apply_keystream 推进内部计数器）
                let mut header = build_header(OP_FRAME, plain.len(), &ntp);
                if self.codec==VideoCodec::Hevc {header[5]=0x10;}
                let mut encrypted = plain.clone();
                stream.apply_keystream(&mut encrypted);
                (header, encrypted)
            }
        };
        self.frame_counter = self.frame_counter.wrapping_add(1);

        let mut wire = Vec::with_capacity(HEADER_LEN + body.len());
        wire.extend_from_slice(&header);
        wire.extend_from_slice(&body);
        self.w.write_all(&wire).await?;

        self.frames = self.frames.wrapping_add(1);
        self.wire_bytes = self.wire_bytes.wrapping_add(wire.len() as u64);
        self.last_write = Some(tokio::time::Instant::now());
        if is_idr {
            self.written_idrs += 1;
            if self.written_idrs == 1 || resuming {
                crate::emit_log(&format!("VIDEO: IDR TCP written pts={pts_us}us frame={} wire={}B origin={}; receiver decode/display unconfirmed", self.frames, wire.len(), origin.as_str()));
            }
        }
        self.bytes = self.bytes.wrapping_add(plain.len() as u64);
        self.frames_since_keep_alive = self.frames_since_keep_alive.wrapping_add(1);
        self.bytes_since_keep_alive = self.bytes_since_keep_alive.wrapping_add(plain.len() as u64);
        Ok(())
    }

    /// 屏幕流 keepAlive（内部按 1s 节流，调用方在推流循环里逐帧调用即可）。
    ///
    /// 明文帧、不消耗 ChaCha 帧计数。与 UDP keepAlive / RTSP feedback 是三条相互独立的
    /// 存活信号；缺失时车机会话 ~10s 被拆（见模块文档）。stats 模式（车机 /info 声明
    /// keepAliveSendStatsAsBody）发 opcode=5，body 长度同时写进头部 params[14] 的后 4 字节
    /// （偏移 124，f32 LE），与 catplay 逐字节一致。
    pub async fn maybe_send_keep_alive(&mut self) -> std::io::Result<()> {
        self.observe_owner();
        if self.last_keep_alive.elapsed() < Duration::from_millis(KEEPALIVE_INTERVAL_MS) {
            return Ok(());
        }
        let window = self.last_keep_alive.elapsed().as_secs_f64().max(0.001);
        // 先把本窗口统计量取出来（下面会清零）
        let frames_in_window = self.frames_since_keep_alive;
        let bytes_in_window = self.bytes_since_keep_alive;
        self.last_keep_alive = tokio::time::Instant::now();
        self.frames_since_keep_alive = 0;
        self.bytes_since_keep_alive = 0;
        self.keep_alive_count = self.keep_alive_count.wrapping_add(1);

        let (opcode, body): (u8, Vec<u8>) = if self.keep_alive_with_stats {
            // 字段名对照 cardeck VideoSender.buildKeepAliveFrame 的 bplist 字典；
            // 真车会解析这个 body —— 发全零占位会让严格接收端解不出字典。
            let bytes_per_second = bytes_in_window as f64 / window;
            let fps = (frames_in_window as f64 / window).round() as i64;
            let stats = crate::bplist::encode(&crate::bplist::Value::dict(vec![
                ("txUsageAvg", crate::bplist::Value::real(bytes_per_second)),
                ("encoderCurrentFPS", crate::bplist::Value::int(fps)),
                ("sentFramesAvg", crate::bplist::Value::int(frames_in_window as i64)),
                ("queuedFramesAvg", crate::bplist::Value::int(0)),
                ("lossAvg", crate::bplist::Value::real(0.0)),
            ]));
            (OP_KEEP_ALIVE_WITH_BODY, stats)
        } else {
            (OP_KEEP_ALIVE, Vec::new())
        };

        let mut header = build_header(opcode, body.len(), &ZERO_TS);
        if !body.is_empty() {
            // params[14]（偏移 8+14*8=120）：前 4 字节 0.0f，后 4 字节 body 长度 f32 LE
            header[120..124].copy_from_slice(&0.0f32.to_le_bytes());
            header[124..128].copy_from_slice(&(body.len() as f32).to_le_bytes());
        }
        let mut wire = Vec::with_capacity(HEADER_LEN + body.len());
        wire.extend_from_slice(&header);
        wire.extend_from_slice(&body);
        self.w.write_all(&wire).await?;
        if self.keep_alive_count == 1 || self.keep_alive_count % 10 == 0 {
            crate::emit_log(&format!("VIDEO: keepAlive written opcode={opcode} body={}B count={} window_fps={:.1} window_payload_Bps={:.0} {}", body.len(), self.keep_alive_count,
                frames_in_window as f64 / window, bytes_in_window as f64 / window, self.diagnostics()));
        }
        Ok(())
    }

    /// 已发送帧总数（对外统计，如英雄卡实时帧率取差值）。
    pub fn frames_sent(&self) -> u64 {
        self.frames
    }

    /// 累计发送字节数（契约约定的简化口径；真实速率统计后续再按 keepAlive 窗口换算）。
    pub fn bytes_per_second(&self) -> u64 {
        self.bytes
    }
}

impl Drop for ScreenSender {
    fn drop(&mut self) {
        crate::emit_log(&format!("VIDEO: sender released {}", self.diagnostics()));
        // Must stop descriptor sampling BEFORE Rust drops the write half and closes its fd.
        self.publish_format(None, None);
        self.tcp_monitor.take();
    }
}

/** 128 字节消息头：u32LE body 长度、opcode、NTP 32.32 LE 时间戳（params[0]），其余为零。 */
fn build_header(opcode: u8, body_len: usize, ntp_le: &[u8; 8]) -> [u8; HEADER_LEN] {
    let mut header = [0u8; HEADER_LEN];
    header[0..4].copy_from_slice(&(body_len as u32).to_le_bytes());
    header[4] = opcode;
    header[8..16].copy_from_slice(ntp_le);
    header
}

/// pts（微秒）→ NTP 32.32，按 LE 存进头部 params[0]。
///
/// 参考实现 VideoSender.buildHeader 把 NTP 64 位按小端逐字节写进 [8..16)；
/// `ntp_be` 出的是大端序，反转即得 LE 编码。
fn ntp_le_from_us(pts_us: u64) -> [u8; 8] {
    let mut be = ntp_be(pts_us.saturating_mul(1_000));
    be.reverse();
    be
}

/// 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 载荷列表（不含起始码）。
/// 无起始码时：非空输入整体视为一个 NAL（对齐参考实现 splitAnnexB）。
pub fn annexb_nals(data: &[u8]) -> Vec<&[u8]> {
    let mut starts: Vec<usize> = Vec::new();
    let mut index = 0;
    while index + 3 < data.len() {
        if data[index] == 0 && data[index + 1] == 0 {
            if data[index + 2] == 1 {
                starts.push(index);
                index += 3;
                continue;
            }
            if index + 3 < data.len() && data[index + 2] == 0 && data[index + 3] == 1 {
                starts.push(index);
                index += 4;
                continue;
            }
        }
        index += 1;
    }
    if starts.is_empty() {
        if !data.is_empty() {
            return vec![data];
        }
        return Vec::new();
    }
    let mut units = Vec::with_capacity(starts.len());
    for (unit, &start) in starts.iter().enumerate() {
        let prefix = if data[start + 2] == 1 { 3 } else { 4 };
        let body_start = start + prefix;
        let body_end = if unit + 1 < starts.len() {
            starts[unit + 1]
        } else {
            data.len()
        };
        if body_end > body_start {
            units.push(&data[body_start..body_end]);
        }
    }
    units
}

/// H.264 NAL 类型（首字节低 5 位）；空切片返回 0。
pub fn nal_type(nal: &[u8]) -> u8 {
    if nal.is_empty() {
        0
    } else {
        nal[0] & 0x1F
    }
}

/// 组 avcC 记录（语义同 VideoSender 的 avcC 组装）：
/// `1, profile, compat, level, 0xFF, 0xE1, spsLenBE, sps, 1, ppsLenBE, pps`。
/// SPS 不足 4 字节或 PPS 为空时返回空 Vec（调用方跳过 config）。
pub fn build_avcc(sps: &[u8], pps: &[u8]) -> Vec<u8> {
    if sps.len() < 4 || pps.is_empty() {
        return Vec::new();
    }
    let mut avcc = Vec::with_capacity(11 + sps.len() + pps.len());
    avcc.push(1);
    avcc.push(sps[1]); // profile
    avcc.push(sps[2]); // compat
    avcc.push(sps[3]); // level
    avcc.push(0xFF); // 6 位保留 + 2 位长度尺寸-1（4 字节）
    avcc.push(0xE1); // 3 位保留 + 5 位 SPS 数
    avcc.extend_from_slice(&(sps.len() as u16).to_be_bytes());
    avcc.extend_from_slice(sps);
    avcc.push(1);
    avcc.extend_from_slice(&(pps.len() as u16).to_be_bytes());
    avcc.extend_from_slice(pps);
    avcc
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;

    #[tokio::test]
    async fn real_encoder_hevc_fixture_has_cropped_hvc1_and_authenticated_idr() {
        let csd=include_bytes!("../tests/fixtures/hevc-phone/csd0.annexb");
        let frame=include_bytes!("../tests/fixtures/hevc-phone/keyframe.annexb");
        let parameters=annexb_nals(csd);
        let get=|kind|parameters.iter().copied().find(|n|crate::hevc::nal_type(n)==Some(kind)).unwrap();
        let (expected_config,size)=crate::hevc::build_config(get(32),get(33),get(34)).unwrap();
        assert_eq!(size,(2732,794));
        assert_eq!(&expected_config[32..36],&[0x0a,0xac,0x03,0x1a]);
        assert_eq!(&expected_config[90..94],b"hvcC");
        assert_eq!(expected_config[95]&31,1); // Main profile from actual encoder SPS.
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();let (_,w)=client.into_split();
        let mut sender=ScreenSender::new(w,ScreenCipher::ChaCha([0x23;32]),size.0,size.1,false);
        sender.set_hevc_allowed(true);sender.set_video_codec(VideoCodec::Hevc).unwrap();
        sender.send_video_annexb(csd,10_000).await.unwrap();
        let mut header=[0;128];peer.read_exact(&mut header).await.unwrap();
        assert_eq!(&header[4..8],&[1,0,0x1e,1]);
        assert_eq!(f32::from_le_bytes(header[16..20].try_into().unwrap()),2732.0);
        assert_eq!(f32::from_le_bytes(header[20..24].try_into().unwrap()),794.0);
        let mut config=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
        peer.read_exact(&mut config).await.unwrap();assert_eq!(config,expected_config);
        assert_eq!(sender.frames_sent(),0);assert_eq!(sender.frame_counter,0);
        sender.send_video_annexb(frame,20_000).await.unwrap();
        peer.read_exact(&mut header).await.unwrap();assert_eq!(&header[4..8],&[0,0x10,0,0]);
        assert_eq!(&header[8..16],&ntp_le_from_us(20_000));
        let mut encrypted=vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];peer.read_exact(&mut encrypted).await.unwrap();
        let mut expected_frame=Vec::new();let mut has_idr=false;
        for nal in annexb_nals(frame) {
            let kind=crate::hevc::nal_type(nal).unwrap();has_idr|=kind==20;
            if !matches!(kind,32..=34|39|40) {expected_frame.extend_from_slice(&(nal.len() as u32).to_be_bytes());expected_frame.extend_from_slice(nal);}
        }
        assert!(has_idr);assert_eq!(crate::crypto::chacha_open(&[0x23;32],&nonce64(0),&encrypted,&header).unwrap(),expected_frame);
        assert_eq!(sender.frames_sent(),1);assert_eq!(sender.frame_counter,1);
    }

    #[tokio::test]
    async fn hevc_requires_agreement_and_preserves_cipher_counter_through_codec_change() {
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client=tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=listener.accept().await.unwrap();let (_,w)=client.into_split();
        let mut sender=ScreenSender::new(w,ScreenCipher::ChaCha([7;32]),1920,1080,false);
        assert_eq!(sender.set_video_codec(VideoCodec::Hevc).unwrap_err().kind(),std::io::ErrorKind::Unsupported);
        sender.frame_counter=9;sender.set_hevc_allowed(true);sender.set_video_codec(VideoCodec::Hevc).unwrap();
        let pack=|nals:Vec<Vec<u8>>|nals.into_iter().flat_map(|n|[vec![0,0,0,1],n].concat()).collect::<Vec<_>>();
        let sps=crate::hevc::tests::fixture_sps();
        let config=pack(vec![vec![0x40,1,12],sps,vec![0x44,1,0xc0]]);
        sender.send_video_annexb(&config,1000).await.unwrap();
        let mut h=[0;128];peer.read_exact(&mut h).await.unwrap();assert_eq!(&h[4..8],&[1,0,0x1e,1]);
        let mut body=vec![0;u32::from_le_bytes(h[..4].try_into().unwrap()) as usize];peer.read_exact(&mut body).await.unwrap();assert_eq!(&body[4..8],b"hvc1");assert_eq!(sender.frame_counter,9);
        sender.send_video_annexb(&pack(vec![vec![2,1,0x80]]),2000).await.unwrap();assert_eq!(sender.frames_sent(),0);
        sender.send_video_annexb(&pack(vec![vec![0x26,1,0x80]]),3000).await.unwrap();
        peer.read_exact(&mut h).await.unwrap();assert_eq!(h[5],0x10);
        let mut encrypted=vec![0;u32::from_le_bytes(h[..4].try_into().unwrap()) as usize];peer.read_exact(&mut encrypted).await.unwrap();
        assert_eq!(crate::crypto::chacha_open(&[7;32],&nonce64(9),&encrypted,&h).unwrap(),vec![0,0,0,3,0x26,1,0x80]);
        sender.send_video_annexb(&pack(vec![vec![0x44,1,0xc1],vec![2,1,0x80]]),4000).await.unwrap();
        assert_eq!(sender.frames_sent(),1);assert!(sender.waiting_idr);assert_eq!(sender.frame_counter,10);
        sender.set_video_codec(VideoCodec::H264).unwrap();assert_eq!(sender.frame_counter,10);assert!(!sender.config_sent);
    }

    #[tokio::test]
    async fn old_stretch_preference_never_overrides_encoded_size_even_after_source_reset() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (_, write) = client.into_split();
        let mut sender = ScreenSender::new(write, ScreenCipher::ChaCha([7;32]), 2560, 744, true);
        let avcc = build_avcc(&[0x67,0x42,0,0x1f], &[0x68,1]);
        // Stale enabled preferences must remain safe across source size changes/new rounds.
        for (step, expected) in [(0,(2560,744)), (1,(2560,744)), (2,(1920,720)), (3,(1920,720))] {
            match step {
                1 => sender.set_display_target(2736,800),
                2 => { sender.set_size(1920,720); sender.reset_source(); },
                3 => sender.set_display_target(0,800),
                _ => {},
            }
            assert_eq!(sender.size(), if step < 2 {(2560,744)} else {(1920,720)});
            sender.send_config(&avcc).await.unwrap();
            let mut header = [0u8;HEADER_LEN];
            peer.read_exact(&mut header).await.unwrap();
            assert_eq!(f32::from_le_bytes(header[16..20].try_into().unwrap()), expected.0 as f32);
            assert_eq!(f32::from_le_bytes(header[20..24].try_into().unwrap()), expected.1 as f32);
            let mut body = vec![0;u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize];
            peer.read_exact(&mut body).await.unwrap();
            assert_eq!(body,avcc); // SPS/PPS and encoded resolution are untouched.
        }
        // Even stale source metadata cannot override a parseable cropped SPS.
        let sps = [0x67,0x42,0xc0,0x28,0xda,0x01,0xe0,0x08,0x9f,0x97,0x01,0x01,0x01,0x02];
        let avcc = build_avcc(&sps, &[0x68,1]);
        sender.set_display_target(2736,800);
        sender.send_config(&avcc).await.unwrap();
        let mut header = [0u8;HEADER_LEN];
        peer.read_exact(&mut header).await.unwrap();
        assert_eq!(f32::from_le_bytes(header[16..20].try_into().unwrap()), 1920.0);
        assert_eq!(f32::from_le_bytes(header[20..24].try_into().unwrap()), 1080.0);
        let mut body = vec![0;avcc.len()];
        peer.read_exact(&mut body).await.unwrap();
        assert_eq!(body, avcc);
    }

    #[tokio::test]
    async fn fresh_stream_waits_for_config_and_idr_then_encrypts_with_actual_header() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (_r, write) = client.into_split();
        let mut sender = ScreenSender::new(write, ScreenCipher::ChaCha([7; 32]), 1920, 560, true);
        sender.send_video_annexb(&[0, 0, 0, 1, 0x41, 9], 1000).await.unwrap();
        assert_eq!(sender.frames_sent(), 0);
        let access_unit = [0,0,0,1,0x67,0x42,0,0x1f, 0,0,0,1,0x68,1, 0,0,0,1,0x65,2];
        sender.send_video_annexb(&access_unit, 2000).await.unwrap();
        let mut header = [0u8; 128];
        peer.read_exact(&mut header).await.unwrap();
        assert_eq!(header[4], OP_CONFIG);
        assert_eq!(header[6], SCREEN_FLAG_ENCRYPTED);
        assert_eq!(f32::from_le_bytes(header[20..24].try_into().unwrap()), 560.0);
        let mut config = vec![0; u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize];
        peer.read_exact(&mut config).await.unwrap();
        assert_eq!(config, build_avcc(&[0x67,0x42,0,0x1f], &[0x68,1]));
        peer.read_exact(&mut header).await.unwrap();
        let mut cipher = vec![0; u32::from_le_bytes(header[0..4].try_into().unwrap()) as usize];
        peer.read_exact(&mut cipher).await.unwrap();
        let plain = crate::crypto::chacha_open(&[7;32], &nonce64(0), &cipher, &header).unwrap();
        assert_eq!(plain, vec![0,0,0,2,0x65,2]);
        assert_eq!(sender.frames_sent(), 1);
        // Borrowing the screen pauses video, not its independent keepAlive.
        let owned = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        sender.set_ownership(owned.clone());
        sender.send_video_annexb(&access_unit, 3000).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
        owned.store(true, std::sync::atomic::Ordering::Release);
        sender.send_video_annexb(&[0,0,0,1,0x41,9], 4000).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
        sender.send_video_annexb(&access_unit, 5000).await.unwrap();
        assert_eq!(sender.frames_sent(), 2);
        assert_eq!(sender.source_frames, 5);
        assert_eq!(sender.ownership_skips, 1);
        assert_eq!(sender.sync_skips, 2);
        assert_eq!(sender.written_idrs, 2);
        assert_eq!(sender.wire_bytes, 2 * (128 + 6 + 16));
    }

    #[tokio::test]
    async fn stats_keepalive_remains_plaintext_before_any_video() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (_, write) = client.into_split();
        let mut s = ScreenSender::new(write, ScreenCipher::ChaCha([7;32]), 1920, 560, true);
        s.last_keep_alive = tokio::time::Instant::now() - Duration::from_secs(2);
        s.maybe_send_keep_alive().await.unwrap();
        let mut header = [0;128]; peer.read_exact(&mut header).await.unwrap();
        assert_eq!(header[4], OP_KEEP_ALIVE_WITH_BODY);
        let n = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
        assert_eq!(f32::from_le_bytes(header[124..].try_into().unwrap()), n as f32);
        let mut body = vec![0;n]; peer.read_exact(&mut body).await.unwrap();
        assert!(crate::bplist::parse(&body).unwrap().get("encoderCurrentFPS").is_some());
        assert_eq!(s.frame_counter, 0);
    }
}


