//! Receiver-reported frame rate and local transport counters for one main screen socket.
use crate::{bplist::{self, Value}, pipeline::Pipeline};
use parking_lot::Mutex;
use std::sync::{Weak, atomic::{AtomicU64, Ordering}};

#[derive(Default)]
struct ReceiverStats {
    state: &'static str,
    stty: Option<i64>,
    rfps: Option<f64>,
    latency: Option<f64>,
    uuid: Option<uuid::Uuid>,
}

fn receiver_stats(body: &[u8], stream_id: u64) -> ReceiverStats {
    let unavailable = |state| ReceiverStats { state, ..Default::default() };
    if body.is_empty() { return unavailable("empty_body"); }
    if body.len() > 65536 { return unavailable("body_over_64KiB"); }
    let Ok(plist) = bplist::parse(body) else { return unavailable("invalid_plist"); };
    let Some(stats) = plist.get("statsDictionary") else { return unavailable("missing_dictionary"); };
    let Some(rows) = stats.as_dict() else { return unavailable("invalid_dictionary"); };
    let mut matches = rows.iter().filter(|(key, _)| {
        let id = match key {
            Value::String(key) => key.parse::<u64>().ok().or_else(|| key.parse::<i64>().ok().map(|n| n as u64)),
            Value::Int(key) => Some(*key as u64),
            _ => None,
        };
        id == Some(stream_id)
    });
    let Some((_, row)) = matches.next() else { return unavailable("stream_absent"); };
    if matches.next().is_some() { return unavailable("ambiguous_stream"); }
    if row.as_dict().is_none() { return unavailable("invalid_stream"); }
    let stty = row.get("stty");
    let stream_type = row.get("type");
    if [stty, stream_type].into_iter().flatten().any(|value| value.as_int() != Some(110)) {
        return unavailable("stream_type_mismatch");
    }
    let number = |field| match row.get(field) {
        Some(Value::Int(n)) => Some(*n as f64),
        Some(Value::Real(n)) if n.is_finite() => Some(*n),
        _ => None,
    };
    ReceiverStats {
        state: "matched", stty: stty.and_then(Value::as_int),
        rfps: number("rfps").filter(|n| *n >= 0.0), latency: number("ltnc"),
        uuid: row.get("uuid").and_then(Value::as_str).and_then(|s| uuid::Uuid::parse_str(s).ok()),
    }
}

#[derive(Clone)]
pub(crate) struct FeedbackTarget {
    stream_id: u64,
    writes: Weak<AtomicU64>,
}

impl FeedbackTarget {
    pub(crate) fn current(p: &Pipeline, rtsp: u64) -> Option<Self> {
        if p.control_trace_id != Some(rtsp) || p.main_video_writes.strong_count() == 0 { return None; }
        Some(Self { stream_id: p.video_tcp.as_ref()?.connection_id, writes: p.main_video_writes.clone() })
    }
}

#[derive(Default)]
pub(crate) struct FeedbackTrace {
    last: Option<(Weak<AtomicU64>, u64, u64)>,
}

