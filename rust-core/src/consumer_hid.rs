//! AirPlay HID controls, scoped to the device, application collection and report ID.
use std::collections::{BTreeMap, BTreeSet, HashMap};
use crate::bplist::Value;

#[derive(Clone, Default)]
struct Globals { page: u32, size: usize, count: usize, id: u8, min: i32, max: Option<i64> }
struct Field { id: u8, offset: usize, size: usize, count: usize, variable: bool, relative: bool, controller: bool, min: i32, usages: Vec<u32> }
#[derive(Default)]
struct Device {
    fields: Vec<Field>,
    has_ids: bool,
    touch_reports: BTreeSet<u8>,
    touch_fields: BTreeMap<(u8, usize), TouchFields>,
    report_bits: HashMap<u8, usize>,
    pressed: HashMap<u8, BTreeSet<u32>>,
}

#[derive(Clone, Copy)]
struct TouchField { offset: usize, size: usize, min: i64, max: Option<i64> }

impl TouchField {
    fn read(self, bytes: &[u8]) -> i64 {
        let value = read_bits(bytes, self.offset, self.size);
        if self.min >= 0 { return i64::from(value); }
        let shift = 32 - self.size;
        i64::from(((value << shift) as i32) >> shift)
    }

    fn pixel(self, bytes: &[u8], extent: u32, max: i64) -> u32 {
        let value = self.read(bytes).clamp(self.min, max) - self.min;
        let range = max - self.min;
        let last_pixel = extent.saturating_sub(1);
        if self.min == 0 && range == i64::from(extent) { return (value as u32).min(last_pixel); }
        ((value as u64 * u64::from(last_pixel) + (range as u64 / 2)) / range as u64) as u32
    }
}

#[derive(Default)]
struct TouchFields {
    tip: Option<TouchField>,
    touch: Option<TouchField>,
    identifier: Option<TouchField>,
    transducer: Option<TouchField>,
    ambiguous_identifier: bool,
    x: Option<TouchField>,
    y: Option<TouchField>,
    ambiguous: bool,
}

impl TouchFields {
    fn set(&mut self, usage: u32, field: TouchField) {
        let slot = match usage {
            0xd0042 => &mut self.tip,
            0xd0033 => &mut self.touch,
            0xd0038 | 0xd0051 => {
                let id = if usage == 0xd0051 { &mut self.identifier } else { &mut self.transducer };
                self.ambiguous_identifier |= id.replace(field).is_some();
                return;
            }
            0x10030 => &mut self.x,
            0x10031 => &mut self.y,
            _ => return,
        };
        self.ambiguous |= slot.replace(field).is_some();
    }

