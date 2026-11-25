//! Per-source protocol probing; candidates are not confirmations.
use super::CarLifeDialect;
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Copy, Default)]
pub(super) struct Evidence {
    pub authentication: bool,
    pub started: bool,
    pub version_rejected: bool,
    pub video_sync: bool,
}

/// Per-round CMD observations shared with the watchdog and transport supervisors.
/// They never require the session lock or access to handshake secrets.
#[derive(Default)]
pub(super) struct Progress {
    pub authentication: AtomicBool,
    pub started: AtomicBool,
    pub version_rejected: AtomicBool,
}

impl Progress {
    pub fn evidence(&self) -> Evidence {
        Evidence {
            authentication: self.authentication.load(Ordering::Relaxed),
            started: self.started.load(Ordering::Relaxed),
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

pub(super) struct Selection {
    automatic: bool,
    current: CarLifeDialect,
    locked: bool,
    tried_plain: bool,
    plain_failures: u8,
}

impl Selection {
    pub fn new(requested: CarLifeDialect) -> Self {
        let automatic=requested==CarLifeDialect::SystemAuto;
        Self { automatic, current:if automatic {CarLifeDialect::CarWithEncrypted41}else{requested},
            locked:false,tried_plain:false,plain_failures:0 }
    }
    pub fn current(&self) -> CarLifeDialect { self.current }
    pub fn round_ended(&mut self, evidence: Evidence, cause: RoundEnd) -> bool {
        if !self.automatic {return false;}
        if evidence.authentication {
            self.locked=true;
            let changed=self.current!=CarLifeDialect::CarWithEncrypted41;
            self.current=CarLifeDialect::CarWithEncrypted41;
            return changed;
        }
        if self.current == CarLifeDialect::SystemPlain30 && evidence.video_sync && !evidence.version_rejected {
            self.locked = true;
            return false;
        }
        // Ordinary lane failures are neutral. An unconfirmed plaintext round
        // that cannot produce its first picture consumes one plaintext trial.
        if !evidence.version_rejected && (cause == RoundEnd::Transport ||
            cause == RoundEnd::VideoStartup && self.current == CarLifeDialect::CarWithEncrypted41) {
            return false;
        }
        if self.current==CarLifeDialect::SystemPlain30 {
            if !self.locked {self.plain_failures=self.plain_failures.saturating_add(1);}
            if evidence.version_rejected || !self.locked && self.plain_failures>=3 {
                self.current=CarLifeDialect::CarWithEncrypted41;
                self.locked=true;
                return true;
            }
            return false;
        }
        self.try_plain()
    }
    fn try_plain(&mut self) -> bool {
        if !self.automatic || self.locked || self.tried_plain {return false;}
        self.tried_plain=true;
        self.current=CarLifeDialect::SystemPlain30;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use CarLifeDialect::*;
    #[test]
    fn transport_failure_preserves_encrypted_candidate_and_plain_probe_budget() {
        let mut choice = Selection::new(SystemAuto);
        for _ in 0..4 {
            assert!(!choice.round_ended(Evidence::default(), RoundEnd::Transport));
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
        let mut choice=Selection::new(SystemAuto);
        assert_eq!(choice.current(),CarWithEncrypted41);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert_eq!(choice.current(),SystemPlain30);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(Evidence {authentication:true,..Default::default()}, RoundEnd::Command));
        assert_eq!(choice.current(),CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }
    #[test]
    fn failed_authentication_is_not_a_plaintext_fallback_signal() {
        let mut choice=Selection::new(SystemAuto);
        assert!(!choice.round_ended(Evidence {authentication:true,..Default::default()}, RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }
    #[test]
    fn confirmed_plain_picture_locks_plain_and_explicit_modes_do_not_probe() {
        let mut choice=Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence {started:true,video_sync:true,..Default::default()}, RoundEnd::Command));
        assert_eq!(choice.current(),SystemPlain30);
        for mode in [SystemPlain30,CarWithEncrypted41] {
            let mut explicit=Selection::new(mode);
            assert!(!explicit.round_ended(Evidence {authentication:true,..Default::default()}, RoundEnd::Command));
            assert_eq!(explicit.current(),mode);
        }
    }
    #[test]
    fn explicit_plain_version_rejection_returns_to_encrypted_without_oscillation() {
        let mut choice=Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(Evidence {version_rejected:true,..Default::default()}, RoundEnd::Command));
        assert_eq!(choice.current(),CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
    }
    #[test]
    fn unconfirmed_plain_trials_are_bounded_but_a_successful_plain_engine_reconnects() {
        let mut choice=Selection::new(SystemAuto);
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert!(choice.round_ended(Evidence::default(), RoundEnd::Command));
        assert_eq!(choice.current(),CarWithEncrypted41);
        assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));
        let mut choice=Selection::new(SystemAuto);
        choice.round_ended(Evidence::default(), RoundEnd::Command);
        choice.round_ended(Evidence {started:true,video_sync:true,..Default::default()}, RoundEnd::Command);
        for _ in 0..10 {assert!(!choice.round_ended(Evidence::default(), RoundEnd::Command));}
        assert_eq!(choice.current(),SystemPlain30);
    }
}
