//! DiPlay reads iAP2 from the AirPlay DataStream and replies on the event RTSP channel.
use std::sync::Arc;
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;

use crate::airplay::{random_positive_i64, SocketProvider};
use crate::bplist::{self, Value};
use crate::rtsp::{ControlCipher, RtspChannel};

const STREAM_TYPE: i64 = 130;
const CLIENT_UUID: &str = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2";
const HEADER_BYTES: usize = 32;

pub(crate) struct Target {
    pub host: String,
    pub factory: Arc<dyn SocketProvider>,
    pub shared: [u8; 32],
}

pub(crate) struct Tunnel {
    socket: TcpStream,
    cipher: ControlCipher,
}

impl Target {
    pub async fn open(self, control: &RtspChannel) -> Result<Tunnel, String> {
        let seed = random_positive_i64();
        let id = random_positive_i64();
        let body = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
            ("type", Value::int(STREAM_TYPE)), ("clientTypeUUID", Value::str(CLIENT_UUID)),
            ("seed", Value::int(seed)), ("streamConnectionID", Value::int(id)),
        ])]))]));
        let reply = control.request("SETUP", "/", &body, "application/x-apple-binary-plist", &[], Duration::from_secs(2)).await?;
        if reply.status() != 200 { return Err(format!("SETUP status={}", reply.status())); }
        crate::resource_trace::validate_ack("IAP_TUNNEL_SETUP", &reply)?;
        let response = bplist::parse(&reply.body).map_err(|error| format!("SETUP plist: {error}"))?;
        let streams = response.get("streams").and_then(Value::as_array).ok_or("SETUP missing streams")?;
        let [stream] = streams.as_slice() else { return Err("SETUP expected one data stream".into()); };
        if stream.get("type").and_then(Value::as_int) != Some(STREAM_TYPE)
            || stream.get("streamID").and_then(Value::as_int) != Some(1)
            || stream.get("streamConnectionID").is_some_and(|value| value.as_int() != Some(id)) {
            return Err("SETUP returned an unrelated data stream".into());
        }
        let port = crate::airplay::port_of(stream.get("dataPort"));
        if port == 0 { return Err("SETUP missing valid dataPort".into()); }

        let key: [u8; 32] = crate::crypto::hkdf_sha512(&self.shared, format!("DataStream-Salt{seed}").as_bytes(),
            b"DataStream-Output-Encryption-Key", 32).try_into().unwrap();
        let socket = tokio::task::spawn_blocking(move || self.factory.open_tcp(&self.host, port, 2500)).await
            .map_err(|error| format!("type 130 connect task: {error}"))?.ok_or("type 130 TCP connect failed")?;
        crate::emit_log(&format!("IAP-TUNNEL: type 130 connected RTSP#{} dataPort={port}", control.trace_id()));
        Ok(Tunnel { socket, cipher: ControlCipher::new(key, key) })
    }
}

impl Tunnel {
    #[cfg(test)]
    pub async fn write(&mut self, bytes: &[u8]) -> Result<(), String> {
        self.write_guarded(bytes, None).await.map(|_| ())
    }

