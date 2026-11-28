//! ACK-committed temporary speech ownership; navigation only updates its app state.
use std::sync::{Arc,atomic::AtomicBool};
use std::time::Duration;
#[cfg(test)]
use std::sync::atomic::Ordering;
use parking_lot::Mutex;
use crate::{bplist,modes::{AuxTransition,CallAudioTransition,Modes},rtsp::RtspChannel};
pub use crate::modes::{SpeechLease,NavigationLease};
pub(crate) use crate::modes::CallAudioLease;

pub(crate) fn projected_route_generation() -> Option<Option<u64>> {
    let bridge = crate::TRANSCODER_BRIDGE.read().clone()?;
    let generation = crate::with_env(|env| {
        env.call_method(bridge.as_obj(), "projectedNavigationRouteGeneration", "()J", &[])?.j()
    })?;
    let generation = u64::try_from(generation).ok()?;
    Some((generation != 0).then_some(generation))
}

pub(crate) fn with_projected_route_write(generation: Option<u64>, write: &mut dyn FnMut() -> std::io::Result<usize>)
    -> Option<std::io::Result<usize>>
{
    let generation = i64::try_from(generation.unwrap_or(0)).ok()?;
    crate::with_navigation_source_write(|bridge| crate::with_env(|env| {
        env.call_method(bridge.as_obj(), "acquireProjectedNavigationRouteWrite", "(J)J", &[generation.into()])?.j()
    }), write)
}

enum CallAck { Accepted, Rejected, Unconfirmed }

pub struct SpeechResource {
    pub control:Arc<RtspChannel>,
    pub modes:Arc<Mutex<Modes>>,
    pub owned:Arc<AtomicBool>,
    serial:Arc<tokio::sync::Mutex<()>>,
}
impl SpeechResource {
    pub fn new(control:Arc<RtspChannel>,modes:Arc<Mutex<Modes>>,owned:Arc<AtomicBool>)->Self {
        control.bind_modes(modes.clone());
        Self{control,modes,owned,serial:Arc::new(tokio::sync::Mutex::new(()))}
    }
    pub fn serial(&self)->Arc<tokio::sync::Mutex<()>> {self.serial.clone()}
    pub fn speech_valid(&self,lease:&SpeechLease)->bool {lease.valid(&self.modes.lock())}
    #[cfg(test)]
    fn navigation_valid(&self,lease:&NavigationLease)->bool {lease.valid(&self.modes.lock())}
    pub(crate) async fn borrow_for_call(&self, attempted: &mut Option<(u64, u64, u64)>,
        offer_is_live: impl FnOnce() -> bool) -> Option<CallAudioLease> {
        let _serial = self.serial.lock().await;
        if !offer_is_live() { return None; }
        let (proposal, lease) = {
            let modes = self.modes.lock();
            let epochs = modes.call_borrow_epochs();
            if *attempted == Some(epochs) { return None; }
            let proposal = modes.call_audio_begin()?;
            *attempted = Some(epochs);
            proposal
        };
        let reconcile = match self.call_declaration(&proposal.declaration()).await {
            CallAck::Accepted => {
                if self.commit_call_audio(&proposal) { return Some(lease); }
                true
            }
            CallAck::Rejected => false,
            CallAck::Unconfirmed => true,
        };
        if reconcile && !self.reconcile_call_modes().await {
            crate::emit_log("CALL-AUDIO: pending borrow state could not be reconciled; close session");
            self.control.close();
        }
        None
    }

    pub(crate) async fn return_call_borrow(&self, lease: &CallAudioLease) -> bool {
        let _serial = self.serial.lock().await;
        let proposal = self.modes.lock().call_audio_end(lease);
        let Some(proposal) = proposal else { return true; };
        matches!(self.call_declaration(&proposal.declaration()).await, CallAck::Accepted)
            && self.commit_call_audio(&proposal)
    }

    fn commit_call_audio(&self, proposal: &CallAudioTransition) -> bool {
        let mut modes = self.modes.lock();
        if !modes.commit_call_audio(proposal) { return false; }
        self.control.buffered_ownership.publish_with_gate(&self.owned, modes.audio_can_send());
        true
    }

    pub(crate) fn enable_call_audio(&self, lease: &CallAudioLease) -> bool {
        let mut modes = self.modes.lock();
        if !modes.enable_call_audio(lease) { return false; }
        self.control.buffered_ownership.publish_with_gate(&self.owned, modes.audio_can_send());
        true
    }

    async fn call_declaration(&self, body: &[u8]) -> CallAck {
        let reply = self.control.request("POST", "/command", body, "application/x-apple-binary-plist", &[],
            std::time::Duration::from_secs(2)).await;
        match reply {
            Ok(reply) if response_accepted(reply.status(), &reply.body) => CallAck::Accepted,
            Ok(reply) if response_rejected(reply.status(), &reply.body) => CallAck::Rejected,
            _ => CallAck::Unconfirmed,
        }
    }

    async fn reconcile_call_modes(&self) -> bool {
        for _ in 0..2 {
            let (epochs, body) = {
                let modes = self.modes.lock();
                (modes.call_audio_epochs(), modes.declaration())
            };
            if !matches!(self.call_declaration(&body).await, CallAck::Accepted) { return false; }
            if self.modes.lock().call_audio_epochs() == epochs { return true; }
        }
        false
    }

