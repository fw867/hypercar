//! Source-scoped CarWith hardware scaling and encoding. Transport stays in ScreenSender.
use crate::video_format::{FrameOrigin, VideoCodec};
use std::collections::VecDeque;
use std::io;

pub struct Unit { pub pts: u64, pub data: Vec<u8>, pub codec: VideoCodec, pub origin: FrameOrigin }
pub trait Codec: Send {
    fn queue(&mut self, data:&[u8], pts:u64)->io::Result<()>;
    fn drain(&mut self)->io::Result<Vec<Unit>>;
}
type Factory = Box<dyn FnMut(VideoCodec,VideoCodec,(u32,u32),(u32,u32),u32,&[u8])->io::Result<Box<dyn Codec>> + Send>;
pub struct Router {
    fit_oversize_display: bool,
    requested_fps: u32,
    display_max_fps: Option<u32>,
    source_fps: Option<u32>,
    enabled:bool, fps:u32, factory:Factory, active:Option<Box<dyn Codec>>, failed:bool,
    output_size: Option<(u32,u32)>, target: Option<(u32,u32)>, input_codec: Option<VideoCodec>, vps: Vec<u8>,
    sps:Vec<u8>,pps:Vec<u8>,waiting:bool, submitted:u64,produced:u64,
    // The hardware codec drains asynchronously, may skip pictures, and preserves PTS via EGL.
    // Keep this diagnostic history bounded even if the encoder stops producing output.
    origins: VecDeque<(u64, FrameOrigin)>,
    progress:std::time::Instant,
    last_input:std::time::Instant,
}
impl Router {
    pub fn new(enabled:bool,fps:u32,factory:Factory)->Self {Self {fit_oversize_display:false,requested_fps:fps,display_max_fps:None,source_fps:None,output_size:None,target:None,input_codec:None,vps:vec![],origins:VecDeque::new(),enabled,fps,factory,active:None,failed:false,sps:vec![],pps:vec![],waiting:true,submitted:0,produced:0,progress:std::time::Instant::now(),last_input:std::time::Instant::now()}}
    pub fn for_carwith_profile(profile: Option<&str>, fps: u32, factory: Factory) -> Self {
        let mut router = Self::new(profile.is_some(), fps, factory);
        // CarWith's source is capped at 2560; any receiver may expose a larger display.
        // Encode at the receiver size while keeping the actual source as the touch canvas.
        router.fit_oversize_display = profile.is_some();
        router
    }
    pub fn reset(&mut self) {
        self.pause();
        self.failed = false;
        self.input_codec = None;
        self.vps.clear();
        self.sps.clear();
        self.pps.clear();
    }
    pub fn set_target(&mut self, target: Option<(u32,u32)>, max_fps: Option<u32>) {
        if self.target != target || self.display_max_fps != max_fps {
            self.reset();
            self.target = target;
            self.display_max_fps = max_fps;
            self.fps = self.requested_fps.min(max_fps.unwrap_or(self.requested_fps));
        } else { self.pause(); }
    }

    pub fn set_source_fps(&mut self, fps: Option<u32>) {
        let was_over = self.needs_rate_conversion();
        self.source_fps = fps;
        if self.needs_rate_conversion() != was_over { self.reset(); }
    }

