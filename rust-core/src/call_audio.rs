//! A CarPlay main-audio stream for an active cellular call. Local PCM belongs
//! to the shell call-redirection endpoints and never enters the media/VR mixer.
use std::{collections::VecDeque, os::{fd::{AsRawFd, RawFd}, unix::net::UnixStream as LocalSocket}, sync::atomic::{AtomicBool, AtomicI64, Ordering}, time::Duration};
use parking_lot::Mutex;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::UnixStream, sync::mpsc};
use crate::{audio_out::AudioOut, aux_audio::Session, bplist::{self, Value}, mic_probe, rtsp::RtspMessage};
use crate::call_aac_eld::{self, CallAacEld};

const TYPE: i64 = 100;
const TELEPHONY: &str = "telephony";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Codec { Pcm, Opus, AacEld }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Format { pub rate: u32, channels: usize, mask: i64, codec: Codec }

impl Format {
    fn frames(self) -> usize {
        match self.codec {
            Codec::AacEld => call_aac_eld::SAMPLES_PER_AU,
            Codec::Pcm if self.channels == 2 => 352,
            _ => self.rate as usize / 50,
        }
    }

    fn compression(self) -> i64 {
        match self.codec { Codec::Pcm => 1, Codec::AacEld => 8, Codec::Opus => 32 }
    }
}

pub(crate) fn formats(info: &Value) -> impl Iterator<Item = Format> + '_ {
    let rows = info.get("audioFormats").and_then(Value::as_array).into_iter().flatten();
    // Keep the established PCM/Opus priority when a receiver also supports ELD.
    use Codec::{AacEld, Opus, Pcm};
    [(24_000, 1, 6, Pcm), (16_000, 1, 4, Pcm), (48_000, 2, 15, Pcm),
        (44_100, 2, 11, Pcm), (24_000, 1, 29, Opus), (16_000, 1, 28, Opus),
        (24_000, 1, 27, AacEld), (16_000, 1, 26, AacEld)]
        .into_iter().filter_map(move |(rate, channels, bit, codec)| {
            let mask = 1 << bit;
            rows.clone().any(|row| {
                row.get("type").and_then(Value::as_int) == Some(TYPE)
                    && row.get("audioType").and_then(Value::as_str) == Some(TELEPHONY)
                    && ["audioInputFormats", "audioOutputFormats"].iter().all(|key| {
                        row.get(key).and_then(Value::as_int).is_some_and(|value| value >= 0 && value & mask != 0)
                    })
            }).then_some(Format { rate, channels, mask, codec })
        })
}

pub(crate) struct PreparedFormat {
    format: Format,
    eld: Option<CallAacEld>,
}

/// A declared ELD format is usable only if the phone can actually encode and decode
/// the 480-sample configuration. Check before touching the shared media stream.
pub(crate) async fn prepare_formats(info: &Value) -> Vec<PreparedFormat> {
    let candidates = formats(info).collect::<Vec<_>>();
    let mut prepared = Vec::with_capacity(candidates.len());
    for format in candidates {
        let eld = if format.codec == Codec::AacEld {
            let probe = tokio::task::spawn_blocking(move || CallAacEld::probe(format.rate));
            let result = match tokio::time::timeout(Duration::from_secs(1), probe).await {
                Ok(result) => result.map_err(|error| error.to_string()).and_then(|result| result),
                Err(_) => Err("MediaCodec probe timed out".into()),
            };
            match result {
                Ok(codec) => Some(codec),
                Err(error) => {
                    crate::emit_log(&format!("CALL-AUDIO: AAC-ELD {}Hz unavailable: {error}", format.rate));
                    continue;
                }
            }
        } else { None };
        prepared.push(PreparedFormat { format, eld });
    }
    prepared
}

static NEXT_TOKEN: AtomicI64 = AtomicI64::new(1);

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum OfferResult {
    #[default]
    Pending = 0,
    SameRate = 1,
    NextRate = 2,
    Rejected = 3,
    Withdrawn = 4,
}

pub(crate) struct Offer { pub attempt: i64, pub rate: u32, pub socket: LocalSocket }
struct Inbox { token: i64, sender: mpsc::Sender<Offer>, result: Option<(i64, OfferResult)> }
static OFFER: Mutex<Option<Inbox>> = Mutex::new(None);

