//! CarWith's installed CarLife SDK emits 0x10030 on the command transport.
//! Decode only the verified fields. Icons and segment total distance are not route totals.
use serde::Serialize;

pub const NEXT_TURN: u32 = 0x10030;
pub const ASSISTANT: u32 = 0x10047;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NavigationUpdate {
    pub action: u32,
    pub turn: Option<u32>,
    pub distance: Option<u32>,
    pub road: String,
}

fn varint(bytes: &[u8], pos: &mut usize) -> Option<u64> {
    let mut value = 0;
    for shift in (0..70).step_by(7) {
        let b = *bytes.get(*pos)?;
        *pos += 1;
        if shift == 63 && b > 1 { return None; }
        value |= ((b & 127) as u64) << shift;
        if b & 128 == 0 { return Some(value); }
    }
    None
}

pub fn decode(bytes: &[u8]) -> Option<NavigationUpdate> {
    if bytes.len() > 65535 { return None; }
    let (mut pos, mut seen) = (0usize, 0u32);
    let (mut action, mut turn, mut distance, mut road) = (None, None, None, String::new());
    let mut count = 0;
    while pos < bytes.len() {
        count += 1;
        if count > 64 { return None; }
        let tag = varint(bytes, &mut pos)?;
        let field = tag >> 3;
        let wire = tag & 7;
        if field == 0 || field > 0x1fffffff { return None; }
        if field <= 6 {
            let bit = 1 << field;
            if seen & bit != 0 { return None; }
            seen |= bit;
            let expected = if field == 3 || field == 6 { 2 } else { 0 };
            if wire != expected { return None; }
        }
        match wire {
            0 => {
                let value = varint(bytes, &mut pos)?;
                match field {
                    1 => { if !(1..=3).contains(&value) { return None; } action = Some(value as u32); }
                    2 => { if value > 255 { return None; } turn = Some(value as u32); }
                    4 | 5 => {
                        if value > i32::MAX as u64 { return None; }
                        if field == 5 { distance = Some(value as u32); }
                    }
                    _ => {}
                }
            }
            2 => {
                let len = usize::try_from(varint(bytes, &mut pos)?).ok()?;
                let end = pos.checked_add(len)?;
                let data = bytes.get(pos..end)?;
                if field == 3 {
                    if len > 512 { return None; }
                    road = std::str::from_utf8(data).ok()?.replace('\0', " ");
                }
                pos = end;
            }
            1 | 5 => { pos = pos.checked_add(if wire == 1 { 8 } else { 4 })?; if pos > bytes.len() { return None; } }
            _ => return None,
        }
    }
    let action = action?;
    if action != 3 && turn.is_none() { return None; }
    Some(NavigationUpdate { action, turn, distance, road })
}

/// Only counts/lengths go into diagnostics, never road strings or icon bodies.
#[derive(Default)]
pub(super) struct Diagnostics { counts: std::collections::BTreeMap<u32, u64> }
impl Diagnostics {
    pub fn observe(&mut self, channel: &str, kind: u32, wire_len: usize, plain_len: Option<usize>) -> bool {
        if !self.counts.contains_key(&kind) && self.counts.len() >= 64 { return false; }
        let count = self.counts.entry(kind).or_default();
        *count = count.saturating_add(1);
        if *count <= 3 || count.is_power_of_two() {
            crate::emit_log(&format!("CARWITH-DATA: channel={channel} kind=0x{kind:x} wire={wire_len}B plain={plain_len:?} count={count}"));
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn decodes_verified_fields_skips_icon_and_does_not_use_total_as_remaining() {
        // action=2, turn=5 (U-turn), road="test", segment total=300, remaining=35, icon=3 bytes.
        let bytes = [8,2,16,5,26,4,b't',b'e',b's',b't',32,0xac,2,40,35,50,3,1,2,3];
        assert_eq!(decode(&bytes), Some(NavigationUpdate { action:2, turn:Some(5), road:"test".into(), distance:Some(35) }));
    }
    #[test] fn cancellation_needs_no_maneuver_or_fake_arrival() {
        assert_eq!(decode(&[8,3]), Some(NavigationUpdate { action:3,turn:None,distance:None,road:String::new() }));
        assert!(decode(&[8,2]).is_none());
    }
    #[test] fn rejects_truncated_duplicate_wrong_type_negative_and_overflow() {
        for bad in [vec![8,2,16],vec![8,1,8,2,16,5],vec![10,1,2],vec![8,0],
            vec![8,2,16,5,26,5,b'a'],vec![8,2,16,5,40,255,255,255,255,255,255,255,255,255,1],
            vec![8,2,16,5,40,255,255,255,255,255,255,255,255,255,2]] {
            assert!(decode(&bad).is_none(), "{bad:?}");
        }
    }
    #[test] fn unknown_turn_is_preserved_and_unknown_fields_are_skipped() {
        assert_eq!(decode(&[8,2,16,99,61,0,0,0,0]).unwrap().turn,Some(99));
        assert!(decode(&[8,2,16,5,26,1,255]).is_none());
    }
}

/// Capability response to our single module-0 (turn-by-turn) query, not vehicle sensor data.
pub fn supports_turn_updates(bytes:&[u8]) -> Option<bool> {
    fn fields(bytes:&[u8]) -> Option<Vec<(u64,Option<u64>,&[u8])>> {
        let mut out=Vec::new();let mut pos=0;
        while pos<bytes.len() {
            if out.len()>=16 {return None;}
            let tag=varint(bytes,&mut pos)?;
            if tag>>3==0 {return None;}
            match tag&7 {
                0=>out.push((tag>>3,Some(varint(bytes,&mut pos)?),&[][..])),
                2=>{let len=usize::try_from(varint(bytes,&mut pos)?).ok()?;let end=pos.checked_add(len)?;
                    out.push((tag>>3,None,bytes.get(pos..end)?));pos=end;},
                _=>return None,
            }
        }
        Some(out)
    }
    if bytes.len()>1024 {return None;}
    let list=fields(bytes)?;
    let counts:Vec<_>=list.iter().filter(|f|f.0==1).collect();
    if counts.len()!=1 {return None;}
    let count=counts[0].1?;
    let items:Vec<_>=list.iter().filter(|f|f.0==2).collect();
    if count!=items.len() as u64 || count>8 {return None;}
    let mut supported=None;
    for item in items {
        if item.1.is_some() {return None;}
        let parts=fields(item.2)?;
        let ids:Vec<_>=parts.iter().filter(|f|f.0==1).collect();
        if ids.len()!=1 {return None;}
        if ids[0].1?==0 {
            if supported.is_some() {return None;}
            let flags:Vec<_>=parts.iter().filter(|f|f.0==2).collect();
            if flags.len()!=1 {return Some(false);}
            supported=Some(flags[0].1?==1);
        }
    }
    Some(supported.unwrap_or(false))
}
