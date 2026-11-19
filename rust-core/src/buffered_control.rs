//! Playback control for one TCP main-buffered audio stream.
use crate::{
    bplist::{self, Value},
    buffered_ownership::Ownership,
    rtsp::RtspChannel,
};
use std::{io, sync::{Arc, OnceLock}, time::{Duration, Instant}};
use tokio::sync::{watch, Mutex, OwnedMutexGuard};

const CONTROL_TIMEOUT: Duration = Duration::from_secs(1);

struct Playback {
    losses: u64,
    last_written: Option<(u32, u32)>,
    start_requested: bool,
}

struct Stream {
    channel: Arc<RtspChannel>,
    uri: String,
    anchor_uri: Option<String>,
    sample_rate: u32,
    samples_per_packet: u32,
    ownership: watch::Receiver<Ownership>,
    retired: watch::Receiver<bool>,
    failure: OnceLock<String>,
    playing_generation: parking_lot::Mutex<Option<u64>>,
}

impl Stream {
    async fn request(&self, method: &str, body: Value) -> io::Result<Option<Value>> {
        if *self.retired.borrow() {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "buffered stream retired"));
        }
        let uri = if method == "GETANCHOR" { self.anchor_uri.as_deref().unwrap_or(&self.uri) } else { &self.uri };
        let response = self.channel.request(method, uri, &bplist::encode(&body),
            "application/x-apple-binary-plist", &[], CONTROL_TIMEOUT).await.map_err(io::Error::other)?;
        let status = response.status();
        if status != 200 {
            return Err(io::Error::other(format!("type103 {method} RTSP {status}")));
        }
        if response.body.is_empty() { return Ok(None); }
        let plist = bplist::parse(&response.body).map_err(io::Error::other)?;
        if plist.as_dict().is_none() { return Err(io::Error::other(format!("{method} response is not a dictionary"))); }
        if let Some(value) = plist.get("status") {
            let business = value.as_int().ok_or_else(|| io::Error::other(format!("type103 {method} invalid status")))?;
            if business != 0 {
                return Err(io::Error::other(format!("type103 {method} status {business}")));
            }
        }
        Ok(Some(plist))
    }

    // The CarPlay NTP path in mib2q-carplay-rgi/hook/audio/audio_hook.c
    // (spa103_get_session_network_time) returns a render time on its synchronized
    // session clock (including its chosen scheduling lead). Keep all four integers
    // unchanged, including signed NTP seconds, fractional bits and a zero NTP
    // timeline ID. A PTP grandmaster or synthesized local wall time is unnecessary.
    async fn clock(&self) -> io::Result<Vec<(&'static str, Value)>> {
        let reply = self.request("GETANCHOR", rate(1)).await?
            .ok_or_else(|| io::Error::other("type103 GETANCHOR returned no session clock"))?;
        ["networkTimeSecs", "networkTimeFrac", "networkTimeTimelineID", "networkTimeFlags"]
            .into_iter().map(|key| {
                reply.get(key).and_then(Value::as_int).map(|n| (key, Value::int(n)))
                    .ok_or_else(|| io::Error::other(format!("type103 GETANCHOR missing {key}")))
            }).collect()
    }

    async fn start(&self, generation: u64, ts: u32) -> io::Result<()> {
        let _ordered = self.channel.buffered_commands.lock().await;
        if !self.permits(generation) { return Ok(()); }
        let mut fields = self.clock().await?;
        if !self.permits(generation) { return Ok(()); }
        for (key, value) in [
            ("rate", 1), ("rtpTime", ts), ("mediaTimeValue", ts), ("mediaTimeScale", self.sample_rate),
            ("firstAudibleRTPTime", ts), ("firstAudibleMediaTimeValue", ts),
            ("firstAudibleMediaTimeScale", self.sample_rate),
        ] {
            fields.push((key, Value::int(value.into())));
        }
        self.request("SETRATEANCHORTIME", Value::dict(fields)).await?;
        *self.playing_generation.lock() = Some(generation);
        Ok(())
    }

    fn permits(&self, generation: u64) -> bool {
        let owner = *self.ownership.borrow();
        owner.owned && owner.losses == generation
    }

    async fn reconcile_loss(&self, state: &mut Playback) -> io::Result<()> {
        let owner = *self.ownership.borrow();
        let generation = owner.losses;
        if generation == state.losses { return Ok(()); }
        state.losses = generation;
        state.start_requested = false;
        let Some((seq, ts)) = state.last_written.take() else { return Ok(()); };
        // Prefix flush uses the inclusive wire sequence and final sample of the
        // last completed AU. The first sample sent after regain survives wrap.
        let last_sample = ts.wrapping_add(self.samples_per_packet.saturating_sub(1));
        let _ordered = self.channel.buffered_commands.lock().await;
        let started = owner.lost_at.unwrap_or_else(Instant::now);
        let flush = self.request("FLUSHBUFFERED", Value::dict(vec![
            ("flushUntilSeq", Value::int(seq.into())),
            ("flushUntilTS", Value::int(last_sample.into())),
        ])).await;
        let flush_ms = started.elapsed().as_millis();
        let pause = self.request("SETRATEANCHORTIME", rate(0)).await;
        let pause_ms = started.elapsed().as_millis();
        // Even a rejected flush should still attempt to stop playback.
        crate::emit_log(&format!(
            "BUFFERED-AUDIO: type103 lossGeneration={generation} flushUntilSeq={seq} flushUntilTS={last_sample} flush_ms={flush_ms} flush={:?} rate=0 pause_ms={pause_ms} pause={:?}",
            flush.as_ref().map(|_| ()), pause.as_ref().map(|_| ())));
        flush.and(pause).map(|_| ()).inspect_err(|error| {
            if !*self.retired.borrow() { let _ = self.failure.set(error.to_string()); }
        })?;
        Ok(())
    }
}