    fn needs_rate_conversion(&self) -> bool {
        self.display_max_fps.is_some() && self.source_fps.is_none_or(|source| source > self.fps)
    }
    fn output_canvas(&self, source: (u32,u32)) -> (u32,u32) {
        self.target.filter(|&(w,h)| needs_stretch(source, (w,h)) ||
            (self.fit_oversize_display && w.max(h) > 2560)).unwrap_or(source)
    }
    pub fn output_size(&self) -> Option<(u32,u32)> { self.output_size }
    fn configuration(&self) -> Vec<u8> {
        let mut data = Vec::new();
        for set in [&self.vps, &self.sps, &self.pps].into_iter().filter(|s| !s.is_empty()) {
            data.extend([0,0,0,1]);
            data.extend(set);
        }
        data
    }
    pub fn pause(&mut self) {self.active=None;self.output_size=None;self.waiting=true;self.submitted=0;self.produced=0;}
    pub fn discard_backlog(&mut self) {
        self.pause();
        self.vps.clear();
        self.sps.clear();
        self.pps.clear();
        self.origins.clear();
    }
    fn fail(&mut self, error: impl std::fmt::Display) {
        self.pause();
        self.failed = true;
        let fallback = if self.needs_rate_conversion() { "source frame rate unconfirmed or exceeds maxFPS; withholding video" }
            else { "source passthrough fallback latched" };
        crate::emit_log(&format!("CARWITH-TRANSCODE: {fallback}: {error}; request source IDR"));
        crate::emit_force_keyframe();
    }
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
        // Keep an unsupported HEVC source tagged for ScreenSender's explicit session rejection.
        if !self.enabled || (!hevc_allowed && (codec == VideoCodec::Hevc || (self.target.is_none() && !self.needs_rate_conversion()))) {
            if self.input_codec.is_some() { self.reset(); }
            return vec![Unit { pts, data, codec, origin }];
        }
        if self.input_codec != Some(codec) {
            self.reset();
            self.input_codec = Some(codec);
        }
        let mut changed = false;
        let mut idr = false;
        let mut has_sps = false;
        let mut has_pps = false;
        let mut has_vps = codec == VideoCodec::H264;
        for nal in crate::screen::annexb_nals(&data) {
            let (slot, seen) = match codec {
                VideoCodec::H264 => match nal.first().map(|v| v & 31) {
                    Some(7) => (&mut self.sps, &mut has_sps),
                    Some(8) => (&mut self.pps, &mut has_pps),
                    kind => { idr |= kind == Some(5); continue; }
                },
                VideoCodec::Hevc => match crate::hevc::nal_type(nal) {
                    Some(32) => (&mut self.vps, &mut has_vps),
                    Some(33) => (&mut self.sps, &mut has_sps),
                    Some(34) => (&mut self.pps, &mut has_pps),
                    kind => { idr |= matches!(kind, Some(16..=21)); continue; }
                },
            };
            *seen = true;
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
                    let target = self.output_canvas(source);
                    let output = if hevc_allowed { VideoCodec::Hevc } else { VideoCodec::H264 };
                    if codec != output || source != target || self.needs_rate_conversion() {
                        match (self.factory)(codec,output,source,target,self.fps,&configured) {
                            Ok(c) => {
                                self.origins.clear(); self.active=Some(c); self.output_size=Some(target); self.submitted=0; self.produced=0;
                                self.progress=std::time::Instant::now();
                                crate::emit_log(&format!("CARWITH-TRANSCODE: {} {}x{} -> {} {}x{} initializing target_fps={}",codec.as_str(),source.0,source.1,output.as_str(),target.0,target.1,self.fps));
                            },
                            Err(e) => self.fail(e),
                        }
                    } else {
                        crate::emit_log(&format!("CARWITH-TRANSCODE: {} {}x{} passthrough receiver={:?} aspect difference within 3%", codec.as_str(), source.0, source.1, self.target));
                    }
                } else { self.fail("invalid source SPS dimensions"); }
            }
        }
        if let Some(c)=self.active.as_mut() {
            // Codec-specific data was supplied at decoder configuration time.
            match c.queue(&data,pts) {Ok(())=>{
                if self.origins.len() == 256 { self.origins.pop_front(); }
                self.origins.push_back((pts, origin));
                // Idle pages may resume after arbitrarily long silence. Give the asynchronous
                // codec a fresh output deadline instead of treating the old idle time as a stall.
                if self.produced>0 && self.last_input.elapsed()>=std::time::Duration::from_millis(500) {self.progress=std::time::Instant::now();}
                self.waiting=false;self.submitted+=1;self.last_input=std::time::Instant::now();return self.poll();
            },Err(e)=>self.fail(e)}
        }
        // Dropping arbitrary P pictures would corrupt references. If the phone
        // exceeds the effective limit or omits its rate for a capped display, only paced output is valid.
        if self.needs_rate_conversion() { return vec![]; }
        if self.waiting {
            if !idr {return vec![];}
            self.waiting=false;
            if has_vps && has_sps && has_pps { return vec![Unit{pts,data,codec,origin}]; }
            if configured.is_empty() { configured = self.configuration(); }
            configured.extend(data);
            return vec![Unit{pts,data:configured,codec,origin}];
        }
        vec![Unit{pts,data,codec,origin}]
    }
}

