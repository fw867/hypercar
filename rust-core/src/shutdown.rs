//! 停止请求持久化，并确认旧引擎已退出后才开始下一代；Notify 本身不记忆广播。
use std::{sync::{Arc, atomic::{AtomicBool, Ordering}}, time::{Duration, Instant}};
use parking_lot::{Condvar, Mutex};
use tokio::sync::Notify;

pub struct EngineShutdown {
    pub signal: std::sync::Arc<Notify>,
    requested: AtomicBool,
    graceful: AtomicBool,
    completion: Mutex<Completion>,
    completed: Condvar,
}

#[derive(Default)]
struct Completion {
    engine_done: bool,
    audio_threads: usize,
}

impl Completion {
    fn done(&self) -> bool { self.engine_done && self.audio_threads == 0 }
}

impl EngineShutdown {
    pub fn new() -> Self {
        Self { signal: std::sync::Arc::new(Notify::new()), requested: AtomicBool::new(false),
            graceful: AtomicBool::new(false), completion: Mutex::new(Completion::default()), completed: Condvar::new() }
    }
    pub fn request(&self, graceful: bool) {
        self.graceful.store(graceful, Ordering::Release);
        self.requested.store(true, Ordering::Release);
        self.signal.notify_waiters();
    }
    pub fn requested(&self) -> bool { self.requested.load(Ordering::Acquire) }
    pub fn graceful(&self) -> bool { self.graceful.load(Ordering::Acquire) }
    pub async fn wait(&self) {
        let notified = self.signal.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if !self.requested() { notified.await; }
    }
    pub fn finish(&self) {
        self.completion.lock().engine_done = true;
        self.completed.notify_all();
    }
    pub fn track_audio_thread(self: &Arc<Self>) -> AudioThreadGuard {
        self.completion.lock().audio_threads += 1;
        AudioThreadGuard(self.clone())
    }
    pub fn wait_done(&self, budget: Duration) -> bool {
        let deadline = Instant::now() + budget;
        let mut completion = self.completion.lock();
        while !completion.done() {
            let Some(left) = deadline.checked_duration_since(Instant::now()) else { break };
            self.completed.wait_for(&mut completion, left);
        }
        completion.done()
    }
}
pub struct CompletionGuard(pub std::sync::Arc<EngineShutdown>);
impl Drop for CompletionGuard { fn drop(&mut self) { self.0.finish(); } }

pub struct AudioThreadGuard(Arc<EngineShutdown>);
impl Drop for AudioThreadGuard {
    fn drop(&mut self) {
        self.0.completion.lock().audio_threads -= 1;
        self.0.completed.notify_all();
    }
}

/// Caller serializes start/stop. Timeout must leave the old owner available to the next stop.
pub fn stop_retaining<T>(slot: &Mutex<Option<T>>, stop: impl FnOnce(&T) -> bool) -> bool {
    let previous = slot.lock().take();
    let Some(handle) = previous else { return true };
    if stop(&handle) { true } else { *slot.lock() = Some(handle); false }
}

#[cfg(test)] mod tests {
    use super::*;
    #[tokio::test] async fn stop_before_wait_is_not_lost() {
        let stop = EngineShutdown::new(); stop.request(true);
        tokio::time::timeout(Duration::from_millis(100), stop.wait()).await.unwrap();
        assert!(stop.graceful());
    }
    #[test] fn completion_and_timeout_are_distinct() {
        let stop = std::sync::Arc::new(EngineShutdown::new());
        assert!(!stop.wait_done(Duration::from_millis(5)));
        drop(CompletionGuard(stop.clone()));
        assert!(stop.wait_done(Duration::from_millis(5)));
    }
    #[test] fn timed_out_handle_blocks_replacement_until_completion_is_confirmed() {
        let stop = std::sync::Arc::new(EngineShutdown::new());
        let slot = Mutex::new(Some(stop.clone()));
        for _ in 0..2 {
            assert!(!stop_retaining(&slot, |owner| {
                owner.request(false);
                owner.wait_done(Duration::from_millis(1))
            }));
            assert!(std::sync::Arc::ptr_eq(slot.lock().as_ref().unwrap(), &stop));
        }
        stop.finish();
        assert!(stop_retaining(&slot, |owner| owner.wait_done(Duration::ZERO)));
        assert!(slot.lock().is_none());
        let next = std::sync::Arc::new(EngineShutdown::new());
        *slot.lock() = Some(next.clone());
        stop.finish(); // A late completion only belongs to the previous handle.
        assert!(!stop_retaining(&slot, |owner| owner.wait_done(Duration::ZERO)));
        assert!(std::sync::Arc::ptr_eq(slot.lock().as_ref().unwrap(), &next));
    }
}