fn rate(value: i64) -> Value {
    Value::dict(vec![("rate", Value::int(value))])
}

pub(crate) struct BufferedControl {
    stream: Arc<Stream>,
    state: Arc<Mutex<Playback>>,
    // The ownership observer must include an in-flight data write in its cutoff.
    // Holding this guard from prepare through packet_written serializes those two
    // operations and the observer without ever blocking the RTSP event handler.
    reservation: Option<OwnedMutexGuard<Playback>>,
    retire: watch::Sender<bool>,
}

impl BufferedControl {
    pub(crate) fn new(channel: Arc<RtspChannel>, uri: String, anchor_uri: Option<String>, sample_rate: u32, samples_per_packet: u32) -> Self {
        let mut changes = channel.buffered_ownership.subscribe();
        let losses = changes.borrow().losses;
        let (retire, mut retired) = watch::channel(false);
        let stream = Arc::new(Stream { channel, uri, anchor_uri, sample_rate, samples_per_packet,
            ownership: changes.clone(), retired: retired.clone(), failure: OnceLock::new(), playing_generation: Default::default() });
        let state = Arc::new(Mutex::new(Playback { losses, last_written: None, start_requested: false }));
        let observer = stream.clone();
        let playback = state.clone();
        crate::callback_context::spawn(async move {
            loop {
                tokio::select! {
                    biased;
                    _ = retired.changed() => break,
                    event = changes.changed() => if event.is_err() { break; },
                }
                let mut locked = playback.lock().await;
                if *retired.borrow() || observer.reconcile_loss(&mut locked).await.is_err() { break; }
            }
        });
        Self { stream, state, reservation: None, retire }
    }

    pub(crate) fn loss_generation(&self) -> u64 { self.stream.ownership.borrow().losses }

    pub(crate) fn playing(&self) -> bool {
        let confirmed = *self.stream.playing_generation.lock();
        confirmed.is_some_and(|generation| self.stream.permits(generation))
    }

    pub(crate) fn failed(&self) -> bool { self.stream.failure.get().is_some() }

    pub(crate) async fn probe_anchor(&self) -> io::Result<()> {
        let _ordered = self.stream.channel.buffered_commands.lock().await;
        self.stream.clock().await.map(|_| ())
    }