    pub(crate) async fn set_phone_call(&self, active: bool) -> bool {
        let _serial = self.serial.lock().await;
        let (current, body) = {
            let modes = self.modes.lock();
            let current = modes.phone_entity();
            let next = i64::from(active);
            if current == next && active { return true; }
            if current == 2 { return !active; }
            if active && !modes.audio_owned() { return false; }
            let mut proposed = modes.clone();
            proposed.set_phone_entity(next);
            (current, proposed.declaration())
        };
        let accepted = self.control.request("POST", "/command", &body, "application/x-apple-binary-plist", &[],
            std::time::Duration::from_secs(2)).await.is_ok_and(|reply| response_accepted(reply.status(), &reply.body));
        if !accepted { return false; }
        let mut modes = self.modes.lock();
        if modes.phone_entity() != current { return false; }
        modes.set_phone_entity(i64::from(active));
        true
    }
    pub async fn begin_speech(&self,user_initiated:bool)->Option<SpeechLease> {
        let _serial=self.serial.lock().await;
        let (proposal,lease)=self.modes.lock().speech_begin(user_initiated)?;
        if self.publish(proposal).await {Some(lease)} else {None}
    }
    pub(crate) async fn begin_recognition(&self)->Option<SpeechLease> {
        let _serial=self.serial.lock().await;
        let (proposal,lease)=self.modes.lock().recognition_begin()?;
        if self.publish(proposal).await {Some(lease)} else {None}
    }
    pub async fn end_speech(&self,lease:SpeechLease)->bool {
        let _serial=self.serial.lock().await;
        let proposal=self.modes.lock().speech_end(&lease);
        match proposal {
            Some(proposal)=>self.publish(proposal).await || self.modes.lock().speech_end(&lease).is_none(),
            None=>true,
        }
    }
    #[cfg(test)]
    async fn begin_navigation(&self)->Option<NavigationLease> {
        let (proposal,lease)=self.modes.lock().navigation_begin()?;
        if self.publish_test_navigation(proposal).await {Some(lease)} else {None}
    }
    #[cfg(test)]
    async fn end_navigation(&self,lease:NavigationLease)->bool {
        let proposal=self.modes.lock().navigation_end(&lease);
        match proposal {
            Some(proposal)=>self.publish_test_navigation(proposal).await || self.modes.lock().navigation_end(&lease).is_none(),
            None=>true,
        }
    }
    #[cfg(test)]
    async fn publish_test_navigation(&self, proposal: AuxTransition) -> bool {
        self.control.request("POST", "/command", &proposal.navigation_declaration(),
            "application/x-apple-binary-plist", &[], Duration::from_secs(3)).await
            .is_ok_and(|reply| response_accepted(reply.status(), &reply.body))
            && self.modes.lock().commit_aux(&proposal)
    }

