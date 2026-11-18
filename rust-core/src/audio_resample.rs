//! Streaming input conversion to the negotiated stereo mix clock.
use std::collections::VecDeque;

#[derive(Default)]
pub(crate) struct PcmToStereo {
    format: Option<(u32, u8, u32)>,
    bytes: Vec<u8>,
    // Position of the next output sample, in units of 1/output_rate of a source frame.
    phase: u64,
}

impl PcmToStereo {
    pub fn clear(&mut self) {
        self.format = None;
        self.bytes.clear();
        self.phase = 0;
    }

    pub fn push(&mut self, raw: &[u8], rate: u32, channels: u8, output_rate: u32, out: &mut VecDeque<i16>) {
        if self.format != Some((rate, channels, output_rate)) {
            self.clear();
            self.format = Some((rate, channels, output_rate));
        }
        if output_rate == 0 || rate == 0 || !(1..=2).contains(&channels) { return; }
        self.bytes.extend_from_slice(raw);
        let stride = channels as usize * 2;
        let frames = self.bytes.len() / stride;
        if rate == output_rate {
            for frame in self.bytes.chunks_exact(stride) {
                out.push_back(i16::from_le_bytes([frame[0], frame[1]]));
                out.push_back(i16::from_le_bytes([frame[stride - 2], frame[stride - 1]]));
            }
            self.bytes.drain(..frames * stride);
            return;
        }
        let clock = u64::from(output_rate);
        loop {
            let index = (self.phase / clock) as usize;
            let fraction = (self.phase % clock) as i64;
            let next = index + usize::from(fraction != 0);
            if next >= frames { break; } // Wait for the next packet's interpolation endpoint.
            for lane in [0, stride - 2] {
                let read = |frame| {
                    let offset: usize = frame * stride + lane;
                    i16::from_le_bytes([self.bytes[offset], self.bytes[offset + 1]]) as i64
                };
                let weighted = read(index) * (clock as i64 - fraction) + read(next) * fraction;
                out.push_back((weighted / clock as i64) as i16);
            }
            self.phase += u64::from(rate);
        }
        let consumed = frames.min((self.phase / clock) as usize);
        self.bytes.drain(..consumed * stride);
        self.phase -= consumed as u64 * clock;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn partial_source_frames_do_not_cross_formats_or_restarts() {
        let mut converter = PcmToStereo::default();
        let mut out = VecDeque::new();
        converter.push(&[99], 44_100, 2, 48000, &mut out);
        converter.push(&[10, 0, 20], 48_000, 2, 48000, &mut out);
        assert!(out.is_empty());
        converter.push(&[0], 48_000, 2, 48000, &mut out);
        assert_eq!(out, [10, 20]);
        converter.push(&[99], 48_000, 1, 48000, &mut out);
        converter.clear();
        converter.push(&[30, 0], 48_000, 1, 48000, &mut out);
        assert_eq!(out, [10, 20, 30, 30]);
    }

    #[test]
    fn stereo_conversion_is_chunk_invariant_at_both_mix_rates() {
        let frames: Vec<_> = (0..48000).flat_map(|i| [(i%30000) as i16,-((i%30000) as i16)]).collect();
        let bytes: Vec<_> = frames.iter().flat_map(|n| n.to_le_bytes()).collect();
        for (source, target, expected_frames) in [(48000,44100,44100),(44100,44100,48000),(48000,48000,48000)] {
            for chunk in [1,703,4096,bytes.len()] {
                let mut converter = PcmToStereo::default();
                let mut result = VecDeque::new();
                for part in bytes.chunks(chunk) { converter.push(part,source,2,target,&mut result); }
                assert_eq!(result.len(),expected_frames*2);
                if source==target { assert_eq!(result.make_contiguous(),frames.as_slice()); }
                else {
                    for (i,pair) in result.make_contiguous().chunks_exact(2).enumerate() {
                        let pos=i*48000;
                        let a=frames[2*(pos/44100)] as i64;
                        let b=frames[2*(pos/44100+1)] as i64;
                        let frac=(pos%44100) as i64;
                        let sample=((a*(44100-frac)+b*frac)/44100) as i16;
                        assert_eq!(pair,[sample,-sample]);
                    }
                }
            }
        }
    }
}