    pub(crate) async fn prepare_packet(&mut self, _seq: u32, _ts: u32, generation: u64) -> io::Result<bool> {
        if self.reservation.is_some() { return Err(io::Error::other("uncompleted buffered data write")); }
        if let Some(error) = self.stream.failure.get() { return Err(io::Error::other(error.clone())); }
        let Ok(state) = self.state.clone().try_lock_owned() else { return Ok(false); };
        if !self.stream.permits(generation) || state.losses != generation { return Ok(false); }
        self.reservation = Some(state);
        Ok(true)
    }

    pub(crate) async fn packet_written(&mut self, seq: u32, ts: u32) -> io::Result<()> {
        let mut state = self.reservation.take().ok_or_else(|| io::Error::other("unreserved buffered data write"))?;
        state.last_written = Some((seq, ts));
        if !state.start_requested && self.stream.permits(state.losses) {
            state.start_requested = true;
            let generation = state.losses;
            let stream = self.stream.clone();
            // Keep RTSP waits off the shared mixer. Same-generation AUs may prime
            // within the negotiated byte capacity until rate=1 is acknowledged.
            crate::callback_context::spawn(async move {
                let started = Instant::now();
                let result = stream.start(generation, ts).await;
                if *stream.retired.borrow() { return; }
                if let Err(error) = &result { let _ = stream.failure.set(error.to_string()); }
                crate::emit_log(&format!(
                    "BUFFERED-AUDIO: type103 rate=1 seq={seq} rtp={ts} sampleRate={} lossGeneration={generation} clock=GETANCHOR playing={} elapsed_ms={} result={result:?}",
                    stream.sample_rate, *stream.playing_generation.lock() == Some(generation) && stream.permits(generation), started.elapsed().as_millis()));
            });
        }
        Ok(())
    }
}

impl Drop for BufferedControl {
    fn drop(&mut self) {
        // Finish any RTSP bytes already in flight before a stream TEARDOWN can
        // acquire buffered_commands. Do not abort a partly written encrypted request.
        self.retire.send_replace(true);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rtsp::{parse_rtsp_messages, RtspMessage};
    use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, time::timeout};

    const URI: &str = "rtsp://127.0.0.1:7000/";

    async fn pair() -> (BufferedControl, Arc<RtspChannel>, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (peer, _) = listener.accept().await.unwrap();
        let (read, write) = client.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        channel.buffered_ownership.publish(true);
        (BufferedControl::new(channel.clone(), URI.into(), None, 48_000, 1024), channel, peer)
    }

    async fn read_message(peer: &mut TcpStream) -> RtspMessage {
        let mut data = vec![];
        timeout(Duration::from_secs(2), async {
            loop {
                let mut bytes = [0; 4096];
                let n = peer.read(&mut bytes).await.unwrap();
                assert_ne!(n, 0);
                data.extend_from_slice(&bytes[..n]);
                if let Some(message) = parse_rtsp_messages(&mut data).unwrap().pop() {
                    return message;
                }
            }
        }).await.expect("expected buffered audio RTSP request")
    }

    async fn request(peer: &mut TcpStream, method: &str) -> (RtspMessage, Value) {
        let message = read_message(peer).await;
        assert_eq!(message.first_line, format!("{method} {URI} RTSP/1.0"));
        assert_eq!(message.headers["content-type"], "application/x-apple-binary-plist");
        let plist = bplist::parse(&message.body).unwrap();
        (message, plist)
    }

    async fn reply(peer: &mut TcpStream, message: &RtspMessage, status: u16, body: Option<Value>) {
        let body = body.map(|value| bplist::encode(&value)).unwrap_or_default();
        let mut text = format!("RTSP/1.0 {status} Reply\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
            message.cseq().unwrap(), body.len()).into_bytes();
        text.extend(body);
        peer.write_all(&text).await.unwrap();
    }

    fn clock(seconds: i64) -> Value {
        Value::dict(vec![("status", Value::int(0)), ("networkTimeSecs", Value::int(seconds)),
            ("networkTimeFrac", Value::int(i64::MIN)), ("networkTimeTimelineID", Value::int(0)),
            ("networkTimeFlags", Value::int(0))])
    }

