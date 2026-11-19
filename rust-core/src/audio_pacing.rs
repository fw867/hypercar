//! Audio scheduling shared by the production pump and its stalled-worker regression.
use std::{collections::VecDeque, time::{Duration, Instant}};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct StreamId {
    pub rtsp: u64,
    pub stream_id: i64,
    pub setup_accepted_at: Instant,
}

#[derive(Clone, Copy, Default, PartialEq)]
pub(crate) struct DroppedFrames {
    pub media: u64,
    media_pre_setup: u64,
    media_post_setup: u64,
    pub tts: u64,
    pub voice: u64,
}

/// Arrival time of each batch of stereo frames produced by the resampler. Between
/// media pushes the mixer can only consume the FIFO head or clear the entire queue.
#[derive(Default)]
pub(crate) struct MediaQueueTrace {
    batches: VecDeque<(Instant, usize)>,
    frames: usize,
}

impl MediaQueueTrace {
    fn remove_front(&mut self, mut count: usize, setup: Option<Instant>) -> usize {
        let mut pre_setup = 0;
        while count > 0 {
            let Some((arrival, remaining)) = self.batches.front_mut() else { break; };
            let n = count.min(*remaining);
            if setup.is_some_and(|at| *arrival < at) { pre_setup += n; }
            count -= n;
            self.frames -= n;
            *remaining -= n;
            if *remaining == 0 { self.batches.pop_front(); }
        }
        pre_setup
    }

    pub fn observe_push(&mut self, queued_before: usize, appended: usize, overflow: usize,
        arrival: Instant, setup: Option<Instant>, dropped: &mut DroppedFrames) {
        // Mirror prior playback/clears without counting them as overflow or touching PCM.
        debug_assert!(self.frames >= queued_before);
        self.remove_front(self.frames - queued_before, None);
        if appended > 0 {
            self.batches.push_back((arrival, appended));
            self.frames += appended;
        }
        let old = self.remove_front(overflow, setup);
        dropped.media += overflow as u64;
        if setup.is_some() {
            dropped.media_pre_setup += old as u64;
            dropped.media_post_setup += (overflow - old) as u64;
        }
    }
}

/// Local to one mixer. A control connection can install more than one main sender.
pub(crate) struct PacingTrace {
    stream_id: Option<i64>,
    rtsp: Option<u64>,
    pub recoveries: u64,
    pub skipped: u64,
    pub max_late_us: u128,
    pub dropped: DroppedFrames,
    previous: DroppedFrames,
    last_report: Instant,
    window_start_ns: u64,
}

impl PacingTrace {
    pub fn new() -> Self {
        Self { stream_id: None, rtsp: None, recoveries: 0, skipped: 0, max_late_us: 0,
            dropped: Default::default(), previous: Default::default(),
            last_report: Instant::now(), window_start_ns: crate::timing::monotonic_nanos() }
    }

    pub fn select_stream(&mut self, rtsp: Option<u64>, stream_id: Option<i64>) -> Option<String> {
        if (self.rtsp, self.stream_id) == (rtsp, stream_id) { return None; }
        let now = crate::timing::monotonic_nanos();
        let tail = self.final_sample(now);
        self.rtsp = rtsp;
        self.stream_id = stream_id;
        self.recoveries = 0;
        self.skipped = 0;
        self.max_late_us = 0;
        self.dropped = DroppedFrames::default();
        self.previous = DroppedFrames::default();
        self.last_report = Instant::now();
        self.window_start_ns = now;
        tail
    }

    pub fn periodic(&mut self) -> Option<String> {
        (self.last_report.elapsed() >= Duration::from_secs(5))
            .then(|| self.sample("periodic", crate::timing::monotonic_nanos()))
    }

    fn final_sample(&mut self, now: u64) -> Option<String> {
        (self.stream_id.is_some() || self.recoveries > 0 || self.dropped != DroppedFrames::default())
            .then(|| self.sample("retired", now))
    }

