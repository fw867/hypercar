//! Audio scheduling shared by the production pump and its stalled-worker regression.
use std::time::Duration;

pub(crate) const PERIOD: Duration = Duration::from_nanos(352 * 1_000_000_000 / 48_000);

pub(crate) fn interval() -> tokio::time::Interval {
    let mut tick = tokio::time::interval(PERIOD);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    tick
}

/// Whole 352-frame source blocks missed while the worker was stalled. Sub-period jitter
/// must not reset a codec or discard a complete audio block.
pub(crate) fn missed_source_frames(scheduled: tokio::time::Instant, now: tokio::time::Instant) -> u64 {
    let late = now.saturating_duration_since(scheduled).as_nanos();
    (late / PERIOD.as_nanos()).min(u64::MAX as u128 / 352) as u64 * 352
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(start_paused = true)]
    async fn a_stalled_audio_worker_does_not_replay_elapsed_packets_in_a_burst() {
        let mut tick = interval();
        tick.tick().await;
        tokio::time::advance(Duration::from_millis(100)).await;
        tick.tick().await;
        assert!(tokio::time::timeout(Duration::from_millis(1), tick.tick()).await.is_err(),
            "missed audio must advance the RTP clock, not replay immediately");
    }

    #[test]
    fn elapsed_source_frames_ignore_small_jitter_and_count_only_whole_periods() {
        let now=tokio::time::Instant::now();
        assert_eq!(missed_source_frames(now,now+Duration::from_millis(5)),0);
        assert_eq!(missed_source_frames(now,now+Duration::from_millis(100)),4576);
        assert_eq!(missed_source_frames(now,now+PERIOD*3),1056);
    }
}
