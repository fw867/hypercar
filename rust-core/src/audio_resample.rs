//! Stateful 48k -> 44.1k stereo converter. Fractional phase survives every pump tick.
#[derive(Default)]
pub(crate) struct Stereo48To441 { samples: Vec<i16>, phase: u64 }
impl Stereo48To441 {
    pub fn reset(&mut self) { self.samples.clear(); self.phase = 0; }
    pub fn convert(&mut self, input: &[i16]) -> Vec<i16> {
        self.samples.extend_from_slice(input);
        let frames = self.samples.len() / 2;
        if frames < 2 { return Vec::new(); }
        let mut out = Vec::with_capacity(input.len());
        while self.phase / 44_100 < (frames - 1) as u64 {
            let index = (self.phase / 44_100) as usize;
            let fraction = (self.phase % 44_100) as i64;
            for lane in 0..2 {
                let a = self.samples[index * 2 + lane] as i64;
                let b = self.samples[(index + 1) * 2 + lane] as i64;
                out.push(((a * (44_100 - fraction) + b * fraction) / 44_100) as i16);
            }
            self.phase += 48_000;
        }
        let consumed = ((self.phase / 44_100) as usize).min(frames - 1);
        self.samples.drain(..consumed * 2);
        self.phase -= consumed as u64 * 44_100;
        out
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn chunk_boundaries_do_not_change_samples_or_rate() {
        let input: Vec<i16> = (0..480_000).flat_map(|i| [(i % 30000) as i16, -((i % 30000) as i16)]).collect();
        let expected = Stereo48To441::default().convert(&input);
        assert_eq!(expected.len(), 441_000 * 2);
        for chunk in [1, 352, 704, 2048] {
            let mut converter = Stereo48To441::default();
            let actual: Vec<i16> = input.chunks(chunk).flat_map(|part| converter.convert(part)).collect();
            assert_eq!(expected, actual, "chunk={chunk}");
        }
        assert!(expected.chunks_exact(2).all(|p| p[0] == -p[1]));
    }
    #[test] fn silence_and_constant_signals_preserve_channels() {
        let input = [1200, -2400].repeat(48000);
        let output = Stereo48To441::default().convert(&input);
        assert_eq!(output, [1200,-2400].repeat(44100));
    }
    #[test] fn reset_does_not_replay_old_channel_or_phase() {
        let mut converter=Stereo48To441::default();
        converter.convert(&[1000,-1000,2000,-2000,3000]);
        converter.reset();
        assert_eq!(converter.convert(&[0;704]),Stereo48To441::default().convert(&[0;704]));
    }
}
