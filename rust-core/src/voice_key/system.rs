//! System CarLife+ phone-microphone voice key policy, independent of legacy sources.
#[derive(Default)]
pub struct SystemVoiceKey {
    pressed: Option<u64>,
    pending: Option<u64>,
    lease: Option<u64>,
    triggered: bool,
    active: bool,
}
impl SystemVoiceKey {
    pub fn reset(&mut self) { *self=Self::default(); }
    pub fn receive(&mut self, action: u8, now: u64) -> Vec<u64> {
        match action {
            1 => {
                // Duplicate reports from another input transport retain this press.
                if self.pressed.is_none() { self.pressed=Some(now);self.triggered=false; }
            }
            2 if !self.triggered => {
                // HID and some receivers omit prewarm; give them the same hold delay.
                let pressed=*self.pressed.get_or_insert(now);
                self.pending=Some(pressed.saturating_add(500));
            }
            3 => { self.pressed=None;self.pending=None;self.triggered=false; }
            _=>{}
        }
        self.advance(now)
    }
    pub fn advance(&mut self, now: u64) -> Vec<u64> {
        let mut keys=Vec::new();
        if self.lease.is_some_and(|at| now>=at) {
            self.lease=None;
            if self.active {keys.push(0x22);self.active=false;}
        }
        if self.pending.is_some_and(|at| now>=at) {
            self.pending=None;
            if self.pressed.is_some() && !self.triggered {
                if self.active { keys.push(0x22); }
                keys.push(0x21);self.active=true;self.triggered=true;
                self.lease=Some(now.saturating_add(60_000));
            }
        }
        keys
    }
    pub fn deadline(&self) -> Option<u64> { self.pending.into_iter().chain(self.lease).min() }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn short_press_is_ignored_and_hid_down_alone_can_hold() {
        let mut key=SystemVoiceKey::default();
        assert!(key.receive(2,0).is_empty());
        assert_eq!(key.deadline(),Some(500));
        assert!(key.receive(3,499).is_empty());
        assert!(key.advance(500).is_empty());
        key.receive(2,1000);
        assert!(key.advance(1499).is_empty());
        assert_eq!(key.advance(1500),vec![0x21]);
    }
    #[test]
    fn prewarm_and_duplicate_activation_do_not_restart_the_same_press() {
        let mut key=SystemVoiceKey::default();
        key.receive(1,0);assert_eq!(key.deadline(),None);
        key.receive(2,400);key.receive(2,450);
        assert_eq!(key.advance(500),vec![0x21]);
        assert!(key.receive(2,600).is_empty());
        assert!(key.receive(3,1000).is_empty());
        assert_eq!(key.deadline(),Some(60500));
    }
    #[test]
    fn another_hold_stops_previous_session_then_starts_in_order() {
        let mut key=SystemVoiceKey::default();
        key.receive(2,0);assert_eq!(key.advance(500),vec![0x21]);
        key.receive(3,600);key.receive(2,1000);
        assert_eq!(key.advance(1500),vec![0x22,0x21]);
        assert_eq!(key.deadline(),Some(61500));
        assert!(key.advance(60500).is_empty());
        assert_eq!(key.advance(61500),vec![0x22]);
        assert!(key.advance(62000).is_empty());
    }
    #[test]
    fn reset_discards_pending_and_old_leases() {
        let mut key=SystemVoiceKey::default();
        key.receive(2,0);key.reset();assert!(key.advance(500).is_empty());
        key.receive(2,1000);assert_eq!(key.advance(1500),vec![0x21]);
        key.reset();assert_eq!(key.deadline(),None);assert!(key.advance(61500).is_empty());
    }
}
