//! Classic type-103 TCP records (not packetFormatAPAP).
use crate::buffered_control::BufferedControl;
use crate::crypto::{chacha_seal, nonce64};
use std::{io, time::Duration};
use tokio::net::TcpStream;

const RECORD_OVERHEAD: usize = 2 + 12 + 16 + 8;
const WRITE_TIMEOUT: Duration = Duration::from_millis(250);
const SEQUENCE_MASK: u32 = 0x7f_ffff;
const MARKER: u32 = 0x80_0000;

pub(super) struct BufferedTransport {
    // Taking the pair before the first await closes both on cancellation or error.
    // A second call can never append to a truncated length-prefixed record.
    endpoint: Option<(TcpStream, BufferedControl)>,
    encoder_generation: u64,
    sequence: u32,
    buffer_bytes: u32,
    priming_bytes: u32,
    pub(super) written: u64,
    pub(super) payload_bytes: u64,
    #[cfg(test)]
    before_record_write: Option<Box<dyn FnOnce() + Send + Sync>>,
}

impl BufferedTransport {
    pub(super) fn new(data: TcpStream, control: BufferedControl, buffer_bytes: u32) -> Self {
        let encoder_generation = control.loss_generation();
        Self {
            endpoint: Some((data, control)),
            encoder_generation,
            sequence: 103 << 16,
            buffer_bytes,
            written: 0,
            payload_bytes: 0,
            #[cfg(test)]
            before_record_write: None,
            priming_bytes: 0,
        }
    }

    pub(super) fn generation(&self) -> Option<u64> {
        self.endpoint
            .as_ref()
            .map(|(_, control)| control.loss_generation())
    }

    pub(super) fn failed(&self) -> bool {
        self.endpoint
            .as_ref()
            .is_none_or(|(_, control)| control.failed())
    }

    pub(super) fn encoder_generation_changed(&mut self, current: u64) -> bool {
        let changed = std::mem::replace(&mut self.encoder_generation, current) != current;
        if changed {
            self.priming_bytes = 0;
        }
        changed
    }

    pub(super) async fn send(
        &mut self,
        key: &[u8; 32],
        raw: &[u8],
        timestamp: u32,
        rate: u32,
        expected_generation: u64,
    ) -> io::Result<bool> {
        let (data, mut control) = self.endpoint.take().ok_or_else(closed)?;
        let length = record_length(raw.len(), self.buffer_bytes)?;
        let priming = !control.playing();
        if priming && u32::from(length) > self.buffer_bytes - self.priming_bytes {
            self.endpoint = Some((data, control));
            return Ok(false);
        }
        let wire_seq = MARKER | self.sequence;
        if !control
            .prepare_packet(wire_seq, timestamp, expected_generation)
            .await?
        {
            self.endpoint = Some((data, control));
            return Ok(false);
        }
        // Failure or cancellation retires this key/socket pair; only successful writes
        // can be followed by another encryption, with the next independent nonce.
        let nonce = self.written;
        let next = nonce
            .checked_add(1)
            .ok_or_else(|| io::Error::other("type 103 packet counter exhausted"))?;
        let packet = frame(key, raw, self.sequence, timestamp, nonce, rate, length)?;
        #[cfg(test)]
        if let Some(handover) = self.before_record_write.take() { handover(); }
        let start = crate::timing::monotonic_nanos();
        let written = tokio::time::timeout(WRITE_TIMEOUT, write_record(&data, &control, &packet, expected_generation))
            .await
            .map_err(|_| {
                io::Error::new(io::ErrorKind::TimedOut, "type 103 audio TCP write stalled")
            })??;
        if !written {
            control.discard_reserved_packet();
            self.endpoint = Some((data, control));
            return Ok(false);
        }
        self.written = next;
        self.payload_bytes = self.payload_bytes.saturating_add(raw.len() as u64);
        if priming {
            self.priming_bytes += u32::from(length);
        }
        if nonce == 0 {
            let now = crate::timing::monotonic_nanos();
            crate::emit_log(&format!("AUDIO: first AAC TCP type=103 rate={rate} SPF=1024 seq={wire_seq} rtp={timestamp} raw_bytes={} wire_bytes={} local_write_us={} write_duration_us={}",
                raw.len(), packet.len(), now/1000, now.saturating_sub(start)/1000));
        }
        control.packet_written(wire_seq, timestamp).await?;
        self.sequence = (self.sequence + 1) & SEQUENCE_MASK;
        self.endpoint = Some((data, control));
        Ok(true)
    }
}

