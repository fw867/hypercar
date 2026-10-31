//! Controller-side resource arbitration, matching CatPlay's initial modes and request semantics.
use crate::bplist::{self, Value};

const ANYTIME: i64 = 100;
const USER_INITIATED: i64 = 500;
const NEVER: i64 = 1000;

#[derive(Clone, Copy, Debug)]
struct Resource {
    permanent: i64,
    current: i64,
    borrows: u32,
    take: i64,
    borrow: i64,
    unborrow: i64,
}
impl Default for Resource {
    fn default() -> Self { Self::owned(1, ANYTIME, ANYTIME) }
}
impl Resource {
    fn owned(entity: i64, take: i64, borrow: i64) -> Self {
        Self { permanent: entity, current: entity, borrows: 0, take, borrow, unborrow: ANYTIME }
    }
    fn allowed(priority: i64, constraint: i64) -> bool {
        match constraint { ANYTIME => true, USER_INITIATED => priority == USER_INITIATED, _ => false }
    }
    fn take_by(&mut self, actor: i64, priority: i64, take: i64, borrow: i64) -> bool {
        let other = 3 - actor;
        let other_borrowed = self.current == other && self.permanent == actor;
        if other_borrowed && !Self::allowed(priority, self.unborrow) { return false; }
        if !other_borrowed && (self.current != actor || self.permanent != actor) {
            if !Self::allowed(priority, self.take) { return false; }
        }
        *self = Self::owned(actor, take, borrow);
        true
    }
    fn request(&mut self, value: &Value, actor: i64) -> bool {
        let other = 3 - actor;
        match integer(value, "transferType") {
            Some(1) => {
                let (Some(priority), Some(take), Some(borrow)) = (
                    priority(value), constraint(value, "takeConstraint"), constraint(value, "borrowConstraint"),
                ) else { return false; };
                self.take_by(actor, priority, take, borrow)
            }
            Some(2) => {
                if !release_fields_empty(value) { return false; }
                if self.permanent == actor { *self = Self::owned(0, ANYTIME, ANYTIME); }
                true
            }
            Some(3) => {
                if value.get("takeConstraint").is_some() || value.get("borrowConstraint").is_some() { return false; }
                let (Some(priority), Some(unborrow)) = (priority(value), constraint(value, "unborrowConstraint")) else { return false; };
                if self.current == actor && self.permanent == other {
                    self.borrows = self.borrows.saturating_add(1);
                    return true;
                }
                if self.permanent == actor || !Self::allowed(priority, self.borrow) { return false; }
                self.permanent = other;
                self.current = actor;
                self.borrows = 1;
                self.unborrow = unborrow;
                true
            }
            Some(4) => {
                if !release_fields_empty(value) { return false; }
                if self.current == actor && self.permanent == other {
                    self.borrows = self.borrows.saturating_sub(1);
                    if self.borrows == 0 { self.current = other; self.unborrow = ANYTIME; }
                } else if self.current == other && self.permanent == actor { return false; }
                true
            }
            _ => false,
        }
    }
}

#[derive(Clone, Default, Debug)]
pub struct Modes {
    resources: [Resource; 2],
    apps: [i64; 3],
    speech: i64,
    playback_enabled: bool,
}

impl Modes {
    pub fn initial() -> Self { Self { speech: -1, playback_enabled: true, ..Self::default() } }
    pub fn box_initial() -> Self {
        let mut value = Self::initial();
        value.resources[1] = Resource::owned(2, ANYTIME, ANYTIME);
        value
    }
    pub fn audio_owned(&self) -> bool { self.resources[1].current == 1 }
    pub fn screen_owned(&self) -> bool { self.resources[0].current == 1 }