    fn sample(&mut self, reason: &str, now: u64) -> String {
        let rtsp = self.rtsp.map_or_else(|| "unknown".into(), |n| n.to_string());
        let stream_id = self.stream_id.map_or_else(|| "unknown".into(), |n| n.to_string());
        let label = format!("RTSP#{rtsp} streamConnectionID={stream_id}");
        let start = std::mem::replace(&mut self.window_start_ns, now);
        let previous = std::mem::replace(&mut self.previous, self.dropped);
        let media_delta = self.dropped.media - previous.media;
        let pre = self.dropped.media_pre_setup - previous.media_pre_setup;
        let post = self.dropped.media_post_setup - previous.media_post_setup;
        self.last_report = Instant::now();
        format!(concat!("AUDIO-PACING: {} monoNs={} windowStartMonoNs={} reason={} recoveries={} ",
            "skipped_source_frames={} dropped_media_frames={} dropped_tts_frames={} dropped_voice_frames={} ",
            "max_late_us={} dropped_media_frames_delta={} dropped_tts_frames_delta={} dropped_voice_frames_delta={} ",
            "media_pre_setup_delta={} media_post_setup_delta={} media_setup_unknown_delta={}"),
            label, now, start, reason, self.recoveries, self.skipped, self.dropped.media, self.dropped.tts,
            self.dropped.voice, self.max_late_us, media_delta,
            self.dropped.tts - previous.tts, self.dropped.voice - previous.voice, pre, post, media_delta - pre - post)
    }
}

impl Drop for PacingTrace {
    fn drop(&mut self) {
        if let Some(line) = self.final_sample(crate::timing::monotonic_nanos()) { crate::emit_log(&line); }
    }
}

fn period(rate: u32) -> Duration { Duration::from_nanos(352 * 1_000_000_000 / u64::from(rate)) }

pub(crate) fn interval(rate: u32) -> tokio::time::Interval {
    let mut tick = tokio::time::interval(period(rate));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    tick
}

