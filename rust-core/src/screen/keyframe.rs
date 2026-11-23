//! Track observations independently on either side of optional transcoding. A picture
//! dequeued after a request can already have been queued or be a cached replay.
use crate::video_format::{FrameOrigin, VideoCodec};
use parking_lot::Mutex;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};

const MAX_PENDING: usize = 8;

#[derive(Clone, Copy, Debug)]
struct Request {
    id: u64, reason: &'static str, at_ms: u64,
    source: bool, sender: bool, written: bool,
}

#[derive(Clone, Copy)]
enum Stage { Source { queue_age_ms: Option<u64> }, Sender, Written }

#[derive(Default, Debug)]
struct Requests { sequence: u64, pending: VecDeque<Request>, released: bool }

impl Requests {
    fn push(&mut self, reason: &'static str, at_ms: u64) -> (Request, Option<Request>) {
        self.sequence += 1;
        let request = Request { id: self.sequence, reason, at_ms, source: false, sender: false, written: false };
        let evicted = if self.pending.len() == MAX_PENDING { self.pending.pop_front() } else { None };
        self.pending.push_back(request);
        (request, evicted)
    }

    fn observe(&mut self, stage: Stage, cutoff: u64) -> Vec<Request> {
        let mut seen = Vec::new();
        for r in self.pending.iter_mut().take_while(|r| r.id <= cutoff) {
            let flag = match stage {
                Stage::Source { .. } => &mut r.source,
                Stage::Sender => &mut r.sender,
                Stage::Written if r.sender => &mut r.written,
                Stage::Written => continue,
            };
            if !*flag { *flag = true; seen.push(*r); }
        }
        self.pending.retain(|r| !(r.source && r.written));
        seen
    }
}

#[derive(Debug)]
pub(crate) struct Trace {
    engine: u64,
    pub(super) control: Option<u64>,
    pub(super) connection: u64,
    requests: Mutex<Requests>,
    /// Fast path for ordinary access units when nobody has requested a keyframe.
    pending_through: AtomicU64,
}

pub(super) fn now_ms() -> u64 { crate::timing::monotonic_nanos() / 1_000_000 }

pub(super) fn sync_frame(codec: VideoCodec, nals: &[&[u8]]) -> Option<&'static str> {
    nals.iter().find_map(|nal| match codec {
        VideoCodec::H264 => (super::nal_type(nal) == 5).then_some("idr"),
        VideoCodec::Hevc => match crate::hevc::nal_type(nal) {
            Some(19 | 20) => Some("idr"),
            Some(21) => Some("cra"),
            _ => None,
        },
    })
}

impl Trace {
    pub(super) fn new(engine: u64, control: Option<u64>, connection: u64) -> Self {
        Self { engine, control, connection, requests: Mutex::default(), pending_through: AtomicU64::new(0) }
    }

    pub(super) fn watermark(&self) -> u64 { self.pending_through.load(Ordering::Acquire) }

    #[cfg(test)]
    pub(crate) fn pending_with_reason(&self, reason: &str) -> usize {
        self.requests.lock().pending.iter().filter(|r| r.reason == reason).count()
    }

    fn emit(&self, at: u64, event: &str, r: Request, detail: std::fmt::Arguments<'_>) {
        crate::emit_media_trace(at, event, &format!(
            "engine={} RTSP#{} conn={} request={} reason={} elapsed_ms={} {detail}",
            self.engine, self.control.unwrap_or(0), self.connection, r.id, r.reason, at.saturating_sub(r.at_ms)));
    }

    fn end(&self, at: u64, r: Request, why: &str) {
        self.emit(at, "keyframe_end", r, format_args!(
            "status={why} source_sync={} sender_sync={} tcp_written={}", r.source, r.sender, r.written));
    }

    pub(super) fn request(&self, reason: &'static str) {
        let at = now_ms();
        let (r, evicted) = {
            let mut requests = self.requests.lock();
            if requests.released { return; }
            let result = requests.push(reason, at);
            self.pending_through.store(result.0.id, Ordering::Release);
            result
        };
        if let Some(old) = evicted { self.end(at, old, "capacity"); }
        self.emit(at, "keyframe_request", r, format_args!("status=requested"));
    }

