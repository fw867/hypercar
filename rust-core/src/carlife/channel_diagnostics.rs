//! Content-free, bounded observation of the audio/VR transport boundaries.
#[derive(Default)]
pub(super) struct Stats {
    pub rx_bytes: u64, pub frames: u64, pub decrypt_ok: u64, pub decrypt_fail: u64,
    pub plain: u64, pub pcm_bytes: u64, pub nonzero_samples: u64, pub odd_bytes: u64,
    pub forwarded: u64, pub event_closed: u64, pub empty: u64, pub unknown: u64,
    pub parse_fail: u64, pub observed_only: u64, pub not_ready: u64,
    services: Vec<(u32,u64)>, other_services: u64,
}
impl Stats {
    pub fn frame(&mut self, service:u32) {
        self.frames+=1;
        if let Some((_,count))=self.services.iter_mut().find(|(s,_)|*s==service) {*count+=1;}
        else if self.services.len()<8 {self.services.push((service,1));}
        else {self.other_services+=1;}
    }
    pub fn pcm(&mut self, data:&[u8]) {
        self.pcm_bytes+=data.len() as u64;
        self.nonzero_samples+=data.chunks_exact(2).filter(|p|p[0]!=0 || p[1]!=0).count() as u64;
        self.odd_bytes+=(data.len()%2) as u64;
    }
    pub fn report(&self,label:&str,reason:&str,pending:usize)->String {
        let services=self.services.iter().map(|(s,n)|format!("0x{s:x}:{n}")).collect::<Vec<_>>().join(",");
        format!("CARWITH-CHANNEL: {label} reason={reason} rxBytes={} frames={} services=[{services}] otherServices={} decryptOk={} decryptFail={} plain={} pcmBytes={} nonzeroSamples={} oddBytes={} forwarded={} eventClosed={} empty={} unknown={} parseFail={} observedOnly={} notReady={} pending={pending}; cumulative, no content",self.rx_bytes,self.frames,self.other_services,self.decrypt_ok,self.decrypt_fail,self.plain,self.pcm_bytes,self.nonzero_samples,self.odd_bytes,self.forwarded,self.event_closed,self.empty,self.unknown,self.parse_fail,self.observed_only,self.not_ready)
    }
}
const MAX_PAYLOAD:usize=256*1024;
#[derive(Default)]
pub(super) struct Observer { buf:Vec<u8>, skip:u64 }
impl Observer {
    pub fn pending(&self)->usize {self.buf.len()+self.skip.min(usize::MAX as u64) as usize}
    pub fn push(&mut self, mut input:&[u8], stats:&mut Stats, mut frame:impl FnMut(u32,&[u8],&mut Stats)) {
        stats.rx_bytes+=input.len() as u64;
        while !input.is_empty() {
            if self.skip>0 {let n=self.skip.min(input.len() as u64) as usize;self.skip-=n as u64;input=&input[n..];continue;}
            if self.buf.len()<12 {
                let n=(12-self.buf.len()).min(input.len());self.buf.extend_from_slice(&input[..n]);input=&input[n..];
                if self.buf.len()<12 {break;}
                let len=u32::from_be_bytes(self.buf[..4].try_into().unwrap()) as usize;
                let service=u32::from_be_bytes(self.buf[8..12].try_into().unwrap());
                stats.frame(service);
                if len>MAX_PAYLOAD {stats.parse_fail+=1;self.skip=len as u64;self.buf.clear();continue;}
            }
            let total=12+u32::from_be_bytes(self.buf[..4].try_into().unwrap()) as usize;
            let n=(total-self.buf.len()).min(input.len());self.buf.extend_from_slice(&input[..n]);input=&input[n..];
            if self.buf.len()==total {
                let service=u32::from_be_bytes(self.buf[8..12].try_into().unwrap());
                frame(service,&self.buf[12..],stats);self.buf.clear();
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pcm_counts_samples_without_exposing_content() {
        let mut s=Stats::default();s.pcm(&[0,0,1,0,0,128,9]);
        assert_eq!((s.pcm_bytes,s.nonzero_samples,s.odd_bytes),(7,2,1));
    }
    #[test]
    fn service_cardinality_is_bounded_and_overflow_is_counted() {
        let mut s=Stats::default();for service in 0..100 {s.frame(service);}
        assert_eq!(s.frames,100);assert_eq!(s.services.len(),8);assert_eq!(s.other_services,92);
    }
    #[test]
    fn fragmented_and_coalesced_frames_are_observed_once() {
        let mut o=Observer::default();let mut s=Stats::default();let mut seen=Vec::new();
        let bytes=[0,0,0,2,0,0,0,7,0,5,128,1,3,4,0,0,0,0,0,0,0,8,0,5,128,9];
        for part in bytes.chunks(5) {o.push(part,&mut s,|service,p,_|seen.push((service,p.to_vec())));}
        assert_eq!(seen,vec![(0x58001,vec![3,4]),(0x58009,vec![])]);
        assert_eq!((s.frames,s.rx_bytes),(2,26));assert!(o.buf.is_empty());
    }
    #[test]
    fn oversized_payload_is_skipped_without_buffering_or_false_headers() {
        let mut o=Observer::default();let mut s=Stats::default();let mut seen=0;
        let mut header=[0;12];header[..4].copy_from_slice(&((MAX_PAYLOAD+1) as u32).to_be_bytes());
        o.push(&header,&mut s,|_,_,_|panic!("oversized frame delivered"));
        assert_eq!(s.parse_fail,1);assert!(o.buf.len()<=12);
        for part in vec![0;MAX_PAYLOAD+1].chunks(1024) {o.push(part,&mut s,|_,_,_|panic!("payload parsed as header"));assert!(o.buf.len()<=12);}
        o.push(&[0;12],&mut s,|_,_,_|seen+=1);assert_eq!(seen,1);assert_eq!(s.frames,2);
    }
}
