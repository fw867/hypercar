//! 停止请求持久化，并确认旧引擎已退出后才开始下一代；Notify 本身不记忆广播。
use std::{sync::atomic::{AtomicBool, Ordering}, time::{Duration, Instant}};
use parking_lot::{Condvar, Mutex};
use tokio::sync::Notify;

pub struct EngineShutdown {
    pub signal: std::sync::Arc<Notify>,
    requested: AtomicBool,
    graceful: AtomicBool,
    done: Mutex<bool>,
    completed: Condvar,
}
impl EngineShutdown {
    pub fn new() -> Self {
        Self { signal: std::sync::Arc::new(Notify::new()), requested: AtomicBool::new(false),
            graceful: AtomicBool::new(false), done: Mutex::new(false), completed: Condvar::new() }
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
    pub fn finish(&self) { *self.done.lock() = true; self.completed.notify_all(); }
    pub fn wait_done(&self, budget: Duration) -> bool {
        let deadline = Instant::now() + budget;
        let mut done = self.done.lock();
        while !*done {
            let Some(left) = deadline.checked_duration_since(Instant::now()) else { break };
            self.completed.wait_for(&mut done, left);
        }
        *done
    }
}
pub struct CompletionGuard(pub std::sync::Arc<EngineShutdown>);
impl Drop for CompletionGuard { fn drop(&mut self) { self.0.finish(); } }

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
}
