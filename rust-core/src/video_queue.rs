//! Bound encoded video backlog before the main video pump without interrupting TCP writes.
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::mpsc;
use tokio::time::Instant;

use crate::engine::SourceEvent;

const MAX_AGE: Duration = Duration::from_millis(300);
const MAX_FRAMES: usize = 32;
const MAX_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone)]
pub(crate) struct Sender {
    wake: mpsc::Sender<()>,
    observer: Observer,
}

pub(crate) struct Receiver {
    wake: mpsc::Receiver<()>,
    observer: Observer,
    last_dequeue_age_ms: Option<u64>,
}

#[derive(Clone, Default)]
pub(crate) struct Observer(Arc<Mutex<State>>);

#[derive(Default)]
struct State {
    events: VecDeque<SourceEvent>,
    closed: bool,
    dropped_frames: usize,
    dropped_bytes: usize,
    dropped_oldest_ms: u64,
    // Only video events have timestamps. Control events retain their FIFO order.
    queued_at: VecDeque<Instant>,
    queued_bytes: usize,
    dequeue_connection: Option<u64>,
    dequeued_frames: u64,
    last_dequeued_ms: Option<u64>,
    max_dequeued_ms: Option<u64>,
    last_report: Option<Instant>,
}

pub(crate) struct Report {
    connection_id: u64,
    queued_frames: usize,
    queued_bytes: usize,
    oldest_ms: Option<u64>,
    dequeued_frames: u64,
    last_dequeued_ms: Option<u64>,
    max_dequeued_ms: Option<u64>,
}

pub(crate) fn channel() -> (Sender, Receiver) {
    let (tx, rx) = mpsc::channel(1);
    let observer = Observer::default();
    (Sender { wake: tx, observer: observer.clone() }, Receiver { wake: rx, observer, last_dequeue_age_ms: None })
}

fn frame_bytes(event: &SourceEvent) -> Option<usize> {
    match event {
        SourceEvent::VideoFrame { data, .. } | SourceEvent::VideoFrameTagged { data, .. } => Some(data.len()),
        _ => None,
    }
}

impl Sender {
    pub fn send(&self, event: SourceEvent) -> Result<(), mpsc::error::SendError<SourceEvent>> {
        let mut state = self.observer.0.lock();
        if state.closed { return Err(mpsc::error::SendError(event)); }
        if let Some(bytes) = frame_bytes(&event) {
            let now = Instant::now();
            state.expire_video(now);
            if state.queued_at.len() >= MAX_FRAMES || state.queued_bytes + bytes > MAX_BYTES {
                state.discard_video(now);
            }
            if bytes <= MAX_BYTES {
                state.queued_at.push_back(now);
                state.queued_bytes += bytes;
                state.events.push_back(event);
            } else {
                state.dropped_frames += 1;
                state.dropped_bytes += bytes;
            }
        } else {
            state.events.push_back(event);
        }
        let _ = self.wake.try_send(());
        Ok(())
    }
}

impl Receiver {
    pub fn last_dequeue_age_ms(&self) -> Option<u64> {
        self.last_dequeue_age_ms
    }

    pub fn observer(&self) -> Observer {
        self.observer.clone()
    }

    // The pump checks this before sending any already-converted output.
    pub fn take_discontinuity(&mut self) -> Option<SourceEvent> {
        let mut state = self.observer.0.lock();
        state.expire_video(Instant::now());
        state.take_discontinuity()
    }

    pub async fn recv(&mut self, connection: Option<u64>) -> Option<SourceEvent> {
        self.last_dequeue_age_ms = None;
        loop {
            {
                let mut state = self.observer.0.lock();
                let now = Instant::now();
                state.expire_video(now);
                if let Some(gap) = state.take_discontinuity() { return Some(gap); }
                if let Some(event) = state.events.pop_front() {
                    if let Some(bytes) = frame_bytes(&event) {
                        let queued = state.queued_at.pop_front().expect("each video event has a timestamp");
                        let age = (now - queued).as_millis() as u64;
                        self.last_dequeue_age_ms = Some(age);
                        state.queued_bytes -= bytes;
                        if state.dequeue_connection != connection {
                            state.reset_dequeues();
                            state.dequeue_connection = connection;
                        }
                        if connection.is_some() {
                            state.dequeued_frames += 1;
                            state.last_dequeued_ms = Some(age);
                            state.max_dequeued_ms = Some(state.max_dequeued_ms.unwrap_or(0).max(age));
                        }
                    }
                    return Some(event);
                }
                if self.wake.is_closed() { return None; }
            }
            let _ = self.wake.recv().await;
        }
    }
}