async fn write_record(data: &TcpStream, control: &BufferedControl, record: &[u8], generation: u64) -> io::Result<bool> {
    let mut written = 0;
    while written < record.len() {
        data.writable().await?;
        // The watch guard is held only over a nonblocking syscall, never over a wait.
        match control.with_packet_ownership(generation, || data.try_write(&record[written..])) {
            None if written == 0 => return Ok(false),
            // A truncated record cannot be followed by another on this transport.
            None => return Err(io::Error::other("type 103 ownership changed during a partial TCP record")),
            Some(Ok(0)) => return Err(io::Error::new(io::ErrorKind::WriteZero, "type 103 audio TCP write returned zero")),
            Some(Ok(n)) => written += n,
            Some(Err(error)) if error.kind() == io::ErrorKind::WouldBlock => continue,
            Some(Err(error)) => return Err(error),
        }
    }
    Ok(true)
}

pub(super) fn closed() -> io::Error {
    io::Error::new(
        io::ErrorKind::BrokenPipe,
        "type 103 audio TCP transport closed",
    )
}

fn record_length(payload_bytes: usize, buffer_bytes: u32) -> io::Result<u16> {
    payload_bytes
        .checked_add(RECORD_OVERHEAD)
        .and_then(|n| u16::try_from(n).ok())
        .filter(|&n| payload_bytes > 0 && u32::from(n) <= buffer_bytes)
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "AAC record exceeds type 103 length or receiver buffer",
            )
        })
}

fn frame(
    key: &[u8; 32],
    raw: &[u8],
    sequence: u32,
    timestamp: u32,
    nonce: u64,
    rate: u32,
    total: u16,
) -> io::Result<Vec<u8>> {
    // Shairport's buffered processor treats the low 23 bits as a continuous sequence
    // and uses the high byte of SSRC to select the decoder. FLUSH names the same record.
    let format_index = match rate {
        44100 => 22,
        48000 => 23,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported buffered AAC rate",
            ))
        }
    };
    let mut header = [0u8; 12];
    header[..4].copy_from_slice(&(0x8000_0000 | MARKER | sequence).to_be_bytes());
    header[4..8].copy_from_slice(&timestamp.to_be_bytes());
    header[8] = format_index;
    let sealed = chacha_seal(key, &nonce64(nonce), raw, &header[4..12]);
    let mut packet = Vec::with_capacity(usize::from(total));
    packet.extend_from_slice(&total.to_be_bytes());
    packet.extend_from_slice(&header);
    packet.extend_from_slice(&sealed);
    packet.extend_from_slice(&nonce.to_le_bytes());
    Ok(packet)
}

#[cfg(test)]
pub(super) mod tests {
    use super::*;
    use crate::{
        bplist::{self, Value},
        rtsp::{parse_rtsp_messages, RtspChannel},
    };
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::{TcpListener, TcpStream};

    pub(crate) async fn control_pair(rate: u32) -> (BufferedControl, Arc<RtspChannel>, TcpStream) {
        let rtsp = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let controller = TcpStream::connect(rtsp.local_addr().unwrap())
            .await
            .unwrap();
        let (peer, _) = rtsp.accept().await.unwrap();
        let (read, write) = controller.into_split();
        let (channel, _) = RtspChannel::spawn(read, write, None);
        channel.buffered_ownership.publish(true);
        (
            BufferedControl::new(channel.clone(), "/".into(), None, rate, 1024),
            channel,
            peer,
        )
    }

    pub(crate) async fn accept_start(mut peer: TcpStream) {
        let mut input = Vec::new();
        for method in ["GETANCHOR", "SETRATEANCHORTIME"] {
            let request = loop {
                if let Some(msg) = parse_rtsp_messages(&mut input).unwrap().pop() {
                    break msg;
                }
                assert_ne!(peer.read_buf(&mut input).await.unwrap(), 0);
            };
            assert_eq!(request.first_line, format!("{method} / RTSP/1.0"));
            let anchor = Value::dict(vec![
                ("networkTimeSecs", Value::int(10)),
                ("networkTimeFrac", Value::int(0)),
                ("networkTimeTimelineID", Value::int(0)),
                ("networkTimeFlags", Value::int(0)),
            ]);
            let body = if method == "GETANCHOR" {
                bplist::encode(&anchor)
            } else {
                vec![]
            };
            let response = format!(
                "RTSP/1.0 200 OK\r\nCSeq: {}\r\nContent-Length: {}\r\n\r\n",
                request.cseq().unwrap(),
                body.len()
            );
            peer.write_all(response.as_bytes()).await.unwrap();
            peer.write_all(&body).await.unwrap();
        }
    }

    #[tokio::test]
    async fn tcp_generation_is_checked_again_after_reserving_the_record() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let data = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (control, channel, peer) = control_pair(48000).await;
        let start = tokio::spawn(accept_start(peer));
        let owner = channel.buffered_ownership.clone();
        let handover = owner.clone();
        let mut out = BufferedTransport::new(data, control, 100_000);
        out.before_record_write = Some(Box::new(move || {
            handover.publish(false);
            handover.publish(true);
        }));