    fn sample(&self, bytes: &[u8], display: (u32, u32)) -> Option<TouchSample> {
        let (tip, x, y) = (self.tip.or(self.touch)?, self.x?, self.y?);
        let (max_x, max_y) = (x.max?, y.max?);
        if self.ambiguous || max_x <= x.min || max_y <= y.min { return None; }
        Some(TouchSample { pressed: tip.read(bytes) != 0, x: x.pixel(bytes, display.0, max_x), y: y.pixel(bytes, display.1, max_y) })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TouchSample { pub pressed: bool, pub x: u32, pub y: u32 }

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum TouchIdentity { Identifier(u32), Slot(usize) }

pub(crate) struct MultiTouchReport {
    pub source: (String, u8),
    pub contacts: Vec<(TouchIdentity, TouchSample)>,
}

impl TouchSample {
    // Legacy receivers report pixels as either a single five-byte contact or six bytes per finger.
    pub(crate) fn legacy(report: &[u8]) -> Option<Self> {
        if report.len() < 5 { return None; }
        let contact = if report.len() % 6 == 0 {
            let finger = report.chunks_exact(6).find(|f| f[1] & 1 != 0).unwrap_or(&report[..6]);
            &finger[1..]
        } else { report };
        Some(Self {
            pressed: contact[0] & 1 != 0,
            x: u32::from(u16::from_le_bytes([contact[1], contact[2]])),
            y: u32::from(u16::from_le_bytes([contact[3], contact[4]])),
        })
    }
}

pub const CONSOLE_BACK: u32 = 0x30001;
pub const CONSOLE_SELECT: u32 = 0x30002;
pub const CONSOLE_LEFT: u32 = 0x30003;
pub const CONSOLE_RIGHT: u32 = 0x30004;
pub const CONSOLE_UP: u32 = 0x30005;
pub const CONSOLE_DOWN: u32 = 0x30006;
pub const CONSOLE_PREV: u32 = 0x30007;
pub const CONSOLE_NEXT: u32 = 0x30008;

// Keep the Telephony page: its digit usages overlap Consumer media keys.
pub const PHONE_HOOK: u32 = 0x000b_0020;
pub const PHONE_DROP: u32 = 0x000b_0026;
pub fn is_phone_key(key: u32) -> bool { matches!(key, PHONE_HOOK | PHONE_DROP) }

const MULTI_AXIS_CONTROLLER: u32 = 0x0001_0008;
const MAX_ROTATION_STEPS: usize = 16;

fn supported(u: u32) -> bool { matches!(u, 0xb0|0xb1|0xb3|0xb4|0xb5|0xb6|0xb7|0xb9|0xcd|0xcf) }
fn identity(v: &Value) -> Option<String> {
    v.as_str().map(|s| s.trim().to_ascii_lowercase().trim_start_matches("0x").to_string())
        .or_else(|| v.as_int().filter(|n| *n>=0).map(|n| format!("{n:x}")))
}

fn display_identity(id: &str) -> String {
    let id = id.trim();
    uuid::Uuid::parse_str(id).map_or_else(|_| id.to_ascii_lowercase(), |uuid| uuid.simple().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    // Apple's R14G17 HIDTelephony.c: array indices, including a final keyboard usage.
    const TELEPHONY: &[u8] = &[
        0x05,0x0b,0x09,7,0xa1,1,0x15,0,0x25,0x11,0x05,0x0b,
        0x09,0,0x09,0x20,0x09,0x21,0x09,0x26,0x09,0x2f,
        0x09,0xb0,0x09,0xb1,0x09,0xb2,0x09,0xb3,0x09,0xb4,
        0x09,0xb5,0x09,0xb6,0x09,0xb7,0x09,0xb8,0x09,0xb9,
        0x09,0xba,0x09,0xbb,0x05,7,0x09,0x2a,0x75,8,0x95,1,0x81,0,0xc0,
    ];

    #[test]
    fn phone_array_uses_qualified_usages_and_only_new_press_edges() {
        let id=Value::int(9);
        let info=Value::dict(vec![("hidDevices",Value::array(vec![Value::dict(vec![
            ("uuid",id.clone()),("hidDescriptor",Value::Data(TELEPHONY.to_vec())),
        ])]))]);
        let mut hid=ConsumerHid::from_info(&info);
        assert_eq!(hid.report(Some(&id),&[1]),Some(vec![0xb0020]));
        assert_eq!(hid.report(Some(&id),&[1]),Some(vec![]));
        assert_eq!(hid.report(Some(&id),&[]),Some(vec![]));
        assert_eq!(hid.report(Some(&id),&[1]),Some(vec![]),"short report must not release a held key");
        assert_eq!(hid.report(Some(&id),&[0]),Some(vec![]));
        assert_eq!(hid.report(Some(&id),&[1]),Some(vec![0xb0020]));
        assert_eq!(hid.report(Some(&id),&[3]),Some(vec![0xb0026]));
        for index in [0,2,4,5,8,10,11,17,255] {
            assert_eq!(hid.report(Some(&id),&[index]),Some(vec![]),"telephone digits/Flash/Mute/delete must not control media");
        }
        assert_eq!(hid.report(Some(&id),&[0x20]),Some(vec![]),"the report is an index, not a direct Usage");
        let mut reconnected=ConsumerHid::from_info(&info);
        assert_eq!(reconnected.report(Some(&id),&[3]),Some(vec![0xb0026]));
        assert_eq!(hid.report(Some(&Value::int(99)),&[1]),Some(vec![]));
        assert_eq!(ConsumerHid::default().report(None,&[1]),Some(vec![]));
    }

    #[test]
    fn phone_bitmap_and_report_ids_do_not_alias_media_or_other_devices() {
        let mut descriptor=vec![0x05,0x0b,0x09,7,0xa1,1,0x85,4,
            0x15,0,0x25,1,0x09,0x20,0x09,0x26,0x75,1,0x95,2,0x81,2,
            0x75,6,0x95,1,0x81,1,0xc0];
        descriptor.extend([0x05,0x0c,0x09,1,0xa1,1,0x85,5,0x15,0,0x25,1,
            0x09,0xb5,0x75,1,0x95,1,0x81,2,0x75,7,0x81,1,0xc0]);
        let a=Value::int(1);let b=Value::int(2);
        let mut hid=ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(
            [a.clone(),b.clone()].into_iter().map(|id|Value::dict(vec![
                ("uuid",id),("hidDescriptor",Value::Data(descriptor.clone())),
            ])).collect(),
        ))]));
        assert_eq!(hid.report(Some(&a),&[4,3]),Some(vec![0xb0020,0xb0026]));
        assert_eq!(hid.report(Some(&a),&[5,1]),Some(vec![0xb5]));
        assert_eq!(hid.report(Some(&a),&[4,3]),Some(vec![]));
        assert_eq!(hid.report(Some(&b),&[4,1]),Some(vec![0xb0020]));
        assert_eq!(hid.report(Some(&a),&[4]),Some(vec![]));
        assert_eq!(hid.report(Some(&a),&[8,1]),Some(vec![]));
        assert_eq!(hid.report(Some(&a),&[4,0]),Some(vec![]));
        assert_eq!(hid.report(Some(&a),&[4,2]),Some(vec![0xb0026]));
        let wrong_page=[0x05,0x0c,0x09,1,0xa1,1,0x09,0x20,0x09,0x26,
            0x15,0,0x25,1,0x75,1,0x95,2,0x81,2,0xc0];
        let mut consumer=Device::parse(&wrong_page).unwrap();
        assert!(consumer.report(&[3]).is_empty(),"Consumer page 0x20/0x26 are not phone operations");
    }
    // MGU-81 /info hard keys and touchscreen, and G38 NBT Evo hard keys.
    const MGU_KNOB: &[u8] = &[
        0x05,1,0x09,8,0xa1,1,0x05,9,0x09,1,0x15,0,0x25,1,0x75,1,0x95,1,0x81,2,
        0x05,0x0c,0x75,1,0x95,1,0x81,1,0x0a,0x24,2,0x15,0,0x25,1,0x75,1,0x95,1,
        0x81,2,0x75,5,0x95,1,0x81,1,0x05,1,0x09,0x30,0x09,0x31,0x15,0x81,0x25,
        0x7f,0x75,8,0x95,2,0x81,2,0x05,1,0x09,0x38,0x15,0x81,0x25,0x7f,0x75,8,
        0x95,1,0x81,6,0xc0,
    ];
    const G38_KNOB: &[u8] = &[
        0x05,1,0x09,8,0xa1,1,0x05,9,0x09,1,0x15,0,0x25,1,0x75,1,0x95,1,0x81,2,
        0x05,0x0c,0x0a,0x24,2,0x95,1,0x81,2,0x95,6,0x81,1,0x05,1,0x09,0x38,
        0x15,0x81,0x25,0x7f,0x75,8,0x95,1,0x81,6,0xc0,
    ];
    const MGU_TOUCH: &[u8] = &[
        0x05,0x0d,0x09,4,0xa1,1,0x05,0x0d,0x09,0x22,0xa1,2,0x05,0x0d,0x09,0x33,
        0x15,0,0x25,1,0x75,1,0x95,1,0x81,2,0x75,7,0x95,1,0x81,1,0x05,1,0x09,
        0x30,0x15,0,0x26,0x66,0x0a,0x75,0x10,0x95,1,0x81,2,0x09,0x31,0x15,0,0x26,
        0x2c,3,0x75,0x10,0x95,1,0x81,2,0xc0,0xc0,
    ];
    #[test]
    fn bmw_absolute_buttons_and_axes_emit_once_until_release_or_reversal() {
        let mut d = Device::parse(MGU_KNOB).unwrap();
        assert_eq!(d.report(&[0,0,0,2]), vec![0x30008; 2]);
        assert_eq!(d.report(&[0,0,0,0xff]), vec![0x30007]);
        assert_eq!(d.report(&[1,0,0,0]), vec![0x30002]);
        assert!(d.report(&[1,0,0,0]).is_empty());
        assert_eq!(d.report(&[4,0,0,0]), vec![0x30001]);
        assert!(d.report(&[0,0,0,0]).is_empty());
        assert_eq!(d.report(&[4,0,0,0]), vec![0x30001]);
        assert_eq!(d.report(&[0,0xff,0,0]), vec![0x30003]);
        assert!(d.report(&[0,0x81,0,0]).is_empty());
        assert_eq!(d.report(&[0,0x7f,0,0]), vec![0x30004]);
        assert_eq!(d.report(&[0,0,0xff,0]), vec![0x30005]);
        assert_eq!(d.report(&[0,0,1,0]), vec![0x30006]);
    }
    #[test]
    fn bmw_relative_wheel_counts_signed_detents_on_each_report_with_a_burst_limit() {
        let mut d = Device::parse(G38_KNOB).unwrap();
        assert_eq!(d.report(&[2,0]), vec![0x30001]);
        assert_eq!(d.report(&[0,2]), vec![0x30008; 2]);
        assert_eq!(d.report(&[0,2]), vec![0x30008; 2]);
        assert_eq!(d.report(&[0,0xfd]), vec![0x30007; 3]);
        assert_eq!(d.report(&[0,0x7f]), vec![0x30008; 16]);
        assert_eq!(d.report(&[0,0x81]), vec![0x30007; 16]);
        assert!(d.report(&[0,0]).is_empty());
        assert!(d.report(&[0xb5]).is_empty());
    }
    #[test]
    fn bmw_composite_report_ids_keep_touch_media_and_console_independent() {
        let mut all = Vec::new();
        for (id, desc) in [(1, MGU_TOUCH), (2, MGU_KNOB), (3, &bitmap(false)[..])] {
            all.extend_from_slice(&desc[..6]);
            all.extend([0x85,id]);
            all.extend_from_slice(&desc[6..]);
        }
        let id = Value::int(7);
        let mut h = ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(vec![
            Value::dict(vec![("uuid",id.clone()),("hidDescriptor",Value::Data(all))]),
        ]))]));
        assert_eq!(h.report(Some(&id), &[1,1,0xb5,0,0xb6,0]), None);
        assert_eq!(h.touch_sample(Some(&id), &[1,1,0xb5,0,0xb6,0], (2662,812)),
            Some(TouchSample { pressed:true, x:0xb5, y:0xb6 }));
        assert_eq!(h.report(Some(&id), &[2,4,0,0,0]), Some(vec![0x30001]));
        assert_eq!(h.report(Some(&id), &[3,4]), Some(vec![0xb5]));
        assert_eq!(h.report(Some(&id), &[2,4,0,0,0]), Some(vec![]));
        assert_eq!(h.report(Some(&id), &[8,0xb6]), Some(vec![]));
    }
    #[test]
    fn bmw_declared_touch_uuid_cannot_fall_back_to_raw_media_or_knob() {
        let touch = Value::int(0);
        let knob = Value::int(1);
        let mut h = ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(vec![
            Value::dict(vec![("uuid",touch.clone()),("hidDescriptor",Value::Data(MGU_TOUCH.to_vec()))]),
            Value::dict(vec![("uuid",knob.clone()),("hidDescriptor",Value::Data(G38_KNOB.to_vec()))]),
        ]))]));
        assert_eq!(h.report(Some(&touch), &[0xb5]), None);
        assert_eq!(h.report(Some(&touch), &[1,0xb6,0,0xb5,0]), None);
        assert_eq!(h.touch_sample(Some(&touch), &[1,0xb6,0,0xb5,0], (2662,812)),
            Some(TouchSample { pressed:true, x:0xb6, y:0xb5 }));
        assert_eq!(h.report(Some(&knob), &[1,0]), Some(vec![0x30002]));
        assert_eq!(h.report(None, &[2,0]), Some(vec![]));
        assert_eq!(h.report(None, &[0xb5]), Some(vec![]));
        assert_eq!(h.report(Some(&Value::int(9)), &[0xb6]), Some(vec![]));
    }

    #[test]
    fn finger_collections_use_the_active_contacts_signed_axes_and_reject_short_reports() {
        let mut descriptor = vec![0x05,0x0d,0x09,4,0xa1,1,0x85,7];
        let finger = [
            0x05,0x0d,0x09,0x22,0xa1,2,0x09,0x42,0x15,0,0x25,1,0x75,1,0x95,1,0x81,2,
            0x75,7,0x95,1,0x81,1,0x05,1,0x09,0x30,0x09,0x31,
            0x16,0,0xff,0x26,0xff,0,0x75,16,0x95,2,0x81,2,0xc0,
        ];
        descriptor.extend(finger);
        descriptor.extend(finger);
        descriptor.push(0xc0);
        let id = Value::int(1);
        let hid = ConsumerHid::from_info(&Value::dict(vec![("hidDevices", Value::array(vec![
            Value::dict(vec![("uuid", id.clone()), ("hidDescriptor", Value::Data(descriptor))]),
        ]))]));
        let report = [7,0,0,0,0,0,1,0x80,0xff,0x80,0];
        assert_eq!(hid.touch_sample(Some(&id), &report, (200,200)), Some(TouchSample { pressed:true, x:50, y:150 }));
        assert_eq!(hid.touch_sample(Some(&id), &report[..10], (200,200)), None);
        assert_eq!(hid.touch_sample(None, &report[..10], (200,200)), None);
    }

    #[test]
    fn secondary_single_touch_is_rejected_before_legacy_fallback() {
        let touch = Value::int(1);
        let controls = Value::int(2);
        let mut hid = ConsumerHid::for_session(5, &Value::dict(vec![("hidDevices", Value::array(vec![
            Value::dict(vec![("uuid", touch.clone()), ("displayUUID", Value::str("cluster")),
                ("hidDescriptor", Value::Data(MGU_TOUCH.to_vec()))]),
            Value::dict(vec![("uuid", controls.clone()), ("displayUUID", Value::str("cluster")),
                ("hidDescriptor", Value::Data(bitmap(false)))]),
        ]))]), "main");
        let report = [1, 20, 0, 30, 0];
        let size = (2662, 812);

        assert!(hid.multi_touch_samples(Some(&touch), &report, size).is_err());
        assert_eq!(hid.touch_sample(Some(&touch), &report, size), None);
        assert_eq!(hid.report(Some(&controls), &[4]), Some(vec![0xb5]));
        assert!(matches!(hid.multi_touch_samples(Some(&controls), &[4], size), Ok(None)));

        hid.main_display = Some(display_identity("CLUSTER"));
        assert!(matches!(hid.multi_touch_samples(Some(&touch), &report, size), Ok(None)));
        assert_eq!(hid.touch_sample(Some(&touch), &report, size), Some(TouchSample { pressed: true, x: 20, y: 30 }));
    }

    #[test]
    fn legacy_touch_requires_main_affinity_when_an_auxiliary_touch_device_is_declared() {
        let aux = Value::int(1);
        let main = Value::int(2);
        let unknown = Value::int(3);
        let size = (2662, 812);
        let report = [1, 20, 0, 30, 0];
        for target in [Value::str("cluster"), Value::Data(vec![1; 16]), Value::int(7)] {
            for descriptor in [None, Some(vec![]), Some(MGU_TOUCH.to_vec())] {
                let mut aux_entry = vec![("uuid", aux.clone()), ("displayUUID", target.clone())];
                if let Some(descriptor) = descriptor { aux_entry.push(("hidDescriptor", Value::Data(descriptor))); }
                let info = Value::dict(vec![("hidDevices", Value::array(vec![
                    Value::dict(aux_entry),
                    Value::dict(vec![("uuid", main.clone()), ("displayUUID", Value::str("main"))]),
                ]))]);
                let mut hid = ConsumerHid::for_session(5, &info, "main");
                for uuid in [Some(&aux), Some(&unknown), None] {
                    assert!(hid.report(uuid, &report).is_none());
                    assert_eq!(hid.touch_sample(uuid, &report, size), None);
                }
                assert_eq!(hid.touch_sample(Some(&main), &report, size),
                    Some(TouchSample { pressed: true, x: 20, y: 30 }));
            }
        }
    }

    #[test]
    fn a_legacy_hid_can_target_the_main_display_in_the_canonical_uuid_encodings() {
        let id = Value::int(1);
        let report = [1, 20, 0, 30, 0];
        let size = (2662, 812);
        for (target, main) in [
            (Value::Data(vec![1; 16]), "01010101010101010101010101010101"),
            (Value::str("01010101010101010101010101010101"), "01010101-0101-0101-0101-010101010101"),
            (Value::int(7), "7"),
        ] {
            let info = Value::dict(vec![("hidDevices", Value::array(vec![
                Value::dict(vec![("uuid", id.clone()), ("displayUUID", target)]),
            ]))]);
            let hid = ConsumerHid::for_session(5, &info, main);
            assert!(hid.touch_sample(None, &report, size).is_some());
            assert!(hid.touch_sample(Some(&id), &report, size).is_some());
        }
    }

    #[test]
    fn multi_touch_uses_declared_transducer_ids_and_rejects_duplicate_active_contacts() {
        let mut descriptor = vec![0x05,0x0d,0x09,4,0xa1,1,0x85,7];
        let finger = [
            0x05,0x0d,0x09,0x22,0xa1,2,
            0x09,0x38,0x15,0,0x25,15,0x75,8,0x95,1,0x81,2,
            0x09,0x33,0x25,1,0x75,1,0x81,2,0x75,7,0x81,1,
            0x05,1,0x09,0x30,0x09,0x31,0x25,100,0x75,16,0x95,2,0x81,2,0xc0,
        ];
        descriptor.extend(finger);
        descriptor.extend(finger);
        descriptor.push(0xc0);
        let id = Value::int(1);
        let hid = ConsumerHid::from_info(&Value::dict(vec![("hidDevices", Value::array(vec![
            Value::dict(vec![("uuid", id.clone()), ("hidDescriptor", Value::Data(descriptor))]),
        ]))]));
        let mut report = [7,9,1,20,0,30,0,4,1,80,0,60,0];
        let samples = hid.multi_touch_samples(Some(&id), &report, (100,100)).unwrap().unwrap();
        assert_eq!(samples.contacts, vec![
            (TouchIdentity::Identifier(9), TouchSample { pressed:true, x:20, y:30 }),
            (TouchIdentity::Identifier(4), TouchSample { pressed:true, x:80, y:60 }),
        ]);
        assert!(hid.multi_touch_samples(Some(&id), &report[..12], (100,100)).is_err());
        report[7] = 9;
        assert!(hid.multi_touch_samples(Some(&id), &report, (100,100)).is_err());
        report[8] = 0;
        assert_eq!(hid.multi_touch_samples(Some(&id), &report, (100,100)).unwrap().unwrap().contacts.len(), 1);
    }
    #[test]
    fn ambiguous_and_unknown_devices_cannot_inject_media_or_touch_from_a_control_report() {
        let mut desc = MGU_KNOB.to_vec();
        desc.splice(6..6, [0x85,7]);
        let mut h = ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(vec![
            Value::dict(vec![("uuid",Value::int(1)),("hidDescriptor",Value::Data(desc))]),
            Value::dict(vec![("uuid",Value::int(2))]),
        ]))]));
        let unknown = Value::int(3);
        for uuid in [None, Some(&unknown)] {
            assert_eq!(h.report(uuid, &[0xb5]), Some(vec![]));
            assert_eq!(h.report(uuid, &[7,1,0,0,0]), Some(vec![]));
        }
        assert_eq!(h.report(Some(&Value::int(2)), &[1,1,0,2,0]), None);
        assert_eq!(h.report(Some(&Value::int(1)), &[7,1,0,0,0]), Some(vec![0x30002]));
    }
    #[test]
    fn one_declared_long_control_layout_can_resolve_a_missing_uuid() {
        let mut desc = MGU_KNOB.to_vec();
        desc.splice(desc.len()-1..desc.len()-1, [0x75,8,0x95,2,0x81,1]);
        let mut h = ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(vec![
            Value::dict(vec![("uuid",Value::int(1)),("hidDescriptor",Value::Data(desc))]),
        ]))]));
        assert_eq!(h.report(None, &[1,0,0,0,0,0]), Some(vec![0x30002]));
    }
    #[test] fn voice_hid_has_down_and_up_edges_and_does_not_repeat() {
        let mut h=ConsumerHid::default();
        assert_eq!(h.report(None,&[0xcf]),Some(vec![0xcf]));
        assert_eq!(h.report(None,&[0xcf]),Some(vec![]));
        assert_eq!(h.report(None,&[0]),Some(vec![crate::voice_key::HID_UP]));
        assert_eq!(h.report(None,&[0]),Some(vec![]));
        let descriptor=[0x05,0x0c,0x09,1,0xa1,1,0x75,1,0x95,1,0x09,0xcf,0x81,2,0xc0];
        let mut d=Device::parse(&descriptor).unwrap();
        assert_eq!(d.report(&[1]),vec![0xcf]);
        assert_eq!(d.report(&[0]),vec![crate::voice_key::HID_UP]);
    }
    fn bitmap(id:bool)->Vec<u8> {
        let mut d=vec![0x05,0x0c,0x09,0x01,0xa1,0x01];
        if id {d.extend([0x85,7]);}
        d.extend([0x15,0,0x25,1,0x75,1,0x95,5,0x09,0xb0,0x09,0xb1,0x09,0xb5,0x09,0xb6,0x09,0xb9,0x81,2,0x75,3,0x95,1,0x81,3,0xc0]);d
    }
    fn array()->Vec<u8>{vec![0x05,0x0c,0x09,1,0xa1,1,0x15,0,0x25,3,0x09,0,0x09,0xb5,0x09,0xb6,0x09,0xcd,0x75,8,0x95,1,0x81,0,0xc0]}
    fn registry()->ConsumerHid {
        ConsumerHid::from_info(&Value::dict(vec![("hidDevices",Value::array(vec![
            Value::dict(vec![("uuid",Value::int(0)),("hidDescriptor",Value::Data(bitmap(false)))]),
            Value::dict(vec![("uuid",Value::int(1)),("hidDescriptor",Value::Data(array()))]),
        ]))]))
    }
    #[test] fn session_summary_counts_track_edges_separately_from_reports() {
        let mut h = registry();
        let device = Value::int(0);
        h.report(Some(&device), &[4]);
        h.report(Some(&device), &[4]);
        h.report(Some(&Value::int(99)), &[8]);
        let summary = h.diagnostic_summary();
        assert!(summary.contains("uuid=0 track_descriptor=both reports=2 next=1 previous=0"));
        assert!(summary.contains("uuid=1 track_descriptor=both reports=0 next=0 previous=0"));
        assert!(summary.contains("unmatched_reports=1"));
        assert!(registry().diagnostic_summary().contains("uuid=0 track_descriptor=both reports=0 next=0 previous=0"));
    }
    #[test] fn session_summary_keeps_bmw_knob_and_touch_separate_from_track_keys() {
        let mut hid = ConsumerHid::from_info(&Value::dict(vec![("hidDevices", Value::array(vec![
            Value::dict(vec![("uuid", Value::int(0)), ("hidDescriptor", Value::Data(MGU_KNOB.to_vec()))]),
            Value::dict(vec![("uuid", Value::int(1)), ("hidDescriptor", Value::Data(MGU_TOUCH.to_vec()))]),
        ]))]));
        assert_eq!(hid.report(Some(&Value::int(0)), &[0, 0, 0, 2]), Some(vec![CONSOLE_NEXT; 2]));
        assert_eq!(hid.report(Some(&Value::int(1)), &[1, 0, 0, 0, 0]), None);
        let summary = hid.diagnostic_summary();
        assert!(summary.contains("uuid=0 track_descriptor=none reports=1 next=0 previous=0"));
        assert!(summary.contains("uuid=1 track_descriptor=none reports=1 next=0 previous=0"));
    }
    #[test] fn bitmap_padding_and_array_indices_are_different() {
        let mut b=Device::parse(&bitmap(false)).unwrap();
        assert_eq!(b.report(&[4]),vec![0xb5]);
        assert!(b.report(&[4]).is_empty());
        assert!(b.report(&[0]).is_empty());
        assert_eq!(b.report(&[8]),vec![0xb6]);
        let mut a=Device::parse(&array()).unwrap();
        assert_eq!(a.report(&[1]),vec![0xb5]);
        assert_eq!(a.report(&[2]),vec![0xb6]);
        assert!(a.report(&[4]).is_empty());
    }
    #[test] fn report_ids_and_offsets_do_not_turn_padding_into_keys() {
        let mut d=bitmap(true);
        // Insert an eight-bit constant prefix before the real Consumer field.
        d.splice(8..8,[0x75,8,0x95,1,0x81,3]);
        let mut device=Device::parse(&d).unwrap();
        assert!(device.report(&[6,0xff,4]).is_empty());
        assert_eq!(device.report(&[7,0xff,4]),vec![0xb5]);
        assert!(device.report(&[7]).is_empty());
        assert!(device.report(&[7,0xff,0]).is_empty());
        assert_eq!(device.report(&[7,0,4]),vec![0xb5]);
    }
    #[test] fn device_uuid_isolates_press_state_and_touch_is_preserved() {
        let mut h=registry();let zero=Value::str("0");let one=Value::int(1);
        assert_eq!(h.report(Some(&zero),&[4]),Some(vec![0xb5]));
        assert_eq!(h.report(Some(&one),&[1]),Some(vec![0xb5]));
        assert_eq!(h.report(Some(&zero),&[4]),Some(vec![]));
        assert!(h.report(Some(&Value::str("touch")),&[1,10,0,20,0]).is_none());
        assert_eq!(h.report(Some(&one),&[0]),Some(vec![]));
        assert_eq!(h.report(Some(&zero),&[4]),Some(vec![]));
        assert_eq!(h.report(Some(&one),&[1]),Some(vec![0xb5]));
    }
    #[test] fn diagnostics_expose_offsets_and_short_report_without_coordinates() {
        let d=Device::parse(&bitmap(true)).unwrap();
        let layout=d.diagnostic_layout();
        assert!(layout.contains("id=7 offset=0 size=1 count=5"));
        assert!(layout.contains("c00b5"));
        assert!(d.diagnostic_report(&[7]).contains("short=true"));
        assert!(d.diagnostic_report(&[7,4]).contains("short=false"));
        assert!(d.diagnostic_report(&[6,4]).contains("fields=0"));
    }
    #[test] fn diagnostic_sampling_does_not_change_key_edges_or_exceed_budgets() {
        let mut h=registry();
        for _ in 0..100 {
            assert_eq!(h.report(Some(&Value::int(0)),&[4]),Some(vec![0xb5]));
            assert_eq!(h.report(Some(&Value::int(0)),&[0]),Some(vec![]));
            assert!(h.report(Some(&Value::str("touch")),&[1,10,0,20,0]).is_none());
        }
        assert_eq!(h.short_logs,64);
        assert_eq!(h.long_logs,8);
    }
    #[test] fn direct_usage_fallback_deduplicates_but_never_guesses_04() {
        let mut h=ConsumerHid::default();
        assert_eq!(h.report(None,&[0xb5]),Some(vec![0xb5]));
        assert_eq!(h.report(None,&[0xb5]),Some(vec![]));
        assert_eq!(h.report(None,&[0]),Some(vec![]));
        assert_eq!(h.report(None,&[0xb5,0]),Some(vec![0xb5]));
        assert_eq!(h.report(None,&[4]),Some(vec![]));
    }
    #[test] fn usage_ranges_and_logical_minimum_are_respected() {
        let descriptor=[0x05,0x0c,0x09,1,0xa1,1,0x15,1,0x25,2,0x19,0xb5,0x29,0xb6,0x75,8,0x95,1,0x81,0,0xc0];
        let mut d=Device::parse(&descriptor).unwrap();
        assert_eq!(d.report(&[1]),vec![0xb5]);assert_eq!(d.report(&[2]),vec![0xb6]);
    }
    #[test] fn malformed_descriptors_and_short_reports_do_not_panic_or_press_keys() {
        let b=bitmap(false);
        for len in 0..b.len() {let _=Device::parse(&b[..len]);}
        assert!(Device::parse(&[0x75,0xff,0x95,0xff,0x81,2]).is_none());
        assert!(Device::parse(&[0xfe,10]).is_none());
        assert!(Device::parse(&b).unwrap().report(&[]).is_empty());
    }
}
fn qualified(v: u32, page: u32) -> u32 { if v>0xffff { v } else { (page<<16)|v } }

