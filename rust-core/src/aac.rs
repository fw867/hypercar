//! Android platform AAC-LC encoder. One raw access unit per RTP packet (no ADTS/AU header).
//! Protocol reference: CarPToCarL AacLcEncoder/AudioSender; platform calls use NDK MediaCodec.
use std::io;
pub const FRAMES: usize = 1024;

#[cfg(target_os = "android")]
mod platform {
    use super::*;
    use std::ffi::{c_char, c_void};
    #[repr(C)]
    #[derive(Default)]
    struct BufferInfo { offset: i32, size: i32, pts: i64, flags: u32 }
    #[link(name = "mediandk")]
    extern "C" {
        fn AMediaCodec_createEncoderByType(mime: *const c_char) -> *mut c_void;
        fn AMediaCodec_configure(codec: *mut c_void, format: *const c_void, window: *mut c_void, crypto: *mut c_void, flags: u32) -> i32;
        fn AMediaCodec_start(codec: *mut c_void) -> i32;
        fn AMediaCodec_stop(codec: *mut c_void) -> i32;
        fn AMediaCodec_delete(codec: *mut c_void) -> i32;
        fn AMediaCodec_dequeueInputBuffer(codec: *mut c_void, timeout_us: i64) -> isize;
        fn AMediaCodec_getInputBuffer(codec: *mut c_void, index: usize, size: *mut usize) -> *mut u8;
        fn AMediaCodec_queueInputBuffer(codec: *mut c_void, index: usize, offset: libc::off_t, size: usize, pts: u64, flags: u32) -> i32;
        fn AMediaCodec_dequeueOutputBuffer(codec: *mut c_void, info: *mut BufferInfo, timeout_us: i64) -> isize;
        fn AMediaCodec_getOutputBuffer(codec: *mut c_void, index: usize, size: *mut usize) -> *mut u8;
        fn AMediaCodec_releaseOutputBuffer(codec: *mut c_void, index: usize, render: bool) -> i32;
        fn AMediaFormat_new() -> *mut c_void;
        fn AMediaFormat_delete(format: *mut c_void) -> i32;
        fn AMediaFormat_setString(format: *mut c_void, key: *const c_char, value: *const c_char);
        fn AMediaFormat_setInt32(format: *mut c_void, key: *const c_char, value: i32);
    }
    fn status(code: i32, operation: &str) -> io::Result<()> {
        if code == 0 { Ok(()) } else { Err(io::Error::other(format!("AAC {operation}: status={code}"))) }
    }
    pub struct AacEncoder { codec: *mut c_void, submitted: u64, pending: Vec<i16> }
    // Exclusive &mut access; NDK synchronous codec calls can move between Tokio worker threads.
    unsafe impl Send for AacEncoder {}
    impl AacEncoder {
        pub fn new() -> io::Result<Self> {
            // Standalone Android test binaries have no framework-started Binder pool.
            // Codec2 needs it for asynchronous buffer completion; app processes already have one.
            #[cfg(test)] {
                static INIT: std::sync::Once = std::sync::Once::new();
                INIT.call_once(|| unsafe {
                    // Process helpers are exported by the platform library, not NDK API stubs.
                    let library=libc::dlopen(b"libbinder_ndk.so\0".as_ptr().cast(),libc::RTLD_NOW);
                    assert!(!library.is_null(), "Binder library unavailable in standalone tests");
                    let start=libc::dlsym(library,b"ABinderProcess_startThreadPool\0".as_ptr().cast());
                    assert!(!start.is_null(), "Binder thread-pool helper unavailable");
                    std::mem::transmute::<*mut c_void,unsafe extern "C" fn()>(start)();
                });
            }
            unsafe {
                let codec = AMediaCodec_createEncoderByType(b"audio/mp4a-latm\0".as_ptr().cast());
                if codec.is_null() { return Err(io::Error::other("AAC encoder unavailable")); }
                let encoder = Self { codec, submitted: 0, pending: Vec::new() };
                let format = AMediaFormat_new();
                if format.is_null() { return Err(io::Error::other("AAC format allocation failed")); }
                AMediaFormat_setString(format, b"mime\0".as_ptr().cast(), b"audio/mp4a-latm\0".as_ptr().cast());
                for (key, value) in [("sample-rate",48000), ("channel-count",2), ("aac-profile",2),
                    ("bitrate",256000), ("max-input-size",4096)] {
                    let key = std::ffi::CString::new(key).unwrap();
                    AMediaFormat_setInt32(format, key.as_ptr(), value);
                }
                let configured = AMediaCodec_configure(codec, format, std::ptr::null_mut(), std::ptr::null_mut(), 1);
                AMediaFormat_delete(format);
                status(configured, "configure")?;
                status(AMediaCodec_start(codec), "start")?;
                Ok(encoder)
            }
        }
        pub fn encode(&mut self, pcm: &[i16]) -> io::Result<Vec<Vec<u8>>> {
            if pcm.len() % 2 != 0 { return Err(io::Error::other("AAC requires complete stereo samples")); }
            self.pending.extend_from_slice(pcm);
            if self.pending.len() > 48000 { return Err(io::Error::other("AAC input stalled >500ms")); }
            let mut frames = self.drain()?;
            while self.pending.len() >= FRAMES * 2 {
                unsafe {
                    let index = AMediaCodec_dequeueInputBuffer(self.codec, 0);
                    if index == -1 { break; } // Retain input and retry on the next audio tick.
                    if index < 0 { return Err(io::Error::other(format!("AAC input index={index}"))); }
                    let mut capacity = 0;
                    let buffer = AMediaCodec_getInputBuffer(self.codec, index as usize, &mut capacity);
                    if buffer.is_null() || capacity < FRAMES * 4 { return Err(io::Error::other("AAC input buffer too small")); }
                    let output = std::slice::from_raw_parts_mut(buffer, FRAMES * 4);
                    for (sample, bytes) in self.pending[..FRAMES*2].iter().zip(output.chunks_exact_mut(2)) {
                        bytes.copy_from_slice(&sample.to_le_bytes());
                    }
                    status(AMediaCodec_queueInputBuffer(self.codec, index as usize, 0, FRAMES*4,
                        self.submitted * 1_000_000 / 48000, 0), "queue")?;
                    self.pending.drain(..FRAMES*2);
                    self.submitted += FRAMES as u64;
                }
                frames.extend(self.drain()?);
            }
            Ok(frames)
        }
        fn drain(&mut self) -> io::Result<Vec<Vec<u8>>> {
            let mut frames = Vec::new();
            for _ in 0..64 { // Bound work even if a broken vendor codec keeps reporting changes.
                unsafe {
                    let mut info = BufferInfo::default();
                    let index = AMediaCodec_dequeueOutputBuffer(self.codec, &mut info, 0);
                    if index == -1 { return Ok(frames); }
                    if index == -2 || index == -3 { continue; }
                    if index < 0 { return Err(io::Error::other(format!("AAC output index={index}"))); }
                    let mut capacity = 0;
                    let buffer = AMediaCodec_getOutputBuffer(self.codec, index as usize, &mut capacity);
                    let valid = info.offset >= 0 && info.size >= 0 && (info.offset as usize)
                        .checked_add(info.size as usize).is_some_and(|end| end <= capacity);
                    let result = if !valid || (info.size > 0 && buffer.is_null()) {
                        Err(io::Error::other("AAC invalid output bounds"))
                    } else if info.size > 0 && info.flags & 2 == 0 {
                        // Codec config (AudioSpecificConfig) is not an audio access unit.
                        let bytes = std::slice::from_raw_parts(buffer.add(info.offset as usize), info.size as usize);
                        raw_access_unit(bytes).map(|raw| frames.push(raw.to_vec()))
                    } else { Ok(()) };
                    let released = AMediaCodec_releaseOutputBuffer(self.codec, index as usize, false);
                    result?; status(released, "release")?;
                }
            }
            Err(io::Error::other("AAC output did not quiesce"))
        }
    }
    impl Drop for AacEncoder {
        fn drop(&mut self) { unsafe { AMediaCodec_stop(self.codec); AMediaCodec_delete(self.codec); } }
    }
}
#[cfg(target_os = "android")]
pub use platform::AacEncoder;
#[cfg(not(target_os = "android"))]
pub struct AacEncoder;
#[cfg(not(target_os = "android"))]
impl AacEncoder {
    pub fn new() -> io::Result<Self> { Err(io::Error::other("AAC encoder requires Android")) }
    pub fn encode(&mut self, _: &[i16]) -> io::Result<Vec<Vec<u8>>> { Err(io::Error::other("AAC encoder requires Android")) }
}

