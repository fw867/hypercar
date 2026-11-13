package io.github.hypercar.core

import org.junit.Assert.*
import org.junit.Test

class PipelineDiagnosticsTest {
    @Test fun audio_format_is_actual_negotiated_codec_and_rate_can_be_zero_or_unknown() {
        val pcm=PipelineDiagnostics.parse("""{"generation":1,"audio_ready":true,"audio_codec":"PCM","audio_kbps":1536}""")!!
        assertEquals("PCM",pcm.audioCodec);assertEquals(1536L,pcm.audioKbps)
        val aac=PipelineDiagnostics.parse("""{"generation":2,"audio_ready":true,"audio_codec":"AAC","audio_kbps":0}""")!!
        assertEquals("AAC",aac.audioCodec);assertEquals(0L,aac.audioKbps)
        val old=PipelineDiagnostics.parse("""{"generation":3,"audio_ready":true}""")!!
        assertEquals("",old.audioCodec);assertNull(old.audioKbps)
        val invalid=PipelineDiagnostics.parse("""{"generation":4,"audio_codec":"auto","audio_kbps":-1}""")!!
        assertEquals("",invalid.audioCodec);assertNull(invalid.audioKbps)
    }
    @Test fun control_and_transport_do_not_confirm_receiver_presentation() {
        val control = PipelineDiagnostics(generation=1,active=true,controlReady=true,
            recordAccepted=true,resourcesAccepted=true,audioReady=true)
        assertEquals("等待车机交出主屏",control.activationLabel)
        val video=control.copy(screenOwned=true,screenSetupAccepted=true,videoChannelReady=true)
        assertEquals("等待首帧写出",video.activationLabel)
        assertEquals("视频已写出 · 车机显示待确认",video.copy(writtenFrames=4187).activationLabel)
        // No Wi-Fi iAP2 readiness requirement is imposed on PC or other receivers.
        assertEquals("视频已写出 · 车机显示待确认",video.copy(writtenFrames=1,wifiIap2="WAIT_PEER_LONG").activationLabel)
        assertEquals("主屏已交还车机",video.copy(videoStream="PAUSED",screenOwned=false).activationLabel)
    }
    @org.junit.Test fun recordAcceptanceIsIndependentOfControlAndVideoReadiness() {
        val p = PipelineDiagnostics.parse("""{"generation":7,"control_ready":false,"record_accepted":true,"resources_accepted":false,"screen_setup_accepted":false,"video_channel_ready":false}""")!!
        org.junit.Assert.assertTrue(p.recordAccepted)
        org.junit.Assert.assertFalse(p.controlReady)
        org.junit.Assert.assertFalse(p.resourcesAccepted)
        org.junit.Assert.assertFalse(p.screenSetupAccepted)
        org.junit.Assert.assertFalse(p.videoChannelReady)
    }

