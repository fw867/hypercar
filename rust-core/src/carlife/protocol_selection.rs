//! Per-source protocol probing; candidates are not confirmations.
use super::CarLifeDialect;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::time::Instant;

const CONFIRMED_PLAIN32_STARTUP_LIMIT: u8 = 3;
pub(super) const PLAIN32_RECOVERY_BACKOFF: Duration = Duration::from_secs(15);

#[derive(Clone, Copy, Default)]
pub(super) struct Evidence {
    pub authentication: bool,
    pub started: bool,
    pub version_matched: bool,
    pub version_rejected: bool,
    pub video_sync: bool,
}

/// Per-round CMD observations shared with the watchdog and transport supervisors.
/// They never require the session lock or access to handshake secrets.
#[derive(Default)]
pub(super) struct Progress {
    pub authentication: AtomicBool,
    pub started: AtomicBool,
    pub version_matched: AtomicBool,
    pub version_rejected: AtomicBool,
}

impl Progress {
    pub fn evidence(&self) -> Evidence {
        Evidence {
            authentication: self.authentication.load(Ordering::Relaxed),
            started: self.started.load(Ordering::Relaxed),
            version_matched: self.version_matched.load(Ordering::Relaxed),
            version_rejected: self.version_rejected.load(Ordering::Relaxed),
            video_sync: false,
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum RoundEnd {
    Command,
    Transport,
    VideoStartup,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ProbeStage {
    Probing,
    PlainConfirmed,
    AfterPlain32 { recheck: CarLifeDialect, on_exhaustion: CarLifeDialect },
    RecheckingPlain { on_exhaustion: CarLifeDialect },
    Plain32Backoff { recheck: Option<CarLifeDialect>, until: Instant },
    RestartProbe,
    ExhaustedAfterPlain32 { retry_at: Instant },
    EncryptedHandshake,
    Exhausted,
}

pub(super) struct Selection {
    automatic: bool,
    current: CarLifeDialect,
    stage: ProbeStage,
    plain_30_failures: u8,
    plain_30_matched: bool,
    confirmed_plain_32_failures: u8,
    delayed_recovery_used: bool,
    missed_restart_recovery_used: bool,
}

impl Selection {
    pub fn new(requested: CarLifeDialect) -> Self {
        let automatic = requested == CarLifeDialect::SystemAuto;
        Self {
            automatic,
            current: if automatic {
                CarLifeDialect::CarWithEncrypted41
            } else {
                requested
            },
            stage: ProbeStage::Probing,
            plain_30_failures: 0,
            plain_30_matched: false,
            confirmed_plain_32_failures: 0,
            delayed_recovery_used: false,
            missed_restart_recovery_used: false,
        }
    }

    pub fn current(&self) -> CarLifeDialect {
        self.current
    }

    pub(super) fn reconnect_delay(&self) -> Duration {
        let normal = Duration::from_secs(1);
        match self.stage {
            ProbeStage::Plain32Backoff { until, .. } => {
                until.saturating_duration_since(Instant::now()).max(normal)
            }
            ProbeStage::ExhaustedAfterPlain32 { .. } => PLAIN32_RECOVERY_BACKOFF,
            _ => normal,
        }
    }

    pub(super) fn command_listener_unavailable(&mut self) {
        if matches!(
            self.stage,
            ProbeStage::AfterPlain32 { .. }
                | ProbeStage::RecheckingPlain { .. }
                | ProbeStage::Plain32Backoff { recheck: Some(_), .. }
                | ProbeStage::RestartProbe
                | ProbeStage::ExhaustedAfterPlain32 { .. }
        ) {
            // A replacement listener gets a fresh probe. Subsequent refusals
            // during the same outage leave its backoff deadline alone.
            self.delayed_recovery_used = false;
            self.missed_restart_recovery_used = false;
            let until = Instant::now() + PLAIN32_RECOVERY_BACKOFF;
            self.select(CarLifeDialect::CarWithEncrypted41, ProbeStage::Plain32Backoff { recheck: None, until });
        }
    }

    pub fn round_ended(&mut self, evidence: Evidence, cause: RoundEnd) -> bool {
        if !self.automatic {
            return false;
        }
        if evidence.authentication {
            return self.select(CarLifeDialect::CarWithEncrypted41, ProbeStage::EncryptedHandshake);
        }

        if self.current == CarLifeDialect::CarWithEncrypted41 {
            if !evidence.version_rejected && cause != RoundEnd::Command {
                return false;
            }
            return match self.stage {
                ProbeStage::Probing => self.select(CarLifeDialect::SystemPlain30, ProbeStage::Probing),
                ProbeStage::AfterPlain32 { recheck, on_exhaustion } => {
                    self.recheck_plain(recheck, on_exhaustion)
                }
                ProbeStage::Plain32Backoff { recheck, until } if Instant::now() >= until => {
                    match recheck {
                        Some(dialect) => {
                            self.delayed_recovery_used = true;
                            self.recheck_plain(dialect, dialect)
                        }
                        None => self.begin_restart_probe(),
                    }
                }
                ProbeStage::ExhaustedAfterPlain32 { retry_at }
                    if evidence.version_rejected
                        && !self.missed_restart_recovery_used
                        && Instant::now() >= retry_at =>
                {
                    // A short listener restart can go unnoticed between dials.
                    // One fresh protocol rejection buys one bounded restart probe.
                    self.missed_restart_recovery_used = true;
                    self.begin_restart_probe()
                }
                _ => false,
            };
        }

        if evidence.video_sync {
            // A configured picture confirms the dialect and renews recovery.
            self.stage = ProbeStage::PlainConfirmed;
            self.confirmed_plain_32_failures = 0;
            self.delayed_recovery_used = false;
            self.missed_restart_recovery_used = false;
        }
        if evidence.version_rejected {
            if self.stage == ProbeStage::RestartProbe {
                if self.current == CarLifeDialect::SystemPlain30 {
                    return self.select(CarLifeDialect::SystemPlain32, ProbeStage::RestartProbe);
                }
                return self.exhaust_after_plain32(CarLifeDialect::SystemPlain32, true);
            }
            if self.current == CarLifeDialect::SystemPlain32 {
                return self.fall_back_from_plain32(CarLifeDialect::SystemPlain30, CarLifeDialect::SystemPlain30);
            }
            if self.stage == ProbeStage::Probing {
                return self.select(CarLifeDialect::SystemPlain32, ProbeStage::Probing);
            }
            return self.exhaust_plain30(true);
        }

        if self.current == CarLifeDialect::SystemPlain30 {
            self.plain_30_matched |= evidence.version_matched;
        }
        // Ordinary lane failures and rounds with a picture keep the dialect.
        if cause == RoundEnd::Transport
            || evidence.video_sync
            || matches!(self.stage, ProbeStage::ExhaustedAfterPlain32 { .. })
        {
            return false;
        }
        if self.stage == ProbeStage::PlainConfirmed {
            if self.current != CarLifeDialect::SystemPlain32 {
                return false;
            }
            self.confirmed_plain_32_failures += 1;
            if self.confirmed_plain_32_failures < CONFIRMED_PLAIN32_STARTUP_LIMIT {
                return false;
            }
            return self.fall_back_from_plain32(CarLifeDialect::SystemPlain30, CarLifeDialect::SystemPlain32);
        }
        if self.current == CarLifeDialect::SystemPlain32 {
            if self.stage == ProbeStage::RestartProbe {
                return self.exhaust_after_plain32(CarLifeDialect::SystemPlain32, false);
            }
            return self.fall_back_from_plain32(CarLifeDialect::SystemPlain32, CarLifeDialect::SystemPlain32);
        }
        self.plain_30_failures += 1;
        if self.plain_30_failures < 3 {
            return false;
        }
        // The new listener may close 3.0 without ever returning a match status.
        if self.stage == ProbeStage::RestartProbe {
            return self.select(CarLifeDialect::SystemPlain32, ProbeStage::RestartProbe);
        }
        // Some phones only check the major version in their match reply, but
        // still select a different feature exchange based on the minor version.
        if self.plain_30_matched && self.stage == ProbeStage::Probing {
            return self.select(CarLifeDialect::SystemPlain32, ProbeStage::Probing);
        }
        self.exhaust_plain30(false)
    }

    fn begin_restart_probe(&mut self) -> bool {
        self.plain_30_failures = 0;
        self.plain_30_matched = false;
        self.delayed_recovery_used = true;
        self.select(CarLifeDialect::SystemPlain30, ProbeStage::RestartProbe)
    }

    fn recheck_plain(&mut self, dialect: CarLifeDialect, on_exhaustion: CarLifeDialect) -> bool {
        self.plain_30_failures = 0;
        self.plain_30_matched = false;
        self.select(dialect, ProbeStage::RecheckingPlain { on_exhaustion })
    }

    fn exhaust_plain30(&mut self, rejected: bool) -> bool {
        match self.stage {
            ProbeStage::RecheckingPlain { on_exhaustion } => {
                self.exhaust_after_plain32(on_exhaustion, rejected)
            }
            ProbeStage::ExhaustedAfterPlain32 { .. } => {
                self.exhaust_after_plain32(CarLifeDialect::SystemPlain30, rejected)
            }
            _ => self.select(CarLifeDialect::CarWithEncrypted41, ProbeStage::Exhausted),
        }
    }

    fn fall_back_from_plain32(&mut self, recheck: CarLifeDialect, on_exhaustion: CarLifeDialect) -> bool {
        // An explicit refusal can still try 3.0 after a failed 3.2 recheck.
        if matches!(self.stage, ProbeStage::RecheckingPlain { .. }) && recheck == CarLifeDialect::SystemPlain32 {
            return self.exhaust_after_plain32(on_exhaustion, false);
        }
        self.select(CarLifeDialect::CarWithEncrypted41, ProbeStage::AfterPlain32 { recheck, on_exhaustion })
    }

    fn exhaust_after_plain32(&mut self, recheck: CarLifeDialect, rejected: bool) -> bool {
        if self.delayed_recovery_used {
            // Reconnect in place until a picture, an explicit rejection or a
            // listener restart gives us new evidence. Avoid alternating modes.
            let dialect = if rejected { CarLifeDialect::CarWithEncrypted41 } else { self.current };
            let retry_at = Instant::now() + PLAIN32_RECOVERY_BACKOFF;
            return self.select(dialect, ProbeStage::ExhaustedAfterPlain32 { retry_at });
        }
        let until = Instant::now() + PLAIN32_RECOVERY_BACKOFF;
        self.select(CarLifeDialect::CarWithEncrypted41, ProbeStage::Plain32Backoff { recheck: Some(recheck), until })
    }

    fn select(&mut self, dialect: CarLifeDialect, stage: ProbeStage) -> bool {
        let changed = self.current != dialect;
        self.stage = stage;
        self.current = dialect;
        changed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use CarLifeDialect::*;

    #[test]
    fn neutral_round_failures_preserve_the_candidate_and_plain_probe_budget() {
        let mut choice = Selection::new(SystemAuto);
        for _ in 0..4 {
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::VideoStartup));
        }
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        for _ in 0..4 {
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
        }
        assert_eq!(choice.current(), SystemPlain30);
    }

    #[test]
    fn auto_trials_are_bounded_and_authenticated_rounds_never_downgrade() {
        let mut choice = Selection::new(SystemAuto);
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(
            Evidence {
                authentication: true,
                ..version_rejection()
            },
            RoundEnd::Command,
        ));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }

    #[test]
    fn failed_authentication_is_not_a_plaintext_fallback_signal() {
        let mut choice = Selection::new(SystemAuto);
        assert!(!choice.round_ended(
            Evidence {
                authentication: true,
                ..Default::default()
            },
            RoundEnd::Command,
        ));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }

    #[test]
    fn explicit_modes_do_not_probe() {
        for mode in [SystemPlain30, SystemPlain32, CarWithEncrypted41] {
            let mut explicit = Selection::new(mode);
            assert!(!explicit.round_ended(
                Evidence {
                    authentication: true,
                    ..Default::default()
                },
                RoundEnd::Command,
            ));
            assert!(!explicit.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(explicit.current(), mode);
        }
    }

    #[test]
    fn unconfirmed_plain_30_version_rejection_probes_plain_32() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[test]
    fn failed_plain_32_startup_can_retry_after_unauthenticated_41() {
        for cause in [RoundEnd::Command, RoundEnd::VideoStartup] {
            let mut choice = Selection::new(SystemAuto);
            assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), SystemPlain32);
            for _ in 0..4 {
                assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
            }
            assert_eq!(choice.current(), SystemPlain32);
            assert!(choice.round_ended(version_match(), cause));
            assert_eq!(choice.current(), CarWithEncrypted41);
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), SystemPlain32);

            assert!(choice.round_ended(version_match(), cause));
            assert_eq!(choice.current(), CarWithEncrypted41);
            assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        }
    }

    #[test]
    fn plain_32_rejection_recovers_plain_30_with_a_fresh_startup_budget() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        for _ in 0..2 {
            assert!(!choice.round_ended(version_match(), RoundEnd::VideoStartup));
        }
        assert!(choice.round_ended(version_match(), RoundEnd::VideoStartup));
        assert_eq!(choice.current(), SystemPlain32);
        assert!(!choice.round_ended(
            Evidence { video_sync: true, ..version_match() },
            RoundEnd::Command,
        ));

        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);

        for _ in 0..2 {
            assert!(!choice.round_ended(version_match(), RoundEnd::VideoStartup));
        }
        assert!(choice.round_ended(version_match(), RoundEnd::VideoStartup));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
    }

    #[test]
    fn rejected_plain_32_recheck_can_try_plain_30_without_cycling() {
        let mut choice = Selection::new(SystemAuto);
        for dialect in [SystemPlain30, SystemPlain32] {
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), dialect);
        }
        assert!(choice.round_ended(version_match(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);

        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
    }

    #[test]
    fn encrypted_handshake_after_plain_32_failure_prevents_recovery() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert!(choice.round_ended(version_match(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(
            Evidence { authentication: true, ..version_rejection() },
            RoundEnd::Command,
        ));
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
    }

    #[test]
    fn confirmed_plain_32_rechecks_plain_30_after_consecutive_rounds_without_sync() {
        let mut choice = probing_plain_32();
        let synced = Evidence { video_sync: true, started: true, ..version_match() };
        assert!(!choice.round_ended(synced, RoundEnd::Command));
        for _ in 0..2 {
            assert!(!choice.round_ended(version_match(), RoundEnd::VideoStartup));
        }
        assert!(!choice.round_ended(synced, RoundEnd::Command));
        for cause in [RoundEnd::Command, RoundEnd::Transport, RoundEnd::VideoStartup] {
            assert!(!choice.round_ended(version_match(), cause));
        }
        assert_eq!(choice.current(), SystemPlain32);
        assert!(choice.round_ended(version_match(), RoundEnd::VideoStartup));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
    }

    #[tokio::test(start_paused = true)]
    async fn exhausted_plain_30_can_recover_previously_confirmed_plain_32() {
        let mut choice = probing_plain_32();
        assert!(!choice.round_ended(
            Evidence { video_sync: true, ..version_match() },
            RoundEnd::Command,
        ));
        for _ in 0..2 {
            assert!(!choice.round_ended(version_match(), RoundEnd::VideoStartup));
        }
        assert!(choice.round_ended(version_match(), RoundEnd::VideoStartup));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);

        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[tokio::test(start_paused = true)]
    async fn exhausted_plain_32_has_one_delayed_recovery_while_listener_stays_up() {
        let mut choice = exhaust_plain_32();
        assert_eq!(choice.reconnect_delay(), PLAIN32_RECOVERY_BACKOFF);
        for _ in 0..14 {
            tokio::time::advance(Duration::from_secs(1)).await;
            assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        }
        tokio::time::advance(Duration::from_secs(1)).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);

        assert!(!choice.round_ended(version_match(), RoundEnd::Command));
        assert_eq!(choice.reconnect_delay(), PLAIN32_RECOVERY_BACKOFF);
        for _ in 0..4 {
            tokio::time::advance(Duration::from_secs(60)).await;
            assert!(!choice.round_ended(version_match(), RoundEnd::Command));
            assert_eq!(choice.current(), SystemPlain32);
        }
        assert!(!choice.round_ended(Evidence { video_sync: true, ..version_match() }, RoundEnd::Command));
        assert_eq!(choice.reconnect_delay(), Duration::from_secs(1));
    }

    #[tokio::test(start_paused = true)]
    async fn refused_command_listener_renews_delayed_plain_32_recovery() {
        let mut choice = exhaust_plain_32();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert!(!choice.round_ended(version_match(), RoundEnd::Command));

        choice.command_listener_unavailable();
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[tokio::test(start_paused = true)]
    async fn listener_refusal_during_a_recheck_renews_the_recovery_budget() {
        let mut choice = exhaust_plain_32();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);

        choice.command_listener_unavailable();
        assert_eq!(choice.current(), CarWithEncrypted41);
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[tokio::test(start_paused = true)]
    async fn restarted_listener_can_try_plain_32_after_old_listener_rejected_every_version() {
        let mut choice = probing_plain_32();
        for dialect in [CarWithEncrypted41, SystemPlain30, CarWithEncrypted41] {
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), dialect);
        }
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));

        choice.command_listener_unavailable();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[tokio::test(start_paused = true)]
    async fn restarted_listener_preserves_plain_32_recovery_if_plain_30_closes_before_matching() {
        let mut choice = exhaust_plain_32();
        choice.command_listener_unavailable();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        for _ in 0..2 {
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        }
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);

        choice.command_listener_unavailable();
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
    }

    #[tokio::test(start_paused = true)]
    async fn missed_restart_gets_one_probe_after_backoff_and_new_41_rejection() {
        let mut choice = exhaust_with_all_versions_rejected().await;
        assert_eq!(choice.reconnect_delay(), PLAIN32_RECOVERY_BACKOFF);
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF - Duration::from_secs(1)).await;
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        tokio::time::advance(Duration::from_secs(1)).await;
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        for _ in 0..4 {
            tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
            assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), CarWithEncrypted41);
        }
    }

    #[tokio::test(start_paused = true)]
    async fn encrypted_handshake_preempts_missed_restart_recovery() {
        let mut choice = exhaust_with_all_versions_rejected().await;
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(!choice.round_ended(
            Evidence { authentication: true, ..version_rejection() },
            RoundEnd::Command,
        ));
        choice.command_listener_unavailable();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert_eq!(choice.reconnect_delay(), Duration::from_secs(1));
    }

    #[tokio::test(start_paused = true)]
    async fn encrypted_handshake_during_backoff_cancels_plain_32_recovery() {
        let mut choice = exhaust_plain_32();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(!choice.round_ended(
            Evidence { authentication: true, ..version_rejection() },
            RoundEnd::Command,
        ));
        choice.command_listener_unavailable();
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert_eq!(choice.reconnect_delay(), Duration::from_secs(1));
    }

    #[test]
    fn confirmed_plain_modes_keep_their_version_on_transport_and_initial_startup_failures() {
        for mode in [SystemPlain30, SystemPlain32] {
            let mut choice = Selection::new(SystemAuto);
            assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
            if mode == SystemPlain32 {
                assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            }
            assert_eq!(choice.current(), mode);
            assert!(!choice.round_ended(
                Evidence {
                    started: true,
                    video_sync: true,
                    ..Default::default()
                },
                RoundEnd::Transport,
            ));
            for _ in 0..2 {
                assert!(!choice.round_ended(Evidence::default(), RoundEnd::VideoStartup));
                assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
            }
            if mode == SystemPlain30 {
                for _ in 0..4 {
                    assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
                }
            }
            assert_eq!(choice.current(), mode);
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), CarWithEncrypted41);
            if mode == SystemPlain30 {
                assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
            }
        }
    }

    #[test]
    fn first_plain_30_picture_and_rejection_in_the_same_round_pin_encrypted() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(
            Evidence {
                started: true,
                video_sync: true,
                ..version_rejection()
            },
            RoundEnd::Command,
        ));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }

    #[test]
    fn encrypted_evidence_during_plain_32_pins_encrypted_even_on_transport_failure() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
        assert!(choice.round_ended(
            Evidence {
                authentication: true,
                ..Default::default()
            },
            RoundEnd::Transport,
        ));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }

    #[test]
    fn unconfirmed_plain_30_has_three_startup_trials() {
        let mut choice = Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::VideoStartup));
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        assert_eq!(choice.reconnect_delay(), Duration::from_secs(1));
        choice.command_listener_unavailable();
        assert!(!choice.round_ended(version_rejection(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }

    fn version_rejection() -> Evidence {
        Evidence {
            version_rejected: true,
            ..Default::default()
        }
    }

    fn version_match() -> Evidence {
        Evidence { version_matched: true, ..Evidence::default() }
    }

    fn probing_plain_32() -> Selection {
        let mut choice = Selection::new(SystemAuto);
        for dialect in [SystemPlain30, SystemPlain32] {
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), dialect);
        }
        choice
    }

    fn exhaust_plain_32() -> Selection {
        let mut choice = probing_plain_32();
        assert!(choice.round_ended(version_match(), RoundEnd::Command));
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain32);
        assert!(choice.round_ended(version_match(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        choice
    }

    async fn exhaust_with_all_versions_rejected() -> Selection {
        let mut choice = probing_plain_32();
        for dialect in [CarWithEncrypted41, SystemPlain30, CarWithEncrypted41] {
            assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
            assert_eq!(choice.current(), dialect);
        }
        tokio::time::advance(PLAIN32_RECOVERY_BACKOFF).await;
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), SystemPlain30);
        assert!(choice.round_ended(version_rejection(), RoundEnd::Command));
        assert_eq!(choice.current(), CarWithEncrypted41);
        choice
    }
}
