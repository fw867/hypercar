//! Controller-side resource snapshots for inbound changeModes (CatPlay resource semantics).
use crate::bplist::{self, Value};

#[derive(Clone, Copy, Debug)]
struct Resource { permanent: i64, current: i64, borrows: u32 }
impl Default for Resource {
    fn default() -> Self { Self { permanent: 1, current: 1, borrows: 0 } }
}

#[derive(Clone, Default, Debug)]
pub struct Modes {
    resources: [Resource; 2],
    apps: [i64; 3],
    speech: i64,
}

impl Modes {
    pub fn initial() -> Self { Self { speech: -1, ..Self::default() } }
    pub fn box_initial() -> Self {
        let mut value = Self::initial();
        value.resources[1] = Resource { permanent: 2, current: 2, borrows: 0 };
        value
    }
    pub fn claim_audio(&mut self) { self.resources[1] = Resource::default(); }
    pub fn audio_owned(&self) -> bool { self.resources[1].current == 1 }
    pub fn screen_owned(&self) -> bool { self.resources[0].current == 1 }

    pub fn snapshot(&self) -> Value {
        Value::dict(vec![
            ("resources", Value::array(self.resources.iter().enumerate().map(|(i, r)| Value::dict(vec![
                ("resourceID", Value::int(i as i64 + 1)), ("entity", Value::int(r.current)),
                ("permanentEntity", Value::int(r.permanent)),
            ])).collect())),
            ("appStates", Value::array(self.apps.iter().enumerate().map(|(i, entity)| {
                let mut fields = vec![("appStateID", Value::int(i as i64 + 1)), ("entity", Value::int(*entity))];
                if i == 0 { fields.push(("speechMode", Value::int(self.speech))); }
                Value::dict(fields)
            }).collect())),
        ])
    }

    pub fn declaration(&self) -> Vec<u8> {
        bplist::encode(&Value::dict(vec![("type", Value::str("modesChanged")), ("params", self.snapshot())]))
    }

    /// Apply only the requested resource. Car borrow/return must not steal unrelated audio/screen.
    pub fn change(&mut self, params: &Value) -> bool {
        let mut next = self.clone();
        let Some(_) = params.as_dict() else { return false; };
        if let Some(values) = params.get("initialPermanentEntity") {
            let Some(values) = values.as_array() else { return false; };
            if values.len() > 2 { return false; }
            for v in values {
                let (Some(id @ 1..=2), Some(entity @ 1..=2)) = (integer(v, "resourceID"), integer(v, "permanentEntity")) else { return false; };
                let r = &mut next.resources[id as usize - 1];
                r.permanent = entity;
                if r.borrows == 0 { r.current = entity; }
            }
        }
        if let Some(values) = params.get("resources") {
            let Some(values) = values.as_array() else { return false; };
            if values.len() > 8 { return false; }
            for v in values {
                let (Some(id @ 1..=2), Some(kind @ 1..=4)) = (integer(v, "resourceID"), integer(v, "transferType")) else { return false; };
                let r = &mut next.resources[id as usize - 1];
                match kind {
                    1 => { r.permanent = 2; r.current = 2; r.borrows = 0; }
                    2 => { if r.permanent == 2 { *r = Resource { permanent: 0, current: 0, borrows: 0 }; } }
                    3 => { if r.permanent != 2 { r.borrows = r.borrows.saturating_add(1); r.current = 2; } }
                    4 => { r.borrows = r.borrows.saturating_sub(1); if r.borrows == 0 { r.current = r.permanent; } }
                    _ => unreachable!(),
                }
            }
        }
        if let Some(values) = params.get("appStates") {
            let Some(values) = values.as_array() else { return false; };
            if values.len() > 8 { return false; }
            for v in values {
                let Some(id @ 1..=3) = integer(v, "appStateID") else { return false; };
                if id == 1 {
                    let Some(mode @ -1..=2) = integer(v, "speechMode") else { return false; };
                    next.speech = mode;
                    next.apps[0] = if mode <= 0 { 0 } else { 2 };
                } else if let Some(active) = v.get("state").and_then(Value::as_bool) {
                    next.apps[id as usize - 1] = if active { 2 } else { 0 };
                } else { return false; }
            }
        }
        *self = next;
        true
    }

    pub fn response(&mut self, command: &Value) -> Option<Vec<u8>> {
        if command.get("type").and_then(Value::as_str) != Some("changeModes") { return None; }
        let accepted = command.get("params").map(|p| self.change(p)).unwrap_or(false);
        crate::emit_log(&format!("MODES: changeModes accepted={accepted} screen={}/{} audio={}/{}",
            self.resources[0].current, self.resources[0].permanent, self.resources[1].current, self.resources[1].permanent));
        Some(bplist::encode(&Value::dict(vec![
            ("status", Value::int(if accepted { 0 } else { 1 })), ("params", self.snapshot()),
        ])))
    }

    /// A released screen is unclaimed, not implicitly phone-owned. An explicit UI request can claim it.
    pub fn request_ui(&mut self) {
        if self.resources[0].current == 0 { self.resources[0] = Resource::default(); }
    }
}

fn integer(v: &Value, key: &str) -> Option<i64> { v.get(key).and_then(Value::as_int) }

#[cfg(test)]
mod tests {
    use super::*;
    fn change(id: i64, transfer: i64) -> Value { Value::dict(vec![("resources", Value::array(vec![Value::dict(vec![
        ("resourceID", Value::int(id)), ("transferType", Value::int(transfer)),
    ])]))]) }
    #[test]
    fn box_bootstrap_keeps_receiver_audio_until_granted() {
        let mut m = Modes::box_initial(); assert!(m.screen_owned());
        assert_eq!(m.resources[1].current, 2); m.claim_audio();
        assert_eq!(m.resources[1].current, 1);
    }
    #[test]
    fn borrowing_screen_preserves_permanent_owner_and_audio() {
        let mut m = Modes::initial();
        assert!(m.change(&change(1, 3)));
        assert!(!m.screen_owned());
        assert_eq!(m.resources[0].permanent, 1);
        assert_eq!(m.resources[1].current, 1);
        assert!(m.change(&change(1, 4)));
        assert!(m.screen_owned());
        assert!(m.change(&change(2, 1)));
        assert!(m.screen_owned());
        assert_eq!(m.resources[1].current, 2);
        assert!(m.change(&change(2, 2)));
        assert_eq!(m.resources[1].current, 0);
        assert!(m.change(&change(1, 1)));
        assert!(m.change(&change(1, 2)));
        assert!(!m.screen_owned());
        m.request_ui();
        assert!(m.screen_owned());
        assert_eq!(m.resources[1].current, 0);
    }
    #[test]
    fn malformed_changes_do_not_partially_update_modes() {
        let mut m = Modes::initial();
        let before = m.snapshot();
        assert!(!m.change(&change(3, 1)));
        assert!(!m.change(&change(1, 5)));
        assert_eq!(m.snapshot(), before);
    }
}
