//! Resource and SETUP diagnostics. ID9 session SETUP is selected at the control connection.
use crate::{bplist::{self, Value}, rtsp::RtspMessage};
use sha2::{Digest, Sha256};
use std::borrow::Cow;

/// Transport ACK only. Empty bodies are legal, but never confirm resource ownership.
pub(crate) fn validate_ack(stage:&str, reply:&RtspMessage)->Result<(),String> {
    if reply.status()!=200 {return Err(format!("{stage} rejected RTSP status={}",reply.status()));}
    if reply.body.is_empty() {
        crate::emit_log(&format!("ACTIVATION: {stage} RTSP=200 business_status=absent_empty_body; receiver activation unconfirmed"));
        return Ok(());
    }
    let value=bplist::parse(&reply.body).map_err(|_|format!("{stage} malformed response body"))?;
    if value.as_dict().is_none(){return Err(format!("{stage} response is not a dictionary"));}
    match value.get("status") {
        Some(Value::Int(0))=>crate::emit_log(&format!("ACTIVATION: {stage} RTSP=200 business_status=0; receiver activation unconfirmed")),
        Some(Value::Int(code))=>return Err(format!("{stage} business rejection status={code}")),
        Some(_)=>return Err(format!("{stage} invalid business status type")),
        None=>crate::emit_log(&format!("ACTIVATION: {stage} RTSP=200 business_status=absent; receiver activation unconfirmed")),
    }
    Ok(())
}

pub(crate) fn is_resource_request(method: &str, path: &str, body: &[u8]) -> bool {
    method == "POST" && path == "/command" && bplist::parse(body).ok()
        .and_then(|v| v.get("type").and_then(Value::as_str).map(str::to_owned))
        .is_some_and(|kind| matches!(kind.as_str(), "modesChanged" | "changeModes"))
}

/// General tracing covers resource commands and media streams on all profiles.
pub(crate) fn trace_kind(method: &str, path: &str, body: &[u8]) -> Option<&'static str> {
    if is_resource_request(method, path, body) { return Some("RESOURCE"); }
    if method != "SETUP" || !(path == "/" || path.starts_with("rtsp://")) { return None; }
    let value = bplist::parse(body).ok()?;
    let streams = value.get("streams")?.as_array()?;
    if streams.is_empty() { return None; }
    let mut audio = false;
    let mut screen = false;
    for stream in streams {
        match stream.get("type").and_then(Value::as_int) {
            Some(100 | 102 | 103) => audio = true,
            Some(110) => screen = true,
            _ => return None,
        }
    }
    Some(match (audio, screen) {
        (true, false) => "AUDIO_SETUP",
        (false, true) => "SCREEN_SETUP",
        _ => "MEDIA_SETUP",
    })
}

// Small independently numbered lines survive logcat limits and interleaved heartbeat logs.
// Every byte is retained; concatenate hex chunks in offset order to reconstruct the packet.
pub(crate) fn bytes_lines(prefix: &str, bytes: &[u8]) -> Vec<String> {
    let mut lines = vec![format!("{prefix} bytes={} sha256={:x}", bytes.len(), Sha256::digest(bytes))];
    for (i, chunk) in bytes.chunks(192).enumerate() {
        let hex = chunk.iter().map(|b| format!("{b:02x}")).collect::<String>();
        lines.push(format!("{prefix} offset={} total={} hex={hex}", i * 192, bytes.len()));
    }
    lines
}

fn plist_lines(prefix: &str, bytes: &[u8]) -> Vec<String> {
    let text = match bplist::parse(bytes) {
        Ok(value) => format!("{value:?}"),
        Err(error) => format!("parse_error={error:?}"),
    };
    text_lines(prefix, &text)
}

fn text_lines(prefix: &str, text: &str) -> Vec<String> {
    text.chars().collect::<Vec<_>>().chunks(512).enumerate()
        .map(|(i, chars)| format!("{prefix} part={} text={}", i + 1, chars.iter().collect::<String>()))
        .collect()
}

