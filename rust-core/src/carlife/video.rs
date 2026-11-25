//! CarWith packet boundaries are not H.264 access-unit boundaries. Keep the
//! trailing NAL until its successor arrives; cache parameter sets per source round.
//! Flow reference: mcarplay a3a4117 CarLifeVideoParser / CarLifeJoinGate.
const LIMIT: usize = 4 * 1024 * 1024;
const PREFIX: &[u8] = &[0, 0, 0, 1];
use crate::video_format::VideoCodec;

pub(super) struct AccessUnit {
    pub data: Vec<u8>,
    pub codec: VideoCodec,
}

/// A picture that can start the screen sender after a source reset.
pub(super) fn is_configured_sync(codec: VideoCodec, data: &[u8]) -> bool {
    let nals = crate::screen::annexb_nals(data);
    match codec {
        VideoCodec::H264 => {
            let find = |kind| nals.iter().copied().find(|nal| nal[0] & 31 == kind);
            let (Some(sps), Some(pps), Some(_)) = (find(7), find(8), find(5)) else { return false; };
            sps.len() >= 4 && sps.len() <= u16::MAX as usize &&
                pps.len() >= 2 && pps.len() <= u16::MAX as usize
        }
        VideoCodec::Hevc => {
            let find = |kind| nals.iter().copied().find(|nal| crate::hevc::nal_type(nal) == Some(kind));
            let (Some(vps), Some(sps), Some(pps)) = (find(32), find(33), find(34)) else { return false; };
            nals.iter().any(|nal| matches!(crate::hevc::nal_type(nal), Some(19..=21))) &&
                crate::hevc::build_config(vps, sps, pps).is_some()
        }
    }
}

#[derive(Default)]
pub(super) struct VideoParser {
    buffer: Vec<u8>,
    nals: Vec<Vec<u8>>,
    bytes: usize,
    vcl: bool,
    idr: bool,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    hevc: bool,
    vps: Option<Vec<u8>>,
    probe_vps: Option<Vec<u8>>,
    probe_sps: Option<Vec<u8>>,
    probe_avc_sps: Option<Vec<u8>>,
}

