//! Receiver-requested instrument map. The source, socket, cipher and cancellation belong to type 111.
use crate::{
    airplay::{self, uuid_text},
    bplist::{self, Value},
    screen::{InstrumentFirstWriteTimeout, ScreenCipher, ScreenSender},
    screen_session::{setup_stream, ScreenSession},
};
use jni::objects::{GlobalRef, JByteArray, JLongArray};
use std::io;
use std::{sync::{Arc, atomic::{AtomicBool, Ordering}}, time::{Duration, Instant}};
use tokio::sync::watch;

pub(crate) const MAP_URL: &str = "maps:/car/instrumentcluster/map";

fn map_url_options(url: &str) -> Option<u8> {
    let Some((path, query)) = url.split_once('?') else { return (url == MAP_URL).then_some(0); };
    if path != MAP_URL { return None; }
    // MGU22 appends these display options in ICDisplay::setShowUiUrl.
    let mut options = 0;
    for option in query.split('&') {
        let (mask, flag) = match option {
            "showCompass=user" => (0b0001, 0b0001),
            "showETA=yes" => (0b0110, 0b0010),
            "showETA=no" => (0b0110, 0b0100),
            "showSpeedLimit=no" => (0b1000, 0b1000),
            _ => return None,
        };
        if options & mask != 0 { return None; }
        options |= flag;
    }
    Some(options)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Display {
    uuid: String,
    width: i32,
    height: i32,
    safe: [i32; 4],
    encoder_fps: i32,
    initial_map: Option<u8>,
    suggest_url: Option<String>,
}

fn kind(d: &Value) -> Option<i64> { d.get("type")?.as_int() }
fn same_uuid(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b) || uuid::Uuid::parse_str(a).ok()
        .zip(uuid::Uuid::parse_str(b).ok()).is_some_and(|(a, b)| a == b)
}

impl Display {
    pub(crate) fn declared(info: &Value) -> Option<Self> {
        let displays = info.get("displays")?.as_array()?;
        let mut auxiliary = displays.iter().filter(|d| kind(d) == Some(111));
        let d = auxiliary.next()?;
        let uuid = uuid_text(d.get("uuid"))?;
        if auxiliary.next().is_some() || uuid::Uuid::parse_str(&uuid).ok()?.is_nil()
            || !displays.iter().any(|d| matches!(kind(d), Some(110 | 0)) && uuid_text(d.get("uuid")).is_some())
            || displays.iter().filter(|d| uuid_text(d.get("uuid")).is_some_and(|s| same_uuid(&s, &uuid))).count() != 1 {
            return None;
        }
        let (w, h) = (d.get("widthPixels")?.as_int()?, d.get("heightPixels")?.as_int()?);
        if !(64..=16384).contains(&w) || !(48..=16384).contains(&h) { return None; }
        // The map has a single stable viewport; dynamically retargeted instrument panels need a separate contract.
        if d.get("viewAreas").is_some_and(|areas| areas.as_array().is_none_or(|a| a.len() > 1))
            || d.get("initialViewArea").is_some_and(|index| index.as_int() != Some(0)) { return None; }
        let rect = |r: &Value| -> Option<[i64; 4]> {
            let coordinate = |field| match r.get(field) { None => Some(0), Some(v) => v.as_int() };
            let box_ = [coordinate("originXPixels")?, coordinate("originYPixels")?,
                r.get("widthPixels")?.as_int()?, r.get("heightPixels")?.as_int()?];
            let [x,y,rw,rh] = box_;
            (x >= 0 && y >= 0 && rw > 0 && rh > 0 && rw <= w && rh <= h && x <= w-rw && y <= h-rh).then_some(box_)
        };
        let mut effective = [0,0,w,h];
        if let Some(view) = d.get("viewAreas").and_then(Value::as_array).and_then(|areas| areas.first()) {
            if view.get("displayUUID").is_some() && !uuid_text(view.get("displayUUID")).is_some_and(|s| same_uuid(&s,&uuid)) { return None; }
            effective = rect(view)?;
            if let Some(raw) = view.get("safeArea") {
                let safe = rect(raw)?;
                if safe[0] < effective[0] || safe[1] < effective[1] || safe[0]+safe[2] > effective[0]+effective[2]
                    || safe[1]+safe[3] > effective[1]+effective[3] { return None; }
                effective = safe;
            }
        }
        // Scale both axes proportionally, aligning the small independent AVC canvas for hardware encoders.
        let scale = (960.0 / w as f64).min(540.0 / h as f64).min(1.0);
        let width = ((w as f64 * scale) as i32 / 16) * 16;
        let height = ((h as f64 * scale) as i32 / 16) * 16;
        let [x,y,rw,rh] = effective;
        let left = ((x * width as i64 + w - 1)/w) as i32;
        let top = ((y * height as i64 + h - 1)/h) as i32;
        let safe = [left,top,((x+rw)*width as i64/w) as i32-left,((y+rh)*height as i64/h) as i32-top];
        let mut urls = d.as_dict()?.iter().filter(|(key, _)| key.as_str() == Some("initialURL"));
        let initial_map = urls.next().and_then(|(_, value)| value.as_str()).and_then(map_url_options)
            .filter(|_| urls.next().is_none());
        let suggest_url = info.get("altScreenSuggestUIURLs").and_then(Value::as_array)
            .and_then(|urls| urls.iter().filter_map(Value::as_str).find(|url| map_url_options(url).is_some()))
            .map(str::to_owned);
        (safe[2]>=64 && safe[3]>=48).then_some(Self {
            uuid, width, height, safe, encoder_fps: airplay::display_max_fps(d).unwrap_or(2).min(2) as i32,
            initial_map, suggest_url,
        })
    }

    pub(crate) fn source_available(&self) -> bool {
        let Some(bridge) = crate::TRANSCODER_BRIDGE.read().clone() else { return false; };
        crate::with_env(|env| env.call_method(bridge.as_obj(),"clusterMapAvailable","(II)Z",
            &[self.width.into(), self.height.into()])?.z()).unwrap_or(false)
    }
}

/// The initial SETUP advertises what the phone can produce. Only the receiver's
/// authenticated, post-SETUP declaration can identify a stream to open.
pub(crate) enum Offer {
    AwaitingDisplays,
    Declared(Display),
}

impl Offer {
    pub(crate) fn prepare(info: &Value, encrypted: bool, minimal_profile: bool) -> Option<Self> {
        if !encrypted || minimal_profile { return None; }
        if let Some(display) = Display::declared(info) {
            return display.source_available().then_some(Self::Declared(display));
        }
        // Some receivers return 455 or omit the complete display list at this stage.
        // A malformed or ambiguous explicit auxiliary declaration is never an invitation.
        if let Some(value) = info.get("displays") {
            if value.as_array()?.iter().any(|d| kind(d) == Some(111)) { return None; }
        }
        let bridge = crate::TRANSCODER_BRIDGE.read().clone()?;
        let capable = crate::with_env(|env| env.call_method(bridge.as_obj(), "clusterMapCapable", "()Z", &[])?.z());
        (capable == Some(true)).then_some(Self::AwaitingDisplays)
    }

    pub(crate) fn resolve(&self, info: &Value, reply: &Value) -> Option<Display> {
        let enabled = reply.get("enabledFeatures")?.as_array()?.iter().any(|f| f.as_str() == Some("altScreen"));
        if !enabled { return None; }
        let display = Display::declared(info)?;
        if let Self::Declared(early) = self {
            if !same_uuid(&early.uuid, &display.uuid) || (early.width, early.height, early.safe)
                != (display.width, display.height, display.safe) { return None; }
        }
        Some(display)
    }
}

struct Encoder(GlobalRef);
enum Sample { First, Next(bool), Clear }
#[derive(Clone, Copy, Default)]
struct MapStatus { round: u64, active: bool, ready: bool, card: u64 }
impl MapStatus {
    fn ready_round(self) -> Option<u64> { (self.active && self.ready).then_some(self.round) }
}
trait MapSource: Send + Sync + 'static {
    fn sample(&self, request: Sample) -> Option<Vec<u8>>;
    fn is_current(&self) -> bool { true }
    fn with_current_card<T>(&self, action: impl FnOnce() -> T) -> Option<T> {
        self.is_current().then(action)
    }
    fn with_frame_write(&self, round: u64, require_ready: bool, map_status: fn() -> MapStatus,
        write: &mut dyn FnMut() -> io::Result<usize>) -> Option<io::Result<usize>> {
        let status = map_status();
        (status.round == round && (!require_ready || status.ready_round() == Some(round))).then(write)
    }
}
impl Drop for Encoder {
    fn drop(&mut self) { crate::with_env(|env| env.call_method(self.0.as_obj(),"close","()V",&[]).map(|_|())); }
}
impl Encoder {
    fn map_status() -> MapStatus {
        let Some(bridge) = crate::TRANSCODER_BRIDGE.read().clone() else { return MapStatus::default(); };
        let [packed, card] = crate::with_env(|env| env.with_local_frame(4, |env| {
            let observation = env.call_method(bridge.as_obj(), "clusterMapObservation", "()[J", &[])?.l()?;
            let mut fields = [0_i64; 2];
            env.get_long_array_region(&JLongArray::from(observation), 0, &mut fields)?;
            Ok(fields.map(|value| value as u64))
        })).unwrap_or_default();
        MapStatus { round: packed >> 2, active: packed & 2 != 0, ready: packed & 1 != 0, card }
    }

    fn open(d: &Display, round: u64, card: u64) -> Option<Self> {
        let bridge = crate::TRANSCODER_BRIDGE.read().clone()?;
        let [x,y,w,h] = d.safe;
        crate::with_env(|env| env.with_local_frame(4,|env| {
            let object = env.call_method(bridge.as_obj(),"openClusterMap","(IIIIIIIJJ)Lio/github/hypercar/core/ClusterMapEncoder;",
                &[d.width.into(),d.height.into(),x.into(),y.into(),w.into(),h.into(),d.encoder_fps.into(),
                    (round as i64).into(), (card as i64).into()])?.l()?;
            if object.is_null() { return Ok(None); }
            Ok(Some(Self(env.new_global_ref(object)?)))
        })).flatten()
    }
}
impl MapSource for Encoder {
    fn sample(&self, request: Sample) -> Option<Vec<u8>> {
        crate::with_env(|env| env.with_local_frame(4,|env| {
            let bytes = match request {
                Sample::First => env.call_method(self.0.as_obj(),"firstSample","()[B",&[])?,
                Sample::Next(sync) => env.call_method(self.0.as_obj(),"nextSample","(Z)[B",&[sync.into()])?,
                Sample::Clear => env.call_method(self.0.as_obj(),"clearSample","()[B",&[])?,
            }.l()?;
            if bytes.is_null() { return Ok(None); }
            let array = JByteArray::from(bytes);
            if !(1..=4*1024*1024).contains(&env.get_array_length(&array)?) { return Ok(None); }
            Ok(Some(env.convert_byte_array(array)?))
        })).flatten()
    }

    fn is_current(&self) -> bool {
        crate::with_env(|env| env.call_method(self.0.as_obj(), "isCardCurrent", "()Z", &[])?.z()) == Some(true)
    }

    fn with_current_card<T>(&self, action: impl FnOnce() -> T) -> Option<T> {
        struct Permit<'a> { encoder: &'a GlobalRef, stamp: i64 }
        impl Drop for Permit<'_> {
            fn drop(&mut self) {
                crate::with_env(|env| env.call_method(self.encoder.as_obj(), "releaseCardWrite", "(J)V",
                    &[self.stamp.into()])?.v());
            }
        }
        let stamp = crate::with_env(|env| env.call_method(self.0.as_obj(), "acquireCardWrite", "()J", &[])?.j())?;
        if stamp == 0 { return None; }
        let _permit = Permit { encoder: &self.0, stamp };
        Some(action())
    }

    fn with_frame_write(&self, round: u64, require_ready: bool, _map_status: fn() -> MapStatus,
        write: &mut dyn FnMut() -> io::Result<usize>) -> Option<io::Result<usize>> {
        crate::with_navigation_source_write(|bridge| crate::with_env(|env| {
            env.call_method(bridge.as_obj(), "acquireClusterMapWrite", "(JZ)J",
                &[(round as i64).into(), require_ready.into()])?.j()
        }), || self.with_current_card(write)).flatten()
    }
}

#[derive(Default, Clone)]
struct Request {
    grant: Option<Arc<AtomicBool>>,
    grant_since: Option<Instant>,
    map_options: Option<u8>,
    keyframe: u64,
    selection: u64,
    show_revision: u64,
    revoked_show_revision: u64,
    last_selection: Option<Selection>,
}

impl Request {
    fn has_live_grant(&self) -> bool {
        self.grant.as_ref().is_some_and(|grant| grant.load(Ordering::Acquire))
    }
}

#[derive(Clone, Copy)]
struct Selection { received_at: Instant, map_options: Option<u8> }