// Compare receiver and source aspect ratios relative to the source (the touch canvas).
fn needs_stretch((sw, sh): (u32,u32), (dw, dh): (u32,u32)) -> bool {
    let display = u128::from(dw) * u128::from(sh);
    let source = u128::from(sw) * u128::from(dh);
    display.abs_diff(source) * 100 > source * 3
}

struct JavaCodec { bridge: jni::objects::GlobalRef, output: VideoCodec }
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
                units.push(Unit {pts:u64::from_le_bytes(bytes[..8].try_into().unwrap()),data:bytes[8..].to_vec(),codec:self.output,origin:FrameOrigin::Unknown});
            }Ok(units)
        }).ok_or_else(||io::Error::other("MediaCodec drain failed"))
    }
}
impl Drop for JavaCodec {fn drop(&mut self){let _=crate::with_env(|env|env.call_method(self.bridge.as_obj(),"close","()V",&[]).map(|_|()));}}
pub fn hardware_factory()->Factory {Box::new(|codec,output,(w,h),(tw,th),fps,csd| {
    let listener=crate::TRANSCODER_BRIDGE.read().clone().ok_or_else(||io::Error::other("no codec provider"))?;
    crate::with_env(|env| {
        let bytes=env.byte_array_from_slice(csd)?;
        let obj=env.call_method(listener.as_obj(),"createCarWithTranscoder","(ZZIIIII[B)Lio/github/hypercar/core/CarWithHevcTranscoder;",&[(codec == VideoCodec::Hevc).into(),(output == VideoCodec::Hevc).into(),(w as i32).into(),(h as i32).into(),(tw as i32).into(),(th as i32).into(),(fps as i32).into(),(&bytes).into()])?.l()?;
        let result=env.new_global_ref(&obj)?;env.delete_local_ref(obj)?;env.delete_local_ref(bytes)?;
        Ok(Box::new(JavaCodec{bridge:result,output}) as Box<dyn Codec>)
    }).ok_or_else(||io::Error::other("hardware codec pair unavailable or configure failed"))
})}

