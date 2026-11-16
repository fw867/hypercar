//! Source-scoped CarWith HEVC scaling and encoding. Transport stays in ScreenSender.
use crate::video_format::{FrameOrigin, VideoCodec};
use std::collections::VecDeque;
use std::io;

pub struct Unit { pub pts: u64, pub data: Vec<u8>, pub codec: VideoCodec, pub origin: FrameOrigin }
pub trait Codec: Send {
    fn queue(&mut self, data:&[u8], pts:u64)->io::Result<()>;
    fn drain(&mut self)->io::Result<Vec<Unit>>;
}
type Factory = Box<dyn FnMut(VideoCodec,(u32,u32),(u32,u32),u32,&[u8])->io::Result<Box<dyn Codec>> + Send>;
pub struct Router {
    enabled:bool, fps:u32, factory:Factory, active:Option<Box<dyn Codec>>, failed:bool,
    target: Option<(u32,u32)>, input_codec: Option<VideoCodec>, vps: Vec<u8>,
    sps:Vec<u8>,pps:Vec<u8>,waiting:bool, submitted:u64,produced:u64,
    // The hardware codec drains asynchronously, may skip pictures, and preserves PTS via EGL.
    // Keep this diagnostic history bounded even if the encoder stops producing output.
    origins: VecDeque<(u64, FrameOrigin)>,
    progress:std::time::Instant,
    last_input:std::time::Instant,
}
impl Router {
    pub fn new(enabled:bool,fps:u32,factory:Factory)->Self {Self {target:None,input_codec:None,vps:vec![],origins:VecDeque::new(),enabled,fps,factory,active:None,failed:false,sps:vec![],pps:vec![],waiting:true,submitted:0,produced:0,progress:std::time::Instant::now(),last_input:std::time::Instant::now()}}
    pub fn reset(&mut self) {self.active=None;self.failed=false;self.vps.clear();self.input_codec=None;self.sps.clear();self.pps.clear();self.waiting=true;self.submitted=0;self.produced=0;}
    pub fn set_target(&mut self, target: Option<(u32,u32)>) {
        if self.target != target { self.reset(); self.target = target; }
    }
    pub fn encoding_to_display(&self) -> bool { self.active.is_some() }
    fn configuration(&self) -> Vec<u8> {
        let mut data = Vec::new();
        for set in [&self.vps, &self.sps, &self.pps].into_iter().filter(|s| !s.is_empty()) {
            data.extend([0,0,0,1]);
            data.extend(set);
        }
        data
    }
    pub fn pause(&mut self) {self.active=None;self.waiting=true;self.submitted=0;self.produced=0;}
    fn fail(&mut self,error:impl std::fmt::Display) {self.active=None;self.failed=true;self.waiting=true;crate::emit_log(&format!("CARWITH-TRANSCODE: source passthrough fallback latched: {error}; request source IDR"));crate::emit_force_keyframe();}
    pub fn poll(&mut self)->Vec<Unit> {
        let Some(codec)=self.active.as_mut() else {return vec![];};
        match codec.drain() {
            Ok(mut out)=>{
                for unit in &mut out {
                    let mut origins = self.origins.iter().filter(|(pts, _)| *pts == unit.pts).map(|(_, origin)| *origin);
                    unit.origin = match origins.next() {
                        Some(first) if origins.all(|other| other == first) => first,
                        _ => FrameOrigin::Unknown,
                    };
                }
                if !out.is_empty() {self.produced+=out.len() as u64;self.progress=std::time::Instant::now();}
                // GPU pacing may intentionally omit decoded pictures. Silence after successful
                // output is not a stall; initialization and actively arriving input remain watched.
                let deadline = std::time::Duration::from_secs(if self.produced == 0 { 8 } else { 2 });
                if self.submitted>self.produced && (self.produced==0 || self.last_input.elapsed()<std::time::Duration::from_millis(500)) && self.progress.elapsed()>deadline {self.fail(format!("no hardware output for {} seconds", deadline.as_secs()));return vec![];}out}
            Err(e)=>{self.fail(e);vec![]}
        }
    }
    pub fn input(&mut self, hevc_allowed:bool, pts:u64,data:Vec<u8>,codec:VideoCodec,origin:FrameOrigin)->Vec<Unit> {
        if !self.enabled || !hevc_allowed {
            if self.input_codec.is_some() { self.reset(); }
            return vec![Unit { pts, data, codec, origin }];
        }
        if self.input_codec != Some(codec) {
            self.reset();
            self.input_codec = Some(codec);
        }
        let mut changed = false;
        let mut idr = false;
        for nal in crate::screen::annexb_nals(&data) {
            let slot = match codec {
                VideoCodec::H264 => match nal.first().map(|v| v & 31) {
                    Some(7) => &mut self.sps, Some(8) => &mut self.pps,
                    kind => { idr |= kind == Some(5); continue; }
                },
                VideoCodec::Hevc => match crate::hevc::nal_type(nal) {
                    Some(32) => &mut self.vps, Some(33) => &mut self.sps, Some(34) => &mut self.pps,
                    kind => { idr |= matches!(kind, Some(16..=21)); continue; }
                },
            };
            if slot.as_slice() != nal { *slot = nal.to_vec(); changed = true; }
        }
        if changed { self.pause(); }
        let mut configured = Vec::new();
        if self.waiting {
            if !idr || self.sps.is_empty() || self.pps.is_empty() ||
                (codec == VideoCodec::Hevc && self.vps.is_empty()) { return vec![]; }
            configured = self.configuration();
            if !self.failed {
                let source = match codec {
                    VideoCodec::Hevc => crate::hevc::build_config(&self.vps,&self.sps,&self.pps).map(|(_,size)|size),
                    VideoCodec::H264 => crate::h264::sps_dimensions(&self.sps),
                };
                if let Some(source) = source {
                    let target = self.target.unwrap_or(source);
                    if codec != VideoCodec::Hevc || source != target {
                        match (self.factory)(codec,source,target,self.fps,&configured) {
                            Ok(c) => {
                                self.origins.clear(); self.active=Some(c); self.submitted=0; self.produced=0;
                                self.progress=std::time::Instant::now();
                                crate::emit_log(&format!("CARWITH-TRANSCODE: {} {}x{} -> HEVC {}x{} initializing",codec.as_str(),source.0,source.1,target.0,target.1));
                            },
                            Err(e) => self.fail(e),
                        }
                    }
                } else { self.fail("invalid source SPS dimensions"); }
            }
        }
        if let Some(c)=self.active.as_mut() {
            let mut input=if self.waiting {configured.clone()}else{vec![]};input.extend(&data);
            match c.queue(&input,pts) {Ok(())=>{
                if self.origins.len() == 256 { self.origins.pop_front(); }
                self.origins.push_back((pts, origin));
                // Idle pages may resume after arbitrarily long silence. Give the asynchronous
                // codec a fresh output deadline instead of treating the old idle time as a stall.
                if self.produced>0 && self.last_input.elapsed()>=std::time::Duration::from_millis(500) {self.progress=std::time::Instant::now();}
                self.waiting=false;self.submitted+=1;self.last_input=std::time::Instant::now();return self.poll();
            },Err(e)=>self.fail(e)}
        }
        if self.waiting {
            if !idr {return vec![];}
            if configured.is_empty() { configured = self.configuration(); }
            configured.extend(data);self.waiting=false;
            return vec![Unit{pts,data:configured,codec,origin}];
        }
        vec![Unit{pts,data,codec,origin}]
    }
}

