//! CarWith packet boundaries are not H.264 access-unit boundaries. Keep the
//! trailing NAL until its successor arrives; cache parameter sets per source round.
//! Flow reference: mcarplay a3a4117 CarLifeVideoParser / CarLifeJoinGate.
const LIMIT: usize = 4 * 1024 * 1024;
const PREFIX: &[u8] = &[0, 0, 0, 1];

#[derive(Default)]
pub(super) struct VideoParser {
    buffer: Vec<u8>,
    nals: Vec<Vec<u8>>,
    bytes: usize,
    vcl: bool,
    idr: bool,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
}

impl VideoParser {
    pub fn push(&mut self, payload: &[u8]) -> Result<Vec<Vec<u8>>, &'static str> {
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
        let Some(header) = nal.first() else { return false; };
        let kind = header & 31;
        kind == 9 || self.vcl && (matches!(kind, 6 | 7 | 8) ||
            matches!(kind, 1 | 5) && first_mb(nal) == Some(0))
    }

    fn consume(&mut self, nal: Vec<u8>, output: &mut Vec<Vec<u8>>) {
        if nal.is_empty() { return; }
        if self.boundary(&nal) { self.flush(output); }
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

    fn flush(&mut self, output: &mut Vec<Vec<u8>>) {
        if self.vcl {
            let mut data = Vec::with_capacity(self.bytes + 256);
            if self.idr {
                // Reinsert the current pair on every IDR. Early IDRs may have
                // been discarded while AirPlay lacked a sender/screen ownership.
                for (kind, cached) in [(7, &self.sps), (8, &self.pps)] {
                    if !self.nals.iter().any(|n| n[0] & 31 == kind) {
                        if let Some(nal) = cached { data.extend_from_slice(PREFIX); data.extend_from_slice(nal); }
                    }
                }
            }
            for nal in &self.nals { data.extend_from_slice(PREFIX); data.extend_from_slice(nal); }
            output.push(data);
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
