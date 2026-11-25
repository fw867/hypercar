//! An engine's tasks retain its listener, never the listener of a subsequent start.
use std::future::Future;
use jni::objects::GlobalRef;
use std::sync::atomic::{AtomicU64, Ordering};
static NEXT: AtomicU64 = AtomicU64::new(1);

#[derive(Clone)]
pub struct Context {
    pub listener: Option<GlobalRef>,
    pub generation: u64,
    // Inherited by negotiation/rebuild tasks so detached codec workers retire
    // with their original engine, even after a newer JNI listener is installed.
    pub audio_shutdown: Option<std::sync::Arc<crate::shutdown::EngineShutdown>>,
}
impl Context {
    pub fn new(listener: Option<GlobalRef>) -> Self {
        Self { listener, generation: NEXT.fetch_add(1, Ordering::Relaxed), audio_shutdown: None }
    }
}
tokio::task_local! { static CURRENT: Option<Context>; }

pub fn current() -> Option<Context> { CURRENT.try_with(Clone::clone).ok().flatten() }
pub fn sync_scope<R>(context: Context, action: impl FnOnce() -> R) -> R {
    CURRENT.sync_scope(Some(context), action)
}
// Capture on the spawning thread, not when the future is first polled.
pub fn inherit<F: Future>(future: F) -> impl Future<Output = F::Output> {
    CURRENT.scope(current(), future)
}
pub fn spawn<F>(future: F) -> tokio::task::JoinHandle<F::Output>
where F: Future + Send + 'static, F::Output: Send + 'static {
    tokio::spawn(inherit(future))
}

#[cfg(test)] mod tests {
    use super::*;
    use std::sync::{Arc, atomic::{AtomicBool, Ordering}};

    #[tokio::test] async fn child_and_aborted_cleanup_keep_the_original_context() {
        let dropped = Arc::new(AtomicBool::new(false));
        struct Cleanup(Arc<AtomicBool>, u64);
        impl Drop for Cleanup {
            fn drop(&mut self) { self.0.store(current().map(|c|c.generation) == Some(self.1), Ordering::SeqCst); }
        }
        let (ready, started) = tokio::sync::oneshot::channel();
        let signal = dropped.clone();
        let first = Context::new(None);
        let id = first.generation;
        let task = sync_scope(first, || spawn(async move {
            let _cleanup = Cleanup(signal, id);
            assert_eq!(current().unwrap().generation, id);
            let child = spawn(async { current().unwrap().generation });
            assert_eq!(child.await.unwrap(), id);
            ready.send(()).unwrap();
            std::future::pending::<()>().await;
        }));
        started.await.unwrap();
        assert!(current().is_none());
        sync_scope(Context::new(None), || {
            assert_ne!(current().unwrap().generation, id);
            task.abort();
        });
        assert!(task.await.unwrap_err().is_cancelled());
        assert!(dropped.load(Ordering::SeqCst));
    }
}