impl FeedbackTrace {
    pub(crate) fn observe(&mut self, pipeline: &Mutex<Pipeline>, requested: &FeedbackTarget,
        rtsp: u64, cseq: i32, body: &[u8], now_ms: u64) -> Option<String> {
        let last = self.last.as_ref().filter(|(writes, _, _)| writes.ptr_eq(&requested.writes));
        let window_ms = last.map(|(_, then, _)| now_ms.saturating_sub(*then));
        if window_ms.is_some_and(|elapsed| elapsed < 10_000) { return None; }
        let (frames, tcp) = {
            let p = pipeline.lock();
            if p.control_trace_id != Some(rtsp) || !p.main_video_writes.ptr_eq(&requested.writes) { return None; }
            let tcp = p.video_tcp.as_ref().filter(|s| s.connection_id == requested.stream_id)?;
            (requested.writes.upgrade()?.load(Ordering::Relaxed), tcp.clone())
        };
        let fps = last.zip(window_ms).and_then(|((_, _, previous), ms)|
            frames.checked_sub(*previous).map(|delta| format!("{:.1}", delta as f64 * 1000.0 / ms as f64)));
        self.last = Some((requested.writes.clone(), now_ms, frames));
        let receiver = receiver_stats(body, requested.stream_id);
        fn field(v: Option<impl std::fmt::Display>) -> String {
            v.map(|n| n.to_string()).unwrap_or_else(|| "unknown".into())
        }
        Some(format!("VIDEO-FEEDBACK: RTSP#{rtsp} CSeq={cseq} streamConnectionID={} monoMs={now_ms} stats={} stty={} uuid={} rfps={} ltnc={} tcp_write_frames={frames} tcp_write_fps={} window_ms={} tcp_acked_bytes={} tcp_sample_age_ms={} receiver_display=unknown",
            requested.stream_id, receiver.state, field(receiver.stty), field(receiver.uuid), field(receiver.rfps),
            field(receiver.latency), field(fps), field(window_ms), field(tcp.acked_bytes), now_ms.saturating_sub(tcp.sampled_ms)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{bplist::{self, Value}, pipeline::Pipeline, tcp_diagnostics::TcpSnapshot};
    use std::sync::{Arc, atomic::AtomicU64};
    use parking_lot::Mutex;

    fn feedback(rows: Vec<(Value, Value)>) -> Vec<u8> {
        bplist::encode(&Value::dict(vec![("statsDictionary", Value::Dict(rows))]))
    }

    #[test]
    fn audi_capture_reports_zero_and_ignores_metadata_and_other_streams() {
        // CL3D, 18:30:55.119, CarPToCarL docs/carplay-black-screen-20261106.md §8.
        let body = feedback(vec![
            (Value::str("showTime"), Value::real(203.956)),
            (Value::str("7"), Value::dict(vec![("stty", Value::int(111)), ("rfps", Value::int(5))])),
            (Value::str("7926980582282045449"), Value::dict(vec![
                ("ahea", Value::int(0)), ("ltnc", Value::int(25)),
                ("stty", Value::int(110)), ("rfps", Value::int(0)),
            ])),
        ]);
        let row = receiver_stats(&body, 7926980582282045449);
        assert_eq!((row.rfps, row.latency), (Some(0.0), Some(25.0)));
        assert_eq!(receiver_stats(&body, 7).rfps, None);
        assert_eq!(receiver_stats(&body, 792698058228204544).rfps, None);
    }

    #[test]
    fn unsigned_keys_and_partial_rows_preserve_unknown_values() {
        let row = |key, rate| feedback(vec![(key, Value::dict(vec![
            ("stty", Value::int(110)), ("rfps", rate)
        ]))]);
        for key in [Value::str("18446744073709551615"), Value::int(-1)] {
            assert_eq!(receiver_stats(&row(key, Value::real(29.7)), u64::MAX).rfps, Some(29.7));
        }
        for rate in [Value::Null, Value::str("0"), Value::int(-1), Value::real(f64::NAN)] {
            assert_eq!(receiver_stats(&row(Value::str("1"), rate), 1).rfps, None);
        }
        let missing = feedback(vec![(Value::str("1"), Value::dict(vec![("stty", Value::int(110))]))]);
        for body in [&missing[..], &[][..], &b"bad plist"[..], &vec![0; 65537][..]] {
            assert_eq!(receiver_stats(body, 1).rfps, None);
        }
    }

    #[test]
    fn conflicting_stream_evidence_cannot_supply_receiver_fps() {
        let row = Value::dict(vec![("stty", Value::int(110)), ("type", Value::int(111)), ("rfps", Value::int(25))]);
        assert_eq!(receiver_stats(&feedback(vec![(Value::str("7"), row)]), 7).rfps, None);
        let main = Value::dict(vec![("stty", Value::int(110)), ("rfps", Value::int(30))]);
        let duplicate = feedback(vec![(Value::str("7"), main.clone()), (Value::int(7), main)]);
        assert_eq!(receiver_stats(&duplicate, 7).rfps, None);
    }

    #[tokio::test]
    async fn local_count_tracks_frame_writes_and_expires_with_the_main_sender() {
        use crate::screen::{build_avcc, ScreenCipher, ScreenSender};
        use tokio::net::{TcpListener, TcpStream};
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_peer, _) = listener.accept().await.unwrap();
        let mut sender = ScreenSender::new(phone.into_split().1, ScreenCipher::ChaCha([1; 32]), 64, 48, false);
        let pipeline = Arc::new(Mutex::new(Pipeline::new()));
        pipeline.lock().control_trace_id = Some(12);
        sender.attach_diagnostics(pipeline.clone(), 42);
        let target = FeedbackTarget::current(&pipeline.lock(), 12).unwrap();
        sender.send_config(&build_avcc(&[0x67,0x42,0,0x1f], &[0x68,1])).await.unwrap();
        sender.send_video_annexb(&[0,0,1,0x41,9], 1000).await.unwrap();
        let mut trace = FeedbackTrace::default();
        let first = trace.observe(&pipeline, &target, 12, 4, &[], 1000).unwrap();
        assert!(first.contains("tcp_write_frames=0 "));
        sender.send_video_annexb(&[0,0,1,0x65,9], 2000).await.unwrap();
        let next = trace.observe(&pipeline, &target, 12, 14, &[], 11000).unwrap();
        assert!(next.contains("tcp_write_frames=1 tcp_write_fps=0.1"));
        drop(sender);
        assert!(trace.observe(&pipeline, &target, 12, 24, &[], 21000).is_none());
    }

    fn install(p: &mut Pipeline, id: u64, frames: u64, acked: Option<u64>) -> Arc<AtomicU64> {
        let writer = Arc::new(AtomicU64::new(frames));
        p.main_video_writes = Arc::downgrade(&writer);
        p.video_tcp = Some(TcpSnapshot { connection_id:id, acked_bytes:acked, ..Default::default() });
        writer
    }

    #[test]
    fn matched_reply_samples_only_this_socket_and_rtsp_connection() {
        let pipeline = Mutex::new(Pipeline::new());
        pipeline.lock().control_trace_id = Some(12);
        let mut trace = FeedbackTrace::default();
        let body = feedback(vec![(Value::str("42"), Value::dict(vec![
            ("stty", Value::int(110)), ("rfps", Value::int(0))
        ]))]);
        let writer = install(&mut pipeline.lock(), 42, 10, Some(100));
        let request = FeedbackTarget::current(&pipeline.lock(), 12).unwrap();
        let first = trace.observe(&pipeline, &request, 12, 1, &body, 1000).unwrap();
        assert!(first.contains("RTSP#12 CSeq=1 streamConnectionID=42") && first.contains("rfps=0"));
        assert!(first.contains("tcp_write_fps=unknown") && first.contains("tcp_acked_bytes=100"));
        writer.store(310, std::sync::atomic::Ordering::Relaxed);
        assert!(trace.observe(&pipeline, &request, 12, 2, &body, 2000).is_none());
        let next = trace.observe(&pipeline, &request, 12, 10, &body, 11000).unwrap();
        assert!(next.contains("tcp_write_fps=30.0") && next.contains("window_ms=10000"));
        assert!(FeedbackTarget::current(&pipeline.lock(), 13).is_none());
        let replacement = install(&mut pipeline.lock(), 42, 9, None);
        assert!(trace.observe(&pipeline, &request, 12, 11, &body, 21000).is_none());
        let fresh = FeedbackTarget::current(&pipeline.lock(), 12).unwrap();
        let new = trace.observe(&pipeline, &fresh, 12, 12, &[], 21010).unwrap();
        assert!(new.contains("tcp_write_fps=unknown") && new.contains("tcp_acked_bytes=unknown") && new.contains("rfps=unknown"));
        pipeline.lock().control_trace_id = Some(13);
        assert!(trace.observe(&pipeline, &fresh, 12, 13, &body, 31010).is_none());
        drop(replacement);
        assert!(FeedbackTarget::current(&pipeline.lock(), 13).is_none());
    }
}