fn read_bits(bytes: &[u8], offset: usize, size: usize) -> u32 {
    (0..size).fold(0, |value, i| {
        let bit = offset + i;
        value | (u32::from((bytes[bit / 8] >> (bit % 8)) & 1) << i)
    })
}

impl Device {
    fn parse(bytes: &[u8]) -> Option<Self> {
        if bytes.len()>4096 { return None; }
        let (mut at, mut g, mut stack) = (0, Globals::default(), Vec::new());
        let (mut usages, mut range) = (Vec::new(), None);
        let mut device = Self::default();
        let (mut application, mut finger, mut next_finger) = (0, 0, 0);
        let mut collections = Vec::new();
        while at<bytes.len() {
            let prefix=bytes[at]; at+=1;
            if prefix==0xfe { let len=*bytes.get(at)? as usize; at=at.checked_add(2+len)?; if at>bytes.len(){return None;} continue; }
            let size=match prefix&3 {3=>4,n=>n as usize};
            let data=bytes.get(at..at+size)?; at+=size;
            let value=data.iter().enumerate().fold(0u32,|v,(i,b)|v|((*b as u32)<<(8*i)));
            let signed = || match size { 1 => value as i8 as i32, 2 => value as i16 as i32, _ => value as i32 };
            match ((prefix>>2)&3, prefix>>4) {
                (1,0)=>g.page=value,
                (1,1)=>g.min=signed(),
                (1,2)=>g.max=Some(if g.min<0 { i64::from(signed()) } else { i64::from(value) }),
                (1,7)=>g.size=value as usize,
                (1,8)=>{if value==0||value>255{return None;} g.id=value as u8;device.has_ids=true;},
                (1,9)=>g.count=value as usize,
                (1,10)=>{if stack.len()>16{return None;}stack.push(g.clone());},
                (1,11)=>g=stack.pop()?,
                (2,0)=>usages.push(qualified(value,g.page)),
                (2,1)=>range=Some(qualified(value,g.page)),
                (2,2)=>{
                    let start=range.take()?; let end=qualified(value,g.page);
                    if end<start || end-start>512{return None;} usages.extend(start..=end);
                },
                (0,10)=>{
                    collections.push((application, finger));
                    if value == 1 { application = usages.first().copied().unwrap_or(0); finger = 0; }
                    if usages.first() == Some(&0xd0022) { next_finger += 1; finger = next_finger; }
                    usages.clear();range=None;
                },
                (0,12)=>{
                    (application, finger) = collections.pop()?;
                    usages.clear();range=None;
                },
                (0,8)=>{
                    if g.size==0||g.size>32||g.count>256{return None;}
                    let offset=*device.report_bits.get(&g.id).unwrap_or(&0);
                    let end=offset.checked_add(g.size.checked_mul(g.count)?)?;
                    if end>32768{return None;} device.report_bits.insert(g.id,end);
                    let controller = application == MULTI_AXIS_CONTROLLER;
                    if application >> 16 == 0x0d {
                        device.touch_reports.insert(g.id);
                        if application == 0xd0004 && value & 7 == 2 {
                            for (index, &usage) in usages.iter().take(g.count).enumerate() {
                                if matches!(usage, 0xd0042 | 0xd0033 | 0xd0038 | 0xd0051 | 0x10030 | 0x10031) {
                                    device.touch_fields.entry((g.id, finger)).or_default().set(usage, TouchField {
                                        offset: offset + index*g.size, size: g.size, min: i64::from(g.min), max: g.max,
                                    });
                                }
                            }
                        }
                    } else if value&1==0 && usages.iter().any(|u|matches!(u>>16, 0x0b | 0x0c) ||
                        controller && matches!(u,0x90001|0x10030|0x10031|0x10038)) {
                        device.fields.push(Field{id:g.id,offset,size:g.size,count:g.count,
                            variable:value&2!=0,relative:value&4!=0,controller,min:g.min,usages:usages.clone()});
                    }
                    usages.clear();range=None;
                },
                (0,_)=>{usages.clear();range=None;},
                _=>{},
            }
        }
        (collections.is_empty() && (!device.fields.is_empty() || !device.touch_reports.is_empty())).then_some(device)
    }

