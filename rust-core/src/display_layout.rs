//! Receiver display coordinates. Invalid/missing areas fall back to the visible display.
use crate::bplist::Value;
use parking_lot::Mutex;
use std::sync::atomic::{AtomicI64, Ordering};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rect { pub x: i32, pub y: i32, pub w: i32, pub h: i32 }
impl Rect {
    fn intersect(self, other: Self) -> Option<Self> {
        let x = self.x.max(other.x); let y = self.y.max(other.y);
        let right = (self.x + self.w).min(other.x + other.w);
        let bottom = (self.y + self.h).min(other.y + other.h);
        (right > x && bottom > y).then_some(Self { x, y, w: right-x, h: bottom-y })
    }
    fn read(value: &Value) -> Option<Self> {
        let n = |key| value.get(key).and_then(Value::as_int).and_then(|v| i32::try_from(v).ok());
        let w = n("widthPixels")?; let h = n("heightPixels")?;
        let x = n("originXPixels").unwrap_or(0); let y = n("originYPixels").unwrap_or(0);
        if !(1..=16384).contains(&w) || !(1..=16384).contains(&h) || x.abs_diff(0)>16384 || y.abs_diff(0)>16384 { return None; }
        Some(Self { x, y, w, h })
    }
}
#[derive(Debug, PartialEq)]
pub struct Layout {
    pub view: Rect, pub safe: Rect, pub draw_outside: bool,
    pub status_bar_edge: Option<i32>,
    pub allows_focus: bool,
    allows_transition: bool,
}

fn area_permission(area: Option<&Value>, key: &str) -> bool {
    match area.and_then(|area| area.get(key)) {
        None => true, // Older receivers omitted the field and used negotiation / adjacency alone.
        Some(Value::Bool(flag)) => *flag,
        Some(Value::Int(n)) => *n != 0,
        Some(_) => false,
    }
}

pub(crate) fn right_hand_drive(info: &Value) -> bool {
    let Some(value) = info.get("rightHandDrive") else { return false; };
    // The R14G17 sample supplies a one-element array containing "0" or "1".
    let value = match value { Value::Array(items) if items.len() == 1 => &items[0], value => value };
    matches!(value, Value::Bool(true) | Value::Int(1)) || value.as_str() == Some("1")
}

impl Layout {
    fn from_area(area: Option<&Value>, view: Rect) -> Self {
        let safe_area = area.and_then(|a| a.get("safeArea"));
        let clipped_safe = safe_area.and_then(Rect::read).and_then(|r| r.intersect(view));
        let safe = clipped_safe.unwrap_or(view);
        let policy = area.filter(|a| Rect::read(a).and_then(|r| r.intersect(view)).is_some()
            && (safe_area.is_none() || clipped_safe.is_some()));
        let draw_outside = safe_area.and_then(|a| a.get("drawUIOutsideSafeArea"))
            .map(|v| v.as_bool().unwrap_or_else(|| v.as_int().unwrap_or(0) != 0)).unwrap_or(false);
        Self { view, safe, draw_outside,
            status_bar_edge: policy.and_then(|a| a.get("viewAreaStatusBarEdge"))
                .and_then(Value::as_int)
                .and_then(|n| i32::try_from(n).ok()),
            allows_focus: area_permission(policy, "viewAreaSupportsFocusTransfer"),
            allows_transition: area_permission(policy, "viewAreaTransitionControl"),
        }
    }
}

fn engine_generation() -> u64 {
    crate::callback_context::current().map(|ctx| ctx.generation).unwrap_or(0)
}

fn log_uuid(uuid: Option<&str>) -> &str {
    match uuid {
        None => "missing",
        Some(id) if id.len() <= 80 && id.bytes().all(|b| b.is_ascii_graphic()) => id,
        Some(_) => "nonstandard",
    }
}

fn declared_rect(raw: Option<&Value>) -> String {
    match raw {
        None => "undeclared".into(),
        Some(area) => Rect::read(area).map_or_else(|| "invalid".into(), |r|
            format!("{},{},{}x{}", r.x, r.y, r.w, r.h)),
    }
}

