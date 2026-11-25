use super::*;

#[derive(Default)]
pub(in crate::carlife) struct VideoPacketProbe {
    reads: u64,
    received: u64,
    packets: u64,
    previous_read: u64,
    data_packets: usize,
    mode: Option<String>,
    eligible: bool,
    phase: Recovery,
    deadline: Option<tokio::time::Instant>,
    seen_nals: u64,
    seen_parameters: Option<(bool, bool, bool)>,
    tested_bare_keyframe: bool,
    pub(in crate::carlife) inspect_next: bool,
}

#[derive(Default, PartialEq, Debug)]
enum Recovery {
    #[default]
    Idle,
    NextPacket,
    Keyframe,
    Recovered,
}

impl VideoPacketProbe {
    pub(in crate::carlife) fn recovery_enabled(&self) -> bool { self.mode.is_some() }

    pub(in crate::carlife) fn expired(&self) -> bool {
        let expired = self.deadline.is_some_and(|limit| tokio::time::Instant::now() >= limit);
        if expired { log::info!(target: "carwith_probe", "RECOVERY deadline passed phase={:?}", self.phase); }
        expired
    }

    pub(in crate::carlife) async fn read_until_deadline<T>(
        &self, read: impl Future<Output=std::io::Result<T>>,
    ) -> std::io::Result<T> {
        let Some(limit) = self.deadline else { return read.await };
        match tokio::time::timeout_at(limit, read).await {
            Ok(result) => result,
            Err(_) => {
                log::info!(target: "carwith_probe", "RECOVERY timeout phase={:?}", self.phase);
                Err(std::io::ErrorKind::TimedOut.into())
            }
        }
    }

    pub(in crate::carlife) fn check_packet(&mut self, kind: u32, body: &mut [u8], session: &Session) -> bool {
        if self.expired() { return false; }
        if self.phase == Recovery::NextPacket && (kind != VIDEO_DATA || body.is_empty() || body.len()%16 != 0) {
            log::info!(target: "carwith_probe", "RECOVERY invalid next header kind=0x{kind:x} body={}", body.len());
            return false;
        }
        if kind != VIDEO_DATA { return true; }
        self.data_packets += 1;
        if self.data_packets == 1 {
            self.mode = std::env::var("HC_PROBE_VIDEO_RECOVERY").ok().filter(|m|
                matches!(m.as_str(), "natural" | "inject-first" | "inject-two"));
            self.eligible = session.ready && session.admitted && session.started && session.seed.is_some();
            if std::env::var_os("HC_PROBE_DECLARED_ONLY").is_some() && session.init_used_fallback {
                self.mode = None;
            }
        }
        let inject = match self.mode.as_deref() {
            Some("inject-first") => self.data_packets == 1,
            Some("inject-two") => self.data_packets <= 2,
            _ => false,
        };
        if inject && self.eligible && body.len() >= 16 && body.len()%16 == 0 && session.decrypt(body).is_ok() {
            // Use a ciphertext block whose plaintext ends in zero (invalid PKCS#7).
            let invalid = session.encrypt(&[0;16]).unwrap();
            let end = body.len();
            body[end-16..].copy_from_slice(&invalid[..16]);
            log::info!(target: "carwith_probe", "RECOVERY injected padding fault data_packet={}", self.data_packets);
        }
        true
    }

    pub(in crate::carlife) fn drop_first(&mut self, kind: u32, error: &str, aus: u64) -> bool {
        if self.recovery_enabled() && self.eligible && self.data_packets == 1 && aus == 0 &&
            kind == VIDEO_DATA && error == "invalid AES padding" && self.phase == Recovery::Idle {
            self.phase = Recovery::NextPacket;
            self.deadline = Some(tokio::time::Instant::now() + Duration::from_millis(500));
            log::info!(target: "carwith_probe", "RECOVERY dropped first VIDEO_DATA: invalid AES padding; keep seven channels");
            return true;
        }
        if self.deadline.is_some() {
            log::info!(target: "carwith_probe", "RECOVERY end phase={:?} error={error}", self.phase);
        }
        false
    }

