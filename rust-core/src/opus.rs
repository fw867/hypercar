//! Platform Opus decoder for the microphone probe. PCM exists only long enough to measure levels.
use std::io;
#[cfg(target_os="android")]
mod android {
    use super::*;
    use std::ffi::{c_char,c_void};
    #[repr(C)] #[derive(Default)]
    struct BufferInfo { offset:i32,size:i32,pts:i64,flags:u32 }
    #[link(name="mediandk")] extern "C" {
        fn AMediaCodec_createDecoderByType(mime:*const c_char)->*mut c_void;
        fn AMediaCodec_configure(c:*mut c_void,f:*const c_void,w:*mut c_void,k:*mut c_void,flags:u32)->i32;
        fn AMediaCodec_start(c:*mut c_void)->i32;
        fn AMediaCodec_stop(c:*mut c_void)->i32;
        fn AMediaCodec_delete(c:*mut c_void)->i32;
        fn AMediaCodec_dequeueInputBuffer(c:*mut c_void,timeout:i64)->isize;
        fn AMediaCodec_getInputBuffer(c:*mut c_void,index:usize,size:*mut usize)->*mut u8;
        fn AMediaCodec_queueInputBuffer(c:*mut c_void,index:usize,offset:libc::off_t,size:usize,pts:u64,flags:u32)->i32;
        fn AMediaCodec_dequeueOutputBuffer(c:*mut c_void,info:*mut BufferInfo,timeout:i64)->isize;
        fn AMediaCodec_getOutputBuffer(c:*mut c_void,index:usize,size:*mut usize)->*mut u8;
        fn AMediaCodec_releaseOutputBuffer(c:*mut c_void,index:usize,render:bool)->i32;
        fn AMediaCodec_getOutputFormat(c:*mut c_void)->*mut c_void;
        fn AMediaFormat_new()->*mut c_void;
        fn AMediaFormat_delete(f:*mut c_void)->i32;
        fn AMediaFormat_setString(f:*mut c_void,k:*const c_char,v:*const c_char);
        fn AMediaFormat_setInt32(f:*mut c_void,k:*const c_char,v:i32);
        fn AMediaFormat_setBuffer(f:*mut c_void,k:*const c_char,v:*const c_void,size:usize);
        fn AMediaFormat_getInt32(f:*mut c_void,k:*const c_char,v:*mut i32)->bool;
    }
    fn check(code:i32)->io::Result<()> {if code==0 {Ok(())}else{Err(io::Error::other(format!("Opus codec status={code}")))}}
    pub struct OpusDecoder {codec:*mut c_void,pub rate:u32,pts:u64}
    // All codec use is synchronous and exclusive through &mut self.
    unsafe impl Send for OpusDecoder {}
    impl OpusDecoder {
        pub fn new(rate:u32)->io::Result<Self> {
            #[cfg(test)] {
                static INIT:std::sync::Once=std::sync::Once::new();
                INIT.call_once(||unsafe{
                    let library=libc::dlopen(c"libbinder_ndk.so".as_ptr(),libc::RTLD_NOW);
                    assert!(!library.is_null());
                    let start=libc::dlsym(library,c"ABinderProcess_startThreadPool".as_ptr());
                    assert!(!start.is_null());
                    std::mem::transmute::<*mut c_void,unsafe extern "C" fn()>(start)();
                });
            }
            unsafe {
            let codec=AMediaCodec_createDecoderByType(c"audio/opus".as_ptr());
            if codec.is_null(){return Err(io::Error::other("Opus decoder unavailable"));}
            let mut decoder=Self{codec,rate,pts:0};
            let f=AMediaFormat_new();if f.is_null(){return Err(io::Error::other("Opus format allocation"));}
            AMediaFormat_setString(f,c"mime".as_ptr(),c"audio/opus".as_ptr());
            AMediaFormat_setInt32(f,c"sample-rate".as_ptr(),rate as i32);
            AMediaFormat_setInt32(f,c"channel-count".as_ptr(),1);
            AMediaFormat_setInt32(f,c"pcm-encoding".as_ptr(),2);
            let mut head=b"OpusHead\x01\x01\0\0".to_vec();head.extend(rate.to_le_bytes());head.extend([0,0,0]);
            let delay=0u64.to_ne_bytes();let preroll=80_000_000u64.to_ne_bytes();
            for (key,bytes) in [(c"csd-0",head.as_slice()),(c"csd-1",delay.as_slice()),(c"csd-2",preroll.as_slice())] {
                AMediaFormat_setBuffer(f,key.as_ptr(),bytes.as_ptr() as _,bytes.len());
            }
            let result=AMediaCodec_configure(codec,f,std::ptr::null_mut(),std::ptr::null_mut(),0);
            AMediaFormat_delete(f);check(result)?;check(AMediaCodec_start(codec))?;
            decoder.rate=rate;Ok(decoder)
        }}
        pub fn decode(&mut self,packet:&[u8])->io::Result<Vec<i16>> {unsafe {
            if packet.is_empty()||packet.len()>4096{return Err(io::Error::other("Opus packet size"));}
            let index=AMediaCodec_dequeueInputBuffer(self.codec,1000);
            if index<0{return Err(io::Error::new(io::ErrorKind::WouldBlock,"Opus input busy"));}
            let mut cap=0;let input=AMediaCodec_getInputBuffer(self.codec,index as _,&mut cap);
            if input.is_null()||cap<packet.len(){return Err(io::Error::other("Opus input capacity"));}
            std::ptr::copy_nonoverlapping(packet.as_ptr(),input,packet.len());
            check(AMediaCodec_queueInputBuffer(self.codec,index as _,0,packet.len(),self.pts,0))?;
            self.pts+=20_000;
            let mut pcm=Vec::new();
            for _ in 0..16 {
                let mut info=BufferInfo::default();
                let index=AMediaCodec_dequeueOutputBuffer(self.codec,&mut info,1000);
                if index == -1 {break;}
                if index == -3 {continue;}
                if index == -2 {
                    let f=AMediaCodec_getOutputFormat(self.codec);
                    if f.is_null(){return Err(io::Error::other("Opus output format"));}
                    let(mut rate,mut channels,mut encoding)=(self.rate as i32,1,2);
                    AMediaFormat_getInt32(f,c"sample-rate".as_ptr(),&mut rate);
                    AMediaFormat_getInt32(f,c"channel-count".as_ptr(),&mut channels);
                    AMediaFormat_getInt32(f,c"pcm-encoding".as_ptr(),&mut encoding);
                    AMediaFormat_delete(f);
                    if !(8000..=48000).contains(&rate)||channels!=1||encoding!=2{return Err(io::Error::other("Opus requires mono PCM16 output"));}
                    self.rate=rate as u32;continue;
                }
                if index<0{return Err(io::Error::other("Opus output dequeue"));}
                let mut cap=0;let out=AMediaCodec_getOutputBuffer(self.codec,index as _,&mut cap);
                let valid=info.offset>=0&&info.size>=0&&(info.offset as usize).checked_add(info.size as usize).is_some_and(|n|n<=cap)&&info.size%2==0&&info.size<=48000;
                if valid&&info.size>0&&!out.is_null() {
                    let bytes=std::slice::from_raw_parts(out.add(info.offset as usize),info.size as usize);
                    pcm.extend(bytes.chunks_exact(2).map(|b|i16::from_ne_bytes([b[0],b[1]])));
                }
                check(AMediaCodec_releaseOutputBuffer(self.codec,index as _,false))?;
                if !valid{return Err(io::Error::other("Opus output bounds"));}
            }
            Ok(pcm)
        }}
    }
    impl Drop for OpusDecoder {fn drop(&mut self){unsafe{AMediaCodec_stop(self.codec);AMediaCodec_delete(self.codec);}}}
}
#[cfg(target_os="android")] pub use android::OpusDecoder;
#[cfg(not(target_os="android"))]
pub struct OpusDecoder {pub rate:u32}
#[cfg(not(target_os="android"))]
impl OpusDecoder {
    pub fn new(_:u32)->io::Result<Self>{Err(io::Error::other("Android Opus decoder required"))}
    pub fn decode(&mut self,_:&[u8])->io::Result<Vec<i16>>{Err(io::Error::other("Android Opus decoder required"))}
}
#[cfg(all(test,target_os="android"))]
mod tests {
    use super::*;
    #[test] fn android_opus_silence_decodes_without_recording_or_microphone_permission(){
        let mut decoder=OpusDecoder::new(16000).unwrap();let mut count=0;
        for _ in 0..12 {let pcm=decoder.decode(&[0xf8,0xff,0xfe]).unwrap();count+=pcm.len();}
        assert!(count>0);assert!([16000,24000,48000].contains(&decoder.rate));
    }
}