    @Test fun queueAndCumulativeRetransmitsDoNotTurnHealthyTransportRed() {
        val p=PipelineDiagnostics.parse("""{"generation":11,"control_ready":true,"wifi_iap2":"READY_TRANSPORT","video_stream":"READY","audio_ready":true,"timing":{"requests":20,"replies":20},"video_tcp":{"state":1,"acked_bytes":8192,"outq_bytes":1024,"total_retrans":9}}""")!!
        assertEquals(PipelineStatus.SUCCESS,p.controlStatus)
        assertEquals(PipelineStatus.SUCCESS,p.wifiStatus)
        assertEquals(PipelineStatus.SUCCESS,p.videoStatus)
        assertEquals(PipelineStatus.SUCCESS,p.audioStatus)
        assertEquals(PipelineStatus.SUCCESS,p.timingStatus)
        assertEquals(PipelineStatus.SUCCESS,p.tcpStatus)
    }
    @Test fun waitingBorrowedScreenAndSynOnlyCounterAreNotFailures() {
        val p=PipelineDiagnostics(generation=12,active=true,wifiIap2="WAIT_PEER_LONG",
            screenOwned=false,videoStream="PAUSED",tcpState=1,tcpAckedBytes=1)
        assertEquals(PipelineStatus.NEUTRAL,p.controlStatus)
        assertEquals(PipelineStatus.NEUTRAL,p.wifiStatus)
        assertEquals(PipelineStatus.NEUTRAL,p.videoStatus)
        assertEquals(PipelineStatus.NEUTRAL,p.audioStatus)
        assertEquals(PipelineStatus.NEUTRAL,p.timingStatus)
        assertEquals(PipelineStatus.NEUTRAL,p.tcpStatus)
    }
    @Test fun explicitFailuresAreRedButEndedSnapshotsAreNeutral() {
        val p=PipelineDiagnostics.parse("""{"generation":13,"wifi_iap2":"FAILED","video_stream":"FAILED","timing":{"requests":20,"replies":19,"send_errors":1,"receive_errors":2},"video_tcp":{"state":8,"acked_bytes":8192,"info_errno":null}}""")!!
        assertEquals(1L,p.timingSendErrors);assertEquals(2L,p.timingReceiveErrors)
        assertEquals(PipelineStatus.ERROR,p.wifiStatus)
        assertEquals(PipelineStatus.ERROR,p.videoStatus)
        assertEquals(PipelineStatus.ERROR,p.timingStatus)
        assertEquals(PipelineStatus.ERROR,p.tcpStatus)
        assertEquals(PipelineStatus.ERROR,p.copy(tcpState=1,tcpInfoError=9).tcpStatus)
        val old=p.copy(active=false)
        assertEquals(PipelineStatus.NEUTRAL,old.wifiStatus)
        assertEquals(PipelineStatus.NEUTRAL,old.videoStatus)
        assertEquals(PipelineStatus.NEUTRAL,old.timingStatus)
        assertEquals(PipelineStatus.NEUTRAL,old.tcpStatus)
    }
    @Test fun preservesUnknownOwnershipAndDoesNotInferSignalFromRtp() {
        val p = PipelineDiagnostics.parse("""{"generation":7,"control_ready":true,"screen_owned":null,"wifi_iap2":"WAIT_PEER_LONG","audio_packets_written":1000,"audio_signal_packets":0,"video_frames_written":200} """)!!
        assertNull(p.screenOwned)
        assertEquals("尚无车机首包", p.wifiLabel)
        assertEquals(1000L, p.audioPackets)
        assertEquals(0L, p.signalPackets)
        assertEquals(200L, p.writtenFrames)
    }
    @Test fun readsReceiverOwnershipAndRejectsInvalidSnapshots() {
        assertEquals(false, PipelineDiagnostics.parse("""{"generation":8,"screen_owned":false}""")!!.screenOwned)
        assertNull(PipelineDiagnostics.parse("{}"))
        assertNull(PipelineDiagnostics.parse("""{"generation":0}"""))
        assertNull(PipelineDiagnostics.parse("not json"))
    }
    @Test fun readsTimingAndPreservesUnavailableTcpCounters() {
        val p = PipelineDiagnostics.parse("""{"generation":9,"timing":{"requests":12,"replies":11,"reply_age_ms":2200},"video_tcp":{"acked_bytes":4096,"outq_bytes":0,"total_retrans":null,"age_ms":300}}""")!!
        assertEquals(12L, p.timingRequests)
        assertEquals(11L, p.timingReplies)
        assertEquals(2200L, p.timingReplyAgeMs)
        assertEquals(4096L, p.tcpAckedBytes)
        assertEquals(0L, p.tcpQueuedBytes)
        assertNull(p.tcpRetransmissions)
        assertEquals(300L, p.tcpSampleAgeMs)
        assertTrue(p.tcpLabel.contains("未知"))
        val old = PipelineDiagnostics.parse("""{"generation":10}""")!!
        assertNull(old.tcpAckedBytes)
        assertNull(old.timingReplyAgeMs)
        assertTrue(old.timingLabel.contains("最近 无"))
    }
}