    // Called only after the normal full-body decrypt has succeeded.
    pub(in crate::carlife) fn feed_after_reset(&mut self, state: &Shared) -> bool {
        if self.phase == Recovery::NextPacket {
            let mut session = state.lock();
            session.probe_reset_written = false;
            session.probe_reset.as_ref().expect("live CMD task").notify_one();
            log::info!(target: "carwith_probe", "RECOVERY next VIDEO_DATA strict decrypt OK; request one 0x1800b");
            self.phase = Recovery::Keyframe;
            let seconds = std::env::var("HC_PROBE_KEYFRAME_SECONDS").ok()
                .and_then(|s| s.parse::<u64>().ok()).unwrap_or(3).clamp(1, 10);
            self.deadline = Some(tokio::time::Instant::now() + Duration::from_secs(seconds));
            return false;
        }
        self.phase != Recovery::Keyframe || state.lock().probe_reset_written
    }

    pub(in crate::carlife) async fn accept_unit(&mut self, data: &[u8], codec: crate::video_format::VideoCodec) -> bool {
        if self.phase != Recovery::Keyframe { return true; }
        let hevc = codec == crate::video_format::VideoCodec::Hevc;
        let kinds: Vec<_> = crate::screen::annexb_nals(data).iter().map(|n|
            if hevc { (n[0] >> 1) & 63 } else { n[0] & 31 }).collect();
        let mask = kinds.iter().fold(0u64, |bits, kind| bits | 1u64 << kind);
        if mask & !self.seen_nals != 0 {
            self.seen_nals |= mask;
            log::info!(target: "carwith_probe", "RECOVERY post-RESET access unit codec={codec:?} nals={kinds:?}");
        }
        let keyframe = if hevc { kinds.iter().any(|k| matches!(k,19..=21)) } else { kinds.contains(&5) };
        let configured = if hevc { [32,33,34].iter().all(|k| kinds.contains(k)) }
            else { [7,8].iter().all(|k| kinds.contains(k)) };
        if !keyframe || (!configured && self.tested_bare_keyframe) { return false; }
        if !configured { self.tested_bare_keyframe = true; }
        // A host decoder reached via adb reverse validates one AU in memory, returning 1 on output.
        let check = async {
            let port = std::env::var("HC_PROBE_DECODER_PORT").ok()?.parse::<u16>().ok()?;
            let mut socket = TcpStream::connect(("127.0.0.1",port)).await.ok()?;
            socket.write_u8(u8::from(hevc)).await.ok()?;
            socket.write_u32(data.len().try_into().ok()?).await.ok()?;
            socket.write_all(data).await.ok()?;
            socket.read_u8().await.ok()
        };
        let decoded = matches!(tokio::time::timeout_at(self.deadline.unwrap(), check).await, Ok(Some(1))) && !self.expired();
        log::info!(target: "carwith_probe", "RECOVERY post-RESET codec={codec:?} nals={kinds:?} configured={configured} decoder_output={decoded}");
        if configured && decoded { self.phase = Recovery::Recovered; self.deadline = None; return true; }
        false
    }

    pub(in crate::carlife) fn observe_parameters(&mut self, parser: &video::VideoParser) {
        if self.phase != Recovery::Keyframe { return; }
        let flags = parser.probe_parameters();
        if self.seen_parameters != Some(flags) {
            self.seen_parameters = Some(flags);
            let (vps, sps, pps) = flags;
            log::info!(target: "carwith_probe", "RECOVERY post-RESET parameter cache vps={vps} sps={sps} pps={pps}");
        }
    }
    pub(in crate::carlife) fn read(&mut self, count: usize) {
        self.reads += 1;
        self.received += count as u64;
    }