struct JavaCodec { bridge: jni::objects::GlobalRef }
impl Codec for JavaCodec {
    fn queue(&mut self,data:&[u8],pts:u64)->io::Result<()> {
        crate::with_env(|env|env.with_local_frame(8,|env|{let bytes=env.byte_array_from_slice(data)?;env.call_method(self.bridge.as_obj(),"queue","([BJ)V",&[(&bytes).into(),(pts as i64).into()])?;Ok(())})).ok_or_else(||io::Error::other("MediaCodec queue failed"))
    }
    fn drain(&mut self)->io::Result<Vec<Unit>> {
        crate::with_env(|env|{
            if env.call_method(self.bridge.as_obj(),"needsKeyFrame","()Z",&[])?.z()? {crate::emit_force_keyframe();}
            let mut units=vec![];
            for _ in 0..16 {
                let data=env.call_method(self.bridge.as_obj(),"drain","()[B",&[])?.l()?;
                if data.is_null(){break;}
                let arr=jni::objects::JByteArray::from(data);
                let bytes=env.convert_byte_array(&arr)?;env.delete_local_ref(arr)?;
                if bytes.len()<8 || bytes.len()>16*1024*1024 {return Err(jni::errors::Error::NullPtr("encoded AU bounds"));}
                units.push(Unit {pts:u64::from_le_bytes(bytes[..8].try_into().unwrap()),data:bytes[8..].to_vec(),codec:VideoCodec::Hevc,origin:FrameOrigin::Unknown});
            }Ok(units)
        }).ok_or_else(||io::Error::other("MediaCodec drain failed"))
    }
}
impl Drop for JavaCodec {fn drop(&mut self){let _=crate::with_env(|env|env.call_method(self.bridge.as_obj(),"close","()V",&[]).map(|_|()));}}
pub fn hardware_factory()->Factory {Box::new(|codec,(w,h),(tw,th),fps,csd| {
    let listener=crate::TRANSCODER_BRIDGE.read().clone().ok_or_else(||io::Error::other("no codec provider"))?;
    crate::with_env(|env| {
        let bytes=env.byte_array_from_slice(csd)?;
        let obj=env.call_method(listener.as_obj(),"createCarWithTranscoder","(ZIIIII[B)Lio/github/hypercar/core/CarWithHevcTranscoder;",&[(codec == VideoCodec::Hevc).into(),(w as i32).into(),(h as i32).into(),(tw as i32).into(),(th as i32).into(),(fps as i32).into(),(&bytes).into()])?.l()?;
        let result=env.new_global_ref(&obj)?;env.delete_local_ref(obj)?;env.delete_local_ref(bytes)?;
        Ok(Box::new(JavaCodec{bridge:result}) as Box<dyn Codec>)
    }).ok_or_else(||io::Error::other("hardware codec pair unavailable or configure failed"))
})}

