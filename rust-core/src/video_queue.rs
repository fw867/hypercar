//! Observations at the shared input FIFO for the main video pump.
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use tokio::sync::mpsc;
use tokio::time::Instant;

use crate::engine::SourceEvent;

#[derive(Clone)]
pub(crate) struct Sender {
    tx: mpsc::UnboundedSender<SourceEvent>,
    observer: Observer,
}

pub(crate) struct Receiver {
    rx: mpsc::UnboundedReceiver<SourceEvent>,
    observer: Observer,
    last_dequeue_age_ms: Option<u64>,
}

#[derive(Clone, Default)]
pub(crate) struct Observer(Arc<Mutex<State>>);

#[derive(Default)]
struct State {
    // The channel owns the payload. One timestamp per queued frame shares FIFO order.
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
    let (tx, rx) = mpsc::unbounded_channel();
    let observer = Observer::default();
    (Sender { tx, observer: observer.clone() }, Receiver { rx, observer, last_dequeue_age_ms: None })
}

fn frame_bytes(event: &SourceEvent) -> Option<usize> {
    match event {
        SourceEvent::VideoFrame { data, .. } | SourceEvent::VideoFrameTagged { data, .. } => Some(data.len()),
        _ => None,
    }
}

impl Sender {
    pub fn send(&self, event: SourceEvent) -> Result<(), mpsc::error::SendError<SourceEvent>> {
        let Some(bytes) = frame_bytes(&event) else { return self.tx.send(event) };
        // Both producers must publish the timestamp in the same order as the channel send.
        let mut state = self.observer.0.lock();
        let now = Instant::now();
        self.tx.send(event)?;
        state.queued_at.push_back(now);
        state.queued_bytes += bytes;
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

    pub async fn recv(&mut self, connection: Option<u64>) -> Option<SourceEvent> {
        let event = self.rx.recv().await;
        self.last_dequeue_age_ms = None;
        let event = event?;
        if let Some(bytes) = frame_bytes(&event) {
            let mut state = self.observer.0.lock();
            let queued = state.queued_at.pop_front().expect("each queued frame has an enqueue timestamp");
            let age = queued.elapsed().as_millis() as u64;
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
        Some(event)
    }
}

impl Drop for Receiver {
    fn drop(&mut self) {
        let mut state = self.observer.0.lock();
        self.rx.close();
        state.queued_at = VecDeque::new();
        state.queued_bytes = 0;
    }
}

impl State {
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

        assert!(matches!(rx.recv(Some(42)).await, Some(SourceEvent::VideoFrameTagged { .. })));
        assert!(observer.report(Some(42)).is_none(), "slow samples must be rate limited");
        advance(Duration::from_secs(2)).await;
        let sample = observer.report(Some(42)).unwrap();
        assert_eq!((sample.queued_frames, sample.queued_bytes, sample.oldest_ms), (0, 0, None));
        assert_eq!((sample.dequeued_frames, sample.max_dequeued_ms), (1, Some(2060)));
        drop(tx);
        assert!(rx.recv(Some(42)).await.is_none());
    }

    #[tokio::test(start_paused = true)]
    async fn current_connection_does_not_report_a_previous_senders_dequeues() {
        let (tx, mut rx) = channel();
        let observer = rx.observer();
        tx.send(SourceEvent::VideoFrame { pts_us: 0, data: vec![1] }).unwrap();
        advance(Duration::from_secs(2)).await;
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