    pub(in crate::carlife) fn packet(
        &mut self,
        buf: &[u8],
        consumed: usize,
        access_units: u64,
        session: &Session,
    ) {
        self.packets += 1;
        self.inspect_next = false;
        if access_units != 0 || self.packets > 32 || !log::log_enabled!(target: "carwith_probe", log::Level::Info) {
            return;
        }
        let word = |bytes: &[u8]| u32::from_be_bytes(bytes.try_into().unwrap());
        let declared = word(&buf[..4]);
        let wire = &buf[12..consumed];
        let next = &buf[consumed..];
        let next_header = (next.len() >= 12).then(|| (word(&next[8..12]), word(&next[..4])));
        let offset = self.received - buf.len() as u64;
        let key = session.seed.as_deref();
        log::info!(target: "carwith_probe",
            "VIDEO packet={} type=0x{:x} ts={} stream_offset={offset} declared={declared} body={} mod16={} reads_since_last={} buffered_after={} next_type_len={next_header:?} key_bits={} ready={} admitted={} started={}",
            self.packets, word(&buf[8..12]), word(&buf[4..8]), wire.len(), wire.len() % 16,
            self.reads - self.previous_read, next.len(), key.map_or(0, |k| k.len() * 8),
            session.ready, session.admitted, session.started);
        self.previous_read = self.reads;
        if wire.is_empty() || wire.len() % 16 != 0 { return; }
        let Some(key) = key else { return; };
        // Inspect only the outer blocks. Never feed diagnostic plaintext to the video parser.
        let first = &wire[..16];
        let last = &wire[wire.len() - 16..];
        let mut head = aes::Block::clone_from_slice(first);
        let mut tail = aes::Block::clone_from_slice(last);
        macro_rules! inspect { ($cipher:ty) => {{
            let cipher = <$cipher>::new_from_slice(key).unwrap();
            cipher.decrypt_block(&mut head);
            cipher.decrypt_block(&mut tail);
        }}; }
        match key.len() {
            16 => inspect!(aes::Aes128),
            24 => inspect!(aes::Aes192),
            32 => inspect!(aes::Aes256),
            _ => return,
        }
        let pad = tail[15] as usize;
        let valid = pkcs7(&tail);
        log::info!(target: "carwith_probe",
            "VIDEO edges packet={} raw_annexb={:?} ecb_annexb={:?} ecb_pkcs7={:?} ecb_trailing_zeros={} wire_tail_zero_block={}",
            self.packets, annexb_header(first), annexb_header(&head), valid.then_some(pad),
            tail.iter().rev().take_while(|&&b| b == 0).count(), last.iter().all(|&b| b == 0));
        if !valid && key.len() == 16 {
            inspect_failed_body(wire, key);
            self.inspect_next = session.started && word(&buf[8..12]) == VIDEO_DATA;
        }
    }

    // The current VIDEO_DATA is already terminal. Briefly observe the following wire header
    // in this opt-in probe only; no subsequent packet is delivered to the decoder.
    pub(in crate::carlife) async fn next_packet(
        &self, rd: &mut tokio::net::tcp::OwnedReadHalf, buffered: &[u8], state: &Shared,
    ) {
        let read = async {
            let mut header = [0; 12];
            let prefix = buffered.len().min(12);
            header[..prefix].copy_from_slice(&buffered[..prefix]);
            rd.read_exact(&mut header[prefix..]).await?;
            let len = u32::from_be_bytes(header[..4].try_into().unwrap()) as usize;
            let kind = u32::from_be_bytes(header[8..].try_into().unwrap());
            if len > 65536 { return Ok(format!("type=0x{kind:x} declared={len} exceeds_probe_limit")); }
            let mut body = vec![0; len];
            let prefix = buffered.len().saturating_sub(12).min(len);
            if prefix > 0 { body[..prefix].copy_from_slice(&buffered[12..12 + prefix]); }
            rd.read_exact(&mut body[prefix..]).await?;
            let decrypted = state.lock().decrypt(&body);
            let nal = decrypted.as_ref().ok().and_then(|p| p.get(..16)).and_then(annexb_header);
            Ok::<_,std::io::Error>(format!(
                "type=0x{kind:x} declared={len} current_key_decrypt_ok={} annexb={nal:?}", decrypted.is_ok()))
        };
        let result = tokio::time::timeout(Duration::from_millis(100), read).await;
        log::info!(target: "carwith_probe", "VIDEO next at declared boundary: {result:?}");
    }
}

