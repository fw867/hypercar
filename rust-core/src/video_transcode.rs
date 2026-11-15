//! Source-scoped CarWith AVC -> hardware HEVC routing. Transport stays in ScreenSender.
use crate::video_format::VideoCodec;
use std::io;

pub struct Unit { pub pts: u64, pub data: Vec<u8>, pub codec: VideoCodec }
pub trait Codec: Send {
    fn queue(&mut self, data:&[u8], pts:u64)->io::Result<()>;
    fn drain(&mut self)->io::Result<Vec<Unit>>;
}
type Factory = Box<dyn FnMut(u32,u32,u32,&[u8])->io::Result<Box<dyn Codec>> + Send>;
pub struct Router {
    enabled:bool, fps:u32, factory:Factory, active:Option<Box<dyn Codec>>, failed:bool,
    sps:Vec<u8>,pps:Vec<u8>,waiting:bool, submitted:u64,produced:u64,
    progress:std::time::Instant,
    last_input:std::time::Instant,
}
impl Router {
    pub fn new(enabled:bool,fps:u32,factory:Factory)->Self {Self {enabled,fps,factory,active:None,failed:false,sps:vec![],pps:vec![],waiting:true,submitted:0,produced:0,progress:std::time::Instant::now(),last_input:std::time::Instant::now()}}
    pub fn reset(&mut self) {self.active=None;self.failed=false;self.sps.clear();self.pps.clear();self.waiting=true;self.submitted=0;self.produced=0;}
    pub fn pause(&mut self) {self.active=None;self.waiting=true;self.submitted=0;self.produced=0;}
    fn fail(&mut self,error:impl std::fmt::Display) {self.active=None;self.failed=true;self.waiting=true;crate::emit_log(&format!("CARWITH-TRANSCODE: H264 passthrough fallback latched: {error}; request source IDR"));crate::emit_force_keyframe();}
    pub fn poll(&mut self)->Vec<Unit> {
        let Some(codec)=self.active.as_mut() else {return vec![];};
        match codec.drain() {
            Ok(out)=>{if !out.is_empty() {self.produced+=out.len() as u64;self.progress=std::time::Instant::now();}
                // GPU pacing may intentionally omit decoded pictures. Silence after successful
                // output is not a stall; initialization and actively arriving input remain watched.
                if self.submitted>self.produced && (self.produced==0 || self.last_input.elapsed()<std::time::Duration::from_millis(500)) && self.progress.elapsed()>std::time::Duration::from_secs(2) {self.fail("no hardware output for 2 seconds");return vec![];}out}
            Err(e)=>{self.fail(e);vec![]}
        }
    }
    pub fn input(&mut self, allowed:bool, pts:u64,data:Vec<u8>,codec:VideoCodec)->Vec<Unit> {
        if !self.enabled || !allowed || codec==VideoCodec::Hevc {self.active=None;self.waiting=true;return vec![Unit {pts,data,codec}];}
        let nals=crate::screen::annexb_nals(&data);
        let mut changed=false;let mut idr=false;
        for nal in nals {
            match nal.first().map(|v|v&31) {
                Some(7) if nal!=self.sps=>{self.sps=nal.to_vec();changed=true;},
                Some(8) if nal!=self.pps=>{self.pps=nal.to_vec();changed=true;},
                Some(5)=>idr=true,_=>{}
            }
        }
        if changed {self.active=None;self.waiting=true;}
        if self.sps.is_empty() || self.pps.is_empty() {return vec![];}
        if self.waiting && !idr {return vec![];}
        let mut configured=Vec::new();for set in [&self.sps,&self.pps] {configured.extend([0,0,0,1]);configured.extend(set);}
        if !self.failed && self.active.is_none() {
            if let Some((w,h))=crate::h264::sps_dimensions(&self.sps) {
                match (self.factory)(w,h,self.fps,&configured) {
                    Ok(c)=>{self.active=Some(c);self.submitted=0;self.produced=0;self.progress=std::time::Instant::now();crate::emit_log(&format!("CARWITH-TRANSCODE: hardware AVC {w}x{h} -> HEVC initializing; watchdog active"));},
                    Err(e)=>self.fail(e),
                }
            } else {self.fail("invalid AVC SPS dimensions");}
        }
        if let Some(c)=self.active.as_mut() {
            let mut input=if self.waiting {configured.clone()}else{vec![]};input.extend(&data);
            match c.queue(&input,pts) {Ok(())=>{
                // Idle pages may resume after arbitrarily long silence. Give the asynchronous
                // codec a fresh output deadline instead of treating the old idle time as a stall.
                if self.produced>0 && self.last_input.elapsed()>=std::time::Duration::from_millis(500) {self.progress=std::time::Instant::now();}
                self.waiting=false;self.submitted+=1;self.last_input=std::time::Instant::now();return self.poll();
            },Err(e)=>self.fail(e)}
        }
        if self.waiting {
            if !idr {return vec![];}
            configured.extend(data);self.waiting=false;
            return vec![Unit{pts,data:configured,codec:VideoCodec::H264}];
        }
        vec![Unit{pts,data,codec:VideoCodec::H264}]
    }
}