impl Selection {
    fn parse(command: &str, body: &Value, received_at: Instant) -> Option<(String, Self)> {
        let params = body.get("params");
        if params.is_some_and(|value| value.as_dict().is_none()) { return None; }
        let map_options = match command {
            "stopUI" => None,
            "showUI" => {
                let nested = params.and_then(|p| p.get("url"));
                let top = body.get("url");
                match (nested, top) {
                    (Some(a), Some(b)) if a != b => {
                        let options = map_url_options(a.as_str()?)?;
                        if map_url_options(b.as_str()?) != Some(options) { return None; }
                        Some(options)
                    }
                    _ => match nested.or(top) {
                        Some(url) => map_url_options(url.as_str()?),
                        None => None,
                    },
                }
            }
            _ => return None,
        };
        Some((crate::receiver_ui::display_target(body).ok()??, Self { received_at, map_options }))
    }

    fn follows(self, previous: Self) -> bool {
        self.received_at > previous.received_at ||
            (self.received_at == previous.received_at && previous.map_options.is_some() && self.map_options.is_none())
    }

    fn apply(self, request: &mut Request) -> bool {
        if request.last_selection.is_some_and(|last| !self.follows(last)) { return false; }
        request.last_selection = Some(self);
        if self.map_options.is_some() {
            // Equivalent showUI commands advance the ordering fence without changing the grant.
            if request.has_live_grant() && request.map_options == self.map_options { return false; }
            request.map_options = self.map_options;
            request.grant_since = Some(self.received_at);
            request.show_revision = request.show_revision.wrapping_add(1);
            if !request.has_live_grant() {
                request.grant = Some(Arc::new(AtomicBool::new(true)));
            }
        } else {
            request.map_options = None;
            request.grant_since = None;
            request.selection = request.selection.wrapping_add(1);
            if let Some(grant) = request.grant.take() {
                request.revoked_show_revision = request.show_revision;
                grant.store(false, Ordering::Release);
            }
        }
        true
    }
}

#[derive(Default)]
enum IngressState {
    Waiting(Vec<(String, Selection)>),
    Active { uuid: String, since: Instant, request: watch::Sender<Request> },
    #[default]
    Disabled,
}

/// The control and event readers share this mailbox for one negotiated display.
pub(crate) struct Ingress(parking_lot::Mutex<IngressState>);

impl Default for Ingress {
    fn default() -> Self { Self(parking_lot::Mutex::new(IngressState::Waiting(Vec::new()))) }
}

impl Ingress {
    pub(crate) fn queue(&self, command: &str, body: &Value, received_at: Instant, first_request: impl FnOnce() -> bool) {
        let Some((id, selection)) = Selection::parse(command, body, received_at) else { return; };
        match &mut *self.0.lock() {
            IngressState::Waiting(entries) => {
                if !first_request() { return; }
                if let Some((_, latest)) = entries.iter_mut().find(|(uuid, _)| same_uuid(uuid, &id)) {
                    if selection.follows(*latest) { *latest = selection; }
                } else {
                    // During SETUP the accepted display may arrive with the session /info.
                    const MAX_PENDING_DISPLAYS: usize = 8;
                    if entries.len() == MAX_PENDING_DISPLAYS { entries.remove(0); }
                    entries.push((id, selection));
                }
            }
            IngressState::Active { uuid, since, request } if received_at >= *since && same_uuid(uuid, &id) => {
                if first_request() { request.send_if_modified(|desired| selection.apply(desired)); }
            }
            _ => (),
        }
    }

    fn attach(&self, uuid: String, since: Instant, mut initial: Request) -> watch::Receiver<Request> {
        let mut state = self.0.lock();
        if let IngressState::Waiting(entries) = &*state {
            if let Some((_, selection)) = entries.iter().find(|(id, selected)| same_uuid(id, &uuid) && selected.received_at >= since) {
                selection.apply(&mut initial);
            }
        }
        let (request, receiver) = watch::channel(initial);
        *state = IngressState::Active { uuid, since, request };
        receiver
    }

    fn keyframe(&self, id: &str, received_at: Instant) {
        if let IngressState::Active { uuid, since, request } = &*self.0.lock() {
            if received_at < *since || !same_uuid(uuid, id) { return; }
            request.send_if_modified(|desired| {
                if !desired.has_live_grant() || desired.grant_since.is_some_and(|since| received_at < since) {
                    return false;
                }
                desired.keyframe = desired.keyframe.wrapping_add(1);
                true
            });
        }
    }

    fn disable(&self) {
        if let IngressState::Active { request, .. } = std::mem::take(&mut *self.0.lock()) {
            request.send_modify(|desired| {
                desired.selection = desired.selection.wrapping_add(1);
                if let Some(grant) = desired.grant.take() { grant.store(false, Ordering::Release); }
            });
        }
    }
}

#[derive(Default)]
pub(crate) struct Commands { ids: Vec<String>, ingress: Option<Arc<Ingress>> }

impl Drop for Commands {
    fn drop(&mut self) {
        if let Some(ingress) = &self.ingress { ingress.disable(); }
    }
}
impl Commands {
    pub(crate) fn new(info: &Value, reply: &Value, session: Arc<ScreenSession>, since: Instant,
        tasks: &mut Vec<tokio::task::JoinHandle<()>>) -> Self {
        Self::with_source_since(info, reply, session, since, tasks, Display::source_available, Encoder::open, Encoder::map_status)
    }

    #[cfg(test)]
    fn with_source<S: MapSource>(info: &Value, reply: &Value, session: Arc<ScreenSession>,
        tasks: &mut Vec<tokio::task::JoinHandle<()>>, available: fn(&Display) -> bool,
        open_source: fn(&Display, u64, u64) -> Option<S>, map_status: fn() -> MapStatus,
    ) -> Self {
        Self::with_source_since(info, reply, session, Instant::now(), tasks, available, open_source, map_status)
    }

    fn with_source_since<S: MapSource>(info: &Value, reply: &Value, session: Arc<ScreenSession>, since: Instant,
        tasks: &mut Vec<tokio::task::JoinHandle<()>>, available: fn(&Display) -> bool,
        open_source: fn(&Display, u64, u64) -> Option<S>, map_status: fn() -> MapStatus,
    ) -> Self {
        let ids: Vec<_> = info.get("displays").and_then(Value::as_array).into_iter().flatten()
            .filter(|d| kind(d)==Some(111)).filter_map(|d| uuid_text(d.get("uuid")))
            .filter(|id| !same_uuid(id, &session.uuid)).collect();
        let enabled = reply.get("enabledFeatures").and_then(Value::as_array)
            .is_some_and(|f| f.iter().any(|v| v.as_str()==Some("altScreen")));
        let display = session.control.cluster_display.get().filter(|d|
            enabled && ids.iter().any(|id| same_uuid(id, &d.uuid)) && available(d)
        ).cloned();
        crate::emit_log(&format!("CLUSTER: /info type111_ids={} peer_enabled={} request_listener={} initial_map={} source=AMap_MAP_PREVIEW",
            ids.len(),enabled,display.is_some(),display.as_ref().is_some_and(|d| d.initial_map.is_some())));
        let ingress = session.control.cluster_ingress();
        if let Some(d) = display {
            let rx = ingress.attach(d.uuid.clone(), since, Request {
                grant: d.initial_map.map(|_| Arc::new(AtomicBool::new(true))),
                grant_since: d.initial_map.map(|_| since),
                map_options: d.initial_map,
                ..Default::default()
            });
            tasks.push(crate::callback_context::spawn(run(d,session,rx,open_source,map_status)));
            Self { ids, ingress: Some(ingress) }
        } else {
            ingress.disable();
            Self { ids, ingress: None }
        }
    }
    #[cfg(test)]
    fn handle(&self, command: &str, body: &Value) -> bool {
        self.handle_test_at(command, body, Instant::now())
    }

    #[cfg(test)]
    fn handle_test_at(&self, command: &str, body: &Value, received_at: Instant) -> bool {
        if let Some(ingress) = &self.ingress { ingress.queue(command, body, received_at, || true); }
        self.dispatch(command, body, received_at)
    }

    pub(crate) fn dispatch(&self, command: &str, body: &Value, received_at: Instant) -> bool {
        if !matches!(command,"showUI"|"stopUI"|"forceKeyFrame") { return false; }
        let Ok(Some(id)) = crate::receiver_ui::display_target(body) else { return false; };
        if !self.ids.iter().any(|s| same_uuid(s,&id)) { return false; }
        if command == "forceKeyFrame" {
            if let Some(ingress) = &self.ingress { ingress.keyframe(&id, received_at); }
        }
        true
    }
}

fn stream_body(id: &str, connection: Option<i64>) -> Vec<u8> {
    let mut fields = vec![("type",Value::int(111)),("uuid",Value::str(id))];
    if let Some(conn) = connection { fields.push(("streamConnectionID",Value::int(conn))); }
    bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]))
}

fn first_idr(data: &[u8], d: &Display) -> bool {
    let nals = crate::screen::annexb_nals(data);
    let Some(sps) = nals.iter().find(|n| crate::screen::nal_type(n)==7) else { return false; };
    crate::h264::sps_dimensions(sps)==Some((d.width as u32,d.height as u32))
        && nals.iter().any(|n| crate::screen::nal_type(n)==8 && n.len()>1)
        && nals.iter().any(|n| crate::screen::nal_type(n)==5 && n.len()>1)
}

fn response_port(body: &Value, display: &Display, conn: i64) -> Result<u16,String> {
    let reply = setup_stream::select(body,111,false)?;
    if reply.get("streamConnectionID").is_some_and(|id| id.as_int()!=Some(conn))
        || reply.get("uuid").is_some() && !uuid_text(reply.get("uuid")).is_some_and(|u|same_uuid(&u,&display.uuid)) {
        return Err("mismatched type111 stream identity".into());
    }
    reply.get("dataPort").and_then(Value::as_int).and_then(|p|u16::try_from(p).ok()).filter(|p|*p>0)
        .ok_or_else(||"invalid type111 dataPort".into())
}

struct Cleanup {
    connection: i64,
    retired_round: Option<u64>,
    retired_show_revision: u64,
    failures: usize,
    retry_at: tokio::time::Instant,
    covered_round: Option<u64>,
    covered_card: u64,
    parked_at_show: Option<u64>,
}

impl Cleanup {
    const DELAYS_MS: [u64; 3] = [250, 500, 1_000];

    fn new(connection: i64, retired_round: Option<u64>, retired_show_revision: u64, retired_card: u64) -> Self {
        Self { connection, retired_round, retired_show_revision, failures: 0, retry_at: tokio::time::Instant::now(),
            covered_round: retired_round, covered_card: retired_card, parked_at_show: None }
    }

    fn deadline(&mut self, show: u64, ready_route: Option<u64>, ready_card: u64) -> Option<tokio::time::Instant> {
        if let Some(parked_show) = self.parked_at_show {
            let new_route = ready_route.is_some_and(|route| self.covered_round.is_none_or(|old| route > old));
            if parked_show == show && !new_route && ready_card <= self.covered_card { return None; }
            self.covered_round = self.covered_round.max(ready_route);
            self.covered_card = self.covered_card.max(ready_card);
            self.parked_at_show = None;
            self.failures = 1;
            self.retry_at = tokio::time::Instant::now() + Duration::from_millis(Self::DELAYS_MS[0]);
        }
        Some(self.retry_at)
    }

    fn failed(&mut self, show_at_attempt: u64, error: &str) {
        self.failures += 1;
        crate::emit_log(&format!("CLUSTER: type111 cleanup attempt {} rejected: {error}", self.failures));
        if let Some(delay) = Self::DELAYS_MS.get(self.failures - 1) {
            self.retry_at = tokio::time::Instant::now() + Duration::from_millis(*delay);
        } else {
            self.parked_at_show = Some(show_at_attempt);
        }
    }
}

#[derive(Default)]
struct SuggestionState {
    active_round: Option<u64>,
    suggested_round: Option<u64>,
    selection: u64,
    show_round: Option<(u64, u64)>,
}

impl SuggestionState {
    fn needs_update(&self, desired: &Request) -> bool {
        desired.selection != self.selection ||
            (desired.has_live_grant() && self.show_round.is_none_or(|(revision, _)| revision != desired.show_revision))
    }

    fn observe(&mut self, desired: &Request, status: MapStatus, cleanup: Option<&Cleanup>) {
        let current_round = status.active.then_some(status.round);
        if desired.selection != self.selection {
            let granted_round = self.show_round.filter(|(revision, _)| *revision == desired.revoked_show_revision)
                .map(|(_, round)| round);
            let stopped_round = match cleanup {
                Some(cleanup) if desired.revoked_show_revision <= cleanup.retired_show_revision =>
                    cleanup.retired_round.or(granted_round).or(self.active_round).or(current_round),
                Some(_) => granted_round.or(current_round).or(self.active_round),
                None => granted_round.or(self.active_round).or(current_round),
            };
            self.suggested_round = self.suggested_round.max(stopped_round);
            self.selection = desired.selection;
        }
        self.active_round = current_round.or(self.active_round);
        if desired.has_live_grant() {
            if self.show_round.is_none_or(|(revision, _)| revision != desired.show_revision) {
                if let Some(round) = current_round {
                    self.show_round = Some((desired.show_revision, round));
                }
            }
            if cleanup.is_none() {
                self.suggested_round = self.suggested_round.max(self.active_round);
            }
        }
    }
}

