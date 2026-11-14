//! Reliable iAP2 bytes: event/control ingress, control-channel command egress.
//! A generation guard prevents an old Kotlin worker writing into a new CarPlay session.
use std::sync::{Arc, atomic::{AtomicU64, Ordering}};
use std::time::Duration;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use tokio::sync::{mpsc, Notify};
use crate::{bplist::{self, Value}, rtsp::RtspChannel};

#[derive(Default)]
struct Handoff {
    id: u64, ready: bool, health: Option<std::time::Instant>, ready_token: u64, phase: String,
    event_packets: u64, event_bytes: u64, control_packets: u64, control_bytes: u64,
}
static HANDOFF: Lazy<Mutex<Handoff>> = Lazy::new(|| Mutex::new(Handoff::default()));
pub fn is_active(id: u64) -> bool { HANDOFF.lock().id == id }
pub fn set_phase(id: u64, phase: &str) {
    if !matches!(phase,"WAIT_PEER"|"WAIT_PEER_LONG"|"LINK"|"IDENTIFICATION"|"CERTIFICATE"|"CHALLENGE"|"READY_PENDING"|"FAILED"|"STOPPED") { return; }
    let mut s=HANDOFF.lock(); if s.id==id { s.phase=phase.into(); }
}
pub fn phase(id: u64) -> String {
    let s=HANDOFF.lock();
    if id==0 { "WAIT_SESSION".into() } else if s.id!=id { "STOPPED".into() }
    else if s.ready { "READY_TRANSPORT".into() } else if s.phase.is_empty() { "WAIT_PEER".into() } else { s.phase.clone() }
}
pub fn mark_ready(id: u64, ready: bool) {
    let token = {
        let mut state = HANDOFF.lock();
        if state.id != id { return; }
        state.ready = false;
        state.ready_token = state.ready_token.wrapping_add(1);
        if !ready { return; }
        state.ready_token
    };
    // FIFO barrier: only become transport-ready after earlier authentication/greeting writes.
    let queued = OUTPUT.lock().as_ref().filter(|(current,_)| *current == id)
        .map(|(_,tx)| tx.try_send(Outbound::Ready(token)).is_ok()).unwrap_or(false);
    if !queued { crate::emit_log(&format!("WIFI-IAP#{id}: readiness barrier could not be queued")); }
}
fn ready_written(id: u64, token: u64) {
    let accepted = {
        let mut state = HANDOFF.lock();
        if state.id != id || state.ready_token != token { false }
        else { state.ready=true; true }
    };
    if accepted { crate::emit_log(&format!("WIFI-IAP#{id}: READY transport barrier reached (peer authentication acceptance is separate)")); }
}
pub fn feedback(id: u64, healthy: bool) { let mut s = HANDOFF.lock(); if s.id == id { s.health = if healthy { Some(std::time::Instant::now()) } else { None }; } }
pub fn can_keep_session() -> bool {
    let s = HANDOFF.lock(); s.id != 0 && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
pub fn can_keep_generation(id: u64) -> bool {
    let s = HANDOFF.lock(); s.id == id && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
fn clear_handoff(id: u64) { let mut s = HANDOFF.lock(); if s.id == id { *s = Handoff::default(); } }

pub fn ingress_summary(id: u64) -> String {
    let s=HANDOFF.lock();
    if s.id!=id { return "ingress=stale".into(); }
    format!("ingress_event={}/{}B ingress_control={}/{}B",s.event_packets,s.event_bytes,s.control_packets,s.control_bytes)
}

pub fn command_bytes(command: &Value) -> Option<&[u8]> {
    if command.get("type").and_then(Value::as_str)!=Some("iAPSendMessage") { return None; }
    command.get("params").and_then(|p|p.get("data")).and_then(Value::as_bytes)
        .filter(|b| !b.is_empty() && b.len()<=65535)
}

pub fn receive_bytes(id: u64, data: &[u8], source: &str) -> bool {
    if data.is_empty() || data.len()>65535 { return false; }
    let count={
        let mut s=HANDOFF.lock();
        if s.id!=id { return false; }
        match source {
            "event"=>{s.event_packets+=1;s.event_bytes+=data.len() as u64;s.event_packets},
            "control"=>{s.control_packets+=1;s.control_bytes+=data.len() as u64;s.control_packets},
            _=>return false,
        }
    };
    if count<=3 || count%100==0 { crate::emit_log(&format!("WIFI-IAP#{id}: ingress source={source} count={count} bytes={}; payload omitted",data.len())); }
    crate::emit_iap2_bytes(id,data);
    true
}

async fn run_control_ingress(
    mut inbound: mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>, id: u64, stop: Arc<Notify>,
    current: impl Fn() -> bool, mut deliver: impl FnMut(&[u8]),
) -> &'static str {
    let mut other=std::collections::HashSet::new();
    let mut invalid=0;
    loop {
        if !current() { return "stale"; }
        let message=tokio::select! {
            biased;
            _=stop.notified()=>return "cancelled",
            message=inbound.recv()=>match message { Some(v)=>v,None=>return "closed" },
        };
        if !current() { return "stale"; }
        if message.first_line.split_whitespace().nth(1)!=Some("/command") { continue; }
        let command=match bplist::parse(&message.body) {
            Ok(v)=>v,
            Err(_)=>{ if invalid<3 {crate::emit_log(&format!("WIFI-IAP#{id}: control command malformed; payload omitted"));} invalid+=1; continue; },
        };
        if let Some(data)=command_bytes(&command) { deliver(data); }
        else if command.get("type").and_then(Value::as_str)==Some("iAPSendMessage") {
            if invalid<3 {crate::emit_log(&format!("WIFI-IAP#{id}: control iAP data missing/oversized"));} invalid+=1;
        } else {
            let kind=command.get("type").and_then(Value::as_str).unwrap_or("unknown");
            if other.len()<16 && other.insert(kind.to_owned()) {
                crate::emit_log(&format!("CONTROL-IN: command type={} not routed as iAP; existing RTSP acknowledgement preserved",kind.chars().take(48).collect::<String>()));
            }
        }
    }
}

/// Some receivers place iAP commands on the control socket; its automatic 200 is not delivery.
pub fn spawn_control_ingress(
    inbound: mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>, id: u64, stop: Arc<Notify>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let reason=run_control_ingress(inbound,id,stop,||is_active(id),|bytes|{receive_bytes(id,bytes,"control");}).await;
        if reason=="closed" { mark_ready(id,false); }
        crate::emit_log(&format!("WIFI-IAP#{id}: control ingress ended={reason}"));
    })
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
enum Outbound { Frame(Vec<u8>), Ready(u64) }
static OUTPUT: Lazy<Mutex<Option<(u64, mpsc::Sender<Outbound>)>>> = Lazy::new(|| Mutex::new(None));

