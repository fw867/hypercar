//! Reliable iAP2 bytes through RTSP commands or the negotiated DiPlay data stream.
//! A generation guard prevents an old Kotlin worker writing into a new CarPlay session.
use std::collections::{BTreeMap, HashSet, VecDeque};
use std::sync::{Arc, Weak, atomic::{AtomicBool, AtomicU64, Ordering}};
use std::time::Duration;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use tokio::sync::{mpsc, watch, Notify};
use crate::{bplist::{self, Value}, rtsp::RtspChannel};

#[derive(Default)]
struct Handoff {
    id: u64, ready: bool, health: Option<std::time::Instant>, ready_token: u64, phase: String,
    event_packets: u64, event_bytes: u64, control_packets: u64, control_bytes: u64,
    rtsp_owner: Option<u64>,
    startup_detect: Option<StartupDetect>,
    cancelled_writes: HashSet<u64>,
    completed_cancellable: u64,
    writes: Vec<i64>,
    ingress: BTreeMap<u64, bool>,
    next_ingress: u64,
    detect_prefix: usize,
    written_syn_ack: u64,
    channel: Weak<RtspChannel>,
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

    fn detect_pending(&self) -> bool { self.ingress.values().any(|&detect| detect) }

    fn register_ingress(&mut self, data: &[u8]) -> u64 {
        // Fence a possible DETECT across command boundaries. Kotlin checks link framing.
        let mut detect = false;
        for &byte in data {
            if byte == DETECT[self.detect_prefix] {
                self.detect_prefix += 1;
                if self.detect_prefix == DETECT.len() {
                    detect = true;
                    self.detect_prefix = 0;
                }
            } else {
                self.detect_prefix = usize::from(byte == DETECT[0]);
            }
        }
        self.next_ingress += 1;
        self.ingress.insert(self.next_ingress, detect);
        self.next_ingress
    }

    fn ingress_fence(&self, through: u64) -> u64 {
        if through == 0 {
            return self.ingress.last_key_value().map_or(0, |(&ticket, _)| ticket);
        }
        if self.ingress.first_key_value().is_some_and(|(&ticket, _)| ticket <= through) { through } else { 0 }
    }
}
static HANDOFF: Lazy<Mutex<Handoff>> = Lazy::new(|| Mutex::new(Handoff::default()));
// Control and event readers must register tickets in the same order as their JNI deliveries.
static WRITE_CHANGED: Lazy<watch::Sender<u64>> = Lazy::new(|| watch::channel(0).0);
fn wake_writer() { WRITE_CHANGED.send_modify(|value| *value = value.wrapping_add(1)); }
pub fn take_rtsp_end(id: u64) -> bool {
    let ended = HANDOFF.lock().take_rtsp_end(id);
    if ended { wake_writer(); }
    ended
}
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
    mark_ready_generation(id, None, ready)
}

