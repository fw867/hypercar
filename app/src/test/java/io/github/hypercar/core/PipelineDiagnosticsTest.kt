package io.github.hypercar.core

import org.junit.Assert.*
import org.junit.Test

class PipelineDiagnosticsTest {
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