/// Keep headers readable in the export without splitting one log record at CRLF.
pub(crate) fn log_head(prefix: &str, head: &[u8]) {
    let escaped = format!("{:?}", String::from_utf8_lossy(head));
    for line in text_lines(prefix, &escaped) { crate::emit_log(&line); }
}

pub(crate) fn info_modes(prefix: &str, value: Option<&Value>) {
    if let Some(value) = value {
        for line in text_lines(prefix, &format!("{value:?}")) { crate::emit_log(&line); }
    } else { crate::emit_log(&format!("{prefix} absent")); }
}

pub(crate) fn outbound(kind: &str, id: u64, cseq: i32, packet: &[u8], body: &[u8]) -> Vec<String> {
    let prefix = format!("{kind} RTSP#{id} CSeq={cseq}");
    let keys = bplist::parse(body).ok().map(|value| stream_key_material(&value)).unwrap_or_default();
    let packet = redact_stream_keys(packet, &keys);
    let body = redact_stream_keys(body, &keys);
    let mut lines = bytes_lines(&format!("{prefix} TX_PREPARED_PLAINTEXT"), &packet);
    lines.extend(plist_lines(&format!("{prefix} TX_PLIST"), &body));
    lines
}

/// Type 103 SETUP carries the stream key in `shk`. Diagnostic reconstruction keeps the packet
/// shape, but not those 32 bytes. Only the diagnostic copy is changed.
fn redact_stream_keys<'a>(bytes: &'a [u8], keys: &[Vec<u8>]) -> Cow<'a, [u8]> {
    if keys.is_empty() { return Cow::Borrowed(bytes); }
    let mut redacted = bytes.to_vec();
    for key in keys {
        // Search the original so a previous match cannot hide another overlapping match.
        for (offset, window) in bytes.windows(key.len()).enumerate() {
            if window == key.as_slice() { redacted[offset..offset + key.len()].fill(0); }
        }
    }
    Cow::Owned(redacted)
}

fn stream_key_material(value: &Value) -> Vec<Vec<u8>> {
    let mut keys = Vec::new();
    collect_stream_keys(value, &mut keys);
    keys
}

fn collect_stream_keys(value: &Value, keys: &mut Vec<Vec<u8>>) {
    match value {
        Value::Dict(pairs) => {
            for (name, child) in pairs {
                if name.as_str() == Some("shk") {
                    if let Some(bytes) = child.as_bytes().filter(|bytes| bytes.len() == 32 && bytes.iter().any(|b| *b != 0)) {
                        keys.push(bytes.to_vec());
                    }
                }
                collect_stream_keys(child, keys);
            }
        }
        Value::Array(items) => { for item in items { collect_stream_keys(item, keys); } }
        _ => {}
    }
}

pub(crate) fn media_setup_written_latency(id: u64, cseq: i32, body: &[u8]) -> Vec<String> {
    let Ok(value) = bplist::parse(body) else { return Vec::new(); };
    value.get("streams").and_then(Value::as_array).into_iter().flatten().filter_map(|stream| {
        let stream_type = stream.get("type")?.as_int()?;
        if !matches!(stream_type, 100 | 102 | 103) || stream.get("audioType")?.as_str()? != "media" {
            return None;
        }
        let latency = stream.get("audioLatencyMs")?.as_int()?;
        Some(format!("AUDIO-LATENCY: RTSP#{id} CSeq={cseq} SETUP TX_WRITTEN type={stream_type} audioType=media audioLatencyMs={latency}"))
    }).collect()
}