/// Whole 352-frame source blocks missed while the worker was stalled. Sub-period jitter
/// must not reset a codec or discard a complete audio block.
pub(crate) fn missed_source_frames(scheduled: tokio::time::Instant, now: tokio::time::Instant, rate: u32) -> u64 {
    let late = now.saturating_duration_since(scheduled).as_nanos();
    (late / period(rate).as_nanos()).min(u64::MAX as u128 / 352) as u64 * 352
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resampled_44100_to_48000_boundary_frame_counts_only_in_post_setup_tail() {
        let mut converter = crate::audio_resample::PcmToStereo::default();
        let mut queue = VecDeque::new();
        let mut fifo = MediaQueueTrace::default();
        let mut trace = PacingTrace::new();
        trace.select_stream(Some(7), Some(88));
        let setup = Instant::now();
        let mut push = |frames: usize, sample: i16, arrival, dropped: &mut DroppedFrames| {
            let before = queue.len();
            converter.push(&sample.to_le_bytes().repeat(frames * 2), 44_100, 2, 48_000, &mut queue);
            let appended = (queue.len() - before) / 2;
            let first_sample = queue[before];
            let overflow = (queue.len() / 2).saturating_sub(24_000); // 500 ms at 48 kHz
            queue.drain(..overflow * 2);
            fifo.observe_push(before / 2, appended, overflow, arrival, Some(setup), dropped);
            (appended, overflow, first_sample)
        };
        let before = setup - Duration::from_millis(1);
        let after = setup + Duration::from_millis(1);
        assert_eq!(push(14_702, 0, before, &mut trace.dropped), (16_002, 0, 0));
        // The first post-SETUP frame interpolates between 0 and 10,000.
        assert_eq!(push(14_699, 10_000, after, &mut trace.dropped), (15_999, 8_001, 8_375));
        trace.last_report -= Duration::from_secs(5);
        let periodic = trace.periodic().unwrap();

        // Evict the remaining half of the old batch and exactly the interpolated frame.
        assert_eq!(push(7_352, 10_000, after, &mut trace.dropped), (8_002, 8_002, 10_000));
        let retired = trace.select_stream(Some(7), None).unwrap();
        let field = |line: &str, name: &str| -> u64 {
            line.split_whitespace().filter_map(|word| word.split_once('='))
                .find(|(key, _)| *key == name).unwrap().1.parse().unwrap()
        };
        for (line, pre, post) in [(&periodic, 8_001, 0), (&retired, 8_001, 1)] {
            assert_eq!(field(line, "media_pre_setup_delta"), pre);
            assert_eq!(field(line, "media_post_setup_delta"), post);
            assert_eq!(field(line, "media_setup_unknown_delta"), 0);
            assert_eq!(field(line, "dropped_media_frames_delta"), pre + post);
        }
        let total = field(&retired, "dropped_media_frames");
        assert_eq!(total, 16_003);
        assert_eq!(field(&periodic, "dropped_media_frames_delta")
            + field(&retired, "dropped_media_frames_delta"), total);
    }

    #[test]
    fn rebuilt_main_stream_classifies_evicted_fifo_head_across_setup_and_periodic_boundary() {
        let mut trace = PacingTrace::new();
        let mut fifo = MediaQueueTrace::default();
        let setup = Instant::now();
        let before = setup - Duration::from_millis(12);
        let after = setup + Duration::from_millis(1);
        trace.select_stream(Some(12), Some(41));
        fifo.observe_push(0, 8, 0, before, Some(before), &mut trace.dropped);
        trace.select_stream(Some(12), None);
        // Install clears the old queue. During the gap a separate overflow has no stream.
        fifo.observe_push(0, 5, 3, before, None, &mut trace.dropped);
        let gap = trace.select_stream(Some(12), Some(42)).unwrap();
        assert!(gap.contains("media_setup_unknown_delta=3"));

        // A delayed packet contributes eight frames; two play before the next arrival.
        fifo.observe_push(0, 8, 0, before, Some(setup), &mut trace.dropped);
        fifo.observe_push(6, 5, 7, setup, Some(setup), &mut trace.dropped);
        trace.last_report -= Duration::from_secs(5);
        let line = trace.periodic().unwrap();
        assert!(line.contains("dropped_media_frames_delta=7 "));
        assert!(line.contains("media_pre_setup_delta=6 media_post_setup_delta=1 media_setup_unknown_delta=0"));

        // Arrival order can differ from timestamps. A late old packet first evicts
        // two of the four post-SETUP frames, then the next overflow crosses both batches.
        fifo.observe_push(4, 4, 2, before, Some(setup), &mut trace.dropped);
        fifo.observe_push(6, 5, 5, after, Some(setup), &mut trace.dropped);
        let tail = trace.select_stream(Some(12), None).unwrap();
        assert!(tail.contains("RTSP#12 streamConnectionID=42 "));
        assert!(tail.contains("media_pre_setup_delta=3 media_post_setup_delta=4 media_setup_unknown_delta=0"));
    }

    #[test]
    fn queued_pcm_before_first_install_keeps_its_arrival_across_free_running_mixer_ticks() {
        let mut fifo = MediaQueueTrace::default();
        let mut trace = PacingTrace::new();
        let setup = Instant::now();
        fifo.observe_push(0, 8, 0, setup - Duration::from_millis(1), None, &mut trace.dropped);
        trace.select_stream(Some(1), Some(18));
        // The initial ready sender retains the same-rate queue. Three frames already played.
        fifo.observe_push(5, 4, 6, setup, Some(setup), &mut trace.dropped);
        let tail = trace.select_stream(Some(1), None).unwrap();
        assert!(tail.contains("media_pre_setup_delta=5 media_post_setup_delta=1 media_setup_unknown_delta=0"));
    }

    #[test]
    fn main_stream_rebuild_splits_even_a_partial_pacing_window() {
        let mut trace = PacingTrace::new();
        let rtsp = Some(12);
        let first = Some(41);
        let rebuilt = Some(42);
        trace.dropped.media = 700;
        assert!(trace.select_stream(rtsp, first).unwrap().contains("RTSP#unknown streamConnectionID=unknown"));
        trace.dropped = DroppedFrames { media: 100, tts: 20, voice: 3, ..DroppedFrames::default() };
        trace.last_report -= Duration::from_secs(5);
        let first_window = trace.periodic().unwrap();
        assert!(first_window.contains("dropped_media_frames_delta=100 "));
        assert!(trace.select_stream(rtsp, first).is_none());
        trace.dropped.media += 7;
        let tail = trace.select_stream(rtsp, rebuilt).unwrap();
        assert!(tail.contains("RTSP#12 streamConnectionID=41 monoNs=") && tail.contains("reason=retired"));
        assert!(tail.contains("dropped_media_frames=107 ") && tail.contains("dropped_media_frames_delta=7 "));
        assert!(tail.contains("dropped_tts_frames_delta=0 dropped_voice_frames_delta=0"));
        trace.dropped.media += 11;
        let next = trace.select_stream(rtsp, None).unwrap();
        assert!(next.contains("RTSP#12 streamConnectionID=42 monoNs="));
        assert!(next.contains("dropped_media_frames=11 ") && next.contains("dropped_media_frames_delta=11 "));
        assert!(next.contains("dropped_tts_frames=0 dropped_voice_frames=0"));
        trace.dropped.media = 1;
        let gap = trace.select_stream(rtsp, first).unwrap();
        assert!(gap.contains("RTSP#12 streamConnectionID=unknown ") && gap.contains("dropped_media_frames_delta=1 "));
    }

    #[tokio::test]
    async fn same_address_reinvite_keeps_a_late_pacing_tail_with_its_rtsp_connection() {
        use tokio::net::{TcpListener, TcpStream};
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut connections = Vec::new();
        let mut peers = Vec::new();
        for _ in 0..2 {
            let client = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            peers.push(listener.accept().await.unwrap().0);
            let (r, w) = client.into_split();
            connections.push(crate::rtsp::RtspChannel::spawn(r, w, None).0);
        }
        let old_id = connections[0].trace_id();
        let new_id = connections[1].trace_id();
        assert_ne!(old_id, new_id);
        let mut old = PacingTrace::new();
        let mut new = PacingTrace::new();
        // Even if the stream ID is reused, the old pump can finish after the new one starts.
        old.select_stream(Some(old_id), Some(42));
        let old_setup = Instant::now();
        let arrival = old_setup + Duration::from_millis(1);
        let new_setup = arrival + Duration::from_millis(1);
        let mut old_fifo = MediaQueueTrace::default();
        old_fifo.observe_push(0, 10, 0, arrival, Some(old_setup), &mut old.dropped);
        new.select_stream(Some(new_id), Some(42));
        let mut new_fifo = MediaQueueTrace::default();
        new_fifo.observe_push(0, 5, 2, arrival, Some(new_setup), &mut new.dropped);
        old_fifo.observe_push(10, 9, 9, arrival, Some(old_setup), &mut old.dropped);
        let late = old.select_stream(Some(old_id), None).unwrap();
        assert!(late.contains(&format!("RTSP#{old_id} streamConnectionID=42 monoNs=")));
        assert!(late.contains("dropped_media_frames_delta=9 "));
        assert!(late.contains("media_pre_setup_delta=0 media_post_setup_delta=9 media_setup_unknown_delta=0"));
        let fresh = new.select_stream(Some(new_id), None).unwrap();
        assert!(fresh.contains(&format!("RTSP#{new_id} streamConnectionID=42 monoNs=")));
        assert!(fresh.contains("dropped_media_frames_delta=2 "));
        assert!(fresh.contains("media_pre_setup_delta=2 media_post_setup_delta=0 media_setup_unknown_delta=0"));
    }

    #[tokio::test(start_paused = true)]
    async fn a_stalled_audio_worker_does_not_replay_elapsed_packets_in_a_burst() {
        let mut tick = interval(48000);
        tick.tick().await;
        tokio::time::advance(Duration::from_millis(100)).await;
        tick.tick().await;
        assert!(tokio::time::timeout(Duration::from_millis(1), tick.tick()).await.is_err(),
            "missed audio must advance the RTP clock, not replay immediately");
    }

    #[test]
    fn elapsed_source_frames_ignore_small_jitter_and_count_only_whole_periods() {
        let now=tokio::time::Instant::now();
        assert_eq!(missed_source_frames(now,now+Duration::from_millis(5),48000),0);
        assert_eq!(missed_source_frames(now,now+Duration::from_millis(100),48000),4576);
        assert_eq!(missed_source_frames(now,now+period(44100)*3,44100),1056);
    }
}
