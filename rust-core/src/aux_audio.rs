//! Independent speech and navigation audio stream lifecycle.
use std::{collections::VecDeque,sync::{Arc,atomic::{AtomicBool,AtomicU8,AtomicU64,Ordering}},time::Duration};
use parking_lot::Mutex;
use tokio::sync::{mpsc,oneshot,watch,Notify};
use crate::{airplay::{AirplayParams,AudioCodec,SocketProvider},audio_out::AudioOut,aux_setup::Kind,bplist::Value,rtsp::RtspChannel,speech_resource::SpeechResource};
use crate::audio_pacing::StreamId;
use crate::mic_probe::{self, InputFormat, InputStream, ProbeControl};
use crate::shutdown::EngineShutdown;

pub enum MainCommand {
    Pause {
        reply: oneshot::Sender<Option<AudioOut>>,
        hold_mixed_voice: bool,
    },
    Install(Option<AudioOut>),
    ReplaceRealtime {
        output: AudioOut,
        grant: MainAudioGrant,
        reply: oneshot::Sender<()>,
    },
}

pub struct MainAudioGrant {
    pub modes: Arc<Mutex<crate::modes::Modes>>,
    pub generation: Arc<AtomicU64>,
    pub expected: u64,
    pub media_epoch: u64,
    pub shutdown: Arc<EngineShutdown>,
}

impl MainAudioGrant {
    pub fn is_current(&self, modes: &crate::modes::Modes) -> bool {
        !self.shutdown.requested() && modes.media_rebuild_epoch() == Some(self.media_epoch)
            && self.generation.load(Ordering::Acquire) == self.expected
    }
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Route {Mix,Independent,Drop}
#[derive(Default)]
struct Pending { epoch:u64,format:Option<(u32,u8)>,samples:VecDeque<i16>,remain:crate::audio_resample::PcmToStereo,ended:bool,failed:Option<Route> }
#[derive(Default)]
pub struct Input {
    lanes:[Mutex<Pending>;2],supported:AtomicU8,media_epoch:AtomicU64,wake:Notify,user_intent:Mutex<Option<std::time::Instant>>,
    shared_voice_wait:AtomicBool,
    pub(crate) buffered_owner: std::sync::OnceLock<tokio::sync::watch::Receiver<crate::buffered_ownership::Ownership>>,
    pub(crate) buffered_failed: watch::Sender<Option<StreamId>>,
}
fn index(kind:Kind)->usize {if kind==Kind::Speech {0}else{1}}
impl Input {
    pub(crate) fn clear_buffered_failure(&self) {
        self.buffered_failed.send_if_modified(|failure| failure.take().is_some());
    }
    pub fn init(&self,kind:Kind,rate:u32,channels:u8) {
        let mut pending=self.lanes[index(kind)].lock();
        let epoch=pending.epoch.wrapping_add(1);
        *pending=Pending{epoch,format:if (4000..=96000).contains(&rate) && (1..=2).contains(&channels) {Some((rate,channels))}else{None},..Default::default()};
        self.wake.notify_waiters();
    }
    pub fn push(&self,kind:Kind,rate:u32,channels:u8,data:&[u8])->Route {
        let mut pending=self.lanes[index(kind)].lock();
        if pending.ended || pending.format!=Some((rate,channels)) || data.len()%(usize::from(channels.max(1))*2)!=0 {return Route::Drop;}
        let route=self.route(kind,&pending);
        if route!=Route::Drop {
            let Pending{remain,samples,..}=&mut *pending;
            let reply_is_waiting = kind == Kind::Speech && route == Route::Independent &&
                (self.shared_voice_wait.load(Ordering::Acquire) || samples.len() > 48000);
            let cap = if reply_is_waiting { 480000 } else { 48000 };
            remain.push(data,rate,channels,48000,samples);
            let overflow=samples.len().saturating_sub(cap);
            if overflow>0 {samples.drain(..overflow);}
        }
        drop(pending);self.wake.notify_waiters();route
    }
    pub fn stop(&self,kind:Kind) {self.lanes[index(kind)].lock().ended=true;self.wake.notify_waiters();}
    pub fn independent(&self,kind:Kind)->bool {self.route(kind,&self.lanes[index(kind)].lock())==Route::Independent}
    fn route(&self,kind:Kind,pending:&Pending)->Route {
        if let Some(route)=pending.failed {return route;}
        if self.supported.load(Ordering::Acquire)&(1<<index(kind))!=0 {Route::Independent}else{Route::Mix}
    }
    fn fail(&self,kind:Kind,epoch:u64,route:Route)->Vec<i16> {
        let mut p=self.lanes[index(kind)].lock();
        if p.epoch!=epoch {return Vec::new();}
        p.failed=Some(route);p.remain.clear();
        let samples=p.samples.drain(..).collect();samples
    }
    fn retire_late(&self,kind:Kind,epoch:u64,frames:u64)->bool {
        let mut pending=self.lanes[index(kind)].lock();
        if pending.epoch!=epoch {return false;}
        let count=(frames as usize).saturating_mul(2).min(pending.samples.len());
        pending.samples.drain(..count);pending.remain.clear();true
    }
    fn user_initiated(&self)->bool {self.user_intent.lock().is_some_and(|at|at.elapsed()<Duration::from_secs(30))}
    pub fn media_restart(&self) {self.media_epoch.fetch_add(1,Ordering::AcqRel);self.wake.notify_waiters();}
    pub fn mark_user_intent(&self) {*self.user_intent.lock()=Some(std::time::Instant::now());}
    pub(crate) fn configure(&self,info:&Value,kinds:&[Kind]) {
        let mask=kinds.iter().copied().filter(|kind| crate::aux_setup::supports(info,*kind))
            .fold(0,|mask,kind| mask | 1<<index(kind));
        self.supported.store(mask,Ordering::Release);self.wake.notify_waiters();
    }
}

struct SharedVoiceWait<'a>(&'a AtomicBool);
impl<'a> SharedVoiceWait<'a> {
    fn start(input: &'a Input) -> Self {
        input.shared_voice_wait.store(true, Ordering::Release);
        Self(&input.shared_voice_wait)
    }
}
impl Drop for SharedVoiceWait<'_> {
    fn drop(&mut self) { self.0.store(false, Ordering::Release); }
}

