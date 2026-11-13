//! Per-engine diagnostic facts. None of these facts confirms receiver display or speaker output.
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(1);

#[derive(Clone, Debug, serde::Serialize)]
pub struct Pipeline {
    pub generation: u64,
    pub control_ready: bool,
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
    pub audio_ready: bool,
    pub audio_packets_written: u64,
    pub audio_signal_packets: u64,
    pub ui_requests: u64,
    pub screen_rebuilds: u64,
    pub timing: crate::timing::TimingDiagnostics,
    pub video_tcp: Option<crate::tcp_diagnostics::TcpSnapshot>,
}
impl Pipeline {
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
    pub fn new() -> Self {
        Self { generation: NEXT.fetch_add(1,Ordering::Relaxed), control_ready:false, record_accepted:false, resources_accepted:false,
            screen_setup_accepted:false,video_channel_ready:false,wifi_id:0,
            wifi_iap2:"WAIT_SESSION".into(),screen_owned:None,video_stream:"WAIT_SETUP".into(),
            video_source_units:0,video_frames_written:0,audio_ready:false,audio_packets_written:0,
            audio_signal_packets:0,ui_requests:0,screen_rebuilds:0,timing:Default::default(),video_tcp:None }
    }
    pub fn summary(&self) -> String {
        format!("PIPELINE#{}: control={} record={} resources={} screen_setup={} video_tcp_ready={} wifi_iap2={} screen_phone={:?} video={} source_units={} tcp_frames={} audio_ready={} rtp_packets={} signal_packets={} requestUI={} rebuilds={} timing=[{}] tcp=[{}] activation_gaps=[{}] receiver_display=unknown receiver_audio=unknown",
            self.generation,self.control_ready,self.record_accepted,self.resources_accepted,self.screen_setup_accepted,self.video_channel_ready,self.wifi_iap2,self.screen_owned,self.video_stream,
            self.video_source_units,self.video_frames_written,self.audio_ready,self.audio_packets_written,
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
    let snapshot = {
        let mut p = pipeline.lock();
        match stage {
            "RECORD" => p.record_accepted = true,
            "RESOURCES_ACCEPTED" => p.resources_accepted = true,
            "SCREEN_SETUP" => p.screen_setup_accepted = true,
            "VIDEO_CHANNEL_READY" => p.video_channel_ready = true,
            _ => return,
        }
        p.clone()
    };
    if let Ok(json) = serde_json::to_string(&snapshot) { crate::emit_pipeline(&json); }
    crate::emit_state("activation", stage);
}
