//! Android AAC-ELD for CarPlay telephony. Each packet carries one raw 480-sample AU.

pub(crate) const SAMPLES_PER_AU: usize = 480;
pub(crate) const PCM_BYTES_PER_AU: usize = SAMPLES_PER_AU * size_of::<i16>();

#[cfg(any(target_os = "android", test))]
fn decoder_asc(sample_rate: u32) -> Result<[u8; 4], String> {
    // AOT escape + 7 (AOT 39), indexed rate, one channel, 480-sample frame flag,
    // no resilience or SBR, extension terminator, and epConfig 0.
    match sample_rate {
        16_000 => Ok([0xf8, 0xf0, 0x30, 0x00]),
        24_000 => Ok([0xf8, 0xec, 0x30, 0x00]),
        _ => Err(format!("AAC-ELD unsupported call rate {sample_rate} Hz")),
    }
}

#[cfg(any(target_os = "android", test))]
fn validate_asc(asc: &[u8], sample_rate: u32) -> Result<(), String> {
    let mut bits = asc
        .iter()
        .flat_map(|byte| (0..8).rev().map(move |bit| (byte >> bit) & 1));
    let mut read = |count| {
        (0..count).try_fold(0u32, |value, _| {
            bits.next()
                .map(|bit| (value << 1) | u32::from(bit))
                .ok_or_else(|| "AAC-ELD truncated AudioSpecificConfig".to_string())
        })
    };
    if read(5)? != 31 || read(6)? != 7 {
        return Err("AAC-ELD encoder did not select audio object type 39".into());
    }
    let rate = match read(4)? {
        6 => 24_000,
        8 => 16_000,
        15 => read(24)?,
        _ => return Err("AAC-ELD encoder selected an unsupported sampling frequency".into()),
    };
    if rate != sample_rate || read(4)? != 1 {
        return Err("AAC-ELD encoder did not select the requested mono rate".into());
    }
    if read(1)? != 1 {
        return Err("AAC-ELD encoder selected 512 samples per AU, expected 480".into());
    }
    // The stream does not signal any codec-specific options to the receiver.
    if read(4)? != 0 || read(4)? != 0 || read(2)? != 0 || bits.any(|bit| bit != 0) {
        return Err("AAC-ELD encoder selected unsupported SBR, resilience, or extensions".into());
    }
    Ok(())
}

#[cfg(any(target_os = "android", test))]
fn validate_pcm_frame(size: usize) -> Result<(), String> {
    if size != PCM_BYTES_PER_AU {
        return Err(format!(
            "AAC-ELD expected {PCM_BYTES_PER_AU} bytes of mono PCM16 per AU, got {size}"
        ));
    }
    Ok(())
}

#[cfg(target_os = "android")]
pub(crate) use platform::{CallAacEld, Decoder, Encoder};

#[cfg(not(target_os = "android"))]
pub(crate) use unsupported::{CallAacEld, Decoder, Encoder};

#[cfg(target_os = "android")]
mod platform {
    use super::*;
    use std::{
        ffi::{c_char, c_void, CStr, CString},
        ptr::{self, NonNull},
        slice, thread,
        time::{Duration, Instant},
    };

    const MIME: &CStr = c"audio/mp4a-latm";
    const MAX_AU_BYTES: usize = 4_096;
    const MAX_ASC_BYTES: usize = 64;
    const TRY_AGAIN: isize = -1;
    const FORMAT_CHANGED: isize = -2;
    const BUFFERS_CHANGED: isize = -3;
    const CODEC_CONFIG: u32 = 2;
    const END_OF_STREAM: u32 = 4;
    const PARTIAL_FRAME: u32 = 8;
    const PROBE_LIMIT: Duration = Duration::from_millis(550);
    const PROBE_TICK: Duration = Duration::from_millis(2);
    const PROBE_PTS: u64 = 1_000_000;
    const PROBE_ENCODED: usize = 6;
    const PROBE_DECODED: usize = 4;
    const PROBE_MAX_INPUT: usize = 12;

    type Frame = (u64, Vec<u8>);

    #[repr(C)]
    #[derive(Default)]
    struct BufferInfo {
        offset: i32,
        size: i32,
        pts: i64,
        flags: u32,
    }