struct JavaCodec(jni::objects::GlobalRef);
impl Codec for JavaCodec {
    fn queue(&mut self,data:&[u8],pts:u64)->io::Result<()> {
        crate::with_env(|env|env.with_local_frame(8,|env|{let bytes=env.byte_array_from_slice(data)?;env.call_method(self.0.as_obj(),"queue","([BJ)V",&[(&bytes).into(),(pts as i64).into()])?;Ok(())})).ok_or_else(||io::Error::other("MediaCodec queue failed"))
    }
    fn drain(&mut self)->io::Result<Vec<Unit>> {
        crate::with_env(|env|{
            if env.call_method(self.0.as_obj(),"needsKeyFrame","()Z",&[])?.z()? {crate::emit_force_keyframe();}
            let mut units=vec![];
            for _ in 0..16 {
                let data=env.call_method(self.0.as_obj(),"drain","()[B",&[])?.l()?;
                if data.is_null(){break;}
                let arr=jni::objects::JByteArray::from(data);
                let bytes=env.convert_byte_array(&arr)?;env.delete_local_ref(arr)?;
                if bytes.len()<8 || bytes.len()>16*1024*1024 {return Err(jni::errors::Error::NullPtr("encoded AU bounds"));}
                units.push(Unit {pts:u64::from_le_bytes(bytes[..8].try_into().unwrap()),data:bytes[8..].to_vec(),codec:VideoCodec::Hevc});
            }Ok(units)
        }).ok_or_else(||io::Error::other("MediaCodec drain failed"))
    }
}
impl Drop for JavaCodec {fn drop(&mut self){let _=crate::with_env(|env|env.call_method(self.0.as_obj(),"close","()V",&[]).map(|_|()));}}
pub fn hardware_factory(target:std::sync::Arc<parking_lot::Mutex<(u32,u32)>>)->Factory {Box::new(move |w,h,fps,csd| {
    let (tw,th)=*target.lock();
    let listener=crate::TRANSCODER_BRIDGE.read().clone().ok_or_else(||io::Error::other("no codec provider"))?;
    crate::with_env(|env| {
        let bytes=env.byte_array_from_slice(csd)?;
        let obj=env.call_method(listener.as_obj(),"createCarWithTranscoder","(IIIII[B)Lio/github/hypercar/core/CarWithHevcTranscoder;",&[(w as i32).into(),(h as i32).into(),(tw as i32).into(),(th as i32).into(),(fps as i32).into(),(&bytes).into()])?.l()?;
        let result=env.new_global_ref(&obj)?;env.delete_local_ref(obj)?;env.delete_local_ref(bytes)?;
        Ok(Box::new(JavaCodec(result)) as Box<dyn Codec>)
    }).ok_or_else(||io::Error::other("hardware AVC/HEVC pair unavailable or configure failed"))
})}

