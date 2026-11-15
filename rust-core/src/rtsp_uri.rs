//! Absolute RTSP URI from a raw socket host (not an already percent-encoded URI).
#[derive(Debug)]
pub(crate) struct SessionUri(String);
impl SessionUri {
    pub fn new(host:&str,id:u64)->Result<Self,String> {
        let raw=if host.starts_with('[') || host.ends_with(']') {
            host.strip_prefix('[').and_then(|s|s.strip_suffix(']')).ok_or("invalid bracketed socket host")?
        }else{host};
        if raw.is_empty() || raw.chars().any(|c|c.is_whitespace() || c.is_control() || matches!(c,'['|']'|'/'|'?'|'#'|'@')) {return Err("invalid session URI socket host".into());}
        let authority=if raw.contains(':') {
            let (address,zone)=raw.split_once('%').map(|(a,z)|(a,Some(z))).unwrap_or((raw,None));
            address.parse::<std::net::Ipv6Addr>().map_err(|_|"invalid IPv6 socket address")?;
            let zone=match zone {
                Some("")=>return Err("empty IPv6 socket scope".into()),
                Some(zone)=>format!("%25{}",zone.bytes().map(|b|if b.is_ascii_alphanumeric() || matches!(b,b'-'|b'.'|b'_'|b'~'){(b as char).to_string()}else{format!("%{b:02X}")}).collect::<String>()),
                None=>String::new(),
            };
            format!("[{address}{zone}]")
        }else {
            if !raw.bytes().all(|b|b.is_ascii_alphanumeric() || matches!(b,b'.'|b'-'|b'_')) {return Err("invalid IPv4/DNS socket host".into());}
            raw.to_string()
        };
        Ok(Self(format!("rtsp://{authority}/{id}")))
    }
    pub fn as_str(&self)->&str {&self.0}
    pub fn resolve<'a>(&'a self,method:&str,path:&'a str)->&'a str {
        match (method,path) {("SETUP","/setup"|"/")|("RECORD"|"TEARDOWN","/")=>self.as_str(),_=>path}
    }
}

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
    #[test] fn session_authority_and_unsigned_id_match_box_shape_with_valid_ipv6() {
        for (host,expected) in [
            ("192.0.2.1","rtsp://192.0.2.1/18446744073709551615"),
            ("fe80::1%wlan0","rtsp://[fe80::1%25wlan0]/18446744073709551615"),
            ("[::1]","rtsp://[::1]/18446744073709551615"),
            ("fe80::1%25","rtsp://[fe80::1%2525]/18446744073709551615"),
            ("::ffff:192.0.2.1","rtsp://[::ffff:192.0.2.1]/18446744073709551615"),
        ] {assert_eq!(SessionUri::new(host,u64::MAX).unwrap().as_str(),expected);}
    }
    #[test] fn session_uri_rejects_header_injection_or_an_already_built_uri() {
        for host in ["", "host\r\nInjected: yes", "rtsp://host/", "host/path", "[::1", "bad:host", "fe80::1%", "user@host"] {assert!(SessionUri::new(host,7).is_err(),"{host:?}");}
    }
    #[test] fn only_requested_legacy_session_and_screen_routes_are_resolved() {
        let uri=SessionUri::new("192.0.2.1",7).unwrap();
        for (method,path) in [("SETUP","/setup"),("SETUP","/"),("RECORD","/"),("TEARDOWN","/")] {assert_eq!(uri.resolve(method,path),uri.as_str());}
        for (method,path) in [("SETUP","rtsp://[fe80::1]:7000/"),("GET","/info"),("POST","/command"),("POST","/auth-setup"),("POST","/feedback"),("TEARDOWN","/unrelated")] {assert_eq!(uri.resolve(method,path),path);}
    }

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