impl Drop for Receiver {
    fn drop(&mut self) {
        let mut state = self.observer.0.lock();
        state.closed = true;
        self.wake.close();
        state.events.clear();
        state.queued_at.clear();
        state.queued_bytes = 0;
    }
}

impl State {
    fn expire_video(&mut self, now: Instant) {
        if self.queued_at.front().is_some_and(|at| now - *at >= MAX_AGE) {
            self.discard_video(now);
        }
    }

    fn discard_video(&mut self, now: Instant) {
        if let Some(oldest) = self.queued_at.front() {
            self.dropped_oldest_ms = self.dropped_oldest_ms.max((now - *oldest).as_millis() as u64);
        }
        self.dropped_frames += self.queued_at.len();
        self.dropped_bytes += self.queued_bytes;
        self.queued_at.clear();
        self.queued_bytes = 0;
        self.events.retain(|event| frame_bytes(event).is_none());
    }

    fn take_discontinuity(&mut self) -> Option<SourceEvent> {
        (self.dropped_frames > 0).then(|| SourceEvent::VideoBacklogDropped {
            frames: std::mem::take(&mut self.dropped_frames),
            bytes: std::mem::take(&mut self.dropped_bytes),
            oldest_ms: std::mem::take(&mut self.dropped_oldest_ms),
        })
    }

    fn reset_dequeues(&mut self) {
        self.dequeued_frames = 0;
        self.last_dequeued_ms = None;
        self.max_dequeued_ms = None;
    }
}

impl Observer {
    /// Called on the independent health tick, including while a frame write is pending.
    pub fn report(&self, connection: Option<u64>) -> Option<Report> {
        let connection_id = connection?;
        let mut state = self.0.lock();
        let current = state.dequeue_connection == connection;
        let dequeued_frames = if current { state.dequeued_frames } else { 0 };
        let now = Instant::now();
        let oldest_ms = state.queued_at.front().map(|at| now.duration_since(*at).as_millis() as u64);
        let max_dequeued_ms = if current { state.max_dequeued_ms } else { None };
        let slow = oldest_ms.unwrap_or(0).max(max_dequeued_ms.unwrap_or(0)) >= 100;
        let interval = Duration::from_secs(if slow { 2 } else { 10 });
        if state.last_report.is_some_and(|at| now.duration_since(at) < interval) { return None; }
        let report = Report {
            connection_id, queued_frames: state.queued_at.len(), queued_bytes: state.queued_bytes,
            oldest_ms, dequeued_frames, last_dequeued_ms: if current { state.last_dequeued_ms } else { None },
            max_dequeued_ms,
        };
        state.last_report = Some(now);
        if current { state.reset_dequeues(); }
        Some(report)
    }
}