async fn run<S: MapSource>(display: Display, session: Arc<ScreenSession>, mut rx: watch::Receiver<Request>,
                           open_source: fn(&Display, u64, u64) -> Option<S>, map_status: fn() -> MapStatus) {
    let mut suggestion = SuggestionState::default();
    let mut pending_cleanup: Option<Cleanup> = None;
    let mut failed_attempt = None;
    let mut first_write_retry = None;
    while session.session_is_live() {
        let desired = rx.borrow_and_update().clone();
        let status = (display.suggest_url.is_some() || pending_cleanup.is_some() || desired.has_live_grant()).then(map_status);
        let ready_round = status.and_then(MapStatus::ready_round);
        let card = status.map_or(0, |status| status.card);
        let cleanup_card = if ready_round.is_some() && desired.has_live_grant() { card } else { 0 };
        if let Some(status) = status {
            suggestion.observe(&desired, status, pending_cleanup.as_ref());
        }
        let mut retry_at = None;
        if let Some(cleanup) = pending_cleanup.as_mut() {
            retry_at = cleanup.deadline(desired.show_revision, ready_round, cleanup_card);
            if retry_at.is_some_and(|at| at <= tokio::time::Instant::now()) {
                // Cleanup is owned by the retired allocation, independent of UI and main-stream grants.
                cleanup.covered_round = cleanup.covered_round.max(ready_round);
                let release = release_stream(&display, &session, cleanup.connection);
                tokio::pin!(release);
                let outcome = loop {
                    tokio::select! {
                        outcome = &mut release => break outcome,
                        change = rx.changed() => {
                            if change.is_err() { return; }
                            let current = rx.borrow_and_update().clone();
                            if suggestion.needs_update(&current) {
                                suggestion.observe(&current, map_status(), Some(cleanup));
                            }
                        }
                    }
                };
                // The final ACK and a newly registered selection may wake the worker together.
                let current = rx.borrow_and_update().clone();
                if suggestion.needs_update(&current) {
                    suggestion.observe(&current, map_status(), Some(cleanup));
                }
                match outcome {
                    Ok(()) => pending_cleanup = None,
                    Err(error) => cleanup.failed(desired.show_revision, &error),
                }
                continue;
            }
        }
        if let (Some(url), Some(status)) = (display.suggest_url.as_deref(), status) {
            if !desired.has_live_grant() && pending_cleanup.is_none() && suggestion.suggested_round != Some(status.round) && status.active && status.ready {
                if let Some(connection) = current_main_connection(&session) {
                    let check = |write: &mut dyn FnMut() -> io::Result<usize>| {
                        with_current_main(&session, connection, || {
                            if map_status().ready_round() != Some(status.round) { return None; }
                            // Hold the selection only around the nonblocking write. Both readers
                            // can publish while main ownership or map readiness is being checked.
                            let current = rx.borrow();
                            (current.selection == desired.selection && !current.has_live_grant()).then(write)
                        }).flatten()
                    };
                    if suggest_ui(&session, url, &check).await { suggestion.suggested_round = Some(status.round); }
                }
            }
        }
        if let (None, Some(grant), Some(round)) = (&pending_cleanup, desired.grant, ready_round) {
            let attempt = active_main_connection(&session, &grant)
                .map(|connection| (connection, desired.show_revision, round, card))
                .filter(|attempt| failed_attempt != Some(*attempt));
            retry_at = first_write_retry.and_then(|(retried, at)| {
                (attempt == Some(retried) && at > tokio::time::Instant::now()).then_some(at)
            });
            if let (None, Some(attempt @ (main_connection, ..))) = (retry_at, attempt) {
                let d = display.clone();
                let source = tokio::task::spawn_blocking(move || {
                    let encoder = Arc::new(open_source(&d, round, card)?);
                    let frame = encoder.sample(Sample::First)?;
                    first_idr(&frame,&d).then_some(encoder)
                }).await.ok().flatten();
                if let Some(encoder) = source {
                    let mut retired = None;
                    if let Err(e) = stream(&display,&session,&rx,&grant,main_connection,encoder,round,
                        desired.show_revision,map_status,&mut retired).await {
                        // The failed stream belongs to this card. The receiver's showUI
                        // remains valid for a replacement card until it is explicitly revoked.
                        if e.is_first_write_timeout() && first_write_retry.is_none_or(|(retried, _)| retried != attempt) {
                            first_write_retry = Some((attempt, tokio::time::Instant::now() + Duration::from_millis(250)));
                            crate::emit_log("CLUSTER: type111 first write timed out; one retry after old stream cleanup");
                        } else {
                            failed_attempt = Some(attempt);
                            crate::emit_log(&format!("CLUSTER: type111 stopped: {}", e.detail()));
                        }
                    }
                    if let Some(connection) = retired {
                        pending_cleanup = Some(Cleanup::new(connection, Some(round), desired.show_revision, card));
                        continue;
                    }
                }
            }
        }
        tokio::select! {
            changed = rx.changed() => if changed.is_err() { return; },
            _ = tokio::time::sleep_until(retry_at.unwrap_or_else(|| tokio::time::Instant::now() + Duration::from_secs(1))),
                if pending_cleanup.is_some() || display.suggest_url.is_some() || rx.borrow().has_live_grant() => (),
        }
    }
}

async fn suggest_ui(session: &ScreenSession, url: &str, check: crate::rtsp::CheckedWrite<'_>) -> bool {
    let body = bplist::encode(&Value::dict(vec![
        ("type", Value::str("suggestUI")),
        ("params", Value::dict(vec![("urls", Value::array(vec![Value::str(url)]))])),
    ]));
    let started = AtomicBool::new(false);
    let track_write = |write: &mut dyn FnMut() -> io::Result<usize>| check(&mut || {
        let result = write();
        if result.as_ref().is_ok_and(|count| *count > 0) { started.store(true, Ordering::Release); }
        result
    });
    match session.control.post_while("/command", &body, "application/x-apple-binary-plist", &track_write).await {
        Ok(None) => false,
        Ok(Some(cseq)) => {
            crate::emit_log(&format!("CLUSTER: map suggestion written CSeq={cseq}; waiting for receiver showUI"));
            true
        }
        Err(error) => {
            crate::emit_log(&format!("CLUSTER: map suggestion write failed: {error}"));
            started.load(Ordering::Acquire)
        }
    }
}

pub(crate) fn active_main_connection(session: &ScreenSession, grant: &AtomicBool) -> Option<u64> {
    if !grant.load(Ordering::Acquire) { return None; }
    current_main_connection(session)
}

fn current_main_connection(session: &ScreenSession) -> Option<u64> {
    if !session.owned.load(Ordering::Acquire) || !session.session_is_live() {
        return None;
    }
    // Release the pipeline lock before inspecting the same modes lock used by the pump's install path.
    let main = session.params.pipeline.lock().main_video_connection.clone()?;
    if let Some((modes, revision)) = main.resource_grant {
        if !modes.lock().can_restore_screen(revision) { return None; }
    }
    Some(main.id)
}

fn with_current_main<T>(session: &ScreenSession, connection: u64, run: impl FnOnce() -> T) -> Option<T> {
    let main = session.params.pipeline.lock().main_video_connection.clone()?;
    if main.id != connection { return None; }
    // Match the main sender's mode -> pipeline lock order. Hold both only around try_write.
    let mode = main.resource_grant.as_ref().map(|(modes, revision)| (modes.lock(), *revision));
    if mode.as_ref().is_some_and(|(modes, revision)| !modes.can_restore_screen(*revision)) { return None; }
    let pipeline = session.params.pipeline.lock();
    if !session.owned.load(Ordering::Acquire) || pipeline.main_video_connection.as_ref()?.id != connection { return None; }
    session.shutdown.while_running(|| session.control.while_session_live(run)).flatten()
}

enum StreamError {
    VideoWrite(io::Error),
    Other(String),
}

impl StreamError {
    fn is_first_write_timeout(&self) -> bool {
        matches!(self, Self::VideoWrite(error) if error.get_ref().is_some_and(|cause| cause.is::<InstrumentFirstWriteTimeout>()))
    }

    fn detail(&self) -> String {
        match self {
            Self::VideoWrite(error) => error.to_string(),
            Self::Other(error) => error.clone(),
        }
    }
}

impl From<String> for StreamError {
    fn from(error: String) -> Self { Self::Other(error) }
}

impl From<&str> for StreamError {
    fn from(error: &str) -> Self { Self::Other(error.to_owned()) }
}

async fn stream(display: &Display, session: &ScreenSession, rx: &watch::Receiver<Request>, grant: &Arc<AtomicBool>,
                main_connection: u64, encoder: Arc<impl MapSource>, round: u64, show_revision: u64,
                map_status: fn() -> MapStatus, pending_cleanup: &mut Option<i64>) -> Result<(),StreamError> {
    let selected = |current: &Request| current.show_revision == show_revision &&
        current.grant.as_ref().is_some_and(|active| Arc::ptr_eq(active, grant) && active.load(Ordering::Acquire));
    let authorized = || {
        let selected = selected(&rx.borrow());
        selected && active_main_connection(session, grant) == Some(main_connection) && encoder.is_current()
    };
    let map_ready = || map_status().ready_round() == Some(round);
    if !authorized() || !map_ready() { return Ok(()); }
    let shared = session.shared.as_ref().ok_or("pair-verify unavailable")?;
    let conn = airplay::random_positive_i64();
    crate::emit_log(&format!("CLUSTER: active map+encoded IDR ready, SETUP type111 source={}x{}",display.width,display.height));
    let check = |write: &mut dyn FnMut() -> io::Result<usize>| {
        with_current_main(session, main_connection, || {
            if !map_ready() { return None; }
            let current = rx.borrow();
            if !selected(&current) { return None; }
            encoder.with_current_card(write)
        }).flatten()
    };
    let check_frame = |write: &mut dyn FnMut() -> io::Result<usize>, require_ready| {
        with_current_main(session, main_connection, || {
            let current = rx.borrow();
            if !selected(&current) { return None; }
            encoder.with_frame_write(round, require_ready, map_status, write)
        }).flatten()
    };
    let check_live_frame = |write: &mut dyn FnMut() -> io::Result<usize>| check_frame(write, true);
    let check_clear_frame = |write: &mut dyn FnMut() -> io::Result<usize>| check_frame(write, false);
    let response = match session.control.screen_setup_while(&stream_body(&display.uuid,Some(conn)), &check).await {
        Ok(Some(response)) => response,
        Ok(None) => return Ok(()),
        Err(error) => {
            // After a reply timeout the receiver may have allocated the UUID anyway.
            *pending_cleanup = matches!(error, crate::rtsp::RequestError::ReplyTimeout(_)).then_some(conn);
            return Err(error.to_string().into());
        }
    };
    if response.status()!=200 { return Err(format!("SETUP status {}",response.status()).into()); }
    *pending_cleanup = Some(conn);
    let result = async {
        let body = bplist::parse(&response.body).map_err(|e|e.to_string())?;
        crate::resource_trace::validate_ack("CLUSTER_SETUP",&response)?;
        let port = response_port(&body, display, conn)?;
        if !authorized() || !map_ready() { return Ok(()); }
        let factory=session.factory.clone(); let host=session.params.car_ip.clone();
        let socket=tokio::task::spawn_blocking(move ||factory.open_tcp(&host,port,3000)).await.map_err(|e|e.to_string())?.ok_or("connect type111 TCP failed")?;
        if !authorized() || !map_ready() { return Ok(()); }
        let (_,write) = socket.into_split();
        let mut sender = ScreenSender::new(write,ScreenCipher::ChaCha(airplay::stream_key(shared,conn as u64)),display.width as u32,display.height as u32,false);
        sender.mark_instrument_map();
        sender.set_ownership(grant.clone());
        // SETUP and connecting the socket may have taken seconds. Re-sample the live map before the first write.
        tokio::time::sleep(Duration::from_millis(1000 / display.encoder_fps as u64 + 10)).await;
        if !authorized() || !map_ready() { return Ok(()); }
        let source = encoder.clone();
        let fresh = tokio::task::spawn_blocking(move ||source.sample(Sample::Next(true))).await.map_err(|e|e.to_string())?;
        let Some(mut sample) = fresh else { return Ok(()); };
        if !first_idr(&sample, display) { return Err("encoder failed to refresh the navigation IDR".into()); }
        let mut keyframe = rx.borrow().keyframe;
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        interval.tick().await;
        while authorized() && map_ready() {
            let pts = crate::timing::monotonic_nanos()/1000;
            let written = sender.send_instrument_map_while(&sample, pts, &check_live_frame, Duration::from_millis(750))
                .await.map_err(StreamError::VideoWrite)?;
            if !written {
                if !authorized() || !map_ready() { break; }
                // A concurrent source publication can briefly hold the write permit.
                tokio::time::sleep(Duration::from_millis(10)).await;
                continue;
            }
            interval.tick().await;
            if !authorized() { break; }
            let next_key = rx.borrow().keyframe;
            let source = encoder.clone();
            let next = tokio::task::spawn_blocking(move || source.sample(Sample::Next(next_key!=keyframe))).await.map_err(|e|e.to_string())?;
            keyframe = next_key;
            match next { Some(bytes)=>sample=bytes, None=>{
                crate::emit_log("CLUSTER: map, maneuver or encoder unavailable; closing auxiliary output");
                if authorized() && map_status().round == round {
                    let source = encoder.clone();
                    let clear = tokio::task::spawn_blocking(move ||source.sample(Sample::Clear)).await.ok().flatten();
                    if let Some(blank) = clear.filter(|frame|authorized() && map_status().round == round && first_idr(frame,display)) {
                        if let Err(error) = sender.send_instrument_map_while(&blank, crate::timing::monotonic_nanos()/1000,
                            &check_clear_frame, Duration::from_millis(500)).await {
                            let failure = StreamError::VideoWrite(error);
                            if failure.is_first_write_timeout() { return Err(failure); }
                        }
                    }
                }
                break
            } }
        }
        Ok(())
    }.await;
    // The worker retires this allocation after the video socket has been dropped.
    result
}

