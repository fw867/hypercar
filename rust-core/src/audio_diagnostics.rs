//! Receiver feedback and retransmission of already sealed realtime main audio RTP.
use crate::{bplist::{self, Value}, buffered_ownership::Ownership, emit_log, timing::monotonic_nanos};
use parking_lot::Mutex;
use std::{collections::VecDeque, net::{IpAddr, SocketAddr}, sync::{Arc, atomic::{AtomicBool, AtomicU64, Ordering}}, time::{Duration, Instant}};
use tokio::{net::UdpSocket, sync::watch};

const CACHE_PACKETS: usize = 32;
const PCM_CACHE_PACKETS: usize = 72;
const CACHE_AGE: Duration = Duration::from_millis(500);
const MAX_PER_REQUEST: u16 = 16;
const MAX_PER_SECOND: u16 = 32;

struct CachedRtp {
    seq: u16,
    generation: u64,
    sent_at: Instant,
    wire: Vec<u8>,
}

struct ResendCache {
    active: AtomicBool,
    capacity: usize,
    ownership: watch::Receiver<Ownership>,
    packets: Mutex<VecDeque<CachedRtp>>,
}

impl ResendCache {
    fn new(ownership: watch::Receiver<Ownership>, capacity: usize) -> Self {
        Self { active: AtomicBool::new(true), capacity, ownership, packets: Mutex::new(VecDeque::with_capacity(capacity)) }
    }

    fn generation(&self) -> Option<u64> {
        let state = self.ownership.borrow();
        (state.owned && self.active.load(Ordering::Acquire)).then_some(state.losses)
    }

    fn remember(&self, seq: u16, generation: u64, wire: Vec<u8>) {
        let mut packets = self.packets.lock();
        if packets.back().is_some_and(|packet| packet.generation != generation) { packets.clear(); }
        if packets.len() == self.capacity { packets.pop_front(); }
        packets.push_back(CachedRtp { seq, generation, sent_at: Instant::now(), wire });
    }

    fn response(&self, seq: u16, generation: u64) -> Option<Vec<u8>> {
        let packets = self.packets.lock();
        let original = &packets.iter().rev().find(|p|
            p.seq == seq && p.generation == generation && p.sent_at.elapsed() <= CACHE_AGE)?.wire;
        let length = u16::try_from(original.len()).ok()?;
        let mut response = Vec::with_capacity(4 + original.len());
        response.extend_from_slice(&[0x80, 0xd6]);
        // xiap2 _audioReceiveRTCP writes the original RTP byte count as a native
        // little-endian halfword. The reference receiver uses the datagram size
        // and dispatches everything after the four-byte header to its RTP path.
        response.extend_from_slice(&length.to_le_bytes());
        response.extend_from_slice(original);
        Some(response)
    }
}

#[derive(Default)]
struct ResendTotals {
    hits: u64,
    misses: u64,
    sent: u64,
    futile_sent: u64,
    send_errors: u64,
    skipped: u64,
}

impl ResendTotals {
    fn add(&mut self, result: &Self) {
        self.hits += result.hits;
        self.misses += result.misses;
        self.sent += result.sent;
        self.futile_sent += result.futile_sent;
        self.send_errors += result.send_errors;
        self.skipped += result.skipped;
    }

    fn fields(&self) -> String {
        format!("cacheHits={} cacheMisses={} d6Sent={} d6SendErrors={} d6FutileSent={} resendSkipped={}",
            self.hits, self.misses, self.sent, self.send_errors, self.futile_sent, self.skipped)
    }
}