    #[link(name = "mediandk")]
    extern "C" {
        fn AMediaCodec_createEncoderByType(mime: *const c_char) -> *mut c_void;
        fn AMediaCodec_createDecoderByType(mime: *const c_char) -> *mut c_void;
        fn AMediaCodec_createCodecByName(name: *const c_char) -> *mut c_void;
        fn AMediaCodec_configure(
            codec: *mut c_void,
            format: *const c_void,
            window: *mut c_void,
            crypto: *mut c_void,
            flags: u32,
        ) -> i32;
        fn AMediaCodec_start(codec: *mut c_void) -> i32;
        fn AMediaCodec_stop(codec: *mut c_void) -> i32;
        fn AMediaCodec_delete(codec: *mut c_void) -> i32;
        fn AMediaCodec_dequeueInputBuffer(codec: *mut c_void, timeout_us: i64) -> isize;
        fn AMediaCodec_getInputBuffer(
            codec: *mut c_void,
            index: usize,
            size: *mut usize,
        ) -> *mut u8;
        fn AMediaCodec_queueInputBuffer(
            codec: *mut c_void,
            index: usize,
            offset: libc::off_t,
            size: usize,
            pts: u64,
            flags: u32,
        ) -> i32;
        fn AMediaCodec_dequeueOutputBuffer(
            codec: *mut c_void,
            info: *mut BufferInfo,
            timeout_us: i64,
        ) -> isize;
        fn AMediaCodec_getOutputBuffer(
            codec: *mut c_void,
            index: usize,
            size: *mut usize,
        ) -> *mut u8;
        fn AMediaCodec_releaseOutputBuffer(codec: *mut c_void, index: usize, render: bool) -> i32;
        fn AMediaCodec_getOutputFormat(codec: *mut c_void) -> *mut c_void;
        fn AMediaFormat_new() -> *mut c_void;
        fn AMediaFormat_delete(format: *mut c_void) -> i32;
        fn AMediaFormat_setString(format: *mut c_void, key: *const c_char, value: *const c_char);
        fn AMediaFormat_getInt32(format: *mut c_void, key: *const c_char, value: *mut i32) -> bool;
        fn AMediaFormat_setInt32(format: *mut c_void, key: *const c_char, value: i32);
        fn AMediaFormat_getBuffer(
            format: *mut c_void,
            key: *const c_char,
            data: *mut *mut c_void,
            size: *mut usize,
        ) -> bool;
        fn AMediaFormat_setBuffer(
            format: *mut c_void,
            key: *const c_char,
            data: *const c_void,
            size: usize,
        );
    }

    fn status(operation: &str, value: i32) -> Result<(), String> {
        if value == 0 {
            return Ok(());
        }
        Err(format!("AAC-ELD {operation}: MediaCodec status {value}"))
    }

    struct MediaFormat(NonNull<c_void>);

    impl MediaFormat {
        fn set_i32(&self, key: &CStr, value: i32) {
            unsafe {
                AMediaFormat_setInt32(self.0.as_ptr(), key.as_ptr(), value);
            }
        }

        fn get_i32(&self, key: &CStr) -> Option<i32> {
            let mut value = 0;
            unsafe { AMediaFormat_getInt32(self.0.as_ptr(), key.as_ptr(), &mut value) }
                .then_some(value)
        }

        fn asc(&self) -> Result<Option<&[u8]>, String> {
            let mut ptr = ptr::null_mut();
            let mut size = 0;
            unsafe {
                if !AMediaFormat_getBuffer(self.0.as_ptr(), c"csd-0".as_ptr(), &mut ptr, &mut size)
                {
                    return Ok(None);
                }
                if ptr.is_null() || !(1..=MAX_ASC_BYTES).contains(&size) {
                    return Err("AAC-ELD invalid codec configuration buffer".into());
                }
                // The borrowed bytes are owned by the MediaFormat and never outlive it.
                Ok(Some(slice::from_raw_parts(ptr.cast::<u8>(), size)))
            }
        }
    }

    impl Drop for MediaFormat {
        fn drop(&mut self) {
            unsafe {
                AMediaFormat_delete(self.0.as_ptr());
            }
        }
    }

