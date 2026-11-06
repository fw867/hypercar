//! Absolute RTSP URI from a raw socket host (not an already percent-encoded URI).
pub(crate) fn audio_uri(host: &str, port: u16) -> String {
    let raw = host.strip_prefix('[').and_then(|v| v.strip_suffix(']')).unwrap_or(host);
    let authority = if raw.contains(':') {
        // RFC 3986 IPv6 literal; raw socket zone separators become URI %25 (RFC 6874).
        format!("[{}]", raw.replace('%', "%25"))
    } else { raw.to_string() };
    format!("rtsp://{authority}:{port}/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mbux_ipv6_literal_does_not_merge_address_with_port() {
        assert_eq!(audio_uri("fe80::f0a2:f2ff:fe47:7b85", 7000),
            "rtsp://[fe80::f0a2:f2ff:fe47:7b85]:7000/");
        assert_eq!(audio_uri("[::1]", 7100), "rtsp://[::1]:7100/");
        assert_eq!(audio_uri("::ffff:192.168.1.1", 7000), "rtsp://[::ffff:192.168.1.1]:7000/");
    }

    #[test]
    fn ipv4_and_hostnames_keep_the_existing_uri() {
        assert_eq!(audio_uri("172.16.222.97", 7000), "rtsp://172.16.222.97:7000/");
        assert_eq!(audio_uri("receiver.local", 7000), "rtsp://receiver.local:7000/");
    }

    #[test]
    fn socket_zone_is_encoded_inside_ipv6_brackets() {
        assert_eq!(audio_uri("fe80::1%wlan0", 7000), "rtsp://[fe80::1%25wlan0]:7000/");
        assert_eq!(audio_uri("fe80::1%25", 7000), "rtsp://[fe80::1%2525]:7000/");
    }
}