fn resend(socket: &UdpSocket, destination: Option<SocketAddr>, cache: &ResendCache, first: u16, count: u16, budget: &mut u16) -> ResendTotals {
    let limit = count.min(MAX_PER_REQUEST);
    let mut result = ResendTotals { skipped: u64::from(count - limit), ..Default::default() };
    let Some(generation) = cache.generation() else {
        result.skipped = u64::from(count);
        return result;
    };
    for offset in 0..limit {
        let seq = first.wrapping_add(offset);
        let response = cache.response(seq, generation);
        let hit = response.is_some();
        result.hits += u64::from(hit);
        result.misses += u64::from(!hit);
        // Keep the ownership check and nonblocking write together across handover.
        let state = cache.ownership.borrow();
        if *budget == 0 || !state.owned || state.losses != generation || !cache.active.load(Ordering::Acquire) {
            result.skipped += 1;
            continue;
        }
        *budget -= 1;
        // xiap2 uses an eight-byte negative response with original sequence and zero pad.
        let [hi, lo] = seq.to_be_bytes();
        let futile = [0x80, 0xd6, 4, 0, hi, lo, 0, 0];
        let bytes = response.as_deref().unwrap_or(&futile);
        let socket = socket2::SockRef::from(socket);
        let written = match destination {
            Some(peer) => socket.send_to_with_flags(bytes, &peer.into(), libc::MSG_DONTWAIT),
            None => socket.send_with_flags(bytes, libc::MSG_DONTWAIT),
        };
        match written {
            Ok(n) if n == bytes.len() && hit => result.sent += 1,
            Ok(n) if n == bytes.len() => result.futile_sent += 1,
            _ => result.send_errors += 1,
        }
    }
    result
}

fn resend_request(packet: &[u8]) -> Option<(u16, u16, u16)> {
    if !matches!(packet.len(), 8 | 16) || packet[0] != 0x80 || packet[1] != 0xd5 { return None; }
    let word = |i| u16::from_be_bytes([packet[i], packet[i+1]]);
    let count = word(6);
    let length = word(2);
    // The optional NTP timestamp contributes two more words.
    (usize::from(length) == packet.len() / 4 - 1 && count != 0).then(|| (length, word(4), count))
}

pub(crate) struct ControlObserver {
    worker: tokio::task::JoinHandle<()>,
    label: String,
    requests: Arc<AtomicU64>,
    cache: Arc<ResendCache>,
    totals: Arc<Mutex<ResendTotals>>,
}

impl Drop for ControlObserver {
    fn drop(&mut self) {
        self.cache.active.store(false, Ordering::Release);
        self.worker.abort();
        emit_log(&format!("AUDIO-RTCP: {} stream retired observedD5={} {}", self.label,
            self.requests.load(Ordering::Relaxed), self.totals.lock().fields()));
    }
}

impl ControlObserver {
    pub(crate) fn start(socket: Arc<UdpSocket>, label: String, ownership: watch::Receiver<Ownership>,
        pcm: bool, peer_ip: Option<IpAddr>) -> Self {
        let local = socket.local_addr().map(|a| a.port()).ok();
        let peer = socket.peer_addr().map(|a| a.port()).ok();
        let source = if peer_ip.is_some() { "session_address" } else { "negotiated_endpoint" };
        emit_log(&format!("AUDIO-RTCP: {label} listening localPort={local:?} peerControlPort={peer:?} sourceFilter={source}"));
        let requests = Arc::new(AtomicU64::new(0));
        let cache = Arc::new(ResendCache::new(ownership, if pcm { PCM_CACHE_PACKETS } else { CACHE_PACKETS }));
        let totals = Arc::new(Mutex::new(ResendTotals::default()));
        let worker = crate::callback_context::spawn(observe_control(socket, peer_ip, label.clone(), requests.clone(), cache.clone(), totals.clone(), emit_log));
        Self { worker, label, requests, cache, totals }
    }

    pub(crate) fn generation(&self) -> Option<u64> { self.cache.generation() }

    pub(crate) fn remember_sent(&self, seq: u16, generation: u64, wire: Vec<u8>) {
        self.cache.remember(seq, generation, wire);
    }
}

