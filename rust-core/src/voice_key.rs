//! Internal key messages, not wire usages. Siri actions: prewarm=1, down=2, up=3.
pub const HID_UP: u32 = 0x100cf;
pub const RESET: u32 = 0x20000;
pub fn message(action: i64) -> Option<u32> { match action {1..=3=>Some(0x20000+action as u32),_=>None} }
pub fn action(key:u32)->Option<u8> {match key {0xcf=>Some(2),HID_UP=>Some(3),0x20001..=0x20003=>Some((key-0x20000) as u8),_=>None}}
#[derive(Default)]
pub struct VoiceKey { down:Option<u64> }
impl VoiceKey {
    pub fn reset(&mut self) {self.down=None;}
    pub fn receive(&mut self,action:u8,now:u64)->bool {
        if self.down.is_some_and(|at| now.saturating_sub(at)>=15_000) {self.reset();}
        match action {
            2 if self.down.is_none()=>{self.down=Some(now);true},
            3=>{self.down=None;false},
            _=>false,
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn prewarm_and_duplicate_down_never_launch_twice() {
        let mut key=VoiceKey::default();
        assert_eq!(key.receive(1,0),false);assert_eq!(key.receive(2,10),true);
        assert_eq!(key.receive(2,30),false);assert_eq!(key.receive(3,300),false);
        assert_eq!(key.receive(3,301),false);assert_eq!(key.receive(2,400),true);
    }
    #[test] fn short_and_long_release_both_keep_listening() {
        let mut key=VoiceKey::default();key.receive(2,0);
        assert_eq!(key.receive(3,1499),false);key.receive(2,2000);
        assert_eq!(key.receive(3,3500),false);
        assert_eq!(key.receive(3,3501),false);
    }
    #[test] fn expired_or_reset_press_does_not_stop_a_new_session() {
        let mut key=VoiceKey::default();key.receive(2,0);
        assert_eq!(key.receive(3,15001),false);key.receive(2,16000);key.reset();
        assert_eq!(key.receive(3,18000),false);
        for invalid in [i64::MIN,-1,0,4,i64::MAX] {assert_eq!(message(invalid),None);}
        assert_eq!(action(0xcf),Some(2));assert_eq!(action(HID_UP),Some(3));
    }
}

/// Generation-scoped iAP2 input into the existing AirPlay voice queue.
pub struct Input {
    pub token: u64,
    sender: tokio::sync::mpsc::UnboundedSender<u32>,
}
impl Input {
    pub fn new(sender: tokio::sync::mpsc::UnboundedSender<u32>) -> Self {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        Self { token: NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed), sender }
    }
    pub fn send(&self, token: u64, action: i32) -> bool {
        token == self.token && matches!(action, 2 | 3) &&
            self.sender.send(message(i64::from(action)).unwrap()).is_ok()
    }
}

#[cfg(test)]
mod input_tests {
    use super::*;
    #[test] fn iap2_and_airplay_share_deduplication_and_stale_engines_cannot_inject() {
        let (tx,mut rx)=tokio::sync::mpsc::unbounded_channel();
        let input=Input::new(tx.clone());let next=Input::new(tx.clone());
        assert!(!next.send(input.token,2));
        for action in [-1,0,1,4] {assert!(!input.send(input.token,action));}
        assert!(rx.try_recv().is_err());
        assert!(input.send(input.token,2));
        tx.send(message(2).unwrap()).unwrap(); // same press via requestSiri
        assert!(input.send(input.token,3));
        tx.send(HID_UP).unwrap();
        assert!(input.send(input.token,2));
        let mut voice=VoiceKey::default();let mut starts=0;
        while let Ok(key)=rx.try_recv(){if voice.receive(action(key).unwrap(),100){starts+=1;}}
        assert_eq!(starts,2);
        drop(rx);assert!(!input.send(input.token,3));
    }
}
