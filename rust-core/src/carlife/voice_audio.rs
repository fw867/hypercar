//! CarWith assistant playback on VR is separate from car microphone VR_DATA.
#[derive(Debug, PartialEq)]
pub(super) enum Event {
    Init { rate: u32, channels: u8 },
    Data { rate: u32, channels: u8, data: Vec<u8> },
    Stop,
}

#[derive(Default)]
pub(super) struct Decoder { format: Option<(u32, u8)> }
impl Decoder {
    pub fn receive(&mut self, service: u32, payload: &[u8]) -> Result<Option<Event>, &'static str> {
        match service {
            0x50002 => {
                self.format = None;
                let (rate, channels) = parse_format(payload)?;
                self.format = Some((rate, channels));
                Ok(Some(Event::Init { rate, channels }))
            }
            0x50003 => {
                let (rate, channels) = self.format.ok_or("data-before-init")?;
                if payload.len() % (channels as usize * 2) != 0 { return Err("unaligned-pcm16"); }
                if payload.is_empty() { return Ok(None); }
                Ok(Some(Event::Data { rate, channels, data: payload.to_vec() }))
            }
            0x50004 => {
                if !payload.is_empty() { return Err("stop-with-payload"); }
                self.format = None;
                Ok(Some(Event::Stop))
            }
            _ => Ok(None), // Including microphone input 0x58001, FEED_END and INTERRUPT.
        }
    }
}

// PCMPackageHead.setTTSAudioTrackParameter / CarlifeTTSInitProto.proto:
// 1=sampleRate, 2=channelConfig (count), 3=sampleFormat (bits).
fn parse_format(bytes: &[u8]) -> Result<(u32, u8), &'static str> {
    fn varint(bytes: &[u8], at: &mut usize) -> Result<u64, &'static str> {
        let mut value = 0u64;
        for shift in (0..70).step_by(7) {
            let byte = *bytes.get(*at).ok_or("truncated-init")?; *at += 1;
            if shift == 63 && byte > 1 { return Err("invalid-varint"); }
            value |= u64::from(byte & 127) << shift;
            if byte & 128 == 0 { return Ok(value); }
        }
        Err("invalid-varint")
    }
    if bytes.len() > 4096 { return Err("oversize-init"); }
    let mut fields = [None; 3]; let mut at = 0;
    while at < bytes.len() {
        let tag = varint(bytes, &mut at)?; let field = tag >> 3;
        if field == 0 { return Err("invalid-field"); }
        if (1..=3).contains(&field) {
            let slot = &mut fields[(field - 1) as usize];
            if tag & 7 != 0 || slot.is_some() { return Err("invalid-format-field"); }
            *slot = Some(varint(bytes, &mut at)?);
        } else {
            let skip = match tag & 7 {
                0 => { varint(bytes, &mut at)?; 0 },
                1 => 8,
                2 => usize::try_from(varint(bytes, &mut at)?).map_err(|_| "invalid-length")?,
                5 => 4,
                _ => return Err("invalid-wire-type"),
            };
            at = at.checked_add(skip).filter(|end| *end <= bytes.len()).ok_or("truncated-init")?;
        }
    }
    match fields {
        [Some(rate @ 4000..=96000), Some(channels @ 1..=2), Some(16)] => Ok((rate as u32, channels as u8)),
        _ => Err("unsupported-or-missing-format"),
    }
}