pub struct Session {
    pub kinds: &'static [Kind],
    pub control:Arc<RtspChannel>,pub factory:Arc<dyn SocketProvider>,pub params:AirplayParams,
    pub shared:[u8;32],pub info:Value,pub resource:Arc<SpeechResource>,pub media_codec:Mutex<Option<AudioCodec>>,
    pub receiver_audio_generation: Arc<AtomicU64>,
    pub realtime_media_rebuild: bool,
}
pub struct Controller {
    session: Arc<Session>,
    stop:Arc<Stop>,tasks:Vec<tokio::task::JoinHandle<()>>,allocated:Arc<[AtomicBool;2]>,
    call_allocated: Arc<AtomicBool>,
}
#[derive(Default)]
struct MainAudioTransition {
    call_media_paused: bool,
    restored_unborrow_generation: u64,
}

#[derive(Default)]
struct Stop {
    requested:AtomicBool,
    wake:Notify,
    // R14G17 type100/102 SETUP and TEARDOWN share a receiver slot. Serialize
    // media swaps and temporary users of the slot until cleanup is acknowledged.
    main_transition: Arc<tokio::sync::Mutex<MainAudioTransition>>,
}
impl Stop {
    async fn wait(&self) {
        let wake=self.wake.notified();tokio::pin!(wake);wake.as_mut().enable();
        if !self.requested.load(Ordering::Acquire) {wake.await;}
    }
}
impl Controller {
    pub(crate) fn arm_calls(&mut self, commands: mpsc::UnboundedSender<MainCommand>) {
        let session = self.session.clone();
        let stop = self.stop.clone();
        let call_allocated = self.call_allocated.clone();
        let Some(format) = crate::call_audio::formats(&session.info).next().filter(|_| session.params.enable_audio) else {
            crate::emit_log("CALL-AUDIO: receiver has no compatible bidirectional telephony format");
            return;
        };
        self.tasks.push(crate::callback_context::spawn(async move {
            let mut offers = crate::call_audio::Offers::arm(format);
            let mut ownership = session.control.buffered_ownership.subscribe();
            'calls: while !stop.requested.load(Ordering::Acquire) && !session.control.is_closed() {
                let crate::call_audio::Offer { attempt, rate, socket: local } = tokio::select! {
                    biased;
                    _ = stop.wait() => break,
                    socket = offers.rx.recv() => match socket { Some(s) => s, None => break },
                };
                if local.set_nonblocking(true).is_err() { continue; }
                let Ok(local) = tokio::net::UnixStream::from_std(local) else { continue; };
                // A live call has priority over the optional assistant microphone lease.
                if let Some(probe) = &session.params.microphone_probe { probe.request(false); }
                let (transition, grant) = loop {
                    let transition = tokio::select! {
                        biased;
                        _ = stop.wait() => break 'calls,
                        _ = local.readable() => continue 'calls, // Before GO, readability means EOF or an invalid offer.
                        guard = stop.main_transition.lock() => guard,
                    };
                    let grant = *ownership.borrow_and_update();
                    if grant.owned { break (transition, grant.losses); }
                    drop(transition);
                    tokio::select! {
                        biased;
                        _ = stop.wait() => break 'calls,
                        _ = local.readable() => continue 'calls,
                        changed = ownership.changed() => if changed.is_err() { break 'calls; },
                    }
                };
                let mut cleanup = Cleanup::default();
                let mut call_id = None;
                let mut announced = false;
                let mut retryable = crate::call_audio::Retry::None;
                let result = match session.pause_main(&commands, &mut cleanup, false).await {
                    Ok(()) if stop.requested.load(Ordering::Acquire) => Ok(()),
                    Ok(()) => crate::call_audio::run(&session, rate, local, grant, stop.wait(), &mut call_id, &call_allocated, &mut announced, &mut retryable).await,
                    Err(error) => Err(error),
                };
                if let Some(id) = call_id {
                    session.control.udp_media.set(100, false);
                    let input = InputStream::Shared(InputFormat::Pcm16);
                    let mut closed = false;
                    for _ in 0..2 {
                        if mic_probe::close(&session.control, input, id).await.is_ok() { closed = true; break; }
                    }
                    if !closed {
                        crate::emit_log("CALL-AUDIO: telephony cleanup unconfirmed; close session before restoring media");
                        session.control.close();
                        break;
                    }
                    call_allocated.store(false, Ordering::Release);
                }
                if announced && !session.resource.set_phone_call(false).await && !session.resource.set_phone_call(false).await {
                    crate::emit_log("CALL-AUDIO: phone state cleanup unconfirmed; close session");
                    session.control.close();
                    break;
                }
                if cleanup.retire_media && session.retire_media().await.is_err() {
                    crate::emit_log("CALL-AUDIO: media teardown unconfirmed; close session");
                    session.control.close();
                    break;
                }
                if cleanup.restore {
                    if !session.restore_media_after_call(&commands, &mut ownership, &stop, transition).await {
                        crate::emit_log("CALL-AUDIO: media restore failed with a current grant; close session");
                        session.control.close();
                        break;
                    }
                } else {
                    drop(transition);
                }
                if !stop.requested.load(Ordering::Acquire) && !session.control.is_closed() && result.is_err() {
                    offers.request_retry(attempt, retryable);
                }
                match result {
                    Ok(()) => crate::emit_log("CALL-AUDIO: telephony ended"),
                    Err(error) => crate::emit_log(&format!("CALL-AUDIO: telephony ended: {error}")),
                }
            }
        }));
    }

