//! Reliable iAP2 bytes through RTSP commands or the negotiated DiPlay data stream.
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
    rtsp_owner: Option<u64>,
    startup_detect: Option<StartupDetect>,
}
#[derive(Default)]
struct StartupDetect {
    screen_setup_seen: bool,
    peer_syn_seen: bool,
    queued: bool,
}

#[derive(Clone, Copy)]
pub(crate) struct TransportReadiness {
    generation: u64,
    ready: bool,
}

impl Handoff {
    fn new_session(id: u64) -> Self {
        Self { id, rtsp_owner: Some(id), ..Self::default() }
    }
    fn clear_writer(&mut self, id: u64) {
        if self.id == id {
            // The RTSP readers can observe EOF after the iAP writer has exited.
            *self = Self { rtsp_owner: self.rtsp_owner, ..Self::default() };
        }
    }
    fn take_rtsp_end(&mut self, id: u64) -> bool {
        if self.rtsp_owner != Some(id) { return false; }
        self.rtsp_owner = None;
        true
    }

    fn readiness(&self, id: u64) -> Option<TransportReadiness> {
        (self.id == id && self.rtsp_owner == Some(id)).then_some(TransportReadiness {
            generation: self.ready_token,
            ready: self.ready,
        })
    }
}
static HANDOFF: Lazy<Mutex<Handoff>> = Lazy::new(|| Mutex::new(Handoff::default()));
pub fn take_rtsp_end(id: u64) -> bool { HANDOFF.lock().take_rtsp_end(id) }
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
pub(crate) fn transport_readiness(id: u64) -> Option<TransportReadiness> {
    HANDOFF.lock().readiness(id)
}

pub(crate) fn transport_ready(id: u64) -> bool {
    transport_readiness(id).is_some_and(|state| state.ready)
}