        assert!(!out.send(&[7; 32], &[11, 11], 0, 48000, 0).await.unwrap());
        let received = tokio::time::timeout(Duration::from_secs(1), async {
            while !out.send(&[7; 32], &[22, 22], 1024, 48000, 1).await.unwrap() {
                tokio::task::yield_now().await;
            }
            assert_eq!(car.read_u16().await.unwrap(), 40);
            let mut record = [0; 38];
            car.read_exact(&mut record).await.unwrap();
            record
        }).await.unwrap();
        assert_eq!(&received[30..38], &0u64.to_le_bytes());
        let plain = crate::crypto::chacha_open(&[7; 32], &nonce64(0), &received[12..30], &received[4..12]).unwrap();
        assert_eq!(plain, [22, 22]);
        assert_eq!(out.written, 1);
        start.abort();
    }

    #[tokio::test]
    async fn two_buffered_access_units_match_independent_crypto_and_tcp_framing() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let phone = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (control, _channel, peer) = control_pair(44100).await;
        let rtsp = tokio::spawn(accept_start(peer));
        let mut transport = BufferedTransport::new(phone, control, 84);
        transport.sequence = 0x67ffff;
        // Expected ciphertext and tag generated with OpenSSL ChaCha20-Poly1305.
        let expected_sealed = [
            [
                0xde, 0xa9, 0xb2, 0x45, 0x32, 0x1a, 0x17, 0x45, 0x9f, 0x6c, 0xf1, 0x5f, 0x32, 0x0f,
                0x25, 0x83, 0xcb, 0x3c, 0x07, 0x71,
            ],
            [
                0x8b, 0xec, 0xac, 0x3d, 0x66, 0xde, 0x47, 0xcc, 0xd6, 0x94, 0x1e, 0x5f, 0x9a, 0xb6,
                0x7b, 0x0e, 0x36, 0x53, 0x04, 0xab,
            ],
        ];
        for i in 0..2 {
            assert!(transport
                .send(&[3; 32], &[0x21, 0x10, 0x56, 0xab], 1024 * i, 44100, 0)
                .await
                .unwrap());
        }
        rtsp.await.unwrap();
        assert_eq!((transport.written, transport.payload_bytes), (2, 8));
        for (index, sealed) in expected_sealed.iter().enumerate() {
            assert_eq!(car.read_u16().await.unwrap(), 42);
            let mut packet = [0; 40];
            car.read_exact(&mut packet).await.unwrap();
            assert_eq!(
                &packet[..4],
                if index == 0 {
                    &[0x80, 0xe7, 0xff, 0xff]
                } else {
                    &[0x80, 0xe8, 0, 0]
                }
            );
            assert_eq!(&packet[4..8], &(1024 * index as u32).to_be_bytes());
            assert_eq!(&packet[8..12], &[0x16, 0, 0, 0]);
            assert_eq!(&packet[12..32], sealed);
            assert_eq!(&packet[32..], &(index as u64).to_le_bytes());
        }
    }

    #[test]
    fn buffered_frame_refuses_an_access_unit_that_cannot_fit_the_receiver_or_prefix() {
        let build = |raw: &[u8], size| {
            frame(
                &[1; 32],
                raw,
                12,
                19,
                0,
                48000,
                record_length(raw.len(), size)?,
            )
        };
        assert!(build(&[4], 38).is_err());
        assert!(build(&vec![0; 65535 - 37], u32::MAX).is_err());
        let max = build(&vec![0; 65535 - 38], u32::MAX).unwrap();
        assert_eq!(&max[..2], &[0xff, 0xff]);
        assert_eq!(&max[10..14], &[0x17, 0, 0, 0]);
    }

    #[tokio::test]
    async fn pending_start_primes_only_the_receivers_byte_capacity_then_resumes() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let data = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (control, _channel, peer) = control_pair(48000).await;
        let mut stream = BufferedTransport::new(data, control, 80); // Two 40-byte encrypted records.
        for (i, expected) in [(0, true), (1, true), (2, false)] {
            let send = stream.send(&[1; 32], &[3, 4], 1024 * i, 48000, 0);
            assert_eq!(
                tokio::time::timeout(Duration::from_millis(150), send)
                    .await
                    .expect("pending playback ack must not hold the audio pump")
                    .unwrap(),
                expected
            );
        }
        let mut initial = [0; 80];
        car.read_exact(&mut initial).await.unwrap();
        assert_eq!(&initial[..2], &[0, 40]);
        assert_eq!(&initial[40..42], &[0, 40]);
        assert_eq!(
            car.try_read(&mut [0; 1]).unwrap_err().kind(),
            io::ErrorKind::WouldBlock
        );
        accept_start(peer).await;
        tokio::time::timeout(Duration::from_secs(1), async {
            while !stream
                .send(&[1; 32], &[5, 6], 2048, 48000, 0)
                .await
                .unwrap()
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        let mut resumed = [0; 40];
        car.read_exact(&mut resumed).await.unwrap();
        assert_eq!(&resumed[32..], &2u64.to_le_bytes());
        assert_eq!((stream.written, stream.payload_bytes), (3, 6));
    }

    #[cfg(target_os = "android")]
    #[tokio::test]
    async fn stale_mix_after_a_rapid_take_and_return_is_discarded_before_fresh_aac() {
        use crate::{aac::AacEncoder, audio_out::AudioOut, crypto::chacha_open};
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let data = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut car, _) = listener.accept().await.unwrap();
        let (control, channel, peer) = control_pair(44100).await;
        let encoder = AacEncoder::at_rate(44100).unwrap();
        let mut output = AudioOut::new_buffered_aac(data, [8; 32], encoder, control, 100_000);
        let old = output.buffered_loss_generation();
        output
            .send_pcm_for_generation(&[12000; 1024], old)
            .await
            .unwrap(); // Half an AU.
        channel.buffered_ownership.publish(false);
        channel.buffered_ownership.publish(true);
        output
            .send_pcm_for_generation(&[12000; 1024], old)
            .await
            .unwrap();
        assert!(output.aac.is_none());
        assert_eq!(output.blocked_samples(), 1024);
        assert_eq!(output.packets_sent(), 0);
        let fresh = output.buffered_loss_generation();
        assert_ne!(fresh, old);
        let rtsp = tokio::spawn(accept_start(peer));
        for _ in 0..25 {
            output
                .send_pcm_for_generation(&[0; 2048], fresh)
                .await
                .unwrap();
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        rtsp.await.unwrap();
        assert!(output.packets_sent() >= 16);
        assert_eq!(
            (
                output.stream_type(),
                output.samples_per_packet(),
                output.sample_rate()
            ),
            (103, 1024, 44100)
        );
        let mut bytes = 0;
        for index in 0..output.packets_sent() {
            let length = car.read_u16().await.unwrap() as usize;
            let mut record = vec![0; length - 2];
            car.read_exact(&mut record).await.unwrap();
            assert_eq!(&record[..4], &(0x80e70000 + index as u32).to_be_bytes());
            assert_eq!(&record[4..8], &(1024 * index as u32).to_be_bytes());
            let end = record.len() - 8;
            assert_eq!(&record[end..], &index.to_le_bytes());
            let raw =
                chacha_open(&[8; 32], &nonce64(index), &record[12..end], &record[4..12]).unwrap();
            assert!(!raw.is_empty());
            bytes += raw.len() as u64;
        }
        assert_eq!(output.payload_bytes_sent(), bytes);
    }

    #[tokio::test]
    async fn stalled_or_cancelled_partial_record_closes_tcp_before_any_retry() {
        use tokio::net::TcpSocket;
        for cancel in [false, true] {
            let (control, _channel, _control_peer) = control_pair(48000).await;

            // Negotiate a small receive window before connecting. One maximum-size record
            // cannot fit even after the receiver acknowledges everything in its window.
            let receiver = TcpSocket::new_v4().unwrap();
            receiver.set_recv_buffer_size(1024).unwrap();
            receiver.bind("127.0.0.1:0".parse().unwrap()).unwrap();
            let listener = receiver.listen(1).unwrap();
            let sender = TcpSocket::new_v4().unwrap();
            sender.set_send_buffer_size(1024).unwrap();
            let data = sender
                .connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (mut peer, _) = listener.accept().await.unwrap();
            let mut stream = BufferedTransport::new(data, control, 65535);
            let generation = stream.generation().unwrap();
            let raw = vec![0; 65535 - 38];
            {
                let send = stream.send(&[7; 32], &raw, 1, 48000, generation);
                tokio::pin!(send);
                tokio::select! {
                    n = peer.read_u16() => assert_eq!(n.unwrap(), 65535),
                    result = &mut send => panic!("expected a partially written frame: {result:?}"),
                }
                if !cancel {
                    assert_eq!(send.await.unwrap_err().kind(), io::ErrorKind::TimedOut);
                }
            }
            assert_eq!(
                stream
                    .send(&[7; 32], &[4], 1025, 48000, generation)
                    .await
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::BrokenPipe
            );
            assert!(stream.failed());
            assert_eq!((stream.written, stream.payload_bytes), (0, 0));
            let mut tail = Vec::new();
            tokio::time::timeout(Duration::from_secs(3), peer.read_to_end(&mut tail))
                .await
                .unwrap()
                .unwrap();
            assert!(
                tail.len() < 65535 - 2,
                "a partial record must be the final bytes on its TCP connection"
            );
        }
    }
}