    enum Output {
        Format(MediaFormat),
        Config(Vec<u8>),
        Frame(Frame),
        Empty,
    }

    struct MediaCodec {
        ptr: NonNull<c_void>,
        started: bool,
    }

    // Codec calls are synchronous and thread-safe. Each endpoint owns its codec exclusively,
    // and no native buffer is retained across Rust calls or accessed after release.
    unsafe impl Send for MediaCodec {}

    impl MediaCodec {
        fn open(sample_rate: u32, encode: bool, name: Option<&CStr>) -> Result<Self, String> {
            let role = if encode { "encoder" } else { "decoder" };
            let asc = decoder_asc(sample_rate)?;
            unsafe {
                let ptr = match name {
                    Some(name) => AMediaCodec_createCodecByName(name.as_ptr()),
                    None if encode => AMediaCodec_createEncoderByType(MIME.as_ptr()),
                    None => AMediaCodec_createDecoderByType(MIME.as_ptr()),
                };
                let ptr = NonNull::new(ptr).ok_or_else(|| format!("AAC-ELD {role} unavailable"))?;
                let mut codec = Self {
                    ptr,
                    started: false,
                };
                let format = MediaFormat(
                    NonNull::new(AMediaFormat_new()).ok_or("AAC-ELD format allocation failed")?,
                );
                AMediaFormat_setString(format.0.as_ptr(), c"mime".as_ptr(), MIME.as_ptr());
                format.set_i32(c"sample-rate", sample_rate as i32);
                format.set_i32(c"channel-count", 1);
                format.set_i32(c"aac-profile", 39);
                format.set_i32(c"pcm-encoding", 2); // Android AudioFormat.ENCODING_PCM_16BIT
                format.set_i32(c"max-input-size", MAX_AU_BYTES as i32);
                if encode {
                    format.set_i32(c"bitrate", 24_000);
                    format.set_i32(c"aac-sbr-mode", 0);
                } else {
                    // CarPlay specifies the bitstream through the negotiated audio format.
                    // The decoder uses a local AudioSpecificConfig for that format.
                    AMediaFormat_setBuffer(
                        format.0.as_ptr(),
                        c"csd-0".as_ptr(),
                        asc.as_ptr().cast(),
                        asc.len(),
                    );
                }
                status(
                    &format!("configure {role}"),
                    AMediaCodec_configure(
                        ptr.as_ptr(),
                        format.0.as_ptr(),
                        ptr::null_mut(),
                        ptr::null_mut(),
                        u32::from(encode),
                    ),
                )?;
                status(&format!("start {role}"), AMediaCodec_start(ptr.as_ptr()))?;
                codec.started = true;
                Ok(codec)
            }
        }

        fn name(&self) -> Result<CString, String> {
            type GetName = unsafe extern "C" fn(*mut c_void, *mut *mut c_char) -> i32;
            type ReleaseName = unsafe extern "C" fn(*mut c_void, *mut c_char);
            let mut name = ptr::null_mut();
            unsafe {
                // The app runs on API 31+, while cargo-ndk links against API 21 stubs.
                // Resolve the API 28 name functions from the already loaded libmediandk.
                let get_name = libc::dlsym(libc::RTLD_DEFAULT, c"AMediaCodec_getName".as_ptr());
                let release_name =
                    libc::dlsym(libc::RTLD_DEFAULT, c"AMediaCodec_releaseName".as_ptr());
                if get_name.is_null() || release_name.is_null() {
                    return Err("AAC-ELD MediaCodec component names unavailable".into());
                }
                let get_name = std::mem::transmute::<*mut c_void, GetName>(get_name);
                let release_name = std::mem::transmute::<*mut c_void, ReleaseName>(release_name);
                status("get component name", get_name(self.ptr.as_ptr(), &mut name))?;
                if name.is_null() {
                    return Err("AAC-ELD codec returned an empty component name".into());
                }
                let owned = CStr::from_ptr(name).to_owned();
                release_name(self.ptr.as_ptr(), name);
                Ok(owned)
            }
        }

