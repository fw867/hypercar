//! Consumer Control HID: device/report-specific layouts and key-down edges, never touch coordinates.
use std::collections::{BTreeSet, HashMap};
use crate::bplist::Value;

#[derive(Clone, Default)]
struct Globals { page: u32, size: usize, count: usize, id: u8, min: i32 }
struct Field { id: u8, offset: usize, size: usize, count: usize, variable: bool, min: i32, usages: Vec<u32> }
#[derive(Default)]
struct Device { fields: Vec<Field>, has_ids: bool, pressed: HashMap<u8, BTreeSet<u32>> }

fn supported(u: u32) -> bool { matches!(u, 0xb0|0xb1|0xb3|0xb4|0xb5|0xb6|0xb7|0xb9|0xcd|0xcf) }
fn identity(v: &Value) -> Option<String> {
    v.as_str().map(|s| s.trim().to_ascii_lowercase().trim_start_matches("0x").to_string())
        .or_else(|| v.as_int().filter(|n| *n>=0).map(|n| format!("{n:x}")))
}

#[cfg(test)]
mod tests {
    use super::*;
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

impl Device {
    fn parse(bytes: &[u8]) -> Option<Self> {
        if bytes.len()>4096 { return None; }
        let (mut at, mut g, mut stack) = (0, Globals::default(), Vec::new());
        let (mut usages, mut range) = (Vec::new(), None);
        let mut offsets = HashMap::<u8, usize>::new();
        let mut device = Self::default();
        while at<bytes.len() {
            let prefix=bytes[at]; at+=1;
            if prefix==0xfe { let len=*bytes.get(at)? as usize; at=at.checked_add(2+len)?; if at>bytes.len(){return None;} continue; }
            let size=match prefix&3 {3=>4,n=>n as usize};
            let data=bytes.get(at..at+size)?; at+=size;
            let value=data.iter().enumerate().fold(0u32,|v,(i,b)|v|((*b as u32)<<(8*i)));
            match ((prefix>>2)&3, prefix>>4) {
                (1,0)=>g.page=value,
                (1,1)=>g.min=match size {1=>value as u8 as i8 as i32,2=>value as u16 as i16 as i32,_=>value as i32},
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
                (0,8)=>{
                    if g.size==0||g.size>32||g.count>256{return None;}
                    let offset=*offsets.get(&g.id).unwrap_or(&0);
                    let end=offset.checked_add(g.size.checked_mul(g.count)?)?;
                    if end>32768{return None;} offsets.insert(g.id,end);
                    if value&1==0 && usages.iter().any(|u|u>>16==0x0c) {
                        device.fields.push(Field{id:g.id,offset,size:g.size,count:g.count,variable:value&2!=0,min:g.min,usages:usages.clone()});
                    }
                    usages.clear();range=None;
                },
                (0,_)=>{usages.clear();range=None;},
                _=>{},
            }
        }
        (!device.fields.is_empty()).then_some(device)
    }

    fn diagnostic_layout(&self) -> String {
        self.fields.iter().take(8).map(|f| format!(
            "id={} offset={} size={} count={} variable={} min={} usages={:x?}",
            f.id,f.offset,f.size,f.count,f.variable,f.min,
            f.usages.iter().take(24).collect::<Vec<_>>()
        )).collect::<Vec<_>>().join("; ")
    }

    fn diagnostic_report(&self, bytes: &[u8]) -> String {
        let (id,len)=if self.has_ids { (bytes.first().copied().unwrap_or(0),bytes.len().saturating_sub(1)) } else { (0,bytes.len()) };
        let fields:Vec<_>=self.fields.iter().filter(|f| f.id==id).collect();
        let required=fields.iter().map(|f|f.offset+f.size*f.count).max().unwrap_or(0);
        format!("report_id={id} fields={} bits={} required_bits={required} short={}", fields.len(),len*8,len*8<required)
    }