fn validate_teardown(reply: &crate::rtsp::RtspMessage, display: &Display, connection: i64) -> Result<(), String> {
    const NOT_FOUND: i64 = -6727;
    if !reply.body.is_empty() {
        let body = bplist::parse(&reply.body).map_err(|_| "invalid type111 TEARDOWN body")?;
        let matches_identity = |value: &Value| {
            value.get("type").is_none_or(|v| v.as_int() == Some(111)) &&
                value.get("uuid").is_none_or(|v| uuid_text(Some(v)).is_some_and(|id| same_uuid(&id, &display.uuid))) &&
                value.get("streamConnectionID").is_none_or(|v| v.as_int() == Some(connection))
        };
        if !matches_identity(&body) { return Err("type111 TEARDOWN result identifies another stream".into()); }
        if let Some(streams) = body.get("streams") {
            let [entry] = streams.as_array().ok_or("invalid type111 TEARDOWN stream list")?.as_slice() else {
                return Err("ambiguous type111 TEARDOWN stream list".into());
            };
            if kind(entry) != Some(111) || !matches_identity(entry) {
                return Err("type111 TEARDOWN result identifies another stream".into());
            }
            if let Some(status) = entry.get("status") {
                match status.as_int() {
                    Some(0) => (),
                    Some(NOT_FOUND) if entry.get("uuid").is_some() && matches!(reply.status(), 200 | 404)
                        && body.get("status").is_none_or(|v| matches!(v.as_int(), Some(0 | NOT_FOUND))) => return Ok(()),
                    _ => return Err(format!("type111 TEARDOWN rejected stream status={:?}", status.as_int())),
                }
            }
        }
    }
    crate::resource_trace::validate_ack("CLUSTER_TEARDOWN", reply)
}