/// Report the receiver's array in wire order, before clipping or full-canvas fallback.
pub(crate) fn log_declaration(source: &Value, uuid: Option<&str>, mut log: impl FnMut(&str)) {
    let prefix = format!("VIEW-AREA: engine={} /info main_uuid={}", engine_generation(), log_uuid(uuid));
    let areas = match source.get("viewAreas") {
        Some(Value::Array(areas)) => areas,
        other => {
            let state = if other.is_some() { "invalid" } else { "undeclared" };
            log(&format!("{prefix} viewAreas={state}"));
            return;
        }
    };
    log(&format!("{prefix} viewAreas={}", areas.len()));
    for (index, area) in areas.iter().enumerate() {
        let owner = crate::airplay::uuid_text(area.get("displayUUID"));
        let scalar = |key| match area.get(key) {
            Some(Value::Int(n)) => n.to_string(), Some(Value::Bool(b)) => b.to_string(),
            Some(_) => "invalid".into(), None => "undeclared".into(),
        };
        log(&format!("{prefix} viewAreaIndex={index} view={} safe={} displayUUID={} transition={} statusBarEdge={} focusTransfer={}",
            declared_rect(Some(area)), declared_rect(area.get("safeArea")), log_uuid(owner.as_deref()),
            scalar("viewAreaTransitionControl"), scalar("viewAreaStatusBarEdge"), scalar("viewAreaSupportsFocusTransfer")));
    }
}

#[derive(Clone)]
pub(crate) struct ViewRequest {
    pub uuid: String,
    pub target: i32,
    pub revision: i64,
}

#[derive(Clone)]
struct ActiveArea {
    index: Option<usize>,
    adjacent: Vec<i32>,
    revision: i64,
    pending: bool,
}

// A queued tap from an old session or from an earlier update must not select a
// different layout, even if the receiver reuses its UUID and array indices.
static NEXT_REVISION: AtomicI64 = AtomicI64::new(1);

/// The event names an index in the array advertised for this session's display.
pub(crate) struct ViewAreas {
    pub width: u32,
    pub height: u32,
    uuid: Option<String>,
    layouts: Vec<Option<Layout>>,
    negotiated: bool,
    right_hand_drive: bool,
    active: Mutex<ActiveArea>,
}

impl ViewAreas {
    pub fn new(source: &Value, width: u32, height: u32, uuid: Option<&str>, negotiated: bool, right_hand_drive: bool) -> Self {
        let full = Rect { x: 0, y: 0, w: width as i32, h: height as i32 };
        let layouts: Vec<_> = source.get("viewAreas").and_then(Value::as_array).into_iter().flatten().map(|area| {
            if let Some(owner) = area.get("displayUUID") {
                if !crate::airplay::uuid_text(Some(owner)).zip(uuid).is_some_and(|(a,b)| a.eq_ignore_ascii_case(b)) {
                    return None;
                }
            }
            let view = Rect::read(area)?.intersect(full)?;
            if let Some(safe) = area.get("safeArea") { Rect::read(safe)?.intersect(view)?; }
            Some(Layout::from_area(Some(area), view))
        }).collect();
        let index = initial_index(source, uuid).filter(|i| layouts.get(*i).is_some_and(Option::is_some));
        let adjacent = Self::adjacent(&layouts, index, source.get("adjacentViewAreas"));
        Self { width, height, uuid: uuid.map(str::to_owned), layouts, negotiated, right_hand_drive,
            active: Mutex::new(ActiveArea { index, adjacent, pending: false, revision: NEXT_REVISION.fetch_add(1, Ordering::Relaxed) }) }
    }

    fn adjacent(layouts: &[Option<Layout>], active: Option<usize>, raw: Option<&Value>) -> Vec<i32> {
        let mut targets = Vec::new();
        let Some(current) = active else { return targets; };
        if !layouts[current].as_ref().is_some_and(|area| area.allows_transition) { return targets; }
        for item in raw.and_then(Value::as_array).into_iter().flatten() {
            let Some(index) = item.as_int().and_then(|n| i32::try_from(n).ok()) else { continue; };
            if index >= 0 && index as usize != current && layouts.get(index as usize).is_some_and(Option::is_some)
                && !targets.contains(&index) { targets.push(index); }
        }
        targets
    }

