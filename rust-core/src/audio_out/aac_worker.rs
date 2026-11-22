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
        mut encode: impl FnMut(u64, &[i16]) -> io::Result<Vec<Vec<u8>>> + Send + 'static,
    ) -> io::Result<Self> {
        let (input, jobs) = mpsc::sync_channel::<Input>(QUEUED_BLOCKS);
        let (results, output) = mpsc::sync_channel(QUEUED_BLOCKS);
        let worker_owner = ownership.clone();
        std::thread::Builder::new().name("hypercar-aac".into()).spawn(move || {
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

    #[test]
    fn aac_timeline_preserves_source_positions_across_gaps_and_recovery() {
        let mark = Instant::now();
        let packet = |ts, recover_until| AacTiming { ts, recover_until };
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
        let timing = AacTiming { ts: 0, recover_until: None };
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