async fn release_stream(display: &Display, session: &ScreenSession, connection: i64) -> Result<(), String> {
    let check = |write: &mut dyn FnMut() -> io::Result<usize>| {
        session.shutdown.while_running(|| session.control.while_session_live(write)).flatten()
    };
    let teardown = session.control.screen_teardown_while(&stream_body(&display.uuid, None), &check).await
        .map_err(|error| error.to_string())?.ok_or("type111 control session is closing")?;
    validate_teardown(&teardown, display, connection)?;
    crate::emit_log("CLUSTER: type111 TCP closed and stream TEARDOWN accepted");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    const MAIN: &str = "ab4f0144-bd7e-4bfe-8f81-76bc29117c57";
    const AUX: &str = "acabed80-3883-435c-9260-19128a4c1a79";
    const BMW_MAP_URL: &str = "maps:/car/instrumentcluster/map?showCompass=user&showETA=yes&showSpeedLimit=no";

    fn ready_map() -> MapStatus { MapStatus { round: 0, active: true, ready: true, card: 1 } }

    fn declared(d: Vec<Value>) -> Option<Display> {
        Display::declared(&Value::dict(vec![("displays",Value::array(d))]))
    }
    fn display(kind: i64, id: &str) -> Value {
        Value::dict(vec![("type",Value::int(kind)),("uuid",Value::str(id)),
            ("widthPixels",Value::int(1280)),("heightPixels",Value::int(720))])
    }
    fn aux() -> Display { declared(vec![display(111,AUX),display(110,MAIN)]).unwrap() }
    #[test]
    fn final_auxiliary_rate_and_uuid_letter_case_do_not_invalidate_an_early_offer() {
        let reply = Value::dict(vec![("enabledFeatures", Value::array(vec![Value::str("altScreen")]))]);
        let mut late = display(111, &AUX.to_uppercase());
        if let Value::Dict(fields) = &mut late { fields.push((Value::str("maxFPS"), Value::int(1))); }
        let info = Value::dict(vec![("displays", Value::array(vec![display(110, MAIN), late]))]);
        assert_eq!(Offer::Declared(aux()).resolve(&info, &reply).unwrap().encoder_fps, 1);
        let other = Display { uuid: "ba0f617b-aa63-4f5a-97ee-fdb1d85a7170".into(), ..aux() };
        assert!(Offer::Declared(other).resolve(&info, &reply).is_none());
    }
    fn command(id: &str, url: &str) -> Value {
        Value::dict(vec![("params",Value::dict(vec![("uuid",Value::str(id)),("url",Value::str(url))]))])
    }

    fn stop_request() -> Vec<u8> {
        let mut stop = command(AUX, "");
        let Value::Dict(fields) = &mut stop else { unreachable!() };
        fields.push((Value::str("type"), Value::str("stopUI")));
        let payload = bplist::encode(&stop);
        let mut wire = format!("POST /command RTSP/1.0\r\nCSeq: 5\r\nContent-Length: {}\r\n\r\n", payload.len()).into_bytes();
        wire.extend_from_slice(&payload);
        wire
    }
    #[tokio::test]
    async fn duplicate_auxiliary_uuid_leaves_main_keyframe_dispatch_available() {
        use crate::screen_resource::tests::Fixture;
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let info = Value::dict(vec![("displays", Value::array(vec![
            display(110, MAIN), display(111, &MAIN.to_uppercase()), display(111, AUX),
        ]))]);
        let commands = Commands::new(&info, &Value::Null, f.session.clone(), Instant::now(), &mut Vec::new());
        let main = command(MAIN, "");
        assert!(!commands.handle("forceKeyFrame", &main));
        assert!(crate::receiver_ui::main_target(&main, Some(MAIN)));
        assert!(commands.handle("forceKeyFrame", &command(AUX, "")));
    }

    // A 64x48, single-frame x264 baseline encode, with the optional SEI removed.
    const SAMPLE: &[u8] = &[0,0,0,1,103,66,192,10,217,4,123,1,16,0,0,3,0,16,0,0,3,0,40,241,34,100,128,
        0,0,0,1,104,203,131,203,32,0,0,1,101,136,132,4,188,152,160,0,56,163,39,39,39,93,117,215,93,117,215,128];
    struct FixtureMap;
    impl MapSource for FixtureMap {
        fn sample(&self, _: Sample) -> Option<Vec<u8>> { Some(SAMPLE.to_vec()) }
    }

    fn startup_info(url: Option<&str>) -> Value {
        let mut instrument = vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)),
            ("widthPixels", Value::int(64)), ("heightPixels", Value::int(48)),
        ];
        if let Some(url) = url { instrument.push(("initialURL", Value::str(url))); }
        Value::dict(vec![("displays", Value::array(vec![display(110, MAIN), Value::dict(instrument)]))])
    }

    fn accepted_alt_screen() -> Value {
        Value::dict(vec![("enabledFeatures", Value::array(vec![Value::str("altScreen")]))])
    }

    #[tokio::test]
    async fn initial_map_starts_after_current_main_idr_without_show_ui() {
        exercise_initial_map(false).await;
    }

    #[tokio::test]
    async fn early_stop_ui_cancels_the_initial_map_until_an_explicit_show_ui() {
        exercise_initial_map(true).await;
    }

    async fn exercise_initial_map(stop_before_main: bool) {
        use crate::screen_resource::tests::{Fixture, read, reply};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let info = startup_info(Some(MAP_URL));
        let session_reply = accepted_alt_screen();
        let selected = Offer::AwaitingDisplays.resolve(&info, &session_reply).unwrap();
        f.session.control.cluster_display.set(selected).unwrap();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &session_reply, f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        if stop_before_main {
            assert!(commands.handle("stopUI", &command(AUX, MAP_URL)));
        }
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_peer, _) = listener.accept().await.unwrap();
        let mut main = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
        main.attach_diagnostics(f.session.params.pipeline.clone(), 47);
        main.send_video_annexb(&[0,0,1,0x41,9], 1000).await.unwrap();
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        main.send_video_annexb(SAMPLE, 2000).await.unwrap();
        if stop_before_main {
            assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
            assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        }
        let setup = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        let aux = setup_stream::select(&body, 111, false).unwrap();
        assert_eq!(aux.get("uuid").and_then(Value::as_str), Some(AUX));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn only_negotiated_available_auxiliary_map_can_seed_the_initial_request() {
        use crate::screen_resource::tests::Fixture;
        for (url, accepted, available, expected) in [
            (None, true, true, Some(false)),
            (Some("maps:/car/instrumentcluster/map?x=1"), true, true, Some(false)),
            (Some(BMW_MAP_URL), true, true, Some(true)),
            (Some(MAP_URL), false, true, None),
            (Some(MAP_URL), true, false, None),
            (Some(MAP_URL), true, true, Some(true)),
        ] {
            let mut f = Fixture::new(crate::modes::Modes::initial()).await;
            Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
            let info = startup_info(url);
            let reply = if accepted { accepted_alt_screen() } else { Value::Null };
            f.session.control.cluster_display.set(Display::declared(&info).unwrap()).unwrap();
            let mut workers = Vec::new();
            let available: fn(&Display) -> bool = if available { |_| true } else { |_| false };
            let commands = Commands::with_source(&info, &reply, f.session.clone(), &mut workers,
                available, |_, _, _| Some(FixtureMap), ready_map);
            let seeded = commands.ingress.as_ref().map(|ingress| match &*ingress.0.lock() {
                IngressState::Active { request, .. } => request.borrow().grant.is_some(),
                _ => unreachable!(),
            });
            assert_eq!(seeded, expected);
            drop(commands);
            for worker in workers { worker.await.unwrap(); }
        }
    }

    #[tokio::test]
    async fn active_map_suggests_the_advertised_url_and_waits_for_the_display_request() {
        use crate::screen_resource::tests::{Fixture, read, reply};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let mut info = startup_info(None);
        let Value::Dict(fields) = &mut info else { unreachable!() };
        fields.push((Value::str("altScreenSuggestUIURLs"), Value::array(vec![Value::str(MAP_URL)])));
        let session_reply = accepted_alt_screen();
        f.session.control.cluster_display.set(Offer::AwaitingDisplays.resolve(&info, &session_reply).unwrap()).unwrap();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &session_reply, f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (_peer, _) = listener.accept().await.unwrap();
        let mut main = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
        main.attach_diagnostics(f.session.params.pipeline.clone(), 47);
        main.send_video_annexb(SAMPLE, 1000).await.unwrap();
        let suggestion = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(suggestion.first_line.starts_with("POST /command "));
        let body = bplist::parse(&suggestion.body).unwrap();
        assert_eq!(body.get("type").and_then(Value::as_str), Some("suggestUI"));
        assert_eq!(body.get("params").and_then(|params| params.get("urls")), Some(&Value::array(vec![Value::str(MAP_URL)])));
        assert!(timeout(Duration::from_millis(100), f.car.read(&mut byte)).await.is_err());
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let setup = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        assert_eq!(setup_stream::select(&body, 111, false).unwrap().get("uuid").and_then(Value::as_str), Some(AUX));
        reply(&mut f.car, &suggestion, 200, &[]).await;
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    async fn fixture_with_live_main(suggest: bool) -> (crate::screen_resource::tests::Fixture, Value, ScreenSender, tokio::net::TcpStream) {
        use crate::screen_resource::tests::Fixture;
        use tokio::net::{TcpListener, TcpStream};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        Arc::get_mut(&mut f.session).unwrap().uuid = MAIN.into();
        let mut info = startup_info(None);
        if suggest {
            let Value::Dict(fields) = &mut info else { unreachable!() };
            fields.push((Value::str("altScreenSuggestUIURLs"), Value::array(vec![Value::str(MAP_URL)])));
        }
        f.session.control.cluster_display.set(Offer::AwaitingDisplays.resolve(&info, &accepted_alt_screen()).unwrap()).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (peer, _) = listener.accept().await.unwrap();
        let mut main = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
        main.attach_diagnostics(f.session.params.pipeline.clone(), 47);
        main.send_video_annexb(SAMPLE, 1000).await.unwrap();
        (f, info, main, peer)
    }

    #[tokio::test]
    async fn same_round_card_rebind_discards_the_old_frame_and_reuses_show_ui() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, net::TcpListener, time::timeout};
        static CARD: AtomicU64 = AtomicU64::new(1);
        struct RebindingMap(u64);
        impl MapSource for RebindingMap {
            fn sample(&self, _: Sample) -> Option<Vec<u8>> { Some(SAMPLE.to_vec()) }
            fn is_current(&self) -> bool { CARD.load(Ordering::Acquire) == self.0 }
            fn with_frame_write(&self, _: u64, _: bool, _: fn() -> MapStatus,
                write: &mut dyn FnMut() -> io::Result<usize>) -> Option<io::Result<usize>> {
                // Rebind at the first write of the already sampled old access unit.
                if self.0 == 1 { CARD.store(2, Ordering::Release); }
                self.is_current().then(write)
            }
        }
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let video = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)),
            ("dataPort", Value::int(i64::from(video.local_addr().unwrap().port()))),
        ])]))]));
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, card| Some(RebindingMap(card)),
            || MapStatus { card: CARD.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let setup = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let (mut old_peer, _) = timeout(Duration::from_secs(2), video.accept()).await.unwrap().unwrap();
        let mut header = [0_u8; 128];
        assert_eq!(timeout(Duration::from_secs(2), old_peer.read(&mut header)).await
            .expect("the old card kept its socket whilst the replacement was ready").unwrap(), 0);

        let teardown = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(timeout(Duration::from_millis(40), f.car.read(&mut header)).await.is_err());
        reply(&mut f.car, &teardown, 200, &[]).await;
        let setup = timeout(Duration::from_secs(2), read(&mut f.car)).await.unwrap();
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let (mut new_peer, _) = timeout(Duration::from_secs(2), video.accept()).await.unwrap().unwrap();
        for opcode in [1, 0] {
            timeout(Duration::from_secs(2), new_peer.read_exact(&mut header)).await.unwrap().unwrap();
            assert_eq!(header[4], opcode);
            let mut body = vec![0; u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
            timeout(Duration::from_secs(2), new_peer.read_exact(&mut body)).await.unwrap().unwrap();
        }
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        timeout(Duration::from_secs(2), new_peer.read_exact(&mut header)).await.unwrap().unwrap();
        assert_eq!(header[4], 0, "repeated showUI interrupted the active instrument stream");
        assert!(commands.handle("stopUI", &command(AUX, "")));
        let teardown = timeout(Duration::from_secs(3), read(&mut f.car)).await.unwrap();
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        reply(&mut f.car, &teardown, 200, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn instrument_stream_drains_a_started_frame_after_card_rebind_and_backpressure() {
        use crate::screen_resource::tests::{read, reply};
        use std::{os::fd::AsRawFd, sync::atomic::AtomicU64};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream, UdpSocket}, time::{sleep, timeout}};
        static CARD: AtomicU64 = AtomicU64::new(1);
        struct BackpressuredSocket;
        impl crate::airplay::SocketProvider for BackpressuredSocket {
            fn open_tcp(&self, host: &str, port: u16, _: u32) -> Option<TcpStream> {
                let socket = std::net::TcpStream::connect((host, port)).ok()?;
                let buffer = 4096i32;
                assert_eq!(unsafe { libc::setsockopt(socket.as_raw_fd(), libc::SOL_SOCKET, libc::SO_SNDBUF,
                    (&buffer as *const i32).cast(), std::mem::size_of_val(&buffer) as libc::socklen_t) }, 0);
                socket.set_nonblocking(true).ok()?;
                TcpStream::from_std(socket).ok()
            }
            fn open_udp(&self, _: &str, _: u16) -> Option<UdpSocket> { None }
        }
        struct LargeMap(u64);
        impl MapSource for LargeMap {
            fn sample(&self, request: Sample) -> Option<Vec<u8>> {
                let mut sample = SAMPLE.to_vec();
                if matches!(request, Sample::Next(_)) { sample.resize(1024 * 1024, 0x55); }
                Some(sample)
            }
            fn is_current(&self) -> bool { CARD.load(Ordering::Acquire) == self.0 }
            fn with_frame_write(&self, _: u64, _: bool, _: fn() -> MapStatus,
                write: &mut dyn FnMut() -> io::Result<usize>) -> Option<io::Result<usize>> {
                self.is_current().then(write)
            }
        }
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        Arc::get_mut(&mut f.session).unwrap().factory = Arc::new(BackpressuredSocket);
        let video = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)),
            ("dataPort", Value::int(i64::from(video.local_addr().unwrap().port()))),
        ])]))]));
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, card| Some(LargeMap(card)),
            || MapStatus { card: CARD.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        let connection = body.get("streams").unwrap().as_array().unwrap()[0]
            .get("streamConnectionID").unwrap().as_int().unwrap();
        reply(&mut f.car, &setup, 200, &allocated).await;
        let (mut old_peer, _) = timeout(Duration::from_secs(2), video.accept()).await.unwrap().unwrap();
        let mut header = [0_u8; 128];
        timeout(Duration::from_secs(2), old_peer.read_exact(&mut header)).await.unwrap().unwrap();
        assert_eq!(header[4], 1);
        let mut config = vec![0; u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
        old_peer.read_exact(&mut config).await.unwrap();
        timeout(Duration::from_secs(2), old_peer.read_exact(&mut header)).await.unwrap().unwrap();
        assert_eq!(header[4], 0);
        CARD.store(2, Ordering::Release);
        // Sustain real TCP backpressure past the former 750 ms whole-record deadline.
        sleep(Duration::from_millis(900)).await;
        let mut body = vec![0; u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
        assert!(body.len() > 1_000_000);
        timeout(Duration::from_secs(2), old_peer.read_exact(&mut body)).await.unwrap()
            .expect("the instrument stream truncated a started frame under backpressure");
        let key = airplay::stream_key(&f.session.shared.unwrap(), connection as u64);
        assert!(crate::crypto::chacha_open(&key, &[0; 12], &body, &header).is_ok());
        assert_eq!(timeout(Duration::from_secs(2), old_peer.read(&mut header)).await.unwrap().unwrap(), 0);
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut header)).await.is_err());
        assert!(commands.handle("stopUI", &command(AUX, "")));
        reply(&mut f.car, &teardown, 200, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn replacing_a_failed_card_keeps_show_ui_after_its_allocation_is_cleaned() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static CARD: AtomicU64 = AtomicU64::new(1);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus { card: CARD.load(Ordering::Acquire), ..ready_map() });
        assert!(read(&mut f.car).await.first_line.starts_with("POST /command "));
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let setup = read(&mut f.car).await;
        let invalid = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(0)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &invalid).await;
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        {
            let ingress = commands.ingress.as_ref().unwrap().0.lock();
            let IngressState::Active { request, .. } = &*ingress else { unreachable!() };
            assert!(request.borrow().has_live_grant(), "the error revoked the peer's showUI");
        }
        reply(&mut f.car, &teardown, 200, &[]).await;
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(1_100), f.car.read(&mut byte)).await.is_err());
        CARD.store(2, Ordering::Release);
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn each_new_card_rearms_parked_cleanup_once_in_the_same_navigation_round() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static CARD: AtomicU64 = AtomicU64::new(1);
        struct CardMap(u64);
        impl MapSource for CardMap {
            fn sample(&self, _: Sample) -> Option<Vec<u8>> { Some(SAMPLE.to_vec()) }
            fn is_current(&self) -> bool { CARD.load(Ordering::Acquire) == self.0 }
        }
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, card| Some(CardMap(card)), || {
                let card = CARD.load(Ordering::Acquire);
                MapStatus { card, ready: card != 0, ..ready_map() }
            });
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let setup = read(&mut f.car).await;
        CARD.store(0, Ordering::Release);
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let rejected = bplist::encode(&Value::dict(vec![("status", Value::int(17))]));
        for _ in 0..4 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(100), f.car.read(&mut byte)).await.is_err());
        CARD.store(2, Ordering::Release);
        for _ in 0..3 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        assert!(timeout(Duration::from_millis(1_200), f.car.read(&mut byte)).await.is_err());
        CARD.store(3, Ordering::Release);
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(timeout(Duration::from_millis(100), f.car.read(&mut byte)).await.is_err());
        reply(&mut f.car, &teardown, 200, &[]).await;
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn an_in_progress_cleanup_batch_does_not_consume_a_later_cards_wakeup() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static CARD: AtomicU64 = AtomicU64::new(1);
        struct CardMap(u64);
        impl MapSource for CardMap {
            fn sample(&self, _: Sample) -> Option<Vec<u8>> { Some(SAMPLE.to_vec()) }
            fn is_current(&self) -> bool { CARD.load(Ordering::Acquire) == self.0 }
        }
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, card| Some(CardMap(card)), || {
                let card = CARD.load(Ordering::Acquire);
                MapStatus { card, ready: card != 0, ..ready_map() }
            });
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let setup = read(&mut f.car).await;
        CARD.store(0, Ordering::Release);
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let rejected = bplist::encode(&Value::dict(vec![("status", Value::int(17))]));
        for _ in 0..4 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(100), f.car.read(&mut byte)).await.is_err());
        CARD.store(2, Ordering::Release);
        for attempt in 0..3 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            if attempt == 0 { CARD.store(3, Ordering::Release); }
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(timeout(Duration::from_millis(100), f.car.read(&mut byte)).await.is_err());
        reply(&mut f.car, &teardown, 200, &[]).await;
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn first_write_timeout_rebuilds_once_after_cleanup_with_the_same_show_ui() {
        use crate::screen_resource::tests::{read, reply};
        use std::{io::Write, os::fd::AsRawFd, sync::atomic::AtomicUsize};
        use tokio::{io::AsyncReadExt, net::{TcpSocket, TcpStream, UdpSocket}, time::timeout};
        struct PreloadedSocket(AtomicUsize);
        impl crate::airplay::SocketProvider for PreloadedSocket {
            fn open_tcp(&self, host: &str, port: u16, _: u32) -> Option<TcpStream> {
                let mut socket = std::net::TcpStream::connect((host, port)).ok()?;
                let buffer = 4096i32;
                assert_eq!(unsafe { libc::setsockopt(socket.as_raw_fd(), libc::SOL_SOCKET, libc::SO_SNDBUF,
                    (&buffer as *const i32).cast(), std::mem::size_of_val(&buffer) as libc::socklen_t) }, 0);
                socket.set_write_timeout(Some(Duration::from_millis(150))).ok()?;
                // Fill the socket before the instrument sender can write a configuration byte.
                let mut sent = 0;
                while sent < 128 * 1024 {
                    match socket.write(&[0x55; 4096]) {
                        Ok(n) if n > 0 => sent += n,
                        Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => break,
                        result => panic!("unexpected socket preload: {result:?}"),
                    }
                }
                assert!((1..128 * 1024).contains(&sent));
                self.0.store(sent, Ordering::Release);
                socket.set_nonblocking(true).ok()?;
                TcpStream::from_std(socket).ok()
            }
            fn open_udp(&self, _: &str, _: u16) -> Option<UdpSocket> { None }
        }
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let factory = Arc::new(PreloadedSocket(AtomicUsize::new(0)));
        Arc::get_mut(&mut f.session).unwrap().factory = factory.clone();
        let socket = TcpSocket::new_v4().unwrap();
        socket.set_recv_buffer_size(4096).unwrap();
        socket.bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let video = socket.listen(2).unwrap();
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)),
            ("dataPort", Value::int(i64::from(video.local_addr().unwrap().port()))),
        ])]))]));
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        let mut byte = [0];
        for _ in 0..2 {
            let setup = read(&mut f.car).await;
            assert!(setup.first_line.starts_with("SETUP "));
            reply(&mut f.car, &setup, 200, &allocated).await;
            let (mut peer, _) = timeout(Duration::from_secs(2), video.accept()).await.unwrap().unwrap();
            let teardown = read(&mut f.car).await; // Do not drain the video socket until the first write timed out.
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            let mut bytes = Vec::new();
            timeout(Duration::from_secs(2), peer.read_to_end(&mut bytes)).await.unwrap().unwrap();
            assert_eq!(bytes.len(), factory.0.load(Ordering::Acquire));
            assert!(bytes.iter().all(|byte| *byte == 0x55));
            assert!(timeout(Duration::from_millis(300), f.car.read(&mut byte)).await.is_err());
            reply(&mut f.car, &teardown, 200, &[]).await;
        }
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        assert!(timeout(Duration::from_millis(1_250), f.car.read(&mut byte)).await.is_err());
        {
            let ingress = commands.ingress.as_ref().unwrap().0.lock();
            let IngressState::Active { request, .. } = &*ingress else { unreachable!() };
            assert!(request.borrow().has_live_grant());
        }
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn duplicate_show_ui_fences_older_stops_without_revising_the_grant() {
        let now = Instant::now();
        let (commands, rx) = test_commands(now);
        let show = command(AUX, BMW_MAP_URL);
        let reordered = command(AUX, "maps:/car/instrumentcluster/map?showETA=yes&showSpeedLimit=no&showCompass=user");
        assert!(commands.handle_test_at("showUI", &show, now + Duration::from_millis(1)));
        let original = rx.borrow().clone();
        assert!(commands.handle_test_at("showUI", &reordered, now + Duration::from_millis(3)));
        assert!(commands.handle_test_at("stopUI", &command(AUX, ""), now + Duration::from_millis(2)));
        let duplicate = rx.borrow().clone();
        assert_eq!(duplicate.show_revision, original.show_revision);
        assert!(Arc::ptr_eq(duplicate.grant.as_ref().unwrap(), original.grant.as_ref().unwrap()));
        let updated = command(AUX, "maps:/car/instrumentcluster/map?showCompass=user&showETA=no&showSpeedLimit=no");
        assert!(commands.handle_test_at("showUI", &updated, now + Duration::from_millis(4)));
        assert_ne!(rx.borrow().show_revision, original.show_revision);
    }

    #[tokio::test]
    async fn replayed_instrument_commands_are_acknowledged_without_reapplying_selection() {
        use crate::screen_resource::tests::read;
        use tokio::{io::AsyncWriteExt, net::{TcpListener, TcpStream}};

        async fn send(peer: &mut TcpStream, kind: &str, cseq: i32) {
            let Value::Dict(mut fields) = command(AUX, MAP_URL) else { unreachable!() };
            fields.push((Value::str("type"), Value::str(kind)));
            let body = bplist::encode(&Value::Dict(fields));
            let mut wire = format!("POST /command RTSP/1.0\r\nCSeq: {cseq}\r\nContent-Length: {}\r\n\r\n", body.len()).into_bytes();
            wire.extend_from_slice(&body);
            peer.write_all(&wire).await.unwrap();
            let ack = read(peer).await;
            assert_eq!(ack.cseq(), Some(cseq));
            assert_eq!(ack.status(), 200);
        }

        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (_event, _inbound) = f.session.control.spawn_event(r, w, None, Arc::new(|_, _| {}));
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| None::<FixtureMap>, ready_map);
        let current = || {
            let state = commands.ingress.as_ref().unwrap().0.lock();
            let IngressState::Active { request, .. } = &*state else { unreachable!() };
            let selection = request.borrow();
            (selection.has_live_grant(), selection.show_revision, selection.selection)
        };

        send(&mut event_peer, "showUI", 17).await;
        assert!(current().0);
        send(&mut event_peer, "stopUI", 18).await;
        let stopped = current();
        assert!(!stopped.0);
        send(&mut event_peer, "showUI", 17).await;
        assert_eq!(current(), stopped);

        send(&mut f.car, "showUI", 17).await;
        let granted = current();
        assert!(granted.0);
        assert_ne!(granted.1, stopped.1);
        send(&mut event_peer, "stopUI", 18).await;
        assert_eq!(current(), granted);

        send(&mut event_peer, "stopUI", 90).await;
        let stopped = current();
        assert!(!stopped.0);
        send(&mut event_peer, "showUI", 17).await;
        assert_eq!(current(), stopped, "a CSeq outside the replay window must stay retired");
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn repeated_show_ui_does_not_drop_keyframe_for_the_current_grant() {
        let now = Instant::now();
        let (commands, rx) = test_commands(now);
        let show = command(AUX, BMW_MAP_URL);
        let keyframe = command(AUX, "");
        assert!(commands.handle_test_at("showUI", &show, now + Duration::from_millis(1)));
        assert!(commands.handle_test_at("showUI", &show, now + Duration::from_millis(3)));
        assert!(commands.dispatch("forceKeyFrame", &keyframe, now + Duration::from_millis(2)));
        assert_eq!(rx.borrow().keyframe, 1);

        assert!(commands.handle_test_at("stopUI", &command(AUX, ""), now + Duration::from_millis(4)));
        assert!(commands.handle_test_at("showUI", &show, now + Duration::from_millis(5)));
        assert!(commands.dispatch("forceKeyFrame", &keyframe, now + Duration::from_millis(3)));
        assert_eq!(rx.borrow().keyframe, 1);
        assert!(commands.dispatch("forceKeyFrame", &keyframe, now + Duration::from_millis(6)));
        assert_eq!(rx.borrow().keyframe, 2);
    }

    #[test]
    fn equivalent_top_and_nested_map_urls_advance_selection_order_together() {
        let now = Instant::now();
        let (commands, rx) = test_commands(now);
        let both_urls = |top: &str| Value::dict(vec![
            ("url", Value::str(top)),
            ("params", Value::dict(vec![("uuid", Value::str(AUX)), ("url", Value::str(BMW_MAP_URL))])),
        ]);
        assert!(commands.handle_test_at("showUI", &command(AUX, BMW_MAP_URL), now + Duration::from_millis(1)));
        let original = rx.borrow().show_revision;
        let reordered = both_urls("maps:/car/instrumentcluster/map?showETA=yes&showSpeedLimit=no&showCompass=user");
        assert!(commands.handle_test_at("showUI", &reordered, now + Duration::from_millis(3)));
        let stop = command(AUX, "");
        assert!(commands.handle_test_at("stopUI", &stop, now + Duration::from_millis(2)));
        assert!(rx.borrow().has_live_grant());
        assert_eq!(rx.borrow().show_revision, original);

        let conflicting = both_urls("maps:/car/instrumentcluster/map?showETA=no&showSpeedLimit=no&showCompass=user");
        assert!(commands.handle_test_at("showUI", &conflicting, now + Duration::from_millis(5)));
        assert!(commands.handle_test_at("stopUI", &stop, now + Duration::from_millis(4)));
        assert!(!rx.borrow().has_live_grant());
    }

    #[tokio::test]
    async fn a_queued_suggestion_or_setup_is_withdrawn_before_the_first_byte() {
        use tokio::{io::AsyncReadExt, time::timeout};
        for (suggest, revoked_by) in [
            (true, "stopUI"), (true, "shutdown"), (true, "main replacement"),
            (false, "stopUI"), (false, "shutdown"), (false, "main replacement"),
        ] {
            let (mut f, info, _main, _peer) = fixture_with_live_main(suggest).await;
            let channel = f.session.control.clone();
            let writer = channel.hold_writer_for_test().await;
            let before = channel.last_cseq_for_test();
            let mut workers = Vec::new();
            let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
                |_| true, |_, _, _| Some(FixtureMap), ready_map);
            if !suggest { assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL))); }
            timeout(Duration::from_secs(2), async {
                while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
            }).await.unwrap();
            match revoked_by {
                "stopUI" => assert!(commands.handle("stopUI", &command(AUX, ""))),
                "shutdown" => f.session.shutdown.request(true),
                _ => f.session.params.pipeline.lock().main_video_connection = None,
            }
            drop(writer);
            let mut byte = [0];
            assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err(),
                "old request reached the wire: suggest={suggest} revoked_by={revoked_by}");
            assert!(!channel.is_closed());
            drop(commands);
            workers.pop().unwrap().await.unwrap();
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn suggestion_samples_the_route_after_acquiring_the_main_write_guard() {
        use std::sync::atomic::AtomicUsize;
        use tokio::{io::AsyncReadExt, time::timeout};
        static READS: AtomicUsize = AtomicUsize::new(0);
        static READY: AtomicBool = AtomicBool::new(true);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let channel = f.session.control.clone();
        let writer = channel.hold_writer_for_test().await;
        let before = channel.last_cseq_for_test();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || {
                let ready = READY.load(Ordering::Acquire);
                READS.fetch_add(1, Ordering::Release);
                if ready { ready_map() } else { MapStatus::default() }
            });
        timeout(Duration::from_secs(2), async {
            while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
        }).await.unwrap();
        {
            let _main = f.session.params.pipeline.lock();
            drop(writer);
            // The write task is waiting for this lock. Keep the bounded wait off Tokio's timer driver.
            let deadline = Instant::now() + Duration::from_millis(100);
            while READS.load(Ordering::Acquire) < 2 && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(1));
            }
            READY.store(false, Ordering::Release);
        }
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_new_grant_cannot_setup_until_the_old_teardown_is_confirmed() {
        use crate::screen_resource::tests::{read, reply};
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let first = read(&mut f.car).await;
        assert!(first.first_line.starts_with("SETUP "));
        assert!(commands.handle("stopUI", &command(AUX, "")));
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &first, 200, &allocated).await;
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let rejected = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("status", Value::int(17)),
        ])]))]));
        reply(&mut f.car, &teardown, 200, &rejected).await;
        let retry = read(&mut f.car).await;
        assert!(retry.first_line.starts_with("TEARDOWN "));
        let body = bplist::parse(&retry.body).unwrap();
        assert_eq!(setup_stream::select(&body, 111, false).unwrap().get("uuid"), Some(&Value::str(AUX)));
        reply(&mut f.car, &retry, 200, &rejected).await;
        let retry = read(&mut f.car).await;
        assert!(retry.first_line.starts_with("TEARDOWN "));
        reply(&mut f.car, &retry, 200, &[]).await;
        let next = read(&mut f.car).await;
        assert!(next.first_line.starts_with("SETUP "));
        reply(&mut f.car, &next, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn early_selection_is_replayed_only_for_the_negotiated_uuid_and_session() {
        let since = Instant::now();
        let old = since - Duration::from_secs(1);
        let show = command(AUX, MAP_URL);
        let stop = command(AUX, "");
        let ingress = Ingress::default();
        ingress.queue("showUI", &show, old, || true);
        assert!(ingress.attach(AUX.into(), since, Request::default()).borrow().grant.is_none());

        let ingress = Ingress::default();
        ingress.queue("showUI", &show, since + Duration::from_millis(2), || true);
        ingress.queue("stopUI", &stop, since + Duration::from_millis(1), || true);
        ingress.queue("stopUI", &command(MAIN, ""), since + Duration::from_millis(3), || true);
        let rx = ingress.attach(AUX.into(), since, Request::default());
        assert!(rx.borrow().grant.is_some());
        // For a clock tie across the two readers, the explicit withdrawal wins.
        ingress.queue("stopUI", &stop, since + Duration::from_millis(2), || true);
        ingress.queue("showUI", &show, since + Duration::from_millis(2), || true);
        assert!(rx.borrow().grant.is_none());
    }

    #[tokio::test]
    async fn either_rtsp_reader_revokes_queued_setup_before_dispatch() {
        use crate::screen_resource::tests::read;
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}, time::timeout};
        for event_path in [true, false] {
            let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
            let channel = f.session.control.clone();
            let writer = channel.hold_writer_for_test().await;
            let before = channel.last_cseq_for_test();
            let mut workers = Vec::new();
            let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
                |_| true, |_, _, _| Some(FixtureMap), ready_map);
            let mut rx = match &*commands.ingress.as_ref().unwrap().0.lock() {
                IngressState::Active { request, .. } => request.subscribe(),
                _ => unreachable!(),
            };
            assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
            timeout(Duration::from_secs(2), async {
                while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
            }).await.unwrap();
            let event = if event_path {
                let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
                let (peer, _) = listener.accept().await.unwrap();
                let (r, w) = socket.into_split();
                let (event, inbound) = channel.spawn_event(r, w, None, Arc::new(|_, _| {}));
                Some((event, inbound, peer))
            } else { None };
            let mut event = event;
            let wire = event.as_mut().map_or(&mut f.car, |(_, _, peer)| peer);
            wire.write_all(&stop_request()).await.unwrap();
            timeout(Duration::from_secs(2), rx.wait_for(|request| request.grant.is_none())).await.unwrap().unwrap();
            drop(writer);
            let ack = read(wire).await;
            assert_eq!(ack.status(), 200);
            if let Some((_, inbound, _)) = &event { assert_eq!(inbound.len(), 1); }
            let mut byte = [0];
            assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
            drop(commands);
            workers.pop().unwrap().await.unwrap();
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 3)]
    async fn event_stop_can_register_while_the_setup_first_byte_waits_for_main_ownership() {
        use crate::screen_resource::tests::read;
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let channel = f.session.control.clone();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (_event, inbound) = channel.spawn_event(r, w, None, Arc::new(|_, _| {}));
        event_peer.writable().await.unwrap();
        let writer = channel.hold_writer_for_test().await;
        let before = channel.last_cseq_for_test();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        timeout(Duration::from_secs(2), async {
            while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
        }).await.unwrap();
        {
            let _main = f.session.params.pipeline.lock();
            drop(writer);
            let deadline = Instant::now() + Duration::from_secs(1);
            while !channel.has_pending_reply_for_test(before + 1) && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(1));
            }
            assert!(channel.has_pending_reply_for_test(before + 1));
            let stop = stop_request();
            assert_eq!(event_peer.try_write(&stop).unwrap(), stop.len());
            let deadline = Instant::now() + Duration::from_secs(1);
            while inbound.is_empty() && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(1));
            }
            assert_eq!(inbound.len(), 1, "stop registration waited for the main ownership lock");
        }
        assert_eq!(read(&mut event_peer).await.status(), 200);
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_blocked_ack_does_not_delay_instrument_registration_until_a_later_show() {
        use crate::screen_resource::tests::read;
        use tokio::{io::AsyncWriteExt, net::{TcpListener, TcpStream}, time::timeout};
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let channel = f.session.control.clone();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut event_peer, _) = listener.accept().await.unwrap();
        let (r, w) = socket.into_split();
        let (event, mut inbound) = channel.spawn_event(r, w, None, Arc::new(|_, _| {}));
        let writer = event.hold_writer_for_test().await;
        let mut workers = Vec::new();
        let commands = Commands::with_source::<FixtureMap>(&info, &accepted_alt_screen(), f.session.clone(),
            &mut workers, |_| true, |_, _, _| None, ready_map);
        let mut observed = match &*commands.ingress.as_ref().unwrap().0.lock() {
            IngressState::Active { request, .. } => request.subscribe(),
            _ => unreachable!(),
        };
        let ping = bplist::encode(&Value::dict(vec![("type", Value::str("ping"))]));
        event_peer.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 4\r\nContent-Length: {}\r\n\r\n", ping.len()).as_bytes()).await.unwrap();
        event_peer.write_all(&ping).await.unwrap();
        assert_eq!(timeout(Duration::from_secs(2), inbound.recv()).await.unwrap().unwrap().cseq(), Some(4));
        event_peer.write_all(&stop_request()).await.unwrap();
        timeout(Duration::from_secs(2), observed.wait_for(|request| request.selection == 1)).await.unwrap().unwrap();

        let mut show = command(AUX, BMW_MAP_URL);
        let Value::Dict(fields) = &mut show else { unreachable!() };
        fields.push((Value::str("type"), Value::str("showUI")));
        let body = bplist::encode(&show);
        f.car.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 6\r\nContent-Length: {}\r\n\r\n", body.len()).as_bytes()).await.unwrap();
        f.car.write_all(&body).await.unwrap();
        assert_eq!(read(&mut f.car).await.status(), 200);
        drop(writer);
        assert!(observed.borrow().has_live_grant());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn a_route_wakeup_is_consumed_before_it_becomes_unready() {
        let mut cleanup = Cleanup::new(123, Some(0), 0, 1);
        for _ in 0..4 { cleanup.failed(0, "receiver rejected"); }
        assert!(cleanup.deadline(0, Some(1), 1).is_some());
        for _ in 0..3 {
            assert!(cleanup.deadline(0, None, 0).is_some());
            cleanup.failed(0, "receiver rejected");
        }
        assert!(cleanup.deadline(0, Some(1), 1).is_none());
        assert!(cleanup.deadline(0, Some(2), 1).is_some());
    }

    #[tokio::test]
    async fn a_navigation_change_withdraws_setup_waiting_for_the_first_byte() {
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static ROUND: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let channel = f.session.control.clone();
        let writer = channel.hold_writer_for_test().await;
        let before = channel.last_cseq_for_test();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus { round: ROUND.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        timeout(Duration::from_secs(2), async {
            while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
        }).await.unwrap();
        ROUND.store(1, Ordering::Release);
        drop(writer);
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(200), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn late_setup_failure_does_not_revoke_the_new_rounds_map_selection() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        static ROUND: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus { round: ROUND.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let old = read(&mut f.car).await;
        assert!(old.first_line.starts_with("SETUP "));
        ROUND.store(1, Ordering::Release);
        reply(&mut f.car, &old, 400, &[]).await;
        let next = read(&mut f.car).await;
        assert!(next.first_line.starts_with("SETUP "));
        assert_ne!(old.cseq(), next.cseq());
        reply(&mut f.car, &next, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn late_setup_failure_cannot_revoke_selection_on_a_replacement_main_connection() {
        use crate::screen_resource::tests::{read, reply};
        use tokio::time::timeout;
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let old = read(&mut f.car).await;
        assert!(old.first_line.starts_with("SETUP "));
        {
            let mut pipeline = f.session.params.pipeline.lock();
            let mut replacement = pipeline.main_video_connection.clone().unwrap();
            replacement.id += 1;
            pipeline.main_video_connection = Some(replacement);
        }
        reply(&mut f.car, &old, 400, &[]).await;
        let next = timeout(Duration::from_secs(2), read(&mut f.car)).await.expect("replacement SETUP");
        assert!(next.first_line.starts_with("SETUP "));
        assert_ne!(old.cseq(), next.cseq());
        reply(&mut f.car, &next, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_stop_during_old_cleanup_does_not_suppress_the_new_rounds_suggestion() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        static ROUND: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus { round: ROUND.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        ROUND.store(1, Ordering::Release);
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(commands.handle("stopUI", &command(AUX, "")));
        reply(&mut f.car, &teardown, 200, &[]).await;
        let hint = read(&mut f.car).await;
        assert_eq!(bplist::parse(&hint.body).unwrap().get("type").and_then(Value::as_str), Some("suggestUI"));
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_stop_of_a_new_show_during_old_cleanup_suppresses_only_that_round() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static ROUND: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus { round: ROUND.load(Ordering::Acquire), ..ready_map() });
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        ROUND.store(1, Ordering::Release);
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &allocated).await;
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        assert!(commands.handle("showUI", &command(AUX, MAP_URL)));
        assert!(commands.handle("stopUI", &command(AUX, "")));
        reply(&mut f.car, &teardown, 200, &[]).await;
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        ROUND.store(2, Ordering::Release);
        let hint = timeout(Duration::from_secs(2), read(&mut f.car)).await.expect("next route suggestion");
        assert_eq!(bplist::parse(&hint.body).unwrap().get("type").and_then(Value::as_str), Some("suggestUI"));
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn stopping_an_observed_show_does_not_suppress_a_route_that_already_followed_it() {
        let mut suggestion = SuggestionState::default();
        let mut request = Request::default();
        let now = Instant::now();
        assert!(Selection { received_at: now, map_options: Some(0) }.apply(&mut request));
        suggestion.observe(&request, ready_map(), None);
        let cleanup = Cleanup::new(123, Some(0), request.show_revision, 1);
        assert!(Selection { received_at: now + Duration::from_millis(1), map_options: Some(1) }.apply(&mut request));
        assert!(suggestion.needs_update(&request));
        suggestion.observe(&request, MapStatus { round: 1, ..ready_map() }, Some(&cleanup));
        assert!(Selection { received_at: now + Duration::from_millis(2), map_options: None }.apply(&mut request));
        suggestion.observe(&request, MapStatus { round: 2, ..ready_map() }, Some(&cleanup));
        assert_eq!(suggestion.suggested_round, Some(1));
    }

    #[tokio::test]
    async fn retired_stream_retries_without_show_and_keeps_a_new_show_during_the_final_retry() {
        use crate::screen_resource::tests::{read, reply};
        use tokio::{io::AsyncReadExt, time::timeout};
        let (mut f, info, _main, _peer) = fixture_with_live_main(false).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let first = read(&mut f.car).await;
        assert!(first.first_line.starts_with("SETUP "));
        assert!(commands.handle("stopUI", &command(AUX, "")));
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &first, 200, &allocated).await;
        let rejected = bplist::encode(&Value::dict(vec![("status", Value::int(17))]));
        for attempt in 0..=3 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            if attempt == 3 { assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL))); }
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        for _ in 0..3 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(1_250), f.car.read(&mut byte)).await.is_err());
        let ingress = commands.ingress.as_ref().unwrap();
        if let IngressState::Active { request, .. } = &*ingress.0.lock() {
            assert!(request.borrow().grant.as_ref().unwrap().load(Ordering::Acquire));
        } else { unreachable!(); }
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        assert!(timeout(Duration::from_millis(1_250), f.car.read(&mut byte)).await.is_err());
        assert!(commands.handle("stopUI", &command(AUX, "")));
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        reply(&mut f.car, &teardown, 200, &[]).await;
        let next = read(&mut f.car).await;
        assert!(next.first_line.starts_with("SETUP "));
        reply(&mut f.car, &next, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_new_route_rearms_parked_cleanup_before_making_a_suggestion() {
        use crate::screen_resource::tests::{read, reply};
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static ROUTE: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || {
                let round = ROUTE.load(Ordering::Acquire);
                MapStatus { round, ..ready_map() }
            });
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let first = read(&mut f.car).await;
        assert!(first.first_line.starts_with("SETUP "));
        assert!(commands.handle("stopUI", &command(AUX, "")));
        let allocated = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(4400)),
        ])]))]));
        reply(&mut f.car, &first, 200, &allocated).await;
        let rejected = bplist::encode(&Value::dict(vec![("status", Value::int(17))]));
        for _ in 0..4 {
            let teardown = read(&mut f.car).await;
            assert!(teardown.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &teardown, 200, &rejected).await;
        }
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(300), f.car.read(&mut byte)).await.is_err());
        ROUTE.store(1, Ordering::Release);
        for _ in 0..3 {
            let retry = read(&mut f.car).await;
            assert!(retry.first_line.starts_with("TEARDOWN "));
            reply(&mut f.car, &retry, 200, &rejected).await;
        }
        assert!(timeout(Duration::from_millis(1_200), f.car.read(&mut byte)).await.is_err());
        ROUTE.store(2, Ordering::Release);
        let retry = read(&mut f.car).await;
        assert!(retry.first_line.starts_with("TEARDOWN "));
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        reply(&mut f.car, &retry, 200, &[]).await;
        let suggestion = read(&mut f.car).await;
        assert_eq!(bplist::parse(&suggestion.body).unwrap().get("type").and_then(Value::as_str), Some("suggestUI"));
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_failed_stream_preserves_show_ui_for_the_next_route_after_cleanup() {
        use crate::screen_resource::tests::{read, reply};
        use tokio::{io::AsyncReadExt, time::timeout};
        static NEXT_ROUTE: AtomicBool = AtomicBool::new(false);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus {
                round: NEXT_ROUTE.load(Ordering::Acquire) as u64, ..ready_map()
            });
        let initial = read(&mut f.car).await;
        assert!(initial.first_line.starts_with("POST /command "));
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        // An accepted SETUP with no usable video port still leaves an allocation to retire.
        let invalid = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(111)), ("uuid", Value::str(AUX)), ("dataPort", Value::int(0)),
        ])]))]));
        reply(&mut f.car, &setup, 200, &invalid).await;
        let teardown = read(&mut f.car).await;
        assert!(teardown.first_line.starts_with("TEARDOWN "));
        reply(&mut f.car, &teardown, 200, &[]).await;
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        NEXT_ROUTE.store(true, Ordering::Release);
        let next = read(&mut f.car).await;
        assert!(next.first_line.starts_with("SETUP "));
        reply(&mut f.car, &next, 400, &[]).await;
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn suggestion_can_retry_after_the_writer_times_out_before_the_first_byte() {
        use crate::screen_resource::tests::read;
        use tokio::{io::AsyncReadExt, time::timeout};
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let channel = f.session.control.clone();
        let writer = channel.hold_writer_for_test().await;
        let before = channel.last_cseq_for_test();
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), ready_map);
        timeout(Duration::from_secs(2), async {
            while channel.last_cseq_for_test() == before { tokio::task::yield_now().await; }
        }).await.unwrap();
        tokio::time::sleep(Duration::from_millis(3_200)).await;
        drop(writer);
        let suggestion = read(&mut f.car).await;
        assert_eq!(suggestion.cseq(), Some(before + 2));
        assert_eq!(bplist::parse(&suggestion.body).unwrap().get("type").and_then(Value::as_str), Some("suggestUI"));
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(1_200), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[tokio::test]
    async fn a_missing_map_sample_does_not_reissue_a_suggestion() {
        use crate::screen_resource::tests::read;
        use std::sync::atomic::AtomicU64;
        use tokio::{io::AsyncReadExt, time::timeout};
        static ACTIVE: AtomicBool = AtomicBool::new(true);
        static READY: AtomicBool = AtomicBool::new(true);
        static ROUND: AtomicU64 = AtomicU64::new(0);
        let (mut f, info, _main, _peer) = fixture_with_live_main(true).await;
        let mut workers = Vec::new();
        let commands = Commands::with_source(&info, &accepted_alt_screen(), f.session.clone(), &mut workers,
            |_| true, |_, _, _| Some(FixtureMap), || MapStatus {
                round: ROUND.load(Ordering::Acquire), active: ACTIVE.load(Ordering::Acquire), ready: READY.load(Ordering::Acquire), ..ready_map()
            });
        let first = read(&mut f.car).await;
        assert!(first.first_line.starts_with("POST /command "));
        READY.store(false, Ordering::Release);
        tokio::time::sleep(Duration::from_millis(1_200)).await;
        READY.store(true, Ordering::Release);
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(1_100), f.car.read(&mut byte)).await.is_err());
        READY.store(false, Ordering::Release);
        ACTIVE.store(false, Ordering::Release);
        ROUND.store(1, Ordering::Release);
        assert!(commands.handle("stopUI", &command(AUX, "")));
        assert!(timeout(Duration::from_millis(150), f.car.read(&mut byte)).await.is_err());
        ACTIVE.store(true, Ordering::Release);
        READY.store(true, Ordering::Release);
        let next = read(&mut f.car).await;
        assert!(next.first_line.starts_with("POST /command "));
        assert_ne!(first.cseq(), next.cseq());
        drop(commands);
        workers.pop().unwrap().await.unwrap();
    }

    #[test]
    fn top_level_map_url_and_nested_display_uuid_use_the_existing_grant() {
        let (commands, rx) = test_commands(Instant::now());
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let grant = rx.borrow().grant.clone().unwrap();
        let mixed = Value::dict(vec![
            ("params", Value::dict(vec![("uuid", Value::str(&AUX.to_uppercase()))])),
            ("url", Value::str(BMW_MAP_URL)),
        ]);
        assert!(commands.handle("showUI", &mixed));
        assert!(grant.load(Ordering::Acquire));
        assert!(Arc::ptr_eq(&grant, rx.borrow().grant.as_ref().unwrap()));
    }

    #[test]
    fn a_show_with_only_the_instrument_uuid_revokes_the_map() {
        let (commands, rx) = test_commands(Instant::now());
        assert!(commands.handle("showUI", &command(AUX, BMW_MAP_URL)));
        let grant = rx.borrow().grant.clone().unwrap();
        let malformed = Value::dict(vec![("uuid", Value::str(AUX)), ("url", Value::int(42))]);
        assert!(commands.handle("showUI", &malformed));
        assert!(grant.load(Ordering::Acquire));
        let conflicting = Value::dict(vec![
            ("url", Value::str("non-map")),
            ("params", Value::dict(vec![("uuid", Value::str(AUX)), ("url", Value::str(BMW_MAP_URL))])),
        ]);
        assert!(commands.handle("showUI", &conflicting));
        assert!(grant.load(Ordering::Acquire));
        let uuid_only = Value::dict(vec![("params", Value::dict(vec![("uuid", Value::str(AUX))]))]);
        assert!(commands.handle("showUI", &uuid_only));
        assert!(!grant.load(Ordering::Acquire));
        assert!(!rx.borrow().has_live_grant());
    }

    #[test]
    fn ambiguous_initial_url_does_not_grant_the_map() {
        let mut instrument = display(111, AUX);
        let Value::Dict(fields) = &mut instrument else { unreachable!() };
        fields.push((Value::str("initialURL"), Value::str(MAP_URL)));
        fields.push((Value::str("initialURL"), Value::str("")));
        assert!(declared(vec![display(110, MAIN), instrument]).unwrap().initial_map.is_none());
    }
    #[tokio::test]
    async fn requested_cluster_setup_waits_for_a_frame_on_the_current_main_connection() {
        use crate::screen_resource::tests::{Fixture, read, reply};
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}, time::timeout};
        let mut f = Fixture::new(crate::modes::Modes::initial()).await;
        let display = Display { width:64, height:48, safe:[0,0,64,48], uuid:AUX.into(), encoder_fps:2, initial_map:None, suggest_url:None };
        let (commands, rx) = test_commands(Instant::now());
        let worker = tokio::spawn(run(display, f.session.clone(), rx, |_, _, _| Some(FixtureMap), ready_map));
        let request = command(AUX,MAP_URL);
        let pipeline = &f.session.params.pipeline;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let main_sender = async |id| {
            let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
            let (peer,_) = listener.accept().await.unwrap();
            let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha([1;32]), 64, 48, false);
            sender.attach_diagnostics(pipeline.clone(), id);
            (sender,peer)
        };
        let (mut main, _peer) = main_sender(47).await;
        commands.handle("showUI", &request);
        main.send_video_annexb(&[0,0,1,0x41,9],1000).await.unwrap();
        let mut byte = [0];
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        main.send_video_annexb(SAMPLE,2000).await.unwrap();
        commands.handle("forceKeyFrame", &request);
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        let body = bplist::parse(&setup.body).unwrap();
        let aux = setup_stream::select(&body,111,false).unwrap();
        assert_eq!(aux.get("uuid").and_then(Value::as_str), Some(AUX));
        reply(&mut f.car, &setup, 400, &[]).await;

        // A late write on the old connection must not release SETUP for its successor.
        let (mut replacement, _peer2) = main_sender(48).await;
        main.reset_source();
        main.send_video_annexb(SAMPLE,3000).await.unwrap();
        commands.handle("stopUI", &request);
        commands.handle("showUI", &request);
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        replacement.send_video_annexb(SAMPLE,4000).await.unwrap();
        drop(main);
        commands.handle("forceKeyFrame", &request);
        let setup = read(&mut f.car).await;
        assert!(setup.first_line.starts_with("SETUP "));
        reply(&mut f.car, &setup, 400, &[]).await;

        f.session.install(replacement).await.unwrap();
        let (ack, paused) = tokio::sync::oneshot::channel();
        f.session.commands.send(crate::screen_session::ScreenCommand::Pause(ack)).await.unwrap();
        paused.await.unwrap();
        commands.handle("stopUI", &request);
        commands.handle("showUI", &request);
        assert!(timeout(Duration::from_millis(50), f.car.read(&mut byte)).await.is_err());
        drop(commands);
        worker.await.unwrap();
    }
    #[tokio::test]
    async fn independent_avc_frame_is_written_with_its_own_connection_key() {
        use tokio::{io::AsyncReadExt, net::{TcpListener, TcpStream}};
        let sample = SAMPLE;
        let display = Display { width:64, height:48, safe:[0,0,64,48], uuid:AUX.into(), encoder_fps:2, initial_map:None, suggest_url:None };
        assert!(first_idr(sample, &display));
        assert!(!first_idr(sample, &aux()));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let socket = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut peer, _) = listener.accept().await.unwrap();
        let shared = [9;32];
        let key = airplay::stream_key(&shared, 11101);
        let mut sender = ScreenSender::new(socket.into_split().1, ScreenCipher::ChaCha(key), 64, 48, false);
        sender.mark_instrument_map();
        sender.set_ownership(Arc::new(AtomicBool::new(true)));
        sender.send_video_annexb(sample, 1000).await.unwrap();
        drop(sender);
        for expected_opcode in [1,0] {
            let mut header = [0u8;128];
            peer.read_exact(&mut header).await.unwrap();
            assert_eq!(header[4], expected_opcode);
            let mut body = vec![0;u32::from_le_bytes(header[..4].try_into().unwrap()) as usize];
            peer.read_exact(&mut body).await.unwrap();
            if expected_opcode == 1 {
                assert_eq!(crate::h264::avcc_dimensions(&body), Some((64,48)));
            } else {
                let nonce = crate::crypto::nonce64(0);
                let plain = crate::crypto::chacha_open(&key, &nonce, &body, &header).unwrap();
                let idr = crate::screen::annexb_nals(sample).pop().unwrap();
                assert_eq!(&plain[4..], idr);
                assert!(crate::crypto::chacha_open(&airplay::stream_key(&shared, 11001), &nonce, &body, &header).is_err());
            }
        }
    }
    #[test]
    fn declaration_requires_unique_separate_instruments_and_valid_dimensions() {
        let selected = aux();
        assert_eq!(selected.uuid,AUX);
        assert_eq!(selected.safe,[0,0,960,528]);
        assert_eq!(selected.encoder_fps,2);
        let mut limited = display(111,AUX);
        if let Value::Dict(fields) = &mut limited { fields.push((Value::str("maxFPS"),Value::int(1))); }
        assert_eq!(declared(vec![display(110,MAIN), limited]).unwrap().encoder_fps,1);
        for entries in [vec![display(110,MAIN)],vec![display(111,AUX)],
            vec![display(110,MAIN),display(111,MAIN)],vec![display(110,MAIN),display(111,"no uuid")],
            vec![display(110,MAIN),display(111,&MAIN.replace('-', ""))],
            vec![display(110,MAIN),display(111,AUX),display(111,AUX)]] { assert!(declared(entries).is_none()); }
        let area = Value::dict(vec![("widthPixels",Value::int(900)),("heightPixels",Value::int(600)),
            ("safeArea",Value::dict(vec![("originXPixels",Value::int(40)),("originYPixels",Value::int(60)),
                ("widthPixels",Value::int(800)),("heightPixels",Value::int(480))]))]);
        let mut screen=display(111,AUX);
        if let Value::Dict(fields)=&mut screen { fields.push((Value::str("viewAreas"),Value::array(vec![area]))); }
        assert_eq!(declared(vec![display(110,MAIN),screen]).unwrap().safe,[30,44,600,352]);
    }
    #[test]
    fn only_navigation_url_grants_the_auxiliary_uuid_and_stop_revokes_all_old_work() {
        let (commands, rx) = test_commands(Instant::now());
        assert!(!commands.handle("showUI",&command(MAIN,MAP_URL)));
        assert!(!commands.handle("forceKeyFrame",&command(MAIN,"")));
        assert!(commands.handle("showUI",&command(AUX,"unrelated")));
        assert!(rx.borrow().grant.is_none());
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let old=rx.borrow().grant.clone().unwrap();
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        assert!(Arc::ptr_eq(&old,rx.borrow().grant.as_ref().unwrap()));
        assert!(commands.handle("forceKeyFrame",&command(AUX,"")));
        assert_eq!(rx.borrow().keyframe,1);
        assert!(commands.handle("stopUI",&command(AUX,MAP_URL)));
        assert!(!old.load(Ordering::Acquire));
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let next=rx.borrow().grant.clone().unwrap();
        assert!(!Arc::ptr_eq(&old,&next));
        next.store(false,Ordering::Release);
        assert!(commands.handle("showUI",&command(AUX,MAP_URL)));
        let retry=rx.borrow().grant.clone().unwrap();
        drop(commands);
        assert!(!retry.load(Ordering::Acquire));
        assert!(!Commands::default().handle("stopUI",&command(MAIN,"")));
        assert!(Commands { ids:vec![AUX.into()],ingress:None }.handle("forceKeyFrame",&command(AUX,"")));
    }

    fn test_commands(since: Instant) -> (Commands, watch::Receiver<Request>) {
        let ingress = Arc::new(Ingress::default());
        let receiver = ingress.attach(AUX.into(), since, Request::default());
        (Commands { ids: vec![AUX.into()], ingress: Some(ingress) }, receiver)
    }

    #[test]
    fn late_display_commands_cannot_restore_or_revoke_the_newer_selection() {
        let first = Instant::now();
        let (commands, rx) = test_commands(first);
        let stop = command(AUX, "");
        let show = command(AUX, MAP_URL);
        assert!(commands.handle_test_at("stopUI", &stop, first + Duration::from_millis(2)));
        assert!(commands.handle_test_at("showUI", &show, first + Duration::from_millis(1)));
        assert!(rx.borrow().grant.is_none());
        assert!(commands.handle_test_at("showUI", &show, first + Duration::from_millis(3)));
        let new = rx.borrow().grant.clone().unwrap();
        assert!(commands.handle_test_at("showUI", &show, first + Duration::from_millis(5)));
        assert!(commands.handle_test_at("stopUI", &stop, first + Duration::from_millis(4)));
        assert!(!commands.handle_test_at("stopUI", &command(MAIN, ""), first + Duration::from_millis(7)));
        assert!(Arc::ptr_eq(&new, rx.borrow().grant.as_ref().unwrap()));
        assert!(new.load(Ordering::Acquire));
        assert!(commands.handle_test_at("stopUI", &stop, first + Duration::from_millis(6)));
        assert!(!new.load(Ordering::Acquire));
    }

    #[test]
    fn response_port_and_teardown_can_only_select_the_addressed_type111_stream() {
        let d=aux();
        let body=|fields| Value::dict(vec![("streams",Value::array(vec![Value::dict(fields)]))]);
        let decode=|fields| response_port(&body(fields),&d,123);
        assert_eq!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(4400))]),Ok(4400));
        for extra in [("uuid",Value::str(MAIN)),("streamConnectionID",Value::int(124))] {
            assert!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(4400)),extra]).is_err());
        }
        for port in [-1,0,65536] { assert!(decode(vec![("type",Value::int(111)),("dataPort",Value::int(port))]).is_err()); }
        assert!(decode(vec![("type",Value::int(110)),("dataPort",Value::int(4400))]).is_err());
        assert!(decode(vec![("dataPort",Value::int(4400))]).is_err());
        for connection in [None,Some(123)] {
            let wire=bplist::parse(&stream_body(AUX,connection)).unwrap();
            let streams=wire.get("streams").unwrap().as_array().unwrap();
            assert_eq!(streams.len(),1);
            assert_eq!(streams[0].get("type").and_then(Value::as_int),Some(111));
            assert_eq!(streams[0].get("uuid").and_then(Value::as_str),Some(AUX));
            assert_eq!(streams[0].get("streamConnectionID").and_then(Value::as_int),connection);
        }
        assert!(!first_idr(&[0,0,1,0x67,0,0,1,0x68,0,0,1,0x65],&d));
    }
}
