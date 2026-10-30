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

pub fn read(info: &Value, width: u32, height: u32, display_uuid: Option<&str>) -> Option<Layout> {
    if width == 0 || height == 0 { return None; }
    let full = Rect { x: 0, y: 0, w: width as i32, h: height as i32 };
    let areas = info.get("viewAreas").and_then(Value::as_array);
    let area = areas.and_then(|areas| {
        let matches = |v: &&Value| v.get("displayUUID").and_then(Value::as_str) == display_uuid && display_uuid.is_some();
        areas.iter().find(matches).or_else(|| areas.iter().find(|v| v.get("displayUUID").is_none()))
    });
    let view = area.and_then(Rect::read).and_then(|r| r.intersect(full)).unwrap_or(full);
    let safe = area.and_then(|a| a.get("safeArea")).and_then(Rect::read).and_then(|r| r.intersect(view)).unwrap_or(view);
    let draw_outside = area.and_then(|a| a.get("safeArea")).and_then(|a| a.get("drawUIOutsideSafeArea"))
        .map(|v| v.as_bool().unwrap_or_else(|| v.as_int().unwrap_or(0) != 0)).unwrap_or(false);
    Some(Layout { view, safe, draw_outside })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn rect(x:i64,y:i64,w:i64,h:i64) -> Value { Value::dict(vec![("originXPixels",Value::int(x)),("originYPixels",Value::int(y)),("widthPixels",Value::int(w)),("heightPixels",Value::int(h))]) }
    fn info(mut area: Value, safe: Value) -> Value {
        if let Value::Dict(ref mut pairs) = area { pairs.push((Value::str("safeArea"),safe)); }
        Value::dict(vec![("viewAreas",Value::array(vec![area]))])
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