pub(crate) fn response(kind: &str, id: u64, cseq: i32, reply: &RtspMessage) -> Vec<String> {
    let parsed = if reply.body.is_empty() { None } else { Some(bplist::parse(&reply.body)) };
    let business = match parsed.as_ref() {
        None => "absent_empty_body".to_string(),
        Some(Err(_)) => "unparseable_body".to_string(),
        Some(Ok(value)) => match value.get("status") {
            None => "absent".to_string(),
            Some(Value::Int(code)) => code.to_string(),
            Some(_) => "non_integer".to_string(),
        },
    };
    let prefix = format!("{kind} RTSP#{id} CSeq={cseq}");
    let mut lines = vec![format!("{prefix} RX rtsp_status={} business_status={business} first_line={:?}; absent status is not business success", reply.status(), reply.first_line)];
    if matches!(kind, "AUDIO_SETUP" | "MEDIA_SETUP") && matches!(parsed.as_ref(), Some(Err(_))) {
        // A malformed SETUP reply can still contain a key, but has no reliably parsed field to redact.
        lines.push(format!("{prefix} RX_BODY bytes={} omitted=unparseable_audio_setup", reply.body.len()));
    } else {
        let keys = match parsed.as_ref() {
            Some(Ok(value)) => stream_key_material(value),
            _ => Vec::new(),
        };
        let body = redact_stream_keys(&reply.body, &keys);
        lines.extend(bytes_lines(&format!("{prefix} RX_BODY"), &body));
        if !body.is_empty() { lines.extend(plist_lines(&format!("{prefix} RX_PLIST"), &body)); }
    }
    lines
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn only_resource_commands_get_full_plaintext_logging() {
        let resource = crate::modes::Modes::initial().declaration();
        assert!(is_resource_request("POST", "/command", &resource));
        for path in ["/pair-setup", "/pair-verify", "/auth-setup", "/info", "/feedback"] {
            assert!(!is_resource_request("POST", path, &resource));
        }
        for kind in ["requestUI", "sendHIDReport", "iAPSendMessage", "authSetup"] {
            let body = bplist::encode(&Value::dict(vec![("type", Value::str(kind))]));
            assert!(!is_resource_request("POST", "/command", &body));
        }
        assert!(!is_resource_request("GET", "/command", &resource));
        assert!(!is_resource_request("POST", "/command", b"not plist"));
    }

    #[test]
    fn written_latency_reports_the_serialized_main_media_values_only() {
        let stream = |kind, role, latency| Value::dict(vec![
            ("type", Value::int(kind)), ("audioType", Value::str(role)),
            ("audioLatencyMs", Value::int(latency)),
        ]);
        let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![
            stream(100, "media", 250), stream(102, "media", 325), stream(103, "media", 410),
            stream(100, "speechRecognition", 72), stream(101, "default", 98), stream(110, "media", 75),
        ]))]));
        assert_eq!(media_setup_written_latency(45, 17, &body), [
            "AUDIO-LATENCY: RTSP#45 CSeq=17 SETUP TX_WRITTEN type=100 audioType=media audioLatencyMs=250",
            "AUDIO-LATENCY: RTSP#45 CSeq=17 SETUP TX_WRITTEN type=102 audioType=media audioLatencyMs=325",
            "AUDIO-LATENCY: RTSP#45 CSeq=17 SETUP TX_WRITTEN type=103 audioType=media audioLatencyMs=410",
        ]);
    }

    #[test]
    fn media_setup_is_classified_by_stream_type_without_logging_session_setup() {
        let screen = crate::airplay::build_screen_setup(42, "screen-id");
        let audio = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![
            Value::dict(vec![("type", Value::Int(100))]),
        ]))]));
        assert_eq!(trace_kind("SETUP", "/", &screen), Some("SCREEN_SETUP"));
        assert_eq!(trace_kind("SETUP", "rtsp://[fe80::1]:7000/", &audio), Some("AUDIO_SETUP"));
        assert_eq!(trace_kind("POST", "/command", &crate::modes::Modes::initial().declaration()), Some("RESOURCE"));
        for stream_type in [102,103] {
            let aac=bplist::encode(&Value::dict(vec![("streams",Value::array(vec![Value::dict(vec![
                ("type",Value::int(stream_type)), ("audioType",Value::str("media")),
                ("audioFormat",Value::int(1 << 23)), ("spf",Value::int(1024)),
            ])]))]));
            assert_eq!(trace_kind("SETUP", "rtsp://[fe80::1]:7000/", &aac),Some("AUDIO_SETUP"));
            assert_eq!(trace_kind("SETUP", "/setup", &aac),None);
        }
        for path in ["/setup", "/pair-setup", "/pair-verify", "/auth-setup"] {
            assert_eq!(trace_kind("SETUP", path, &screen), None);
        }
    }

    #[test]
    fn malformed_empty_and_unknown_streams_do_not_enable_full_logging() {
        for body in [
            Vec::new(), b"not plist".to_vec(),
            bplist::encode(&Value::dict(vec![("streams", Value::array(vec![]))])),
            bplist::encode(&Value::dict(vec![("streams", Value::array(vec![
                Value::dict(vec![("type", Value::Int(999))]),
            ]))])),
        ] { assert_eq!(trace_kind("SETUP", "/", &body), None); }
        let resource = crate::modes::Modes::initial().declaration();
        assert_eq!(trace_kind("SETUP", "/", &resource), None);
    }

    #[test]
    fn activation_ack_rejects_business_failure_despite_200() {
        assert!(validate_ack("RECORD",&reply(200,Vec::new())).is_ok());
        assert!(validate_ack("RESOURCE",&reply(200,bplist::encode(&Value::dict(vec![])))).is_ok());
        assert!(validate_ack("RESOURCE",&reply(200,bplist::encode(&Value::dict(vec![("status",Value::int(0))])))).is_ok());
        for body in [b"bad plist".to_vec(),bplist::encode(&Value::array(vec![])),
            bplist::encode(&Value::dict(vec![("status",Value::str("0"))])),
            bplist::encode(&Value::dict(vec![("status",Value::int(-6722))]))] {
            assert!(validate_ack("RESOURCE",&reply(200,body)).is_err());
        }
        assert!(validate_ack("RECORD",&reply(455,Vec::new())).is_err());
    }

    #[test]
    fn empty_400_media_reply_keeps_stream_kind_and_does_not_invent_business_status() {
        for kind in ["SCREEN_SETUP", "AUDIO_SETUP"] {
            let lines = response(kind, 5, 12, &reply(400, Vec::new()));
            assert!(lines[0].starts_with(&format!("{kind} RTSP#5 CSeq=12 RX ")));
            assert!(lines[0].contains("rtsp_status=400 business_status=absent_empty_body"));
            assert!(lines.iter().any(|s| s.contains("RX_BODY bytes=0")));
        }
    }

    #[test]
    fn complete_packet_can_be_reconstructed_across_log_lines() {
        let packet: Vec<u8> = (0..1025).map(|i| (i % 256) as u8).collect();
        let lines = bytes_lines("test", &packet);
        assert!(lines[0].contains(&format!("sha256={:x}", Sha256::digest(&packet))));
        let mut reconstructed = Vec::new();
        for line in &lines[1..] {
            assert!(line.contains(&format!("offset={} ", reconstructed.len())));
            let hex = line.split("hex=").nth(1).unwrap();
            assert!(line.len() < 1024);
            for i in (0..hex.len()).step_by(2) {
                reconstructed.push(u8::from_str_radix(&hex[i..i+2], 16).unwrap());
            }
        }
        assert_eq!(reconstructed, packet);
        let body = crate::modes::Modes::initial().declaration();
        assert!(outbound("RESOURCE", 9, 10, &packet, &body).iter().all(|s| s.starts_with("RESOURCE RTSP#9 CSeq=10")));
    }

    #[test]
    fn outbound_trace_keeps_type103_shape_without_printing_shk() {
        let key = [0xA5u8; 32];
        let body = crate::bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(103)),
            ("shk", Value::Data(key.to_vec())),
        ])]))]));
        let mut packet = b"SETUP ".to_vec();
        packet.extend_from_slice(&body);
        let joined = outbound("AUDIO_SETUP", 3, 10, &packet, &body).join("\n");
        let secret = key.iter().map(|byte| format!("{byte:02x}")).collect::<String>();
        assert!(!joined.contains(&secret));
        assert!(!joined.contains("165, 165"));
        assert!(joined.contains("Data([0, 0, 0"));
        assert!(joined.contains("Int(103)"));
    }

    #[test]
    fn received_stream_keys_are_removed_from_hex_and_plist_without_mutating_reply() {
        let key = [0xa5; 32];
        let original = bplist::encode(&Value::dict(vec![
            ("status", Value::int(0)),
            ("streams", Value::array(vec![Value::dict(vec![
                ("type", Value::int(103)), ("dataPort", Value::int(7001)),
                ("shk", Value::Data(key.to_vec())),
            ])])),
        ]));
        let reply = reply(200, original.clone());
        let lines = response("AUDIO_SETUP", 1, 10, &reply);
        let mut reconstructed = Vec::new();
        for hex in lines.iter().filter(|line| line.contains(" RX_BODY offset="))
            .filter_map(|line| line.split_once("hex=").map(|(_, hex)| hex)) {
            for i in (0..hex.len()).step_by(2) {
                reconstructed.push(u8::from_str_radix(&hex[i..i + 2], 16).unwrap());
            }
        }
        let joined = lines.join("\n");
        assert_eq!(reconstructed.len(), original.len());
        assert!(!reconstructed.windows(key.len()).any(|window| window == key.as_slice()));
        assert!(!joined.contains(&format!("sha256={:x}", Sha256::digest(&original))));
        assert!(joined.contains(&format!("sha256={:x}", Sha256::digest(&reconstructed))));
        assert!(!joined.contains("165, 165"));
        assert!(joined.contains("Data([0, 0, 0"));
        let plist = bplist::parse(&reconstructed).unwrap();
        let stream = &plist.get("streams").unwrap().as_array().unwrap()[0];
        assert_eq!(stream.get("shk"), Some(&Value::Data(vec![0; 32])));
        assert_eq!(stream.get("dataPort"), Some(&Value::int(7001)));
        assert_eq!(reply.body, original);
    }

    #[test]
    fn unparseable_audio_setup_replies_do_not_dump_raw_bytes() {
        let body = vec![0xa5; 80];
        for kind in ["AUDIO_SETUP", "MEDIA_SETUP"] {
            let lines = response(kind, 1, 10, &reply(200, body.clone()));
            assert!(lines[0].contains("business_status=unparseable_body"));
            assert!(lines[1].contains("bytes=80 omitted=unparseable_audio_setup"));
            assert_eq!(lines.len(), 2);
        }
    }

    fn reply(status: u16, body: Vec<u8>) -> RtspMessage {
        RtspMessage { received_at: std::time::Instant::now(), hid_screen_grant: None, first_line: format!("RTSP/1.0 {status} Test"), headers: HashMap::new(), body }
    }

    #[test]
    fn rtsp_and_business_rejection_are_distinct_and_signed() {
        // Independent binary plist fixture: a signed 64-bit receiver error, not our encoder.
        let body = vec![0x62, 0x70, 0x6c, 0x69, 0x73, 0x74, 0x30, 0x30, 0xd2, 0x01, 0x02, 0x03, 0x04, 0x56, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73, 0x56, 0x72, 0x65, 0x61, 0x73, 0x6f, 0x6e, 0x13, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xe5, 0xbe, 0x56, 0x64, 0x65, 0x6e, 0x69, 0x65, 0x64, 0x08, 0x0d, 0x14, 0x1b, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2b];
        let lines = response("RESOURCE", 1, 10, &reply(200, body));
        assert!(lines[0].contains("rtsp_status=200 business_status=-6722"));
        assert!(lines.iter().any(|s| s.contains("denied")));
        assert!(response("RESOURCE", 1, 11, &reply(455, Vec::new()))[0].contains("rtsp_status=455 business_status=absent_empty_body"));
    }

    #[test]
    fn empty_missing_invalid_or_noninteger_status_never_becomes_zero() {
        for (body, expected) in [
            (Vec::new(), "absent_empty_body"),
            (b"not plist".to_vec(), "unparseable_body"),
            (bplist::encode(&Value::dict(vec![])), "absent"),
            (bplist::encode(&Value::dict(vec![("status", Value::str("denied"))])), "non_integer"),
            (bplist::encode(&Value::dict(vec![("status", Value::Int(0))])), "0"),
        ] {
            assert!(response("RESOURCE", 1, 10, &reply(200, body))[0].contains(&format!("business_status={expected}")));
        }
    }
}
