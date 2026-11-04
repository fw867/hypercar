//! Bounded SPS dimension reader. It never changes the encoded bitstream.

pub fn avcc_dimensions(avcc: &[u8]) -> Option<(u32, u32)> {
    if avcc.first() != Some(&1) || avcc.get(5)? & 31 == 0 { return None; }
    let len = u16::from_be_bytes(avcc.get(6..8)?.try_into().ok()?) as usize;
    sps_dimensions(avcc.get(8..8usize.checked_add(len)?)?)
}

fn sps_dimensions(nal: &[u8]) -> Option<(u32, u32)> {
    if nal.first()? & 31 != 7 || nal.len() > 65535 { return None; }
    let mut rbsp = Vec::with_capacity(nal.len());
    let mut zeros = 0;
    for &byte in &nal[1..] {
        if zeros >= 2 && byte == 3 { zeros = 0; continue; }
        rbsp.push(byte);
        zeros = if byte == 0 { zeros + 1 } else { 0 };
    }
    let mut b = Bits { data: &rbsp, pos: 0 };
    let profile = b.read(8)?;
    if !matches!(profile, 66 | 77 | 88 | 100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135) { return None; }
    b.read(16)?; // constraints, level
    if b.ue()? > 31 { return None; }
    let mut chroma = 1;
    let mut separate = false;
    if matches!(profile, 100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135) {
        chroma = b.ue()?;
        if chroma > 3 { return None; }
        if chroma == 3 { separate = b.read(1)? != 0; }
        if b.ue()? > 6 || b.ue()? > 6 { return None; }
        b.read(1)?;
        if b.read(1)? != 0 {
            for i in 0..if chroma == 3 { 12 } else { 8 } {
                if b.read(1)? != 0 {
                    let mut last = 8i64;
                    let mut next = 8i64;
                    for _ in 0..if i < 6 { 16 } else { 64 } {
                        if next != 0 { next = (last + b.se()? as i64).rem_euclid(256); }
                        if next != 0 { last = next; }
                    }
                }
            }
        }
    }
    if b.ue()? > 12 { return None; } // log2_max_frame_num_minus4
    match b.ue()? {
        0 => if b.ue()? > 12 { return None; },
        1 => {
            b.read(1)?; b.se()?; b.se()?;
            let n = b.ue()?;
            if n > 255 { return None; }
            for _ in 0..n { b.se()?; }
        }
        2 => {},
        _ => return None,
    }
    b.ue()?; b.read(1)?; // reference frames, gaps flag
    let width = b.ue()?.checked_add(1)?.checked_mul(16)?;
    let map_height = b.ue()?.checked_add(1)?;
    let frame_only = b.read(1)?;
    let factor = 2 - frame_only;
    let height = map_height.checked_mul(16)?.checked_mul(factor)?;
    if frame_only == 0 { b.read(1)?; }
    b.read(1)?; // direct_8x8_inference_flag
    let (mut w, mut h) = (width, height);
    if b.read(1)? != 0 {
        let left = b.ue()?; let right = b.ue()?;
        let top = b.ue()?; let bottom = b.ue()?;
        let array = if separate { 0 } else { chroma };
        let unit_x = if array == 1 || array == 2 { 2 } else { 1 };
        let unit_y = if array == 1 { 2 * factor } else { factor };
        w = w.checked_sub(left.checked_add(right)?.checked_mul(unit_x)?)?;
        h = h.checked_sub(top.checked_add(bottom)?.checked_mul(unit_y)?)?;
    }
    if w < 2 || h < 2 || w > 65536 || h > 65536 { return None; }
    Some((w, h))
}