    pub(crate) async fn write_guarded(
        &mut self, bytes: &[u8], start: Option<&crate::wifi_iap2::WriteStart>,
    ) -> Result<bool, String> {
        let mut package = vec![0; HEADER_BYTES];
        let length = (HEADER_BYTES + bytes.len()) as u32;
        package[..4].copy_from_slice(&length.to_be_bytes());
        package[16..20].copy_from_slice(b"comm");
        package.extend_from_slice(bytes);
        let (wire, counter) = self.cipher.preview_encrypt(&package);
        tokio::time::timeout(Duration::from_secs(3), async {
            let offset = if let Some(start) = start {
                match start.prefix(wire.len(), || self.socket.writable(), || self.socket.try_write(&wire)).await? {
                    Some(count) => count,
                    None => return Ok(false),
                }
            } else { 0 };
            self.cipher.commit_encrypt(counter);
            if let Some(start) = start.filter(|start| start.syn_ack) {
                start.finish_syn_ack(wire.len(), offset, || self.socket.writable(), |at| self.socket.try_write(&wire[at..])).await?;
            } else {
                self.socket.write_all(&wire[offset..]).await?;
            }
            Ok::<_, std::io::Error>(true)
        }).await
            .map_err(|_| "type 130 write timeout".to_string())?
            .map_err(|error| format!("type 130 write failed: {error}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    struct Loopback;
    impl SocketProvider for Loopback {
        fn open_tcp(&self, host: &str, port: u16, _: u32) -> Option<TcpStream> {
            assert_eq!(host, "127.0.0.1");
            let socket = std::net::TcpStream::connect((host, port)).ok()?;
            socket.set_nonblocking(true).ok()?;
            let _runtime = crate::TOKIO_RT.enter();
            TcpStream::from_std(socket).ok()
        }
        fn open_udp(&self, _: &str, _: u16) -> Option<tokio::net::UdpSocket> { None }
    }

    #[tokio::test]
    async fn type_130_setup_carries_encrypted_iap_bytes_to_its_returned_port() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let car_data = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (read, write) = phone.into_split();
        let (channel, _inbound) = RtspChannel::spawn(read, write, None);
        let shared = [7; 32];
        let target = Target { host: "127.0.0.1".into(), factory: Arc::new(Loopback), shared };
        let marker = [0xff, 0x55, 2, 0, 0xee, 0x10];
        let car = tokio::spawn(async move {
            let mut buf = Vec::new();
            let request = loop {
                let mut chunk = [0; 1024];
                let len = car.read(&mut chunk).await.unwrap();
                assert!(len > 0);
                buf.extend_from_slice(&chunk[..len]);
                if let Some(msg) = crate::rtsp::parse_rtsp_messages(&mut buf).unwrap().pop() { break msg; }
            };
            assert_eq!(request.first_line, "SETUP / RTSP/1.0");
            let offer = bplist::parse(&request.body).unwrap();
            let stream = &offer.get("streams").unwrap().as_array().unwrap()[0];
            assert_eq!(stream.get("type").unwrap().as_int(), Some(130));
            assert_eq!(stream.get("clientTypeUUID").unwrap().as_str(), Some("E9459FD0-BCAD-4C45-820F-1E72447EF2F2"));
            let seed = stream.get("seed").unwrap().as_int().unwrap();
            let connection = stream.get("streamConnectionID").unwrap().as_int().unwrap();
            let response = bplist::encode(&Value::dict(vec![("streams", Value::array(vec![Value::dict(vec![
                ("type", Value::int(130)), ("streamID", Value::int(1)),
                ("streamConnectionID", Value::int(connection)),
                ("dataPort", Value::int(car_data.local_addr().unwrap().port().into())),
            ])]))]));
            car.write_all(format!("RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
                request.cseq().unwrap(), response.len()).as_bytes()).await.unwrap();
            car.write_all(&response).await.unwrap();
            let (mut data, _) = car_data.accept().await.unwrap();
            let key = crate::crypto::hkdf_sha512(&shared, format!("DataStream-Salt{seed}").as_bytes(),
                b"DataStream-Output-Encryption-Key", 32).try_into().unwrap();
            let mut cipher = ControlCipher::new(key, key);
            for _ in 0..2 {
                let mut encrypted = [0; 2 + 32 + 6 + 16];
                data.read_exact(&mut encrypted).await.unwrap();
                let (plain, consumed) = cipher.decrypt(&encrypted).unwrap();
                assert_eq!(consumed, encrypted.len());
                assert_eq!(&plain[..4], &38u32.to_be_bytes());
                assert_eq!(&plain[16..20], b"comm");
                assert_eq!(&plain[32..], &marker);
            }
        });
        let mut tunnel = target.open(&channel).await.unwrap();
        tunnel.write(&marker).await.unwrap();
        tunnel.write(&marker).await.unwrap();
        car.await.unwrap();
    }
}