async fn observe_control(socket: Arc<UdpSocket>, peer_ip: Option<IpAddr>, label: String, requests: Arc<AtomicU64>,
    cache: Arc<ResendCache>, totals: Arc<Mutex<ResendTotals>>, log: impl Fn(&str)) {
    let period = Duration::from_secs(10);
    let mut summary = tokio::time::interval_at(tokio::time::Instant::now() + period, period);
    summary.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let (mut requested_packets, mut other, mut candidates, mut errors) = (0u64, 0u64, 0u64, 0u64);
    let mut last = None;
    let mut budget = MAX_PER_SECOND;
    let mut budget_window = Instant::now();
    // The sentinel byte distinguishes an oversized datagram from the extended request.
    let mut bytes = [0; 17];
    loop {
        tokio::select! {
            result = socket.recv_from(&mut bytes) => match result {
                Ok((n, source)) => {
                    if peer_ip.is_some_and(|expected| expected.to_canonical() != source.ip().to_canonical()) {
                        continue;
                    }
                    if let Some((length, first, count)) = resend_request(&bytes[..n]) {
                        let seen = requests.fetch_add(1, Ordering::Relaxed) + 1;
                        requested_packets += u64::from(count);
                        let now = monotonic_nanos();
                        last = Some((first, count, now));
                        if budget_window.elapsed() >= Duration::from_secs(1) {
                            budget = MAX_PER_SECOND;
                            budget_window = Instant::now();
                        }
                        let result = resend(&socket, peer_ip.map(|_| source), &cache, first, count, &mut budget);
                        totals.lock().add(&result);
                        if seen <= 3 {
                            log(&format!("AUDIO-RTCP: {label} RX=0xD5 lengthWordsMinusOne={length} firstMissingSeq={first} count={count} sourcePort={} monoNs={now} {}", source.port(), result.fields()));
                        }
                    } else if n >= 2 && bytes[1] == 0xd5 {
                        candidates += 1;
                        if candidates == 1 {
                            log(&format!("AUDIO-RTCP: {label} unconfirmed 0xD5 header={:02x?} capturedBytes={n} (max=17)", &bytes[..n]));
                        }
                    } else { other += 1; }
                }
                Err(error) => {
                    errors += 1;
                    if errors == 1 { log(&format!("AUDIO-RTCP: {label} receive error={error}")); }
                    tokio::time::sleep(Duration::from_millis(250)).await;
                }
            },
            _ = summary.tick() => {
                log(&format!("AUDIO-RTCP: {label} d5={} requestedPackets={requested_packets} unconfirmedD5={candidates} other={other} receiveErrors={errors} lastFirstCountMonoNs={last:?} {}",
                    requests.load(Ordering::Relaxed), totals.lock().fields()));
            }
        }
    }
}

#[derive(Default)]
pub(crate) struct FeedbackTrace {
    layout: String,
    repeats: u64,
}

