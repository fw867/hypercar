use parking_lot::Mutex;
use std::time::{Duration, Instant};

pub(crate) const GPS_MESSAGE: u32 = 0x18010;
pub(super) const MIN_INTERVAL: Duration = Duration::from_millis(900);
const MAX_AGE: Duration = Duration::from_secs(2);

#[derive(Default)]
pub struct PendingLocation(Mutex<Option<Location>>);

struct Location {
    tunnel: u64,
    received: Instant,
    fields: [i64; 25],
}

impl PendingLocation {
    pub(crate) fn update(&self, tunnel: u64, fields: Option<[i64; 25]>) {
        let mut pending = self.0.lock();
        if let Some(fields) = fields {
            *pending = Some(Location { tunnel, received: Instant::now(), fields });
        } else if pending.as_ref().is_some_and(|fix| fix.tunnel == tunnel) {
            *pending = None;
        }
    }

    pub(super) fn take(&self) -> Option<Vec<u8>> {
        let fix = self.0.lock().take()?;
        (fix.received.elapsed() < MAX_AGE && crate::wifi_iap2::transport_ready(fix.tunnel))
            .then(|| encode(&fix.fields))
    }
}

fn encode(fields: &[i64; 25]) -> Vec<u8> {
    let mut payload = Vec::with_capacity(96);
    for (index, value) in fields.iter().enumerate() {
        super::proto::varint(&mut payload, index as u32 + 1, *value as u64);
    }
    payload
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gps_encodes_all_fields_as_varints_including_signed_coordinates() {
        let mut fields = [0; 25];
        fields[2] = -33_865_143;
        fields[3] = 151_209_900;
        fields[24] = 1_795_579_201_000;
        let encoded = encode(&fields);
        assert!(encoded.windows(3).any(|window| window == [0x80, 1, 0]));
        let mut cursor = 0;
        for (index, value) in fields.into_iter().enumerate() {
            let (tag, end) = super::super::pb_read_varint(&encoded, cursor).unwrap();
            assert_eq!(tag, ((index + 1) as u64) << 3);
            let (actual, end) = super::super::pb_read_varint(&encoded, end).unwrap();
            assert_eq!(actual, value as u64);
            cursor = end;
        }
        assert_eq!(cursor, encoded.len());
    }

    #[test]
    fn old_tunnel_invalidation_cannot_clear_the_latest_fix() {
        let pending = PendingLocation::default();
        pending.update(9, Some([7; 25]));
        pending.update(10, Some([8; 25]));
        pending.update(9, None);
        assert_eq!(pending.0.lock().as_ref().unwrap().fields, [8; 25]);
        pending.update(10, None);
        assert!(pending.0.lock().is_none());
    }
}