impl VideoParser {
    #[cfg(test)]
    pub(super) fn probe_parameters(&self) -> (bool, bool, bool) {
        (self.vps.is_some(), self.sps.is_some(), self.pps.is_some())
    }
    pub fn push(&mut self, payload: &[u8]) -> Result<Vec<Vec<u8>>, &'static str> {
        self.push_tagged(payload).map(|units| units.into_iter().map(|unit| unit.data).collect())
    }

    pub fn push_tagged(&mut self, payload: &[u8]) -> Result<Vec<AccessUnit>, &'static str> {
        if self.buffer.len().saturating_add(payload.len()).saturating_add(self.bytes) > LIMIT {
            *self = Self::default();
            return Err("CarWith video access unit exceeds limit");
        }
        self.buffer.extend_from_slice(payload);
        let starts = start_codes(&self.buffer);
        let mut output = Vec::new();
        for pair in starts.windows(2) {
            let nal = self.buffer[pair[0].0 + pair[0].1..pair[1].0].to_vec();
            self.consume(nal, &mut output);
            let cached = [&self.vps,&self.sps,&self.pps,&self.probe_vps,&self.probe_sps,&self.probe_avc_sps]
                .iter().filter_map(|value| value.as_ref()).map(|nal| nal.len()+PREFIX.len()).sum::<usize>();
            if self.bytes.saturating_add(cached) > LIMIT || output.iter().any(|unit| unit.data.len()>LIMIT) {
                *self=Self::default(); return Err("CarWith video access unit exceeds limit");
            }
        }
        if let Some(&(offset, prefix)) = starts.last() {
            let trailing = &self.buffer[offset + prefix..];
            // Its header already proves a new picture, even if its body is split
            // across the next CarLife packet. Flush only the preceding picture.
            if self.boundary(trailing) { self.flush(&mut output); }
            self.buffer.drain(..offset);
        }
        Ok(output)
    }

    fn boundary(&self, nal: &[u8]) -> bool {
        if self.hevc {
            return crate::hevc::nal_type(nal).is_some_and(|kind| kind == 35 || self.vcl &&
                (matches!(kind,32..=34 | 39) || kind <= 31 && nal.get(2).is_some_and(|b| b & 0x80 != 0)));
        }
        let Some(header) = nal.first() else { return false; };
        let kind = header & 31;
        kind == 9 || self.vcl && (matches!(kind, 6 | 7 | 8) ||
            matches!(kind, 1 | 5) && first_mb(nal) == Some(0))
    }

    fn consume(&mut self, nal: Vec<u8>, output: &mut Vec<AccessUnit>) {
        if nal.is_empty() { return; }
        if self.hevc {
            // A producer may fall back without reconnecting its CarLife socket.
            // A validated AVC SPS followed by PPS is a source change, never a
            // reason to relabel ambiguous slice headers or retain a partial AU.
            match nal[0]&0x9f {
                7 if crate::h264::avcc_dimensions(&crate::screen::build_avcc(&nal,&[0x68,0x80])).is_some() => {
                    self.probe_avc_sps=Some(nal.clone());
                }
                8 if nal.len()>=2 && first_mb(&nal).is_some() => {
                    if let Some(sps)=self.probe_avc_sps.take() {
                        self.hevc=false; self.vps=None; self.sps=Some(sps.clone()); self.pps=None;
                        self.nals=vec![sps]; self.bytes=self.nals[0].len()+PREFIX.len();
                        self.vcl=false; self.idr=false;
                        self.probe_vps=None; self.probe_sps=None;
                    }
                }
                _ => self.probe_avc_sps=None,
            }
        }
        // Do not infer HEVC from slice bytes: valid H.264 NALs can share them.
        // Only an ordered, validated source configuration can change the codec.
        if !self.hevc {
            let kind = crate::hevc::nal_type(&nal).filter(|_| nal[0]&1 == 0 && nal[1]&0xf8 == 0);
            match kind {
                Some(32) => { self.probe_vps = Some(nal.clone()); self.probe_sps = None; }
                Some(33) if self.probe_vps.is_some() => { self.probe_sps = Some(nal.clone()); }
                Some(34) => {
                    if let (Some(vps),Some(sps)) = (&self.probe_vps,&self.probe_sps) {
                        if crate::hevc::build_config(vps,sps,&nal).is_some() {
                            let (vps,sps) = (vps.clone(),sps.clone());
                            self.flush(output);
                            self.hevc = true; self.vps = Some(vps.clone()); self.sps = Some(sps.clone());
                            self.pps = Some(nal.clone()); self.nals = vec![vps,sps];
                            self.bytes = self.nals.iter().map(|n| n.len()+PREFIX.len()).sum();
                            self.probe_vps = None; self.probe_sps = None;
                        }
                    }
                }
                _ => { self.probe_vps = None; self.probe_sps = None; }
            }
        }
        if self.boundary(&nal) { self.flush(output); }
        if self.hevc {
            let Some(kind) = crate::hevc::nal_type(&nal) else { return; };
            match kind {
                32 => {
                    if self.vps.as_deref() != Some(nal.as_slice()) {
                        self.sps=None; self.pps=None;
                        self.nals.retain(|n| !matches!(crate::hevc::nal_type(n),Some(32..=34)));
                    }
                    self.vps=Some(nal.clone());
                }
                33 => {
                    if self.sps.as_deref() != Some(nal.as_slice()) {
                        self.pps=None;
                        self.nals.retain(|n| !matches!(crate::hevc::nal_type(n),Some(33 | 34)));
                    }
                    self.sps=Some(nal.clone());
                }
                34 => { self.nals.retain(|n| crate::hevc::nal_type(n)!=Some(34)); self.pps=Some(nal.clone()); }
                0..=31 => { self.vcl=true; self.idr |= matches!(kind,19..=21); }
                _ => {}
            }
            self.bytes = self.nals.iter().map(|n|n.len()+PREFIX.len()).sum::<usize>() + nal.len()+PREFIX.len();
            self.nals.push(nal); return;
        }
        match nal[0] & 31 {
            7 => {
                // A changed SPS must not be paired with the preceding format's PPS.
                if self.sps.as_deref().map(|old| old != nal.as_slice()).unwrap_or(false) {
                    self.pps = None;
                    self.nals.retain(|n| !matches!(n[0] & 31, 7 | 8));
                    self.bytes = self.nals.iter().map(|n| n.len() + PREFIX.len()).sum();
                }
                self.sps = Some(nal.clone());
            }
            8 => {
                self.nals.retain(|n| n[0] & 31 != 8);
                self.bytes = self.nals.iter().map(|n| n.len() + PREFIX.len()).sum();
                self.pps = Some(nal.clone());
            }
            1 => self.vcl = true,
            5 => { self.vcl = true; self.idr = true; }
            _ => {}
        }
        self.bytes += nal.len() + PREFIX.len();
        self.nals.push(nal);
    }

    fn flush(&mut self, output: &mut Vec<AccessUnit>) {
        if self.vcl {
            let mut data = Vec::with_capacity(self.bytes + 256);
            if self.idr {
                // Reinsert the current pair on every IDR. Early IDRs may have
                // been discarded while AirPlay lacked a sender/screen ownership.
                let parameters = if self.hevc { vec![(32,&self.vps),(33,&self.sps),(34,&self.pps)] }
                    else { vec![(7,&self.sps),(8,&self.pps)] };
                for (kind, cached) in parameters {
                    if !self.nals.iter().any(|n| if self.hevc { crate::hevc::nal_type(n)==Some(kind) } else { n[0]&31==kind }) {
                        if let Some(nal) = cached { data.extend_from_slice(PREFIX); data.extend_from_slice(nal); }
                    }
                }
            }
            for nal in &self.nals { data.extend_from_slice(PREFIX); data.extend_from_slice(nal); }
            output.push(AccessUnit { data, codec: if self.hevc { VideoCodec::Hevc } else { VideoCodec::H264 } });
        }
        self.nals.clear(); self.bytes = 0; self.vcl = false; self.idr = false;
    }
}

