//! Statically linked Opus output for the media mixer and native-rate call audio.
use std::io;

pub(crate) struct OpusEncoder {
    native: *mut audiopus_sys::OpusEncoder,
    rate: u32,
    frames: usize,
    pending: Vec<i16>,
    half: Option<i16>,
}
// libopus state is exclusively accessed via &mut self, including reset and destruction.
unsafe impl Send for OpusEncoder {}
impl OpusEncoder {
    pub fn new(rate: u32, frames: usize) -> io::Result<Self> {
        if !matches!((rate,frames),(24000,480)|(48000,960)) {
            return Err(io::Error::other("Opus output requires negotiated 24k/480 or 48k/960 mono"));
        }
        let application = if rate == 24000 {audiopus_sys::OPUS_APPLICATION_VOIP} else {audiopus_sys::OPUS_APPLICATION_AUDIO};
        Self::create(rate, frames, application)
    }
    pub(crate) fn new_call(rate: u32, frames: usize) -> io::Result<Self> {
        if !matches!((rate, frames), (16000, 320) | (24000, 480)) {
            return Err(io::Error::other("unsupported call Opus format"));
        }
        Self::create(rate, frames, audiopus_sys::OPUS_APPLICATION_VOIP)
    }
    fn create(rate: u32, frames: usize, application: i32) -> io::Result<Self> {
        let mut error = 0;
        let native = unsafe { audiopus_sys::opus_encoder_create(rate as i32,1,application,&mut error) };
        if native.is_null() || error != 0 { return Err(io::Error::other(format!("Opus create status={error}"))); }
        Ok(Self { native, rate, frames, pending:Vec::new(), half:None })
    }
    pub fn sample_rate(&self) -> u32 { self.rate }
    pub fn samples_per_packet(&self) -> usize { self.frames }
    pub(crate) fn encode_mono_packet(&mut self, pcm: &[i16]) -> io::Result<Vec<u8>> {
        if pcm.len() != self.frames { return Err(io::Error::other("incorrect Opus call frame length")); }
        let mut packet = vec![0; 1276];
        let size = unsafe { audiopus_sys::opus_encode(self.native, pcm.as_ptr(), self.frames as i32, packet.as_mut_ptr(), packet.len() as i32) };
        if size <= 0 { return Err(io::Error::other(format!("Opus encode status={size}"))); }
        packet.truncate(size as usize);
        Ok(packet)
    }
    pub fn encode(&mut self, stereo: &[i16]) -> io::Result<Vec<Vec<u8>>> {
        if stereo.len()%2 != 0 || stereo.len()>48000 { return Err(io::Error::other("Opus requires bounded complete stereo PCM16 frames")); }
        for pair in stereo.chunks_exact(2) {
            let mono=((i32::from(pair[0])+i32::from(pair[1]))/2) as i16;
            if self.rate == 48000 { self.pending.push(mono); }
            else if let Some(previous)=self.half.take() {
                // A two-sample area average downmixes 48k to 24k continuously across ticks.
                self.pending.push(((i32::from(previous)+i32::from(mono))/2) as i16);
            } else { self.half=Some(mono); }
        }
        let mut packets=Vec::new();
        while self.pending.len()>=self.frames {
            let mut packet=vec![0;1276];
            let size=unsafe { audiopus_sys::opus_encode(self.native,self.pending.as_ptr(),self.frames as i32,packet.as_mut_ptr(),packet.len() as i32) };
            if size<=0 { return Err(io::Error::other(format!("Opus encode status={size}"))); }
            packet.truncate(size as usize);
            self.pending.drain(..self.frames);
            packets.push(packet);
        }
        Ok(packets)
    }
    pub fn reset(&mut self) -> io::Result<()> {
        let status=unsafe { audiopus_sys::opus_encoder_ctl(self.native,audiopus_sys::OPUS_RESET_STATE) };
        if status!=0 { return Err(io::Error::other(format!("Opus reset status={status}"))); }
        self.pending.clear();self.half=None;Ok(())
    }
}
impl Drop for OpusEncoder { fn drop(&mut self) { unsafe { audiopus_sys::opus_encoder_destroy(self.native); } } }

#[cfg(test)]
mod tests {
    use super::*;
    fn tone() -> Vec<i16> {
        (0..4800).flat_map(|i| { let v=((i as f32*std::f32::consts::TAU*440.0/48000.0).sin()*12000.0) as i16; [v,v] }).collect()
    }
    #[test]
    fn bmw_opus_has_exact_twenty_ms_mono_packets_and_chunk_boundaries_preserve_audio() {
        let input=tone();
        for (rate,frames) in [(24000,480),(48000,960)] {
            let whole=OpusEncoder::new(rate,frames).unwrap().encode(&input).unwrap();
            assert_eq!(whole.len(),5,"100ms source must produce five negotiated packets");
            let mut split=OpusEncoder::new(rate,frames).unwrap();
            let actual:Vec<_>=input.chunks(704).flat_map(|part|split.encode(part).unwrap()).collect();
            assert_eq!(actual,whole,"source pump boundaries cannot reset resampling or codec state");
            for packet in &whole { unsafe {
                assert_eq!(audiopus_sys::opus_packet_get_nb_channels(packet.as_ptr()),1);
                assert_eq!(audiopus_sys::opus_packet_get_nb_samples(packet.as_ptr(),packet.len() as i32,rate as i32),frames as i32);
            }}
        }
    }
    #[test]
    fn unsupported_format_and_incomplete_stereo_are_rejected() {
        for (rate,frames) in [(44100,882),(24000,352),(48000,352),(0,0)] { assert!(OpusEncoder::new(rate,frames).is_err()); }
        assert!(OpusEncoder::new(24000,480).unwrap().encode(&[1]).is_err());
    }
    #[test]
    fn encoded_tone_decodes_and_reset_discards_partial_source_audio() {
        for (rate,frames) in [(24000,480),(48000,960)] {
            let packets=OpusEncoder::new(rate,frames).unwrap().encode(&tone()).unwrap();
            let mut error=0;
            let decoder=unsafe{audiopus_sys::opus_decoder_create(rate as i32,1,&mut error)};
            assert_eq!(error,0);assert!(!decoder.is_null());
            let mut total=0;let mut energy=0u64;
            for packet in packets {
                let mut pcm=vec![0i16;frames];
                let count=unsafe{audiopus_sys::opus_decode(decoder,packet.as_ptr(),packet.len() as i32,pcm.as_mut_ptr(),frames as i32,0)};
                assert_eq!(count,frames as i32);total+=count;
                energy+=pcm.iter().map(|v|i64::from(*v).unsigned_abs()).sum::<u64>();
            }
            unsafe{audiopus_sys::opus_decoder_destroy(decoder)};
            assert_eq!(total,rate as i32/10);assert!(energy>100000,"real Opus output must retain the source signal");
            let mut encoder=OpusEncoder::new(rate,frames).unwrap();
            assert!(encoder.encode(&[12000,12000].repeat(600)).unwrap().is_empty());
            encoder.reset().unwrap();
            let zeros=vec![0;1920];
            assert_eq!(encoder.encode(&zeros).unwrap(),OpusEncoder::new(rate,frames).unwrap().encode(&zeros).unwrap());
        }
    }
}