#[cfg(test)] mod tests {
    use super::*;
    use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
    const SPS:&[u8]=&[0x67,0x42,0xc0,0x28,0xda,0x01,0xe0,0x08,0x9f,0x97,1,1,1,2];
    fn avc(idr:bool)->Vec<u8> {let mut v=vec![0,0,0,1];v.extend(SPS);v.extend([0,0,0,1,0x68,1,0,0,0,1,if idr{0x65}else{0x41},0x80]);v}
    struct Fake {fail:bool,pending:Vec<Unit>}
    impl Codec for Fake {
        fn queue(&mut self,_:&[u8],pts:u64)->io::Result<()> {if self.fail {return Err(io::Error::other("hardware failed"));}self.pending.push(Unit{pts,data:vec![],codec:VideoCodec::Hevc,origin:FrameOrigin::Unknown});Ok(())}
        fn drain(&mut self)->io::Result<Vec<Unit>> {Ok(std::mem::take(&mut self.pending))}
    }
    fn router(enabled:bool,fail:bool)->(Router,Arc<AtomicUsize>) {let n=Arc::new(AtomicUsize::new(0));let count=n.clone();(Router::new(enabled,60,Box::new(move|_,source,_,_,_|{assert_eq!(source,(1920,1080));count.fetch_add(1,Ordering::SeqCst);Ok(Box::new(Fake{fail,pending:vec![]}))})),n)}
    #[test] fn priority_and_receiver_gate_hardware_creation() {for (on,allowed) in [(false,true),(true,false)] {let(mut r,n)=router(on,false);assert_eq!(r.input(allowed,1,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),0);}}
    #[tokio::test]
    async fn avc_only_receiver_passes_cached_and_live_source_unchanged_at_any_display_size() {
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut receiver, _) = listener.accept().await.unwrap();
        let key = [7;32];
        let mut sender = crate::screen::ScreenSender::new(client.into_split().1,
            crate::screen::ScreenCipher::ChaCha(key), 1920, 1080, false);
        sender.set_receiver_size((2736,800));
        let (mut router, opened) = router(true, false);
        router.set_target(sender.receiver_size());
        for (pts, origin, bytes) in [(1, FrameOrigin::Cached, avc(true)), (2, FrameOrigin::Live, avc(false))] {
            let frames = router.input(false, pts, bytes.clone(), VideoCodec::H264, origin);
            assert_eq!(frames.len(), 1);
            assert_eq!((frames[0].pts, frames[0].origin, frames[0].codec), (pts, origin, VideoCodec::H264));
            assert_eq!(frames[0].data, bytes);
            sender.send_video_annexb_from(&frames[0].data, pts, origin).await.unwrap();
        }
        assert_eq!(opened.load(Ordering::SeqCst), 0);
        assert_eq!(sender.frames_sent(), 2);
        for index in 0..3 {
            let mut header = [0;128];
            receiver.read_exact(&mut header).await.unwrap();
            let mut body = vec![0; u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
            receiver.read_exact(&mut body).await.unwrap();
            if index == 0 {
                assert_eq!(body, crate::screen::build_avcc(SPS, &[0x68,1]));
                assert_eq!(crate::h264::avcc_dimensions(&body), Some((1920,1080)));
            } else {
                let plain = crate::crypto::chacha_open(&key, &crate::crypto::nonce64(index-1), &body, &header).unwrap();
                assert_eq!(plain, [0,0,0,2,if index == 1 {0x65} else {0x41},0x80]);
            }
        }
    }
    #[test] fn avc_is_converted_only_after_configuration_and_idr() {let(mut r,n)=router(true,false);assert!(r.input(true,1,vec![0,0,0,1,0x41,0x80],VideoCodec::H264,FrameOrigin::Live).is_empty());let out=r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(out[0].codec,VideoCodec::Hevc);assert_eq!(out[0].pts,2);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test] fn runtime_failure_latches_avc_fallback() {let(mut r,n)=router(true,true);let out=r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(out[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test]
    fn first_hevc_idr_uses_current_display_only_when_resize_is_needed() {
        let mut data=Vec::new();
        for nal in [vec![0x40,1,12],crate::hevc::tests::fixture_sps(),vec![0x44,1,0xc0],vec![0x26,1,0x80]] {
            data.extend([0,0,0,1]); data.extend(nal);
        }
        for target in [(1920,1080),(2400,1080)] {
            let opened=Arc::new(AtomicUsize::new(0)); let count=opened.clone();
            let mut r=Router::new(true,30,Box::new(move |codec,source,output,_,_| {
                assert_eq!((codec,source,output),(VideoCodec::Hevc,(1920,1080),target));
                count.fetch_add(1,Ordering::SeqCst);
                Ok(Box::new(Fake{fail:false,pending:vec![]}))
            }));
            r.set_target(Some(target));
            assert_eq!(r.input(true,1,data.clone(),VideoCodec::Hevc,FrameOrigin::Cached)[0].codec,VideoCodec::Hevc);
            assert_eq!(opened.load(Ordering::SeqCst),usize::from(target!=(1920,1080)));
        }
    }
    #[test] fn pause_waits_for_keyframe_and_retains_failure_latch() {let(mut r,n)=router(true,true);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.pause();assert!(r.input(true,2,avc(false),VideoCodec::H264,FrameOrigin::Live).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);r.reset();r.input(true,4,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(n.load(Ordering::SeqCst),2);}
    #[test] fn output_watchdog_waits_for_idr_before_avc_fallback() {let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);assert!(r.poll().is_empty());assert!(r.input(true,2,avc(false),VideoCodec::H264,FrameOrigin::Live).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);}
    #[test] fn unsupported_hardware_falls_back_without_retry_loop() {let count=Arc::new(AtomicUsize::new(0));let n=count.clone();let mut r=Router::new(true,30,Box::new(move|_,_,_,_,_|{n.fetch_add(1,Ordering::SeqCst);Err(io::Error::other("unsupported"))}));assert_eq!(r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(count.load(Ordering::SeqCst),1);}
    #[test] fn paced_source_idle_is_not_a_false_stall() {let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);r.last_input=r.progress;assert!(r.poll().is_empty());assert!(!r.failed);}
    #[test] fn cold_start_has_its_own_bounded_first_output_deadline() {
        let (mut router,_) = router(true,false);
        router.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);
        router.produced = 0;
        router.progress = std::time::Instant::now()-std::time::Duration::from_secs(3);
        assert!(router.poll().is_empty() && !router.failed);
        router.progress -= std::time::Duration::from_secs(6);
        assert!(router.poll().is_empty() && router.failed);
    }
    #[test] fn first_packet_after_idle_gets_a_fresh_async_deadline() {
        struct Delayed;impl Codec for Delayed {fn queue(&mut self,_:&[u8],_:u64)->io::Result<()>{Ok(())}fn drain(&mut self)->io::Result<Vec<Unit>>{Ok(vec![])}}
        let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.active=Some(Box::new(Delayed));r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);r.last_input=r.progress;
        assert!(r.input(true,2,avc(false),VideoCodec::H264,FrameOrigin::Live).is_empty());assert!(!r.failed);assert!(r.progress.elapsed()<std::time::Duration::from_secs(1));
    }

    #[test]
    fn delayed_hevc_output_uses_its_input_pts_even_after_a_live_input() {
        struct DelayedCodec(Vec<Unit>);
        impl Codec for DelayedCodec {
            fn queue(&mut self, _: &[u8], pts: u64) -> io::Result<()> {
                self.0.push(Unit { pts, data: vec![], codec: VideoCodec::Hevc, origin: FrameOrigin::Unknown });
                Ok(())
            }
            fn drain(&mut self) -> io::Result<Vec<Unit>> {
                Ok(if self.0.len() >= 2 { vec![self.0.remove(0)] } else { vec![] })
            }
        }
        let mut router = Router::new(true, 30, Box::new(|_, _, _, _, _| Ok(Box::new(DelayedCodec(vec![])))));
        assert!(router.input(true, 10, avc(true), VideoCodec::H264, FrameOrigin::Cached).is_empty());
        let cached = router.input(true, 20, avc(false), VideoCodec::H264, FrameOrigin::Live);
        assert_eq!((cached[0].pts, cached[0].origin), (10, FrameOrigin::Cached));
        let live = router.input(true, 30, avc(false), VideoCodec::H264, FrameOrigin::Live);
        assert_eq!((live[0].pts, live[0].origin), (20, FrameOrigin::Live));
        let ambiguous = router.input(true, 30, avc(false), VideoCodec::H264, FrameOrigin::Cached);
        assert_eq!(ambiguous[0].origin, FrameOrigin::Unknown);
    }

    #[test] fn parameter_change_rebuilds_only_at_next_keyframe() {let(mut r,n)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);let mut changed=avc(false);let at=changed.windows(2).position(|v|v==[0x68,1]).unwrap();changed[at+1]=2;assert!(r.input(true,2,changed,VideoCodec::H264,FrameOrigin::Live).is_empty());assert_eq!(n.load(Ordering::SeqCst),1);r.input(true,3,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(n.load(Ordering::SeqCst),2);}
}
