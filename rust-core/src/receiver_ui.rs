use crate::bplist::Value;
use serde_json::{json, Value as Json};

const LIMITED_ELEMENTS: [&str; 5] =
    ["softKeyboard", "softPhoneKeypad", "nonMusicLists", "musicLists", "japanMaps"];

fn field<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.get("params").and_then(|params| params.get(key)).or_else(|| value.get(key))
}

pub(crate) fn display_target(command: &Value) -> Result<Option<String>, ()> {
    let decode = |value| crate::airplay::uuid_text(Some(value)).ok_or(());
    let nested = command.get("params").and_then(|p| p.get("uuid")).map(decode).transpose()?;
    let top = command.get("uuid").map(decode).transpose()?;
    if let (Some(a), Some(b)) = (&nested, &top) {
        if !a.eq_ignore_ascii_case(b) { return Err(()); }
    }
    Ok(nested.or(top))
}

pub(crate) fn main_target(command: &Value, main_uuid: Option<&str>) -> bool {
    match display_target(command) {
        Ok(None) => true,
        Ok(Some(target)) => main_uuid.is_some_and(|main| main.eq_ignore_ascii_case(&target)),
        Err(()) => false,
    }
}

fn elements(value: Option<&Value>) -> Vec<&'static str> {
    LIMITED_ELEMENTS.into_iter().filter(|name| value.and_then(Value::as_array)
        .is_some_and(|entries| entries.iter().any(|entry| entry.as_str() == Some(*name)))).collect()
}

pub(crate) struct LimitedUi {
    elements: Vec<&'static str>,
}

impl LimitedUi {
    pub(crate) fn from_info(info: &Value) -> Self {
        Self { elements: elements(info.get("limitedUIElements")) }
    }

    pub(crate) fn update(&mut self, value: &Value) -> Option<Json> {
        let flag = field(value, "limitedUI")?;
        let enabled = flag.as_bool().or_else(|| match flag.as_int()? {
            0 => Some(false), 1 => Some(true), _ => None,
        })?;
        if let Some(list) = field(value, "limitedUIElements") {
            list.as_array()?;
            self.elements = elements(Some(list));
        }
        Some(json!({ "limitedUI": enabled, "limitedUIElements": self.elements }))
    }
}

pub(crate) fn destination(command: &Value) -> Option<&'static str> {
    let (scheme, _) = field(command, "url")?.as_str()?.split_once(':')?;
    ["maps:", "music:", "nowplaying:", "mobilephone:"].into_iter()
        .find(|target| scheme.eq_ignore_ascii_case(target.trim_end_matches(':')))
}

pub(crate) fn focus(command: &Value, display: (u32,u32)) -> Option<Json> {
    let number = |key| field(command, key).and_then(Value::as_int);
    let (dw, dh) = (i64::from(display.0), i64::from(display.1));
    let (x, y) = (number("originXPixels")?, number("originYPixels")?);
    let (w, h) = (number("widthPixels")?, number("heightPixels")?);
    if x < 0 || y < 0 || w <= 0 || h <= 0 || x.checked_add(w)? > dw || y.checked_add(h)? > dh {
        return None;
    }
    let heading = number("focusHeading")?;
    if !matches!(heading, 1 | 2 | 4 | 8 | 16 | 32) { return None; }
    Some(json!({ "originXPixels": x, "originYPixels": y, "widthPixels": w, "heightPixels": h,
        "focusHeading": heading, "displayWidth": dw, "displayHeight": dh }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keyframes_default_to_main_only_when_untargeted() {
        let target = |fields| main_target(&Value::dict(fields), Some("main"));
        assert!(target(vec![]));
        assert!(target(vec![("uuid", Value::str("MAIN"))]));
        assert!(!target(vec![("params",Value::dict(vec![("uuid",Value::str("unknown"))]))]));
        assert!(!target(vec![("uuid", Value::Null)]));
        assert!(!target(vec![("uuid", Value::str("alt")), ("params", Value::dict(vec![("uuid",Value::str("main"))]))]));
    }

    #[test]
    fn limit_toggle_preserves_declaration_and_malformed_update_is_atomic() {
        let info = Value::dict(vec![("limitedUIElements", Value::array(vec![Value::str("musicLists")]))]);
        let mut ui = LimitedUi::from_info(&info);
        let command = |flag, list| {
            let mut fields = vec![("limitedUI", Value::int(flag))];
            if let Some(value) = list { fields.push(("limitedUIElements", value)); }
            Value::dict(vec![("params", Value::dict(fields))])
        };
        assert!(ui.update(&command(2, Some(Value::array(vec![])))).is_none());
        assert_eq!(ui.update(&command(1, None)).unwrap(), json!({"limitedUI":true,"limitedUIElements":["musicLists"]}));
        assert_eq!(ui.update(&command(0, None)).unwrap()["limitedUI"], false);
    }

    #[test]
    fn only_known_destinations_and_bounded_main_display_focus_are_delivered() {
        assert_eq!(destination(&Value::dict(vec![("url", Value::str("MAPS:/route?private"))])), Some("maps:"));
        assert_eq!(destination(&Value::dict(vec![("url", Value::str("intent:maps"))])), None);
        assert!(!main_target(&Value::dict(vec![("uuid", Value::str("aux"))]), Some("main")));
        let rect = |w| Value::dict(vec![("originXPixels", Value::int(8)), ("originYPixels", Value::int(4)),
            ("widthPixels", Value::int(w)), ("heightPixels", Value::int(10)), ("focusHeading", Value::int(8))]);
        assert!(focus(&rect(20), (100, 50)).is_some());
        assert!(focus(&rect(i64::MAX), (100, 50)).is_none());
    }
}
