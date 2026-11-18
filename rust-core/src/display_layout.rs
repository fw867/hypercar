//! Receiver display coordinates. Invalid/missing areas fall back to the visible display.
use crate::bplist::Value;

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
pub struct Layout { pub view: Rect, pub safe: Rect, pub draw_outside: bool }

impl Layout {
    fn from_area(area: Option<&Value>, view: Rect) -> Self {
        let safe_area = area.and_then(|a| a.get("safeArea"));
        let safe = safe_area.and_then(Rect::read).and_then(|r| r.intersect(view)).unwrap_or(view);
        let draw_outside = safe_area.and_then(|a| a.get("drawUIOutsideSafeArea"))
            .map(|v| v.as_bool().unwrap_or_else(|| v.as_int().unwrap_or(0) != 0)).unwrap_or(false);
        Self { view, safe, draw_outside }
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
        log(&format!("{prefix} viewAreaIndex={index} view={} safe={} displayUUID={}",
            declared_rect(Some(area)), declared_rect(area.get("safeArea")), log_uuid(owner.as_deref())));
    }
}

/// The event names an index in the array advertised for this session's display.
pub(crate) struct ViewAreas {
    pub width: u32,
    pub height: u32,
    uuid: Option<String>,
    layouts: Vec<Option<Layout>>,
}

impl ViewAreas {
    pub fn new(source: &Value, width: u32, height: u32, uuid: Option<&str>) -> Self {
        let full = Rect { x: 0, y: 0, w: width as i32, h: height as i32 };
        let layouts = source.get("viewAreas").and_then(Value::as_array).into_iter().flatten().map(|area| {
            if let Some(owner) = area.get("displayUUID") {
                if !crate::airplay::uuid_text(Some(owner)).zip(uuid).is_some_and(|(a,b)| a.eq_ignore_ascii_case(b)) {
                    return None;
                }
            }
            let view = Rect::read(area)?.intersect(full)?;
            if let Some(safe) = area.get("safeArea") { Rect::read(safe)?.intersect(view)?; }
            Some(Layout::from_area(Some(area), view))
        }).collect();
        Self { width, height, uuid: uuid.map(str::to_owned), layouts }
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

pub(crate) fn match_update<'a>(areas: Option<&'a ViewAreas>, params: Option<&Value>, log: impl FnOnce(&str))
    -> Result<&'a Layout, &'static str> {
    let uuid = crate::airplay::uuid_text(params.and_then(|p| p.get("uuid")));
    let index = params.and_then(|p| p.get("viewAreaIndex")).and_then(Value::as_int);
    let result = areas.ok_or("no_declaration").and_then(|a| a.select(uuid.as_deref(), index));
    log(&format!("VIEW-AREA: engine={} updateViewArea uuid={} viewAreaIndex={} result={}",
        engine_generation(), log_uuid(uuid.as_deref()), index.map_or_else(|| "missing_or_invalid".into(), |i| i.to_string()),
        result.as_ref().err().copied().unwrap_or("matched")));
    result
}

pub fn read(info: &Value, width: u32, height: u32, display_uuid: Option<&str>) -> Option<Layout> {
    if width == 0 || height == 0 { return None; }
    let full = Rect { x: 0, y: 0, w: width as i32, h: height as i32 };
    let areas = info.get("viewAreas").and_then(Value::as_array);
    let area = areas.and_then(|areas| {
        let matches = |v: &&Value| v.get("displayUUID").and_then(Value::as_str) == display_uuid && display_uuid.is_some();
        areas.iter().find(matches).or_else(|| areas.iter().find(|v| v.get("displayUUID").is_none()))
    });
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

            let areas = ViewAreas::new(&source, 800, 480, Some("Main"));
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
