use tokio::sync::watch;

#[derive(Clone, Copy, Default, Debug)]
pub(crate) struct Ownership {
    pub(crate) owned: bool,
    pub(crate) losses: u64,
    pub(crate) lost_at: Option<std::time::Instant>,
    pub(crate) regained_at: Option<std::time::Instant>,
}

impl Ownership {
    pub(crate) fn accepts_pcm(self, received_at: std::time::Instant) -> bool {
        self.owned && self.regained_at.is_none_or(|regain| received_at >= regain)
    }
}

#[derive(Clone)]
pub(crate) struct BufferedOwnership(watch::Sender<Ownership>);

impl Default for BufferedOwnership {
    fn default() -> Self {
        Self(watch::channel(Ownership::default()).0)
    }
}

impl BufferedOwnership {
    pub(crate) fn subscribe(&self) -> watch::Receiver<Ownership> { self.0.subscribe() }

    pub(crate) fn publish(&self, owned: bool) {
        self.0.send_if_modified(|state| {
            if state.owned == owned { return false; }
            if !owned {
                state.losses = state.losses.wrapping_add(1);
                state.lost_at = Some(std::time::Instant::now());
            }
            else if state.losses != 0 { state.regained_at = Some(std::time::Instant::now()); }
            state.owned = owned;
            true
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_and_immediate_regain_remain_visible_to_a_late_reader() {
        let events = BufferedOwnership::default();
        events.publish(true);
        let rx = events.subscribe();
        let old_pcm = std::time::Instant::now() - std::time::Duration::from_millis(1);
        assert!(rx.borrow().owned);
        events.publish(false);
        events.publish(false);
        events.publish(true);
        assert!(rx.borrow().owned);
        assert_eq!(rx.borrow().losses, 1);
        assert!(!rx.borrow().accepts_pcm(old_pcm));
        assert!(rx.borrow().accepts_pcm(std::time::Instant::now()));
        assert_eq!(BufferedOwnership::default().subscribe().borrow().losses, 0);
    }
}
