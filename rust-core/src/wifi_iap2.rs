//! Reliable iAP2 bytes: event-channel ingress, control-channel command egress.
//! A generation guard prevents an old Kotlin worker writing into a new CarPlay session.
use std::sync::{Arc, atomic::{AtomicU64, Ordering}};
use std::time::Duration;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use tokio::sync::{mpsc, Notify};
use crate::{bplist::{self, Value}, rtsp::RtspChannel};

#[derive(Default)]
struct Handoff { id: u64, ready: bool, health: Option<std::time::Instant> }
static HANDOFF: Lazy<Mutex<Handoff>> = Lazy::new(|| Mutex::new(Handoff::default()));
pub fn is_active(id: u64) -> bool { HANDOFF.lock().id == id }
pub fn mark_ready(id: u64, ready: bool) { let mut s = HANDOFF.lock(); if s.id == id { s.ready = ready; } }
pub fn feedback(id: u64, healthy: bool) { let mut s = HANDOFF.lock(); if s.id == id { s.health = if healthy { Some(std::time::Instant::now()) } else { None }; } }
pub fn can_keep_session() -> bool {
    let s = HANDOFF.lock(); s.id != 0 && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
pub fn can_keep_generation(id: u64) -> bool {
    let s = HANDOFF.lock(); s.id == id && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
fn clear_handoff(id: u64) { let mut s = HANDOFF.lock(); if s.id == id { *s = Handoff::default(); } }

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static OUTPUT: Lazy<Mutex<Option<(u64, mpsc::Sender<Vec<u8>>)>>> = Lazy::new(|| Mutex::new(None));

pub fn send(id: u64, data: Vec<u8>) -> bool {
    if data.is_empty() || data.len() > 65535 { return false; }
    let output = OUTPUT.lock();
    output.as_ref().filter(|(current, _)| *current == id)
        .map(|(_, tx)| tx.try_send(data).is_ok()).unwrap_or(false)
}

pub struct WifiIap2 {
    pub id: u64,
    task: tokio::task::JoinHandle<()>,
}

impl WifiIap2 {
    pub fn start(channel: Arc<RtspChannel>, device: &str, stop: Arc<Notify>) -> Self {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(64);
        *OUTPUT.lock() = Some((id, tx));
        *HANDOFF.lock() = Handoff { id, ..Handoff::default() };
        let task = tokio::spawn(async move {
            let mut sent = 0u64;
            loop {
                let data = tokio::select! {
                    _ = stop.notified() => break,
                    bytes = rx.recv() => match bytes { Some(b) => b, None => break },
                };
                let body = bplist::encode(&Value::dict(vec![
                    ("type", Value::str("iAPSendMessage")),
                    ("params", Value::dict(vec![("data", Value::Data(data))])),
                ]));
                // Link ACKs arrive independently on the event socket. Do not block all SYN/CSM
                // output waiting for a separate RTSP response to the previous iAP packet.
                match tokio::time::timeout(Duration::from_secs(3), channel.post_fire_and_forget(
                    "/command", &body, "application/x-apple-binary-plist",
                )).await {
                    Ok(Ok(())) => {
                        sent += 1;
                        if sent == 1 || sent % 50 == 0 { crate::emit_log(&format!("WIFI-IAP#{id}: control POST written count={sent}")); }
                    }
                    other => {
                        crate::emit_log(&format!("WIFI-IAP#{id}: control transport failed {}", match other {
                            Ok(Err(error)) => error, Err(_) => "write timeout".to_string(),
                            Ok(Ok(())) => unreachable!(),
                        }));
                        break;
                    }
                }
            }
            {
                let mut current = OUTPUT.lock();
                if current.as_ref().map(|(active, _)| *active) == Some(id) { *current = None; }
            }
            clear_handoff(id);
            crate::emit_iap2_stop(id);
        });
        crate::emit_iap2_start(id, device);
        Self { id, task }
    }
}

impl Drop for WifiIap2 {
    fn drop(&mut self) {
        self.task.abort();
        clear_handoff(self.id);
        {
            let mut current = OUTPUT.lock();
            if current.as_ref().map(|(active, _)| *active) == Some(self.id) { *current = None; }
        }
        crate::emit_iap2_stop(self.id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn link_output_does_not_wait_for_previous_rtsp_response() {
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r,w) = phone.into_split();
        let (channel, _) = RtspChannel::spawn(r,w,None);
        let guard = WifiIap2::start(channel, "unit-test", Arc::new(Notify::new()));
        assert!(send(guard.id, vec![1,2,3]));
        assert!(send(guard.id, vec![4,5,6]));
        let requests = tokio::time::timeout(Duration::from_secs(1), async {
            let mut buffer = Vec::new(); let mut chunk = [0;4096]; let mut requests = Vec::new();
            while requests.len() < 2 {
                let n = car.read(&mut chunk).await.unwrap(); assert!(n>0);
                buffer.extend_from_slice(&chunk[..n]);
                requests.extend(crate::rtsp::parse_rtsp_messages(&mut buffer));
            }
            requests
        }).await.unwrap();
        for request in requests {
            assert_eq!(request.first_line, "POST /command RTSP/1.0");
            assert_eq!(bplist::parse(&request.body).unwrap().get("type").and_then(Value::as_str), Some("iAPSendMessage"));
        }
        let id = guard.id;
        drop(guard);
        assert!(!send(id, vec![7]));
    }

    #[test]
    fn bluetooth_retirement_requires_current_ready_and_fresh_health() {
        *HANDOFF.lock() = Handoff { id: 77, ..Handoff::default() };
        assert!(!can_keep_session()); feedback(77, true); assert!(!can_keep_session());
        mark_ready(76, true); assert!(!can_keep_session());
        mark_ready(77, true); assert!(can_keep_generation(77)); assert!(!can_keep_generation(76));
        clear_handoff(76); assert!(can_keep_session());
        HANDOFF.lock().health = Some(std::time::Instant::now() - Duration::from_secs(6));
        assert!(!can_keep_session()); feedback(77, true); mark_ready(77, false); assert!(!can_keep_session());
        clear_handoff(77); assert!(!can_keep_session());
    }
    #[test]
    fn stale_generation_and_queue_overflow_are_rejected() {
        let (tx, _rx) = mpsc::channel(1);
        *OUTPUT.lock() = Some((999, tx));
        assert!(!send(998, vec![1]));
        assert!(send(999, vec![1]));
        assert!(!send(999, vec![2]));
        assert!(!send(999, vec![0; 65536]));
        *OUTPUT.lock() = None;
    }
}