/// Accept raw output, or a complete, single AAC-LC48k stereo ADTS frame from a vendor codec.
fn raw_access_unit(bytes: &[u8]) -> io::Result<&[u8]> {
    if bytes.is_empty() { return Err(io::Error::other("empty AAC frame")); }
    if bytes.len() < 2 || bytes[0] != 0xff || bytes[1] & 0xf0 != 0xf0 { return Ok(bytes); }
    if bytes.len() < 7 { return Err(io::Error::other("truncated ADTS header")); }
    let header = if bytes[1] & 1 != 0 { 7 } else { 9 };
    let size = ((bytes[3] as usize & 3) << 11) | ((bytes[4] as usize) << 3) | (bytes[5] as usize >> 5);
    let channels = ((bytes[2] & 1) << 2) | (bytes[3] >> 6);
    if bytes[1] & 6 != 0 || bytes[2] >> 6 != 1 || (bytes[2] >> 2) & 15 != 3 || channels != 2 ||
        bytes[6] & 3 != 0 || size != bytes.len() || size <= header {
        return Err(io::Error::other("unsupported/malformed ADTS output"));
    }
    Ok(&bytes[header..])
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn raw_frames_are_never_byte_swapped() { assert_eq!(raw_access_unit(&[0x21,0x10,0x56]).unwrap(), &[0x21,0x10,0x56]); }
    #[test] fn strips_only_complete_single_lc48_stereo_adts() {
        let frame = [0xff,0xf1,0x4c,0x80,0x01,0x3f,0xfc,0x21,0x10];
        assert_eq!(raw_access_unit(&frame).unwrap(), &[0x21,0x10]);
        assert!(raw_access_unit(&frame[..8]).is_err());
        let mut invalid = frame; invalid[6] |= 1; assert!(raw_access_unit(&invalid).is_err());
    }
    #[cfg(target_os="android")]
    #[test] fn device_encoder_emits_raw_aac_at_1024_frame_cadence() {
        let mut encoder=AacEncoder::new().unwrap();
        let pcm:Vec<i16>=(0..2048).map(|i| ((i as f32 * 0.0576).sin()*6000.) as i16).collect();
        let mut count=0;
        for _ in 0..24 {
            for frame in encoder.encode(&pcm).unwrap() { assert!(!frame.is_empty()); assert!(frame.len()<1400); count+=1; }
            std::thread::sleep(std::time::Duration::from_millis(22));
        }
        assert!(count>=20, "encoded only {count} AAC access units");
    }
}