    pub fn spawn(session:Session,input:Arc<Input>,commands:mpsc::UnboundedSender<MainCommand>,fallback:mpsc::UnboundedSender<crate::engine::SourceEvent>,shutdown:Arc<EngineShutdown>)->Self {
        input.configure(&session.info,session.kinds);
        let session=Arc::new(session);let stop=Arc::new(Stop::default());
        let allocated=Arc::new([AtomicBool::new(false),AtomicBool::new(false)]);
        let mut tasks: Vec<_> = session.kinds.iter().copied().map(|kind| {
            crate::callback_context::spawn(run_lane(kind,session.clone(),input.clone(),commands.clone(),fallback.clone(),stop.clone(),allocated.clone()))
        }).collect();
        if let Some((trigger, format)) = session.params.microphone_probe.clone().zip(mic_probe::shared_format(&session.info)) {
            let session = session.clone();
            let mix_input = input.clone();
            let commands = commands.clone();
            let stop = stop.clone();
            let allocated = allocated.clone();
            tasks.push(crate::callback_context::spawn(async move {
                session.run_shared_microphone(trigger, format, &mix_input, &commands, &stop, &allocated).await;
            }));
        }
        if *session.media_codec.lock() == Some(AudioCodec::BufferedAac) {
            let _ = input.buffered_owner.set(session.control.buffered_ownership.subscribe());
            let mut failures = input.buffered_failed.subscribe();
            let recovery = session.clone();
            let stopping = stop.clone();
            let commands = commands.clone();
            let engine_shutdown = shutdown.clone();
            tasks.push(crate::callback_context::spawn(async move {
                loop {
                    tokio::select! {
                        biased;
                        _ = engine_shutdown.wait() => return,
                        _ = stopping.wait() => return,
                        update = failures.changed() => if update.is_err() { return; },
                    }
                    let Some(failed) = *failures.borrow_and_update() else { continue; };
                    let transition = tokio::select! {
                        biased;
                        _ = engine_shutdown.wait() => return,
                        _ = stopping.wait() => return,
                        guard = stopping.main_transition.lock() => guard,
                    };
                    // Pause and restore can supersede this failure while recovery waits for the slot.
                    if transition.call_media_paused || *failures.borrow() != Some(failed) { continue; }
                    recovery.fallback_buffered(&commands, &stopping, &engine_shutdown).await;
                    return;
                }
            }));
        }
        if session.realtime_media_rebuild && matches!(*session.media_codec.lock(),
            Some(AudioCodec::Aac | AudioCodec::Aac441 | AudioCodec::BufferedAac)) {
            let recovery = session.clone();
            let stopping = stop.clone();
            let commands = commands.clone();
            tasks.push(crate::callback_context::spawn(async move {
                tokio::select! {
                    biased;
                    _ = shutdown.wait() => (),
                    _ = stopping.wait() => (),
                    _ = recovery.rebuild_on_unborrow(&commands, &shutdown, &stopping) => (),
                }
            }));
        }
        Self{session,stop,tasks,allocated,call_allocated:Arc::default()}
    }
    pub(crate) fn media_stream_type(&self) -> Option<i64> {
        self.session.media_codec.lock().map(AudioCodec::stream_type)
    }
    pub async fn shutdown(&mut self)->Vec<i64> {
        self.stop.requested.store(true,Ordering::Release);self.stop.wake.notify_waiters();
        // Both lanes clean up concurrently. Keep control alive until cleanup or its
        // bounded budget, then include unfinished stream types in final TEARDOWN.
        let wait=async {for task in &mut self.tasks {let _=task.await;}};
        if tokio::time::timeout(Duration::from_secs(4),wait).await.is_err() {
            for task in &self.tasks {task.abort();}
            crate::emit_log("AUX-AUDIO: cleanup budget exceeded; final session teardown includes pending streams");
        }
        let mut pending: Vec<_> = [Kind::Speech,Kind::Navigation].into_iter()
            .filter(|k|self.allocated[index(*k)].load(Ordering::Acquire)).map(Kind::stream_type).collect();
        if self.call_allocated.load(Ordering::Acquire) && !pending.contains(&100) { pending.push(100); }
        pending
    }
}
impl Drop for Controller {fn drop(&mut self) {self.stop.requested.store(true,Ordering::Release);self.stop.wake.notify_waiters();for task in &self.tasks {task.abort();}}}