impl Report {
    pub fn log(&self, generation: u64, mono_ms: u64) {
        let ms = |v: Option<u64>| v.map_or_else(|| "na".into(), |n| n.to_string());
        crate::emit_log(&format!(
            "VIDEO-QUEUE: gen={generation} connId={} mono_ms={mono_ms} stage=main_pump_input queued_frames={} queued_bytes={} oldest_queue_ms={} dequeued_frames={} last_dequeued_age_ms={} max_dequeued_age_ms={}",
            self.connection_id, self.queued_frames, self.queued_bytes, ms(self.oldest_ms),
            self.dequeued_frames, ms(self.last_dequeued_ms), ms(self.max_dequeued_ms),
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::video_format::{FrameOrigin, VideoCodec};
    use tokio::time::advance;

    #[tokio::test(start_paused = true)]
    async fn stale_backlog_drops_video_preserves_controls_and_accepts_fresh_frames() {
        let (tx, mut rx) = channel();
        tx.send(SourceEvent::VideoFrame { pts_us: 1, data: vec![1; 8] }).unwrap();
        tx.send(SourceEvent::EncoderResolution { width: 1280, height: 720 }).unwrap();
        advance(Duration::from_millis(350)).await;
        tx.send(SourceEvent::VideoFrame { pts_us: 2, data: vec![2; 9] }).unwrap();
        assert!(matches!(rx.recv(Some(1)).await, Some(SourceEvent::VideoBacklogDropped { frames: 1, bytes: 8, .. })));
        assert!(matches!(rx.recv(Some(1)).await, Some(SourceEvent::EncoderResolution { width: 1280, height: 720 })));
        assert!(matches!(rx.recv(Some(1)).await, Some(SourceEvent::VideoFrame { pts_us: 2, .. })));
    }

    #[tokio::test]
    async fn producer_caps_video_bytes_and_count_even_during_a_pending_write() {
        let (tx, mut rx) = channel();
        for n in 0..96 {
            tx.send(SourceEvent::VideoFrame { pts_us: n, data: vec![0; 8] }).unwrap();
        }
        assert!(rx.observer().report(Some(1)).unwrap().queued_frames <= 32);
        assert!(matches!(rx.recv(Some(1)).await, Some(SourceEvent::VideoBacklogDropped { frames: 64, .. })));
        tx.send(SourceEvent::VideoFrame { pts_us: 2, data: vec![0; 16 * 1024 * 1024] }).unwrap();
        tx.send(SourceEvent::VideoFrame { pts_us: 3, data: vec![1] }).unwrap();
        assert!(rx.observer().0.lock().queued_bytes <= 16 * 1024 * 1024);
    }

    #[tokio::test(start_paused = true)]
    async fn source_frame_residence_survives_report_and_excludes_control_events() {
        let (tx, mut rx) = channel();
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![1] }).unwrap();
        advance(Duration::from_millis(70)).await;
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![2] }).unwrap();
        tx.send(SourceEvent::ResetVideoSource).unwrap();
        advance(Duration::from_millis(10)).await;
        rx.recv(Some(7)).await.unwrap();
        assert_eq!(rx.last_dequeue_age_ms(), Some(80));
        rx.recv(Some(7)).await.unwrap();
        let report = rx.observer().report(Some(7)).unwrap();
        assert_eq!(report.max_dequeued_ms, Some(80));
        assert_eq!(rx.last_dequeue_age_ms(), Some(10));
        rx.recv(Some(7)).await.unwrap();
        assert_eq!(rx.last_dequeue_age_ms(), None);
    }

    #[tokio::test(start_paused = true)]
    async fn stalled_pump_reports_payload_bytes_and_oldest_monotonic_age() {
        let (tx, mut rx) = channel();
        let observer = rx.observer();
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![1; 7] }).unwrap();
        tx.send(SourceEvent::ResetVideoSource).unwrap();
        advance(Duration::from_millis(140)).await;
        tx.clone().send(SourceEvent::VideoFrameTagged {
            pts_us: 9000, codec: VideoCodec::H264, origin: FrameOrigin::Cached, data: vec![2; 3],
        }).unwrap();
        advance(Duration::from_millis(60)).await;

        let sample = observer.report(Some(42)).expect("queued frames visible while pump stalls");
        assert_eq!((sample.queued_frames, sample.queued_bytes, sample.oldest_ms), (2, 10, Some(200)));
        assert!(matches!(rx.recv(Some(42)).await, Some(SourceEvent::VideoFrame { .. })));
        assert!(matches!(rx.recv(Some(42)).await, Some(SourceEvent::ResetVideoSource)));
        advance(Duration::from_secs(2)).await;
        let sample = observer.report(Some(42)).unwrap();
        assert_eq!((sample.queued_frames, sample.queued_bytes, sample.oldest_ms), (1, 3, Some(2060)));
        assert_eq!((sample.dequeued_frames, sample.max_dequeued_ms), (1, Some(200)));

        assert!(matches!(rx.recv(Some(42)).await, Some(SourceEvent::VideoBacklogDropped { frames: 1, .. })));
        assert!(observer.report(Some(42)).is_none(), "samples must be rate limited");
        advance(Duration::from_secs(10)).await;
        let sample = observer.report(Some(42)).unwrap();
        assert_eq!((sample.queued_frames, sample.queued_bytes, sample.oldest_ms), (0, 0, None));
        assert_eq!((sample.dequeued_frames, sample.max_dequeued_ms), (0, None));
        drop(tx);
        assert!(rx.recv(Some(42)).await.is_none());
    }

    #[tokio::test(start_paused = true)]
    async fn current_connection_does_not_report_a_previous_senders_dequeues() {
        let (tx, mut rx) = channel();
        let observer = rx.observer();
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![1] }).unwrap();
        advance(Duration::from_millis(200)).await;
        rx.recv(Some(1)).await.unwrap();
        assert!(observer.report(None).is_none());
        let idle = observer.report(Some(2)).expect("active main TCP still reports an empty FIFO");
        assert_eq!(idle.connection_id, 2);
        assert_eq!((idle.queued_frames, idle.queued_bytes, idle.oldest_ms), (0, 0, None));
        assert_eq!((idle.dequeued_frames, idle.max_dequeued_ms, idle.last_dequeued_ms), (0, None, None));
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![2] }).unwrap();
        advance(Duration::from_millis(15)).await;
        rx.recv(Some(2)).await.unwrap();
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![] }).unwrap();
        rx.recv(Some(2)).await.unwrap();
        assert!(observer.report(Some(2)).is_none());
        advance(Duration::from_secs(10)).await;
        let sample = observer.report(Some(2)).unwrap();
        assert_eq!((sample.dequeued_frames, sample.max_dequeued_ms, sample.last_dequeued_ms), (2, Some(15), Some(0)));
    }
}