    fn diagnostic_layout(&self) -> String {
        self.fields.iter().take(8).map(|f| format!(
            "id={} offset={} size={} count={} variable={} relative={} min={} usages={:x?}",
            f.id,f.offset,f.size,f.count,f.variable,f.relative,f.min,
            f.usages.iter().take(24).collect::<Vec<_>>()
        )).collect::<Vec<_>>().join("; ")
    }

    fn is_touch_report(&self, bytes: &[u8]) -> bool {
        let id = if self.has_ids { bytes.first().copied() } else { Some(0) };
        id.is_some_and(|id| self.touch_reports.contains(&id))
    }

    fn matches_report_length(&self, bytes: &[u8]) -> bool {
        let (id, body) = if self.has_ids {
            let Some((&id, body)) = bytes.split_first() else { return false; };
            (id, body)
        } else {
            (0, bytes)
        };
        self.report_bits.get(&id).is_some_and(|bits| bits.div_ceil(8) == body.len())
    }

    fn diagnostic_report(&self, bytes: &[u8]) -> String {
        let (id,len)=if self.has_ids { (bytes.first().copied().unwrap_or(0),bytes.len().saturating_sub(1)) } else { (0,bytes.len()) };
        let fields:Vec<_>=self.fields.iter().filter(|f| f.id==id).collect();
        let required=self.report_bits.get(&id).copied().unwrap_or(0);
        format!("report_id={id} fields={} bits={} required_bits={required} short={}", fields.len(),len*8,len*8<required)
    }