pub fn mark_ready_generation(id: u64, generation: Option<u64>, ready: bool) -> bool {
    let token = {
        let mut state = HANDOFF.lock();
        if id == 0 || state.id != id || generation.is_some_and(|expected| state.ready_token != expected) {
            return false;
        }
        if !ready {
            state.ready = false;
            state.ready_token = state.ready_token.wrapping_add(1);
            state.cancelled_writes.clear();
            state.completed_cancellable = 0;
            state.writes.clear();
            state.written_syn_ack = 0;
            drop(state);
            wake_writer();
            return true;
        }
        if state.rtsp_owner != Some(id) { return false; }
        state.ready_token
    };
    // FIFO barrier: only become transport-ready after earlier authentication/greeting writes.
    OUTPUT.lock().as_ref().filter(|(current,_)| *current == id)
        .is_some_and(|(_,tx)| tx.try_send(Outbound::Ready(token)).is_ok())
}
fn ready_written(id: u64, token: u64) -> bool {
    let accepted = {
        let mut state = HANDOFF.lock();
        if state.id != id || state.rtsp_owner != Some(id) || state.ready_token != token || state.detect_pending() { false }
        else { state.ready=true; true }
    };
    if accepted { crate::emit_log(&format!("WIFI-IAP#{id}: READY transport barrier reached (peer authentication acceptance is separate)")); }
    accepted
}
pub fn feedback(id: u64, healthy: bool) { let mut s = HANDOFF.lock(); if s.id == id { s.health = if healthy { Some(std::time::Instant::now()) } else { None }; } }
pub fn can_keep_session() -> bool {
    let s = HANDOFF.lock(); s.id != 0 && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
pub fn can_keep_generation(id: u64) -> bool {
    let s = HANDOFF.lock(); s.id == id && s.ready && s.health.map(|t| t.elapsed() < Duration::from_secs(5)).unwrap_or(false)
}
fn clear_handoff(id: u64) { HANDOFF.lock().clear_writer(id); wake_writer(); }

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

struct IngressFrame {
    event: bool, bytes: Vec<u8>, ticket: u64, written_syn_ack: u64, count: u64,
}

impl IngressFrame {
    fn register(&mut self, handoff: &mut Handoff) {
        self.ticket = handoff.register_ingress(&self.bytes);
        self.written_syn_ack = handoff.written_syn_ack;
        let (count, bytes) = if self.event {
            (&mut handoff.event_packets, &mut handoff.event_bytes)
        } else {
            (&mut handoff.control_packets, &mut handoff.control_bytes)
        };
        *count += 1;
        *bytes += self.bytes.len() as u64;
        self.count = *count;
    }
}

/// Both RTSP readers register and enqueue link bytes in the same order.
#[derive(Default)]
pub(crate) struct Ingress {
    state: Mutex<IngressQueue>,
    wake: Notify,
}

#[derive(Default)]
struct IngressQueue {
    owner: Option<u64>,
    stopped: bool,
    overflow: bool,
    frames: VecDeque<IngressFrame>,
}

impl Ingress {
    pub(crate) fn queue(&self, event: bool, bytes: &[u8]) {
        let mut ingress = self.state.lock();
        if ingress.stopped || ingress.overflow { return; }
        if ingress.frames.len() >= 64 {
            ingress.overflow = true;
        } else {
            let mut frame = IngressFrame { event, bytes: bytes.to_vec(), ticket: 0, written_syn_ack: 0, count: 0 };
            if let Some(id) = ingress.owner {
                let mut handoff = HANDOFF.lock();
                if handoff.id != id || handoff.rtsp_owner != Some(id) { return; }
                frame.register(&mut handoff);
            }
            ingress.frames.push_back(frame);
        }
        drop(ingress);
        wake_writer();
        self.wake.notify_one();
    }

    fn bind(&self, id: u64) {
        let mut ingress = self.state.lock();
        if ingress.owner.is_some() || ingress.stopped {
            ingress.frames.clear();
            ingress.overflow = false;
        }
        ingress.owner = Some(id);
        ingress.stopped = false;
        let mut handoff = HANDOFF.lock();
        if handoff.id == id && handoff.rtsp_owner == Some(id) {
            for frame in &mut ingress.frames { frame.register(&mut handoff); }
        }
    }

    fn close(&self, id: u64) {
        let mut ingress = self.state.lock();
        if ingress.owner == Some(id) {
            ingress.stopped = true;
            ingress.frames.clear();
        }
    }

    async fn dispatch(&self, id: u64) {
        loop {
            let frame = {
                let mut ingress = self.state.lock();
                if ingress.owner != Some(id) || ingress.stopped { return; }
                if ingress.overflow {
                    drop(ingress);
                    crate::emit_log(&format!("WIFI-IAP#{id}: RTSP ingress queue overflow; stopping this link"));
                    clear_handoff(id);
                    close_output(id);
                    return;
                }
                ingress.frames.pop_front()
            };
            let Some(frame) = frame else {
                self.wake.notified().await;
                continue;
            };
            if frame.count <= 3 || frame.count % 100 == 0 {
                let source = if frame.event { "event" } else { "control" };
                crate::emit_log(&format!("WIFI-IAP#{id}: ingress source={source} count={} bytes={}; payload omitted",
                    frame.count, frame.bytes.len()));
            }
            if !crate::emit_iap2_ingress(id, frame.ticket, frame.written_syn_ack, &frame.bytes) {
                finish_ingress(id, frame.ticket);
            }
        }
    }
}

pub fn finish_ingress(id: u64, ticket: u64) {
    let removed = {
        let mut state = HANDOFF.lock();
        state.id == id && state.ingress.remove(&ticket).is_some()
    };
    if removed { wake_writer(); }
}

pub fn ingress_fence(id: u64, through: u64) -> u64 {
    let state = HANDOFF.lock();
    if state.id == id { state.ingress_fence(through) } else { 0 }
}

pub fn destination_current(id: u64, generation: u64) -> bool {
    let state = HANDOFF.lock();
    write_is_current(&state, id, generation, false) &&
        state.channel.upgrade().is_some_and(|channel| channel.session_is_live())
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
        let reason=run_control_ingress(inbound,id,stop,ui_since,current,|_| {},
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
enum Outbound {
    Frame(u64, Vec<u8>, Option<crate::rtsp::HudPostTrace>, i64, bool),
    Ready(u64),
    StartupDetect(u64),
}
static OUTPUT: Lazy<Mutex<Option<(u64, mpsc::Sender<Outbound>)>>> = Lazy::new(|| Mutex::new(None));
const DETECT: [u8; 6] = [0xff, 0x55, 0x02, 0x00, 0xee, 0x10];

fn close_output(id: u64) {
    let mut current = OUTPUT.lock();
    if current.as_ref().is_some_and(|(active, _)| *active == id) { *current = None; }
}

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
    let mut state = HANDOFF.lock();
    let Some(detect) = state.startup_detect_for(id) else { return; };
    detect.peer_syn_seen = true;
    drop(state);
    wake_writer();
}

pub fn queue_startup_detect(id: u64, generation: u64) -> bool {
    {
        let mut handoff = HANDOFF.lock();
        if handoff.ready_token != generation || handoff.rtsp_owner != Some(id) { return false; }
        let Some(detect) = handoff.startup_detect_for(id) else { return false; };
        if !detect.screen_setup_seen || detect.peer_syn_seen || detect.queued { return false; }
        detect.queued = true;
    }
    OUTPUT.lock().as_ref().filter(|(current, _)| *current == id)
        .is_some_and(|(_, tx)| tx.try_send(Outbound::StartupDetect(generation)).is_ok())
}

pub fn send_capacity(id: u64, generation: u64) -> Option<usize> {
    if !current_write(id, generation, false) { return None; }
    OUTPUT.lock().as_ref().filter(|(current, _)| *current == id)
        .map(|(_, tx)| tx.capacity())
}

fn current_write(id: u64, token: u64, startup_detect: bool) -> bool {
    write_is_current(&HANDOFF.lock(), id, token, startup_detect)
}

fn write_is_current(state: &Handoff, id: u64, token: u64, startup_detect: bool) -> bool {
    state.id == id && state.rtsp_owner == Some(id) && state.ready_token == token &&
        (!startup_detect || state.startup_detect.as_ref().is_some_and(|detect| !detect.peer_syn_seen))
}

pub(crate) struct WriteStart {
    id: u64, generation: u64, startup_detect: bool, ticket: i64,
    pub(crate) syn_ack: bool,
    hud_start: Option<AtomicBool>,
}

impl WriteStart {
    fn allowed(&self, state: &Handoff) -> bool {
        write_is_current(state, self.id, self.generation, self.startup_detect) &&
            (self.ticket >= 0 || (!state.cancelled_writes.contains(&self.ticket.unsigned_abs()) && state.ingress.is_empty()))
    }

    pub(crate) async fn cancelled(&self) {
        let mut changes = WRITE_CHANGED.subscribe();
        while self.allowed(&HANDOFF.lock()) {
            if changes.changed().await.is_err() { return; }
        }
    }

    fn blocked_by_ingress(&self, state: &Handoff) -> bool {
        state.detect_pending()
    }

    async fn wait_for_ingress(&self) -> bool {
        let mut changes = WRITE_CHANGED.subscribe();
        loop {
            {
                let state = HANDOFF.lock();
                if !self.allowed(&state) { return false; }
                if !self.blocked_by_ingress(&state) { return true; }
            }
            if changes.changed().await.is_err() { return false; }
        }
    }

    fn try_first_write(&self, remaining: usize, write: impl FnOnce() -> std::io::Result<usize>) -> Option<std::io::Result<usize>> {
        // Acquire the source read stamp before HANDOFF and release it after this
        // nonblocking write attempt, so route publication and byte one are ordered.
        let _hud_permit = if let Some(revoked) = &self.hud_start {
            match crate::acquire_hud_iap2_write(self.id, self.generation, self.ticket.unsigned_abs()) {
                Some(permit) => Some(permit),
                None => {
                    revoked.store(true, Ordering::Release);
                    return None;
                }
            }
        } else { None };
        // A single non-blocking socket attempt shares the lock with DETECT and cancellation.
        let mut state = HANDOFF.lock();
        if !self.allowed(&state) { return None; }
        if self.blocked_by_ingress(&state) {
            return Some(Err(std::io::ErrorKind::WouldBlock.into()));
        }
        let result = write();
        if self.syn_ack && result.as_ref().is_ok_and(|&count| count == remaining) {
            state.written_syn_ack = self.ticket as u64;
        }
        Some(result)
    }

    pub(crate) async fn prefix<F, Fut>(
        &self, wire_len: usize, writable: F, write: impl Fn() -> std::io::Result<usize>,
    ) -> std::io::Result<Option<usize>>
    where F: Fn() -> Fut, Fut: std::future::Future<Output = std::io::Result<()>> {
        loop {
            if !self.wait_for_ingress().await { return Ok(None); }
            tokio::select! {
                biased;
                _ = self.cancelled() => return Ok(None),
                result = writable() => result?,
            }
            match self.try_first_write(wire_len, &write) {
                None => return Ok(None),
                Some(Ok(0)) => return Err(std::io::ErrorKind::WriteZero.into()),
                Some(Ok(count)) => return Ok(Some(count)),
                Some(Err(error)) if error.kind() == std::io::ErrorKind::WouldBlock => continue,
                Some(Err(error)) => return Err(error),
            }
        }
    }

    // Once bytes have started, finish the transport record even if the link is retired.
    // Record a SYN|ACK on its final socket write, under the same lock as inbound registration.
    pub(crate) async fn finish_syn_ack<F, Fut>(
        &self, wire_len: usize, mut offset: usize, writable: F, write: impl Fn(usize) -> std::io::Result<usize>,
    ) -> std::io::Result<()>
    where F: Fn() -> Fut, Fut: std::future::Future<Output = std::io::Result<()>> {
        while offset < wire_len {
            writable().await?;
            let result = {
                let mut state = HANDOFF.lock();
                let result = write(offset);
                if result.as_ref().is_ok_and(|&count| count == wire_len - offset) &&
                    write_is_current(&state, self.id, self.generation, false) {
                    state.written_syn_ack = self.ticket as u64;
                }
                result
            };
            match result {
                Ok(0) => return Err(std::io::ErrorKind::WriteZero.into()),
                Ok(count) => offset += count,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }

    fn complete(&self, written: bool) {
        if self.ticket == 0 || self.syn_ack { return; }
        let mut state = HANDOFF.lock();
        if !write_is_current(&state, self.id, self.generation, false) { return; }
        let ticket = self.ticket.unsigned_abs() as i64;
        if self.ticket < 0 {
            state.cancelled_writes.remove(&(ticket as u64));
            state.completed_cancellable = ticket as u64;
        }
        let time = if written {
            (crate::timing::monotonic_nanos() / 1_000_000) as i64
        } else if self.hud_start.as_ref().is_some_and(|revoked| revoked.load(Ordering::Acquire)) {
            -2 // source revocation is final even if Kotlin sees the same cue again
        } else { -1 };
        state.writes.extend([ticket, time]);
    }
}

pub fn cancel_pending_start(id: u64, generation: u64, ticket: u64) {
    let mut state = HANDOFF.lock();
    if !write_is_current(&state, id, generation, false) { return; }
    if ticket <= state.completed_cancellable { return; }
    state.cancelled_writes.insert(ticket);
    drop(state);
    wake_writer();
}

pub fn take_write_completions(id: u64, generation: u64) -> Vec<i64> {
    let mut state = HANDOFF.lock();
    if !write_is_current(&state, id, generation, false) { return Vec::new(); }
    if state.detect_pending() { return Vec::new(); }
    std::mem::take(&mut state.writes)
}

#[cfg(test)]
pub fn send(id: u64, generation: u64, data: Vec<u8>, hud_message_id: i32, hud_serial: i64) -> bool {
    send_packet(id, generation, data, hud_message_id, hud_serial, 0, false)
}

pub fn send_packet(id: u64, generation: u64, data: Vec<u8>, hud_message_id: i32, hud_serial: i64, ticket: i64, hud_first: bool) -> bool {
    if data.is_empty() || data.len() > 65535 { return false; }
    if !current_write(id, generation, false) { return false; }
    let hud = match (hud_message_id, data.get(5)) {
        (0x5201 | 0x5202 | 0x5204, Some(&seq)) if hud_serial > 0 => Some(crate::rtsp::HudPostTrace {
            link_id: id, message_id: hud_message_id as u16, seq, serial: hud_serial as u64,
        }),
        _ => None,
    };
    if hud_first && (hud.is_none() || ticket >= 0) { return false; }
    let output = OUTPUT.lock();
    output.as_ref().filter(|(current, _)| *current == id)
        .map(|(_, tx)| tx.try_send(Outbound::Frame(generation, data, hud, ticket, hud_first)).is_ok()).unwrap_or(false)
}

pub struct WifiIap2 {
    pub id: u64,
    task: tokio::task::JoinHandle<()>,
    ingress: Arc<Ingress>,
    ingress_task: tokio::task::JoinHandle<()>,
}

impl WifiIap2 {
    pub fn start(channel: Arc<RtspChannel>, device: &str, device_name: &str, profile_id: &str, stop: Arc<Notify>,
        tunnel: Option<crate::iap2_tunnel::Target>) -> Self {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let (tx, mut rx) = mpsc::channel::<Outbound>(64);
        *OUTPUT.lock() = Some((id, tx));
        let mut handoff = Handoff::new_session(id);
        handoff.channel = Arc::downgrade(&channel);
        if profile_id == "audi_mib3" { handoff.startup_detect = Some(StartupDetect::default()); }
        *HANDOFF.lock() = handoff;
        let ingress = channel.iap_ingress();
        ingress.bind(id);
        wake_writer();
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
                let (token, data, hud, startup_detect, ticket, hud_first) = match data {
                    Outbound::Frame(token, data, hud, ticket, hud_first) => (token, data, hud, false, ticket, hud_first),
                    Outbound::Ready(token) => {
                        if !channel.session_is_live() { break; }
                        let barrier = WriteStart { id, generation: token, startup_detect: false, ticket: 0, syn_ack: false, hud_start: None };
                        let publish = async {
                            while barrier.wait_for_ingress().await {
                                if ready_written(id, token) { break; }
                            }
                        };
                        tokio::select! { _ = stop.notified() => break, _ = publish => {} }
                        continue;
                    }
                    Outbound::StartupDetect(token) => (token, DETECT.to_vec(), None, true, 0, false),
                };
                if !current_write(id, token, startup_detect) { continue; }
                let syn_ack = ticket > 0 && data.starts_with(&[0xff, 0x5a]) && data.get(4) == Some(&0xc0);
                let start = WriteStart { id, generation: token, startup_detect, ticket, syn_ack,
                    hud_start: hud_first.then(AtomicBool::default) };
                let frame_bytes = data.len();
                let metadata = if startup_detect { "link=startup_detect_after_screen_setup".into() } else { frame_diagnostic(&data) };
                if let Some(tunnel) = tunnel.as_mut() {
                    let write = tokio::select! {
                        _ = stop.notified() => break,
                        result = tunnel.write_guarded(&data, Some(&start)) => result,
                    };
                    match write {
                        Ok(written) => {
                            start.complete(written);
                            if !written { continue; }
                        }
                        Err(error) => {
                            crate::emit_log(&format!("WIFI-IAP#{id}: type 130 transport failed: {error}"));
                            break;
                        }
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
                match tokio::time::timeout(Duration::from_secs(3), channel.post_guarded(
                    "/command", &body, "application/x-apple-binary-plist", hud, Some(&start),
                )).await {
                    Ok(Ok(Some(cseq))) => {
                        start.complete(true);
                        sent += 1;
                        last_written = format!("CSeq={cseq} {metadata}");
                        if sent <= 128 || sent % 50 == 0 { crate::emit_log(&format!("WIFI-IAP#{id}: control POST written {last_written} frameBytes={frame_bytes} count={sent}; RTSP reply and link ACK separate")); }
                    }
                    Ok(Ok(None)) => {
                        start.complete(false);
                        crate::emit_log(&format!("WIFI-IAP#{id}: queued output withdrawn before write"));
                    },
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
            close_output(id);
            clear_handoff(id);
            crate::emit_iap2_stop(id);
        });
        crate::emit_iap2_start(id, device, device_name, profile_id);
        let receive = ingress.clone();
        let ingress_task = crate::callback_context::spawn(async move { receive.dispatch(id).await });
        Self { id, task, ingress, ingress_task }
    }
}

impl Drop for WifiIap2 {
    fn drop(&mut self) {
        take_rtsp_end(self.id);
        self.ingress.close(self.id);
        self.ingress_task.abort();
        self.task.abort();
        clear_handoff(self.id);
        close_output(self.id);
        crate::emit_iap2_stop(self.id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    static OUTPUT_TEST_LOCK: parking_lot::Mutex<()> = parking_lot::Mutex::new(());
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
        let _test_lock = OUTPUT_TEST_LOCK.lock();
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r,w) = phone.into_split();
        let (channel, _) = RtspChannel::spawn(r,w,None);
        let guard = WifiIap2::start(channel, "unit-test", "Test phone", "", Arc::new(Notify::new()), None);
        // The old write remains in the same RTSP FIFO when the iAP2 link detects a restart.
        assert!(send(guard.id, 0, vec![0xff], 0, 0));
        assert!(mark_ready(guard.id, false));
        assert!(send(guard.id, 1, vec![1,2,3], 0, 0));
        assert!(send(guard.id, 1, vec![4,5,6], 0, 0));
        let requests = tokio::time::timeout(Duration::from_secs(1), async {
            let mut buffer = Vec::new(); let mut chunk = [0;4096]; let mut requests = Vec::new();
            while requests.len() < 2 {
                let n = car.read(&mut chunk).await.unwrap(); assert!(n>0);
                buffer.extend_from_slice(&chunk[..n]);
                requests.extend(crate::rtsp::parse_rtsp_messages(&mut buffer).unwrap());
            }
            requests
        }).await.unwrap();
        for (request, expected) in requests.into_iter().zip([&[1, 2, 3][..], &[4, 5, 6][..]]) {
            assert_eq!(request.first_line, "POST /command RTSP/1.0");
            assert_eq!(command_bytes(&bplist::parse(&request.body).unwrap()), Some(expected));
        }
        let id = guard.id;
        drop(guard);
        assert!(!send(id, 1, vec![7], 0, 0));
    }

    #[tokio::test]
    async fn audi_startup_detect_is_single_use_and_old_or_syn_ready_writes_are_skipped() {
        let _test_lock = OUTPUT_TEST_LOCK.lock();
        use tokio::io::AsyncReadExt;
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (r, w) = phone.into_split();
        let (channel, _rx) = RtspChannel::spawn(r, w, None);
        let start = || WifiIap2::start(channel.clone(), "phone", "Test phone", "audi_mib3", Arc::new(Notify::new()), None);
        let old = start();
        let old_id = old.id;
        assert!(!queue_startup_detect(old.id, 0));
        screen_setup_accepted(old.id);
        assert!(queue_startup_detect(old.id, 0));
        peer_syn_accepted(old.id);
        assert!(!queue_startup_detect(old.id, 0));
        // Use the same control socket to expose any late writes from the retired writer.
        let current = start();
        drop(old);
        screen_setup_accepted(old_id);
        peer_syn_accepted(old_id);
        assert!(!queue_startup_detect(current.id, 0));
        screen_setup_accepted(current.id);
        assert!(queue_startup_detect(current.id, 0));
        screen_setup_accepted(current.id);
        assert!(!queue_startup_detect(current.id, 0));

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
        assert!(queue_startup_detect(next.id, 0));
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
        let _guard = OUTPUT_TEST_LOCK.lock();
        let (tx,mut rx)=mpsc::channel(4);
        *OUTPUT.lock()=Some((77,tx));
        *HANDOFF.lock() = Handoff::new_session(77);
        assert!(!can_keep_session()); feedback(77, true); assert!(!can_keep_session());
        assert!(!mark_ready(76, true)); assert!(!can_keep_session());
        for _ in 0..4 { assert!(send(77, 0, vec![1,2,3], 0, 0)); }
        assert!(!mark_ready(77, true));
        match rx.try_recv().unwrap() { Outbound::Frame(0, data, None, 0, false)=>assert_eq!(data,vec![1,2,3]), _=>panic!("barrier overtook frame") }
        assert!(mark_ready(77, true)); assert!(!can_keep_session());
        for _ in 0..3 { assert!(matches!(rx.try_recv().unwrap(), Outbound::Frame(0, _, None, 0, false))); }
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
        let _guard = OUTPUT_TEST_LOCK.lock();
        let (tx, mut rx) = mpsc::channel(1);
        *HANDOFF.lock() = Handoff::new_session(999);
        *OUTPUT.lock() = Some((999, tx));
        assert!(!send(998, 0, vec![1], 0, 0));
        assert_eq!(send_capacity(999, 0), Some(1));
        assert!(send(999, 0, vec![1], 0, 0));
        assert_eq!(send_capacity(999, 0), Some(0));
        assert!(!send(999, 0, vec![2], 0, 0));
        assert!(!send(999, 0, vec![0; 65536], 0, 0));
        assert!(mark_ready_generation(999, Some(0), false));
        assert!(!mark_ready_generation(999, Some(0), true));
        assert!(!mark_ready_generation(999, Some(0), false));
        assert_eq!(send_capacity(999, 0), None);
        let Outbound::Frame(retired, _, _, _, _) = rx.try_recv().unwrap() else { panic!("expected old output"); };
        assert!(!current_write(999, retired, false));
        assert!(!send(999, 0, vec![2], 0, 0));
        assert!(send(999, 1, vec![2], 0, 0));
        let Outbound::Frame(current, _, _, _, _) = rx.try_recv().unwrap() else { panic!("expected fresh output"); };
        assert!(current_write(999, current, false));
        assert!(take_rtsp_end(999));
        assert!(!current_write(999, current, false));
        clear_handoff(999);
        *OUTPUT.lock() = None;
    }

    #[tokio::test]
    async fn cancellation_wakes_a_first_write_that_is_waiting_for_socket_capacity() {
        let _test_lock = OUTPUT_TEST_LOCK.lock();
        let id = 778;
        *HANDOFF.lock() = Handoff::new_session(id);
        let start = WriteStart { id, generation: 0, startup_detect: false, ticket: -4, syn_ack: false, hud_start: None };
        let waiting = start.prefix(
            1,
            std::future::pending::<std::io::Result<()>>,
            || panic!("cancelled packet reached the socket"),
        );
        let cancel = async { tokio::task::yield_now().await; cancel_pending_start(id, 0, 4); };
        let (written, ()) = tokio::join!(waiting, cancel);
        assert_eq!(written.unwrap(), None);
        start.complete(false);
        assert_eq!(take_write_completions(id, 0), vec![4, -1]);
        assert!(mark_ready_generation(id, Some(0), false));
        let old = WriteStart { id, generation: 0, startup_detect: false, ticket: 7, syn_ack: false, hud_start: None };
        assert!(old.try_first_write(1, || panic!("retired packet reached the socket")).is_none());
        let current = WriteStart { id, generation: 1, startup_detect: false, ticket: -8, syn_ack: false, hud_start: None };
        assert_eq!(current.prefix(1, || std::future::ready(Ok(())), || Ok(1)).await.unwrap(), Some(1));
        current.complete(true);
        let completed = take_write_completions(id, 1);
        assert_eq!(completed[0], 8);
        assert!(completed[1] >= 0);
        clear_handoff(id);
    }

    #[test]
    fn queued_ingress_blocks_only_an_unstarted_message_until_stop_or_detect_is_processed() {
        let _test_lock = OUTPUT_TEST_LOCK.lock();
        let id = 779;
        *HANDOFF.lock() = Handoff::new_session(id);
        let optional = WriteStart { id, generation: 0, startup_detect: false, ticket: -4, syn_ack: false, hud_start: None };
        let required = WriteStart { id, generation: 0, startup_detect: false, ticket: 0, syn_ack: false, hud_start: None };
        let stop = HANDOFF.lock().register_ingress(&[0x52, 0x03]);
        assert!(optional.try_first_write(1, || panic!("optional write overtook STOP")).is_none());
        assert_eq!(required.try_first_write(1, || Ok(1)).unwrap().unwrap(), 1);
        cancel_pending_start(id, 0, 4);
        finish_ingress(id, stop);
        assert!(optional.try_first_write(1, || panic!("cancelled write reached the socket")).is_none());

        let first = HANDOFF.lock().register_ingress(&DETECT[..2]);
        let last = HANDOFF.lock().register_ingress(&DETECT[2..]);
        finish_ingress(id, first);
        assert_eq!(required.try_first_write(1, || panic!("old write overtook DETECT")).unwrap().unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock);
        assert!(mark_ready_generation(id, Some(0), false));
        finish_ingress(id, last);
        assert!(required.try_first_write(1, || panic!("retired write reached the socket")).is_none());
        clear_handoff(id);
    }

    #[test]
    fn hud_start_without_a_source_permit_is_terminal_but_retransmission_can_write() {
        let _test_lock = OUTPUT_TEST_LOCK.lock();
        let id = 782;
        *HANDOFF.lock() = Handoff::new_session(id);
        let first = WriteStart { id, generation: 0, startup_detect: false, ticket: -4, syn_ack: false,
            hud_start: Some(AtomicBool::default()) };
        assert!(first.try_first_write(1, || panic!("HUD start reached the socket without a source permit")).is_none());
        first.complete(false);
        assert_eq!(take_write_completions(id, 0), vec![4, -2]);
        let retry = WriteStart { id, generation: 0, startup_detect: false, ticket: -5, syn_ack: false, hud_start: None };
        assert_eq!(retry.try_first_write(1, || Ok(1)).unwrap().unwrap(), 1);
        retry.complete(true);
        let completed = take_write_completions(id, 0);
        assert_eq!(completed[0], 5);
        assert!(completed[1] >= 0);
        clear_handoff(id);
    }

    #[tokio::test]
    async fn enqueue_snapshots_only_the_syn_ack_whose_final_byte_has_been_written() {
        let _guard = OUTPUT_TEST_LOCK.lock();
        let id = 781;
        *HANDOFF.lock() = Handoff::new_session(id);
        let ingress = Ingress::default();
        ingress.queue(false, &[0x80]);
        ingress.bind(id);
        let start = WriteStart { id, generation: 0, startup_detect: false, ticket: 9, syn_ack: true, hud_start: None };
        assert_eq!(start.try_first_write(3, || Ok(1)).unwrap().unwrap(), 1);
        ingress.queue(true, &[0x40]);
        start.finish_syn_ack(3, 1, || std::future::ready(Ok(())), |_| Ok(2)).await.unwrap();
        ingress.queue(false, &[0x41]);
        let state = ingress.state.lock();
        assert_eq!(state.frames.iter().map(|frame| frame.ticket).collect::<Vec<_>>(), vec![1, 2, 3]);
        assert_eq!(state.frames.iter().map(|frame| frame.written_syn_ack).collect::<Vec<_>>(), vec![0, 0, 9]);
        assert_eq!(ingress_fence(id, 0), 3);
        drop(state);
        clear_handoff(id);
    }

    #[tokio::test]
    async fn rtsp_readers_register_before_either_dispatcher_consumes_the_command() {
        use tokio::io::AsyncWriteExt;
        let _guard = OUTPUT_TEST_LOCK.lock();
        let id = 782;
        *HANDOFF.lock() = Handoff::new_session(id);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let control = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut control_peer, _) = listener.accept().await.unwrap();
        let (r, w) = control.into_split();
        let (control, mut control_inbound) = RtspChannel::spawn(r, w, None);
        let ingress = control.iap_ingress();
        ingress.bind(id);
        let event = tokio::net::TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (r, w) = event.into_split();
        let (_event, mut event_inbound) = control.spawn_event(r, w, None, Arc::new(|_, _| {}));
        for (bytes, peer, inbound) in [
            (&[0x40][..], &mut control_peer, &mut control_inbound),
            (&DETECT[..], &mut event_peer, &mut event_inbound),
        ] {
            let body = iap(bytes.to_vec());
            peer.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: {}\r\n\r\n", body.len())
                .as_bytes()).await.unwrap();
            peer.write_all(&body).await.unwrap();
            assert!(tokio::time::timeout(Duration::from_secs(1), inbound.recv()).await.unwrap().is_some());
            assert!(ingress_fence(id, 0) > 0);
        }
        let state = ingress.state.lock();
        assert_eq!(state.frames.iter().map(|frame| frame.event).collect::<Vec<_>>(), vec![false, true]);
        assert_eq!(ingress_fence(id, 0), 2);
        assert!(HANDOFF.lock().detect_pending());
        drop(state);
        clear_handoff(id);
    }

    #[test]
    fn retry_fence_waits_for_all_preexisting_acks_and_excludes_future_arrivals() {
        let mut state = Handoff::new_session(780);
        let duplicate = state.register_ingress(&[0x40, 0]);
        let current = state.register_ingress(&[0x40, 1]);
        let boundary = state.ingress_fence(0);
        assert_eq!(boundary, current);
        state.ingress.remove(&duplicate);
        assert_eq!(state.ingress_fence(boundary), boundary);
        state.register_ingress(&[0x40, 2]);
        state.ingress.remove(&current);
        assert_eq!(state.ingress_fence(boundary), 0);
    }
}
