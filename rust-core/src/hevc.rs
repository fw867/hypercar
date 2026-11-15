//! HEVC configuration matching the BMW box's hvc1/hvcC screen stream.
pub fn nal_type(nal: &[u8]) -> Option<u8> {
    if nal.len()<2 || nal[0]&0x80!=0 || nal[1]&7==0 {return None;} Some((nal[0]>>1)&63)
}

struct Bits { data: Vec<u8>, pos: usize }
impl Bits {
    fn read(&mut self,n:usize)->Option<u32>{if n>32 || self.pos.checked_add(n)?>self.data.len()*8{return None;} let mut v=0; for _ in 0..n {v=(v<<1)|((self.data[self.pos/8]>>(7-self.pos%8))&1) as u32;self.pos+=1;}Some(v)}
    fn ue(&mut self)->Option<u32>{let mut n=0;while self.read(1)?==0 {n+=1;if n>31{return None;}}u32::try_from((1u64<<n)-1+self.read(n)? as u64).ok()}
}

/// Decoder configuration plus actual cropped picture size. Reject truncated SPS.
pub fn build_config(vps: &[u8], sps: &[u8], pps: &[u8]) -> Option<(Vec<u8>, (u32, u32))> {
    for (nal,kind) in [(vps,32),(sps,33),(pps,34)] {if nal_type(nal)!=Some(kind) || nal.len()>65535{return None;}}
    let mut rbsp=Vec::new();let mut zeros=0;
    for &byte in &sps[2..] {if zeros>=2 && byte==3 {zeros=0;continue;} rbsp.push(byte);zeros=if byte==0 {zeros+1}else{0};}
    let mut b=Bits{data:rbsp,pos:0}; b.read(4)?;let layers=b.read(3)? as usize;if layers>6{return None;}let nested=b.read(1)? as u8;
    let mut ptl=[0u8;12];for v in &mut ptl {*v=b.read(8)? as u8;}
    let mut flags=Vec::new();for _ in 0..layers {flags.push((b.read(1)?,b.read(1)?));}
    if layers>0 {for _ in layers..8 {b.read(2)?;}}
    for (profile,level) in flags {if profile!=0 {b.read(32)?;b.read(32)?;b.read(24)?;}if level!=0 {b.read(8)?;}}
    if b.ue()?>15{return None;}let chroma=b.ue()?;if chroma>3{return None;}
    let separate=chroma==3 && b.read(1)?!=0;
    let mut w=b.ue()?;let mut h=b.ue()?;
    if b.read(1)?!=0 {let l=b.ue()?;let r=b.ue()?;let t=b.ue()?;let bottom=b.ue()?;
        let array=if separate {0}else{chroma};let ux=if matches!(array,1|2){2}else{1};let uy=if array==1{2}else{1};
        w=w.checked_sub(l.checked_add(r)?.checked_mul(ux)?)?;h=h.checked_sub(t.checked_add(bottom)?.checked_mul(uy)?)?;
    }
    let depth_luma=b.ue()?;let depth_chroma=b.ue()?;
    if w<2||h<2||w>65535||h>65535||depth_luma>7||depth_chroma>7{return None;}
    let mut hvcc=vec![0u8;23];hvcc[0]=1;hvcc[1..13].copy_from_slice(&ptl);
    hvcc[13]=0xf0;hvcc[15]=0xfc;hvcc[16]=0xfc|chroma as u8;hvcc[17]=0xf8|depth_luma as u8;hvcc[18]=0xf8|depth_chroma as u8;
    // Box writes 0x0b for its one-layer, non-nested stream (0x870f0).
    // Android output may differ: preserve SPS temporal structure in standard hvcC.
    hvcc[21]=((layers as u8+1)<<3)|(nested<<2)|3;hvcc[22]=3;
    for (nal,kind) in [(vps,32u8),(sps,33),(pps,34)] {hvcc.extend_from_slice(&[0x80|kind,0,1]);hvcc.extend_from_slice(&(nal.len() as u16).to_be_bytes());hvcc.extend_from_slice(nal);}
    let mut config=vec![0u8;94];config[..4].copy_from_slice(&((94+hvcc.len()) as u32).to_be_bytes());config[4..8].copy_from_slice(b"hvc1");
    config[14..16].copy_from_slice(&[255,255]);config[24..32].copy_from_slice(&[0,0,2,0,0,0,2,0]);
    config[32..34].copy_from_slice(&(w as u16).to_be_bytes());config[34..36].copy_from_slice(&(h as u16).to_be_bytes());
    config[36..44].copy_from_slice(&[0,72,0,0,0,72,0,0]);config[48..50].copy_from_slice(&[0,1]);config[50..55].copy_from_slice(b"\x04HEVC");
    config[82..86].copy_from_slice(&[0,24,255,255]);config[86..90].copy_from_slice(&((8+hvcc.len()) as u32).to_be_bytes());config[90..94].copy_from_slice(b"hvcC");config.extend_from_slice(&hvcc);Some((config,(w,h)))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    pub(crate) fn fixture_sps() -> Vec<u8> {
        fixture_sps_temporal(0,1)
    }
    fn fixture_sps_temporal(layers:u32,nested:u32) -> Vec<u8> {
        let mut bits = Vec::new();
        fn put(b: &mut Vec<u8>, v: u32, n: usize) { for i in (0..n).rev() { b.push(((v >> i) & 1) as u8); } }
        fn ue(b: &mut Vec<u8>, v: u32) { let v = v + 1; let n = 32-v.leading_zeros(); for _ in 1..n { b.push(0); } put(b,v,n as usize); }
        put(&mut bits,1,4); put(&mut bits,layers,3); put(&mut bits,nested,1);
        put(&mut bits,1,8); put(&mut bits,0x60000000,32); put(&mut bits,0,32); put(&mut bits,0,16); put(&mut bits,120,8);
        for _ in 0..layers {put(&mut bits,0,2);}
        if layers>0 {for _ in layers..8 {put(&mut bits,0,2);}}
        ue(&mut bits,0); ue(&mut bits,1); ue(&mut bits,1920); ue(&mut bits,1088);
        put(&mut bits,1,1); ue(&mut bits,0); ue(&mut bits,0); ue(&mut bits,0); ue(&mut bits,4);
        ue(&mut bits,0); ue(&mut bits,0); put(&mut bits,1,1);
        while bits.len()%8!=0 {bits.push(0);}
        let mut out=vec![0x42,1]; let mut zeros=0;
        for chunk in bits.chunks(8) {let byte=chunk.iter().fold(0,|v,b|(v<<1)|b); if zeros>=2 && byte<=3 {out.push(3);zeros=0;} out.push(byte); zeros=if byte==0 {zeros+1}else{0};}
        out
    }
    #[test]
    fn configuration_has_box_hvc1_wrapper_and_sps_crop() {
        let (config,size)=build_config(&[0x40,1,12],&fixture_sps(),&[0x44,1,0xc0]).unwrap();
        assert_eq!(size,(1920,1080)); assert_eq!(&config[4..8],b"hvc1");
        assert_eq!(&config[90..94],b"hvcC"); assert_eq!(&config[14..16],&[255,255]);
        assert_eq!(&config[32..36],&[7,128,4,56]);
        let hvcc=&config[94..]; assert_eq!(&hvcc[..6],&[1,1,0x60,0,0,0]); assert_eq!(hvcc[21],15); assert_eq!(hvcc[22],3);
        assert_eq!(&hvcc[23..31],&[0xa0,0,1,0,3,0x40,1,12]);
        assert_eq!(u32::from_be_bytes(config[..4].try_into().unwrap()) as usize,config.len());
    }
    #[test]
    fn malformed_parameter_sets_are_rejected() {
        for n in 0..fixture_sps().len() {assert!(build_config(&[0x40,1],&fixture_sps()[..n],&[0x44,1]).is_none());}
        assert!(build_config(&[0x42,1],&fixture_sps(),&[0x44,1]).is_none());
    }
    #[test]
    fn temporal_structure_follows_sps_instead_of_box_constant() {
        for (layers,nested,expected) in [(0,0,0x0b),(0,1,0x0f),(2,1,0x1f),(6,0,0x3b)] {
            let (config,_)=build_config(&[0x40,1],&fixture_sps_temporal(layers,nested),&[0x44,1]).unwrap();
            assert_eq!(config[94+21],expected);
        }
    }
}