// Check a small clear wrapper and an explicit CBC IV without changing what the real reader accepts.
// Only report a match when both a video header and a valid tail agree.
fn inspect_failed_body(wire: &[u8], key: &[u8]) {
    let cipher = aes::Aes128::new_from_slice(key).unwrap();
    let decrypt = |bytes: &[u8]| {
        let mut block = aes::Block::clone_from_slice(bytes);
        cipher.decrypt_block(&mut block);
        block
    };
    let mut ecb_ranges = Vec::new();
    let mut cbc_ranges = Vec::new();
    let mut ecb_heads = Vec::new();
    for start in 0..=32.min(wire.len() - 16) {
        let head = decrypt(&wire[start..start + 16]);
        let ecb_header = annexb_header(&head).is_some();
        if ecb_header { ecb_heads.push(start); }
        let cbc_header = start >= 16 && annexb_header(&xor_block(&head, &wire[start - 16..start])).is_some();
        if !ecb_header && !cbc_header { continue; }
        for trim in 0..=32.min(wire.len() - start - 16) {
            let end = wire.len() - trim;
            if (end - start) % 16 != 0 { continue; }
            let tail = decrypt(&wire[end - 16..end]);
            if ecb_header && pkcs7(&tail) { ecb_ranges.push((start, trim)); }
            if cbc_header && pkcs7(&xor_block(&tail, &wire[end - 32..end - 16])) {
                cbc_ranges.push((start, trim));
            }
        }
    }
    let raw_head = (0..=32.min(wire.len() - 16)).find(|&i| annexb_header(&wire[i..i+16]).is_some());
    let truncated_key_match = key.iter().position(|&b| b == 0).map(|nul| {
        let mut candidate = [0; 16];
        candidate[..nul].copy_from_slice(&key[..nul]);
        let candidate = aes::Aes128::new_from_slice(&candidate).unwrap();
        let mut head = aes::Block::clone_from_slice(&wire[..16]);
        let mut tail = aes::Block::clone_from_slice(&wire[wire.len()-16..]);
        candidate.decrypt_block(&mut head);
        candidate.decrypt_block(&mut tail);
        annexb_header(&head).is_some() && pkcs7(&tail)
    });
    log::info!(target: "carwith_probe",
        "VIDEO failure checks raw_header_at={raw_head:?} ecb_headers_at={ecb_heads:?} ecb_ranges={ecb_ranges:?} cbc_with_iv_ranges={cbc_ranges:?} nul_terminated_key_match={truncated_key_match:?}");
}

fn xor_block(block: &[u8], mask: &[u8]) -> [u8; 16] {
    std::array::from_fn(|i| block[i] ^ mask[i])
}

fn pkcs7(tail: &[u8]) -> bool {
    let n = tail[15] as usize;
    (1..=16).contains(&n) && tail[16 - n..].iter().all(|&b| usize::from(b) == n)
}

// (start-code length, candidate AVC type, candidate HEVC type). Codec is resolved downstream.
fn annexb_header(block: &[u8]) -> Option<(usize, u8, u8)> {
    let prefix = if block.starts_with(&[0, 0, 0, 1]) {
        4
    } else if block.starts_with(&[0, 0, 1]) {
        3
    } else {
        return None;
    };
    Some((prefix, block[prefix] & 31, (block[prefix] >> 1) & 63))
}

#[tokio::test(start_paused = true)]
async fn stalled_next_packet_expires_without_reset_or_new_budget() {
    let mut probe = VideoPacketProbe {
        data_packets: 1, mode: Some("natural".into()), eligible: true, ..Default::default()
    };
    assert!(probe.drop_first(VIDEO_DATA, "invalid AES padding", 0));
    let deadline = probe.deadline;
    let err = probe.read_until_deadline(std::future::pending::<std::io::Result<usize>>()).await.unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::TimedOut);
    assert!(probe.expired());
    assert!(!probe.check_packet(VIDEO_DATA, &mut [0;16], &Session::default()));
    assert!(!probe.drop_first(VIDEO_DATA, "invalid AES padding", 0));
    assert_eq!(probe.deadline, deadline);
    assert_eq!(probe.phase, Recovery::NextPacket);
}