    fn observe(&self, stage: Stage, cutoff: u64, codec: VideoCodec, frame: &str, pts_us: u64, origin: FrameOrigin, wire: usize) {
        if cutoff == 0 { return; }
        let at = now_ms();
        let seen = {
            let mut requests = self.requests.lock();
            let seen = requests.observe(stage, cutoff);
            if requests.pending.is_empty() { self.pending_through.store(0, Ordering::Release); }
            seen
        };
        let event = match stage { Stage::Source { .. } => "keyframe_source_sync", Stage::Sender => "keyframe_sender_sync",
            Stage::Written => "keyframe_tcp_write" };
        for r in seen {
            let detail = match stage {
                Stage::Source { queue_age_ms } => format!(" queue_age_ms={}", queue_age_ms.map_or(-1, i128::from)),
                Stage::Sender => String::new(),
                Stage::Written => format!(" wire_bytes={wire}"),
            };
            self.emit(at, event, r, format_args!("codec={} sync_frame={frame} pts_us={pts_us} origin={}{detail}",
                codec.as_str(), origin.as_str()));
        }
    }

    pub(super) fn source(&self, cutoff: u64, codec: VideoCodec, frame: &str, pts_us: u64, origin: FrameOrigin, queue_age_ms: Option<u64>) {
        self.observe(Stage::Source { queue_age_ms }, cutoff, codec, frame, pts_us, origin, 0);
    }
    pub(super) fn sender(&self, cutoff: u64, codec: VideoCodec, frame: &str, pts_us: u64, origin: FrameOrigin) {
        self.observe(Stage::Sender, cutoff, codec, frame, pts_us, origin, 0);
    }
    pub(super) fn written(&self, cutoff: u64, codec: VideoCodec, frame: &str, pts_us: u64, origin: FrameOrigin, bytes: usize) {
        self.observe(Stage::Written, cutoff, codec, frame, pts_us, origin, bytes);
    }