pub(crate) fn armed(token: i64) -> bool {
    OFFER.lock().as_ref().is_some_and(|inbox| inbox.token == token)
}

pub(crate) fn offer(token: i64, attempt: i64, rate: u32, socket: LocalSocket) -> bool {
    if !matches!(rate, 16_000 | 24_000 | 44_100 | 48_000) { return false; }
    OFFER.lock().as_ref().is_some_and(|inbox| inbox.token == token && attempt > 0
        && inbox.sender.try_send(Offer { attempt, rate, socket }).is_ok())
}

pub(crate) fn take_result(token: i64, attempt: i64) -> OfferResult {
    let mut active = OFFER.lock();
    let Some(inbox) = active.as_mut().filter(|inbox| inbox.token == token && inbox.result.is_some_and(|(id, _)| id == attempt)) else {
        return OfferResult::Pending;
    };
    inbox.result.take().map_or(OfferResult::Pending, |(_, result)| result)
}

#[cfg(test)]
pub(crate) fn take_retry(token: i64, attempt: i64) -> bool {
    matches!(take_result(token, attempt), OfferResult::SameRate | OfferResult::NextRate)
}

#[cfg(test)]
pub(crate) fn token_for_test() -> Option<i64> { OFFER.lock().as_ref().map(|inbox| inbox.token) }

pub(crate) struct Offers { token: i64, pub rx: mpsc::Receiver<Offer> }

impl Offers {
    pub fn arm(format: Format) -> Self {
        let token = NEXT_TOKEN.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel(1);
        *OFFER.lock() = Some(Inbox { token, sender: tx, result: None });
        crate::emit_state("call_audio_armed", &format!("{token} {}", format.rate));
        Self { token, rx }
    }

    pub fn complete(&self, attempt: i64, result: OfferResult) {
        if let Some(inbox) = OFFER.lock().as_mut().filter(|inbox| inbox.token == self.token) {
            inbox.result = Some((attempt, result));
        }
    }

    pub fn finish_before_setup(&self, attempt: i64, outcome: OfferResult, reason: String) {
        self.report_stopping(attempt, None, &Err(reason));
        self.complete(attempt, outcome);
    }

    pub(crate) fn finish_queued_on_shutdown(&mut self) {
        self.rx.close();
        while let Ok(Offer { attempt, socket, .. }) = self.rx.try_recv() {
            drop(socket);
            self.finish_before_setup(attempt, OfferResult::Withdrawn, "session stopped before dequeuing call offer".into());
        }
    }

    pub fn report_stopping(&self, attempt: i64, stream_id: Option<i64>, result: &Result<(), String>) {
        let reason = result.as_ref().err().map(String::as_str).unwrap_or("session stopped");
        crate::emit_log(&format!("CALL-AUDIO: telephony stopping token={} offer={attempt} stream={stream_id:?} reason={reason}", self.token));
        crate::emit_state("call_audio_stopping", &format!("{} offer={attempt}", self.token));
    }
}

impl Drop for Offers {
    fn drop(&mut self) {
        // Stop admission before reporting offers that the worker never dequeued.
        self.finish_queued_on_shutdown();
        let mut offer = OFFER.lock();
        if offer.as_ref().is_some_and(|inbox| inbox.token == self.token) { *offer = None; }
    }
}

pub(crate) fn check_phone_endpoint(fd: RawFd) -> Result<(), String> {
    let mut state = libc::pollfd { fd, events: 0, revents: 0 };
    loop {
        // The Unix socket stays alive through this check. A hangup is reported even with unread PCM.
        let result = unsafe { libc::poll(&mut state, 1, 0) };
        if result >= 0 {
            if state.revents & (libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
                return Err("phone downlink PCM endpoint closed".into());
            }
            return Ok(());
        }
        let error = std::io::Error::last_os_error();
        if error.kind() != std::io::ErrorKind::Interrupted {
            return Err(format!("phone PCM endpoint poll failed: {error}"));
        }
    }
}

#[derive(Clone, Copy)]
enum SetupFields { Extended(Option<u16>), Compact }