    async fn anchor(peer: &mut TcpStream, ts: i64, seconds: i64) {
        let (get, rate) = request(peer, "GETANCHOR").await;
        assert_eq!(rate.get("rate"), Some(&Value::int(1)));
        reply(peer, &get, 200, Some(clock(seconds))).await;
        let (play, body) = request(peer, "SETRATEANCHORTIME").await;
        for (key, expected) in [
            ("rate", 1), ("rtpTime", ts), ("mediaTimeValue", ts), ("mediaTimeScale", 48000),
            ("firstAudibleRTPTime", ts), ("firstAudibleMediaTimeValue", ts),
            ("firstAudibleMediaTimeScale", 48000), ("networkTimeSecs", seconds),
            ("networkTimeFrac", i64::MIN), ("networkTimeTimelineID", 0), ("networkTimeFlags", 0)
        ] {
            assert_eq!(body.get(key), Some(&Value::int(expected)), "{key}");
        }
        reply(peer, &play, 200, None).await;
    }

    #[tokio::test]
    async fn rapid_take_and_return_flushes_without_waiting_for_pcm_and_resumes_at_fresh_samples() {
        let (mut control, channel, mut peer) = pair().await;
        let original = control.loss_generation();
        assert!(control.prepare_packet(0x7fffff, u32::MAX-1023, original).await.unwrap());
        let (written, ()) = tokio::join!(control.packet_written(0x7fffff, u32::MAX-1023),
            anchor(&mut peer, i64::from(u32::MAX)-1023, -1));
        written.unwrap();
        channel.buffered_ownership.publish(false);
        channel.buffered_ownership.publish(true);
        let (flush, cut) = request(&mut peer, "FLUSHBUFFERED").await;
        assert_eq!(cut.get("flushUntilSeq"), Some(&Value::int(0x7fffff)));
        assert_eq!(cut.get("flushUntilTS"), Some(&Value::int(u32::MAX.into())));
        reply(&mut peer, &flush, 200, None).await;
        let (pause, rate) = request(&mut peer, "SETRATEANCHORTIME").await;
        assert_eq!(rate, Value::dict(vec![("rate", Value::int(0))]));
        reply(&mut peer, &pause, 200, None).await;
        assert!(!control.prepare_packet(0, 0, original).await.unwrap());
        let fresh = control.loss_generation();
        timeout(Duration::from_secs(1), async {
            while !control.prepare_packet(0, 0, fresh).await.unwrap() { tokio::task::yield_now().await; }
        }).await.unwrap();
        let (written, ()) = tokio::join!(control.packet_written(0, 0), anchor(&mut peer, 0, 42));
        written.unwrap();
    }

    #[tokio::test]
    async fn malformed_clock_or_application_error_never_starts_playback() {
        for (reply_body, status) in [(Value::dict(vec![]), 200), (clock(1), 501),
            (Value::dict(vec![("status", Value::int(-6718))]), 200),
            (Value::dict(vec![("status", Value::str("invalid"))]), 200)] {
            let (control, _, mut peer) = pair().await;
            let (result, ()) = tokio::join!(control.probe_anchor(), async {
                let (get, _) = request(&mut peer, "GETANCHOR").await;
                reply(&mut peer, &get, status, Some(reply_body)).await;
            });
            assert!(result.is_err());
        }
    }
    #[tokio::test]
    async fn take_while_waiting_for_receiver_clock_discards_the_inflight_au() {
        let (mut control, channel, mut peer) = pair().await;
        assert!(control.prepare_packet(788, 4321, control.loss_generation()).await.unwrap());
        let (result, ()) = tokio::join!(control.packet_written(788, 4321), async {
            let (get, _) = request(&mut peer, "GETANCHOR").await;
            channel.buffered_ownership.publish(false);
            reply(&mut peer, &get, 200, Some(clock(16))).await;
            let (flush, cut) = request(&mut peer, "FLUSHBUFFERED").await;
            assert_eq!(cut.get("flushUntilSeq"), Some(&Value::int(788)));
            assert_eq!(cut.get("flushUntilTS"), Some(&Value::int(5344)));
            reply(&mut peer, &flush, 200, None).await;
            let (pause, body) = request(&mut peer, "SETRATEANCHORTIME").await;
            assert_eq!(body.get("rate"), Some(&Value::int(0)));
            reply(&mut peer, &pause, 200, None).await;
        });
        result.unwrap();
        assert!(!control.prepare_packet(789, 5345, control.loss_generation()).await.unwrap());
    }