#[derive(Clone)]
enum Lease {Speech(crate::speech_resource::SpeechLease),Navigation(crate::speech_resource::NavigationLease)}
impl Lease {
    fn valid(&self,session:&Session)->bool {match self {
        Self::Speech(lease)=>session.resource.speech_valid(lease),
        Self::Navigation(lease)=>session.resource.navigation_valid(lease),
    }}
    async fn finish(&self,session:&Session)->bool {match self {
        Self::Speech(lease)=>session.resource.end_speech(lease.clone()).await,
        Self::Navigation(lease)=>session.resource.end_navigation(lease.clone()).await,
    }}
}
struct Active { output: AudioOut, lease: Lease, restore_media: bool }
enum StartError {Refused(String),Failed(String),AwaitNavigationCleanup(String)}
impl Session {
    async fn rebuild_on_unborrow(&self, commands: &mpsc::UnboundedSender<MainCommand>, shutdown: &Arc<EngineShutdown>, stop: &Stop) {
        let unborrow = &self.control.audio_unborrow_generation;
        let mut handled = 0;
        loop {
            self.control.audio_unborrow_wake.notified().await;
            let transition = stop.main_transition.lock().await;
            let generation = unborrow.load(Ordering::Acquire);
            if generation == handled { continue; }
            handled = generation;
            if transition.call_media_paused || transition.restored_unborrow_generation == generation {
                continue;
            }
            let codec = *self.media_codec.lock();
            let Some(codec @ (AudioCodec::Aac | AudioCodec::Aac441)) = codec else { continue; };
            let grant = {
                let modes = self.resource.modes.lock();
                let Some(media_epoch) = modes.media_rebuild_epoch() else { continue; };
                MainAudioGrant {
                    modes: self.resource.modes.clone(),
                    generation: self.receiver_audio_generation.clone(),
                    expected: self.receiver_audio_generation.load(Ordering::Acquire),
                    media_epoch,
                    shutdown: shutdown.clone(),
                }
            };
            if self.control.is_closed() || shutdown.requested()
                || self.params.pipeline.lock().main_audio_stream_type != Some(102) {
                continue;
            }
            let rtsp = self.control.trace_id();
            crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} setup type=102 codec={codec:?}"));
            let Some(mut output) = crate::airplay::negotiate_realtime_audio(
                &self.control, &self.factory, Some(&self.shared), &self.params, false, &self.info, codec, true,
            ).await else {
                crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} setup unusable; retaining old sender"));
                continue;
            };
            let stream = output.pacing_id.map(|id| id.stream_id);
            if !grant.is_current(&grant.modes.lock()) { continue; }
            let record = crate::airplay::record_session(&self.control).await;
            if let Err(error) = record {
                crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} streamConnectionID={stream:?} RECORD failed: {error}; retaining old sender"));
                continue;
            }
            if !grant.is_current(&grant.modes.lock()) {
                crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} streamConnectionID={stream:?} RECORD confirmed after grant changed; retaining old sender"));
                continue;
            }
            output.set_ownership(self.resource.owned.clone());
            let (reply, installed) = oneshot::channel();
            if commands.send(MainCommand::ReplaceRealtime { output, grant, reply }).is_err() {
                return;
            }
            if installed.await.is_err() {
                crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} switch superseded"));
                continue;
            }
            crate::emit_log(&format!("AUDIO-REBUILD: RTSP#{rtsp} unborrow={generation} streamConnectionID={stream:?} RECORD confirmed; sender switched"));
        }
    }

    // The buffered stream owns only main media; independent audio keeps running.
    async fn fallback_buffered(&self, commands:&mpsc::UnboundedSender<MainCommand>, stop:&Stop, shutdown:&EngineShutdown) {
        let stopped = || stop.requested.load(Ordering::Acquire) || shutdown.requested() || self.control.is_closed();
        if stopped() { return; }
        self.params.pipeline.lock().audio_ready = false;
        let rtsp = self.control.trace_id();
        for attempt in 1..=2 {
            if stopped() { return; }
            match crate::airplay::retire_buffered_audio(&self.control).await {
                Ok(()) => {
                    crate::emit_log(&format!("BUFFERED-AUDIO: RTSP#{rtsp} type103 teardown confirmed attempt={attempt}"));
                    break;
                }
                Err(error) => {
                    let retry = attempt == 1 && !stopped();
                    crate::emit_log(&format!("BUFFERED-AUDIO: RTSP#{rtsp} type103 teardown unconfirmed attempt={attempt} retry={retry}: {error}"));
                    if !retry { return; }
                }
            }
        }
        *self.media_codec.lock() = None;
        if stopped() { return; }
        let desktop = crate::airplay::effective_desktop(&self.params, &self.info);
        for codec in crate::airplay::buffered_fallback_candidates(&self.params, &self.info).await {
            if stopped() { return; }
            let stream_type = codec.stream_type();
            // A stop can race the receiver allocating the stream before we read its reply.
            // Include that type in final TEARDOWN even if the recovery task is cancelled.
            *self.media_codec.lock() = Some(codec);
            if let Some(mut output) = crate::airplay::negotiate_audio(
                &self.control,&self.factory,Some(&self.shared),&self.params,desktop,&self.info,codec
            ).await {
                if stopped() { return; }
                output.set_ownership(self.resource.owned.clone());
                crate::emit_log(&format!("BUFFERED-AUDIO: type103 retired; type{stream_type} {codec:?} fallback ready"));
                self.params.pipeline.lock().audio_ready = commands.send(MainCommand::Install(Some(output))).is_ok();
                return;
            }
        }
        crate::emit_log("BUFFERED-AUDIO: no usable declared realtime fallback; video and independent audio continue");
    }
    async fn retire_media(&self)->Result<(),String> {
        let Some(codec)=*self.media_codec.lock() else {return Ok(());};
        let body=crate::bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(codec.stream_type()))])]))]));
        self.control.udp_media.set(codec.stream_type(), false);
        let reply=self.control.request("TEARDOWN","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(2)).await?;
        crate::resource_trace::validate_ack("AUX_MEDIA_PAUSE",&reply)
    }
    async fn pause_main(&self, commands: &mpsc::UnboundedSender<MainCommand>, cleanup: &mut Cleanup,
        hold_mixed_voice: bool) -> Result<(), String> {
        let (tx, mut rx) = oneshot::channel();
        if commands.send(MainCommand::Pause { reply: tx, hold_mixed_voice }).is_err() {
            return Err("media pump pause was not acknowledged".into());
        }
        let sender = match tokio::time::timeout(Duration::from_secs(1), &mut rx).await {
            Ok(Ok(sender)) => sender,
            Ok(Err(_)) => return Err("media pump pause was not acknowledged".into()),
            Err(_) => {
                rx.close();
                rx.try_recv().map_err(|_| "media pump pause timed out")?
            }
        };
        drop(sender);
        cleanup.restore = true;
        cleanup.retire_media = true;
        self.retire_media().await?;
        cleanup.retire_media = false;
        Ok(())
    }

    async fn run_shared_microphone(&self, trigger: Arc<ProbeControl>, format: InputFormat,
        mix_input: &Input, commands: &mpsc::UnboundedSender<MainCommand>, stop: &Stop, allocated: &[AtomicBool;2]) {
        const CAPTURE_START_BUDGET: Duration = Duration::from_secs(3);
        const SHARED_CLEANUP_ATTEMPTS: u32 = 3;
        let (_armed, mut rx) = trigger.arm();
        let input = InputStream::Shared(format);
        let learn_sender_port = crate::airplay::is_diplay_receiver(&self.info);
        crate::emit_log(&format!("MIC-PROBE: armed declared shared type100 {format:?}; waiting for an explicit capture request"));
        let mut cleanup = Cleanup::default();
        let mut voice_wait = None;
        let mut handled = 0;
        let mut attempts = 0;
        let mut retry_at = tokio::time::Instant::now();
        let mut media_epoch = mix_input.media_epoch.load(Ordering::Acquire);
        let mut awaiting_capture = None;
        loop {
            if stop.requested.load(Ordering::Acquire) || self.control.is_closed() { break; }
            let generation = *rx.borrow_and_update();
            let now = tokio::time::Instant::now();
            if generation & 1 != 0 && generation != handled {
                if awaiting_capture.is_none_or(|(pending, _)| pending != generation) {
                    awaiting_capture = Some((generation, now + CAPTURE_START_BUDGET));
                }
            } else {
                awaiting_capture = None;
            }
            let capture_deadline = awaiting_capture.map(|(_, deadline)| deadline);
            if capture_deadline.is_some_and(|deadline| deadline <= now) {
                handled = generation;
                trigger.finish(generation);
                crate::emit_log("MIC-PROBE: capture start timed out waiting for main audio transition");
                continue;
            }
            let mut media_wake = std::pin::pin!(mix_input.wake.notified());
            media_wake.as_mut().enable();
            if cleanup.pending() {
                let current_media = mix_input.media_epoch.load(Ordering::Acquire);
                if cleanup.shared_input.is_none() && current_media != media_epoch {
                    media_epoch = current_media;
                    attempts = 0;
                    retry_at = tokio::time::Instant::now();
                }
                let retry_limit = if cleanup.shared_input.is_some() { SHARED_CLEANUP_ATTEMPTS } else { 2 };
                if attempts < retry_limit && now >= retry_at {
                    cleanup.run(Kind::Speech, self, commands, allocated, false).await;
                    attempts += 1;
                    let delay = if cleanup.shared_input.is_some() && attempts > 1 { 300 } else { 100 };
                    retry_at = tokio::time::Instant::now() + Duration::from_millis(delay);
                }
                if cleanup.shared_input.is_some() && attempts >= SHARED_CLEANUP_ATTEMPTS {
                    // The receiver may still own the shared type100 slot. Exiting
                    // this session avoids colliding with it by reopening media.
                    crate::emit_log("MIC-PROBE: shared input cleanup unconfirmed; ending control for session recovery");
                    self.control.close();
                    break;
                }
                if cleanup.pending() {
                    let wake_at = (attempts < retry_limit).then_some(retry_at).into_iter().chain(capture_deadline).min();
                    tokio::select! {
                        biased;
                        _ = stop.wait() => break,
                        change = rx.changed() => if change.is_err() { break; },
                        _ = media_wake => {},
                        _ = async { match wake_at { Some(at) => tokio::time::sleep_until(at).await, None => std::future::pending().await } } => {},
                    }
                    continue;
                }
            }
            voice_wait.take();
            let Some(capture_deadline) = capture_deadline else {
                tokio::select! { biased; _ = stop.wait() => break, change = rx.changed() => if change.is_err() { break; } }
                continue;
            };
            let transition = tokio::select! {
                biased;
                _ = stop.wait() => break,
                change = rx.changed() => { if change.is_err() { break; } continue; },
                _ = tokio::time::sleep_until(capture_deadline) => continue,
                guard = stop.main_transition.clone().lock_owned() => guard,
            };
            let media_ready = self.media_codec.lock().is_none_or(|codec| {
                self.params.pipeline.lock().main_audio_stream_type == Some(codec.stream_type())
            });
            if transition.call_media_paused || !media_ready {
                drop(transition);
                // Install is asynchronous, so its pipeline publication can trail
                // release of the transition guard.
                tokio::select! {
                    biased;
                    _ = stop.wait() => break,
                    change = rx.changed() => if change.is_err() { break; },
                    _ = tokio::time::sleep_until(capture_deadline.min(tokio::time::Instant::now() + Duration::from_millis(25))) => {},
                }
                continue;
            }
            handled = generation;
            let result = async {
                if trigger.generation() != generation || stop.requested.load(Ordering::Acquire) || self.control.is_closed() {
                    return Err("capture superseded".into());
                }
                voice_wait = Some(SharedVoiceWait::start(mix_input));
                let lease = self.resource.begin_recognition().await.ok_or("recognition resource denied or superseded")?;
                cleanup.main_transition = Some(transition);
                cleanup.lease = Some(Lease::Speech(lease.clone()));
                let valid = || self.resource.speech_valid(&lease);
                if trigger.generation() != generation || stop.requested.load(Ordering::Acquire) || !valid() {
                    return Err("capture superseded after recognition grant".into());
                }
                self.pause_main(commands, &mut cleanup, true).await?;
                let capture = mic_probe::InputSession { control: &self.control, factory: self.factory.as_ref(),
                    shared: &self.shared, host: &self.params.car_ip, port: self.params.airplay_port };
                capture.sample(input, &mut rx, stop.wait(), &trigger, generation, Duration::from_secs(20), learn_sender_port, valid, |id| {
                    allocated[index(Kind::Speech)].store(id.is_some(), Ordering::Release);
                    cleanup.shared_input = id.map(|id| (input, id));
                }).await
            }.await;
            trigger.finish(generation);
            match result {
                Ok(stats) => crate::emit_log(&format!("MIC-PROBE: shared type100 stopped packets={} decoded_samples={}", stats.packets, stats.samples)),
                Err(error) => crate::emit_log(&format!("MIC-PROBE: shared type100 stopped: {error}")),
            }
            attempts = 0;
            retry_at = tokio::time::Instant::now();
        }
        if cleanup.pending() { cleanup.run(Kind::Speech, self, commands, allocated, true).await; }
    }

    async fn restore_media(&self,commands:&mpsc::UnboundedSender<MainCommand>)->bool {
        let Some(codec)=*self.media_codec.lock() else {let _=commands.send(MainCommand::Install(None));return true;};
        for attempt in 1..=2 {
            if attempt > 1 { tokio::time::sleep(Duration::from_millis(100)).await; }
            if let Some(output) = self.negotiate_media_restore(codec, attempt).await {
                return commands.send(MainCommand::Install(Some(output))).is_ok();
            }
        }
        crate::emit_log("AUX-AUDIO: media restore pending; keep pump paused and retry on a fresh media start");
        false
    }

    async fn negotiate_media_restore(&self, codec: AudioCodec, attempt: usize) -> Option<AudioOut> {
        let desktop = crate::airplay::effective_desktop(&self.params, &self.info);
        let mut output = crate::airplay::negotiate_audio(&self.control, &self.factory, Some(&self.shared), &self.params, desktop, &self.info, codec).await?;
        output.set_ownership(self.resource.owned.clone());
        crate::emit_log(&format!("AUX-AUDIO: media restored attempt={attempt} owner_phone={}",self.resource.owned.load(Ordering::Acquire)));
        Some(output)
    }

    async fn restore_media_after_call(&self, commands: &mpsc::UnboundedSender<MainCommand>,
        ownership: &mut tokio::sync::watch::Receiver<crate::buffered_ownership::Ownership>, stop: &Stop,
        mut transition: tokio::sync::MutexGuard<'_, MainAudioTransition>) -> bool {
        let stopping = || stop.requested.load(Ordering::Acquire) || self.control.is_closed();
        if stopping() { return true; }
        let Some(codec) = *self.media_codec.lock() else {
            let _ = commands.send(MainCommand::Install(None));
            return true;
        };
        // The call and previous sender are gone. Speech may borrow the slot while
        // the car owns it, with media restoration still owned by this task.
        transition.call_media_paused = true;
        drop(transition);
        'grants: loop {
            let losses = tokio::select! {
                biased;
                _ = stop.wait() => return true,
                _ = self.control.wait_for_control_close() => return true,
                grant = ownership.wait_for(|grant| grant.owned) => match grant {
                    Ok(grant) => grant.losses,
                    Err(_) => return true,
                },
            };
            let mut transition = tokio::select! {
                biased;
                _ = stop.wait() => return true,
                _ = self.control.wait_for_control_close() => return true,
                guard = stop.main_transition.lock() => guard,
            };
            for attempt in 1..=2 {
                if stopping() { return true; }
                let grant = *ownership.borrow();
                if !grant.owned || grant.losses != losses { continue 'grants; }
                let unborrow = self.control.audio_unborrow_generation.load(Ordering::Acquire);
                if let Some(output) = self.negotiate_media_restore(codec, attempt).await {
                    // The same receiver return may also have queued a realtime rebuild.
                    transition.restored_unborrow_generation = unborrow;
                    transition.call_media_paused = false;
                    return commands.send(MainCommand::Install(Some(output))).is_ok();
                }
                if stopping() { return true; }
                if ownership.borrow().losses != losses { continue 'grants; }
                if attempt == 1 {
                    tokio::select! {
                        biased;
                        _ = stop.wait() => return true,
                        _ = ownership.changed() => continue 'grants,
                        _ = tokio::time::sleep(Duration::from_millis(100)) => {},
                    }
                }
            }
            return false;
        }
    }
}
#[derive(Default)]
struct Cleanup {
    lease:Option<Lease>,close:bool,retire_media:bool,restore:bool,
    close_state:crate::aux_setup::CloseState,
    shared_input: Option<(InputStream, i64)>,
    main_transition: Option<tokio::sync::OwnedMutexGuard<MainAudioTransition>>,
}
impl Cleanup {
    fn pending(&self)->bool {self.lease.is_some()||self.close||self.retire_media||self.restore||self.shared_input.is_some()}
    async fn run(&mut self,kind:Kind,session:&Session,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],stopping:bool)->bool {
        if self.retire_media {
            if let Err(error)=session.retire_media().await {crate::emit_log(&format!("AUX-AUDIO: pending media teardown: {error}"));return false;}
            self.retire_media=false;
        }
        if self.close {
            if let Err(error)=crate::aux_setup::close(&session.control,kind,&mut self.close_state).await {crate::emit_log(&format!("AUX-AUDIO: {:?} pending stream cleanup: {error}",kind));return false;}
            self.close=false;allocated[index(kind)].store(false,Ordering::Release);
        }
        if let Some((input, id)) = self.shared_input {
            if let Err(error) = mic_probe::close(&session.control, input, id).await {
                crate::emit_log(&format!("MIC-PROBE: shared type100 cleanup pending: {error}"));
                return false;
            }
            self.shared_input = None;
            allocated[index(Kind::Speech)].store(false, Ordering::Release);
        }
        if let Some(lease)=self.lease.as_ref() {
            if !lease.finish(session).await {crate::emit_log(&format!("AUX-AUDIO: {:?} app/resource release pending",kind));return false;}
            self.lease=None;
        }
        if self.restore {
            if !stopping && !session.restore_media(commands).await {return false;}
            self.restore=false;
        }
        self.main_transition = None;
        true
    }
}
async fn start(kind:Kind,session:&Session,input:&Input,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],cleanup:&mut Cleanup,transition:Option<tokio::sync::OwnedMutexGuard<MainAudioTransition>>)->Result<Active,StartError> {
    crate::opus_encode::OpusEncoder::new(kind.rate(),kind.spf()).map_err(|e|StartError::Failed(e.to_string()))?;
    let lease=match kind {
        Kind::Speech=>Lease::Speech(session.resource.begin_speech(input.user_initiated()).await.ok_or_else(||StartError::Refused("speech resource denied or superseded".into()))?),
        Kind::Navigation=>Lease::Navigation(session.resource.begin_navigation().await.ok_or_else(||StartError::Refused("navigation app state denied or superseded".into()))?),
    };
    cleanup.lease=Some(lease.clone());
    let call_media_paused = transition.as_ref().is_some_and(|state| state.call_media_paused);
    cleanup.main_transition = transition;
    if kind==Kind::Speech && !call_media_paused {
        if let Err(error)=session.pause_main(commands, cleanup, false).await {
            if !cleanup.retire_media { cleanup.run(kind,session,commands,allocated,false).await; }
            return Err(StartError::Refused(format!("media teardown pending; no conflicting SETUP: {error}")));
        }
    }
    allocated[index(kind)].store(true,Ordering::Release);
    cleanup.close_state=crate::aux_setup::CloseState::default(); // A new stream lifecycle, not a cleanup retry.
    match crate::aux_setup::open(&session.control,&session.factory,&session.params,&session.shared,&session.info,kind).await {
        Ok(mut output)=>{
            if !lease.valid(session) {
                drop(output);cleanup.close=true;
                cleanup.run(kind,session,commands,allocated,false).await;
                return Err(StartError::Refused("resource changed during SETUP".into()));
            }
            if kind==Kind::Speech {output.set_ownership(session.resource.owned.clone());}
            // Ownership of cleanup moves to Active only after the stream is usable.
            cleanup.lease = None;
            let restore_media = std::mem::take(&mut cleanup.restore);
            Ok(Active { output, lease, restore_media })
        }
        Err(error)=>{
            cleanup.close=!error.cleanup_confirmed;
            cleanup.close_state=error.cleanup_state;
            if error.cleanup_confirmed {allocated[index(kind)].store(false,Ordering::Release);}
            let cleaned=cleanup.run(kind,session,commands,allocated,false).await;
            if cleaned {Err(StartError::Failed(error.to_string()))}
            else if kind == Kind::Navigation {
                Err(StartError::AwaitNavigationCleanup(error.to_string()))
            }
            else {Err(StartError::Refused(format!("cleanup/recovery pending: {error}")))}
        }
    }
}
async fn finish(kind:Kind,active:Active,session:&Session,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],cleanup:&mut Cleanup,stopping:bool) {
    drop(active.output);
    cleanup.lease=Some(active.lease);cleanup.close=true;cleanup.restore=active.restore_media;
    cleanup.run(kind,session,commands,allocated,stopping).await;
}