    fn report(&mut self, bytes: &[u8]) -> Vec<u32> {
        let (id,body)=if self.has_ids {match bytes.split_first(){Some((id,b))=>(*id,b),None=>return vec![]}} else {(0,bytes)};
        let Some(&required) = self.report_bits.get(&id) else { return vec![]; };
        if required > body.len()*8 { return vec![]; }
        let mut down=BTreeSet::new();
        let mut rotation = Vec::new();
        for f in self.fields.iter().filter(|f|f.id==id) {
            for i in 0..f.count {
                let value=read_bits(body, f.offset+i*f.size, f.size);
                let usage=if f.variable {
                    if value==0 {continue;} f.usages.get(i).or_else(||f.usages.last()).copied()
                } else {
                    let index=value as i64-f.min as i64;
                    if index<0 {continue;} f.usages.get(index as usize).copied()
                };
                let Some(u) = usage else { continue; };
                if u >> 16 == 0x0c && supported(u & 0xffff) {
                    down.insert(u & 0xffff);
                } else if is_phone_key(u) {
                    down.insert(u);
                } else if u == 0xc0224 {
                    down.insert(CONSOLE_BACK);
                } else if f.controller && f.variable {
                    // BMW describes its push axes as signed, absolute and centered at zero.
                    // Wheel is a signed relative delta, including successive identical samples.
                    let shift = 32 - f.size;
                    let signed = ((value << shift) as i32) >> shift;
                    match (u, f.relative) {
                        (0x90001, false) => { down.insert(CONSOLE_SELECT); }
                        (0x10030, false) if f.min < 0 => {
                            down.insert(if signed < 0 { CONSOLE_LEFT } else { CONSOLE_RIGHT });
                        }
                        (0x10031, false) if f.min < 0 => {
                            down.insert(if signed < 0 { CONSOLE_UP } else { CONSOLE_DOWN });
                        }
                        (0x10038, true) if f.min < 0 => {
                            let count = (signed.unsigned_abs() as usize).min(MAX_ROTATION_STEPS - rotation.len());
                            let direction = if signed < 0 { CONSOLE_PREV } else { CONSOLE_NEXT };
                            rotation.resize(rotation.len() + count, direction);
                        }
                        _ => {}
                    }
                }
            }
        }
        let previous=self.pressed.entry(id).or_default();
        let mut edges:Vec<u32>=down.difference(previous).copied().collect();
        if previous.contains(&0xcf)&&!down.contains(&0xcf){edges.push(crate::voice_key::HID_UP);}
        edges.extend(rotation);
        *previous=down; edges
    }
}