    pub(super) fn released(&self) {
        let pending = {
            let mut requests = self.requests.lock();
            requests.released = true;
            self.pending_through.store(0, Ordering::Release);
            std::mem::take(&mut requests.pending)
        };
        let at = now_ms();
        for r in pending { self.end(at, r, "sender_released"); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_during_inflight_idr_needs_its_own_sender_observation_and_write() {
        let mut requests = Requests::default();
        let (first, _) = requests.push("resume", 10);
        assert_eq!(requests.observe(Stage::Source { queue_age_ms: Some(0) }, first.id).len(), 1);
        assert_eq!(requests.observe(Stage::Sender, first.id).len(), 1);
        let (second, _) = requests.push("forceKeyFrame", 15);
        let written = requests.observe(Stage::Written, first.id);
        assert_eq!(written.len(), 1);
        assert_eq!(written[0].id, first.id);
        assert!(requests.observe(Stage::Written, second.id).is_empty());
        assert_eq!(requests.observe(Stage::Sender, second.id).len(), 1);
        assert_eq!(requests.observe(Stage::Written, second.id).len(), 1);
        // A transcoder can produce an output IDR before the next upstream IDR.
        assert_eq!(requests.observe(Stage::Source { queue_age_ms: Some(12) }, second.id)[0].reason, "forceKeyFrame");
        assert!(requests.pending.is_empty());
    }

    #[test]
    fn storm_keeps_independent_requests_bounded_and_reports_the_displaced_request() {
        let mut requests = Requests::default();
        let (old, _) = requests.push("resume", 100);
        for n in 1..MAX_PENDING { assert!(requests.push("forceKeyFrame", 100 + n as u64).1.is_none()); }
        let (latest, evicted) = requests.push("await_config_idr", 120);
        assert_eq!(evicted.unwrap().id, old.id);
        let first_source = requests.observe(Stage::Source { queue_age_ms: None }, latest.id);
        assert_eq!(first_source.len(), MAX_PENDING);
        assert!(requests.observe(Stage::Source { queue_age_ms: None }, latest.id).is_empty());
    }

    #[tokio::test]
    async fn receiver_request_follows_its_connection_through_borrow_and_cached_replay() {
        use super::super::{observe_receiver_keyframe_request, ScreenCipher, ScreenSender};
        use std::sync::{Arc, atomic::AtomicBool};
        use tokio::net::{TcpListener, TcpStream};
        let pipeline = Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        pipeline.lock().control_trace_id = Some(72);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let connect = async |id| {
            let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (peer,_) = listener.accept().await.unwrap();
            let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
            sender.attach_diagnostics(pipeline.clone(), id);
            (sender, peer)
        };
        observe_receiver_keyframe_request(&pipeline, 72, "forceKeyFrame");
        let (mut sender, _peer) = connect(11).await;
        let trace = sender.keyframe_trace.clone().unwrap();
        observe_receiver_keyframe_request(&pipeline, 72, "forceKeyFrame");
        assert_eq!(trace.watermark(), 0);
        sender.activate_keyframe_trace();
        observe_receiver_keyframe_request(&pipeline, 71, "forceKeyFrame");
        assert_eq!(trace.watermark(), 0);
        observe_receiver_keyframe_request(&pipeline, 72, "forceKeyFrame");
        let idr = [0,0,1,0x67,0x42,0,0x1f, 0,0,1,0x68,1, 0,0,1,0x65,2];
        let owner = Arc::new(AtomicBool::new(false));
        sender.set_ownership(owner.clone());
        sender.observe_source_sync(&idr, 100, VideoCodec::H264, FrameOrigin::Cached, Some(42));
        sender.send_video_annexb_from(&idr, 100, FrameOrigin::Cached).await.unwrap();
        let blocked = trace.requests.lock().pending[0];
        assert!(blocked.source && blocked.sender && !blocked.written);
        owner.store(true, Ordering::Release);
        sender.send_video_annexb_from(&idr, 200, FrameOrigin::Live).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
        assert_eq!(trace.watermark(), 0);

        let (replacement, _peer2) = connect(12).await;
        replacement.activate_keyframe_trace();
        observe_receiver_keyframe_request(&pipeline, 72, "forceKeyFrame");
        sender.observe_source_sync(&idr, 300, VideoCodec::H264, FrameOrigin::Live, Some(0));
        sender.send_video_annexb_from(&idr, 300, FrameOrigin::Live).await.unwrap();
        let new_trace = replacement.keyframe_trace.as_ref().unwrap();
        let pending = new_trace.requests.lock().pending[0];
        assert!(!pending.source && !pending.sender && !pending.written);
        pipeline.lock().retire_video_tcp();
        observe_receiver_keyframe_request(&pipeline, 72, "forceKeyFrame");
        assert_eq!(new_trace.requests.lock().pending.len(), 1);
    }

    #[tokio::test]
    async fn hevc_cra_completes_the_keyframe_trace_after_tcp_write() {
        use super::super::{ScreenCipher, ScreenSender};
        let pipeline = std::sync::Arc::new(Mutex::new(crate::pipeline::Pipeline::new()));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_peer, _) = listener.accept().await.unwrap();
        let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1; 32]), 64, 48, false);
        sender.set_hevc_allowed(true);
        sender.set_video_codec(VideoCodec::Hevc).unwrap();
        sender.attach_diagnostics(pipeline, 42);
        sender.activate_keyframe_trace();
        sender.send_video_annexb(include_bytes!("../../tests/fixtures/hevc-phone/csd0.annexb"), 100).await.unwrap();
        sender.note_keyframe_request("resume");
        let cra = [0, 0, 1, 42, 1, 0x90];
        sender.observe_source_sync(&cra, 200, VideoCodec::Hevc, FrameOrigin::Live, Some(1));
        sender.send_video_annexb(&cra, 200).await.unwrap();
        assert_eq!(sender.frames_sent(), 1);
        assert!(sender.keyframe_trace.as_ref().unwrap().requests.lock().pending.is_empty());
    }
}
