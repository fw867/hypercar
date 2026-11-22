//! Source lifetime and bounded pre-roll are independent of AirPlay engine lifetime.
use std::{collections::VecDeque, sync::Arc, time::{Duration, Instant}};
use parking_lot::Mutex;
use once_cell::sync::Lazy;
use tokio::sync::mpsc;
use crate::{engine::{CarLifeEvent, EngineConfig}, carlife::{CarLifeConfig, CarLifeHub, DialStop}, video_format::{FrameOrigin, VideoCodec}};

const MAX_GOP_BYTES: usize = 4 * 1024 * 1024;
const MAX_GOP_FRAMES: usize = 180;
const MAX_GOP_AGE: Duration = Duration::from_secs(2);

#[derive(Default)]
struct Feed {
    target: Option<mpsc::UnboundedSender<(CarLifeEvent, FrameOrigin)>>,
    parameters: Vec<(u8, CarLifeEvent)>,
    gop: VecDeque<CarLifeEvent>, bytes: usize, since: Option<Instant>,
    codec: Option<VideoCodec>, size: Option<(u32,u32)>,
    formats: [Option<CarLifeEvent>;3], handshake: bool,
    media_rate: tokio::sync::watch::Sender<Option<u32>>,
    #[cfg(test)]
    frames: u64,
}
impl Feed {
    fn clear_video(&mut self) {self.gop.clear(); self.bytes=0; self.since=None;}
    fn receive(&mut self, event: CarLifeEvent) {
        match &event {
            CarLifeEvent::SourceReset { cache_cleared } => {
                self.clear_video(); self.parameters.clear(); self.codec=None;
                self.size=None; self.formats=[None,None,None]; self.handshake=false;
                self.media_rate.send_replace(None);
                if let Some(cleared) = cache_cleared { cleared.notify_one(); }
            }
            CarLifeEvent::Handshake => self.handshake=true,
            CarLifeEvent::EncoderResolution{width,height} => {
                if self.size.is_some_and(|old|old!=(*width,*height)) {
                    self.clear_video(); self.parameters.clear();
                }
                self.size=Some((*width,*height));
            }
            CarLifeEvent::AudioInit{tts,rate,..} => {
                self.formats[*tts as usize]=Some(event.clone());
                if !tts { self.media_rate.send_replace(Some(*rate)); }
            }
            CarLifeEvent::VoiceAudioInit{..} => self.formats[2]=Some(event.clone()),
            CarLifeEvent::AudioStop{tts} => self.formats[*tts as usize]=None,
            CarLifeEvent::VoiceAudioStop => self.formats[2]=None,
            CarLifeEvent::ClientLeft{video:true} => {self.clear_video(); self.handshake=false;}
            _=>{}
        }
        let frame=match &event {
            CarLifeEvent::VideoFrame{pts_us,data}=>Some((*pts_us,data,VideoCodec::H264)),
            CarLifeEvent::VideoFrameTagged{pts_us,data,codec}=>Some((*pts_us,data,*codec)), _=>None,
        };
        if let Some((pts_us,data,codec))=frame {
            #[cfg(test)] {self.frames+=1;}
            if self.codec!=Some(codec) {self.clear_video();self.parameters.clear();self.codec=Some(codec);}
            let mut idr=false;
            for nal in crate::screen::annexb_nals(data) {
                let kind=match codec {VideoCodec::H264=>nal[0]&31, VideoCodec::Hevc=>crate::hevc::nal_type(nal).unwrap_or(255)};
                idr |= match codec {VideoCodec::H264=>kind==5, VideoCodec::Hevc=>matches!(kind,19..=21)};
                let parameter=match codec {VideoCodec::H264=>matches!(kind,7|8), VideoCodec::Hevc=>matches!(kind,32..=34)};
                if parameter && nal.len()<=16*1024 {
                    let mut bytes=vec![0,0,0,1];bytes.extend_from_slice(nal);
                    let config=match codec {VideoCodec::H264=>CarLifeEvent::VideoFrame{pts_us,data:bytes},
                        VideoCodec::Hevc=>CarLifeEvent::VideoFrameTagged{pts_us,data:bytes,codec}};
                    let data_of=|event:&CarLifeEvent| match event {
                        CarLifeEvent::VideoFrame{data,..}|CarLifeEvent::VideoFrameTagged{data,..}=>data.clone(),_=>Vec::new()
                    };
                    if self.parameters.iter().any(|(old,event)|*old==kind && data_of(event)!=data_of(&config)) {self.clear_video();}
                    self.parameters.retain(|(old,_)|*old!=kind); self.parameters.push((kind,config));
                }
            }
            if idr {self.clear_video();self.since=Some(Instant::now());}
            if self.since.is_some() {
                self.bytes=self.bytes.saturating_add(data.len());
                self.gop.push_back(event.clone());
                if self.bytes>MAX_GOP_BYTES || self.gop.len()>MAX_GOP_FRAMES || self.since.unwrap().elapsed()>MAX_GOP_AGE {
                    self.clear_video();
                }
            }
        }
        // Always drain the source. With no engine, PCM/video is never queued unboundedly.
        if self.target.as_ref().is_some_and(|tx|tx.send((event, FrameOrigin::Live)).is_err()) {self.target=None;}
    }
    fn attach(&mut self)->mpsc::UnboundedReceiver<(CarLifeEvent, FrameOrigin)> {
        let (tx,rx)=mpsc::unbounded_channel();
        let _=tx.send((CarLifeEvent::SourceReset { cache_cleared: None }, FrameOrigin::Live));
        if self.handshake {let _=tx.send((CarLifeEvent::Handshake, FrameOrigin::Live));}
        for format in self.formats.iter().flatten() {let _=tx.send((format.clone(), FrameOrigin::Cached));}
        self.target=Some(tx); self.replay(); rx
    }
    fn replay(&mut self) {
        if self.since.is_some_and(|at|at.elapsed()>MAX_GOP_AGE) {self.clear_video();}
        let Some(tx)=&self.target else {return;};
        let _=tx.send((CarLifeEvent::VideoReplay, FrameOrigin::Cached));
        if let Some((width,height))=self.size {let _=tx.send((CarLifeEvent::EncoderResolution{width,height}, FrameOrigin::Cached));}
        for (_,parameter) in &self.parameters {let _=tx.send((parameter.clone(), FrameOrigin::Cached));}
        for frame in &self.gop {let _=tx.send((frame.clone(), FrameOrigin::Cached));}
    }
}

