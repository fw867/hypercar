//! Per-connection sampling of diagnostic metadata, never of protocol traffic.
use std::time::{Duration, Instant};

const SUMMARY_INTERVAL: Duration = Duration::from_secs(30);

#[derive(Default)]
pub(super) struct Repeats<T> {
    last: Option<(T, Instant)>,
}

impl<T: PartialEq + Clone> Repeats<T> {
    fn due(&mut self, value: &T, now: Instant) -> bool {
        if self.last.as_ref().is_some_and(|(old, at)| {
            old == value && now.duration_since(*at) < SUMMARY_INTERVAL
        }) {
            return false;
        }
        self.last = Some((value.clone(), now));
        true
    }
}

impl Repeats<String> {
    /// Compare all cumulative counters (including errors and pending bytes).
    /// EOF, errors and state transitions bypass this periodic-only path.
    pub(super) fn periodic(&mut self, report: String, emit: impl FnOnce(&str)) {
        if self.due(&report, Instant::now()) { emit(&report); }
    }
}

#[derive(Default)]
pub(super) struct CommandRx {
    status: [Repeats<usize>; 2],
}

impl CommandRx {
    pub(super) fn due(&mut self, kind: u32, len: usize, healthy: bool, now: Instant) -> bool {
        if !healthy {
            *self = Self::default();
            return true;
        }
        // Only coalesce identical RX metadata; Session::receive sees every payload.
        let index = match kind { 0x10026 => 0, 0x10036 => 1, _ => return true };
        self.status[index].due(&len, now)
    }
}

pub(super) fn heartbeat_sample(count: u64) -> bool {
    count == 1 || (count != 0 && count % 30 == 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn healthy_interleaved_rx_is_sampled_per_kind_but_shape_and_state_changes_are_visible() {
        let now = Instant::now();
        let mut rx = CommandRx::default();
        assert!(rx.due(0x10026, 8, true, now));
        assert!(rx.due(0x10036, 4, true, now));
        assert!(!rx.due(0x10026, 8, true, now));
        assert!(!rx.due(0x10036, 4, true, now + Duration::from_secs(29)));
        assert!(rx.due(0x10036, 4, true, now + SUMMARY_INTERVAL));
        assert!(rx.due(0x10026, 44, true, now));
        assert!(rx.due(0x10026, 8, true, now));
        assert!(rx.due(0x10026, 8, false, now));
        assert!(rx.due(0x10026, 8, false, now));
        assert!(rx.due(0x10026, 8, true, now));
        for _ in 0..2 { assert!(rx.due(0x10049, 34, true, now)); }
    }

    #[test]
    fn periodic_reports_keep_first_changed_and_thirty_second_snapshots() {
        let mut sampler = Repeats::default();
        let now = Instant::now();
        assert!(sampler.due(&"frames=0", now));
        assert!(!sampler.due(&"frames=0", now + Duration::from_secs(5)));
        assert!(sampler.due(&"frames=0", now + SUMMARY_INTERVAL));
        assert!(sampler.due(&"parseFail=1", now + SUMMARY_INTERVAL));
    }

    #[test]
    fn video_heartbeat_logs_first_success_and_periodic_cumulative_counts() {
        assert_eq!((0..=61).filter(|&n| heartbeat_sample(n)).collect::<Vec<_>>(), [1, 30, 60]);
    }
}
