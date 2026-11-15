//! Select the media description returned by a SETUP response.
use crate::bplist::Value;

/// Explicit type always wins. Only a single untyped dictionary is safe to infer,
/// and only for software receivers known to omit the echoed type.
pub(crate) fn select(root: &Value, expected: i64, software_receiver: bool) -> Result<&Value, String> {
    let streams = root.get("streams").and_then(Value::as_array)
        .ok_or_else(|| "SETUP response has no streams".to_string())?;
    let mut matching = streams.iter().filter(|stream| stream.get("type").and_then(Value::as_int) == Some(expected));
    if let Some(stream) = matching.next() {
        if matching.next().is_some() {
            return Err(format!("SETUP response has ambiguous stream type {expected}"));
        }
        return Ok(stream);
    }
    if software_receiver && streams.len() == 1 && streams[0].as_dict().is_some() && streams[0].get("type").is_none() {
        return Ok(&streams[0]);
    }
    Err(format!("SETUP response has no matching stream type {expected}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn stream(kind: Option<Value>, port: i64) -> Value {
        let mut fields = vec![("dataPort", Value::int(port))];
        if let Some(kind) = kind { fields.push(("type", kind)); }
        Value::dict(fields)
    }
    fn response(streams: Vec<Value>) -> Value {
        Value::dict(vec![("streams", Value::array(streams))])
    }
    #[test]
    fn selects_requested_screen_pcm_and_aac_instead_of_first_stream() {
        for expected in [110, 100, 102] {
            let root = response(vec![stream(Some(Value::int(999)), 1234), stream(Some(Value::int(expected)), 5678)]);
            assert_eq!(select(&root, expected, false).unwrap().get("dataPort").and_then(Value::as_int), Some(5678));
        }
    }
    #[test]
    fn rejects_ambiguous_duplicate_requested_types() {
        for expected in [110, 100, 102] {
            for software in [false, true] {
                let root = response(vec![stream(Some(Value::int(expected)), 1234), stream(Some(Value::int(expected)), 5678)]);
                assert!(select(&root, expected, software).is_err());
            }
        }
    }
    #[test]
    fn rejects_explicit_wrong_or_malformed_type_even_with_valid_port() {
        for kind in [Value::int(100), Value::str("110"), Value::Null] {
            assert!(select(&response(vec![stream(Some(kind), 1234)]), 110, true).is_err());
        }
    }
    #[test]
    fn keeps_single_untyped_software_receiver_response_compatible() {
        for expected in [110, 100, 102] {
            let root = response(vec![stream(None, 1234)]);
            let decoded = crate::bplist::parse(&crate::bplist::encode(&root)).unwrap();
            assert_eq!(select(&decoded, expected, true).unwrap().get("dataPort").and_then(Value::as_int), Some(1234));
        }
    }
    #[test]
    fn car_requires_explicit_type() {
        assert!(select(&response(vec![stream(None,1234)]),110,false).is_err());
    }
    #[test]
    fn does_not_guess_untyped_stream_in_mixed_response() {
        assert!(select(&response(vec![stream(None,1234),stream(Some(Value::int(100)),5678)]),110,true).is_err());
        assert!(select(&response(vec![stream(None,1234),stream(None,5678)]),110,true).is_err());
    }
    #[test]
    fn rejects_missing_empty_and_non_dictionary_streams() {
        for root in [Value::dict(vec![]),response(vec![]),response(vec![Value::int(110)])] {
            assert!(select(&root,110,false).is_err());
        }
    }
}