    fn publish(&self, active: &ActiveArea, layout: &Layout) {
        crate::emit_log(&format!("VIEW-AREA: active={:?} statusBarEdge={:?} rightHandDrive={} focusTransfer={} transition={}",
            active.index, layout.status_bar_edge, self.right_hand_drive, layout.allows_focus, layout.allows_transition));
        crate::emit_display_layout(self.width, self.height, layout, self.right_hand_drive);
        self.publish_choices(active);
    }

    fn publish_choices(&self, active: &ActiveArea) {
        let targets = if self.negotiated && !active.pending && self.uuid.is_some() { &active.adjacent[..] } else { &[] };
        crate::emit_view_area_choices(active.revision, self.uuid.as_deref().unwrap_or(""), targets);
    }

    pub fn publish_initial(&self, fallback: &Layout) {
        let active = self.active.lock();
        let layout = active.index.and_then(|i| self.layouts[i].as_ref()).unwrap_or(fallback);
        crate::emit_log(&format!("VIEW-AREA: main_uuid={} negotiated={} initial={:?} adjacent={:?}",
            log_uuid(self.uuid.as_deref()), self.negotiated, active.index, active.adjacent));
        self.publish(&active, layout);
    }

    pub fn request(&self, request: &ViewRequest) -> Option<Value> {
        let uuid = self.uuid.as_deref()?;
        if !self.negotiated || !uuid.eq_ignore_ascii_case(&request.uuid) { return None; }
        let mut active = self.active.lock();
        if active.pending || active.revision != request.revision || !active.adjacent.contains(&request.target) { return None; }
        active.pending = true;
        self.publish_choices(&active);
        Some(Value::dict(vec![("type", Value::str("requestViewArea")), ("params", Value::dict(vec![
            ("uuid", Value::str(uuid)), ("viewAreaIndex", Value::int(i64::from(request.target))),
        ]))]))
    }

    fn finish_request(&self, revision: i64) -> bool {
        let mut active = self.active.lock();
        if active.revision == revision && active.pending {
            active.pending = false;
            active.revision = NEXT_REVISION.fetch_add(1, Ordering::Relaxed);
            self.publish_choices(&active);
            return true;
        }
        false
    }

    fn select(&self, uuid: Option<&str>, index: Option<i64>) -> Result<&Layout, &'static str> {
        let uuid = uuid.ok_or("missing_or_invalid_uuid")?;
        if !self.uuid.as_ref().ok_or("missing_main_uuid")?.eq_ignore_ascii_case(uuid) {
            return Err("uuid_mismatch");
        }
        let index = usize::try_from(index.ok_or("missing_or_invalid_index")?).map_err(|_| "index_out_of_range")?;
        self.layouts.get(index).ok_or("index_out_of_range")?.as_ref().ok_or("unusable_view_area")
    }
}

/// A successful /command reply only acknowledges the request. Geometry and the
/// next adjacency list arrive independently through updateViewArea on either channel.
pub(crate) async fn send_requests(
    control: std::sync::Arc<crate::rtsp::RtspChannel>,
    mut requests: tokio::sync::mpsc::Receiver<ViewRequest>,
) {
    use std::time::Duration;
    let timeout = Duration::from_secs(3);
    let confirmation_timer = tokio::time::sleep(timeout);
    tokio::pin!(confirmation_timer);
    let mut waiting = None;
    let Some(areas) = control.view_areas.get() else { return; };
    loop {
        tokio::select! {
            _ = &mut confirmation_timer, if waiting.is_some() => {
                if waiting.take().is_some_and(|revision| areas.finish_request(revision)) {
                    crate::emit_log("VIEW-AREA: no receiver update after accepted request; previous choices restored");
                }
            }
            request = requests.recv() => {
                let Some(request) = request else { return; };
                let Some(command) = areas.request(&request) else {
                    crate::emit_log("VIEW-AREA: discarded stale or unavailable switch");
                    continue;
                };
                let body = crate::bplist::encode(&command);
                let result = control.request("POST", "/command", &body, "application/x-apple-binary-plist", &[], timeout).await;
                let accepted = match result {
                    Ok(reply) => {
                        let business = crate::bplist::parse(&reply.body).ok().and_then(|v|v.get("status").and_then(Value::as_int));
                        crate::emit_log(&format!("VIEW-AREA: requestViewArea target={} RTSP={} business={business:?}",
                            request.target, reply.status()));
                        reply.status() == 200 && business.unwrap_or(0) == 0
                    }
                    Err(error) => {
                        crate::emit_log(&format!("VIEW-AREA: requestViewArea target={} failed: {error}", request.target));
                        false
                    }
                };
                if accepted {
                    waiting = Some(request.revision);
                    confirmation_timer.as_mut().reset(tokio::time::Instant::now() + timeout);
                } else {
                    areas.finish_request(request.revision);
                }
            }
        }
    }
}

