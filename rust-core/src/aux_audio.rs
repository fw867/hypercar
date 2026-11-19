//! Independent BMW speech and navigation audio stream lifecycle.
use std::{collections::VecDeque,sync::{Arc,atomic::{AtomicBool,AtomicU8,AtomicU64,Ordering}},time::Duration};
use parking_lot::Mutex;
use tokio::sync::{mpsc,oneshot,Notify};
use crate::{airplay::{AirplayParams,AudioCodec,SocketProvider},audio_out::AudioOut,aux_setup::Kind,bplist::Value,rtsp::RtspChannel,speech_resource::SpeechResource};

pub enum MainCommand { Pause(oneshot::Sender<()>), Install(Option<AudioOut>) }
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Route {Mix,Independent,Drop}
#[derive(Default)]
struct Pending { epoch:u64,format:Option<(u32,u8)>,samples:VecDeque<i16>,remain:crate::audio_resample::PcmToStereo,ended:bool,failed:Option<Route> }
#[derive(Default)]
pub struct Input {
    lanes:[Mutex<Pending>;2],supported:AtomicU8,media_epoch:AtomicU64,wake:Notify,user_intent:Mutex<Option<std::time::Instant>>,
    pub(crate) buffered_owner: std::sync::OnceLock<tokio::sync::watch::Receiver<crate::buffered_ownership::Ownership>>,
    pub(crate) buffered_failed: Notify,
}
fn index(kind:Kind)->usize {if kind==Kind::Speech {0}else{1}}
impl Input {
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
            remain.push(data,rate,channels,48000,samples);
            let overflow=samples.len().saturating_sub(48000);
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

pub struct Session {
    pub kinds: &'static [Kind],
    pub control:Arc<RtspChannel>,pub factory:Arc<dyn SocketProvider>,pub params:AirplayParams,
    pub shared:[u8;32],pub info:Value,pub resource:Arc<SpeechResource>,pub media_codec:Mutex<Option<AudioCodec>>,
}
pub struct Controller {
    session: Arc<Session>,
    stop:Arc<Stop>,tasks:Vec<tokio::task::JoinHandle<()>>,allocated:Arc<[AtomicBool;2]>,
}
#[derive(Default)]
struct Stop { requested:AtomicBool,wake:Notify }
impl Stop {
    async fn wait(&self) {
        let wake=self.wake.notified();tokio::pin!(wake);wake.as_mut().enable();
        if !self.requested.load(Ordering::Acquire) {wake.await;}
    }
}
impl Controller {
    pub fn spawn(session:Session,input:Arc<Input>,commands:mpsc::UnboundedSender<MainCommand>,fallback:mpsc::UnboundedSender<crate::engine::SourceEvent>)->Self {
        input.configure(&session.info,session.kinds);
        let session=Arc::new(session);let stop=Arc::new(Stop::default());
        let allocated=Arc::new([AtomicBool::new(false),AtomicBool::new(false)]);
        let mut tasks: Vec<_> = session.kinds.iter().copied().map(|kind| {
            crate::callback_context::spawn(run_lane(kind,session.clone(),input.clone(),commands.clone(),fallback.clone(),stop.clone(),allocated.clone()))
        }).collect();
        if *session.media_codec.lock() == Some(AudioCodec::BufferedAac) {
            let _ = input.buffered_owner.set(session.control.buffered_ownership.subscribe());
            let recovery = session.clone();
            let stopping = stop.clone();
            tasks.push(crate::callback_context::spawn(async move {
                tokio::select! {
                    biased;
                    _ = stopping.wait() => (),
                    _ = input.buffered_failed.notified() => recovery.fallback_buffered(&commands, &stopping).await,
                }
            }));
        }
        Self{session,stop,tasks,allocated}
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
        [Kind::Speech,Kind::Navigation].into_iter().filter(|k|self.allocated[index(*k)].load(Ordering::Acquire)).map(Kind::stream_type).collect()
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
struct Active { output:AudioOut,lease:Lease }
enum StartError {Refused(String),Failed(String),AwaitNavigationCleanup(String)}
impl Session {
    // The ID9 buffered experiment owns only main media. Let the audio pump keep
    // draining and servicing the independent navigation lane while RTSP waits.
    async fn fallback_buffered(&self, commands:&mpsc::UnboundedSender<MainCommand>, stop:&Stop) {
        self.params.pipeline.lock().audio_ready = false;
        if let Err(error) = crate::airplay::retire_buffered_audio(&self.control).await {
            crate::emit_log(&format!("BUFFERED-AUDIO: type103 teardown unconfirmed: {error}; type102 deferred"));
            return;
        }
        *self.media_codec.lock() = None;
        for codec in [AudioCodec::Aac, AudioCodec::Aac441] {
            if stop.requested.load(Ordering::Acquire) {return;}
            if !crate::airplay::declares_media(&self.info,codec) {continue;}
            // A stop can race the receiver allocating 102 before we read its reply.
            // Include that type in final TEARDOWN even if the recovery task is cancelled.
            *self.media_codec.lock() = Some(codec);
            if let Some(mut output) = crate::airplay::negotiate_audio(
                &self.control,&self.factory,Some(&self.shared),&self.params,false,&self.info,codec
            ).await {
                output.set_ownership(self.resource.owned.clone());
                crate::emit_log("BUFFERED-AUDIO: type103 retired; type102 fallback ready");
                self.params.pipeline.lock().audio_ready = commands.send(MainCommand::Install(Some(output))).is_ok();
                return;
            }
        }
        crate::emit_log("BUFFERED-AUDIO: no usable type102 fallback; video and independent audio continue");
    }
    async fn retire_media(&self)->Result<(),String> {
        let Some(codec)=*self.media_codec.lock() else {return Ok(());};
        let body=crate::bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![("type",Value::int(codec.stream_type()))])]))]));
        self.control.udp_media.set(codec.stream_type(), false);
        let reply=self.control.request("TEARDOWN","/",&body,"application/x-apple-binary-plist",&[],Duration::from_secs(2)).await?;
        crate::resource_trace::validate_ack("AUX_MEDIA_PAUSE",&reply)
    }
    async fn restore_media(&self,commands:&mpsc::UnboundedSender<MainCommand>)->bool {
        let Some(codec)=*self.media_codec.lock() else {let _=commands.send(MainCommand::Install(None));return true;};
        for attempt in 0..2 {
            if attempt>0 {tokio::time::sleep(Duration::from_millis(100)).await;}
            let output=crate::airplay::negotiate_audio(&self.control,&self.factory,Some(&self.shared),&self.params,self.params.desktop_receiver,&self.info,codec).await;
            if let Some(mut output)=output {
                output.set_ownership(self.resource.owned.clone());
                crate::emit_log(&format!("AUX-AUDIO: media restored attempt={} owner_phone={}",attempt+1,self.resource.owned.load(Ordering::Acquire)));
                return commands.send(MainCommand::Install(Some(output))).is_ok();
            }
        }
        crate::emit_log("AUX-AUDIO: media restore pending; keep pump paused and retry on a fresh media start");
        false
    }
}
#[derive(Default)]
struct Cleanup {lease:Option<Lease>,close:bool,retire_media:bool,restore:bool}
impl Cleanup {
    fn pending(&self)->bool {self.lease.is_some()||self.close||self.retire_media||self.restore}
    async fn run(&mut self,kind:Kind,session:&Session,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],stopping:bool)->bool {
        if self.retire_media {
            if let Err(error)=session.retire_media().await {crate::emit_log(&format!("AUX-AUDIO: pending media teardown: {error}"));return false;}
            self.retire_media=false;
        }
        if self.close {
            if let Err(error)=crate::aux_setup::close(&session.control,kind).await {crate::emit_log(&format!("AUX-AUDIO: {:?} pending stream cleanup: {error}",kind));return false;}
            self.close=false;allocated[index(kind)].store(false,Ordering::Release);
        }
        if let Some(lease)=self.lease.as_ref() {
            if !lease.finish(session).await {crate::emit_log(&format!("AUX-AUDIO: {:?} app/resource release pending",kind));return false;}
            self.lease=None;
        }
        if self.restore {
            if !stopping && !session.restore_media(commands).await {return false;}
            self.restore=false;
        }
        true
    }
}
async fn start(kind:Kind,session:&Session,input:&Input,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],cleanup:&mut Cleanup)->Result<Active,StartError> {
    crate::opus_encode::OpusEncoder::new(kind.rate(),kind.spf()).map_err(|e|StartError::Failed(e.to_string()))?;
    let lease=match kind {
        Kind::Speech=>Lease::Speech(session.resource.begin_speech(input.user_initiated()).await.ok_or_else(||StartError::Refused("speech resource denied or superseded".into()))?),
        Kind::Navigation=>Lease::Navigation(session.resource.begin_navigation().await.ok_or_else(||StartError::Refused("navigation app state denied or superseded".into()))?),
    };
    cleanup.lease=Some(lease.clone());
    if kind==Kind::Speech {
        let(tx,rx)=oneshot::channel();
        if commands.send(MainCommand::Pause(tx)).is_err() || !matches!(tokio::time::timeout(Duration::from_secs(1),rx).await,Ok(Ok(()))) {
            cleanup.run(kind,session,commands,allocated,false).await;
            return Err(StartError::Refused("media pump pause was not acknowledged; cancelled late commands are ignored".into()));
        }
        cleanup.restore=true;cleanup.retire_media=true;
        if let Err(error)=session.retire_media().await {
            return Err(StartError::Refused(format!("media teardown pending; no conflicting SETUP: {error}")));
        }
        cleanup.retire_media=false;
    }
    allocated[index(kind)].store(true,Ordering::Release);
    match crate::aux_setup::open(&session.control,&session.factory,&session.params,&session.shared,&session.info,kind).await {
        Ok(mut output)=>{
            if !lease.valid(session) {
                drop(output);cleanup.close=true;
                cleanup.run(kind,session,commands,allocated,false).await;
                return Err(StartError::Refused("resource changed during SETUP".into()));
            }
            if kind==Kind::Speech {output.set_ownership(session.resource.owned.clone());}
            // Ownership of cleanup moves to Active only after the stream is usable.
            cleanup.lease=None;cleanup.restore=false;
            Ok(Active{output,lease})
        }
        Err(error)=>{
            cleanup.close=!error.cleanup_confirmed;
            if error.cleanup_confirmed {allocated[index(kind)].store(false,Ordering::Release);}
            let cleaned=cleanup.run(kind,session,commands,allocated,false).await;
            if cleaned {Err(StartError::Failed(error.to_string()))}
            else if kind == Kind::Navigation && session.params.head_unit_profile == "bmw_id9" {
                Err(StartError::AwaitNavigationCleanup(error.to_string()))
            }
            else {Err(StartError::Refused(format!("cleanup/recovery pending: {error}")))}
        }
    }
}
async fn finish(kind:Kind,active:Active,session:&Session,commands:&mpsc::UnboundedSender<MainCommand>,allocated:&[AtomicBool;2],cleanup:&mut Cleanup,stopping:bool) {
    drop(active.output);
    cleanup.lease=Some(active.lease);cleanup.close=true;cleanup.restore=kind==Kind::Speech;
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
            if cleanup.pending() {continue;}
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
            handled=epoch;
            match start(kind,&session,&input,&commands,&allocated,&mut cleanup).await {
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
