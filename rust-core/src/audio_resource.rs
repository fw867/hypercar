//! Bounded playback-start recovery; PCM arrival alone never overrides a receiver take.
use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
use parking_lot::Mutex;
use crate::{bplist, modes::Modes, rtsp::RtspChannel};

pub struct AudioResource {
    pub control: Arc<RtspChannel>,
    pub modes: Arc<Mutex<Modes>>,
    pub owned: Arc<AtomicBool>,
}

impl AudioResource {
    pub async fn request(&self, user_initiated: bool) {
        let audio_epoch = self.modes.lock().audio_change_epoch();
        for attempt in 0..2 {
            let proposal = {
                let current = self.modes.lock();
                if current.audio_change_epoch() != audio_epoch { return; }
                current.audio_claim(user_initiated)
            };
            let Some((epoch, claim)) = proposal else {
                crate::emit_log(&format!("AUDIO-RESOURCE: playback start user={user_initiated} no claim (owned/disabled/constrained); {}", self.modes.lock().diagnostics()));
                return;
            };
            let body = claim.declaration();
            let result = self.control.request("POST", "/command", &body,
                "application/x-apple-binary-plist", &[], std::time::Duration::from_secs(3)).await;
            let accepted = match result {
                Ok(reply) => {
                    let accepted = response_accepted(reply.status(), &reply.body);
                    crate::emit_log(&format!("AUDIO-RESOURCE: claim user={user_initiated} RTSP={} accepted={accepted}", reply.status()));
                    accepted
                }
                Err(error) => { crate::emit_log(&format!("AUDIO-RESOURCE: claim failed {error}")); false }
            };
            if !accepted { return; }
            let mut current = self.modes.lock();
            let committed = current.commit_audio_claim(epoch, &claim);
            // Event dispatch may have received another take while the RTSP request was in flight.
            // Never restore a grant from an older arbitration epoch.
            self.owned.store(current.audio_owned(), Ordering::Release);
            crate::emit_log(&format!("AUDIO-RESOURCE: committed={committed} {}; receiver audibility unconfirmed", current.diagnostics()));
            if committed || current.audio_change_epoch() != audio_epoch || attempt == 1 { return; }
            drop(current);
            crate::emit_log("AUDIO-RESOURCE: screen-only event superseded snapshot; retry once with latest modes");
        }
    }
}

fn response_accepted(status: u16, body: &[u8]) -> bool {
    if status != 200 { return false; }
    if body.is_empty() { return true; }
    let Ok(value) = bplist::parse(body) else { return false; };
    value.as_dict().is_some() && match value.get("status") {
        None => true,
        Some(status) => status.as_int() == Some(0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bplist::Value;
    fn receiver_take() -> Value {
        Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
            ("resourceID", Value::int(2)), ("transferType", Value::int(1)),
            ("transferPriority", Value::int(500)), ("takeConstraint", Value::int(500)),
            ("borrowConstraint", Value::int(100)),
        ])]))])
    }
    #[tokio::test]
    async fn audio_gate_waits_for_acceptance_and_never_commits_over_a_new_receiver_take() {
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
        for (status, retake, screen_only) in [(200, false, false), (403, false, false), (200, true, false), (200, false, true), (0, false, false)] {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (mut peer, _) = listener.accept().await.unwrap();
            let (r,w) = socket.into_split();
            let (control, _) = RtspChannel::spawn(r,w,None);
            let modes = Arc::new(Mutex::new(Modes::initial()));
            assert!(modes.lock().change(&receiver_take()));
            let owned = Arc::new(AtomicBool::new(false));
            let resource = AudioResource { control, modes: modes.clone(), owned: owned.clone() };
            let task = tokio::spawn(async move { resource.request(true).await });
            let mut buffer = Vec::new();
            let request = tokio::time::timeout(std::time::Duration::from_secs(1), async {
                loop {
                    let mut chunk = [0u8;4096];
                    let n = peer.read(&mut chunk).await.unwrap(); assert!(n>0);
                    buffer.extend_from_slice(&chunk[..n]);
                    if let Some(message) = crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap().into_iter().next() { break message; }
                }
            }).await.unwrap();
            assert!(!owned.load(Ordering::Acquire));
            assert!(!modes.lock().audio_owned());
            if status == 0 {
                task.await.unwrap();
                assert!(!owned.load(Ordering::Acquire));
                assert!(!modes.lock().audio_owned());
                continue;
            }
            if retake { assert!(modes.lock().change(&receiver_take())); }
            if screen_only {
                let screen=Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                    ("resourceID",Value::int(1)),("transferType",Value::int(3)),
                    ("transferPriority",Value::int(500)),("unborrowConstraint",Value::int(100)),
                ])]))]);
                assert!(modes.lock().change(&screen));
            }
            let response = format!("RTSP/1.0 {status} Response\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", request.cseq().unwrap());
            peer.write_all(response.as_bytes()).await.unwrap();
            if screen_only {
                let second=tokio::time::timeout(std::time::Duration::from_secs(1),async {
                    loop {
                        let mut chunk=[0;4096];let n=peer.read(&mut chunk).await.unwrap();assert!(n>0);
                        buffer.extend_from_slice(&chunk[..n]);
                        if let Some(m)=crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap().into_iter().next(){break m;}
                    }
                }).await.unwrap();
                assert!(!owned.load(Ordering::Acquire));
                let declaration=bplist::parse(&second.body).unwrap();
                let resources=declaration.get("params").unwrap().get("resources").unwrap().as_array().unwrap();
                assert_eq!(resources[0].get("entity").and_then(Value::as_int),Some(2));
                let response=format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",second.cseq().unwrap());
                peer.write_all(response.as_bytes()).await.unwrap();
            }
            task.await.unwrap();
            assert_eq!(owned.load(Ordering::Acquire), status == 200 && !retake);
            assert_eq!(modes.lock().audio_owned(), status == 200 && !retake);
            if screen_only { assert!(!modes.lock().screen_owned()); }
        }
    }
    #[test]
    fn refusal_timeout_payload_and_malformed_business_response_cannot_open_the_audio_gate() {
        assert!(response_accepted(200, &[]));
        let body = |v| bplist::encode(&Value::dict(vec![("status", v)]));
        assert!(response_accepted(200, &body(Value::int(0))));
        assert!(!response_accepted(200, &body(Value::int(1))));
        assert!(!response_accepted(200, &body(Value::str("0"))));
        assert!(!response_accepted(403, &body(Value::int(0))));
        assert!(!response_accepted(200, b"invalid"));
    }
}