async fn run_lane(kind:Kind,session:Arc<Session>,input:Arc<Input>,commands:mpsc::UnboundedSender<MainCommand>,fallback:mpsc::UnboundedSender<crate::engine::SourceEvent>,stop:Arc<Stop>,allocated:Arc<[AtomicBool;2]>) {
    if !crate::aux_setup::supports(&session.info,kind) {return;}
    let mut active:Option<Active>=None;let mut handled=0;
    let mut fallback_after_cleanup = None;
    let mut cleanup=Cleanup::default();let mut cleanup_attempts=0;
    let mut cleanup_mark=tokio::time::Instant::now();
    let mut recovery_media_epoch=input.media_epoch.load(Ordering::Acquire);
    let mut recovery_source_epoch=0;
    let mut tick=tokio::time::interval(Duration::from_millis(20));tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut sync=tokio::time::interval(Duration::from_secs(1));sync.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_data=tokio::time::Instant::now();let mut last_log=last_data;
    let mut packets=0u64;let mut skipped=0u64;
    loop {
        let is_tick=tokio::select! {
            _=stop.wait()=>break,
            _=input.wake.notified()=>false,
            _=sync.tick()=>{if let Some(a)=active.as_mut() {let _=a.output.send_sync_now(crate::timing::monotonic_nanos()).await;} false},
            scheduled=tick.tick()=>{
                if let Some(a)=active.as_mut() {
                    let missed=(tokio::time::Instant::now().saturating_duration_since(scheduled).as_millis()/20) as u64*960;
                    if missed>0 {
                        if input.retire_late(kind,handled,missed) {
                            a.output.skip_source_frames(missed);skipped+=missed;
                        }
                    }
                }
                true
            },
        };
        if stop.requested.load(Ordering::Acquire) {break;}
        let(epoch,ended,queued,route)={let p=input.lanes[index(kind)].lock();(p.epoch,p.ended,p.samples.len(),input.route(kind,&p))};
        if cleanup.pending() {
            let media_epoch=input.media_epoch.load(Ordering::Acquire);
            let fresh=media_epoch!=recovery_media_epoch || epoch!=recovery_source_epoch;
            if fresh {cleanup_attempts=0;}
            if fresh || cleanup_attempts<2 && cleanup_mark.elapsed()>=Duration::from_secs(1) {
                cleanup.run(kind,&session,&commands,&allocated,false).await;
                cleanup_attempts+=1;cleanup_mark=tokio::time::Instant::now();
                recovery_media_epoch=media_epoch;recovery_source_epoch=epoch;
            }
            if cleanup.pending() {
                if cleanup_attempts >= 2 && cleanup.main_transition.as_ref().is_some_and(|state| state.call_media_paused) {
                    crate::emit_log("CALL-AUDIO: speech cleanup blocked media restoration; close session");
                    session.control.close();
                    break;
                }
                continue;
            }
            if let Some(epoch) = fallback_after_cleanup.take() {
                fallback_to_mix(&input, kind, epoch, &fallback);
                continue;
            }
        }
        if let Some(current)=active.as_ref() {
            let stale=epoch!=handled || !current.lease.valid(&session);
            let drained=ended && queued==0;
            let idle=queued==0 && last_data.elapsed()>Duration::from_secs(5);
            if stale || drained || idle {
                finish(kind,active.take().unwrap(),&session,&commands,&allocated,&mut cleanup,false).await;
                if stale && epoch==handled {input.fail(kind,epoch,Route::Drop);}
                if idle {input.fail(kind,epoch,Route::Mix);}
                continue;
            }
        }
        if active.is_none() && route==Route::Independent && queued>0 && handled!=epoch {
            let transition = if kind == Kind::Speech {
                // An assistant reply can arrive while its microphone stream is
                // releasing the main slot. Keep the bounded reply queue intact.
                let Ok(guard) = stop.main_transition.clone().try_lock_owned() else { continue; };
                Some(guard)
            } else { None };
            handled=epoch;
            match start(kind,&session,&input,&commands,&allocated,&mut cleanup,transition).await {
                Ok(output)=>{
                    active=Some(output);tick.reset();sync.reset();last_data=tokio::time::Instant::now();
                    crate::emit_log(&format!("AUX-AUDIO: {:?} independent type={} OPUS {}Hz mono SPF{} ready; receiver audibility unconfirmed",kind,kind.stream_type(),kind.rate(),kind.spf()));
                }
                Err(error)=>{
                    let (message,route)=match error {
                        StartError::AwaitNavigationCleanup(e) => {
                            fallback_after_cleanup = Some(epoch);
                            crate::emit_log(&format!("AUX-AUDIO: navigation SETUP failed: {e}; waiting for cleanup before mixing"));
                            continue;
                        }
                        StartError::Refused(e)=>{input.fail(kind,epoch,Route::Drop);(e,Route::Drop)}
                        StartError::Failed(e)=>{fallback_to_mix(&input,kind,epoch,&fallback);(e,Route::Mix)}
                    };
                    crate::emit_log(&format!("AUX-AUDIO: {:?} start failed: {message}; route={route:?}",kind));
                }
            }
            continue;
        }
        if is_tick {
            if let Some(a)=active.as_mut() {
                // A new INIT can supersede an awaited SETUP. Never send its queued
                // samples with the preceding resource/stream generation.
                let mut frame={let mut p=input.lanes[index(kind)].lock();
                    if p.epoch!=handled {continue;}
                    let count=p.samples.len().min(1920);p.samples.drain(..count).collect::<Vec<_>>()};
                if !frame.is_empty() {last_data=tokio::time::Instant::now();}
                frame.resize(1920,0); // Pad the final 20ms packet, including short replies.
                if let Err(error)=a.output.send_pcm(&frame).await {
                    crate::emit_log(&format!("AUX-AUDIO: {:?} send failed: {error}",kind));
                    input.fail(kind,handled,Route::Drop);
                    finish(kind,active.take().unwrap(),&session,&commands,&allocated,&mut cleanup,false).await;
                    continue;
                }
                packets=a.output.packets_sent();
            }
        }
        if last_log.elapsed()>=Duration::from_secs(5) {
            crate::emit_log(&format!("AUX-AUDIO: {:?} active={} packets={packets} queuedFrames={} skippedSourceFrames={skipped}; no content",kind,active.is_some(),queued/2));
            last_log=tokio::time::Instant::now();
        }
    }
    if let Some(current)=active {finish(kind,current,&session,&commands,&allocated,&mut cleanup,true).await;}
    if cleanup.pending() {cleanup.run(kind,&session,&commands,&allocated,true).await;}
}