        fn queue(&mut self, data: &[u8], pts: u64) -> Result<bool, String> {
            if pts > i64::MAX as u64 {
                return Err("AAC-ELD input timestamp is outside MediaCodec's range".into());
            }
            let codec = self.ptr.as_ptr();
            unsafe {
                let index = AMediaCodec_dequeueInputBuffer(codec, 0);
                if index == TRY_AGAIN {
                    return Ok(false);
                }
                let index = usize::try_from(index)
                    .map_err(|_| format!("AAC-ELD dequeue input: {index}"))?;
                let mut capacity = 0;
                let buffer = AMediaCodec_getInputBuffer(codec, index, &mut capacity);
                if buffer.is_null() || capacity < data.len() {
                    return Err("AAC-ELD MediaCodec input buffer is too small".into());
                }
                ptr::copy_nonoverlapping(data.as_ptr(), buffer, data.len());
                status(
                    "queue input",
                    AMediaCodec_queueInputBuffer(codec, index, 0, data.len(), pts, 0),
                )?;
            }
            Ok(true)
        }

        fn output(&mut self) -> Result<Option<Output>, String> {
            let codec = self.ptr.as_ptr();
            let mut info = BufferInfo::default();
            unsafe {
                let index = AMediaCodec_dequeueOutputBuffer(codec, &mut info, 0);
                match index {
                    TRY_AGAIN => return Ok(None),
                    FORMAT_CHANGED => {
                        let ptr = NonNull::new(AMediaCodec_getOutputFormat(codec))
                            .ok_or("AAC-ELD missing MediaCodec output format")?;
                        return Ok(Some(Output::Format(MediaFormat(ptr))));
                    }
                    BUFFERS_CHANGED => return Ok(Some(Output::Empty)),
                    _ => {}
                }
                let index = usize::try_from(index)
                    .map_err(|_| format!("AAC-ELD dequeue output: {index}"))?;
                let result = self.copy_output(index, &info);
                status(
                    "release output",
                    AMediaCodec_releaseOutputBuffer(codec, index, false),
                )?;
                result.map(Some)
            }
        }

        fn copy_output(&self, index: usize, info: &BufferInfo) -> Result<Output, String> {
            if info.flags & (END_OF_STREAM | PARTIAL_FRAME) != 0 {
                return Err("AAC-ELD unexpected end of stream or partial output frame".into());
            }
            if info.size == 0 {
                return Ok(Output::Empty);
            }
            let config = info.flags & CODEC_CONFIG != 0;
            let limit = if config { MAX_ASC_BYTES } else { MAX_AU_BYTES };
            let size = usize::try_from(info.size)
                .ok()
                .filter(|size| *size <= limit)
                .ok_or("AAC-ELD invalid MediaCodec output size")?;
            if info.offset < 0 {
                return Err("AAC-ELD invalid MediaCodec output offset".into());
            }
            // The NDK returns a pointer to the payload already advanced to its offset.
            // BufferInfo.size is authoritative on all supported APIs, including API <= 35.
            let buffer =
                unsafe { AMediaCodec_getOutputBuffer(self.ptr.as_ptr(), index, ptr::null_mut()) };
            if buffer.is_null() {
                return Err("AAC-ELD missing MediaCodec output buffer".into());
            }
            let payload = unsafe { slice::from_raw_parts(buffer, size) }.to_vec();
            if config {
                return Ok(Output::Config(payload));
            }
            let pts = u64::try_from(info.pts)
                .map_err(|_| "AAC-ELD negative MediaCodec output timestamp")?;
            Ok(Output::Frame((pts, payload)))
        }
    }

    impl Drop for MediaCodec {
        fn drop(&mut self) {
            unsafe {
                if self.started {
                    AMediaCodec_stop(self.ptr.as_ptr());
                }
                AMediaCodec_delete(self.ptr.as_ptr());
            }
        }
    }

    fn check_output_format(format: &MediaFormat, sample_rate: u32) -> Result<(), String> {
        if format.get_i32(c"sample-rate") != Some(sample_rate as i32)
            || format.get_i32(c"channel-count") != Some(1)
        {
            return Err("AAC-ELD wrong MediaCodec mono output rate or channel count".into());
        }
        Ok(())
    }