#[derive(Default)]
struct ReportCounts {
    reports: u64,
    next: u64,
    previous: u64,
}

#[derive(Default)]
pub struct ConsumerHid {
    devices: HashMap<String, Device>,
    advertised: BTreeSet<Option<String>>,
    display_targets: BTreeMap<Option<String>, BTreeSet<Option<String>>>,
    raw: HashMap<String, BTreeSet<u32>>,
    unknown: usize,
    short_logs: usize,
    long_logs: usize,
    session_id: Option<u64>,
    counts: BTreeMap<String, ReportCounts>,
    unmatched: ReportCounts,
    main_display: Option<String>,
}
impl ConsumerHid {
    pub fn for_session(session_id: u64, info: &Value, main_display: &str) -> Self {
        let mut hid = Self::from_info(info);
        hid.session_id = Some(session_id);
        hid.main_display = Some(display_identity(main_display));
        hid
    }
    pub fn from_info(info:&Value)->Self {
        let mut result=Self::default();
        for key in ["hidDevices","hids","hid"] {
            for entry in info.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]).iter().take(32) {
                let id = entry.get("uuid").and_then(identity);
                result.advertised.insert(id.clone());
                if let Some(target) = entry.get("displayUUID") {
                    let display = crate::airplay::uuid_text(Some(target)).map(|id| display_identity(&id));
                    result.display_targets.entry(id.clone()).or_default().insert(display);
                }
                let Some(id) = id else { continue; };
                result.counts.entry(id.clone()).or_default();
                let descriptor=entry.get("hidDescriptor").or_else(||entry.get("reportDescriptor")).and_then(Value::as_bytes);
                if let Some(device)=descriptor.and_then(Device::parse) {
                    crate::emit_log(&format!("HID-KEY: device={id} descriptor={}B control_fields={} report_ids={} touch_reports={:?}",descriptor.unwrap().len(),device.fields.len(),device.has_ids,device.touch_reports));
                    crate::emit_log(&format!("HID-LAYOUT: device={id} {}",device.diagnostic_layout()));
                    result.devices.insert(id,device);
                } else {
                    crate::emit_log(&format!("HID-LAYOUT: device={id} descriptor_bytes={:?} consumer_layout=unavailable; touch fallback preserved",descriptor.map(|d| d.len())));
                }
            }
        }
        crate::emit_log(&format!("HID-KEY: registered input devices={}",result.devices.len()));
        result
    }
    fn diagnostic_summary(&self) -> String {
        let mut devices = Vec::new();
        for (id, counts) in &self.counts {
            let track = self.devices.get(id).map_or("unknown", |device| {
                let declares = |usage| device.fields.iter().any(|f| f.usages.contains(&usage));
                match (declares(0xc00b5), declares(0xc00b6)) {
                    (true, true) => "both",
                    (true, false) => "next",
                    (false, true) => "previous",
                    (false, false) => "none",
                }
            });
            devices.push(format!("uuid={id} track_descriptor={track} reports={} next={} previous={}",
                counts.reports, counts.next, counts.previous));
        }
        format!("devices=[{}] unmatched_reports={} unmatched_next={} unmatched_previous={}",
            devices.join("; "), self.unmatched.reports, self.unmatched.next, self.unmatched.previous)
    }
    fn layout_id(&self, uuid: Option<&Value>, bytes: &[u8]) -> Option<String> {
        if let Some(uuid) = uuid { return identity(uuid); }
        if self.advertised.len() != 1 { return None; }
        let (id, device) = self.devices.iter().next()?;
        // A known touch Report ID still identifies a truncated report. Keep it
        // on the descriptor path so an old pixel-layout fallback cannot tap.
        (bytes.len() < 5 || device.is_touch_report(bytes) || device.matches_report_length(bytes)).then(|| id.clone())
    }

    fn touch_targets_main_display(&self, layout: Option<&String>) -> bool {
        let Some(main) = self.main_display.as_ref() else { return true; };
        let targets_main = |targets: &BTreeSet<Option<String>>| {
            targets.iter().all(|target| target.as_ref() == Some(main))
        };
        if let Some(id) = layout.filter(|id| self.counts.contains_key(*id)) {
            return self.display_targets.get(&Some(id.clone())).is_none_or(targets_main);
        }
        // An unmatched report can use legacy coordinates only if it cannot belong
        // to an advertised foreign touch device, including one without a descriptor.
        self.display_targets.iter().all(|(id, targets)| {
            targets_main(targets) || id.as_ref().and_then(|id| self.devices.get(id))
                .is_some_and(|device| device.touch_reports.is_empty())
        })
    }

    pub(crate) fn touch_sample(&self, uuid: Option<&Value>, bytes: &[u8], display: (u32, u32)) -> Option<TouchSample> {
        let layout = self.layout_id(uuid, bytes);
        if !self.touch_targets_main_display(layout.as_ref()) { return None; }
        let Some(device) = layout.as_ref().and_then(|id| self.devices.get(id)) else {
            return TouchSample::legacy(bytes);
        };
        let (id, body) = if device.has_ids { let (&id, body) = bytes.split_first()?; (id, body) } else { (0, bytes) };
        if !device.touch_reports.contains(&id) || body.len() * 8 < *device.report_bits.get(&id)? { return None; }
        let mut released = None;
        for (_, fields) in device.touch_fields.range((id, 0)..=(id, usize::MAX)) {
            if let Some(sample) = fields.sample(body, display) {
                if sample.pressed { return Some(sample); }
                released.get_or_insert(sample);
            }
        }
        released.or_else(|| TouchSample::legacy(body))
    }

    pub(crate) fn multi_touch_samples(&self, uuid: Option<&Value>, bytes: &[u8], display: (u32, u32))
        -> Result<Option<MultiTouchReport>, ()> {
        let layout = self.layout_id(uuid, bytes);
        if !self.touch_targets_main_display(layout.as_ref()) {
            let device = layout.as_ref().and_then(|id| self.devices.get(id));
            if device.map_or(bytes.len() >= 5, |device| device.is_touch_report(bytes)) { return Err(()); }
        }
        let Some(key) = layout else { return Ok(None); };
        let Some(device) = self.devices.get(&key) else { return Ok(None); };
        let (id, body) = if device.has_ids {
            let Some((&id, body)) = bytes.split_first() else { return Ok(None); };
            (id, body)
        } else { (0, bytes) };
        let fingers = device.touch_fields.range((id, 1)..=(id, usize::MAX));
        let count = fingers.clone().count();
        if count < 2 { return Ok(None); }
        if count > 16 || body.len() * 8 < device.report_bits[&id] { return Err(()); }
        if fingers.clone().any(|(_, fields)| fields.ambiguous_identifier || fields.sample(body, display).is_none()) {
            return Ok(None); // Incomplete descriptors retain the single-contact parser.
        }
        let mut contacts = Vec::with_capacity(count);
        let mut active = BTreeSet::new();
        for (&(_, slot), fields) in fingers {
            let sample = fields.sample(body, display).ok_or(())?;
            if !sample.pressed { continue; }
            let identity = match fields.identifier.or(fields.transducer) {
                Some(field) => TouchIdentity::Identifier(u32::try_from(field.read(body)).map_err(|_| ())?),
                None => TouchIdentity::Slot(slot),
            };
            if !active.insert(identity) { return Err(()); }
            contacts.push((identity, sample));
        }
        Ok(Some(MultiTouchReport { source: (key, id), contacts }))
    }

    /// Some = control report consumed (including releases). None = forward to the touch decoder.
    pub fn report(&mut self,uuid:Option<&Value>,bytes:&[u8])->Option<Vec<u32>> {
        let id=self.layout_id(uuid,bytes);
        let keys = self.decode_report(id.as_ref(), bytes);
        let counts = id.as_ref().and_then(|id| self.counts.get_mut(id)).unwrap_or(&mut self.unmatched);
        counts.reports += 1;
        for key in keys.iter().flatten() {
            match key {
                0xb5 => counts.next += 1,
                0xb6 => counts.previous += 1,
                _ => {}
            }
        }
        keys
    }

    fn decode_report(&mut self, id: Option<&String>, bytes: &[u8]) -> Option<Vec<u32>> {
        let budget=if bytes.len()<5 { &mut self.short_logs } else { &mut self.long_logs };
        let limit=if bytes.len()<5 {64} else {8};
        if *budget<limit {
            *budget+=1;
            let device=id.and_then(|id|self.devices.get(id));
            let layout=device.map(|d|d.diagnostic_report(bytes)).unwrap_or_else(||"layout=unmatched".into());
            // Never dump touch coordinates; short control reports alone get a hex sample.
            let sample=if bytes.len()<5 && !device.is_some_and(|d|d.is_touch_report(bytes)) {format!("{:02x?}",bytes)} else {"omitted-touch-or-long-report".into()};
            crate::emit_log(&format!("HID-INPUT: device={id:?} bytes={} {layout} sample={sample} short_budget={}/64 long_budget={}/8",bytes.len(),self.short_logs,self.long_logs));
        }
        if let Some(device)=id.and_then(|id|self.devices.get_mut(id)) {
            if device.is_touch_report(bytes) { return None; }
            return Some(device.report(bytes));
        }
        if bytes.len()>=5 {
            // A declared device lacking a usable descriptor keeps legacy touch decoding.
            // Otherwise an unresolved report matching a control layout must not become a tap.
            let declared = id.is_some_and(|id| self.counts.contains_key(id));
            let possible_control = !declared && self.devices.values().any(|d|
                d.matches_report_length(bytes) && !d.is_touch_report(bytes));
            return possible_control.then(Vec::new);
        }
        if !self.devices.is_empty() { return Some(vec![]); }
        // Without a descriptor only unambiguous direct Usage IDs are supported. 0x04 is not hardcoded.
        let value=match bytes { [v]=>*v as u32,[lo,hi]=>u16::from_le_bytes([*lo,*hi]) as u32,_=>u32::MAX };
        if value==0||supported(value) {
            let down=if value==0 {BTreeSet::new()} else {BTreeSet::from([value])};
            let previous=self.raw.entry(id.cloned().unwrap_or_default()).or_default();
            let mut edges:Vec<u32>=down.difference(previous).copied().collect();
            if previous.contains(&0xcf)&&!down.contains(&0xcf){edges.push(crate::voice_key::HID_UP);}
            *previous=down;return Some(edges);
        }
        if self.unknown<10 {
            self.unknown+=1;
            crate::emit_log(&format!("HID-KEY: unresolved device={id:?} report={bytes:02x?}; need consumer descriptor, not guessing usage"));
        }
        Some(vec![])
    }
}

impl Drop for ConsumerHid {
    fn drop(&mut self) {
        if let Some(id) = self.session_id {
            crate::emit_log(&format!("WIFI-IAP#{id}: AirPlay HID END {}", self.diagnostic_summary()));
        }
    }
}