pub fn send(id: u64, data: Vec<u8>) -> bool {
    if data.is_empty() || data.len() > 65535 { return false; }
    let output = OUTPUT.lock();
    output.as_ref().filter(|(current, _)| *current == id)
        .map(|(_, tx)| tx.try_send(Outbound::Frame(data)).is_ok()).unwrap_or(false)
}

pub struct WifiIap2 {
    pub id: u64,
    task: tokio::task::JoinHandle<()>,
}

impl WifiIap2 {
    pub fn start(channel: Arc<RtspChannel>, device: &str, device_name: &str, stop: Arc<Notify>) -> Self {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let (tx, mut rx) = mpsc::channel::<Outbound>(64);
        *OUTPUT.lock() = Some((id, tx));
        *HANDOFF.lock() = Handoff { id, ..Handoff::default() };
        let task = tokio::spawn(async move {
            let mut sent = 0u64;
            loop {
                let data = tokio::select! {
                    _ = stop.notified() => break,
                    bytes = rx.recv() => match bytes { Some(b) => b, None => break },
                };
                let data = match data {
                    Outbound::Frame(data) => data,
                    Outbound::Ready(token) => { ready_written(id,token); continue; }
                };
                let frame_bytes = data.len();
                let body = bplist::encode(&Value::dict(vec![
                    ("type", Value::str("iAPSendMessage")),
                    ("params", Value::dict(vec![("data", Value::Data(data))])),
                ]));
                // Link ACKs arrive independently on the event socket. Do not block all SYN/CSM
                // output waiting for a separate RTSP response to the previous iAP packet.
                match tokio::time::timeout(Duration::from_secs(3), channel.post_fire_and_forget(
                    "/command", &body, "application/x-apple-binary-plist",
                )).await {
                    Ok(Ok(cseq)) => {
                        sent += 1;
                        if sent <= 16 || sent % 50 == 0 { crate::emit_log(&format!("WIFI-IAP#{id}: control POST written CSeq={cseq} frameBytes={frame_bytes} count={sent}")); }
                    }
                    other => {
                        crate::emit_log(&format!("WIFI-IAP#{id}: control transport failed {}", match other {
                            Ok(Err(error)) => error, Err(_) => "write timeout".to_string(),
                            Ok(Ok(_)) => unreachable!(),
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
        crate::emit_iap2_start(id, device, device_name);
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
    fn incoming(data: Vec<u8>) -> crate::rtsp::RtspMessage {
        crate::rtsp::RtspMessage { first_line:"POST /command RTSP/1.0".into(),headers:Default::default(),body:data }
    }
    fn iap(data:Vec<u8>)->Vec<u8> {
        bplist::encode(&Value::dict(vec![("type",Value::str("iAPSendMessage")),("params",Value::dict(vec![("data",Value::Data(data))]))]))
    }
    #[tokio::test]
    async fn control_ingress_filters_invalid_and_delivers_only_iap_data() {
        let (tx,rx)=mpsc::unbounded_channel();
        for data in [vec![0],iap(vec![]),iap(vec![0;65536]),bplist::encode(&Value::dict(vec![("type",Value::str("requestSiri"))])),iap(vec![4,5,6])] {
            tx.send(incoming(data)).unwrap();
        }
        drop(tx); let mut delivered=Vec::new();
        let result=run_control_ingress(rx,9,Arc::new(Notify::new()),||true,|data|delivered.push(data.to_vec())).await;
        assert_eq!(result,"closed"); assert_eq!(delivered,vec![vec![4,5,6]]);
    }
    #[tokio::test]
    async fn stopped_or_replaced_control_ingress_cannot_deliver_queued_bytes() {
        use std::sync::atomic::AtomicUsize;
        for mode in 0..3 {
            let (tx,rx)=mpsc::unbounded_channel(); tx.send(incoming(iap(vec![1]))).unwrap(); drop(tx);
            let stop=Arc::new(Notify::new()); if mode==0 {stop.notify_one();}
            let probes=AtomicUsize::new(0); let mut delivered=false;
            let reason=run_control_ingress(rx,9,stop,||match mode {1=>false,2=>probes.fetch_add(1,Ordering::SeqCst)==0,_=>true},|_|delivered=true).await;
            assert_eq!(reason,if mode==0 {"cancelled"}else{"stale"}); assert!(!delivered);
        }
    }
    #[tokio::test]
    async fn control_command_is_acked_and_queued_until_consumer_starts() {
        use tokio::io::{AsyncReadExt,AsyncWriteExt};
        let server=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone=tokio::net::TcpStream::connect(server.local_addr().unwrap()).await.unwrap();
        let (mut peer,_)=server.accept().await.unwrap(); let (r,w)=phone.into_split();
        let (_control,rx)=RtspChannel::spawn(r,w,None); let payload=vec![255,85,2,0,238,16]; let body=iap(payload.clone());
        peer.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 9\r\nContent-Length: {}\r\n\r\n",body.len()).as_bytes()).await.unwrap();
        peer.write_all(&body).await.unwrap();
        let ack=tokio::time::timeout(Duration::from_secs(1),async {
            let mut data=Vec::new(); loop {let mut buf=[0;1024];let n=peer.read(&mut buf).await.unwrap();assert!(n>0);data.extend_from_slice(&buf[..n]);if let Some(msg)=crate::rtsp::parse_rtsp_messages(&mut data).unwrap().pop(){return msg;}}
        }).await.unwrap();
        assert_eq!(ack.status(),200);assert_eq!(ack.headers.get("cseq").map(String::as_str),Some("9"));
        let stop=Arc::new(Notify::new());let notify=stop.clone();let mut delivered=Vec::new();
        assert_eq!(run_control_ingress(rx,9,stop,||true,|data|{delivered.push(data.to_vec());notify.notify_one();}).await,"cancelled");
        assert_eq!(delivered,vec![payload]);
    }
    #[tokio::test]
    async fn link_output_does_not_wait_for_previous_rtsp_response() {
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r,w) = phone.into_split();
        let (channel, _) = RtspChannel::spawn(r,w,None);
        let guard = WifiIap2::start(channel, "unit-test", "Test phone", Arc::new(Notify::new()));
        assert!(send(guard.id, vec![1,2,3]));
        assert!(send(guard.id, vec![4,5,6]));
        let requests = tokio::time::timeout(Duration::from_secs(1), async {
            let mut buffer = Vec::new(); let mut chunk = [0;4096]; let mut requests = Vec::new();
            while requests.len() < 2 {
                let n = car.read(&mut chunk).await.unwrap(); assert!(n>0);
                buffer.extend_from_slice(&chunk[..n]);
                requests.extend(crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap());
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
        let (tx,mut rx)=mpsc::channel(4);
        *OUTPUT.lock()=Some((77,tx));
        *HANDOFF.lock() = Handoff { id: 77, ..Handoff::default() };
        assert!(!can_keep_session()); feedback(77, true); assert!(!can_keep_session());
        mark_ready(76, true); assert!(!can_keep_session());
        assert!(send(77,vec![1,2,3]));
        mark_ready(77, true); assert!(!can_keep_session());
        match rx.try_recv().unwrap() { Outbound::Frame(data)=>assert_eq!(data,vec![1,2,3]), _=>panic!("barrier overtook frame") }
        let token=match rx.try_recv().unwrap() { Outbound::Ready(t)=>t, _=>panic!("expected barrier") };
        ready_written(77,token);
        assert!(can_keep_generation(77)); assert!(!can_keep_generation(76));
        clear_handoff(76); assert!(can_keep_session());
        HANDOFF.lock().health = Some(std::time::Instant::now() - Duration::from_secs(6));
        assert!(!can_keep_session()); feedback(77, true); mark_ready(77, false); assert!(!can_keep_session());
        mark_ready(77,true);
        let token=match rx.try_recv().unwrap() { Outbound::Ready(t)=>t, _=>panic!("expected barrier") };
        mark_ready(77,false); ready_written(77,token); assert!(!can_keep_session());
        clear_handoff(77); assert!(!can_keep_session()); *OUTPUT.lock()=None;
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
