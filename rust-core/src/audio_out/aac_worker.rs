//! MediaCodec runs independently of the real-time audio pump.
use crate::{aac::AacEncoder, audio_pacing::StageSpan, buffered_ownership::Ownership};
use std::{collections::VecDeque, io, sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError}, time::Instant};
use tokio::sync::watch;

// A 352-frame mixer block represents about 7–8 ms at the supported rates.
pub(super) const QUEUED_BLOCKS: usize = 8;
const CHANNELS: usize = crate::audio_out::CHANNELS;
const AU_SAMPLES: usize = crate::aac::FRAMES * CHANNELS;

struct Input {
    generation: u64,
    pcm: Box<[i16]>,
    timing: AacTiming,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct AacTiming {
    pub ts: u32,
    pub recover_until: Option<Instant>,
    pub rtp_generation: Option<u64>,
}

#[derive(Default)]
struct AacInput {
    partial: Vec<i16>,
    timing: Option<AacTiming>,
    submitted: VecDeque<AacTiming>,
}

impl AacInput {
    fn prepare(&mut self, mut pcm: &[i16], mut timing: AacTiming, ready: &mut Vec<i16>) {
        ready.clear();
        if self.timing.is_some_and(|start| {
            start.ts.wrapping_add((self.partial.len() / CHANNELS) as u32) != timing.ts
        }) {
            // Only complete access units reach MediaCodec, so no AU can straddle a gap.
            self.partial.clear();
            self.timing = None;
        }
        let mut recovered_samples = crate::audio_out::SAMPLES_PER_PACKET * CHANNELS;
        while !pcm.is_empty() {
            let take = pcm.len().min(AU_SAMPLES - self.partial.len());
            let start = self.timing.get_or_insert(timing);
            // The AU may contain PCM prepared on both sides of a handover even
            // when the worker admitted the blocks under one current ownership generation.
            if start.rtp_generation != timing.rtp_generation { start.rtp_generation = None; }
            if let Some(deadline) = timing.recover_until {
                start.recover_until = Some(start.recover_until.map_or(deadline, |old| old.min(deadline)));
            }
            self.partial.extend_from_slice(&pcm[..take]);
            pcm = &pcm[take..];
            timing.ts = timing.ts.wrapping_add((take / CHANNELS) as u32);
            recovered_samples = recovered_samples.saturating_sub(take);
            if recovered_samples == 0 { timing.recover_until = None; }
            if self.partial.len() == AU_SAMPLES {
                ready.extend_from_slice(&self.partial);
                self.partial.clear();
                self.submitted.push_back(self.timing.take().unwrap());
            }
        }
    }
}

pub(super) struct Output {
    pub generation: u64,
    pub payload: io::Result<Option<Vec<u8>>>,
    pub encode_span: StageSpan,
    pub timing: Option<AacTiming>,
}

pub(super) struct AacWorker {
    input: SyncSender<Input>,
    output: Receiver<Output>,
    ownership: watch::Receiver<Ownership>,
}

impl AacWorker {
    pub fn start(encoder: AacEncoder, ownership: watch::Receiver<Ownership>) -> io::Result<Self> {
        let rate = encoder.sample_rate();
        let mut encoder = Some(encoder);
        let mut previous = ownership.borrow().losses;
        Self::spawn(ownership, move |generation, pcm| {
            if generation != previous {
                encoder.take();
                previous = generation;
            }
            if encoder.is_none() {
                encoder = Some(AacEncoder::at_rate(rate)?);
            }
            let result = encoder.as_mut().unwrap().encode(pcm);
            if result.is_err() { encoder.take(); }
            result
        })
    }

    pub(super) fn spawn(
        ownership: watch::Receiver<Ownership>,
        encode: impl FnMut(u64, &[i16]) -> io::Result<Vec<Vec<u8>>> + Send + 'static,
    ) -> io::Result<Self> {
        let (input, jobs) = mpsc::sync_channel::<Input>(QUEUED_BLOCKS);
        let (results, output) = mpsc::sync_channel(QUEUED_BLOCKS);
        let worker_owner = ownership.clone();
        let retirement = crate::callback_context::current()
            .and_then(|context| context.audio_shutdown)
            .map(|shutdown| shutdown.track_audio_thread());
        std::thread::Builder::new().name("hypercar-aac".into()).spawn(move || {
            // Local drop order matters, including early return and panic:
            // the captured codec must finish stop/delete before acknowledging
            // engine retirement. Dropping AacWorker itself never joins here.
            let _retirement = retirement;
            let mut encode = encode;
            let mut generation = None;
            let mut pcm = AacInput::default();
            let mut ready = Vec::new();
            while let Ok(job) = jobs.recv() {
                let state = *worker_owner.borrow();
                if !state.owned || state.losses != job.generation { continue; }
                if generation != Some(job.generation) {
                    generation = Some(job.generation);
                    pcm = AacInput::default();
                }
                pcm.prepare(&job.pcm, job.timing, &mut ready);
                let started = crate::timing::monotonic_nanos();
                let frames = encode(job.generation, &ready);
                if frames.is_err() { pcm = AacInput::default(); }
                let encode_span = StageSpan::since(started);
                let state = *worker_owner.borrow();
                if !state.owned || state.losses != job.generation { continue; }
                let generation = job.generation;
                match frames {
                    Ok(frames) if !frames.is_empty() => {
                        for payload in frames {
                            let (payload, timing) = match pcm.submitted.pop_front() {
                                None => (Err(io::Error::other("AAC output has no input timestamp")), None),
                                Some(timing) => (Ok(Some(payload)), Some(timing)),
                            };
                            if results.send(Output { generation, payload, encode_span, timing }).is_err() { return; }
                        }
                    }
                    other => if results.send(Output { generation, payload: other.map(|_| None), encode_span, timing: None }).is_err() { break; }
                }
            }
        })?;
        Ok(Self { input, output, ownership })
    }

