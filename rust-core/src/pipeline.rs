//! Per-engine diagnostic facts. None of these facts confirms receiver display or speaker output.
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Debug, serde::Serialize)]
pub struct VideoFormat { pub width: u32, pub height: u32, pub codec: &'static str }

#[derive(Clone, Debug)]
pub(crate) struct MainVideoConnection {
    pub id: u64,
    pub resource_grant: Option<(std::sync::Arc<parking_lot::Mutex<crate::modes::Modes>>, u64)>,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct RejectedAudioRecovery {
    pub retired: crate::audio_diagnostics::ClockTarget,
    pub cleanup_stream: Option<i64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PendingAacReplacement {
    pub active: crate::audio_diagnostics::ClockTarget,
    pub stream: i64,
    pub after_switch: bool,
}

impl PendingAacReplacement {
    pub(crate) fn after_switch(self) -> Self {
        Self {
            active: crate::audio_diagnostics::ClockTarget { stream_id: self.stream, ..self.active },
            stream: self.active.stream_id,
            after_switch: true,
        }
    }
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Pipeline {
    pub video_requested_fps: u32,
    pub display_max_fps: Option<u32>,
    /// Main video frame writes per second in the most recent sampling window.
    pub video_actual_fps: Option<f64>,
    pub display_size: Option<(u32,u32)>,
    pub video_format: Option<VideoFormat>,
    /// Dimensions in the current sender's last successfully written VideoConfig header.
    #[serde(skip)]
    pub screen_config_size: Option<(u32,u32)>,
    #[serde(skip)]
    pub(crate) screen_config_generation: u64,
    #[serde(skip)]
    pub(crate) touch_modes: std::sync::Weak<parking_lot::Mutex<crate::modes::Modes>>,
    pub generation: u64,
    pub snapshot_sequence: u64,
    pub control_ready: bool,
    #[serde(skip)]
    pub control_trace_id: Option<u64>,
    pub record_accepted: bool,
    pub resources_accepted: bool,
    pub screen_setup_accepted: bool,
    pub video_channel_ready: bool,
    pub wifi_id: u64,
    pub wifi_iap2: String,
    pub screen_owned: Option<bool>,
    pub video_stream: String,
    pub video_source_units: u64,
    pub video_frames_written: u64,
    /// Current main connection with a completed video frame write. Telemetry totals span connections.
    #[serde(skip)]
    pub(crate) main_video_connection: Option<MainVideoConnection>,
    #[serde(skip)]
    pub(crate) keyframe_trace: std::sync::Weak<crate::screen::keyframe::Trace>,
    pub audio_ready: bool,
    pub audio_codec: String,
    pub main_audio_stream_type: Option<i64>,
    #[serde(skip)]
    pub(crate) main_audio_feedback: Option<crate::audio_diagnostics::FeedbackSource>,
    #[serde(skip)]
    pub(crate) rejected_audio_recovery: Option<RejectedAudioRecovery>,
    #[serde(skip)]
    pub(crate) pending_aac_replacement: Option<PendingAacReplacement>,
    pub audio_kbps: u64,
    pub audio_packets_written: u64,
    pub audio_signal_packets: u64,
    pub ui_requests: u64,
    pub screen_rebuilds: u64,
    pub timing: crate::timing::TimingDiagnostics,
    pub video_tcp: Option<crate::tcp_diagnostics::TcpSnapshot>,
    // The sender owns the counter. An outstanding feedback request must not keep it alive.
    #[serde(skip)]
    pub(crate) main_video_writes: std::sync::Weak<AtomicU64>,
    #[serde(skip)]
    retired_video_tcp: Option<crate::tcp_diagnostics::TcpSnapshot>,
}
impl Pipeline {
    pub(crate) fn retire_video_tcp(&mut self) {
        self.keyframe_trace = std::sync::Weak::new();
        self.main_video_connection = None;
        self.main_video_writes = Default::default();
        self.set_screen_config_size(None);
        if let Some(sample) = self.video_tcp.take() { self.retired_video_tcp = Some(sample); }
    }

    pub(crate) fn last_video_tcp(&self) -> Option<&crate::tcp_diagnostics::TcpSnapshot> {
        self.video_tcp.as_ref().or(self.retired_video_tcp.as_ref())
    }
    pub(crate) fn observe_main_sender(&mut self, sender: Option<&crate::audio_out::AudioOut>) {
        self.rejected_audio_recovery = None;
        self.pending_aac_replacement = None;
        self.main_audio_stream_type = sender.map(crate::audio_out::AudioOut::stream_type);
        self.audio_codec = sender.map(crate::audio_out::AudioOut::codec_name).unwrap_or("").into();
        self.main_audio_feedback = sender.and_then(crate::audio_out::AudioOut::feedback_source);
    }

    /// Allocate the order under the same lock that protects the captured facts.
    pub(crate) fn snapshot(&mut self) -> Self {
        self.snapshot_sequence += 1;
        self.clone()
    }

    pub(crate) fn activation_snapshot(&mut self, stage: &str) -> Option<Self> {
        match stage {
            "RECORD" => self.record_accepted = true,
            "RESOURCES_ACCEPTED" => self.resources_accepted = true,
            "SCREEN_SETUP" => self.screen_setup_accepted = true,
            "VIDEO_CHANNEL_READY" => self.video_channel_ready = true,
            _ => return None,
        }
        Some(self.snapshot())
    }

    /// Missing observations, not inferred receiver requirements or a retry instruction.
    pub fn activation_gaps(&self) -> String {
        let mut gaps=Vec::new();
        if !self.control_ready {gaps.push("control");}
        if !self.record_accepted {gaps.push("RECORD");}
        if !self.resources_accepted {gaps.push("resources");}
        if self.wifi_iap2!="READY_TRANSPORT" {gaps.push("wifi_iap2_unconfirmed");}
        if self.screen_owned!=Some(true) {gaps.push("screen_ownership");}
        if !self.screen_setup_accepted {gaps.push("screen_SETUP");}
        if !self.video_channel_ready {gaps.push("video_TCP");}
        if self.video_frames_written==0 {gaps.push("first_video_write");}
        if !self.audio_ready {gaps.push("audio_SETUP");}
        if gaps.is_empty(){"none_observed_receiver_presentation_unconfirmed".into()}else{gaps.join(",")}
    }
    pub(crate) fn set_screen_config_size(&mut self, size: Option<(u32, u32)>) {
        if self.screen_config_size != size {
            self.screen_config_generation = self.screen_config_generation.wrapping_add(1);
            self.screen_config_size = size;
        }
    }

    pub fn new() -> Self {
        Self { keyframe_trace:Default::default(),video_requested_fps:0,display_max_fps:None,video_actual_fps:None,display_size:None, video_format:None, screen_config_size:None, screen_config_generation:0, touch_modes:Default::default(), generation: NEXT.fetch_add(1,Ordering::Relaxed), snapshot_sequence:0, control_ready:false, control_trace_id:None, record_accepted:false, resources_accepted:false,
            screen_setup_accepted:false,video_channel_ready:false,wifi_id:0,
            wifi_iap2:"WAIT_SESSION".into(),screen_owned:None,video_stream:"WAIT_SETUP".into(),
            video_source_units:0,video_frames_written:0,main_video_connection:None,audio_ready:false,audio_codec:String::new(),main_audio_stream_type:None,main_audio_feedback:None,rejected_audio_recovery:None,pending_aac_replacement:None,audio_kbps:0,audio_packets_written:0,
            audio_signal_packets:0,ui_requests:0,screen_rebuilds:0,timing:Default::default(),video_tcp:None,main_video_writes:Default::default(),retired_video_tcp:None }
    }
    pub fn effective_fps(&self) -> u32 {
        self.display_max_fps.unwrap_or(self.video_requested_fps).min(self.video_requested_fps)
    }
    pub fn summary(&self) -> String {
        let declared = self.display_max_fps.map_or_else(|| "undeclared".into(), |fps| fps.to_string());
        let actual = self.video_actual_fps.map_or_else(|| "pending".into(), |fps| format!("{fps:.1}"));
        format!("PIPELINE#{}: control={} record={} resources={} screen_setup={} video_tcp_ready={} wifi_iap2={} screen_phone={:?} video={} source_units={} tcp_frames={} requested_fps={} declared_max_fps={declared} effective_fps={} actual_tcp_fps={actual} audio_ready={} audio_codec={} audio_kbps={} rtp_packets={} signal_packets={} requestUI={} rebuilds={} timing=[{}] tcp=[{}] activation_gaps=[{}] receiver_display=unknown receiver_audio=unknown",
            self.generation,self.control_ready,self.record_accepted,self.resources_accepted,self.screen_setup_accepted,self.video_channel_ready,self.wifi_iap2,self.screen_owned,self.video_stream,
            self.video_source_units,self.video_frames_written,self.video_requested_fps,self.effective_fps(),self.audio_ready,self.audio_codec,self.audio_kbps,self.audio_packets_written,
            self.audio_signal_packets,self.ui_requests,self.screen_rebuilds,self.timing.summary(),
            self.video_tcp.as_ref().map(|s|s.summary()).unwrap_or_else(||"not sampled".into()),self.activation_gaps())
    }
    pub fn stage_key(&self) -> String {
        format!("{}:{}:{:?}:{}:{}:{}:{}",self.control_ready,self.wifi_iap2,self.screen_owned,self.video_stream,
            self.audio_ready,self.ui_requests,self.screen_rebuilds)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disconnect_keeps_latest_video_sample_after_screen_retirement_only_in_this_engine() {
        let mut p = Pipeline::new();
        p.video_tcp = Some(crate::tcp_diagnostics::TcpSnapshot { connection_id: 9, sampled_ms: 123, ..Default::default() });
        p.retire_video_tcp();
        p.retire_video_tcp();
        assert!(p.video_tcp.is_none());
        assert_eq!(p.last_video_tcp().unwrap().sampled_ms, 123);
        p.video_tcp = Some(crate::tcp_diagnostics::TcpSnapshot { connection_id: 10, sampled_ms: 145, ..Default::default() });
        assert_eq!(p.last_video_tcp().unwrap().connection_id, 10);
        assert!(Pipeline::new().last_video_tcp().is_none());
    }

    #[test]
    fn periodic_and_immediate_snapshots_share_an_order_per_engine() {
        let mut pipeline = Pipeline::new();
        let first = pipeline.snapshot();
        let setup = pipeline.activation_snapshot("SCREEN_SETUP").unwrap();
        let tcp = pipeline.activation_snapshot("VIDEO_CHANNEL_READY").unwrap();
        let last = pipeline.snapshot();
        for (index, captured) in [first, setup, tcp, last].iter().enumerate() {
            assert_eq!(captured.generation, pipeline.generation);
            assert_eq!(serde_json::to_value(captured).unwrap()["snapshot_sequence"], index + 1);
        }
        let next = Pipeline::new().snapshot();
        assert!(next.generation > pipeline.generation);
        assert_eq!(next.snapshot_sequence, 1);
        assert!(next.main_audio_stream_type.is_none());
    }
    #[test] fn control_and_audio_do_not_prove_carplay_activation() {
        let mut p=Pipeline::new();p.control_ready=true;p.record_accepted=true;p.resources_accepted=true;p.audio_ready=true;
        assert!(p.activation_gaps().contains("wifi_iap2_unconfirmed"));
        assert!(p.activation_gaps().contains("screen_SETUP"));
        p.screen_owned=Some(true);p.screen_setup_accepted=true;p.video_channel_ready=true;p.video_frames_written=4187;
        assert_eq!(p.activation_gaps(),"wifi_iap2_unconfirmed");
        p.wifi_iap2="READY_TRANSPORT".into();
        assert_eq!(p.activation_gaps(),"none_observed_receiver_presentation_unconfirmed");
        assert!(p.summary().contains("receiver_display=unknown"));
        p.screen_owned=Some(false);assert_eq!(p.activation_gaps(),"screen_ownership");
    }
}

/// Publish exact acceptance boundaries immediately; a peer can close before the next heartbeat.
pub(crate) fn activation(pipeline: &std::sync::Arc<parking_lot::Mutex<Pipeline>>, stage: &str) {
    let Some(snapshot) = pipeline.lock().activation_snapshot(stage) else { return };
    if let Ok(json) = serde_json::to_string(&snapshot) { crate::emit_pipeline(&json); }
    crate::emit_state("activation", stage);
}