pub(crate) fn match_update<'a>(areas: Option<&'a ViewAreas>, params: Option<&Value>, log: impl FnOnce(&str))
    -> Result<&'a Layout, &'static str> {
    let uuid = crate::airplay::uuid_text(params.and_then(|p| p.get("uuid")));
    let index = params.and_then(|p| p.get("viewAreaIndex")).and_then(Value::as_int);
    let result = areas.ok_or("no_declaration").and_then(|a| {
        let layout = a.select(uuid.as_deref(), index)?;
        let adjacent = params.and_then(|p| p.get("adjacentViewAreas"));
        if adjacent.is_some_and(|v| v.as_array().is_none()) { return Err("invalid_adjacent_view_areas"); }
        let mut active = a.active.lock();
        active.index = index.and_then(|i| usize::try_from(i).ok());
        active.adjacent = ViewAreas::adjacent(&a.layouts, active.index, adjacent);
        active.pending = false;
        active.revision = NEXT_REVISION.fetch_add(1, Ordering::Relaxed);
        a.publish(&active, layout);
        Ok(layout)
    });
    log(&format!("VIEW-AREA: engine={} updateViewArea uuid={} viewAreaIndex={} result={}",
        engine_generation(), log_uuid(uuid.as_deref()), index.map_or_else(|| "missing_or_invalid".into(), |i| i.to_string()),
        result.as_ref().err().copied().unwrap_or("matched")));
    result
}

fn initial_index(info: &Value, uuid: Option<&str>) -> Option<usize> {
    let areas = info.get("viewAreas")?.as_array()?;
    let owned = |area: &Value| crate::airplay::uuid_text(area.get("displayUUID")).zip(uuid)
        .is_some_and(|(owner, main)| owner.eq_ignore_ascii_case(main));
    if let Some(raw) = info.get("initialViewArea") {
        let index = usize::try_from(raw.as_int()?).ok()?;
        let area = areas.get(index)?;
        return (area.get("displayUUID").is_none() || owned(area)).then_some(index);
    }
    areas.iter().position(owned).or_else(|| areas.iter().position(|a| a.get("displayUUID").is_none()))
}