    #[tokio::test]
    async fn rejected_flush_still_pauses_and_latches_failure_without_pcm() {
        let (mut control, channel, mut peer) = pair().await;
        assert!(control.prepare_packet(7, 9, control.loss_generation()).await.unwrap());
        let (written, ()) = tokio::join!(control.packet_written(7, 9), anchor(&mut peer, 9, 11));
        written.unwrap();
        channel.buffered_ownership.publish(false);
        let (flush, _) = request(&mut peer, "FLUSHBUFFERED").await;
        reply(&mut peer, &flush, 400, None).await;
        let (pause, body) = request(&mut peer, "SETRATEANCHORTIME").await;
        assert_eq!(body.get("rate"), Some(&Value::int(0)));
        reply(&mut peer, &pause, 200, None).await;
        timeout(Duration::from_secs(1), async {
            while !control.failed() { tokio::task::yield_now().await; }
        }).await.unwrap();
        channel.buffered_ownership.publish(true);
        assert!(control.prepare_packet(8, 1033, control.loss_generation()).await.is_err());
    }

    #[tokio::test]
    async fn retiring_during_flush_never_sends_a_pause_after_teardown() {
        let (mut control, channel, mut peer) = pair().await;
        assert!(control.prepare_packet(7, 9, control.loss_generation()).await.unwrap());
        let (written, ()) = tokio::join!(control.packet_written(7, 9), anchor(&mut peer, 9, 11));
        written.unwrap();
        channel.buffered_ownership.publish(false);
        let (flush, _) = request(&mut peer, "FLUSHBUFFERED").await;
        drop(control);
        reply(&mut peer, &flush, 200, None).await;
        let _ordered = channel.buffered_commands.lock().await;
        let (result, ()) = tokio::join!(
            channel.request("TEARDOWN", URI, &[], "application/x-apple-binary-plist", &[], Duration::from_secs(1)),
            async {
                let msg = read_message(&mut peer).await;
                assert_eq!(msg.first_line, format!("TEARDOWN {URI} RTSP/1.0"));
                reply(&mut peer, &msg, 200, None).await;
            });
        assert_eq!(result.unwrap().status(), 200);
    }
    #[tokio::test]
    async fn stalled_control_allows_same_generation_priming_but_blocks_writes_during_flush() {
        let (mut control, channel, mut peer) = pair().await;
        let generation = control.loss_generation();
        assert!(control.prepare_packet(1, 1024, generation).await.unwrap());
        timeout(Duration::from_millis(50), control.packet_written(1, 1024))
            .await.expect("audio pump must continue during RTSP").unwrap();
        let (get, _) = request(&mut peer, "GETANCHOR").await;
        assert!(!control.playing());
        assert!(timeout(Duration::from_millis(50), control.prepare_packet(2, 2048, generation))
            .await.expect("control backlog must not stall navigation").unwrap());
        control.packet_written(2, 2048).await.unwrap();
        reply(&mut peer, &get, 200, Some(clock(5))).await;
        let (play, _) = request(&mut peer, "SETRATEANCHORTIME").await;
        reply(&mut peer, &play, 200, None).await;
        channel.buffered_ownership.publish(false);
        let (flush, cut) = request(&mut peer, "FLUSHBUFFERED").await;
        assert_eq!(cut.get("flushUntilSeq"), Some(&Value::int(2)));
        assert_eq!(cut.get("flushUntilTS"), Some(&Value::int(3071)));
        channel.buffered_ownership.publish(true);
        let fresh = control.loss_generation();
        assert!(!timeout(Duration::from_millis(50), control.prepare_packet(2, 2048, fresh))
            .await.expect("ownership cleanup must not stall navigation").unwrap());
        reply(&mut peer, &flush, 200, None).await;
        let (pause, _) = request(&mut peer, "SETRATEANCHORTIME").await;
        reply(&mut peer, &pause, 200, None).await;
    }
}