/// Bracket one attempt, so a reset followed by rapid reauthentication remains observable.
pub(crate) async fn watch_media_attempt<T>(snapshot: impl Fn() -> Option<TransportReadiness>,
    attempt: impl std::future::Future<Output = T>) -> (T, bool) {
    let before = snapshot();
    let result = attempt.await;
    let crossed_readiness = before.zip(snapshot()).is_some_and(|(before, after)|
        !before.ready || before.generation != after.generation);
    (result, crossed_readiness)
}
/// Readiness is a local write barrier, not certificate/signature verification.
pub async fn wait_transport_ready(id: u64, stop: Arc<Notify>, budget: Duration) -> bool {
    wait_ready_barrier(||transport_readiness(id).is_some(), ||transport_ready(id), stop, budget).await
}
async fn wait_ready_barrier(current:impl Fn()->bool, ready:impl Fn()->bool, stop:Arc<Notify>, budget:Duration)->bool {
    let deadline=tokio::time::sleep(budget);tokio::pin!(deadline);
    let mut tick=tokio::time::interval(Duration::from_millis(25));
    loop {
        tokio::select! {
            biased;
            _=stop.notified()=>return false,
            _=&mut deadline=>return false,
            _=tick.tick()=>{
                if !current() {return false;}
                if ready() {return true;}
            }
        }
    }
}
pub fn mark_ready(id: u64, ready: bool) -> bool {
    let token = {
        let mut state = HANDOFF.lock();
        if id == 0 || state.id != id { return false; }
        if !ready {
            state.ready = false;
            state.ready_token = state.ready_token.wrapping_add(1);
            return true;
        }
        if state.rtsp_owner != Some(id) { return false; }
        state.ready_token
    };
    // FIFO barrier: only become transport-ready after earlier authentication/greeting writes.
    OUTPUT.lock().as_ref().filter(|(current,_)| *current == id)
        .is_some_and(|(_,tx)| tx.try_send(Outbound::Ready(token)).is_ok())
}
fn ready_written(id: u64, token: u64) {
    let accepted = {
        let mut state = HANDOFF.lock();
        if state.id != id || state.rtsp_owner != Some(id) || state.ready_token != token { false }
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
fn clear_handoff(id: u64) { HANDOFF.lock().clear_writer(id); }

pub fn ingress_summary(id: u64) -> String {
    let s=HANDOFF.lock();
    if s.id!=id { return "ingress=stale".into(); }
    format!("ingress_event={}/{}B ingress_control={}/{}B",s.event_packets,s.event_bytes,s.control_packets,s.control_bytes)
}

/// A valid transport command may carry empty Data; the box acknowledges it as a no-op.
pub fn command_data(command: &Value) -> Option<&[u8]> {
    if command.get("type").and_then(Value::as_str)!=Some("iAPSendMessage") { return None; }
    command.get("params").and_then(|p|p.get("data")).and_then(Value::as_bytes)
        .filter(|b| b.len()<=65535)
}

/// Only nonempty bytes can enter the link parser; empty commands do not start authentication.
pub fn command_bytes(command: &Value) -> Option<&[u8]> {
    command_data(command).filter(|b| !b.is_empty())
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
    ui_since: std::time::Instant, current: impl Fn() -> bool, mut deliver: impl FnMut(&[u8]),
    mut ui: impl FnMut(crate::rtsp::RtspMessage),
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
        if let Some(data)=command_data(&command) { if !data.is_empty() { deliver(data); } }
        else if command.get("type").and_then(Value::as_str)==Some("iAPSendMessage") {
            if invalid<3 {crate::emit_log(&format!("WIFI-IAP#{id}: control iAP data missing/oversized"));} invalid+=1;
        } else {
            let kind=command.get("type").and_then(Value::as_str).unwrap_or("unknown");
            // updateViewArea is applied once by the RTSP reader on either socket.
            if matches!(kind, "showUI" | "stopUI" | "setLimitedUI" | "accessoryGiveFocus" |
                "forceKeyFrame" | "requestUI" | "changeUIContext" | "setNightMode") {
                if message.received_at >= ui_since { ui(message); }
                continue;
            }
            if other.len()<16 && other.insert(kind.to_owned()) {
                crate::emit_log(&format!("CONTROL-IN: command type={} not routed as iAP; existing RTSP acknowledgement preserved",kind.chars().take(48).collect::<String>()));
            }
        }
    }
}

/// Some receivers place iAP commands on the control socket; its automatic 200 is not delivery.
pub fn spawn_control_ingress(
    inbound: mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>, id: u64, stop: Arc<Notify>,
    ui_since: std::time::Instant, current: impl Fn() -> bool + Send + 'static, ui: mpsc::UnboundedSender<crate::rtsp::RtspMessage>,
) -> tokio::task::JoinHandle<()> {
    crate::callback_context::spawn(async move {
        let reason=run_control_ingress(inbound,id,stop,ui_since,current,|bytes|{receive_bytes(id,bytes,"control");},
            |message| { let _ = ui.send(message); }).await;
        if reason=="closed" { mark_ready(id,false); }
        crate::emit_log(&format!("WIFI-IAP#{id}: control ingress ended={reason}"));
    })
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

// Link/CSM headers only: never expose certificate, challenge, media text or GPS payloads.
fn frame_diagnostic(data: &[u8]) -> String {
    if data.len() < 9 || data[..2] != [0xff, 0x5a] { return "link=detect_or_unparsed".into(); }
    let csm = if data[4] & 0x80 == 0 && data[7] != 0 && data.len() >= 15 && data[9..11] == [0x40, 0x40] {
        format!("0x{:04x}", u16::from_be_bytes([data[13],data[14]]))
    } else { "none".into() };
    format!("seq={} ack={} session={} csm={csm}",data[5],data[6],data[7])
}
enum Outbound { Frame(Vec<u8>, Option<crate::rtsp::HudPostTrace>), Ready(u64), StartupDetect }
static OUTPUT: Lazy<Mutex<Option<(u64, mpsc::Sender<Outbound>)>>> = Lazy::new(|| Mutex::new(None));
const DETECT: [u8; 6] = [0xff, 0x55, 0x02, 0x00, 0xee, 0x10];

impl Handoff {
    fn startup_detect_for(&mut self, id: u64) -> Option<&mut StartupDetect> {
        if self.id != id { return None; }
        self.startup_detect.as_mut()
    }
}

pub fn screen_setup_accepted(id: u64) {
    {
        let mut handoff = HANDOFF.lock();
        let Some(detect) = handoff.startup_detect_for(id) else { return; };
        if detect.screen_setup_seen { return; }
        detect.screen_setup_seen = true;
    }
    crate::emit_iap2_screen_setup_accepted(id);
}

pub fn peer_syn_accepted(id: u64) {
    if let Some(detect) = HANDOFF.lock().startup_detect_for(id) {
        detect.peer_syn_seen = true;
    }
}

pub fn queue_startup_detect(id: u64) -> bool {
    {
        let mut handoff = HANDOFF.lock();
        let Some(detect) = handoff.startup_detect_for(id) else { return false; };
        if !detect.screen_setup_seen || detect.peer_syn_seen || detect.queued { return false; }
        detect.queued = true;
    }
    OUTPUT.lock().as_ref().filter(|(current, _)| *current == id)
        .is_some_and(|(_, tx)| tx.try_send(Outbound::StartupDetect).is_ok())
}

pub fn send(id: u64, data: Vec<u8>, hud_message_id: i32, hud_serial: i64) -> bool {
    if data.is_empty() || data.len() > 65535 { return false; }
    let hud = match (hud_message_id, data.get(5)) {
        (0x5201 | 0x5202, Some(&seq)) if hud_serial > 0 => Some(crate::rtsp::HudPostTrace {
            link_id: id, message_id: hud_message_id as u16, seq, serial: hud_serial as u64,
        }),
        _ => None,
    };
    let output = OUTPUT.lock();
    output.as_ref().filter(|(current, _)| *current == id)
        .map(|(_, tx)| tx.try_send(Outbound::Frame(data, hud)).is_ok()).unwrap_or(false)
}

pub struct WifiIap2 {
    pub id: u64,
    task: tokio::task::JoinHandle<()>,
}

impl WifiIap2 {
    pub fn start(channel: Arc<RtspChannel>, device: &str, device_name: &str, profile_id: &str, stop: Arc<Notify>,
        tunnel: Option<crate::iap2_tunnel::Target>) -> Self {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let (tx, mut rx) = mpsc::channel::<Outbound>(64);
        *OUTPUT.lock() = Some((id, tx));
        let mut handoff = Handoff::new_session(id);
        if profile_id == "audi_mib3" { handoff.startup_detect = Some(StartupDetect::default()); }
        *HANDOFF.lock() = handoff;
        let task = crate::callback_context::spawn(async move {
            let mut tunnel = if let Some(target) = tunnel {
                let result = tokio::select! { _ = stop.notified() => None, result = target.open(&channel) => Some(result) };
                match result {
                    Some(Ok(tunnel)) => Some(tunnel),
                    Some(Err(error)) => {
                        crate::emit_log(&format!("WIFI-IAP#{id}: type 130 unavailable: {error}; using RTSP commands"));
                        None
                    }
                    None => return,
                }
            } else { None };
            let mut sent = 0u64;
            let mut last_written = String::from("none");
            loop {
                let data = tokio::select! {
                    _ = stop.notified() => break,
                    bytes = rx.recv() => match bytes { Some(b) => b, None => break },
                };
                let (data, hud, startup_detect) = match data {
                    Outbound::Frame(data, hud) => (data, hud, false),
                    Outbound::Ready(token) => {
                        if !channel.session_is_live() { break; }
                        ready_written(id, token);
                        continue;
                    }
                    Outbound::StartupDetect => (DETECT.to_vec(), None, true),
                };
                let frame_bytes = data.len();
                let metadata = if startup_detect { "link=startup_detect_after_screen_setup".into() } else { frame_diagnostic(&data) };
                if let Some(tunnel) = tunnel.as_mut() {
                    if startup_detect && !HANDOFF.lock().startup_detect_for(id)
                        .is_some_and(|detect| !detect.peer_syn_seen) { continue; }
                    let write = tokio::select! { _ = stop.notified() => break, result = tunnel.write(&data) => result };
                    if let Err(error) = write {
                        crate::emit_log(&format!("WIFI-IAP#{id}: type 130 transport failed: {error}"));
                        break;
                    }
                    sent += 1;
                    last_written = metadata;
                    if sent <= 128 || sent % 50 == 0 {
                        crate::emit_log(&format!("WIFI-IAP#{id}: type 130 TCP written {last_written} frameBytes={frame_bytes} count={sent}"));
                    }
                    continue;
                }
                let body = bplist::encode(&Value::dict(vec![
                    ("type", Value::str("iAPSendMessage")),
                    ("params", Value::dict(vec![("data", Value::Data(data))])),
                ]));
                // Link ACKs arrive independently on the event socket. Do not block all SYN/CSM
                // output waiting for a separate RTSP response to the previous iAP packet.
                match tokio::time::timeout(Duration::from_secs(3), channel.post_traced_if(
                    "/command", &body, "application/x-apple-binary-plist", hud, || {
                        !startup_detect || HANDOFF.lock().startup_detect_for(id)
                            .is_some_and(|detect| !detect.peer_syn_seen)
                    },
                )).await {
                    Ok(Ok(Some(cseq))) => {
                        sent += 1;
                        last_written = format!("CSeq={cseq} {metadata}");
                        if sent <= 128 || sent % 50 == 0 { crate::emit_log(&format!("WIFI-IAP#{id}: control POST written {last_written} frameBytes={frame_bytes} count={sent}; RTSP reply and link ACK separate")); }
                    }
                    Ok(Ok(None)) => crate::emit_log(&format!("WIFI-IAP#{id}: queued startup DETECT skipped: peer SYN accepted or session retired")),
                    other => {
                        if let Some(hud) = hud {
                            crate::emit_log(&format!("HUD {hud} RTSP#{} write_failed", channel.trace_id()));
                        }
                        crate::emit_log(&format!("WIFI-IAP#{id}: control transport failed {}", match other {
                            Ok(Err(error)) => error, Err(_) => "write timeout".to_string(),
                            Ok(Ok(_)) => unreachable!(),
                        }));
                        break;
                    }
                }
            }
            crate::emit_log(&format!("WIFI-IAP#{id}: writer ended written_frames={sent} output_pending={} last_written=[{last_written}]; queue acceptance is not socket write or peer acceptance",rx.len()));
            {
                let mut current = OUTPUT.lock();
                if current.as_ref().map(|(active, _)| *active) == Some(id) { *current = None; }
            }
            clear_handoff(id);
            crate::emit_iap2_stop(id);
        });
        crate::emit_iap2_start(id, device, device_name, profile_id);
        Self { id, task }
    }
}

impl Drop for WifiIap2 {
    fn drop(&mut self) {
        take_rtsp_end(self.id);
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
    #[test]
    fn writer_failure_preserves_exactly_one_rtsp_end_for_the_current_session() {
        let mut state = Handoff::new_session(17);
        state.clear_writer(17);
        assert_eq!(state.id, 0);
        assert!(state.take_rtsp_end(17));
        assert!(!state.take_rtsp_end(17));

        let mut next = Handoff::new_session(18);
        next.clear_writer(17);
        assert_eq!(next.id, 18);
        assert!(!next.take_rtsp_end(17));
        assert!(next.take_rtsp_end(18));
    }
    #[test]
    fn frame_diagnostics_include_only_link_and_message_headers() {
        let mut frame = vec![0xff,0x5a,0,23,0x40,117,146,1,0,0x40,0x40,0,14,0x50,1];
        frame.extend_from_slice(b"private!");
        assert_eq!(frame_diagnostic(&frame),"seq=117 ack=146 session=1 csm=0x5001");
        frame[9]=0;
        assert_eq!(frame_diagnostic(&frame),"seq=117 ack=146 session=1 csm=none");
        assert_eq!(frame_diagnostic(&[0xff,0x55,2,0,0xee,0x10]),"link=detect_or_unparsed");
    }
    fn incoming(data: Vec<u8>) -> crate::rtsp::RtspMessage {
        crate::rtsp::RtspMessage { received_at: std::time::Instant::now(), hid_screen_grant: None, first_line:"POST /command RTSP/1.0".into(),headers:Default::default(),body:data }
    }
    fn iap(data:Vec<u8>)->Vec<u8> {
        bplist::encode(&Value::dict(vec![("type",Value::str("iAPSendMessage")),("params",Value::dict(vec![("data",Value::Data(data))]))]))
    }
    #[tokio::test]
    async fn acknowledged_control_ui_reaches_the_session_dispatcher_in_order() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let (r,w) = socket.into_split();
        let (_control, requests) = RtspChannel::spawn(r,w,None);
        let (to_dispatch, mut ui) = mpsc::unbounded_channel();
        let receiver = tokio::spawn(run_control_ingress(requests, 1, Arc::new(Notify::new()), std::time::Instant::now(), || true,
            |_| panic!("unexpected iAP"), move |message| { to_dispatch.send(message).unwrap(); }));
        let expected = ["setLimitedUI","showUI","forceKeyFrame","stopUI","accessoryGiveFocus","requestUI","setNightMode"];
        for (seq, kind) in expected.iter().enumerate() {
            let body = bplist::encode(&Value::dict(vec![("type", Value::str(kind)),("params",Value::dict(vec![
                ("uuid",Value::str("target")), ("nightMode",Value::bool(true)), ("limitedUI", Value::bool(true))
            ]))]));
            peer.write_all(format!("POST /command RTSP/1.0\r\nCSeq: {seq}\r\nContent-Length: {}\r\n\r\n", body.len()).as_bytes()).await.unwrap();
            peer.write_all(&body).await.unwrap();
            let mut buf = Vec::new();
            let ack = tokio::time::timeout(Duration::from_secs(2), async {
                loop {
                    let mut chunk = [0u8;512];
                    let n = peer.read(&mut chunk).await.unwrap();
                    assert_ne!(n,0);
                    buf.extend_from_slice(&chunk[..n]);
                    if let Some(reply) = crate::rtsp::parse_rtsp_messages(&mut buf).unwrap().pop() { break reply; }
                }
            }).await.unwrap();
            assert_eq!(ack.status(), 200);
            assert_eq!(ack.cseq(), Some(seq as i32));
        }
        drop(peer);
        assert_eq!(receiver.await.unwrap(),"closed");
        for kind in expected {
            let message = ui.recv().await.expect("acknowledged UI was not forwarded");
            let body = bplist::parse(&message.body).unwrap();
            assert_eq!(body.get("type").and_then(Value::as_str), Some(kind));
            assert_eq!(body.get("params").unwrap().get("uuid").and_then(Value::as_str),Some("target"));
        }
        assert!(ui.recv().await.is_none());
    }
    #[tokio::test]
    async fn control_ingress_filters_invalid_and_delivers_only_iap_data() {
        let (tx,rx)=mpsc::unbounded_channel();
        for data in [vec![0],iap(vec![]),iap(vec![0;65536]),bplist::encode(&Value::dict(vec![("type",Value::str("requestSiri"))])),
            bplist::encode(&Value::dict(vec![("type",Value::str("setLimitedUI"))])),iap(vec![4,5,6])] {
            tx.send(incoming(data)).unwrap();
        }
        drop(tx); let mut delivered=Vec::new();
        let result=run_control_ingress(rx,9,Arc::new(Notify::new()),std::time::Instant::now(),||true,|data|delivered.push(data.to_vec()), |_| panic!("pre-SETUP UI")).await;
        assert_eq!(result,"closed"); assert_eq!(delivered,vec![vec![4,5,6]]);
    }
    #[tokio::test]
    async fn stopped_or_replaced_control_ingress_cannot_deliver_queued_bytes() {
        use std::sync::atomic::AtomicUsize;
        for mode in 0..3 {
            let (tx,rx)=mpsc::unbounded_channel(); tx.send(incoming(iap(vec![1]))).unwrap(); drop(tx);
            let stop=Arc::new(Notify::new()); if mode==0 {stop.notify_one();}
            let probes=AtomicUsize::new(0); let mut delivered=false;
            let reason=run_control_ingress(rx,9,stop,std::time::Instant::now(),||match mode {1=>false,2=>probes.fetch_add(1,Ordering::SeqCst)==0,_=>true},|_|delivered=true, |_| panic!("stale UI")).await;
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
        assert_eq!(run_control_ingress(rx,9,stop,std::time::Instant::now(),||true,|data|{delivered.push(data.to_vec());notify.notify_one();}, |_| panic!("unexpected UI")).await,"cancelled");
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
        let guard = WifiIap2::start(channel, "unit-test", "Test phone", "", Arc::new(Notify::new()), None);
        assert!(send(guard.id, vec![1,2,3], 0, 0));
        assert!(send(guard.id, vec![4,5,6], 0, 0));
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
        assert!(!send(id, vec![7], 0, 0));
    }

    #[tokio::test]
    async fn audi_startup_detect_is_single_use_and_old_or_syn_ready_writes_are_skipped() {
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r, w) = phone.into_split();
        let (channel, _rx) = RtspChannel::spawn(r, w, None);
        let start = || WifiIap2::start(channel.clone(), "phone", "Test phone", "audi_mib3", Arc::new(Notify::new()), None);
        let old = start();
        let old_id = old.id;
        assert!(!queue_startup_detect(old.id));
        screen_setup_accepted(old.id);
        assert!(queue_startup_detect(old.id));
        peer_syn_accepted(old.id);
        assert!(!queue_startup_detect(old.id));
        // Use the same control socket to expose any late writes from the retired writer.
        let current = start();
        drop(old);
        screen_setup_accepted(old_id);
        peer_syn_accepted(old_id);
        assert!(!queue_startup_detect(current.id));
        screen_setup_accepted(current.id);
        assert!(queue_startup_detect(current.id));
        screen_setup_accepted(current.id);
        assert!(!queue_startup_detect(current.id));

        let request = tokio::time::timeout(Duration::from_secs(1), async {
            let mut buffer = Vec::new();
            loop {
                let n = car.read_buf(&mut buffer).await.unwrap();
                assert!(n > 0);
                let messages = crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap();
                if !messages.is_empty() {
                    assert_eq!(messages.len(), 1);
                    return messages.into_iter().next().unwrap();
                }
            }
        }).await.unwrap();
        assert_eq!(command_bytes(&bplist::parse(&request.body).unwrap()), Some(DETECT.as_slice()));

        // A SYN adopted after queueing but before the write must suppress the marker too.
        let next = start();
        drop(current);
        screen_setup_accepted(next.id);
        assert!(queue_startup_detect(next.id));
        peer_syn_accepted(next.id);
        let mut extra = [0u8; 1];
        assert!(tokio::time::timeout(Duration::from_millis(60), car.read(&mut extra)).await.is_err());
    }

    #[tokio::test]
    async fn media_retry_barrier_is_bounded_cancelled_and_generation_scoped() {
        use std::sync::atomic::AtomicBool;
        let budget=Duration::from_millis(80);
        let stop=Arc::new(Notify::new());
        assert!(!wait_ready_barrier(||true,||false,stop.clone(),budget).await);
        assert!(!wait_ready_barrier(||false,||true,stop.clone(),budget).await);
        stop.notify_one();assert!(!wait_ready_barrier(||true,||true,stop.clone(),budget).await);
        let flag=Arc::new(AtomicBool::new(false));let update=flag.clone();
        let worker=crate::callback_context::spawn(async move {tokio::time::sleep(Duration::from_millis(30)).await;update.store(true,Ordering::Release);});
        assert!(wait_ready_barrier(||true,||flag.load(Ordering::Acquire),stop,Duration::from_secs(1)).await);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn media_attempt_observes_link_reset_even_when_ready_again_before_reply() {
        use std::cell::Cell;

        let ready = TransportReadiness { generation: 3, ready: true };
        let reset = TransportReadiness { generation: 4, ready: false };
        let recovered = TransportReadiness { ready: true, ..reset };
        let snapshot = Cell::new(Some(ready));

        let (_, crossing) = watch_media_attempt(|| snapshot.get(), async { 400 }).await;
        assert!(!crossing, "an ordinary rejection from a ready receiver is final");

        let (status, crossing) = watch_media_attempt(|| snapshot.get(), async {
            snapshot.set(Some(reset));
            tokio::task::yield_now().await;
            snapshot.set(Some(recovered));
            400
        }).await;
        assert_eq!(status, 400);
        assert!(crossing, "reset and reauthentication during SETUP allow a retry");

        let (_, crossing) = watch_media_attempt(|| snapshot.get(), async { 400 }).await;
        assert!(!crossing, "a later audio candidate starts in its own ready generation");

        snapshot.set(Some(reset));
        let (_, crossing) = watch_media_attempt(|| snapshot.get(), async {
            snapshot.set(Some(recovered));
        }).await;
        assert!(crossing, "an attempt that began during negotiation can be retried");

        let (_, crossing) = watch_media_attempt(|| snapshot.get(), async { snapshot.set(None) }).await;
        assert!(!crossing, "a retired AirPlay session cannot retry");
    }

    #[test]
    fn bluetooth_retirement_requires_current_ready_and_fresh_health() {
        let (tx,mut rx)=mpsc::channel(4);
        *OUTPUT.lock()=Some((77,tx));
        *HANDOFF.lock() = Handoff::new_session(77);
        assert!(!can_keep_session()); feedback(77, true); assert!(!can_keep_session());
        assert!(!mark_ready(76, true)); assert!(!can_keep_session());
        for _ in 0..4 { assert!(send(77, vec![1,2,3], 0, 0)); }
        assert!(!mark_ready(77, true));
        match rx.try_recv().unwrap() { Outbound::Frame(data, None)=>assert_eq!(data,vec![1,2,3]), _=>panic!("barrier overtook frame") }
        assert!(mark_ready(77, true)); assert!(!can_keep_session());
        for _ in 0..3 { assert!(matches!(rx.try_recv().unwrap(), Outbound::Frame(_, None))); }
        let token=match rx.try_recv().unwrap() { Outbound::Ready(t)=>t, _=>panic!("expected barrier") };
        ready_written(77,token);
        assert!(can_keep_generation(77)); assert!(!can_keep_generation(76));
        clear_handoff(76); assert!(can_keep_session());
        HANDOFF.lock().health = Some(std::time::Instant::now() - Duration::from_secs(6));
        assert!(!can_keep_session()); feedback(77, true); mark_ready(77, false); assert!(!can_keep_session());
        mark_ready(77,true);
        let token=match rx.try_recv().unwrap() { Outbound::Ready(t)=>t, _=>panic!("expected barrier") };
        mark_ready(77,false); ready_written(77,token); assert!(!can_keep_session());
        assert!(mark_ready(77, true));
        let token=match rx.try_recv().unwrap() { Outbound::Ready(t)=>t, _=>panic!("expected barrier") };
        assert!(take_rtsp_end(77));
        ready_written(77,token);
        assert!(!mark_ready(77, true));
        assert!(!can_keep_session());
        clear_handoff(77); assert!(!can_keep_session()); *OUTPUT.lock()=None;
    }
    #[test]
    fn stale_generation_and_queue_overflow_are_rejected() {
        let (tx, _rx) = mpsc::channel(1);
        *OUTPUT.lock() = Some((999, tx));
        assert!(!send(998, vec![1], 0, 0));
        assert!(send(999, vec![1], 0, 0));
        assert!(!send(999, vec![2], 0, 0));
        assert!(!send(999, vec![0; 65536], 0, 0));
        *OUTPUT.lock() = None;
    }
}