    fn report(&mut self, bytes: &[u8]) -> Vec<u32> {
        let (id,body)=if self.has_ids {match bytes.split_first(){Some((id,b))=>(*id,b),None=>return vec![]}} else {(0,bytes)};
        let fields:Vec<_>=self.fields.iter().filter(|f|f.id==id).collect();
        if fields.is_empty()||fields.iter().any(|f|f.offset+f.size*f.count>body.len()*8){return vec![];}
        let mut down=BTreeSet::new();
        for f in fields {
            for i in 0..f.count {
                let value=(0..f.size).fold(0u32,|v,b|{let bit=f.offset+i*f.size+b;v|((((body[bit/8]>>(bit%8))&1) as u32)<<b)});
                let usage=if f.variable {
                    if value==0 {continue;} f.usages.get(i).or_else(||f.usages.last()).copied()
                } else {
                    let index=value as i64-f.min as i64;
                    if index<0 {continue;} f.usages.get(index as usize).copied()
                };
                if let Some(u)=usage.filter(|u|u>>16==0x0c && supported(u&0xffff)) {down.insert(u&0xffff);}
            }
        }
        let previous=self.pressed.entry(id).or_default();
        let mut edges:Vec<u32>=down.difference(previous).copied().collect();
        if previous.contains(&0xcf)&&!down.contains(&0xcf){edges.push(crate::voice_key::HID_UP);}
        *previous=down; edges
    }
}

#[derive(Default)]
pub struct ConsumerHid { devices: HashMap<String,Device>, raw: HashMap<String,BTreeSet<u32>>, unknown: usize, short_logs: usize, long_logs: usize }
impl ConsumerHid {
    pub fn from_info(info:&Value)->Self {
        let mut result=Self::default();
        for key in ["hidDevices","hids","hid"] {
            for entry in info.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]).iter().take(32) {
                let Some(id)=entry.get("uuid").and_then(identity) else {continue;};
                let descriptor=entry.get("hidDescriptor").or_else(||entry.get("reportDescriptor")).and_then(Value::as_bytes);
                if let Some(device)=descriptor.and_then(Device::parse) {
                    crate::emit_log(&format!("HID-KEY: device={id} descriptor={}B consumer_fields={} report_ids={}",descriptor.unwrap().len(),device.fields.len(),device.has_ids));
                    crate::emit_log(&format!("HID-LAYOUT: device={id} {}",device.diagnostic_layout()));
                    result.devices.insert(id,device);
                } else {
                    crate::emit_log(&format!("HID-LAYOUT: device={id} descriptor_bytes={:?} consumer_layout=unavailable; touch fallback preserved",descriptor.map(|d| d.len())));
                }
            }
        }
        crate::emit_log(&format!("HID-KEY: registered media devices={}",result.devices.len()));
        result
    }
    /// Some = media/short report consumed (including releases). None = preserve existing touch path.
    pub fn report(&mut self,uuid:Option<&Value>,bytes:&[u8])->Option<Vec<u32>> {
        let id=uuid.and_then(identity);
        let budget=if bytes.len()<5 { &mut self.short_logs } else { &mut self.long_logs };
        let limit=if bytes.len()<5 {64} else {8};
        if *budget<limit {
            *budget+=1;
            let device=id.as_ref().and_then(|id|self.devices.get(id));
            let layout=device.map(|d|d.diagnostic_report(bytes)).unwrap_or_else(||"layout=unmatched".into());
            // Never dump touch coordinates; short control reports alone get a hex sample.
            let sample=if bytes.len()<5 {format!("{:02x?}",bytes)} else {"omitted-touch-or-long-report".into()};
            crate::emit_log(&format!("HID-INPUT: device={id:?} bytes={} {layout} sample={sample} short_budget={}/64 long_budget={}/8",bytes.len(),self.short_logs,self.long_logs));
        }
        if let Some(device)=id.as_ref().and_then(|id|self.devices.get_mut(id)) {return Some(device.report(bytes));}
        if id.is_none() && bytes.len()<5 && self.devices.len()==1 {return Some(self.devices.values_mut().next().unwrap().report(bytes));}
        if bytes.len()>=5 {return None;}
        // Without a descriptor only unambiguous direct Usage IDs are supported. 0x04 is not hardcoded.
        let value=match bytes { [v]=>*v as u32,[lo,hi]=>u16::from_le_bytes([*lo,*hi]) as u32,_=>u32::MAX };
        if value==0||supported(value) {
            let down=if value==0 {BTreeSet::new()} else {BTreeSet::from([value])};
            let previous=self.raw.entry(id.unwrap_or_default()).or_default();
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