struct Bits<'a> { data: &'a [u8], pos: usize }
impl Bits<'_> {
    fn read(&mut self, n: usize) -> Option<u32> {
        if n > 32 || self.pos.checked_add(n)? > self.data.len() * 8 { return None; }
        let mut value = 0;
        for _ in 0..n {
            value = (value << 1) | ((self.data[self.pos / 8] >> (7 - self.pos % 8)) & 1) as u32;
            self.pos += 1;
        }
        Some(value)
    }
    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0;
        while self.read(1)? == 0 { zeros += 1; if zeros > 31 { return None; } }
        Some(((1u64 << zeros) - 1 + self.read(zeros)? as u64) as u32)
    }
    fn se(&mut self) -> Option<i32> {
        let v = self.ue()? as i64;
        i32::try_from(if v & 1 == 0 { -(v / 2) } else { (v + 1) / 2 }).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // Baseline SPS: 1920x1080 (coded height 1088, bottom crop 8 pixels).
    const SPS: &[u8] = &[0x67, 0x42, 0xc0, 0x28, 0xda, 0x01, 0xe0, 0x08, 0x9f, 0x97, 0x01, 0x01, 0x01, 0x02];
    #[test]
    fn dimensions_and_truncation_are_bounded() {
        assert_eq!(sps_dimensions(SPS), Some((1920, 1080)));
        for len in 0..9 { assert_eq!(sps_dimensions(&SPS[..len]), None); }
        assert_eq!(sps_dimensions(&[0x67; 65536]), None);
        assert_eq!(avcc_dimensions(&[1,0,0,0,0,1,255,255]), None);
        assert_eq!(sps_dimensions(&[0x67,100,0,40,0,0,0,0,0]), None);
    }

    // Construct small SPS fixtures with known macroblock dimensions and cropping.
    struct Writer(Vec<u8>);
    impl Writer {
        fn bits(&mut self, value: u32, n: usize) {
            for bit in (0..n).rev() { self.0.push(((value >> bit) & 1) as u8); }
        }
        fn ue(&mut self, value: u32) {
            let code = value + 1;
            let n = (32 - code.leading_zeros()) as usize;
            self.bits(0, n - 1); self.bits(code, n);
        }
        fn finish(mut self) -> Vec<u8> {
            self.bits(1,1);
            while self.0.len() % 8 != 0 { self.bits(0,1); }
            let mut nal = vec![0x67];
            let mut zeros = 0;
            for bits in self.0.chunks(8) {
                let byte = bits.iter().fold(0, |v,b| (v << 1) | b);
                if zeros >= 2 && byte <= 3 { nal.push(3); zeros = 0; }
                nal.push(byte); zeros = if byte == 0 { zeros + 1 } else { 0 };
            }
            nal
        }
    }
    #[test]
    fn high_profile_scaling_lists_interlaced_and_crop_units() {
        for (profile,chroma,separate,interlaced,poc,width,height,bottom,expected) in [
            (66,1,false,false,0,2560,752,4,(2560,744)),
            (100,1,false,false,1,2736,800,0,(2736,800)),
            (100,1,false,true,2,1920,1088,2,(1920,1080)),
            (244,3,true,false,0,1920,1088,8,(1920,1080)),
            (100,0,false,false,0,1920,1088,8,(1920,1080)),
        ] {
            let mut w = Writer(Vec::new());
            w.bits(profile,8); w.bits(0,8); w.bits(40,8); w.ue(0);
            if profile != 66 {
                w.ue(chroma);
                if chroma == 3 { w.bits(separate as u32,1); }
                w.ue(0); w.ue(0); w.bits(0,1); w.bits(1,1);
                for i in 0..if chroma == 3 { 12 } else { 8 } {
                    w.bits((i == 0 || i == 6) as u32,1);
                    if i == 0 || i == 6 { for _ in 0..if i < 6 { 16 } else { 64 } { w.ue(0); } }
                }
            }
            w.ue(0); w.ue(poc);
            if poc == 0 { w.ue(0); }
            if poc == 1 { w.bits(0,1); w.ue(0); w.ue(0); w.ue(2); w.ue(0); w.ue(0); }
            w.ue(1); w.bits(0,1); w.ue(width / 16 - 1);
            w.ue(height / if interlaced {32} else {16} - 1);
            w.bits((!interlaced) as u32,1);
            if interlaced { w.bits(0,1); }
            w.bits(1,1); w.bits(1,1);
            w.ue(0); w.ue(0); w.ue(0); w.ue(bottom); w.bits(0,1);
            assert_eq!(sps_dimensions(&w.finish()), Some(expected));
        }
    }
}