impl FeedbackTrace {
    pub(crate) fn observe(&mut self, rtsp: u64, cseq: Option<i32>, body: &[u8]) -> Option<String> {
        let mut layout = String::new();
        let mut rows = Vec::new();
        if body.is_empty() { layout.push_str("empty_body"); }
        else if body.len() > 65536 { layout.push_str("body_over_64KiB"); }
        else if let Ok(plist) = bplist::parse(body) {
            if let Some(streams) = plist.get("streams").and_then(Value::as_array) {
                layout.push_str("no_audio_stream");
                for stream in streams {
                    let Some(kind @ 100..=103) = stream.get("type").and_then(Value::as_int) else { continue; };
                    if rows.is_empty() { layout.clear(); }
                    if rows.len() == 8 {
                        layout.push_str(" more");
                        rows.push("additional_streams_omitted".into());
                        break;
                    }
                    let id = stream.get("streamConnectionID").and_then(Value::as_int);
                    let mut row = match id {
                        Some(id) => format!("type={kind} streamConnectionID={id}"),
                        None => format!("type={kind} streamConnectionID=unknown attribution=unknown"),
                    };
                    layout.push_str(&format!(" {kind}/{id:?}"));
                    for field in ["sr", "mediaTime", "wallTime", "sampleTime", "timestamp", "timestampRawNs"] {
                        let number = match stream.get(field) {
                            Some(Value::Int(n)) => Some(n.to_string()),
                            Some(Value::Real(n)) if n.is_finite() => Some(n.to_string()),
                            _ => None,
                        };
                        layout.push(if number.is_some() { '+' } else { '-' });
                        row.push_str(&format!(" {field}={}", number.as_deref().unwrap_or("NA")));
                    }
                    rows.push(row);
                }
            } else { layout.push_str("missing_or_invalid_streams"); }
        } else { layout.push_str("invalid_plist"); }
        if self.layout != layout {
            self.layout = layout;
            self.repeats = 0;
        }
        self.repeats += 1;
        // Capture a consecutive pair when fields first appear, then sample steady replies.
        if self.repeats > 2 && self.repeats % 10 != 0 { return None; }
        let detail = if rows.is_empty() {
            format!("{} streamConnectionID=unknown attribution=unknown", self.layout)
        } else { rows.join(" | ") };
        Some(format!("AUDIO-FEEDBACK: RTSP#{rtsp} CSeq={cseq:?} monoNs={} bodyBytes={} {detail}", monotonic_nanos(), body.len()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resend_evidence_requires_the_full_request_and_nonzero_count() {
        let request = [0x80, 0xd5, 0, 1, 0xff, 0xff, 0, 2];
        assert_eq!(resend_request(&request), Some((1, 65535, 2)));
        for n in 0..8 { assert_eq!(resend_request(&request[..n]), None); }
        for (i, v) in [(0, 0x40), (1, 0xd6), (3, 0), (3, 2), (7, 0)] {
            let mut invalid = request;
            invalid[i] = v;
            assert_eq!(resend_request(&invalid), None);
        }
        assert_eq!(resend_request(&[0x80, 0xd5, 0, 1, 0, 2, 0, 3, 4]), None);
        let extended = [0x80, 0xd5, 0, 3, 0xff, 0xff, 0, 2, 1, 2, 3, 4, 5, 6, 7, 8];
        assert_eq!(resend_request(&extended), Some((3, 65535, 2)));
        assert_eq!(resend_request(&extended[..15]), None);
    }

    #[tokio::test]
    async fn resend_cache_expires_packets_and_bounds_each_request() {
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish(true);
        let cache = ResendCache::new(ownership.subscribe(), CACHE_PACKETS);
        let generation = cache.generation().unwrap();
        for seq in 0..=CACHE_PACKETS as u16 {
            cache.remember(seq, generation, vec![0x80, 0x66]);
        }
        assert!(cache.response(0, generation).is_none());
        cache.packets.lock().front_mut().unwrap().sent_at = Instant::now() - CACHE_AGE - Duration::from_millis(1);
        assert!(cache.response(1, generation).is_none());
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.connect(peer.local_addr().unwrap()).await.unwrap();
        let mut budget = 2;
        let result = resend(&socket, None, &cache, 2, u16::MAX, &mut budget);
        assert_eq!((result.hits, result.sent, result.misses, result.send_errors), (16, 2, 0, 0));
        assert_eq!(result.skipped, u64::from(u16::MAX) - 2);
        assert_eq!(budget, 0);
    }

    #[tokio::test]
    async fn listener_without_peer_port_accepts_extended_request_only_from_session_address() {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let other = UdpSocket::bind("127.0.0.2:0").await.unwrap();
        let local = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let address = local.local_addr().unwrap();
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish(true);
        let observer = ControlObserver::start(local, "PCM test".into(), ownership.subscribe(), true,
            Some(peer.local_addr().unwrap().ip()));
        observer.remember_sent(9, observer.generation().unwrap(), vec![0x80, 100, 0, 9]);
        let request = [0x80, 0xd5, 0, 3, 0, 9, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8];
        other.send_to(&request, address).await.unwrap();
        peer.send_to(&request, address).await.unwrap();
        let mut buf = [0; 16];
        let (n, origin) = tokio::time::timeout(Duration::from_secs(1), peer.recv_from(&mut buf)).await.unwrap().unwrap();
        assert_eq!(origin, address);
        assert_eq!(&buf[..n], &[0x80, 0xd6, 4, 0, 0x80, 100, 0, 9]);
        assert!(tokio::time::timeout(Duration::from_millis(20), other.recv(&mut buf)).await.is_err());
        assert_eq!(observer.requests.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn connected_control_socket_ignores_other_peers_and_logs_cache_misses() {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let other = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let local = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        local.connect(peer.local_addr().unwrap()).await.unwrap();
        let addr = local.local_addr().unwrap();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let count = Arc::new(AtomicU64::new(0));
        let ownership = crate::buffered_ownership::BufferedOwnership::default();
        ownership.publish(true);
        let cache = Arc::new(ResendCache::new(ownership.subscribe(), CACHE_PACKETS));
        let reader = tokio::spawn(observe_control(local.clone(), None, "RTSP#9".into(), count.clone(),
            cache, Arc::new(Mutex::new(ResendTotals::default())),
            move |line| { tx.send(line.to_owned()).unwrap(); }));
        other.send_to(&[0x80, 0xd5, 0, 1, 0, 0, 0, 9], addr).await.unwrap();
        peer.send_to(&[0x80, 0xd5, 0, 1, 0, 1, 0, 2], addr).await.unwrap();
        let line = tokio::time::timeout(Duration::from_secs(1), rx.recv()).await.unwrap().unwrap();
        assert!(line.contains("RTSP#9 RX=0xD5") && line.contains("firstMissingSeq=1 count=2"));
        assert!(line.contains("lengthWordsMinusOne=1") && !line.contains("requestSeq"));
        assert!(line.contains("cacheHits=0 cacheMisses=2 d6Sent=0 d6SendErrors=0"));
        assert_eq!(count.load(Ordering::Relaxed), 1);
        for seq in [1u8, 2] {
            let mut response = [0; 16];
            let n = tokio::time::timeout(Duration::from_secs(1), peer.recv(&mut response)).await.unwrap().unwrap();
            assert_eq!(&response[..n], &[0x80, 0xd6, 4, 0, 0, seq, 0, 0]);
        }
        assert_eq!(local.send(&[0x80, 0xd4]).await.unwrap(), 2);
        let mut sync = [0; 16];
        let (n, source) = peer.recv_from(&mut sync).await.unwrap();
        assert_eq!((&sync[..n], source), (&[0x80, 0xd4][..], addr));
        reader.abort();
        let _ = reader.await;
    }

    #[test]
    fn feedback_keeps_raw_clock_values_separate_and_reports_new_fields_immediately() {
        let mut trace = FeedbackTrace::default();
        for _ in 0..2 { assert!(trace.observe(1, Some(2), &[]).unwrap().contains("empty_body")); }
        assert!(trace.observe(1, Some(3), &[]).is_none());
        let response = |streams| bplist::encode(&Value::dict(vec![("streams", Value::array(streams))]));
        let video = Value::dict(vec![("type", Value::int(110))]);
        assert!(trace.observe(1, None, &response(vec![video])).unwrap().contains("no_audio_stream"));
        let primary = Value::dict(vec![("type", Value::int(102)), ("streamConnectionID", Value::int(42)),
            ("sr", Value::real(47999.75)), ("mediaTime", Value::int(512)), ("wallTime", Value::int(9007199254740993))]);
        let alternate = Value::dict(vec![("type", Value::int(103)), ("sr", Value::int(48000)),
            ("sampleTime", Value::int(0)), ("timestamp", Value::int(4294967295)), ("timestampRawNs", Value::int(123456))]);
        let body = response(vec![primary, alternate]);
        let line = trace.observe(1, Some(8), &body).unwrap();
        assert!(line.contains("RTSP#1 CSeq=Some(8)"));
        assert!(line.contains("type=102 streamConnectionID=42 sr="));
        assert!(line.contains("type=103 streamConnectionID=unknown attribution=unknown sr="));
        assert!(line.contains("sr=47999.75 mediaTime=512 wallTime=9007199254740993 sampleTime=NA"));
        assert!(line.contains("type=103") && line.contains("sr=48000 mediaTime=NA wallTime=NA sampleTime=0 timestamp=4294967295 timestampRawNs=123456"));
        assert!(trace.observe(1, None, &body).is_some());
        assert!(trace.observe(1, None, &body).is_none());
        assert!(FeedbackTrace::default().observe(2, Some(1), &body).unwrap().contains("RTSP#2"));
        assert!(trace.observe(1, None, b"broken").unwrap().contains("invalid_plist streamConnectionID=unknown attribution=unknown"));
    }

    #[test]
    fn feedback_without_an_id_after_rebuild_never_inherits_the_previous_main_stream() {
        let mut trace = FeedbackTrace::default();
        for (rtsp, cseq, id) in [(3, 10, Some(91)), (3, 12, Some(92)), (3, 13, None), (4, 1, None)] {
            let mut fields = vec![("type", Value::int(102))];
            if let Some(id) = id { fields.push(("streamConnectionID", Value::int(id))); }
            let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(fields)]))]));
            let line = trace.observe(rtsp, Some(cseq), &body).unwrap();
            assert!(line.contains(&format!("RTSP#{rtsp} CSeq=Some({cseq})")));
            match id {
                Some(n) => assert!(line.contains(&format!("streamConnectionID={n} "))),
                None => assert!(line.contains("type=102 streamConnectionID=unknown attribution=unknown")),
            }
        }
    }
}
