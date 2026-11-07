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
