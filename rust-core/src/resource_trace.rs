//! Resource and media-stream SETUP diagnostics. Excludes session SETUP and authentication.
use crate::{bplist::{self, Value}, rtsp::RtspMessage};
use sha2::{Digest, Sha256};

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

/// Only locally constructed resource commands and media SETUP are eligible.
/// Session SETUP (/setup) can carry identity/encryption material and must never be dumped.
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
fn bytes_lines(prefix: &str, bytes: &[u8]) -> Vec<String> {
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

pub(crate) fn info_modes(value: Option<&Value>) {
    if let Some(value) = value {
        for line in text_lines("RESOURCE INFO_MODES", &format!("{value:?}")) { crate::emit_log(&line); }
    } else { crate::emit_log("RESOURCE INFO_MODES absent"); }
}

pub(crate) fn outbound(kind: &str, id: u64, cseq: i32, packet: &[u8], body: &[u8]) -> Vec<String> {
    let prefix = format!("{kind} RTSP#{id} CSeq={cseq}");
    let mut lines = bytes_lines(&format!("{prefix} TX_PREPARED_PLAINTEXT"), packet);
    lines.extend(plist_lines(&format!("{prefix} TX_PLIST"), body));
    lines
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
    lines.extend(bytes_lines(&format!("{prefix} RX_BODY"), &reply.body));
    if !reply.body.is_empty() { lines.extend(plist_lines(&format!("{prefix} RX_PLIST"), &reply.body)); }
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

    fn reply(status: u16, body: Vec<u8>) -> RtspMessage {
        RtspMessage { first_line: format!("RTSP/1.0 {status} Test"), headers: HashMap::new(), body }
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