    pub(crate) struct Encoder {
        codec: MediaCodec,
        sample_rate: u32,
        asc_checked: bool,
    }

    impl Encoder {
        fn open(sample_rate: u32, name: Option<&CStr>) -> Result<Self, String> {
            Ok(Self {
                codec: MediaCodec::open(sample_rate, true, name)?,
                sample_rate,
                asc_checked: false,
            })
        }

        /// `false` means MediaCodec has not taken this PCM frame. Drain and retry it.
        pub(crate) fn try_queue_pcm(&mut self, pcm_le: &[u8], pts_us: u64) -> Result<bool, String> {
            validate_pcm_frame(pcm_le.len())?;
            self.codec.queue(pcm_le, pts_us)
        }

        pub(crate) fn poll_encoded(&mut self) -> Result<Option<Frame>, String> {
            for _ in 0..8 {
                match self.codec.output()? {
                    None => return Ok(None),
                    Some(Output::Empty) => {}
                    Some(Output::Format(format)) => {
                        check_output_format(&format, self.sample_rate)?;
                        if let Some(asc) = format.asc()? {
                            validate_asc(asc, self.sample_rate)?;
                            self.asc_checked = true;
                        }
                    }
                    Some(Output::Config(asc)) => {
                        validate_asc(&asc, self.sample_rate)?;
                        self.asc_checked = true;
                    }
                    Some(Output::Frame(frame)) => {
                        if !self.asc_checked {
                            return Err(
                                "AAC-ELD encoder emitted audio before a valid AudioSpecificConfig"
                                    .into(),
                            );
                        }
                        return Ok(Some(frame));
                    }
                }
            }
            Ok(None)
        }
    }

    pub(crate) struct Decoder {
        codec: MediaCodec,
        sample_rate: u32,
        format_checked: bool,
    }

    impl Decoder {
        fn open(sample_rate: u32, name: Option<&CStr>) -> Result<Self, String> {
            Ok(Self {
                codec: MediaCodec::open(sample_rate, false, name)?,
                sample_rate,
                format_checked: false,
            })
        }

        /// `false` means MediaCodec has not taken this AU. Drain and retry it.
        pub(crate) fn try_queue_au(&mut self, au: &[u8], pts_us: u64) -> Result<bool, String> {
            if !(1..=MAX_AU_BYTES).contains(&au.len()) {
                return Err("AAC-ELD invalid raw access unit size".into());
            }
            self.codec.queue(au, pts_us)
        }

        pub(crate) fn poll_decoded(&mut self) -> Result<Option<Frame>, String> {
            for _ in 0..8 {
                match self.codec.output()? {
                    None => return Ok(None),
                    Some(Output::Empty) => {}
                    Some(Output::Format(format)) => {
                        check_output_format(&format, self.sample_rate)?;
                        if format
                            .get_i32(c"pcm-encoding")
                            .is_some_and(|encoding| encoding != 2)
                        {
                            return Err("AAC-ELD MediaCodec decoder did not output PCM16".into());
                        }
                        self.format_checked = true;
                    }
                    Some(Output::Config(_)) => {
                        return Err(
                            "AAC-ELD decoder unexpectedly emitted codec configuration".into()
                        )
                    }
                    Some(Output::Frame(frame)) => {
                        if !self.format_checked {
                            return Err(
                                "AAC-ELD decoder emitted audio before its output format".into()
                            );
                        }
                        validate_pcm_frame(frame.1.len())?;
                        return Ok(Some(frame));
                    }
                }
            }
            Ok(None)
        }
    }

    pub(crate) struct CallAacEld(Encoder, Decoder);