struct Prepared {
    owner: u64, hub: Arc<CarLifeHub>, feed: Arc<Mutex<Feed>>,
    display: crate::carlife::DisplayRequest, microphone: Option<Arc<crate::mic_probe::ProbeControl>>,
    stop: Arc<DialStop>, task: tokio::task::JoinHandle<()>,
}
impl Drop for Prepared {
    fn drop(&mut self) {self.stop.cancel(); self.task.abort(); if let Some(mic)=&self.microphone {mic.request(false);}}
}
#[derive(Default)]
struct Slot {newest:u64, current:Option<Prepared>}
static SOURCE: Lazy<Mutex<Slot>>=Lazy::new(||Mutex::new(Slot::default()));

pub(crate) fn prepare(owner:u64,cfg:&EngineConfig)->Result<(),String> {
    let mut slot=SOURCE.lock();
    if slot.current.as_ref().is_some_and(|s|s.owner==owner) {return Ok(());}
    if owner==0 || owner<=slot.newest {return Err("stale CarWith source owner".into());}
    slot.current=None;slot.newest=owner;
    let stop=Arc::new(DialStop::default());
    // A missing declaration uses the prewarm fallback for INIT only.
    let display=crate::carlife::DisplayRequest::new(if cfg.carwith_cached_canvas { (cfg.width,cfg.height) } else { (0,0) });
    let microphone=cfg.carwith_microphone.then(||Arc::new(crate::mic_probe::ProbeControl::for_carwith()));
    let config=CarLifeConfig{width:cfg.width,height:cfg.height,fps:cfg.carwith_fps(),bt_address:cfg.device_id.clone(),
        bt_name:"HyperCar".into(),navigation_updates:cfg.carwith_navigation_enabled,microphone:microphone.clone()};
    let (hub,mut events)=CarLifeHub::connect(config,"127.0.0.1".into(),stop.clone(),Some(display.clone()),
        move |startup| crate::reopen_carwith_entry(owner, startup))?;
    let feed=Arc::new(Mutex::new(Feed::default()));let sink=feed.clone();
    let task=tokio::spawn(async move {while let Some(event)=events.recv().await {sink.lock().receive(event);}});
    slot.current=Some(Prepared{owner,hub:Arc::new(hub),feed,display,microphone,stop,task});
    drop(slot);
    crate::emit_log(&format!("CARWITH-SOURCE: owner={owner} seven-channel service prepared independently of AirPlay"));
    Ok(())
}
pub(crate) struct Attachment {
    pub media_rate:tokio::sync::watch::Receiver<Option<u32>>,
    pub hub:Arc<CarLifeHub>, pub events:mpsc::UnboundedReceiver<(CarLifeEvent, FrameOrigin)>,
    pub display:crate::carlife::DisplayRequest, pub microphone:Option<Arc<crate::mic_probe::ProbeControl>>,
}
pub(crate) struct InputLease(pub Arc<CarLifeHub>);
impl Drop for InputLease {fn drop(&mut self) {self.0.release_touch();}}
pub(crate) fn attach(owner:u64)->Result<Attachment,String> {
    let slot=SOURCE.lock();let source=slot.current.as_ref().filter(|s|s.owner==owner).ok_or("CarWith source not prepared for this attempt")?;
    let media_rate=source.feed.lock().media_rate.subscribe();
    let events=source.feed.lock().attach(); source.hub.request_keyframe();
    crate::emit_log(&format!("CARWITH-SOURCE: owner={owner} media attached; cached GOP then live events"));
    Ok(Attachment{media_rate,hub:source.hub.clone(),events,display:source.display.clone(),microphone:source.microphone.clone()})
}
pub(crate) fn stop(owner:u64) {
    let mut slot=SOURCE.lock();
    // A tap may retire this attempt before its off-thread prepare reaches Rust.
    slot.newest = slot.newest.max(owner);
    if slot.current.as_ref().is_some_and(|s|s.owner==owner) {slot.current=None;}
}
pub(crate) fn diagnostic_phase(owner: u64) -> Option<Arc<crate::carlife::DiagnosticPhase>> {
    SOURCE.lock().current.as_ref().filter(|source| source.owner == owner)
        .map(|source| source.stop.diagnostic_phase())
}
pub(crate) fn replay(owner:u64) {
    let slot=SOURCE.lock();
    if let Some(source)=slot.current.as_ref().filter(|s|s.owner==owner) {
        source.feed.lock().replay(); source.hub.request_keyframe();
        crate::emit_log(&format!("CARWITH-SOURCE: owner={owner} screen ready; replay current bounded GOP"));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::Notify;
    fn video(kind: u8) -> CarLifeEvent {
        CarLifeEvent::VideoFrame { pts_us: 1, data: vec![0,0,0,1,kind,8] }
    }

    #[test]
    fn only_current_source_media_init_controls_the_negotiation_clock() {
        let mut feed=Feed::default();
        let rate=feed.media_rate.subscribe();
        feed.receive(CarLifeEvent::AudioInit{tts:false,rate:44100,channels:2});
        feed.receive(CarLifeEvent::VoiceAudioInit{rate:16000,channels:1});
        feed.receive(CarLifeEvent::AudioInit{tts:true,rate:24000,channels:1});
        feed.receive(CarLifeEvent::AudioStop{tts:false});
        assert_eq!(*rate.borrow(),Some(44100));
        feed.attach();
        assert_eq!(*feed.media_rate.subscribe().borrow(),Some(44100));
        feed.receive(CarLifeEvent::SourceReset{cache_cleared:None});
        assert_eq!(*rate.borrow(),None);
        feed.receive(CarLifeEvent::AudioInit{tts:false,rate:48000,channels:2});
        assert_eq!(*rate.borrow(),Some(48000));
    }
    #[tokio::test]
    async fn pause_before_prepare_does_not_revive_retired_attempt() {
        let config: EngineConfig = serde_json::from_value(serde_json::json!({
            "device_id":"02:00:00:00:00:01","car_ip":"127.0.0.1","airplay_port":0,
            "width":2880,"height":960,"fps":30,"identity_path":"","carlife_source":true
        })).unwrap();
        stop(710);
        assert!(prepare(710, &config).is_err());
        prepare(711, &config).unwrap();
        let first = attach(711).unwrap();
        stop(710);
        prepare(711, &config).unwrap();
        let next_engine = attach(711).unwrap();
        assert!(Arc::ptr_eq(&first.hub, &next_engine.hub));
        stop(711);
    }

    #[tokio::test(start_paused = true)]
    async fn media_reattach_during_canvas_wait_cannot_replay_old_source_or_restart_timer() {
        for pause in [true, false] {
            let mut feed = Feed::default();
            feed.receive(CarLifeEvent::EncoderResolution { width: 1024, height: 576 });
            for nal in [0x67, 0x68, 0x65] { feed.receive(video(nal)); }
            let (events, mut source) = mpsc::unbounded_channel();
            let stop = Arc::new(DialStop::default());
            let owner_stop = stop.clone();
            let waiting = tokio::spawn(async move {
                crate::carlife::wait_for_canvas_reopen(&events, &owner_stop).await
            });
            let reset = source.recv().await.unwrap();
            // Even a slow consumer must invalidate the old source before the four seconds start.
            tokio::time::advance(Duration::from_secs(5)).await;
            assert!(!waiting.is_finished());
            feed.receive(reset);
            tokio::task::yield_now().await;
            for _ in 0..2 {
                let mut engine = feed.attach();
                assert!(matches!(engine.try_recv(), Ok((CarLifeEvent::SourceReset { .. }, _))));
                assert!(matches!(engine.try_recv(), Ok((CarLifeEvent::VideoReplay, _))));
                assert!(engine.try_recv().is_err(), "reattached engine got a retired resolution, parameter or IDR");
            }
            tokio::time::advance(Duration::from_millis(3999)).await;
            assert!(!waiting.is_finished());
            if pause { stop.cancel(); }
            else { tokio::time::advance(Duration::from_millis(1)).await; }
            assert_eq!(waiting.await.unwrap(), !pause);
        }
    }
    #[tokio::test]
    #[ignore = "requires Xiaomi phone; launch CarWith after READY; no car connection or input injection"]
    async fn live_prewarm_survives_delayed_media_and_reattach() {
        let config:EngineConfig=serde_json::from_value(serde_json::json!({
            "device_id":"02:00:00:00:00:01","car_ip":"127.0.0.1","airplay_port":0,
            "width":2736,"height":800,"fps":30,"identity_path":"","carlife_source":true
        })).unwrap();
        let owner=90001;
        prepare(owner,&config).unwrap();
        println!("SOURCE-PREWARM READY: launch CarWith now; wait 25s without AirPlay");
        tokio::time::sleep(Duration::from_secs(25)).await;
        {
            let slot=SOURCE.lock();let source=slot.current.as_ref().unwrap();let feed=source.feed.lock();
            println!("SOURCE-PREWARM detached: authenticated={} frames={} cached_bytes={}",feed.handshake,feed.frames,feed.bytes);
            assert!(feed.handshake && feed.frames>20);
        }
        for round in 1..=2 {
            let mut source=attach(owner).unwrap();
            let began=Instant::now();let mut frames=0;
            tokio::time::timeout(Duration::from_secs(10),async {
                while let Some((event,_))=source.events.recv().await {
                    if matches!(event,CarLifeEvent::VideoFrame{..}|CarLifeEvent::VideoFrameTagged{..}) {frames+=1;}
                    if began.elapsed()>=Duration::from_secs(3) && frames>=20 {break;}
                }
            }).await.unwrap();
            assert!(frames>=20);println!("SOURCE-PREWARM media attach round={round} frames={frames}");
            drop(source);
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
        stop(owner);
        assert!(SOURCE.lock().current.is_none());
    }

    #[test] fn changed_parameter_set_invalidates_old_gop() {
        let mut feed=Feed::default();feed.receive(video(0x67));feed.receive(video(0x65));
        feed.receive(CarLifeEvent::VideoFrame{pts_us:2,data:vec![0,0,0,1,0x67,9]});
        assert!(feed.gop.is_empty());
    }
    #[test] fn screen_replay_keeps_parameters_after_gop_expiry_without_replaying_audio() {
        let mut feed=Feed::default();
        feed.receive(CarLifeEvent::AudioInit{tts:true,rate:16000,channels:1});
        feed.receive(video(0x67));feed.receive(video(0x68));feed.receive(video(0x65));
        let mut rx=feed.attach();while rx.try_recv().is_ok() {}
        feed.since=Some(Instant::now()-Duration::from_secs(3));feed.replay();
        assert!(matches!(rx.try_recv(),Ok((CarLifeEvent::VideoReplay, _))));
        for kind in [0x67,0x68] {
            match rx.try_recv().unwrap() {(CarLifeEvent::VideoFrame{data,..}, FrameOrigin::Cached)=>assert_eq!(data[4],kind),other=>panic!("{other:?}")}
        }
        assert!(rx.try_recv().is_err());
        assert!(feed.formats[1].is_some());
    }
    #[test] fn attach_starts_with_configuration_and_current_complete_gop() {
        let mut feed = Feed::default();
        feed.receive(CarLifeEvent::EncoderResolution{width:1920,height:720});
        feed.receive(video(0x67)); feed.receive(video(0x68));
        feed.receive(video(0x65)); feed.receive(video(0x41));
        let mut rx = feed.attach();
        assert!(matches!(rx.try_recv(),Ok((CarLifeEvent::SourceReset { .. }, _))));
        assert!(matches!(rx.try_recv(),Ok((CarLifeEvent::VideoReplay, _))));
        assert!(matches!(rx.try_recv(),Ok((CarLifeEvent::EncoderResolution{width:1920,..}, _))));
        let mut kinds = Vec::new();
        while let Ok((CarLifeEvent::VideoFrame{data,..}, FrameOrigin::Cached)) = rx.try_recv() {kinds.push(data[4]);}
        assert_eq!(kinds,vec![0x67,0x68,0x65,0x41]);
    }

    #[tokio::test]
    async fn screen_replay_writes_cached_avc_idr_without_a_new_source_frame() {
        verify_first_idr(FrameOrigin::Cached).await;
    }

    #[tokio::test]
    async fn screen_replay_with_only_parameters_writes_a_live_idr() {
        verify_first_idr(FrameOrigin::Live).await;
    }

    async fn verify_first_idr(expected: FrameOrigin) {
        android_logger::init_once(android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info).with_tag("HyperCar-IDR-test"));
        use crate::screen::{build_avcc, ScreenCipher, ScreenSender};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}};

        let mut feed = Feed::default();
        let mut events = feed.attach();
        let sps = [0x67, 0x42, 0, 0x1f];
        let pps = [0x68, 1];
        // Match the size and NAL layout of the 18:11 access unit without retaining its image.
        let mut idr = vec![0x55; 14_686 - 3 * 4 - sps.len() - pps.len()];
        idr[0] = 0x65;
        let mut au = Vec::new();
        for nal in [&sps[..], &pps, idr.as_slice()] {
            au.extend_from_slice(&[0, 0, 0, 1]);
            au.extend_from_slice(nal);
        }
        let pts = if expected == FrameOrigin::Cached { 1000 } else { 2000 };
        let initial = if expected == FrameOrigin::Cached { au.clone() } else { au[..8].to_vec() };
        feed.receive(CarLifeEvent::VideoFrame { pts_us: pts, data: initial });
        // The live copy arrived before screen installation and was consumed.
        while events.try_recv().is_ok() {}
        tokio::time::sleep(Duration::from_millis(125)).await;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let key = [7; 32];
        let sender = ScreenSender::new(client.into_split().1, ScreenCipher::ChaCha(key), 2736, 800, false);
        let (commands, installs) = mpsc::channel(1);
        let (source, video) = mpsc::unbounded_channel();
        let stop = Arc::new(Notify::new());
        let pump = tokio::spawn(crate::engine::video_pump(
            stop.clone(), Default::default(), true,
            Arc::new(Mutex::new(crate::pipeline::Pipeline::new())), installs, video, None, 30, None,
        ));
        let ready = Instant::now();
        tokio::time::timeout(Duration::from_secs(1), async {
            let modes = crate::modes::Modes::initial();
            let (reply, accepted) = tokio::sync::oneshot::channel();
            commands.send(crate::screen_session::ScreenCommand::InstallIfOwned {
                sender, revision: modes.screen_grant_generation(), modes: Arc::new(Mutex::new(modes)), reply,
            }).await.unwrap();
            assert!(accepted.await.unwrap());
            feed.replay();
            if expected == FrameOrigin::Live {
                feed.receive(CarLifeEvent::VideoFrame { pts_us: pts, data: au });
            }
            while let Ok((event, origin)) = events.try_recv() {
                let delivered = match event {
                    CarLifeEvent::VideoReplay => crate::engine::SourceEvent::ResetVideoSource,
                    CarLifeEvent::VideoFrame { pts_us, data } => {
                        if crate::screen::annexb_nals(&data).iter().any(|n| n[0] & 31 == 5) {
                            assert_eq!(origin, expected);
                        }
                        crate::engine::SourceEvent::VideoFrameTagged { pts_us, data, origin, codec: VideoCodec::H264 }
                    }
                    other => panic!("unexpected replay event {other:?}"),
                };
                source.send(delivered).unwrap();
            }
            for opcode in [1, 0] {
                let mut header = [0; 128];
                peer.read_exact(&mut header).await.unwrap();
                assert_eq!(header[4], opcode);
                let len = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
                let mut body = vec![0; len];
                peer.read_exact(&mut body).await.unwrap();
                if opcode == 1 {
                    assert_eq!(body, build_avcc(&sps, &pps));
                } else {
                    let plain = crate::crypto::chacha_open(&key, &crate::crypto::nonce64(0), &body, &header).unwrap();
                    assert_eq!(plain[..4], (idr.len() as u32).to_be_bytes());
                    assert_eq!(plain[4..], idr[..]);
                }
            }
        }).await.expect("peer did not receive cached configuration and IDR");
        println!("verified {} IDR, screen ready to loopback read: {} us", expected.as_str(), ready.elapsed().as_micros());
        stop.notify_one();
        pump.await.unwrap();
    }
    #[test] fn reset_and_overflow_never_replay_delta_without_idr() {
        let mut feed = Feed::default(); feed.receive(video(0x65));
        for _ in 0..300 {feed.receive(video(0x41));}
        assert!(feed.gop.is_empty());
        feed.receive(video(0x65)); feed.receive(CarLifeEvent::SourceReset { cache_cleared: None });
        feed.receive(video(0x41)); assert!(feed.gop.is_empty());
        assert!(feed.parameters.is_empty());
    }
    #[test] fn reconnect_reuses_live_source_and_discards_historical_pcm() {
        let mut feed = Feed::default();
        feed.receive(CarLifeEvent::AudioInit{tts:false,rate:48000,channels:2});
        feed.receive(CarLifeEvent::AudioData{received_at:std::time::Instant::now(),tts:false,rate:48000,channels:2,data:vec![1;4096]});
        feed.receive(video(0x65));
        let first = feed.attach(); drop(first);
        feed.receive(video(0x41));
        let mut second = feed.attach(); let mut frames=0;
        while let Ok((event,_)) = second.try_recv() {
            assert!(!matches!(event,CarLifeEvent::AudioData{..}));
            if matches!(event,CarLifeEvent::VideoFrame{..}) {frames+=1;}
        }
        assert_eq!(frames,2);
        feed.receive(video(0x41)); assert!(second.try_recv().is_ok());
    }
    #[test] fn new_idr_replaces_previous_gop_and_bytes_are_bounded() {
        let mut feed = Feed::default();
        feed.receive(video(0x65)); feed.receive(video(0x41)); feed.receive(video(0x65));
        assert_eq!(feed.gop.len(),1);
        feed.receive(CarLifeEvent::VideoFrame{pts_us:2,data:vec![0;MAX_GOP_BYTES+1]});
        assert!(feed.gop.is_empty());
        assert_eq!(feed.bytes,0);
    }
}
