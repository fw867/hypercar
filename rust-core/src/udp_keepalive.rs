//! Session UDP keepalive. RTSP feedback and screen keepalive frames run independently.
use std::{sync::{Arc, OnceLock}, time::Duration};
use tokio::{sync::{Notify, watch}, time::Instant};
use crate::{airplay::SocketProvider, emit_log};

pub(crate) fn idle_only(profile: &str, id9: bool, mbux: bool, desktop: bool, model: &str) -> bool {
    !desktop && (profile == "bmw_id85" || id9 || (mbux && model == "MB_E-P-4"))
}

#[derive(Clone, Copy)]
pub(crate) struct MediaState {
    streams: u8,
    idle_since: Instant,
}

impl MediaState {
    pub(crate) fn idle(&self) -> bool { self.streams == 0 }
}

// One tracker per control connection. Screen and each audio type have serialized
// SETUP/TEARDOWN owners. Untracked profiles never allocate a watch channel.
#[derive(Default)]
pub(crate) struct MediaActivity(OnceLock<watch::Sender<MediaState>>);

impl MediaActivity {
    pub(crate) fn subscribe(&self) -> watch::Receiver<MediaState> {
        self.0.get_or_init(|| watch::channel(MediaState { streams: 0, idle_since: Instant::now() }).0)
            .subscribe()
    }

    pub(crate) fn set(&self, kind: i64, active: bool) {
        let Some(tx) = self.0.get() else { return; };
        let bit = match kind { 110 => 1, 100 => 2, 102 => 4, 101 => 8, _ => return };
        tx.send_if_modified(|state| {
            let was_idle = state.idle();
            if active { state.streams |= bit; } else { state.streams &= !bit; }
            if was_idle == state.idle() { return false; }
            if state.idle() { state.idle_since = Instant::now(); }
            true
        });
    }
}

// KeepaliveTasks::drop aborts the worker even when negotiation fails before its first tick.
struct UdpKeepaliveLog {
    id: u64,
    port: u16,
    started: tokio::time::Instant,
    sent: u64,
    end_reason: String,
}

impl UdpKeepaliveLog {
    fn event(&self, detail: &str) {
        emit_log(&format!("AIRPLAY: keepAlive UDP {detail} session={} port={} elapsed_ms={} sent={}",
            self.id, self.port, self.started.elapsed().as_millis(), self.sent));
    }
}

impl Drop for UdpKeepaliveLog {
    fn drop(&mut self) { self.event(&self.end_reason); }
}

pub(crate) async fn run(
    factory: Arc<dyn SocketProvider>, car_ip: String, keep_alive_port: u16,
    stop: Arc<Notify>, session_id: u64, mut media: Option<watch::Receiver<MediaState>>,
) {
    let mut trace = UdpKeepaliveLog {
        id: session_id, port: keep_alive_port, started: tokio::time::Instant::now(),
        sent: 0, end_reason: "停止 reason=session_task_dropped".into(),
    };
    let Some(udp) = factory.open_udp(&car_ip, keep_alive_port) else {
        trace.end_reason = "停止 reason=open_failed".into();
        return;
    };
    let period = Duration::from_secs(if media.is_some() { 10 } else { 5 });
    let periodic = |start| {
        let mut interval = tokio::time::interval_at(start, period);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        interval
    };
    let mut timer = Some(periodic(Instant::now()));
    trace.event(&format!("启动 first_delay_ms=0 period_ms={} idle_only={}", period.as_millis(), media.is_some()));
    loop {
        let sequence = (trace.sent as u32).wrapping_add(1);
        let sent = tokio::select! {
            biased;
            _ = stop.notified() => {
                trace.end_reason = "停止 reason=session_stop".into();
                return;
            },
            change = async {
                let Some(rx) = &mut media else { return std::future::pending().await; };
                rx.changed().await.ok()?;
                Some(*rx.borrow_and_update())
            }, if trace.sent > 0 => {
                let Some(state) = change else {
                    trace.end_reason = "停止 reason=control_released".into();
                    return;
                };
                if state.idle() {
                    timer = Some(periodic(state.idle_since + period));
                    trace.event("重新计时 reason=last_stream_teardown period_ms=10000");
                } else {
                    timer = None;
                    trace.event("取消定时 reason=media_stream_active");
                }
                continue;
            }
            result = async {
                let Some(interval) = &mut timer else { return std::future::pending().await; };
                interval.tick().await;
                udp.send(&sequence.to_be_bytes()).await
            } => result,
        };
        match sent {
            Ok(4) => {
                trace.sent += 1;
                if trace.sent == 1 { trace.event("首包已发送"); }
            }
            Ok(size) => {
                trace.end_reason = format!("停止 reason=short_send seq={sequence} bytes={size}");
                return;
            }
            Err(error) => {
                trace.end_reason = format!("停止 reason=send_failed seq={sequence} error={error}");
                return;
            }
        }
    }
}