    pub fn generation(&self) -> Option<u64> {
        let state = self.ownership.borrow();
        state.owned.then_some(state.losses)
    }

    pub fn submit(&self, generation: u64, pcm: &[i16], timing: AacTiming) -> io::Result<bool> {
        match self.input.try_send(Input { generation, pcm: pcm.into(), timing }) {
            Ok(()) => Ok(true),
            Err(TrySendError::Full(_)) => Ok(false),
            Err(TrySendError::Disconnected(_)) => Err(io::Error::other("AAC worker stopped")),
        }
    }

    pub fn try_output(&self) -> io::Result<Option<Output>> {
        match self.output.try_recv() {
            Ok(output) => Ok(Some(output)),
            Err(TryRecvError::Empty) => Ok(None),
            Err(TryRecvError::Disconnected) => Err(io::Error::other("AAC worker stopped")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::buffered_ownership::BufferedOwnership;

    #[cfg(target_os = "android")]
    #[test]
    fn device_aac_music_stop_and_reconnect_cycles() {
        use std::{sync::Arc, time::Duration};
        let pcm: Vec<i16> = (0..AU_SAMPLES).map(|i| {
            (((i / CHANNELS) as f32 * 0.0576).sin() * 6000.) as i16
        }).collect();
        for cycle in 0..12 {
            let rate = if cycle % 2 == 0 { 44100 } else { 48000 };
            let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
            let mut context = crate::callback_context::Context::new(None);
            context.audio_shutdown = Some(shutdown.clone());
            let owner = BufferedOwnership::default();
            owner.publish(true);
            let encoder = AacEncoder::at_rate(rate).unwrap();
            let worker = crate::callback_context::sync_scope(context, || {
                AacWorker::start(encoder, owner.subscribe()).unwrap()
            });
            let mut packets = 0;
            let mut ts = 0;
            for _ in 0..30 {
                let timing = AacTiming { ts, recover_until: None, rtp_generation: None };
                assert!(worker.submit(0, &pcm, timing).unwrap());
                ts += crate::aac::FRAMES as u32;
                let output = worker.output.recv_timeout(Duration::from_secs(1)).unwrap();
                assert_eq!(output.generation, 0);
                if let Some(payload) = output.payload.unwrap() {
                    assert!(!payload.is_empty());
                    assert!(output.timing.is_some());
                    packets += 1;
                }
                if packets >= 4 { break; }
                std::thread::sleep(Duration::from_millis(5));
            }
            assert!(packets >= 4, "cycle={cycle} rate={rate}: music did not encode");
            // Retire with music still queued, without draining the output.
            for _ in 0..QUEUED_BLOCKS {
                let _ = worker.submit(0, &pcm, AacTiming {
                    ts, recover_until: None, rtp_generation: None,
                }).unwrap();
                ts += crate::aac::FRAMES as u32;
            }
            let slot = parking_lot::Mutex::new(Some(shutdown.clone()));
            shutdown.finish();
            let started = Instant::now();
            drop(worker);
            assert!(crate::shutdown::stop_retaining(&slot, |old| {
                old.request(false);
                old.wait_done(Duration::from_secs(2))
            }), "cycle={cycle}: codec did not retire within normal stop budget");
            assert!(slot.lock().is_none());
            println!("AAC cycle={cycle} rate={rate} packets={packets} stop_ms={}", started.elapsed().as_millis());
        }
    }

    #[test]
    fn slow_codec_stop_is_bounded_and_reconnect_can_retry_after_retirement() {
        use std::{sync::Arc, time::Duration};
        struct EncoderRetirement(mpsc::Sender<()>, mpsc::Receiver<()>);
        impl Drop for EncoderRetirement {
            fn drop(&mut self) {
                let _ = self.0.send(());
                let _ = self.1.recv_timeout(Duration::from_secs(5));
            }
        }
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let mut context = crate::callback_context::Context::new(None);
        context.audio_shutdown = Some(shutdown.clone());
        let (started, retiring) = mpsc::channel();
        let (release, blocked) = mpsc::channel();
        let encoder = EncoderRetirement(started, blocked);
        let owner = BufferedOwnership::default();
        let worker = crate::callback_context::sync_scope(context, || {
            AacWorker::spawn(owner.subscribe(), move |_, _| {
                let _ = &encoder;
                Ok(Vec::new())
            }).unwrap()
        });
        drop(worker);
        retiring.recv_timeout(Duration::from_secs(1)).unwrap();
        shutdown.finish();
        let slot = parking_lot::Mutex::new(Some(shutdown.clone()));
        let began = Instant::now();
        let stopped = crate::shutdown::stop_retaining(&slot, |old| {
            old.request(false);
            old.wait_done(Duration::from_millis(20))
        });
        let elapsed = began.elapsed();
        let retained = slot.lock().as_ref().is_some_and(|old| Arc::ptr_eq(old, &shutdown));
        release.send(()).unwrap();
        assert!(!stopped && retained, "unfinished codec must retain its original engine");
        assert!(elapsed < Duration::from_secs(1), "stop blocked beyond its budget");
        assert!(crate::shutdown::stop_retaining(&slot, |old| old.wait_done(Duration::from_secs(1))));
        assert!(slot.lock().is_none(), "completed engine must no longer block reconnect");
    }

    #[test]
    fn engine_completion_waits_for_encoder_destructor_without_blocking_worker_drop() {
        use std::{sync::Arc, time::Duration};
        struct EncoderRetirement(mpsc::Sender<()>, mpsc::Receiver<()>);
        impl Drop for EncoderRetirement {
            fn drop(&mut self) {
                let _ = self.0.send(());
                let _ = self.1.recv_timeout(Duration::from_secs(5));
            }
        }
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let mut context = crate::callback_context::Context::new(None);
        context.audio_shutdown = Some(shutdown.clone());
        let (started, retiring) = mpsc::channel();
        let (release, blocked) = mpsc::channel();
        let encoder = EncoderRetirement(started, blocked);
        let owner = BufferedOwnership::default();
        let worker = crate::callback_context::sync_scope(context, || {
            AacWorker::spawn(owner.subscribe(), move |_, _| {
                let _ = &encoder;
                Ok(Vec::new())
            }).unwrap()
        });
        // Dropping channels must stay nonblocking on the real-time pump.
        drop(worker);
        retiring.recv_timeout(Duration::from_secs(1)).unwrap();
        shutdown.finish();
        let completed_before_encoder_release = shutdown.wait_done(Duration::from_millis(5));
        release.send(()).unwrap();
        assert!(shutdown.wait_done(Duration::from_secs(1)));
        assert!(!completed_before_encoder_release, "engine retired before its AAC codec");
    }

    #[test]
    fn full_output_queue_retires_without_joining_or_finishing_another_engine() {
        use std::{sync::Arc, time::Duration};
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let next = Arc::new(crate::shutdown::EngineShutdown::new());
        let mut context = crate::callback_context::Context::new(None);
        context.audio_shutdown = Some(shutdown.clone());
        let owner = BufferedOwnership::default();
        owner.publish(true);
        let worker = crate::callback_context::sync_scope(context, || {
            AacWorker::spawn(owner.subscribe(), |_, _| {
                Ok(vec![vec![1]; QUEUED_BLOCKS + 3])
            }).unwrap()
        });
        let timing = AacTiming { ts: 0, recover_until: None, rtp_generation: None };
        worker.submit(0, &[1; AU_SAMPLES], timing).unwrap();
        worker.output.recv_timeout(Duration::from_secs(1)).unwrap();
        shutdown.finish();
        next.finish();
        assert!(!shutdown.wait_done(Duration::from_millis(5)));
        assert!(next.wait_done(Duration::ZERO));
        // Results still exceed queue capacity. Dropping both channel ends
        // must unblock send, then release the old engine's guard.
        drop(worker);
        assert!(shutdown.wait_done(Duration::from_secs(1)));
    }

    #[test]
    fn worker_panic_still_retires_its_encoder_before_engine_completion() {
        use std::{sync::Arc, time::Duration};
        struct EncoderRetirement(mpsc::Sender<()>);
        impl Drop for EncoderRetirement {
            fn drop(&mut self) { let _ = self.0.send(()); }
        }
        let shutdown = Arc::new(crate::shutdown::EngineShutdown::new());
        let mut context = crate::callback_context::Context::new(None);
        context.audio_shutdown = Some(shutdown.clone());
        let (released, retired) = mpsc::channel();
        let encoder = EncoderRetirement(released);
        let owner = BufferedOwnership::default();
        owner.publish(true);
        let worker = crate::callback_context::sync_scope(context, || {
            AacWorker::spawn(owner.subscribe(), move |_, _| {
                let _ = &encoder;
                panic!("intentional AAC worker panic");
            }).unwrap()
        });
        shutdown.finish();
        worker.submit(0, &[1; AU_SAMPLES], AacTiming {
            ts: 0, recover_until: None, rtp_generation: None,
        }).unwrap();
        assert!(shutdown.wait_done(Duration::from_secs(1)));
        retired.recv_timeout(Duration::from_secs(1)).unwrap();
        assert!(matches!(worker.output.recv_timeout(Duration::from_secs(1)),
            Err(mpsc::RecvTimeoutError::Disconnected)));
    }

    #[test]
    fn aac_timeline_preserves_source_positions_across_gaps_and_recovery() {
        let mark = Instant::now();
        let packet = |ts, recover_until| AacTiming { ts, recover_until, rtp_generation: None };
        let mut pcm = AacInput::default();
        let mut ready = Vec::new();
        pcm.prepare(&[1; 352 * CHANNELS], packet(0, None), &mut ready);
        assert!(ready.is_empty());
        pcm.prepare(&[2; 704 * CHANNELS], packet(352, Some(mark)), &mut ready);
        assert_eq!(ready.len(), AU_SAMPLES);
        assert!(ready[352 * CHANNELS..].iter().all(|&sample| sample == 2));
        // The following input arrives after one expired slot.
        pcm.prepare(&[3; AU_SAMPLES], packet(1408, None), &mut ready);
        assert_eq!(ready, vec![3; AU_SAMPLES]);
        pcm.prepare(&[4; AU_SAMPLES], packet(2432, None), &mut ready);
        assert_eq!(ready, vec![4; AU_SAMPLES]);
        assert_eq!(pcm.submitted.pop_front(), Some(packet(0, Some(mark))));
        assert_eq!(pcm.submitted.pop_front(), Some(packet(1408, None)));
        assert_eq!(pcm.submitted.pop_front(), Some(packet(2432, None)));
    }

    #[test]
    fn aac_feedback_skips_a_frame_prepared_across_ownership_generations() {
        let timing = |ts, rtp_generation| AacTiming { ts, recover_until: None, rtp_generation };
        let mut pcm = AacInput::default();
        let mut ready = Vec::new();
        pcm.prepare(&[1; AU_SAMPLES / 2], timing(0, Some(0)), &mut ready);
        pcm.prepare(&[2; AU_SAMPLES / 2], timing((crate::aac::FRAMES / 2) as u32, Some(1)), &mut ready);
        assert_eq!(ready.len(), AU_SAMPLES);
        assert_eq!(pcm.submitted.pop_front(), Some(timing(0, None)));

        pcm.prepare(&[3; AU_SAMPLES], timing(crate::aac::FRAMES as u32, Some(1)), &mut ready);
        assert_eq!(pcm.submitted.pop_front(), Some(timing(crate::aac::FRAMES as u32, Some(1))));
    }

    #[test]
    fn stalled_encoder_never_blocks_submission_and_old_generation_is_discarded() {
        let owner = BufferedOwnership::default();
        owner.publish(true);
        let (started, begun) = mpsc::channel();
        let (release, blocked) = mpsc::channel();
        let worker = AacWorker::spawn(owner.subscribe(), move |generation, pcm| {
            if generation == 0 {
                started.send(()).unwrap();
                blocked.recv().unwrap();
            }
            Ok(vec![vec![pcm[0] as u8]])
        }).unwrap();
        let timeout = std::time::Duration::from_secs(1);
        let timing = AacTiming { ts: 0, recover_until: None, rtp_generation: None };
        assert!(worker.submit(0, &[1; AU_SAMPLES], timing).unwrap());
        begun.recv_timeout(timeout).unwrap();
        assert!(worker.try_output().unwrap().is_none());
        for _ in 0..QUEUED_BLOCKS { assert!(worker.submit(0, &[2; AU_SAMPLES], timing).unwrap()); }
        assert!(!worker.submit(0, &[3; AU_SAMPLES], timing).unwrap());
        owner.publish(false);
        owner.publish(true);
        release.send(()).unwrap();
        let deadline = std::time::Instant::now() + timeout;
        while !worker.submit(1, &[9; AU_SAMPLES], timing).unwrap() {
            assert!(std::time::Instant::now() < deadline);
            std::thread::yield_now();
        }
        let result = worker.output.recv_timeout(timeout).unwrap();
        assert_eq!(result.generation, 1);
        assert_eq!(result.payload.unwrap(), Some(vec![9]));
        assert!(worker.try_output().unwrap().is_none());
    }
}