fn setup(format: Format, id: i64, data: u16, shape: SetupFields, info: &Value) -> Vec<u8> {
    let compression = format.compression();
    let latency = crate::airplay::output_audio_latency_ms(info, TYPE, TELEPHONY,
        [format.rate.into(), 16, format.channels as i64, compression], true);
    let mut fields = vec![
        ("type", Value::int(TYPE)), ("audioType", Value::str(TELEPHONY)),
        ("audioFormat", Value::int(format.mask)), ("streamConnectionID", Value::int(id)),
        ("input", Value::bool(true)), ("dataPort", Value::int(data.into())),
    ];
    if let Some(latency) = latency { fields.push(("audioLatencyMs", Value::int(latency))); }
    if let SetupFields::Extended(control) = shape {
        fields.extend([
            ("spf", Value::int(format.frames() as i64)),
            ("supportsRTPPacketRedundancy", Value::bool(false)), ("ct", Value::int(compression)),
            ("sr", Value::int(format.rate.into())),
        ]);
        if let Some(port) = control { fields.push(("controlPort", Value::int(port.into()))); }
    }
    if info.get("extendedFeatures").and_then(Value::as_array).is_some_and(|features| {
        features.iter().any(|item| item.as_str() == Some("vocoderInfo"))
    }) {
        // The box declares a 16 kHz vocoder separately from the RTP stream's negotiated clock.
        fields.push(("vocoderInfo", Value::dict(vec![("sampleRate", Value::real(16_000.0))])));
    }
    bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(fields)]))]))
}

fn setup_response(reply: &RtspMessage) -> Result<Value, String> {
    crate::resource_trace::validate_ack("TELEPHONY_SETUP", reply)?;
    bplist::parse(&reply.body).map_err(|_| "invalid telephony SETUP plist".into())
}

fn accept(format: Format, id: i64, row: &Value) -> Result<(u16, Option<u16>), String> {
    if row.get("status").is_some_and(|v| v.as_int() != Some(0)) {
        return Err("telephony SETUP stream rejected or invalid status".into());
    }
    if row.get("audioType").is_some_and(|v| v.as_str() != Some(TELEPHONY))
        || row.get("streamConnectionID").is_some_and(|v| v.as_int() != Some(id))
        || row.get("audioFormat").is_some_and(|v| v.as_int() != Some(format.mask))
        || row.get("sr").is_some_and(|v| v.as_int() != Some(format.rate.into()))
        || row.get("ct").is_some_and(|v| v.as_int() != Some(format.compression()))
        || row.get("spf").is_some_and(|v| v.as_int() != Some(format.frames() as i64))
        || row.get("input").is_some_and(|v| v.as_bool() != Some(true)) {
        return Err("telephony SETUP response disagrees with the requested stream".into());
    }
    let port = |key| row.get(key).and_then(Value::as_int).and_then(|n| u16::try_from(n).ok());
    let data = port("dataPort").filter(|p| *p != 0).ok_or("telephony SETUP missing receiver dataPort")?;
    let control = if row.get("controlPort").is_some() {
        Some(port("controlPort").ok_or("telephony SETUP invalid controlPort")?).filter(|p| *p != 0)
    } else { None };
    Ok((data, control))
}