fn fallback_to_mix(input: &Input, kind: Kind, epoch: u64, fallback: &mpsc::UnboundedSender<crate::engine::SourceEvent>) {
    let samples = input.fail(kind, epoch, Route::Mix);
    if !samples.is_empty() {
        let _ = fallback.send(crate::engine::SourceEvent::AuxFallback { kind, samples });
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    fn capabilities()->Value {Value::dict(vec![("audioFormats",Value::array(vec![
        Value::dict(vec![("type",Value::int(100)),("audioType",Value::str("speechRecognition")),("audioOutputFormats",Value::int(1<<29))]),
        Value::dict(vec![("type",Value::int(101)),("audioType",Value::str("default")),("audioOutputFormats",Value::int(1<<30))]),
    ]))])}
    #[test]
    fn independent_input_is_bounded_and_stop_keeps_only_its_tail() {
        let input=Input::default();input.configure(&capabilities(), &[Kind::Speech,Kind::Navigation]);
        input.init(Kind::Speech,16000,1);input.init(Kind::Navigation,48000,2);
        assert_eq!(input.push(Kind::Speech,16000,1,&1500i16.to_le_bytes().repeat(160)),Route::Independent);
        assert!(input.independent(Kind::Speech));
        let before=input.lanes[0].lock().samples.len();assert!(before>0);
        input.stop(Kind::Speech);
        assert_eq!(input.lanes[0].lock().samples.len(),before);
        assert_eq!(input.push(Kind::Speech,16000,1,&[1,0]),Route::Drop);
        assert_eq!(input.push(Kind::Navigation,48000,2,&[1;400000]),Route::Independent);
        assert_eq!(input.lanes[1].lock().samples.len(),48000);
        input.init(Kind::Speech,16000,1);assert!(input.lanes[0].lock().samples.is_empty());
    }
    #[test]
    fn old_stream_lateness_cannot_discard_a_new_reply_generation() {
        let input=Input::default();input.configure(&capabilities(), &[Kind::Speech,Kind::Navigation]);
        input.init(Kind::Speech,16000,1);
        let old=input.lanes[0].lock().epoch;
        input.push(Kind::Speech,16000,1,&[1;640]);
        input.init(Kind::Speech,16000,1);
        input.push(Kind::Speech,16000,1,&[2;640]);
        let next=input.lanes[0].lock().epoch;
        let samples=input.lanes[0].lock().samples.clone();
        assert!(!input.retire_late(Kind::Speech,old,9600));
        assert_eq!(input.lanes[0].lock().samples,samples);
        assert!(input.retire_late(Kind::Speech,next,960));
        assert!(input.lanes[0].lock().samples.len()<samples.len());
    }
    #[test]
    fn unsupported_lanes_keep_the_existing_mixer_route() {
        let input=Input::default();input.configure(&Value::dict(vec![]), &[Kind::Speech,Kind::Navigation]);input.init(Kind::Speech,16000,1);
        assert_eq!(input.push(Kind::Speech,16000,1,&[1,0]),Route::Mix);
        assert!(!input.independent(Kind::Speech));
    }
}

#[cfg(test)]
#[path="aux_audio_tests.rs"]
mod integration_tests;