#[cfg(test)] mod tests {
    use super::*;
    use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
    const SPS:&[u8]=&[0x67,0x42,0xc0,0x28,0xda,0x01,0xe0,0x08,0x9f,0x97,1,1,1,2];
    const SPS_720: &[u8] = &[0x67,0x42,0x0,0x28,0xf4,0x3,0xc0,0x2d,0xc8];
    const SPS_744: &[u8] = &[0x67,0x42,0x0,0x28,0xf4,0x1,0x40,0xb,0xff,0x2a];
    fn avc(idr:bool)->Vec<u8> { avc_with_sps(SPS,idr) }
    fn avc_with_sps(sps:&[u8],idr:bool)->Vec<u8> {let mut v=vec![0,0,0,1];v.extend(sps);v.extend([0,0,0,1,0x68,1,0,0,0,1,if idr{0x65}else{0x41},0x80]);v}
    struct Fake {output:VideoCodec,fail:bool,pending:Vec<Unit>}
    impl Codec for Fake {
        fn queue(&mut self,_:&[u8],pts:u64)->io::Result<()> {if self.fail {return Err(io::Error::other("hardware failed"));}self.pending.push(Unit{pts,data:vec![],codec:self.output,origin:FrameOrigin::Unknown});Ok(())}
        fn drain(&mut self)->io::Result<Vec<Unit>> {Ok(std::mem::take(&mut self.pending))}
    }
    fn router(enabled:bool,fail:bool)->(Router,Arc<AtomicUsize>) {let n=Arc::new(AtomicUsize::new(0));let count=n.clone();(Router::new(enabled,60,Box::new(move|_,output,source,_,_,_|{assert_eq!(source,(1920,1080));count.fetch_add(1,Ordering::SeqCst);Ok(Box::new(Fake{output,fail,pending:vec![]}))})),n)}
    #[test] fn priority_and_receiver_gate_hardware_creation() {for (on,allowed) in [(false,true),(true,false)] {let(mut r,n)=router(on,false);assert_eq!(r.input(allowed,1,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),0);}}
    #[tokio::test]
    async fn avc_only_receiver_passes_near_ratio_source_unchanged() {
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut receiver, _) = listener.accept().await.unwrap();
        let key = [7;32];
        let mut sender = crate::screen::ScreenSender::new(client.into_split().1,
            crate::screen::ScreenCipher::ChaCha(key), 2560, 744, false);
        sender.set_receiver_size((2736,800));
        let (mut router, opened) = router(true, false);
        router.set_target(sender.receiver_size(), None);
        for (pts, origin, bytes) in [(1, FrameOrigin::Cached, avc_with_sps(SPS_744,true)), (2, FrameOrigin::Live, avc_with_sps(SPS_744,false))] {
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
                assert_eq!(body, crate::screen::build_avcc(SPS_744, &[0x68,1]));
                assert_eq!(crate::h264::avcc_dimensions(&body), Some((2560,744)));
            } else {
                let plain = crate::crypto::chacha_open(&key, &crate::crypto::nonce64(index-1), &body, &header).unwrap();
                assert_eq!(plain, [0,0,0,2,if index == 1 {0x65} else {0x41},0x80]);
            }
        }
    }
    #[test]
    fn unconfirmed_handset_rate_requires_pacing_for_any_declared_ceiling() {
        for ceiling in [30, 60] {
            let mut router = Router::new(true, 30, Box::new(|_, output, _, _, fps, _| {
                assert_eq!(fps, 30);
                Ok(Box::new(Fake { output, fail: false, pending: vec![] }))
            }));
            router.set_target(Some((1920, 1080)), Some(ceiling));
            for (pts, idr) in [(1, true), (2, false), (3, false)] {
                let output = router.input(false, pts, avc(idr), VideoCodec::H264, FrameOrigin::Live);
                assert_eq!(output.len(), 1);
                assert!(output[0].data.is_empty(), "the compressed picture bypassed the decoder");
                assert_eq!(output[0].pts, pts);
            }
            router.set_source_fps(Some(30));
            let frame = avc(true);
            assert_eq!(router.input(false, 4, frame.clone(), VideoCodec::H264, FrameOrigin::Live)[0].data, frame);
        }
    }

    #[test]
    fn handset_above_receiver_limit_is_decoded_before_pacing_even_at_native_size() {
        for failed in [false,true] {
            let (mut router, opens) = router(true,failed);
            router.set_target(Some((1920,1080)), Some(30));
            router.set_source_fps(Some(30));
            let frame = avc(true);
            assert_eq!(router.input(false,1,frame.clone(),VideoCodec::H264, FrameOrigin::Live)[0].data,frame);
            assert_eq!(opens.load(Ordering::SeqCst),0);
            router.set_source_fps(Some(60));
            for (pts, idr) in [(2,true),(3,false)] {
                let frames = router.input(false,pts,avc(idr),VideoCodec::H264, FrameOrigin::Live);
                assert_eq!(frames.len(),usize::from(!failed));
                if !failed { assert!(frames[0].data.is_empty()); } // Fake decoder/encoder output
            }
            assert_eq!(opens.load(Ordering::SeqCst),1);
            router.set_target(Some((1920,1080)), None);
            assert_eq!(router.input(false,4,frame.clone(),VideoCodec::H264, FrameOrigin::Live)[0].data,frame);
        }
    }

    #[test]
    fn stretch_encoder_uses_requested_frame_rate_for_avc_and_hevc() {
        for fps in [12, 30, 60] {
            for hevc_allowed in [false, true] {
                let codec = if hevc_allowed { VideoCodec::Hevc } else { VideoCodec::H264 };
                let mut router = Router::new(true, 60, Box::new(move |input, output, source, target, rate, _| {
                    assert_eq!((input, output, rate), (VideoCodec::H264, codec, fps));
                    assert_eq!((source, target), ((1920,720), (2736,800)));
                    Ok(Box::new(Fake { output, fail:false, pending:vec![] }))
                }));
                router.set_target(Some((2736,800)), Some(fps));
                let output = router.input(hevc_allowed, 90, avc_with_sps(SPS_720,true), VideoCodec::H264, FrameOrigin::Cached);
                assert_eq!(router.output_size(), Some((2736,800)));
                assert_eq!((output[0].pts, output[0].codec, output[0].origin), (90, codec, FrameOrigin::Cached));
            }
        }
    }
    #[test]
    fn every_carwith_profile_fills_oversize_screen_even_when_the_source_aspect_matches() {
        for profile in ["bmw_id85", "bmw_id9", "bmw_id6", "generic", "winplay",
            "audi_mib3", "volkswagen_mib", "mercedes_mbux", "remote_custom_profile"] {
            let mut router = Router::for_carwith_profile(Some(profile), 60,
                Box::new(|_, _, _, _, _, _| panic!("canvas negotiation should not start the codec")));
            for (source, display) in [((2560, 748), (2736, 800)), ((2560, 852), (2880, 960)),
                ((748, 2560), (800, 2736))] {
                router.set_target(Some(display), None);
                assert_eq!(router.output_canvas(source), display, "{profile}");
            }
            router.set_target(Some((2560, 748)), None);
            assert_eq!(router.output_canvas((2500, 730)), (2500, 730));
        }
        let mut router = Router::for_carwith_profile(None, 60,
            Box::new(|_, _, _, _, _, _| panic!("canvas negotiation should not start the codec")));
        router.set_target(Some((2736, 800)), None);
        assert_eq!(router.output_canvas((2560, 748)), (2560, 748));
    }
    #[test]
    fn every_carwith_profile_avc_only_receiver_starts_native_size_transcoding() {
        for profile in ["bmw_id85", "bmw_id9", "bmw_id6", "generic", "winplay",
            "audi_mib3", "volkswagen_mib", "mercedes_mbux", "remote_custom_profile"] {
        let mut router = Router::for_carwith_profile(Some(profile), 60,
            Box::new(|input, output, source, target, _, _| {
                assert_eq!((input, output), (VideoCodec::H264, VideoCodec::H264));
                assert_eq!((source, target), ((2560, 744), (2736, 800)));
                Ok(Box::new(Fake { output, fail: false, pending: vec![] }))
            }));
        router.set_target(Some((2736, 800)), None);
        let frames = router.input(false, 90, avc_with_sps(SPS_744, true), VideoCodec::H264, FrameOrigin::Live);
        assert_eq!(router.output_size(), Some((2736, 800)));
        assert_eq!((frames[0].pts, frames[0].codec, frames[0].origin), (90, VideoCodec::H264, FrameOrigin::Live));
        }
    }

    #[test]
    fn self_drawn_source_never_starts_carwith_scaling_on_an_oversize_display() {
        let mut router = Router::for_carwith_profile(None, 60,
            Box::new(|_, _, _, _, _, _| panic!("self-drawn source must not create a CarWith codec")));
        router.set_target(Some((2736, 800)), None);
        let source = avc_with_sps(SPS_744, true);
        let frames = router.input(false, 91, source.clone(), VideoCodec::H264, FrameOrigin::Cached);
        assert_eq!(router.output_size(), None);
        assert_eq!(frames[0].data, source);
        assert_eq!((frames[0].pts, frames[0].origin), (91, FrameOrigin::Cached));
    }

    #[test]
    fn oversize_non_bmw_hardware_failures_latch_source_passthrough() {
        for fail_creation in [true, false] {
            let opened = Arc::new(AtomicUsize::new(0));
            let count = opened.clone();
            let mut router = Router::for_carwith_profile(Some("audi_mib3"), 60,
                Box::new(move |input, output, source, target, _, _| {
                    assert_eq!((input, output), (VideoCodec::H264, VideoCodec::H264));
                    assert_eq!((source, target), ((2560, 744), (2736, 800)));
                    count.fetch_add(1, Ordering::SeqCst);
                    if fail_creation { Err(io::Error::other("unsupported display size")) }
                    else { Ok(Box::new(Fake { output, fail: true, pending: vec![] })) }
                }));
            router.set_target(Some((2736, 800)), None);
            for pts in [92, 93] {
                let source = avc_with_sps(SPS_744, true);
                let frames = router.input(false, pts, source.clone(), VideoCodec::H264, FrameOrigin::Live);
                assert_eq!(frames.len(), 1);
                assert_eq!(frames[0].data, source);
                assert_eq!(router.output_size(), None);
            }
            assert_eq!(opened.load(Ordering::SeqCst), 1);
        }
    }

    #[test]
    fn stretch_threshold_is_strict_and_source_relative() {
        for dw in [970,1030] { assert!(!needs_stretch((1000,500),(dw,500))); }
        for dw in [969,1031] { assert!(needs_stretch((1000,500),(dw,500))); }
    }

    #[test]
    fn failed_avc_stretch_joins_split_parameters_to_source_idr() {
        let (mut router, opened) = router(true, true);
        router.set_target(Some((2736,800)), None);
        let separate = avc(true);
        let idr_at = separate.len()-6;
        assert!(router.input(false, 8, separate[..idr_at].to_vec(), VideoCodec::H264, FrameOrigin::Cached).is_empty());
        let units = router.input(false, 9, separate[idr_at..].to_vec(), VideoCodec::H264, FrameOrigin::Live);
        assert_eq!((units[0].codec, units[0].origin), (VideoCodec::H264, FrameOrigin::Live));
        assert_eq!(units[0].data, separate);
        assert_eq!(router.output_size(), None);
        router.input(false, 10, avc(true), VideoCodec::H264, FrameOrigin::Live);
        assert_eq!(opened.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn forbidden_hevc_stays_tagged_for_transport_rejection() {
        let (mut router, opened) = router(true, false);
        router.set_target(Some((2736,800)), None);
        let data = vec![0,0,0,1,0x26,1,0x80];
        let units = router.input(false, 10, data.clone(), VideoCodec::Hevc, FrameOrigin::Live);
        assert_eq!(units[0].codec, VideoCodec::Hevc);
        assert_eq!(units[0].data, data);
        assert_eq!(opened.load(Ordering::SeqCst), 0);
    }

    #[test] fn avc_is_converted_only_after_configuration_and_idr() {let(mut r,n)=router(true,false);assert!(r.input(true,1,vec![0,0,0,1,0x41,0x80],VideoCodec::H264,FrameOrigin::Live).is_empty());let out=r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(out[0].codec,VideoCodec::Hevc);assert_eq!(out[0].pts,2);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test] fn runtime_failure_latches_avc_fallback() {let(mut r,n)=router(true,true);let out=r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(out[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);}
    #[test]
    fn first_hevc_idr_uses_current_display_only_when_resize_is_needed() {
        let mut data=Vec::new();
        for nal in [vec![0x40,1,12],crate::hevc::tests::fixture_sps(),vec![0x44,1,0xc0],vec![0x26,1,0x80]] {
            data.extend([0,0,0,1]); data.extend(nal);
        }
        for target in [(1920,1080),(1280,720),(1960,1080),(2400,1080)] {
            let opened=Arc::new(AtomicUsize::new(0)); let count=opened.clone();
            let mut r=Router::new(true,30,Box::new(move |codec,encoded,source,output,_,_| {
                assert_eq!((codec,source,output),(VideoCodec::Hevc,(1920,1080),target));
                count.fetch_add(1,Ordering::SeqCst);
                Ok(Box::new(Fake{output:encoded,fail:false,pending:vec![]}))
            }));
            r.set_target(Some(target), None);
            assert_eq!(r.input(true,1,data.clone(),VideoCodec::Hevc,FrameOrigin::Cached)[0].codec,VideoCodec::Hevc);
            assert_eq!(opened.load(Ordering::SeqCst),usize::from(target==(2400,1080)));
        }
    }
    #[test] fn pause_waits_for_keyframe_and_retains_failure_latch() {let(mut r,n)=router(true,true);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.pause();assert!(r.input(true,2,avc(false),VideoCodec::H264,FrameOrigin::Live).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(n.load(Ordering::SeqCst),1);r.reset();r.input(true,4,avc(true),VideoCodec::H264,FrameOrigin::Live);assert_eq!(n.load(Ordering::SeqCst),2);}
    #[test] fn output_watchdog_waits_for_idr_before_avc_fallback() {let(mut r,_)=router(true,false);r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live);r.submitted=9;r.progress=std::time::Instant::now()-std::time::Duration::from_secs(3);assert!(r.poll().is_empty());assert!(r.input(true,2,avc(false),VideoCodec::H264,FrameOrigin::Live).is_empty());assert_eq!(r.input(true,3,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);}
    #[test] fn unsupported_hardware_falls_back_without_retry_loop() {let count=Arc::new(AtomicUsize::new(0));let n=count.clone();let mut r=Router::new(true,30,Box::new(move|_,_,_,_,_,_|{n.fetch_add(1,Ordering::SeqCst);Err(io::Error::other("unsupported"))}));assert_eq!(r.input(true,1,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(r.input(true,2,avc(true),VideoCodec::H264,FrameOrigin::Live)[0].codec,VideoCodec::H264);assert_eq!(count.load(Ordering::SeqCst),1);}
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
        let mut router = Router::new(true, 30, Box::new(|_, _, _, _, _, _| Ok(Box::new(DelayedCodec(vec![])))));
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