    /// /info.modes is a ChangeModes declaration, not an incoming modesChanged snapshot.
    /// Initial Take/Borrow have CatPlay's bootstrap semantics; do not replay them as live requests.
    pub fn from_info(info: &Value, enable_audio: bool, box_bootstrap: bool) -> Self {
        let fallback = if box_bootstrap && !enable_audio { Self::box_initial() } else { Self::initial() };
        let mut value = match info.get("modes") {
            Some(params) => match Self::read_initial(params) {
                Some(value) => value,
                None => { crate::emit_log("MODES: invalid /info.modes; using compatibility defaults"); fallback }
            },
            None => fallback,
        };
        value.playback_enabled = enable_audio;
        // Like CatPlay's overlay: obtain screen when its declared constraints permit it.
        value.resources[0].take_by(1, ANYTIME, ANYTIME, ANYTIME);
        // The user enabled application playback. Never override a receiver's Never constraint or
        // an uninterruptible borrow; publish this local decision with modesChanged, not changeModes.
        if enable_audio { value.resources[1].take_by(1, USER_INITIATED, ANYTIME, ANYTIME); }
        crate::emit_log(&format!("MODES: initial source={} screen={}/{} audio={}/{} playback={enable_audio}",
            if info.get("modes").is_some() { "/info.modes" } else { "compatibility" },
            value.resources[0].current, value.resources[0].permanent,
            value.resources[1].current, value.resources[1].permanent));
        value
    }

    fn read_initial(params: &Value) -> Option<Self> {
        params.as_dict()?;
        let mut next = Self::initial();
        if let Some(values) = params.get("initialPermanentEntity") {
            for v in values.as_array()?.iter().take(3) {
                let id = resource_id(v)?;
                let entity = integer(v, "permanentEntity")?;
                if !(1..=2).contains(&entity) { return None; }
                next.resources[id] = Resource::owned(entity,
                    optional_constraint(v, "takeConstraint")?, optional_constraint(v, "borrowConstraint")?);
            }
            if values.as_array()?.len() > 2 { return None; }
        }
        if let Some(values) = params.get("resources") {
            if values.as_array()?.len() > 8 { return None; }
            for v in values.as_array()? {
                let r = &mut next.resources[resource_id(v)?];
                match integer(v, "transferType")? {
                    1 => *r = Resource::owned(2, optional_constraint(v, "takeConstraint")?, optional_constraint(v, "borrowConstraint")?),
                    3 => if r.permanent != 2 {
                        r.current = 2; r.borrows = 1; r.unborrow = optional_constraint(v, "unborrowConstraint")?;
                    },
                    2 | 4 => {}, // Initial releases do not erase initialPermanentEntity.
                    _ => return None,
                }
            }
        }
        if !next.apply_apps(params) { return None; }
        Some(next)
    }

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
    pub fn change(&mut self, params: &Value) -> bool {
        let mut next = self.clone();
        if params.as_dict().is_none() { return false; }
        // CatPlay ignores initialPermanentEntity in live requests. Only /info can set the base.
        if let Some(values) = params.get("resources") {
            let Some(values) = values.as_array() else { return false; };
            if values.len() > 8 { return false; }
            for v in values {
                let Some(id) = resource_id(v) else { return false; };
                if !next.resources[id].request(v, 2) { return false; }
            }
        }
        if !next.apply_apps(params) { return false; }
        *self = next;
        true
    }
    fn apply_apps(&mut self, params: &Value) -> bool {
        if let Some(values) = params.get("appStates") {
            let Some(values) = values.as_array() else { return false; };
            if values.len() > 8 { return false; }
            for v in values {
                let Some(id @ 1..=3) = integer(v, "appStateID") else { return false; };
                if id == 1 {
                    let Some(mode @ -1..=2) = integer(v, "speechMode") else { return false; };
                    self.speech = mode;
                    self.apps[0] = if mode == -1 { 0 } else { 2 };
                } else if let Some(active) = v.get("state").and_then(Value::as_bool) {
                    if active {
                        if id == 2 && self.apps[1] == 1 { return false; }
                        self.apps[id as usize - 1] = 2;
                    } else if self.apps[id as usize - 1] == 2 { self.apps[id as usize - 1] = 0; }
                } else { return false; }
            }
        }
        true
    }
    pub fn response(&mut self, command: &Value) -> Option<Vec<u8>> {
        if command.get("type").and_then(Value::as_str) != Some("changeModes") { return None; }
        if let Some(resources) = command.get("params").and_then(|p| p.get("resources")).and_then(Value::as_array) {
            for r in resources.iter().take(8) {
                crate::emit_log(&format!("MODES: request id={:?} transfer={:?} priority={:?} take={:?} borrow={:?} unborrow={:?}",
                    integer(r,"resourceID"), integer(r,"transferType"), integer(r,"transferPriority"),
                    integer(r,"takeConstraint"), integer(r,"borrowConstraint"), integer(r,"unborrowConstraint")));
            }
        }
        let accepted = command.get("params").map(|p| self.change(p)).unwrap_or(false);
        crate::emit_log(&format!("MODES: changeModes accepted={accepted} screen={}/{} audio={}/{}",
            self.resources[0].current, self.resources[0].permanent, self.resources[1].current, self.resources[1].permanent));
        let mut response = vec![("status", Value::int(if accepted { 0 } else { 1 }))];
        if accepted { response.push(("params", self.snapshot())); }
        let body = bplist::encode(&Value::dict(response));
        if accepted {
            // First acknowledge the release as requested, then publish a new local claim. A car
            // Untake must not leave playback permanently muted after the resource is available.
            for (i,r) in self.resources.iter_mut().enumerate() {
                if r.current == 0 && (i == 0 || self.playback_enabled) {
                    r.take_by(1, ANYTIME, ANYTIME, ANYTIME);
                    crate::emit_log(&format!("MODES: reclaimed released local resource={}",i+1));
                }
            }
        }
        Some(body)
    }
    pub fn request_ui(&mut self) {
        let accepted = self.resources[0].take_by(1, USER_INITIATED, ANYTIME, ANYTIME);
        crate::emit_log(&format!("MODES: requestUI screen claim accepted={accepted}"));
    }
}