pub(crate) async fn run(session: &Session, phone_rate: u32, mut local: UnixStream, prepared: Vec<PreparedFormat>,
    loss_generation: u64, stop: impl std::future::Future<Output = ()>, allocated: &mut Option<i64>,
    pending: &AtomicBool, announced: &mut bool, outcome: &mut OfferResult,
) -> Result<(), String> {
    let mut ownership = session.control.buffered_ownership.subscribe();
    let valid = |state: &crate::buffered_ownership::Ownership| state.owned && state.losses == loss_generation;
    if !valid(&ownership.borrow()) {
        *outcome = OfferResult::SameRate;
        return Err("main audio grant changed before telephony SETUP".into());
    }
    let host = &session.params.car_ip;
    let uri = crate::rtsp_uri::audio_uri(host, session.params.airplay_port);
    let microphone = session.factory.open_udp(host, 0).ok_or("no microphone UDP socket")?;
    let data_port = microphone.local_addr().map_err(|e| e.to_string())?.port();
    let mut rtcp = session.factory.open_udp(host, 0).and_then(|socket| {
        let port = socket.local_addr().ok()?.port();
        (port != 0).then_some((socket, port))
    });
    tokio::pin!(stop);
    let mut candidates = prepared.into_iter();
    let mut selected = candidates.next().ok_or("no usable bidirectional telephony format")?;
    let mut setup_fields = SetupFields::Extended(rtcp.as_ref().map(|(_, port)| *port));
    let mut tried_compact = false;
    let (id, data, sync) = loop {
        let format = selected.format;
        tokio::select! { biased; _ = &mut stop => return Ok(()), _ = std::future::ready(()) => {} }
        if !valid(&ownership.borrow()) {
            *outcome = OfferResult::SameRate;
            return Err("main audio grant changed during telephony SETUP".into());
        }
        if !matches!(local.try_read(&mut [0]), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock) {
            *outcome = OfferResult::Withdrawn;
            return Err("phone call ended during SETUP".into());
        }
        let id = crate::airplay::random_positive_i64();
        *allocated = Some(id);
        pending.store(true, Ordering::Release);
        let body = setup(format, id, data_port, setup_fields, &session.info);
        let reply = session.control.request("SETUP", &uri, &body, "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await?;
        if matches!(reply.status(), 400 | 451 | 461) {
            // These transport rejections leave the shared slot free. Unknown replies still require TEARDOWN.
            *allocated = None;
            pending.store(false, Ordering::Release);
            if !tried_compact {
                tried_compact = true;
                rtcp = None;
                setup_fields = SetupFields::Compact;
                crate::emit_log(&format!("CALL-AUDIO: SETUP status={}; retry compact request for format {:#x}", reply.status(), format.mask));
            } else if let Some(next) = candidates.next() {
                // A format refusal does not establish that optional fields caused it.
                setup_fields = SetupFields::Extended(None);
                crate::emit_log(&format!("CALL-AUDIO: SETUP status={}; try declared format {:#x}", reply.status(), next.format.mask));
                selected = next;
            } else {
                return Err(format!("telephony SETUP rejected status={}; no remaining declared format", reply.status()));
            }
            continue;
        }
        let root = setup_response(&reply)?;
        let conflicting_id = root.get("streams").and_then(Value::as_array).into_iter().flatten()
            .filter(|row| row.get("type").and_then(Value::as_int) == Some(TYPE))
            .filter_map(|row| row.get("streamConnectionID").and_then(Value::as_int))
            .any(|other| other != id);
        if conflicting_id {
            // A scoped ACK for our proposed ID cannot retire a different receiver allocation.
            crate::emit_log("CALL-AUDIO: SETUP returned a different stream ID; closing session");
            session.control.close();
            return Err("telephony SETUP stream ID mismatch".into());
        }
        let row = crate::screen_session::setup_stream::select(&root, TYPE, false)?;
        let (data, sync) = accept(format, id, row)?;
        break (id, data, sync);
    };
    drop(candidates);
    let PreparedFormat { format, eld } = selected;
    let (mut eld_encoder, mut eld_decoder) = match eld.map(CallAacEld::into_parts) {
        Some((encoder, decoder)) => (Some(encoder), Some(decoder)),
        None => (None, None),
    };
    let mut decoder = (format.codec == Codec::Opus).then(|| CallOpusDecoder::new(format.rate)).transpose()?;
    let mut encoder = (format.codec == Codec::Opus).then(|| crate::opus_encode::OpusEncoder::new_call(format.rate, format.frames()))
        .transpose().map_err(|e| e.to_string())?;
    session.control.udp_media.set(TYPE, true);
    let socket = session.factory.open_udp(host, data).ok_or("no speaker UDP socket")?;
    let receiver = socket.peer_addr().map_err(|e| e.to_string())?;
    let receiver_ip = mic_probe::normalized(receiver.ip());
    let sync = if let Some(port) = sync {
        let mut peer = receiver;
        peer.set_port(port);
        let bound = if let Some((rtcp, _)) = rtcp {
            rtcp.connect(peer).await.ok().map(|()| rtcp)
        } else { None };
        bound.or_else(|| session.factory.open_udp(host, port))
    } else { None };
    let output_key = crate::airplay::stream_key(&session.shared, id as u64);
    let mut speaker = AudioOut::new_pcm(socket, sync, output_key, format.rate);
    speaker.set_ownership(session.resource.owned.clone());
    speaker.track_rtp_ownership(&session.control.buffered_ownership);
    let input_key = mic_probe::input_key(&session.shared, id as u64);
    if !valid(&ownership.borrow()) {
        *outcome = OfferResult::SameRate;
        return Err("main audio grant changed during telephony SETUP".into());
    }
    match local.try_read(&mut [0]) {
        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
        _ => {
            *outcome = OfferResult::Withdrawn;
            return Err("phone call ended during SETUP".into());
        }
    }
    *announced = true; // An ACK can be lost after the receiver applied the state.
    if !session.resource.set_phone_call(true).await {
        if !valid(&ownership.borrow()) { *outcome = OfferResult::SameRate; }
        return Err("phoneCall app state rejected".into());
    }
    tokio::select! {
        biased;
        _ = &mut stop => return Ok(()),
        _ = ownership.changed() => {
            *outcome = OfferResult::SameRate;
            return Err("main audio grant changed before call audio start".into());
        }
        ready = tokio::time::timeout(Duration::from_secs(1), async {
            local.write_u8(1).await?;
            local.read_u8().await
        }) => {
            if !matches!(ready, Ok(Ok(1))) {
                *outcome = OfferResult::NextRate;
                return Err("phone call audio did not start".into());
            }
        }
    }
    *outcome = OfferResult::SameRate;
    crate::emit_log(&format!("CALL-AUDIO: telephony SETUP ready type={TYPE} phoneRate={phone_rate} rate={} channels={} codec={:?}",
        format.rate, format.channels, format.codec));
    let uplink_ownership = ownership.clone();
    let phone_fd = local.as_raw_fd();
    let (mut downlink, uplink) = local.split();
    let down = async {
        let phone_frames = (format.frames() * phone_rate as usize).div_ceil(format.rate as usize);
        let mut pcm = vec![0; phone_frames * 2];
        let mut samples = VecDeque::new();
        let mut resampler = MonoResampler::default();
        let mut sync = std::time::Instant::now() - Duration::from_secs(1);
        let period = Duration::from_secs_f64(format.frames() as f64 / f64::from(format.rate));
        let mut eld_clock = EldClock::new(format);
        let mut next_packet = tokio::time::Instant::now();
        let mut phone_pcm_ready = false;
        loop {
            check_phone_endpoint(phone_fd)?;
            while samples.len() < format.frames() {
                tokio::time::timeout(Duration::from_secs(2), downlink.read_exact(&mut pcm)).await
                    .map_err(|_| "phone downlink PCM stalled")?.map_err(|error| match error.kind() {
                        std::io::ErrorKind::UnexpectedEof => "phone downlink PCM endpoint closed".into(),
                        _ => format!("phone downlink PCM read failed: {error}"),
                    })?;
                if !phone_pcm_ready {
                    phone_pcm_ready = true;
                    crate::emit_log("CALL-AUDIO: phone downlink PCM received");
                }
                resampler.push(&pcm, phone_rate, format.rate, &mut samples);
            }
            let now = tokio::time::Instant::now();
            if now >= next_packet + period { next_packet = now; }
            tokio::time::sleep_until(next_packet).await;
            next_packet += period;
            if !speaker.owns_audio() { return Err("receiver took main audio".into()); }
            let frames = samples.drain(..format.frames());
            let payload = if let Some(eld) = &mut eld_encoder {
                let pcm: Vec<_> = frames.flat_map(i16::to_le_bytes).collect();
                let payload = transfer_eld(eld, &pcm, &mut eld_clock,
                    call_aac_eld::Encoder::try_queue_pcm, call_aac_eld::Encoder::poll_encoded).await?;
                let Some(payload) = payload else { continue; };
                payload
            } else if let Some(encoder) = &mut encoder {
                encoder.encode_mono_packet(&frames.collect::<Vec<_>>()).map_err(|e| e.to_string())?
            } else {
                frames.flat_map(|sample| std::iter::repeat_n(sample.to_be_bytes(), format.channels).flatten()).collect()
            };
            if sync.elapsed() >= Duration::from_secs(1) {
                let _ = speaker.send_call_sync(crate::timing::monotonic_nanos(), format.frames() as u32).await;
                sync = std::time::Instant::now();
            }
            check_phone_endpoint(phone_fd)?;
            speaker.send_call_audio(&payload, format.frames() as u32, loss_generation).await
                .map_err(|error| format!("car speaker RTP send failed: {error}"))?;
        }
    };
    let up = async {
        let mut stats = mic_probe::Stats::default();
        let mut wire = [0u8; 8193];
        let mut port = None;
        let mut deadline = Some(tokio::time::Instant::now() + Duration::from_secs(5));
        let mut resampler = MonoResampler::default();
        let mut converted = VecDeque::new();
        let mut eld_clock = EldClock::new(format);
        loop {
            let receive = microphone.recv_from(&mut wire);
            let packet = if let Some(until) = deadline {
                match tokio::time::timeout_at(until, receive).await {
                    Ok(packet) => packet,
                    Err(_) => {
                        crate::emit_log("CALL-AUDIO: no usable microphone RTP; speaker continues");
                        deadline = None;
                        port = None;
                        stats.reset_sequence();
                        resampler = MonoResampler::default();
                        continue;
                    }
                }
            } else { receive.await };
            let (n, source) = packet.map_err(|error| format!("car microphone RTP receive failed: {error}"))?;
            if mic_probe::normalized(source.ip()) != receiver_ip || port.is_some_and(|p| p != source.port()) { continue; }
            let Ok(payload) = stats.open(TYPE as u8, &wire[..n], &input_key) else { continue; };
            let pcm = if let Some(eld) = &mut eld_decoder {
                let decoded = transfer_eld(eld, &payload, &mut eld_clock,
                    call_aac_eld::Decoder::try_queue_au, call_aac_eld::Decoder::poll_decoded).await?;
                let Some(pcm) = decoded else { continue; };
                pcm
            } else if let Some(decoder) = &mut decoder {
                match decoder.decode(&payload) {
                    Some(samples) => samples.iter().copied().flat_map(i16::to_le_bytes).collect(),
                    _ => continue,
                }
            } else {
                let Some(pcm) = mono_pcm(&payload, format.channels) else { continue; };
                pcm
            };
            if !session.resource.owned.load(Ordering::Acquire) { return Err("receiver took main audio".into()); }
            if port.is_none() { crate::emit_log("CALL-AUDIO: authenticated microphone PCM flowing; remote audibility unconfirmed"); }
            port = Some(source.port());
            deadline = Some(tokio::time::Instant::now() + Duration::from_secs(3));
            resampler.push(&pcm, format.rate, phone_rate, &mut converted);
            let bytes = converted.len() * 2;
            if bytes == 0 { continue; }
            if bytes > 0x7ffe { return Err("phone call microphone frame is too large".into()); }
            let mut frame = Vec::with_capacity(2 + bytes);
            frame.extend_from_slice(&(bytes as u16).to_be_bytes());
            frame.extend(converted.drain(..).flat_map(i16::to_le_bytes));
            tokio::time::timeout(Duration::from_millis(100), async {
                let mut written = 0;
                while written < frame.len() {
                    uplink.writable().await?;
                    let result = {
                        let grant = uplink_ownership.borrow();
                        if !valid(&grant) { return Err(std::io::Error::other("call microphone grant expired")); }
                        uplink.try_write(&frame[written..])
                    };
                    match result {
                        Ok(0) => return Err(std::io::ErrorKind::WriteZero.into()),
                        Ok(bytes) => written += bytes,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => continue,
                        Err(error) => return Err(error),
                    }
                }
                Ok(())
            }).await.map_err(|_| "phone uplink PCM write timed out")?
                .map_err(|error| format!("phone uplink PCM write failed: {error}"))?;
        }
    };
    tokio::select! {
        biased;
        _ = &mut stop => Ok(()),
        _ = ownership.changed() => Err("main audio grant changed during call".into()),
        result = down => result,
        result = up => result,
    }
}

struct EldClock {
    input: u64,
    output: u64,
    step: u64,
}

impl EldClock {
    fn new(format: Format) -> Self {
        Self { input: 1_000_000, output: 1_000_000,
            step: format.frames() as u64 * 1_000_000 / u64::from(format.rate) }
    }
}

async fn transfer_eld<C>(codec: &mut C, input: &[u8], clock: &mut EldClock,
    queue: fn(&mut C, &[u8], u64) -> Result<bool, String>,
    poll: fn(&mut C) -> Result<Option<(u64, Vec<u8>)>, String>,
) -> Result<Option<Vec<u8>>, String> {
    let mut output = None;
    tokio::time::timeout(Duration::from_millis(80), async {
        loop {
            if output.is_none() { output = poll(codec)?; }
            if queue(codec, input, clock.input)? {
                clock.input += clock.step;
                if output.is_none() { output = poll(codec)?; }
                let Some((pts, bytes)) = output else { return Ok(None); };
                if pts != clock.output { return Err("AAC-ELD codec output changed the packet clock".into()); }
                clock.output += clock.step;
                return Ok(Some(bytes));
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    }).await.map_err(|_| "AAC-ELD codec input timed out".to_string())?
}

#[derive(Default)]
struct MonoResampler(crate::audio_resample::PcmToStereo, VecDeque<i16>);

impl MonoResampler {
    fn push(&mut self, pcm: &[u8], from: u32, to: u32, out: &mut VecDeque<i16>) {
        if from == to {
            out.extend(pcm.chunks_exact(2).map(|raw| i16::from_le_bytes([raw[0], raw[1]])));
        } else {
            self.0.push(pcm, from, 1, to, &mut self.1);
            out.extend(self.1.drain(..).step_by(2));
        }
    }
}

struct CallOpusDecoder {
    decoder: *mut audiopus_sys::OpusDecoder,
    pcm: Vec<i16>,
}

// Tokio can migrate the task, but only the uplink future ever accesses the decoder.
unsafe impl Send for CallOpusDecoder {}

impl CallOpusDecoder {
    fn new(rate: u32) -> Result<Self, String> {
        let mut error = 0;
        let decoder = unsafe { audiopus_sys::opus_decoder_create(rate as i32, 1, &mut error) };
        if decoder.is_null() || error != 0 { return Err(format!("call Opus decoder status={error}")); }
        Ok(Self { decoder, pcm: vec![0; rate as usize * 120 / 1000] })
    }

    fn decode(&mut self, payload: &[u8]) -> Option<&[i16]> {
        if payload.is_empty() || payload.len() > 4096 { return None; }
        let frames = unsafe { audiopus_sys::opus_decode(self.decoder, payload.as_ptr(), payload.len() as i32,
            self.pcm.as_mut_ptr(), self.pcm.len() as i32, 0) };
        self.pcm.get(..usize::try_from(frames).ok()?).filter(|samples| !samples.is_empty())
    }
}

impl Drop for CallOpusDecoder {
    fn drop(&mut self) { unsafe { audiopus_sys::opus_decoder_destroy(self.decoder); } }
}

fn mono_pcm(payload: &[u8], channels: usize) -> Option<Vec<u8>> {
    if payload.is_empty() || payload.len() % (channels * 2) != 0 { return None; }
    Some(payload.chunks_exact(channels * 2).flat_map(|frame| {
        let left = i16::from_be_bytes([frame[0], frame[1]]);
        let mono = if channels == 2 {
            ((i32::from(left) + i32::from(i16::from_be_bytes([frame[2], frame[3]]))) / 2) as i16
        } else { left };
        mono.to_le_bytes()
    }).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::Write, os::fd::AsRawFd};

    #[test]
    fn abandoned_local_microphone_socket_reports_hangup_before_draining_pcm() {
        let (mut rust, shell) = LocalSocket::pair().unwrap();
        rust.write_all(&[0, 2, 42, 0]).unwrap();
        drop(rust);
        let mut state = libc::pollfd { fd: shell.as_raw_fd(), events: 0, revents: 0 };
        assert_eq!(unsafe { libc::poll(&mut state, 1, 0) }, 1);
        assert_ne!(state.revents & libc::POLLHUP, 0);
    }

    #[test]
    fn telephony_rejects_stream_level_refusal_and_conflicting_packet_format() {
        let format = Format { rate: 16_000, channels: 1, mask: 1 << 4, codec: Codec::Pcm };
        let reply = |extra| {
            let mut fields = vec![("type", Value::int(100)), ("dataPort", Value::int(7010))];
            fields.extend(extra);
            Value::dict(fields)
        };
        assert_eq!(accept(format, 1, &reply(vec![])), Ok((7010, None)));
        assert_eq!(accept(format, 1, &reply(vec![("status", Value::int(0))])), Ok((7010, None)));
        for field in [("status", Value::int(-6722)), ("status", Value::str("0")),
            ("ct", Value::int(32)), ("spf", Value::int(480))] {
            assert!(accept(format, 1, &reply(vec![field.clone()])).is_err(), "accepted {field:?}");
        }
    }

    #[test]
    fn telephony_requires_a_single_bidirectional_receiver_record() {
        let row = |kind, audio_type, input, output| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(audio_type)),
            ("audioInputFormats", Value::int(input)), ("audioOutputFormats", Value::int(output)),
        ]);
        let info = |rows| Value::dict(vec![("audioFormats", Value::array(rows))]);
        let pcm16 = 1 << 4;
        assert!(formats(&info(vec![row(100, "telephony", 0, pcm16), row(100, "telephony", pcm16, 0),
            row(100, "speechRecognition", pcm16, pcm16), row(102, "telephony", pcm16, pcm16)])).next().is_none());
        let selected = formats(&info(vec![row(100, "telephony", 1 << 28, 1 << 28)])).next().unwrap();
        assert_eq!((selected.rate, selected.frames(), selected.codec), (16000, 320, Codec::Opus));
        let mut encoder = crate::opus_encode::OpusEncoder::new_call(selected.rate, selected.frames()).unwrap();
        let encoded = encoder.encode_mono_packet(&[10; 320]).unwrap();
        let mut decoder = CallOpusDecoder::new(selected.rate).unwrap();
        let decoded = decoder.decode(&encoded).unwrap();
        assert_eq!(decoded.len(), 320);
        assert!(encoder.encode_mono_packet(&[10; 319]).is_err());
    }

    #[test]
    fn telephony_matches_mgu_aac_eld_at_both_declared_rates() {
        let info = Value::dict(vec![("audioFormats", Value::array(vec![Value::dict(vec![
            ("type", Value::int(100)), ("audioType", Value::str("telephony")),
            ("audioInputFormats", Value::int(0x0c00_0000)), ("audioOutputFormats", Value::int(0x0c00_0000)),
        ])]))]);
        let actual = formats(&info).map(|format| (format.mask, format.rate, format.frames(), format.compression()))
            .collect::<Vec<_>>();
        assert_eq!(actual, [(1 << 27, 24_000, 480, 8), (1 << 26, 16_000, 480, 8)]);
    }

    #[test]
    fn telephony_preserves_declared_zero_latency_but_omits_unknown_latency() {
        let format = Format { rate: 24_000, channels: 1, mask: 1 << 29, codec: Codec::Opus };
        let declared = Value::dict(vec![("audioLatencies", Value::array(vec![Value::dict(vec![
            ("type", Value::int(100)), ("audioType", Value::str("telephony")), ("outputLatencyMicros", Value::int(0)),
        ])]))]);
        for (info, expected) in [(Value::dict(vec![]), None), (declared, Some(0))] {
            let request = bplist::parse(&setup(format, 7, 7001, SetupFields::Extended(Some(7002)), &info)).unwrap();
            let stream = &request.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("audioLatencyMs").and_then(Value::as_int), expected);
        }
    }

    #[test]
    fn telephony_sets_vocoder_rate_only_when_receiver_advertises_it() {
        let format = Format { rate: 24_000, channels: 1, mask: 1 << 29, codec: Codec::Opus };
        let advertised = Value::dict(vec![("extendedFeatures", Value::array(vec![Value::str("vocoderInfo")]))]);
        let other = Value::dict(vec![("extendedFeatures", Value::array(vec![Value::str("other")]))]);
        for (info, expected) in [(advertised, Some(Value::Real(16_000.0))), (other, None), (Value::dict(vec![]), None)] {
            let request = bplist::parse(&setup(format, 7, 7001, SetupFields::Extended(None), &info)).unwrap();
            let stream = &request.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("sr"), Some(&Value::Int(24_000)));
            assert_eq!(stream.get("vocoderInfo").and_then(|info| info.get("sampleRate")), expected.as_ref());
        }
    }

    #[test]
    fn telephony_stereo_microphone_is_downmixed_and_converted_to_native_endian() {
        assert_eq!(mono_pcm(&[0x11, 0x22, 0x13, 0x24], 2), Some(vec![0x23, 0x12]));
        assert_eq!(mono_pcm(&[0xff, 0x80], 1), Some(vec![0x80, 0xff]));
        assert_eq!(mono_pcm(&[0, 0, 0], 2), None);
    }
}