#[cfg(test)] mod tests {
    use super::*;
    use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
    const SPS:&[u8]=&[0x67,0x42,0xc0,0x28,0xda,0x01,0xe0,0x08,0x9f,0x97,1,1,1,2];
    fn avc(idr:bool)->Vec<u8> {let mut v=vec![0,0,0,1];v.extend(SPS);v.extend([0,0,0,1,0x68,1,0,0,0,1,if idr{0x65}else{0x41},0x80]);v}
    struct Fake {fail:bool,pending:Vec<Unit>}
    impl Codec for Fake {
        fn queue(&mut self,_:&[u8],pts:u64)->io::Result<()> {if self.fail {return Err(io::Error::other("hardware failed"));}self.pending.push(Unit{pts,data:vec![0,0,0,1,0x26,1,0x80],codec:VideoCodec::Hevc});Ok(())}
        fn drain(&mut self)->io::Result<Vec<Unit>> {Ok(std::mem::take(&mut self.pending))}
    }
    fn router(enabled:bool,fail:bool)->(Router,Arc<AtomicUsize>) {let n=Arc::new(AtomicUsize::new(0));let count=n.clone();(Router::new(enabled,60,Box::new(move|w,h,_,_|{assert_eq!((w,h),(1920,1080));count.fetch_add(1,Ordering::SeqCst);Ok(Box::new(Fake{fail,pending:vec![]}))})),n)}
    #[test] fn priority_and_receiver_gate_hardware_creation() {for (on,allowed) in [(false,true),(true,false)] {let(mut r,n)=router(on,false);assert_eq!(r.input(allowed,1,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),0);}}
    #[test] fn avc_is_converted_only_after_configuration_and_idr() {let(mut r,n)=router(true,false);assert!(r.input(true,1,vec![0,0,0,1,0x41,0x80],VideoCodec::H264).is_empty());let out=r.input(true,2,avc(true),VideoCodec::H264);assert_eq!(out[0].codec,VideoCodec::Hevc);assert_eq!(out[0].pts,2);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test] fn runtime_failure_latches_avc_fallback() {let(mut r,n)=router(true,true);let out=r.input(true,1,avc(true),VideoCodec::H264);assert_eq!(out[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test] fn real_hevc_bypasses_conversion() {let(mut r,n)=router(true,false);assert_eq!(r.input(true,1,vec![0,0,0,1,0x26,1,0x80],VideoCodec::Hevc)[0].codec,VideoCodec::Hevc);assert_eq!(n.load(Ordering::SeqCst),0);}
    #[test] fn pause_waits_for_keyframe_and_retains_failure_latch() {let(mut r,n)=router(true,true);r.input(true,1,avc(true),VideoCodec::H264);r.pause();assert!(r.input(true,2,avc(false),VideoCodec::H264).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);r.reset();r.input(true,4,avc(true),VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),2);}
    #[test] fn output_watchdog_waits_for_idr_before_avc_fallback() {let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264);r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);assert!(r.poll().is_empty());assert!(r.input(true,2,avc(false),VideoCodec::H264).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);}
    #[test] fn unsupported_hardware_falls_back_without_retry_loop() {let count=Arc::new(AtomicUsize::new(0));let n=count.clone();let mut r=Router::new(true,30,Box::new(move|_,_,_,_|{n.fetch_add(1,Ordering::SeqCst);Err(io::Error::other("unsupported"))}));assert_eq!(r.input(true,1,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264)[0].codec,VideoCodec::H264);assert_eq!(count.load(Ordering::SeqCst),1);}
    #[test] fn paced_source_idle_is_not_a_false_stall() {let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264);r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);r.last_input=r.progress;assert!(r.poll().is_empty());assert!(!r.failed);}
    #[test] fn first_packet_after_idle_gets_a_fresh_async_deadline() {
        struct Delayed;impl Codec for Delayed {fn queue(&mut self,_:&[u8],_:u64)->io::Result<()>{Ok(())}fn drain(&mut self)->io::Result<Vec<Unit>>{Ok(vec![])}}
        let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264);r.active=Some(Box::new(Delayed));r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);r.last_input=r.progress;
        assert!(r.input(true,2,avc(false),VideoCodec::H264).is_empty());assert!(!r.failed);assert!(r.progress.elapsed()<std::time::Duration::from_secs(1));
    }

    #[test] fn parameter_change_rebuilds_only_at_next_keyframe() {let(mut r,n)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264);let mut changed=avc(false);let at=changed.windows(2).position(|v|v==[0x68,1]).unwrap();changed[at+1]=2;assert!(r.input(true,2,changed,VideoCodec::H264).is_empty());assert_eq!(n.load(Ordering::SeqCst),1);r.input(true,3,avc(true),VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),2);}
}