fn integer(v: &Value, key: &str) -> Option<i64> { v.get(key).and_then(Value::as_int) }
fn resource_id(v: &Value) -> Option<usize> { match integer(v, "resourceID")? { id @ 1..=2 => Some(id as usize - 1), _ => None } }
fn priority(v: &Value) -> Option<i64> { integer(v, "transferPriority").filter(|n| matches!(*n, ANYTIME | USER_INITIATED)) }
fn constraint(v: &Value, key: &str) -> Option<i64> { integer(v, key).filter(|n| matches!(*n, ANYTIME | USER_INITIATED | NEVER)) }
fn optional_constraint(v: &Value, key: &str) -> Option<i64> { if v.get(key).is_none() { Some(ANYTIME) } else { constraint(v, key) } }
fn release_fields_empty(v: &Value) -> bool { ["transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint"].iter().all(|k| v.get(k).is_none()) }

#[cfg(test)]
mod tests {
    use super::*;
    fn resource(id: i64, transfer: i64) -> Value {
        let mut fields = vec![("resourceID", Value::int(id)), ("transferType", Value::int(transfer))];
        if transfer == 1 { fields.extend([("transferPriority", Value::int(500)), ("takeConstraint", Value::int(100)), ("borrowConstraint", Value::int(100))]); }
        if transfer == 3 { fields.extend([("transferPriority", Value::int(500)), ("unborrowConstraint", Value::int(100))]); }
        Value::dict(fields)
    }
    fn change(id: i64, transfer: i64) -> Value { Value::dict(vec![("resources", Value::array(vec![resource(id, transfer)]))]) }
    fn info_audio(constraint: i64, borrowed: bool) -> Value {
        Value::dict(vec![("modes", Value::dict(vec![
            ("initialPermanentEntity", Value::array(vec![Value::dict(vec![
                ("resourceID", Value::int(2)), ("permanentEntity", Value::int(if borrowed { 1 } else { 2 })),
                ("takeConstraint", Value::int(constraint)), ("borrowConstraint", Value::int(100)),
            ])])),
            ("resources", Value::array(if borrowed { vec![Value::dict(vec![
                ("resourceID", Value::int(2)), ("transferType", Value::int(3)), ("unborrowConstraint", Value::int(constraint)),
            ])] } else { vec![] })),
        ]))])
    }
    #[test] fn info_initial_release_preserves_owner_and_constraints() {
        let mut info = info_audio(1000, false);
        if let Value::Dict(ref mut dict) = info { if let Value::Dict(ref mut modes) = dict[0].1 {
            let resources = modes.iter_mut().find(|(k,_)| k.as_str() == Some("resources")).unwrap();
            resources.1 = Value::array(vec![resource(2,2)]);
        } }
        let modes = Modes::from_info(&info, true, true);
        assert!(!modes.audio_owned()); assert!(modes.screen_owned());
        assert_eq!(modes.resources[1].take, NEVER);
        assert!(Modes::from_info(&info_audio(500, false), true, true).audio_owned());
        assert!(!Modes::from_info(&info_audio(1000, true), true, true).audio_owned());
    }
    #[test] fn initial_default_speech_enum_does_not_discard_resource_constraints() {
        let mut info=info_audio(1000,false);
        if let Value::Dict(ref mut dict)=info { if let Value::Dict(ref mut modes)=dict[0].1 {
            modes.push((Value::str("appStates"),Value::array(vec![Value::dict(vec![
                ("appStateID",Value::int(1)),("speechMode",Value::int(0)),
            ])])));
        } }
        let modes=Modes::from_info(&info,true,true);
        assert!(!modes.audio_owned()); assert_eq!(modes.speech,0);
    }

    #[test] fn borrowing_screen_preserves_permanent_owner_and_audio() {
        let mut m = Modes::initial();
        assert!(m.change(&change(1,3))); assert!(!m.screen_owned()); assert!(m.audio_owned());
        assert!(m.change(&change(1,3))); assert!(m.change(&change(1,4))); assert!(!m.screen_owned());
        assert!(m.change(&change(1,4))); assert!(m.screen_owned());
        assert!(m.change(&change(2,1))); assert!(!m.audio_owned());
        assert!(m.change(&change(2,2))); assert_eq!(m.resources[1].permanent,0);
        m.request_ui(); assert!(m.screen_owned()); assert!(!m.audio_owned());
    }
    #[test] fn request_ui_respects_receiver_constraints_and_can_recover_screen() {
        let mut m = Modes::initial(); assert!(m.change(&change(1,1))); assert!(!m.screen_owned());
        m.request_ui(); assert!(m.screen_owned());
        let mut request = resource(1,3);
        if let Value::Dict(ref mut fields) = request { fields.retain(|(k,_)| k.as_str() != Some("unborrowConstraint")); fields.push((Value::str("unborrowConstraint"),Value::int(1000))); }
        assert!(m.change(&Value::dict(vec![("resources", Value::array(vec![request]))])));
        m.request_ui(); assert!(!m.screen_owned());
    }
    #[test] fn audio_release_is_acknowledged_then_local_playback_can_resume() {
        let mut m=Modes::initial(); assert!(m.change(&change(2,1)));
        let command=Value::dict(vec![("type",Value::str("changeModes")),("params",change(2,2))]);
        let reply=bplist::parse(&m.response(&command).unwrap()).unwrap();
        let audio=&reply.get("params").unwrap().get("resources").unwrap().as_array().unwrap()[1];
        assert_eq!(audio.get("entity").and_then(Value::as_int),Some(0));
        assert!(m.audio_owned()); // Subsequent modesChanged reports the local claim.
        let mut disabled=Modes::from_info(&info_audio(100,false),false,true);
        disabled.response(&command); assert!(!disabled.audio_owned());
    }

    #[test] fn rejected_changes_are_atomic_and_have_no_success_snapshot() {
        let mut m = Modes::initial(); let before = m.snapshot();
        let bad = Value::dict(vec![("resources",Value::array(vec![resource(1,3),resource(7,1)]))]);
        assert!(!m.change(&bad)); assert_eq!(before,m.snapshot());
        let malformed_take = Value::dict(vec![("resources",Value::array(vec![Value::dict(vec![("resourceID",Value::int(2)),("transferType",Value::int(1))])]))]);
        let response = m.response(&Value::dict(vec![("type",Value::str("changeModes")),("params",malformed_take)])).unwrap();
        let parsed = bplist::parse(&response).unwrap(); assert_eq!(parsed.get("status").and_then(Value::as_int),Some(1)); assert!(parsed.get("params").is_none());
        assert_eq!(before,m.snapshot());
    }
}
