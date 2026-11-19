//! Receiver observations only. Never answer a resend request or steer a playback clock here.
use crate::{bplist::{self, Value}, emit_log, timing::monotonic_nanos};
use std::{sync::{Arc, atomic::{AtomicU64, Ordering}}, time::Duration};
use tokio::net::UdpSocket;

fn resend_request(packet: &[u8]) -> Option<(u16, u16, u16)> {
    if packet.len() != 8 || packet[0] != 0x80 || packet[1] != 0xd5 { return None; }
    let word = |i| u16::from_be_bytes([packet[i], packet[i+1]]);
    let count = word(6);
    (count != 0).then(|| (word(2), word(4), count))
}

pub(crate) struct ControlObserver {
    worker: tokio::task::JoinHandle<()>,
    label: String,
    requests: Arc<AtomicU64>,
}

impl Drop for ControlObserver {
    fn drop(&mut self) {
        self.worker.abort();
        emit_log(&format!("AUDIO-RTCP: {} stream retired observedD5={}", self.label, self.requests.load(Ordering::Relaxed)));
    }
}

impl ControlObserver {
    pub(crate) fn start(socket: Arc<UdpSocket>, label: String) -> Self {
        let local = socket.local_addr().map(|a| a.port()).ok();
        let peer = socket.peer_addr().map(|a| a.port()).ok();
        emit_log(&format!("AUDIO-RTCP: {label} listening localPort={local:?} peerControlPort={peer:?}"));
        let requests = Arc::new(AtomicU64::new(0));
        let worker = crate::callback_context::spawn(observe_control(socket, label.clone(), requests.clone(), emit_log));
        Self { worker, label, requests }
    }
}

async fn observe_control(socket: Arc<UdpSocket>, label: String, requests: Arc<AtomicU64>, log: impl Fn(&str)) {
    let period = Duration::from_secs(10);
    let mut summary = tokio::time::interval_at(tokio::time::Instant::now() + period, period);
    summary.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let (mut requested_packets, mut other, mut candidates, mut errors) = (0u64, 0u64, 0u64, 0u64);
    let mut last = None;
    // Any datagram longer than the fixed request has at least nine bytes here.
    let mut bytes = [0; 9];
    loop {
        tokio::select! {
            result = socket.recv(&mut bytes) => match result {
                Ok(n) => {
                    if let Some((request_seq, first, count)) = resend_request(&bytes[..n]) {
                        let seen = requests.fetch_add(1, Ordering::Relaxed) + 1;
                        requested_packets += u64::from(count);
                        let now = monotonic_nanos();
                        last = Some((first, count, now));
                        if seen <= 3 {
                            log(&format!("AUDIO-RTCP: {label} RX=0xD5 requestSeq={request_seq} firstMissingSeq={first} count={count} monoNs={now}"));
                        }
                    } else if n >= 2 && bytes[1] == 0xd5 {
                        candidates += 1;
                        if candidates == 1 {
                            log(&format!("AUDIO-RTCP: {label} unconfirmed 0xD5 header={:02x?} capturedBytes={n} (max=9)", &bytes[..n]));
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
                log(&format!("AUDIO-RTCP: {label} d5={} requestedPackets={requested_packets} unconfirmedD5={candidates} other={other} receiveErrors={errors} lastFirstCountMonoNs={last:?}", requests.load(Ordering::Relaxed)));
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
        let request = [0x80, 0xd5, 0x12, 0x34, 0xff, 0xff, 0, 2];
        assert_eq!(resend_request(&request), Some((0x1234, 65535, 2)));
        for n in 0..8 { assert_eq!(resend_request(&request[..n]), None); }
        for (i, v) in [(0, 0x40), (1, 0xd6), (7, 0)] {
            let mut invalid = request;
            invalid[i] = v;
            assert_eq!(resend_request(&invalid), None);
        }
        assert_eq!(resend_request(&[0x80, 0xd5, 0, 1, 0, 2, 0, 3, 4]), None);
    }

    #[tokio::test]
    async fn connected_control_socket_observes_only_its_peer_and_never_replies() {
        let peer = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let other = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let local = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        local.connect(peer.local_addr().unwrap()).await.unwrap();
        let addr = local.local_addr().unwrap();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let count = Arc::new(AtomicU64::new(0));
        let reader = tokio::spawn(observe_control(local.clone(), "RTSP#9".into(), count.clone(),
            move |line| { tx.send(line.to_owned()).unwrap(); }));
        other.send_to(&[0x80, 0xd5, 0, 0, 0, 0, 0, 9], addr).await.unwrap();
        peer.send_to(&[0x80, 0xd5, 0, 0, 0, 1, 0, 2], addr).await.unwrap();
        let line = tokio::time::timeout(Duration::from_secs(1), rx.recv()).await.unwrap().unwrap();
        assert!(line.contains("RTSP#9 RX=0xD5") && line.contains("firstMissingSeq=1 count=2"));
        assert_eq!(count.load(Ordering::Relaxed), 1);
        assert!(tokio::time::timeout(Duration::from_millis(20), peer.recv(&mut [0; 16])).await.is_err());
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