    pub(crate) async fn follow_navigation_route(&self, shutdown: Arc<crate::shutdown::EngineShutdown>,
        route_generation: impl Fn() -> Option<Option<u64>> + Sync,
        with_route_write: impl Fn(Option<u64>, &mut dyn FnMut() -> std::io::Result<usize>) -> Option<std::io::Result<usize>> + Sync) {
        const POLL: Duration = Duration::from_millis(250);
        const RETRY: Duration = Duration::from_secs(1);
        let mut tick = tokio::time::interval_at(tokio::time::Instant::now() + POLL, POLL);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut lease: Option<NavigationLease> = None;
        let mut observed = None;
        let mut attempts = 0;
        let mut retry_at = tokio::time::Instant::now();
        loop {
            tokio::select! {
                biased;
                _ = shutdown.wait() => return,
                _ = self.control.wait_for_session_end() => return,
                _ = tick.tick() => (),
            }
            if !self.control.session_is_live() { return; }
            let Some(generation) = route_generation() else { continue; };
            let active = generation.is_some();
            let receiver_epoch = self.modes.lock().navigation_receiver_epoch();
            if observed != Some((generation, receiver_epoch)) {
                observed = Some((generation, receiver_epoch));
                attempts = 0;
                retry_at = tokio::time::Instant::now();
            }
            if attempts >= 3 || tokio::time::Instant::now() < retry_at { continue; }
            let proposal = {
                let modes = self.modes.lock();
                if lease.as_ref().is_some_and(|owned| !owned.valid(&modes)) { lease = None; }
                if active {
                    match lease.as_ref() {
                        Some(owned) if modes.navigation_needs_reassertion() => modes.navigation_reassert(owned),
                        None => modes.navigation_begin(),
                        Some(_) => None,
                    }.map(|(proposal, owned)| (proposal, Some(owned)))
                } else {
                    lease.as_ref().map_or_else(
                        || modes.reconcile_unconfirmed_navigation(receiver_epoch),
                        |owned| modes.navigation_end(owned),
                    ).map(|proposal| (proposal, None))
                }
            };
            let Some((proposal, new_lease)) = proposal else { continue; };
            let previous_wire_state = Mutex::new(None);
            let check = |write: &mut dyn FnMut() -> std::io::Result<usize>| {
                // Match the cluster sender's modes -> shutdown -> RTSP lock order.
                let mut modes = self.modes.lock();
                if !modes.aux_transition_current(&proposal) { return None; }
                let result = shutdown.while_running(|| self.control.while_session_live(|| {
                    self.control.without_pending_native_navigation(|| with_route_write(generation, write)).flatten()
                }).flatten()).flatten();
                if matches!(result.as_ref(), Some(Ok(written)) if *written != 0) {
                    *previous_wire_state.lock() = Some(modes.publish_navigation(&proposal));
                }
                result
            };
            let cancel = async {
                loop {
                    tokio::select! {
                        biased;
                        _ = shutdown.wait() => break,
                        _ = self.control.wait_for_session_end() => break,
                        _ = tick.tick() => {
                            if route_generation().is_some_and(|latest| latest != generation) ||
                                self.modes.lock().navigation_receiver_epoch() != receiver_epoch { break; }
                        }
                    }
                }
            };
            let result = self.control.command_while(&proposal.navigation_declaration(), Duration::from_secs(3), &check, cancel).await;
            if shutdown.requested() || !self.control.session_is_live() { return; }
            let accepted = result.as_ref().is_ok_and(|reply| response_accepted(reply.status(), &reply.body));
            if accepted && self.modes.lock().commit_aux(&proposal) {
                lease = new_lease;
                crate::emit_log(&format!("NAVIGATION-RESOURCE: route active={active} acknowledged"));
            } else if !matches!(result, Err(crate::rtsp::RequestError::Withdrawn)) {
                if let Some(previous) = previous_wire_state.into_inner().filter(|_| {
                    result.as_ref().is_ok_and(|reply| response_rejected(reply.status(), &reply.body))
                }) {
                    self.modes.lock().reject_navigation(&proposal, previous);
                }
                attempts += 1;
                retry_at = tokio::time::Instant::now() + RETRY;
            }
        }
    }
    #[cfg(test)]
    async fn follow_test_navigation_route(&self, shutdown: Arc<crate::shutdown::EngineShutdown>,
        route_generation: impl Fn() -> Option<Option<u64>> + Sync) {
        self.follow_navigation_route(shutdown, &route_generation, |expected, write| {
            (route_generation() == Some(expected)).then(write)
        }).await;
    }
    async fn publish(&self,proposal:AuxTransition)->bool {
        let result=self.control.request("POST","/command",&proposal.declaration(),
            "application/x-apple-binary-plist",&[],std::time::Duration::from_secs(3)).await;
        let accepted=result.map(|reply|response_accepted(reply.status(),&reply.body)).unwrap_or(false);
        if !accepted {crate::emit_log("SPEECH-RESOURCE: declaration rejected/timed out; gate unchanged");return false;}
        let mut modes=self.modes.lock();
        let committed=modes.commit_aux(&proposal);
        self.control.buffered_ownership.publish_with_gate(&self.owned, modes.audio_can_send());
        crate::emit_log(&format!("SPEECH-RESOURCE: acknowledged committed={committed}; receiver audibility unconfirmed"));
        committed
    }
}
fn response_rejected(status: u16, body: &[u8]) -> bool {
    (400..500).contains(&status) || (status == 200 && bplist::parse(body).ok()
        .and_then(|body| body.get("status").and_then(bplist::Value::as_int)).is_some_and(|status| status != 0))
}
fn response_accepted(status:u16,body:&[u8])->bool {
    if status!=200 {return false;}
    if body.is_empty() {return true;}
    let Ok(value)=bplist::parse(body) else {return false;};
    value.as_dict().is_some() && match value.get("status") {None=>true,Some(status)=>status.as_int()==Some(0)}
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bplist::Value;
    use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{TcpListener,TcpStream}};
    async fn request(peer:&mut TcpStream)->crate::rtsp::RtspMessage {
        request_with_timeout(peer, Duration::from_secs(1)).await
    }
    async fn request_with_timeout(peer: &mut TcpStream, timeout: Duration) -> crate::rtsp::RtspMessage {
        let mut bytes=Vec::new();
        tokio::time::timeout(timeout,async {
            loop {let mut chunk=[0;4096];let n=peer.read(&mut chunk).await.unwrap();assert!(n>0);
                bytes.extend_from_slice(&chunk[..n]);
                if let Some(message)=crate::rtsp::parse_rtsp_messages(&mut bytes).unwrap().into_iter().next(){return message;}
            }
        }).await.unwrap()
    }
    async fn ack(peer:&mut TcpStream,message:&crate::rtsp::RtspMessage,status:u16) {
        let header=format!("RTSP/1.0 {status} Response\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",message.cseq().unwrap());
        peer.write_all(header.as_bytes()).await.unwrap();
    }

    async fn receiver_command(peer: &mut TcpStream, cseq: u32, kind: &str, params: Value) {
        let body = bplist::encode(&Value::dict(vec![("type", Value::str(kind)), ("params", params)]));
        let mut request = format!("POST /command RTSP/1.0\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", body.len()).into_bytes();
        request.extend(body);
        peer.write_all(&request).await.unwrap();
    }

    async fn route_fixture() -> (Arc<SpeechResource>, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (control, _) = RtspChannel::spawn(r, w, None);
        let modes = Arc::new(Mutex::new(Modes::box_initial()));
        (Arc::new(SpeechResource::new(control, modes, Arc::new(AtomicBool::new(false)))), peer)
    }

    fn receiver_navigation(modes: &mut Modes, active: bool) {
        assert!(modes.change(&Value::dict(vec![("appStates", Value::array(vec![Value::dict(vec![
            ("appStateID", Value::int(3)), ("state", Value::Bool(active)),
        ])]))])));
    }

    fn navigation_entity(message: &crate::rtsp::RtspMessage) -> i64 {
        let mode = bplist::parse(&message.body).unwrap();
        assert_eq!(mode.get("type").and_then(Value::as_str), Some("modesChanged"));
        snapshot_navigation_entity(&mode)
    }

    fn snapshot_navigation_entity(mode: &Value) -> i64 {
        optional_navigation_entity(mode).unwrap()
    }

    fn optional_navigation_entity(mode: &Value) -> Option<i64> {
        let apps = mode.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
        apps.iter().find(|app| app.get("appStateID").and_then(Value::as_int) == Some(3))
            .and_then(|app| app.get("entity")).and_then(Value::as_int)
    }

    fn native_navigation_params(active: bool) -> Value {
        Value::dict(vec![("appStates", Value::array(vec![Value::dict(vec![
            ("appStateID", Value::int(3)), ("state", Value::Bool(active)),
        ])]))])
    }

    async fn route_event(resource: &SpeechResource) -> (Arc<RtspChannel>, TcpStream,
        tokio::sync::mpsc::UnboundedReceiver<crate::rtsp::RtspMessage>, Arc<tokio::sync::Notify>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (event_peer, _) = listener.accept().await.unwrap();
        let (read, write) = socket.into_split();
        let encoded = Arc::new(tokio::sync::Notify::new());
        let reply: crate::rtsp::CommandReply = Arc::new({
            let modes = resource.modes.clone();
            let encoded = encoded.clone();
            move |message| {
                let command = bplist::parse(&message.body).ok()?;
                let reply = modes.lock().response(&command, &Default::default())?;
                encoded.notify_one();
                Some(reply.body)
            }
        });
        let (event, inbound) = resource.control.spawn_event(read, write, Some(reply), Arc::new(|_, _| {}));
        (event, event_peer, inbound, encoded)
    }

    #[tokio::test]
    async fn speech_declaration_omits_app3_while_the_navigation_claim_waits_for_ack() {
        let (resource, mut peer) = route_fixture().await;
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(Some(1))).await }
        });
        let claim = request(&mut peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        let speech = tokio::spawn({
            let resource = resource.clone();
            async move { resource.begin_speech(false).await }
        });
        let speech_declaration = request_with_timeout(&mut peer, Duration::from_millis(500)).await;
        let mode = bplist::parse(&speech_declaration.body).unwrap();
        assert_eq!(optional_navigation_entity(&mode), None);
        assert_eq!(mode.get("params").unwrap().get("appStates").unwrap().as_array().unwrap()[0]
            .get("entity").and_then(Value::as_int), Some(1));
        ack(&mut peer, &speech_declaration, 200).await;
        assert!(speech.await.unwrap().is_some());
        ack(&mut peer, &claim, 403).await;
        tokio::time::timeout(Duration::from_millis(500), async {
            while resource.modes.lock().wire_navigation(false) != Some(0) { tokio::task::yield_now().await; }
        }).await.unwrap();
        let feedback = tokio::spawn({
            let resource = resource.clone();
            async move {
                let body = resource.modes.lock().declaration();
                resource.control.request("POST", "/command", &body,
                    "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await
            }
        });
        let feedback_wire = request(&mut peer).await;
        assert_eq!(navigation_entity(&feedback_wire), 0);
        ack(&mut peer, &feedback_wire, 200).await;
        feedback.await.unwrap().unwrap();
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn native_release_holds_projected_claim_until_its_business_ack() {
        let (resource, mut control_peer) = route_fixture().await;
        receiver_navigation(&mut resource.modes.lock(), true);
        let (event, mut event_peer, _, encoded) = route_event(&resource).await;
        let writer = event.hold_writer_for_test().await;
        receiver_command(&mut event_peer, 19, "changeModes", native_navigation_params(false)).await;
        tokio::time::timeout(Duration::from_secs(1), encoded.notified()).await.unwrap();
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(Some(1))).await }
        });
        assert!(tokio::time::timeout(Duration::from_millis(350), control_peer.read_u8()).await.is_err());
        drop(writer);
        let release = request(&mut event_peer).await;
        assert_eq!(release.cseq(), Some(19));
        assert_eq!(snapshot_navigation_entity(&bplist::parse(&release.body).unwrap()), 0);
        let claim = request(&mut control_peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        ack(&mut control_peer, &claim, 200).await;
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn queued_native_events_each_acknowledge_their_own_navigation_entity() {
        let (resource, _control_peer) = route_fixture().await;
        let (_event, mut peer, _, _) = route_event(&resource).await;
        let mut batch = Vec::new();
        for (cseq, active) in [(4, true), (5, false)] {
            let command = Value::dict(vec![("type", Value::str("changeModes")),
                ("params", native_navigation_params(active))]);
            let body = bplist::encode(&command);
            batch.extend_from_slice(format!("POST /command RTSP/1.0\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", body.len()).as_bytes());
            batch.extend(body);
        }
        peer.write_all(&batch).await.unwrap();
        let replies = tokio::time::timeout(Duration::from_secs(1), async {
            let mut pending = Vec::new();
            let mut replies = Vec::new();
            while replies.len() < 2 {
                let mut bytes = [0; 4096];
                let count = peer.read(&mut bytes).await.unwrap();
                assert_ne!(count, 0);
                pending.extend_from_slice(&bytes[..count]);
                replies.extend(crate::rtsp::parse_rtsp_messages(&mut pending).unwrap());
            }
            replies
        }).await.unwrap();
        for (response, (cseq, entity)) in replies.iter().zip([(4, 2), (5, 0)]) {
            assert_eq!(response.cseq(), Some(cseq));
            assert_eq!(snapshot_navigation_entity(&bplist::parse(&response.body).unwrap()), entity);
        }
    }

    #[tokio::test]
    async fn a_snapshot_queued_before_native_navigation_reports_native_ownership_at_write() {
        let (resource, mut peer) = route_fixture().await;
        let writer = resource.control.hold_writer_for_test().await;
        let snapshot = resource.modes.lock().declaration();
        let pending = tokio::spawn({
            let resource = resource.clone();
            async move {
                resource.control.request("POST", "/command", &snapshot,
                    "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await
            }
        });
        tokio::time::timeout(Duration::from_secs(1), async {
            while resource.control.last_cseq_for_test() == 0 { tokio::task::yield_now().await; }
        }).await.unwrap();
        receiver_navigation(&mut resource.modes.lock(), true);
        drop(writer);
        let snapshot = request(&mut peer).await;
        assert_eq!(navigation_entity(&snapshot), 2);
        ack(&mut peer, &snapshot, 200).await;
        pending.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn unrelated_change_modes_reply_omits_unconfirmed_navigation_claim_and_release() {
        use std::sync::atomic::AtomicU64;
        let (resource, mut control_peer) = route_fixture().await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (read, write) = socket.into_split();
        let encoded = Arc::new(tokio::sync::Notify::new());
        let reply: crate::rtsp::CommandReply = Arc::new({
            let modes = resource.modes.clone();
            let encoded = encoded.clone();
            move |message| {
                let command = bplist::parse(&message.body).ok()?;
                let reply = modes.lock().response(&command, &Default::default())?;
                encoded.notify_one();
                Some(reply.body)
            }
        });
        let (event, _inbound) = resource.control.spawn_event(read, write, Some(reply), Arc::new(|_, _| {}));
        let generation = Arc::new(AtomicU64::new(0));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let generation = generation.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || {
                let current = generation.load(Ordering::Acquire);
                Some((current != 0).then_some(current))
            }).await }
        });
        for (cseq, next, entity) in [(1, 7, 1), (2, 0, 0)] {
            let writer = event.hold_writer_for_test().await;
            receiver_command(&mut event_peer, cseq, "changeModes", Value::dict(vec![])).await;
            tokio::time::timeout(Duration::from_secs(1), encoded.notified()).await.unwrap();
            generation.store(next, Ordering::Release);
            let navigation = request(&mut control_peer).await;
            assert_eq!(navigation_entity(&navigation), entity);
            let body = bplist::parse(&navigation.body).unwrap();
            let params = body.get("params").unwrap();
            assert!(params.get("resources").is_none());
            assert_eq!(params.get("appStates").unwrap().as_array().unwrap().len(), 1);
            drop(writer);
            let reply = request(&mut event_peer).await;
            assert_eq!(reply.cseq(), Some(cseq as i32));
            assert_eq!(optional_navigation_entity(&bplist::parse(&reply.body).unwrap()), None);
            ack(&mut control_peer, &navigation, 200).await;
            if next != 0 {
                tokio::time::timeout(Duration::from_secs(1), async {
                    while resource.modes.lock().navigation_begin().is_some() { tokio::task::yield_now().await; }
                }).await.unwrap();
            }
        }
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn replacement_generation_retries_after_three_refused_claims() {
        use std::sync::atomic::AtomicU64;
        let (resource, mut peer) = route_fixture().await;
        let generation = Arc::new(AtomicU64::new(7));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let generation = generation.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(Some(generation.load(Ordering::Acquire)))).await }
        });
        for _ in 0..3 {
            let claim = request_with_timeout(&mut peer, Duration::from_millis(1750)).await;
            assert_eq!(navigation_entity(&claim), 1);
            ack(&mut peer, &claim, 403).await;
        }
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(1500), peer.read(&mut byte)).await.is_err());
        let feedback = tokio::spawn({
            let resource = resource.clone();
            async move {
                let body = resource.modes.lock().declaration();
                resource.control.request("POST", "/command", &body,
                    "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await
            }
        });
        let feedback_wire = request(&mut peer).await;
        assert_eq!(navigation_entity(&feedback_wire), 0, "an explicit refusal must retire the optimistic claim");
        ack(&mut peer, &feedback_wire, 200).await;
        feedback.await.unwrap().unwrap();
        generation.store(8, Ordering::Release);
        let replacement = request(&mut peer).await;
        assert_eq!(navigation_entity(&replacement), 1);
        ack(&mut peer, &replacement, 200).await;
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn replacement_generation_withdraws_the_previous_claim_ack_wait() {
        use std::sync::atomic::AtomicU64;
        let (resource, mut peer) = route_fixture().await;
        let generation = Arc::new(AtomicU64::new(7));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let generation = generation.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || {
                Some(Some(generation.load(Ordering::Acquire)))
            }).await }
        });
        let previous = request(&mut peer).await;
        assert_eq!(navigation_entity(&previous), 1);
        generation.store(8, Ordering::Release);
        let replacement = request(&mut peer).await;
        assert_eq!(navigation_entity(&replacement), 1);
        assert_ne!(replacement.cseq(), previous.cseq());
        assert!(!resource.control.has_pending_reply_for_test(previous.cseq().unwrap()));
        assert!(resource.modes.lock().navigation_begin().is_some());
        ack(&mut peer, &previous, 200).await;
        ack(&mut peer, &replacement, 200).await;
        tokio::time::timeout(Duration::from_secs(1), async {
            while resource.modes.lock().navigation_begin().is_some() { tokio::task::yield_now().await; }
        }).await.unwrap();
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn route_ownership_respects_native_navigation_and_remains_between_announcements() {
        let (resource, mut peer) = route_fixture().await;
        let active = Arc::new(AtomicBool::new(true));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        receiver_navigation(&mut resource.modes.lock(), true);
        let worker = tokio::spawn({
            let resource = resource.clone();
            let active = active.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(active.load(Ordering::Acquire).then_some(1))).await }
        });
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(320), peer.read(&mut byte)).await.is_err());
        assert!(resource.modes.lock().navigation_audio().is_none());
        receiver_navigation(&mut resource.modes.lock(), false);
        let claim = request(&mut peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        ack(&mut peer, &claim, 200).await;
        let audio = resource.modes.lock().navigation_audio().unwrap();
        assert!(audio.valid(&resource.modes.lock()));
        // No new modes notification is driven by an audio announcement starting or ending.
        assert!(tokio::time::timeout(Duration::from_millis(320), peer.read(&mut byte)).await.is_err());
        active.store(false, Ordering::Release);
        let release = request(&mut peer).await;
        assert_eq!(navigation_entity(&release), 0);
        ack(&mut peer, &release, 200).await;
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn navigation_claim_waiting_for_writer_is_withdrawn_when_route_finishes() {
        let (resource, mut peer) = route_fixture().await;
        let active = Arc::new(AtomicBool::new(true));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let writer = resource.control.hold_writer_for_test().await;
        let worker = tokio::spawn({
            let resource = resource.clone();
            let active = active.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(active.load(Ordering::Acquire).then_some(1))).await }
        });
        tokio::time::timeout(Duration::from_secs(1), async {
            while resource.control.last_cseq_for_test() == 0 {
                tokio::task::yield_now().await;
            }
        }).await.unwrap();
        active.store(false, Ordering::Release);
        drop(writer);
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(320), peer.read(&mut byte)).await.is_err());
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn a_written_unconfirmed_claim_is_reconciled_when_navigation_ends() {
        let (resource, mut peer) = route_fixture().await;
        let active = Arc::new(AtomicBool::new(true));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let active = active.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(active.load(Ordering::Acquire).then_some(1))).await }
        });
        let claim = request(&mut peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        active.store(false, Ordering::Release);
        // The receiver saw the claim but its response is lost. A route end still owes a neutral declaration.
        let clear = request_with_timeout(&mut peer, Duration::from_secs(4)).await;
        assert_eq!(navigation_entity(&clear), 0);
        assert!(resource.modes.lock().navigation_begin().is_some(), "timeout must not grant local ownership");
        ack(&mut peer, &clear, 200).await;
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(320), peer.read(&mut byte)).await.is_err());
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn native_navigation_waiting_for_the_event_writer_has_priority_over_a_claim() {
        let (resource, mut control_peer) = route_fixture().await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (read, write) = socket.into_split();
        let modes = resource.modes.clone();
        let reply: crate::rtsp::CommandReply = Arc::new(move |message| {
            let body = bplist::parse(&message.body).ok()?;
            let params = body.get("params")?;
            assert!(modes.lock().change(params));
            None
        });
        let (event, mut inbound) = resource.control.spawn_event(read, write, Some(reply), Arc::new(|_, _| {}));
        let writer = event.hold_writer_for_test().await;
        event_peer.write_all(b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n").await.unwrap();
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), inbound.recv()).await.unwrap().unwrap().cseq(), Some(1));
        async fn native_navigation(peer: &mut TcpStream, active: bool, cseq: u32) {
            let body = bplist::encode(&Value::dict(vec![
                ("type", Value::str("changeModes")),
                ("params", Value::dict(vec![("appStates", Value::array(vec![Value::dict(vec![
                    ("appStateID", Value::int(3)), ("state", Value::Bool(active)),
                ])]))])),
            ]));
            let mut request = format!("POST /command RTSP/1.0\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", body.len()).into_bytes();
            request.extend(body);
            peer.write_all(&request).await.unwrap();
        }
        native_navigation(&mut event_peer, true, 2).await;
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(Some(1))).await }
        });
        let mut byte = [0];
        assert!(tokio::time::timeout(Duration::from_millis(500), control_peer.read(&mut byte)).await.is_err());
        assert!(resource.modes.lock().navigation_audio().is_some());
        let feedback = tokio::spawn({
            let resource = resource.clone();
            async move {
                let body = resource.modes.lock().declaration();
                resource.control.request("POST", "/command", &body,
                    "application/x-apple-binary-plist", &[], Duration::from_secs(1)).await
            }
        });
        let snapshot = request(&mut control_peer).await;
        let snapshot_body = bplist::parse(&snapshot.body).unwrap();
        let apps = snapshot_body.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
        assert_eq!(apps.iter().filter_map(|app| app.get("appStateID").and_then(Value::as_int)).collect::<Vec<_>>(), vec![1, 2]);
        ack(&mut control_peer, &snapshot, 200).await;
        feedback.await.unwrap().unwrap();
        drop(writer);
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), inbound.recv()).await.unwrap().unwrap().cseq(), Some(2));
        assert!(resource.modes.lock().navigation_audio().is_none());
        native_navigation(&mut event_peer, false, 3).await;
        assert_eq!(tokio::time::timeout(Duration::from_secs(1), inbound.recv()).await.unwrap().unwrap().cseq(), Some(3));
        let claim = request(&mut control_peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        ack(&mut control_peer, &claim, 200).await;
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn a_new_route_reasserts_navigation_after_the_release_ack_is_lost() {
        use std::sync::atomic::AtomicU64;
        let (resource, mut peer) = route_fixture().await;
        let generation = Arc::new(AtomicU64::new(7));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let generation = generation.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || {
                let current = generation.load(Ordering::Acquire);
                Some((current != 0).then_some(current))
            }).await }
        });
        let claim = request(&mut peer).await;
        assert_eq!(navigation_entity(&claim), 1);
        ack(&mut peer, &claim, 200).await;
        generation.store(0, Ordering::Release);
        let release = request(&mut peer).await;
        assert_eq!(navigation_entity(&release), 0);
        generation.store(8, Ordering::Release);
        let reassertion = request(&mut peer).await;
        assert_eq!(navigation_entity(&reassertion), 1);
        ack(&mut peer, &release, 200).await;
        ack(&mut peer, &reassertion, 200).await;
        generation.store(0, Ordering::Release);
        let final_release = request(&mut peer).await;
        assert_eq!(navigation_entity(&final_release), 0);
        ack(&mut peer, &final_release, 200).await;
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn replacement_route_reasserts_after_the_previous_reassertion_times_out() {
        use std::sync::atomic::AtomicU64;
        let (resource, mut peer) = route_fixture().await;
        let generation = Arc::new(AtomicU64::new(7));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let generation = generation.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || {
                let current = generation.load(Ordering::Acquire);
                Some((current != 0).then_some(current))
            }).await }
        });
        let original = request(&mut peer).await;
        assert_eq!(navigation_entity(&original), 1);
        ack(&mut peer, &original, 200).await;
        generation.store(0, Ordering::Release);
        let release = request(&mut peer).await;
        assert_eq!(navigation_entity(&release), 0);
        generation.store(8, Ordering::Release);
        let previous = request(&mut peer).await;
        assert_eq!(navigation_entity(&previous), 1);
        tokio::time::timeout(Duration::from_secs(4), async {
            while resource.control.has_pending_reply_for_test(previous.cseq().unwrap()) {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }).await.unwrap();
        generation.store(9, Ordering::Release);
        let current = request(&mut peer).await;
        assert_eq!(navigation_entity(&current), 1);
        assert_ne!(current.cseq(), previous.cseq());
        ack(&mut peer, &previous, 200).await;
        ack(&mut peer, &current, 200).await;
        assert!(tokio::time::timeout(Duration::from_millis(350), peer.read_u8()).await.is_err());
        shutdown.request(false);
        worker.await.unwrap();
    }

    #[tokio::test]
    async fn shutdown_releases_the_audio_serial_without_waiting_for_navigation_ack() {
        let (resource, mut peer) = route_fixture().await;
        let mut peer_cipher = crate::rtsp::ControlCipher::new([2; 32], [1; 32]);
        resource.control.set_cipher(crate::rtsp::ControlCipher::new([1; 32], [2; 32]));
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let worker = tokio::spawn({
            let resource = resource.clone();
            let shutdown = shutdown.clone();
            async move { resource.follow_test_navigation_route(shutdown, || Some(Some(1))).await }
        });
        async fn read_encrypted(peer: &mut TcpStream, cipher: &mut crate::rtsp::ControlCipher) -> crate::rtsp::RtspMessage {
            tokio::time::timeout(Duration::from_secs(1), async {
                let mut plaintext = Vec::new();
                loop {
                    let mut header = [0; 2];
                    peer.read_exact(&mut header).await.unwrap();
                    let mut packet = vec![0; 2 + usize::from(u16::from_le_bytes(header)) + 16];
                    packet[..2].copy_from_slice(&header);
                    peer.read_exact(&mut packet[2..]).await.unwrap();
                    plaintext.extend(cipher.decrypt(&packet).unwrap().0);
                    if let Some(message) = crate::rtsp::parse_rtsp_messages(&mut plaintext).unwrap().pop() {
                        return message;
                    }
                }
            }).await.unwrap()
        }
        let claim = read_encrypted(&mut peer, &mut peer_cipher).await;
        assert_eq!(navigation_entity(&claim), 1);
        shutdown.request(true);
        tokio::time::timeout(Duration::from_millis(250), worker).await.unwrap().unwrap();
        assert!(resource.serial.try_lock().is_ok());
        assert!(!resource.control.has_pending_reply_for_test(claim.cseq().unwrap()));
        assert!(!resource.control.is_closed(), "cancelling a reply wait leaves the writer available for cleanup");
        let cleanup = tokio::spawn({
            let control = resource.control.clone();
            async move { control.request("TEARDOWN", "/", &[], "", &[], Duration::from_secs(1)).await }
        });
        let teardown = read_encrypted(&mut peer, &mut peer_cipher).await;
        assert_eq!(teardown.first_line, "TEARDOWN / RTSP/1.0");
        let header = format!("RTSP/1.0 200 Response\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n", teardown.cseq().unwrap());
        peer.write_all(&peer_cipher.encrypt(header.as_bytes())).await.unwrap();
        assert_eq!(cleanup.await.unwrap().unwrap().status(), 200);
    }

    #[derive(Clone)]
    enum TestLease {Speech(SpeechLease),Navigation(NavigationLease)}
    impl TestLease {
        async fn end(self,resource:&SpeechResource)->bool {match self {
            Self::Speech(lease)=>resource.end_speech(lease).await,
            Self::Navigation(lease)=>resource.end_navigation(lease).await,
        }}
        fn valid(&self,resource:&SpeechResource)->bool {match self {
            Self::Speech(lease)=>resource.speech_valid(lease),Self::Navigation(lease)=>resource.navigation_valid(lease),
        }}
    }
    #[tokio::test]
    async fn unrelated_screen_update_merges_after_ack_and_rejected_end_is_retryable() {
        for navigation in [false,true] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let resource=Arc::new(SpeechResource::new(control,Arc::new(Mutex::new(Modes::box_initial())),Arc::new(AtomicBool::new(false))));
            let worker=resource.clone();let begin=tokio::spawn(async move {
                if navigation {worker.begin_navigation().await.map(TestLease::Navigation)}else{worker.begin_speech(false).await.map(TestLease::Speech)}
            });
            let incoming=request(&mut peer).await;
            assert!(resource.modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                ("resourceID",Value::int(1)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
                ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(1000)),
            ])]))])));
            ack(&mut peer,&incoming,200).await;let lease=begin.await.unwrap().unwrap();
            assert!(lease.valid(&resource));assert!(!resource.modes.lock().screen_owned());
            let worker=resource.clone();let retry=lease.clone();let end=tokio::spawn(async move {retry.end(&worker).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,403).await;
            assert!(!end.await.unwrap(),"refused cleanup must retain a retryable lease");assert!(lease.valid(&resource));
            let worker=resource.clone();let retry=lease.clone();let end=tokio::spawn(async move {retry.end(&worker).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
            assert!(end.await.unwrap());assert!(!lease.valid(&resource));assert!(!resource.modes.lock().screen_owned());
            // Cleanup was already completed; callers may safely retire their retained lease.
            assert!(lease.end(&resource).await);
        }
    }
    #[tokio::test]
    async fn concurrent_navigation_declaration_leaves_unacknowledged_speech_alone() {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
        let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let resource=Arc::new(SpeechResource::new(control,Arc::new(Mutex::new(Modes::box_initial())),Arc::new(AtomicBool::new(false))));
        let worker=resource.clone();let speech=tokio::spawn(async move {worker.begin_speech(false).await});
        let first=request(&mut peer).await;
        let worker=resource.clone();let navigation=tokio::spawn(async move {worker.begin_navigation().await});
        let second=request(&mut peer).await;let declaration=bplist::parse(&second.body).unwrap();
        let apps=declaration.get("params").unwrap().get("appStates").unwrap().as_array().unwrap();
        assert_eq!(apps.len(), 1);
        assert_eq!(apps[0].get("appStateID").and_then(Value::as_int),Some(3));
        assert_eq!(apps[0].get("entity").and_then(Value::as_int),Some(1));
        ack(&mut peer,&second,200).await;let navigation=navigation.await.unwrap().unwrap();
        ack(&mut peer,&first,200).await;let speech=speech.await.unwrap().unwrap();
        assert!(resource.speech_valid(&speech));assert!(resource.navigation_valid(&navigation));
    }
    #[tokio::test]
    async fn speech_end_waits_for_ack_and_cannot_restore_over_receiver_take() {
        for retake in [false,true] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let modes=Arc::new(Mutex::new(Modes::box_initial()));let owned=Arc::new(AtomicBool::new(false));
            let resource=Arc::new(SpeechResource::new(control,modes.clone(),owned.clone()));
            let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
            let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
            let lease=task.await.unwrap().unwrap();assert!(resource.speech_valid(&lease));assert!(owned.load(Ordering::Acquire));
            let worker=resource.clone();let task=tokio::spawn(async move {worker.end_speech(lease).await});
            let incoming=request(&mut peer).await;
            assert!(owned.load(Ordering::Acquire),"unacknowledged end must not change the gate");
            if retake {assert!(modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
                ("resourceID",Value::int(2)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
                ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(1000)),
            ])]))])));}
            ack(&mut peer,&incoming,200).await;task.await.unwrap();
            assert!(!owned.load(Ordering::Acquire));assert!(!modes.lock().audio_owned());
            let speech=modes.lock().snapshot().get("appStates").unwrap().as_array().unwrap()[0].clone();
            assert_eq!(speech.get("entity").and_then(Value::as_int),Some(if retake {1}else{0}));
        }
    }
    #[tokio::test]
    async fn end_after_receiver_audio_take_clears_only_our_speech_and_allows_next_reply() {
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
        let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
        let modes=Arc::new(Mutex::new(Modes::box_initial()));
        let resource=Arc::new(SpeechResource::new(control,modes.clone(),Arc::new(AtomicBool::new(false))));
        let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
        let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;let lease=task.await.unwrap().unwrap();
        assert!(modes.lock().change(&Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![
            ("resourceID",Value::int(2)),("transferType",Value::int(1)),("transferPriority",Value::int(500)),
            ("takeConstraint",Value::int(1000)),("borrowConstraint",Value::int(100)),
        ])]))])));assert!(!resource.speech_valid(&lease));
        let worker=resource.clone();let task=tokio::spawn(async move {worker.end_speech(lease).await});
        let incoming=request(&mut peer).await;let declaration=bplist::parse(&incoming.body).unwrap();
        let params=declaration.get("params").unwrap();
        assert_eq!(params.get("resources").unwrap().as_array().unwrap()[1].get("entity").and_then(Value::as_int),Some(2));
        assert_eq!(params.get("appStates").unwrap().as_array().unwrap()[0].get("entity").and_then(Value::as_int),Some(0));
        ack(&mut peer,&incoming,200).await;task.await.unwrap();assert!(!modes.lock().audio_owned());
        let worker=resource.clone();let task=tokio::spawn(async move {worker.begin_speech(false).await});
        let incoming=request(&mut peer).await;ack(&mut peer,&incoming,200).await;
        assert!(resource.speech_valid(&task.await.unwrap().unwrap()));
    }
    #[tokio::test]
    async fn speech_and_navigation_wait_for_business_ack_and_receiver_epochs() {
        for navigation in [false,true] {for (status,retake,business) in [(200,false,0),(403,false,0),(200,false,1),(200,true,0)] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let socket=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut peer,_)=listener.accept().await.unwrap();
            let(r,w)=socket.into_split();let(control,_)=RtspChannel::spawn(r,w,None);
            let modes=Arc::new(Mutex::new(Modes::box_initial()));let owned=Arc::new(AtomicBool::new(false));
            let resource=Arc::new(SpeechResource::new(control,modes.clone(),owned.clone()));
            let worker=resource.clone();let task=tokio::spawn(async move {
                if navigation {worker.begin_navigation().await.map(|_|())} else {worker.begin_speech(false).await.map(|_|())}
            });
            let incoming=request(&mut peer).await;
            assert!(!owned.load(Ordering::Acquire));assert!(!modes.lock().audio_owned());
            let declaration=bplist::parse(&incoming.body).unwrap();assert_eq!(declaration.get("type").and_then(Value::as_str),Some("modesChanged"));
            if retake {assert!(modes.lock().change(&Value::dict(vec![("appStates",Value::array(vec![Value::dict(vec![
                ("appStateID",Value::int(if navigation{3}else{1})),
                (if navigation{"state"}else{"speechMode"},if navigation{Value::Bool(true)}else{Value::int(2)}),
            ])]))])));}
            let body=bplist::encode(&Value::dict(vec![("status",Value::int(business))]));
            let header=format!("RTSP/1.0 {status} Response\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",incoming.cseq().unwrap(),body.len());
            peer.write_all(header.as_bytes()).await.unwrap();peer.write_all(&body).await.unwrap();
            let allowed=status==200 && business==0 && !retake;
            assert_eq!(task.await.unwrap().is_some(),allowed);
            assert_eq!(owned.load(Ordering::Acquire),allowed && !navigation);
            assert_eq!(modes.lock().audio_owned(),allowed && !navigation);
        }}
    }
    #[test] fn malformed_and_refused_business_replies_cannot_commit() {
        assert!(response_accepted(200,&[]));assert!(!response_accepted(500,&[]));
        assert!(!response_accepted(200,b"not a plist"));
        assert!(!response_accepted(200,&bplist::encode(&Value::dict(vec![("status",Value::str("0"))]))));
    }
}