/// Shared by both VR socket owners; reading never writes or changes microphone capture.
#[derive(Default)]
pub(super) struct Receiver { observer: super::channel_diagnostics::Observer, decoder: Decoder }
impl Receiver {
    pub fn pending(&self) -> usize { self.observer.pending() }
    pub fn push(&mut self, input: &[u8], stats: &mut super::channel_diagnostics::Stats,
        state: &super::session::Shared, events: &tokio::sync::mpsc::UnboundedSender<crate::engine::CarLifeEvent>) {
        let decoder = &mut self.decoder;
        let mut parser_failures = stats.parse_fail;
        self.observer.push(input, stats, |service, payload, stats| {
            // Observer skips oversized frames without delivery. Retire the old format
            // before the next coalesced frame, so skipped INIT cannot leave it active.
            if stats.parse_fail != parser_failures { *decoder = Decoder::default(); }
            parser_failures = stats.parse_fail;
            if !matches!(service, 0x50002..=0x50004) { stats.unknown += 1; stats.observed_only += 1; return; }
            let plain = {
                let session = state.lock();
                if !session.started || !session.navigation_ready() { stats.not_ready += 1; return; }
                // STOP has a zero-length body; only INIT and DATA are encrypted by VRAudioManager.
                if payload.is_empty() { Vec::new() } else {
                    match session.decrypt(payload) {
                        Ok(bytes) => { stats.decrypt_ok += 1; bytes },
                        Err(_) => {
                            stats.decrypt_fail += 1;
                            if service == 0x50002 { *decoder = Decoder::default(); }
                            return;
                        }
                    }
                }
            };
            let event = match decoder.receive(service, &plain) {
                Ok(Some(Event::Init { rate, channels })) => crate::engine::CarLifeEvent::VoiceAudioInit { rate, channels },
                Ok(Some(Event::Data { rate, channels, data })) => {
                    stats.pcm(&data);
                    crate::engine::CarLifeEvent::VoiceAudioData { rate, channels, data }
                }
                Ok(Some(Event::Stop)) => crate::engine::CarLifeEvent::VoiceAudioStop,
                Ok(None) => { stats.empty += 1; return; }
                Err(_) => { stats.parse_fail += 1; parser_failures = stats.parse_fail; return; }
            };
            if events.send(event).is_ok() { stats.forwarded += 1; } else { stats.event_closed += 1; }
        });
        // Also cover a rejected header with no later complete frame in this read.
        if stats.parse_fail != parser_failures { *decoder = Decoder::default(); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn assistant_pcm_requires_init_and_is_independent_from_microphone_and_navigation() {
        let mut d = Decoder::default();
        assert!(d.receive(0x50003, &[1, 0]).is_err());
        // CarlifeTTSInit: sampleRate=16000, channelConfig=1, sampleFormat=16.
        assert_eq!(d.receive(0x50002, &[8, 128, 125, 16, 1, 24, 16]), Ok(Some(Event::Init { rate: 16000, channels: 1 })));
        assert_eq!(d.receive(0x50003, &[1, 0, 254, 255]), Ok(Some(Event::Data { rate: 16000, channels: 1, data: vec![1, 0, 254, 255] })));
        assert_eq!(d.receive(0x58001, &[9, 0]), Ok(None));
        assert_eq!(d.receive(0x40002, &[]), Ok(None));
        assert_eq!(d.receive(0x50004, &[]), Ok(Some(Event::Stop)));
        assert!(d.receive(0x50003, &[1, 0]).is_err());
    }
    #[test]
    fn unsupported_or_truncated_init_cannot_reuse_the_previous_pcm_format() {
        let good = [8, 128, 125, 16, 1, 24, 16];
        for bad in [vec![8, 128], vec![8, 128, 125, 16, 1, 24, 8], vec![8, 128, 125, 16, 7, 24, 16]] {
            let mut d = Decoder::default();
            d.receive(0x50002, &good).unwrap();
            assert!(d.receive(0x50002, &bad).is_err());
            assert!(d.receive(0x50003, &[1, 0]).is_err());
        }
    }
    #[test]
    fn malformed_pcm_is_rejected_without_inventing_samples_or_consuming_the_format() {
        let mut d = Decoder::default();
        d.receive(0x50002, &[8, 128, 125, 16, 1, 24, 16]).unwrap();
        assert!(d.receive(0x50003, &[1]).is_err());
        assert_eq!(d.receive(0x50003, &[]), Ok(None));
        assert!(matches!(d.receive(0x50003, &[2, 0]), Ok(Some(Event::Data { .. }))));
    }

    #[test]
    fn receiver_rejects_pre_auth_audio_and_failed_new_init_cannot_keep_an_old_format() {
        use super::super::{build_media_frame, session, channel_diagnostics};
        use std::sync::Arc;
        use parking_lot::Mutex;
        let state = session::microphone_test_session();
        let init = state.lock().encrypt(&[8, 128, 125, 16, 1, 24, 16]).unwrap();
        let pcm = state.lock().encrypt(&[1, 0]).unwrap();
        let mut r = Receiver::default(); let mut stats = channel_diagnostics::Stats::default();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let unready = Arc::new(Mutex::new(session::Session::default()));
        r.push(&build_media_frame(0x50002, 0, &init), &mut stats, &unready, &tx);
        assert!(rx.try_recv().is_err()); assert_eq!(stats.not_ready, 1);
        r.push(&build_media_frame(0x50002, 0, &init), &mut stats, &state, &tx);
        assert!(matches!(rx.try_recv(), Ok(crate::engine::CarLifeEvent::VoiceAudioInit { .. })));
        r.push(&build_media_frame(0x50003, 0, &[1, 2, 3]), &mut stats, &state, &tx);
        assert!(rx.try_recv().is_err()); assert_eq!(stats.decrypt_fail, 1);
        r.push(&build_media_frame(0x50002, 0, &[1, 2, 3]), &mut stats, &state, &tx);
        r.push(&build_media_frame(0x50003, 0, &pcm), &mut stats, &state, &tx);
        assert!(rx.try_recv().is_err()); assert_eq!(stats.decrypt_fail, 2);
        assert_eq!(stats.parse_fail, 1); assert_eq!(stats.pcm_bytes, 0);
    }

    #[test]
    fn documented_four_khz_voice_pcm_is_accepted_without_changing_samples() {
        let mut d = Decoder::default();
        assert_eq!(d.receive(0x50002, &[8, 160, 31, 16, 1, 24, 16]), Ok(Some(Event::Init { rate: 4000, channels: 1 })));
        assert_eq!(d.receive(0x50003, &[1, 0]), Ok(Some(Event::Data { rate: 4000, channels: 1, data: vec![1, 0] })));
    }

    #[test]
    fn oversized_init_retires_old_format_before_coalesced_data_and_valid_init_recovers() {
        use super::super::{build_media_frame, session, channel_diagnostics};
        let state = session::microphone_test_session();
        let init = state.lock().encrypt(&[8, 128, 125, 16, 1, 24, 16]).unwrap();
        let pcm = state.lock().encrypt(&[1, 0]).unwrap();
        let mut r = Receiver::default(); let mut stats = channel_diagnostics::Stats::default();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        r.push(&build_media_frame(0x50002, 0, &init), &mut stats, &state, &tx);
        assert!(matches!(rx.try_recv(), Ok(crate::engine::CarLifeEvent::VoiceAudioInit { .. })));
        let invalid = [build_media_frame(0x50002, 0, &vec![0; 256 * 1024 + 1]), build_media_frame(0x50003, 0, &pcm)].concat();
        r.push(&invalid, &mut stats, &state, &tx);
        assert!(rx.try_recv().is_err(), "coalesced DATA must not inherit a format after an oversized INIT");
        assert_eq!(stats.pcm_bytes, 0);
        r.push(&build_media_frame(0x50002, 0, &init), &mut stats, &state, &tx);
        r.push(&build_media_frame(0x50003, 0, &pcm), &mut stats, &state, &tx);
        assert!(matches!(rx.try_recv(), Ok(crate::engine::CarLifeEvent::VoiceAudioInit { .. })));
        assert!(matches!(rx.try_recv(), Ok(crate::engine::CarLifeEvent::VoiceAudioData { data, .. }) if data == [1, 0]));
    }
}