pub fn read(info: &Value, width: u32, height: u32, display_uuid: Option<&str>) -> Option<Layout> {
    if width == 0 || height == 0 { return None; }
    let full = Rect { x: 0, y: 0, w: width as i32, h: height as i32 };
    let areas = info.get("viewAreas").and_then(Value::as_array);
    let area = areas.and_then(|entries| entries.get(initial_index(info, display_uuid)?));
    let view = area.and_then(Rect::read).and_then(|r| r.intersect(full)).unwrap_or(full);
    Some(Layout::from_area(area, view))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn rect(x:i64,y:i64,w:i64,h:i64) -> Value { Value::dict(vec![("originXPixels",Value::int(x)),("originYPixels",Value::int(y)),("widthPixels",Value::int(w)),("heightPixels",Value::int(h))]) }
    fn info(mut area: Value, safe: Value) -> Value {
        if let Value::Dict(ref mut pairs) = area { pairs.push((Value::str("safeArea"),safe)); }
        Value::dict(vec![("viewAreas",Value::array(vec![area]))])
    }
    #[test]
    fn current_area_controls_outgoing_switch_even_when_the_target_is_adjacent() {
        let disabled = Value::dict(vec![("widthPixels", Value::int(400)), ("heightPixels", Value::int(480)),
            ("viewAreaStatusBarEdge", Value::int(1)), ("viewAreaSupportsFocusTransfer", Value::int(0)),
            ("viewAreaTransitionControl", Value::bool(false))]);
        let source = Value::dict(vec![("viewAreas", Value::array(vec![disabled, rect(0,0,800,480)])),
            ("adjacentViewAreas", Value::array(vec![Value::int(1)]))]);
        let areas = ViewAreas::new(&source, 800, 480, Some("Main"), true, false);
        let layout = read(&source, 800, 480, Some("Main")).unwrap();
        assert_eq!(layout.status_bar_edge, Some(1));
        assert!(!layout.allows_focus);
        assert!(areas.active.lock().adjacent.is_empty());
        let revision = areas.active.lock().revision;
        assert!(areas.request(&ViewRequest { uuid: "Main".into(), revision, target: 1 }).is_none());
        let switch = Value::dict(vec![("uuid", Value::str("Main")), ("viewAreaIndex", Value::int(1)),
            ("adjacentViewAreas", Value::array(vec![Value::int(0)]))]);
        let selected = match_update(Some(&areas), Some(&switch), |_| {}).unwrap();
        assert_eq!(selected.status_bar_edge, None);
        assert!(selected.allows_focus);
        let revision = areas.active.lock().revision;
        assert!(areas.request(&ViewRequest { uuid: "Main".into(), revision, target: 0 }).is_some());
    }
    #[test]
    fn unusable_area_cannot_override_fallback_dock_or_focus() {
        let invalid = Value::dict(vec![("widthPixels", Value::int(0)), ("heightPixels", Value::int(480)),
            ("viewAreaStatusBarEdge", Value::int(1)), ("viewAreaSupportsFocusTransfer", Value::bool(false))]);
        let source = Value::dict(vec![("viewAreas",Value::array(vec![invalid]))]);
        let fallback = read(&source, 800, 480, Some("Main")).unwrap();
        assert_eq!(fallback.status_bar_edge, None);
        assert!(fallback.allows_focus);
        for (value, right) in [(Value::bool(true), true), (Value::array(vec![Value::str("1")]), true),
            (Value::int(0), false), (Value::str("invalid"), false)] {
            assert_eq!(right_hand_drive(&Value::dict(vec![("rightHandDrive", value)])), right);
        }
    }
    #[tokio::test]
    async fn acknowledged_view_request_keeps_geometry_until_update_on_wire() {
        use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::{TcpListener, TcpStream}};
        use std::time::Duration;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut receiver = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (phone, _) = listener.accept().await.unwrap();
        let (read, write) = phone.into_split();
        let (control, mut inbound) = crate::rtsp::RtspChannel::spawn(read, write, None);
        let source = Value::dict(vec![
            ("viewAreas", Value::array(vec![rect(0,0,800,480), rect(0,0,400,480)])),
            ("initialViewArea", Value::int(1)),
            ("adjacentViewAreas", Value::array(vec![Value::int(0)])),
        ]);
        let areas = control.view_areas.get_or_init(|| ViewAreas::new(&source,800,480,Some("Main"),true, false));
        let revision = areas.active.lock().revision;
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        let worker = tokio::spawn(send_requests(control.clone(), rx));
        tx.send(ViewRequest { uuid:"Main".into(), target:0, revision }).await.unwrap();
        let request = tokio::time::timeout(Duration::from_secs(2), async {
            let mut bytes = Vec::new();
            let mut chunk = [0; 2048];
            loop {
                let n = receiver.read(&mut chunk).await.unwrap();
                assert_ne!(n, 0);
                bytes.extend_from_slice(&chunk[..n]);
                if let Some(message) = crate::rtsp::parse_rtsp_messages(&mut bytes).unwrap().pop() { break message; }
            }
        }).await.unwrap();
        let expected = Value::dict(vec![("type", Value::str("requestViewArea")), ("params",Value::dict(vec![
            ("uuid", Value::str("Main")), ("viewAreaIndex", Value::int(0))]))]);
        assert_eq!(request.first_line,"POST /command RTSP/1.0");
        assert_eq!(crate::bplist::parse(&request.body).unwrap(),expected);
        receiver.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: 0\r\n\r\n",request.cseq().unwrap()).as_bytes()).await.unwrap();
        drop(tx);
        tokio::time::timeout(Duration::from_secs(2), worker).await.unwrap().unwrap();
        assert_eq!(areas.active.lock().index,Some(1));
        let update = crate::bplist::encode(&Value::dict(vec![("type",Value::str("updateViewArea")),
            ("params",Value::dict(vec![("uuid",Value::str("Main")),("viewAreaIndex",Value::int(0))]))]));
        receiver.write_all(format!("POST /command RTSP/1.0\r\nCSeq: 40\r\nContent-Length: {}\r\n\r\n",update.len()).as_bytes()).await.unwrap();
        receiver.write_all(&update).await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), inbound.recv()).await.unwrap().unwrap();
        let active = areas.active.lock();
        assert_eq!(active.index,Some(0));
        assert!(active.adjacent.is_empty());
        assert!(!active.pending);
    }

    #[test]
    fn foreign_initial_area_and_malformed_adjacency_never_enable_a_switch() {
        let foreign = Value::dict(vec![("displayUUID", Value::str("aux")), ("widthPixels", Value::int(40)),
            ("heightPixels", Value::int(80))]);
        let source = Value::dict(vec![("viewAreas", Value::array(vec![rect(0,0,200,200), foreign])),
            ("initialViewArea", Value::int(1)), ("adjacentViewAreas", Value::array(vec![Value::int(0)]))]);
        let areas = ViewAreas::new(&source, 800, 480, Some("Main"), true, false);
        assert_eq!(read(&source,800,480,Some("Main")).unwrap().view,Rect{x:0,y:0,w:800,h:480});
        assert_eq!(areas.active.lock().index,None);
        assert!(areas.active.lock().adjacent.is_empty());
        let malformed = Value::dict(vec![("uuid",Value::str("Main")),("viewAreaIndex",Value::int(0)),
            ("adjacentViewAreas", Value::int(1))]);
        assert_eq!(match_update(Some(&areas),Some(&malformed), |_| {}),Err("invalid_adjacent_view_areas"));
        assert_eq!(areas.active.lock().index,None);
    }
    #[test]
    fn initial_area_and_adjacent_requests_follow_receiver_confirmation() {
        let source = Value::dict(vec![
            ("viewAreas", Value::array(vec![rect(0,0,400,480), rect(30,0,700,480), Value::Null])),
            ("initialViewArea", Value::int(1)),
            ("adjacentViewAreas", Value::array(vec![0, 2, 1, 0, 9].into_iter().map(Value::int).collect())),
        ]);
        assert_eq!(read(&source,800,480,Some("Main")).unwrap().view.x,30);
        let areas = ViewAreas::new(&source, 800, 480, Some("Main"), true, false);
        let selection = areas.active.lock().clone();
        assert_eq!(selection.index,Some(1));
        assert_eq!(selection.adjacent,[0]);
        let request = ViewRequest { uuid:"Main".into(), target:0, revision:selection.revision };
        let expected = Value::dict(vec![("type",Value::str("requestViewArea")),("params",Value::dict(vec![
            ("uuid",Value::str("Main")),("viewAreaIndex",Value::int(0))]))]);
        assert_eq!(areas.request(&request), Some(expected));
        assert_eq!(areas.active.lock().index,Some(1));
        assert!(areas.request(&request).is_none());
        assert!(areas.request(&ViewRequest { uuid:"Other".into(), ..request.clone() }).is_none());
        let disabled = ViewAreas::new(&source, 800, 480, Some("Main"), false, false);
        let revision = disabled.active.lock().revision;
        assert!(disabled.request(&ViewRequest { revision, ..request.clone() }).is_none());
        let params = Value::dict(vec![("uuid", Value::str("MAIN")), ("viewAreaIndex",Value::int(0)),
            ("adjacentViewAreas",Value::array(vec![Value::int(1)]))]);
        match_update(Some(&areas),Some(&params), |_| {}).unwrap();
        assert_eq!(areas.active.lock().index,Some(0));
        assert_eq!(areas.active.lock().adjacent,[1]);
        areas.finish_request(request.revision);
        assert_eq!(areas.active.lock().adjacent,[1]);
        assert!(areas.request(&request).is_none());
        let updated = areas.active.lock().revision;
        let next = ViewRequest { uuid:"Main".into(), target:1, revision:updated };
        assert!(areas.request(&next).is_some());
        areas.finish_request(updated);
        assert_eq!(areas.active.lock().index,Some(0));
        assert!(areas.request(&next).is_none()); // A delayed second tap cannot become a retry.
        let fresh = areas.active.lock().revision;
        assert!(areas.request(&ViewRequest { revision:fresh, ..next }).is_some());
    }
    #[test]
    fn view_area_diagnostics_preserve_array_indices_and_original_owner() {
        let context = crate::callback_context::Context::new(None);
        let engine = context.generation;
        crate::callback_context::sync_scope(context, || {
            let source = Value::dict(vec![("viewAreas", Value::array(vec![rect(0, 0, 800, 480),
                Value::Null, Value::dict(vec![("widthPixels", Value::int(700)), ("heightPixels", Value::int(450)),
                    ("safeArea", rect(10, 4, 680, 436))])]))]);
            let mut lines = Vec::new();
            log_declaration(&source, Some("Main"), |s| lines.push(s.to_owned()));
            assert_eq!(lines.len(), 4);
            assert!(lines.iter().all(|s| s.contains(&format!("engine={engine} "))));
            assert!(lines[0].contains("main_uuid=Main viewAreas=3"));
            assert!(lines[1].contains("viewAreaIndex=0 view=0,0,800x480 safe=undeclared"));
            assert!(lines[2].contains("viewAreaIndex=1 view=invalid"));
            assert!(lines[3].contains("viewAreaIndex=2 view=0,0,700x450 safe=10,4,680x436"));
            lines.clear();
            log_declaration(&Value::dict(vec![]), None, |s| lines.push(s.to_owned()));
            assert!(lines[0].contains("main_uuid=missing viewAreas=undeclared"));

            let areas = ViewAreas::new(&source, 800, 480, Some("Main"), true, false);
            for (table, uuid, index, result, status) in [
                (Some(&areas), "MAIN", 2, "matched", 200),
                (Some(&areas), "aux", 2, "uuid_mismatch", 400),
                (Some(&areas), "Main", 1, "unusable_view_area", 400),
                (Some(&areas), "Main", 3, "index_out_of_range", 400),
                (None, "Main", 2, "no_declaration", 501),
            ] {
                let params = Value::dict(vec![("uuid", Value::str(uuid)), ("viewAreaIndex", Value::int(index))]);
                let mut log = String::new();
                let matched = match_update(table, Some(&params), |line| log.push_str(line));
                assert_eq!(matched.is_ok(), status == 200);
                assert!(log.contains(&format!("engine={engine} updateViewArea uuid={uuid} viewAreaIndex={index} result={result}")));
            }
        });
    }
    #[test] fn bmw_reported_area_preserves_asymmetric_edges() {
        let l=read(&info(rect(0,0,2736,798),rect(4,4,2732,794)),2736,800,None).unwrap();
        assert_eq!(l.view,Rect{x:0,y:0,w:2736,h:798}); assert_eq!(l.safe,Rect{x:4,y:4,w:2732,h:794});assert!(!l.draw_outside);
    }
    #[test] fn missing_invalid_and_foreign_areas_fall_back() {
        let full=Rect{x:0,y:0,w:2400,h:1080};
        assert_eq!(read(&Value::dict(vec![]),2400,1080,None).unwrap().safe,full);
        assert_eq!(read(&info(rect(0,0,2400,1000),rect(9000,0,200,100)),2400,1080,None).unwrap().safe.h,1000);
        assert_eq!(read(&info(rect(0,0,-1,1000),rect(0,0,-1,20)),2400,1080,None).unwrap().safe,full);
        let foreign=Value::dict(vec![("viewAreas",Value::array(vec![Value::dict(vec![("displayUUID",Value::str("other")),("widthPixels",Value::int(100)),("heightPixels",Value::int(100))])]))]);
        assert_eq!(read(&foreign,2400,1080,Some("primary")).unwrap().view,full);
    }
    #[test] fn partially_outside_areas_are_clipped() {
        let l=read(&info(rect(20,10,1000,600),rect(0,0,500,400)),800,480,None).unwrap();
        assert_eq!(l.view,Rect{x:20,y:10,w:780,h:470});assert_eq!(l.safe,Rect{x:20,y:10,w:480,h:390});
    }
}