    impl CallAacEld {
        /// Configure and verify both directions before allowing an ELD telephony offer.
        /// Run on a blocking thread. Only the startup probe waits for codec buffers.
        pub(crate) fn probe(sample_rate: u32) -> Result<Self, String> {
            let deadline = Instant::now() + PROBE_LIMIT;
            let mut encoder = Encoder::open(sample_rate, None)?;
            let encoded = probe_encode(&mut encoder, deadline)?;
            let mut decoder = Decoder::open(sample_rate, None)?;
            probe_decode(&mut decoder, &encoded, deadline)?;

            // Fresh instances avoid leaving synthetic probe audio in codec state. Reopen the
            // same components and recheck their actual config/PCM output during the call.
            let encoder_name = encoder.codec.name()?;
            let decoder_name = decoder.codec.name()?;
            drop((encoder, decoder));
            let encoder = Encoder::open(sample_rate, Some(&encoder_name))?;
            let decoder = Decoder::open(sample_rate, Some(&decoder_name))?;
            Ok(Self(encoder, decoder))
        }

        pub(crate) fn into_parts(self) -> (Encoder, Decoder) {
            (self.0, self.1)
        }
    }

    fn probe_encode(encoder: &mut Encoder, deadline: Instant) -> Result<Vec<Frame>, String> {
        let sample_rate = encoder.sample_rate;
        let period = SAMPLES_PER_AU as u64 * 1_000_000 / u64::from(sample_rate);
        let mut pcm = [0; PCM_BYTES_PER_AU];
        for (index, bytes) in pcm.chunks_exact_mut(2).enumerate() {
            let sample = (6_000.0
                * (std::f32::consts::TAU * 400.0 * index as f32 / sample_rate as f32).sin())
                as i16;
            bytes.copy_from_slice(&sample.to_le_bytes());
        }
        let mut submitted = 0;
        let mut output = Vec::with_capacity(PROBE_ENCODED);
        while Instant::now() < deadline {
            let queued = submitted < PROBE_MAX_INPUT
                && encoder.try_queue_pcm(&pcm, PROBE_PTS + submitted as u64 * period)?;
            submitted += usize::from(queued);
            if let Some(frame) = encoder.poll_encoded()? {
                if frame.0 != PROBE_PTS + output.len() as u64 * period {
                    return Err(
                        "AAC-ELD encoded access units do not follow 480-sample timestamps".into(),
                    );
                }
                output.push(frame);
                if output.len() == PROBE_ENCODED {
                    return Ok(output);
                }
            } else if !queued {
                thread::sleep(PROBE_TICK);
            }
        }
        Err("AAC-ELD encoder probe timed out waiting for 480-sample access units".into())
    }

    fn probe_decode(
        decoder: &mut Decoder,
        encoded: &[Frame],
        deadline: Instant,
    ) -> Result<(), String> {
        let mut submitted = 0;
        let mut received = 0;
        let mut non_silent = false;
        while Instant::now() < deadline {
            let queued = match encoded.get(submitted) {
                Some((pts, au)) => decoder.try_queue_au(au, *pts)?,
                None => false,
            };
            submitted += usize::from(queued);
            if let Some((pts, pcm)) = decoder.poll_decoded()? {
                if pts != encoded[received].0 {
                    return Err(
                        "AAC-ELD decoder output does not follow the access unit timestamps".into(),
                    );
                }
                non_silent |= pcm
                    .chunks_exact(2)
                    .any(|bytes| i16::from_le_bytes([bytes[0], bytes[1]]).unsigned_abs() > 64);
                received += 1;
                if received == PROBE_DECODED {
                    return non_silent.then_some(()).ok_or_else(|| {
                        "AAC-ELD roundtrip decoded the test tone as silence".into()
                    });
                }
            } else if !queued {
                thread::sleep(PROBE_TICK);
            }
        }
        Err("AAC-ELD decoder probe timed out waiting for 480-sample PCM frames".into())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn device_roundtrips_480_sample_eld_or_rejects_incompatible_encoder() {
            // Standalone test binaries need a Binder pool for Codec2 callbacks.
            unsafe {
                let binder = libc::dlopen(c"libbinder_ndk.so".as_ptr(), libc::RTLD_NOW);
                assert!(!binder.is_null(), "Binder NDK library unavailable");
                let start = libc::dlsym(binder, c"ABinderProcess_startThreadPool".as_ptr());
                assert!(!start.is_null(), "Binder pool entry point unavailable");
                std::mem::transmute::<*mut c_void, unsafe extern "C" fn()>(start)();
            }
            for sample_rate in [16_000, 24_000] {
                match CallAacEld::probe(sample_rate) {
                    Ok(codec) => {
                        let (mut encoder, mut decoder) = codec.into_parts();
                        let deadline = Instant::now() + PROBE_LIMIT;
                        let encoded = probe_encode(&mut encoder, deadline).unwrap();
                        probe_decode(&mut decoder, &encoded, deadline).unwrap();
                    }
                    Err(error) => {
                        assert!(
                            error.contains("selected 512 samples")
                                || error.contains("did not select audio object type 39")
                                || error.contains("encoder unavailable")
                                || error.contains("configure encoder: MediaCodec status"),
                            "{sample_rate} Hz: {error}"
                        );
                        eprintln!("{sample_rate} Hz: {error}");
                    }
                }
            }
        }
    }
}