fn start_codes(data: &[u8]) -> Vec<(usize, usize)> {
    let mut result = Vec::new(); let mut i = 0;
    while i + 3 <= data.len() {
        let n = if data[i..].starts_with(&[0, 0, 0, 1]) { 4 }
            else if data[i..].starts_with(&[0, 0, 1]) { 3 } else { i += 1; continue; };
        result.push((i, n)); i += n;
    }
    result
}

fn first_mb(nal: &[u8]) -> Option<u32> {
    let mut rbsp = Vec::new(); let mut zeros = 0;
    for &byte in nal.iter().skip(1).take(32) {
        if zeros >= 2 && byte == 3 { zeros = 0; continue; }
        rbsp.push(byte); zeros = if byte == 0 { zeros + 1 } else { 0 };
    }
    let mut bits = rbsp.iter().flat_map(|b| (0..8).rev().map(move |shift| (b >> shift) & 1));
    let mut zeros = 0;
    while bits.next()? == 0 { zeros += 1; if zeros >= 32 { return None; } }
    let mut code = 1u32;
    for _ in 0..zeros { code = (code << 1) | bits.next()? as u32; }
    Some(code - 1)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn nal(body: &[u8]) -> Vec<u8> { [PREFIX, body].concat() }
    fn config() -> Vec<u8> { [nal(&[0x67, 0x42, 0, 0x1f]), nal(&[0x68, 1])].concat() }
    fn types(data: &[u8]) -> Vec<u8> { crate::screen::annexb_nals(data).iter().map(|n| n[0] & 31).collect() }

    fn hevc_config() -> Vec<u8> {
        [nal(&[0x40,1,12]), nal(&crate::hevc::tests::fixture_sps()), nal(&[0x44,1,0xc0])].concat()
    }

    #[test]
    fn tagged_hevc_requires_valid_parameters_and_preserves_slices_and_recovery_config() {
        let mut parser = VideoParser::default();
        let bytes = [hevc_config(), nal(&[38,1,0x80]), nal(&[38,1,0x40]),
            nal(&[2,1,0x80]), nal(&[42,1,0x80]), nal(&[2,1,0x80])].concat();
        let mut out = Vec::new();
        for part in bytes.chunks(2) { out.extend(parser.push_tagged(part).unwrap()); }
        assert_eq!(out.len(), 3);
        assert!(out.iter().all(|au| au.codec == crate::video_format::VideoCodec::Hevc));
        let kinds = |data: &[u8]| crate::screen::annexb_nals(data).iter()
            .map(|n| crate::hevc::nal_type(n).unwrap()).collect::<Vec<_>>();
        assert_eq!(kinds(&out[0].data), vec![32,33,34,19,19]);
        assert_eq!(kinds(&out[2].data), vec![32,33,34,21]);
    }

    #[test]
    fn ambiguous_h264_headers_and_truncated_hevc_do_not_select_hevc() {
        for prefix in [config(), [nal(&[0x40,1,12]), nal(&[0x42,1,0x80]), nal(&[0x44,1,0xc0])].concat()] {
            let mut parser = VideoParser::default();
            let bytes = [prefix, nal(&[0x26,1,0x80]), nal(&[0x28,1,0x80]),
                nal(&[0x2a,1,0x80]), nal(&[0x73,0x80]), nal(&[0x74,0x80]),
                nal(&[0x75,0x80]), nal(&[0x65,0x80]), nal(&[0x41,0x80])].concat();
            let out = parser.push_tagged(&bytes).unwrap();
            assert!(out.iter().all(|au| au.codec == crate::video_format::VideoCodec::H264));
        }
    }

    #[test]
    fn hevc_round_and_overflow_reset_codec_and_all_parameter_sets() {
        let mut parser = VideoParser::default();
        let out = parser.push_tagged(&[hevc_config(), nal(&[38,1,0x80]), nal(&[2,1,0x80])].concat()).unwrap();
        assert_eq!(out[0].codec, VideoCodec::Hevc);
        assert!(parser.push_tagged(&vec![0;LIMIT+1]).is_err());
        assert!(!parser.hevc); assert!(parser.vps.is_none());
        assert!(parser.sps.is_none()); assert!(parser.pps.is_none());
        let out = parser.push_tagged(&[nal(&[0x65,0x80]),nal(&[0x41,0x80])].concat()).unwrap();
        assert_eq!(out[0].codec,VideoCodec::H264);
        assert_eq!(types(&out[0].data),vec![5]);
        let mut fresh=VideoParser::default();
        assert!(!fresh.hevc);
        assert!(fresh.push_tagged(&[nal(&[38,1,0x80]),nal(&[2,1,0x80])].concat()).unwrap().is_empty());
    }

    #[test]
    fn same_socket_hevc_to_h264_fallback_discards_old_format_and_partial_picture() {
        let mut parser=VideoParser::default();
        parser.push_tagged(&[hevc_config(),nal(&[38,1,0x80]),nal(&[2,1,0x80])].concat()).unwrap();
        let sps=vec![0x67,0x42,0xc0,0x28,0xda,0x01,0xe0,0x08,0x9f,0x97,0x01,0x01,0x01,0x02];
        let out=parser.push_tagged(&[nal(&sps),nal(&[0x68,0xce,0x06,0xe2]),
            nal(&[0x65,0x80]),nal(&[0x41,0x80])].concat()).unwrap();
        let avc=out.iter().find(|au|au.codec==VideoCodec::H264).expect("source fallback must be H264");
        assert_eq!(types(&avc.data),vec![7,8,5]);
        assert!(parser.vps.is_none());
    }

    #[test]
    fn packet_fragmentation_preserves_start_codes_and_parameter_sets() {
        let bytes = [config(), nal(&[0x65, 0x80, 7]), nal(&[0x41, 0x80, 8])].concat();
        let mut p = VideoParser::default(); let mut out = Vec::new();
        for chunk in bytes.chunks(2) { out.extend(p.push(chunk).unwrap()); }
        assert_eq!(out.len(), 1); assert_eq!(types(&out[0]), vec![7, 8, 5]);
        assert!(out[0].ends_with(&[0x65, 0x80, 7]));
    }

    #[test]
    fn later_idr_contains_cached_pair_after_early_output_was_dropped() {
        let mut p = VideoParser::default();
        p.push(&config()).unwrap();
        p.push(&nal(&[0x65, 0x80])).unwrap();
        let first = p.push(&nal(&[0x41, 0x80])).unwrap();
        assert_eq!(types(&first[0]), vec![7, 8, 5]); // discarded before ownership
        p.push(&nal(&[0x65, 0x80])).unwrap();
        let resumed = p.push(&nal(&[0x41, 0x80])).unwrap();
        assert_eq!(types(&resumed[0]), vec![7, 8, 5]);
    }

    #[test]
    fn multiple_slices_stay_in_one_picture_but_two_pictures_are_separate() {
        let mut p = VideoParser::default();
        let bytes = [config(), nal(&[0x65, 0x80]), nal(&[0x65, 0x40]), nal(&[0x41, 0x80]), nal(&[0x41, 0x80])].concat();
        let out = p.push(&bytes).unwrap();
        assert_eq!(out.len(), 2); assert_eq!(types(&out[0]), vec![7, 8, 5, 5]);
        assert_eq!(types(&out[1]), vec![1]);
    }

    #[test]
    fn new_round_does_not_inherit_old_parameters() {
        let mut old = VideoParser::default(); old.push(&config()).unwrap();
        let mut fresh = VideoParser::default();
        let out = fresh.push(&[nal(&[0x65, 0x80]), nal(&[0x41, 0x80])].concat()).unwrap();
        assert_eq!(types(&out[0]), vec![5]);
    }

    #[test]
    fn changed_sps_before_any_picture_does_not_replay_the_old_pps() {
        let mut p = VideoParser::default();
        let bytes = [config(), nal(&[0x67, 0x42, 0, 0x20]), nal(&[0x65, 0x80]), nal(&[0x41, 0x80])].concat();
        let out = p.push(&bytes).unwrap();
        assert_eq!(types(&out[0]), vec![7, 5]);
        assert!(p.pps.is_none());
        assert_eq!(crate::screen::annexb_nals(&out[0])[0], &[0x67, 0x42, 0, 0x20]);
    }

    #[tokio::test]
    async fn ownership_recovery_sends_configuration_before_idr_and_new_round_waits_again() {
        use crate::screen::{ScreenCipher, ScreenSender};
        use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
        use tokio::{net::{TcpListener, TcpStream}, io::AsyncReadExt};
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut phone, _) = listener.accept().await.unwrap();
        let (_, write) = client.into_split();
        let mut sender = ScreenSender::new(write, ScreenCipher::ChaCha([7; 32]), 1280, 720, true);
        let owned = Arc::new(AtomicBool::new(false)); sender.set_ownership(owned.clone());
        let mut parser = VideoParser::default();
        let early = parser.push(&[config(), nal(&[0x65, 0x80]), nal(&[0x41, 0x80])].concat()).unwrap();
        sender.send_video_annexb(&early[0], 1000).await.unwrap();
        assert_eq!(sender.frames_sent(), 0);
        owned.store(true, Ordering::Release);
        parser.push(&nal(&[0x65, 0x80])).unwrap();
        let resumed = parser.push(&nal(&[0x41, 0x80])).unwrap();
        sender.send_video_annexb(&resumed[0], 2000).await.unwrap();
        let mut header = [0; 128]; phone.read_exact(&mut header).await.unwrap();
        assert_eq!(header[4], 1);
        let len = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
        let mut body = vec![0; len]; phone.read_exact(&mut body).await.unwrap();
        phone.read_exact(&mut header).await.unwrap(); assert_eq!(header[4], 0);
        let len = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
        phone.read_exact(&mut vec![0; len]).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
        sender.reset_source();
        sender.send_video_annexb(&nal(&[0x65, 0x80]), 3000).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
    }

    #[test]
    fn oversized_input_fails_and_clears_cached_format() {
        let mut p = VideoParser::default(); p.push(&config()).unwrap();
        assert!(p.push(&vec![0; LIMIT + 1]).is_err());
        assert!(p.sps.is_none()); assert!(p.pps.is_none()); assert!(p.buffer.is_empty());
    }
}