#[cfg(not(target_os = "android"))]
mod unsupported {
    const ERROR: &str = "AAC-ELD MediaCodec requires Android";

    pub(crate) struct CallAacEld(Encoder, Decoder);
    pub(crate) struct Encoder;
    pub(crate) struct Decoder;

    impl CallAacEld {
        pub(crate) fn probe(_: u32) -> Result<Self, String> {
            Err(ERROR.into())
        }

        pub(crate) fn into_parts(self) -> (Encoder, Decoder) {
            (self.0, self.1)
        }
    }

    impl Encoder {
        pub(crate) fn try_queue_pcm(&mut self, _: &[u8], _: u64) -> Result<bool, String> {
            Err(ERROR.into())
        }

        pub(crate) fn poll_encoded(&mut self) -> Result<Option<(u64, Vec<u8>)>, String> {
            Err(ERROR.into())
        }
    }

    impl Decoder {
        pub(crate) fn try_queue_au(&mut self, _: &[u8], _: u64) -> Result<bool, String> {
            Err(ERROR.into())
        }

        pub(crate) fn poll_decoded(&mut self) -> Result<Option<(u64, Vec<u8>)>, String> {
            Err(ERROR.into())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_raw_eld_480_mono_config_at_both_call_rates() {
        assert!(validate_asc(&[0xf8, 0xf0, 0x30, 0x00], 16_000).is_ok());
        assert!(validate_asc(&[0xf8, 0xec, 0x30, 0x00], 24_000).is_ok());
        assert!(validate_asc(&[0xf8, 0xfe, 0x00, 0x7d, 0x00, 0x30, 0x00], 16_000).is_ok());
    }

    #[test]
    fn builds_the_call_format_decoder_configuration() {
        assert_eq!(decoder_asc(16_000).unwrap(), [0xf8, 0xf0, 0x30, 0x00]);
        assert_eq!(decoder_asc(24_000).unwrap(), [0xf8, 0xec, 0x30, 0x00]);
        assert!(decoder_asc(48_000).is_err());
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_does_not_offer_a_codec() {
        assert!(CallAacEld::probe(16_000).is_err());
        assert!(CallAacEld::probe(24_000).is_err());
    }

    #[test]
    fn rejects_platform_default_512_sample_eld_and_other_bitstream_modes() {
        let error = validate_asc(&[0xf8, 0xec, 0x20, 0], 24_000).unwrap_err();
        assert!(error.contains("512"));
        for unsupported in [
            &[0x13, 0x08][..],         // AAC-LC
            &[0xf8, 0xf0, 0x30, 0],    // ELD at the other rate
            &[0xf8, 0xec, 0x50, 0],    // stereo
            &[0xf8, 0xec, 0x31, 0],    // LD-SBR
            &[0xf8, 0xec, 0x38, 0],    // error resilience
            &[0xf8, 0xec, 0x30, 0x40], // ELD extension
            &[0xf8, 0xec, 0x30, 0x04], // error protection
            &[0xf8, 0xec, 0x30],       // truncated
        ] {
            assert!(
                validate_asc(unsupported, 24_000).is_err(),
                "{unsupported:02x?}"
            );
        }
    }

    #[test]
    fn requires_one_480_sample_pcm16_mono_frame() {
        assert!(validate_pcm_frame(960).is_ok());
        for size in [0, 958, 961, 1024, 1920] {
            assert!(validate_pcm_frame(size).is_err());
        }
    }
}
