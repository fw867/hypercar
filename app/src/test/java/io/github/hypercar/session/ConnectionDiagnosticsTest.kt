package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.session.ConnectionDiagnostics.Stage

class ConnectionDiagnosticsTest {
    @Test fun lateTeardownEnrichesOnlyFinishedRowAndKeepsLastSampleWhenSocketAlreadyRetired() {
        val journal = ConnectionDiagnostics { 0L }
        val first = journal.begin("manual", "bmw_id85")
        journal.engineStarted(first, 7, "AA", 90, 1500)
        journal.eventChannel(first, 7, 20, 100, "open", "")
        journal.videoSample(first, 7, "sampled_ms=150 rtt_us=4500")
        journal.finish(first, "STOPPED", "user")
        val next = journal.begin("retry", "bmw_id85")
        journal.engineStarted(next, 8, "AA", 190, 1600)
        journal.eventChannel(first, 7, 20, 200, "local_close", "")
        journal.videoSample(next, 7, "stale_tcp")
        val (old, fresh) = journal.report()
        assertTrue(old.contains("event_close=[local_close]") && old.contains("reason=user"))
        assertTrue(old.contains("sampled_ms=150"))
        assertTrue(fresh.contains("video_tcp=[unknown]") && fresh.contains("event_close=[unknown]"))
    }

    @Test fun firstInboundIsNotReentryAndPreAirplayRepeatBelongsOnlyToThisAttemptAndPeer() {
        val journal = ConnectionDiagnostics { 0L }
        val id = journal.begin("retry", "bmw_id85", 100)
        journal.rfcommAccepted(id, "AA", 98)
        journal.rfcommAccepted(id, "AA", 120)
        journal.rfcommAccepted(id, "BB", 125)
        journal.rfcommAccepted(id, "AA", 135)
        journal.engineStarted(id, 1, "AA", 150, 9000)
        assertTrue(journal.report().single().contains("rfcomm_reentry_ms=135"))
        journal.engineStarted(id, 2, "AA", 220, 9070)
        journal.rfcommAccepted(id, "AA", 215)
        journal.rfcommAccepted(id, "AA", 225)
        assertTrue(journal.report().single().contains("rfcomm_reentry_ms=unknown"))
    }

    @Test fun disconnectEvidenceIsScopedToAttemptEnginePeerAndFirstClose() {
        val journal = ConnectionDiagnostics { 0L }
        val first = journal.begin("manual", "bmw_id85")
        journal.engineStarted(first, 7, "AA", 90, 1500)
        journal.eventChannel(first, 7, 20, 100, "open", "")
        journal.rfcommAccepted(first, "BB", 130)
        journal.rfcommAccepted(first, "aa", 99)
        assertTrue(journal.report().single().contains("rfcomm_reentry_ms=unknown"))
        journal.rfcommAccepted(first, "aa", 140)
        journal.rfcommAccepted(first, "AA", 130)
        journal.eventChannel(first, 7, 20, 155, "peer_eof", "sampled_ms=150 rtt_us=4500")
        journal.finish(first, "STOPPED", "事件通道关闭")
        val second = journal.begin("retry", "bmw_id85")
        journal.engineStarted(second, 8, "AA", 90, 1500)
        journal.eventChannel(first, 7, 20, 180, "local_close", "")
        journal.eventChannel(second, 7, 20, 180, "local_close", "")
        val (old, fresh) = journal.report()
        for (field in listOf("event_rtsp=20","event_close_ms=155","peer_eof","sampled_ms=150","rfcomm_reentry_ms=140"))
            assertTrue(old, old.contains(field))
        assertFalse(fresh.contains("peer_eof"))
        journal.engineStarted(second, 9, "AA", 90, 1500)
        journal.eventChannel(second, 8, 21, 200, "read_error", "")
        assertTrue(journal.report().last().contains("event_rtsp=unknown"))
    }

    @Test fun recordAcceptedButResourceDeclarationClosedIsNotStreaming() {
        var now = 0L
        val journal = ConnectionDiagnostics { now }
        val id = journal.begin("manual", "bmw")
        journal.mark(id, Stage.RECORD)
        now = 4
        journal.mark(id, Stage.WAITING_INVITATION, "control EOF after RECORD")
        val waiting = journal.report().single()
        assertTrue(waiting.contains("record=true invite=false"))
        assertTrue(waiting.contains("outcome=WAITING_INVITATION"))
        assertFalse(waiting.contains("RESOURCES_ACCEPTED@"))
        journal.mark(id, Stage.CTRL_INVITED)
        journal.mark(id, Stage.RESOURCES_ACCEPTED)
        journal.mark(id, Stage.CONTROL_READY)
        assertTrue(journal.report().single().contains("outcome=CONTROL_READY"))
        journal.mark(id, Stage.FIRST_VIDEO_FRAME)
        assertTrue(journal.report().single().contains("outcome=VIDEO_WRITING"))
        journal.mark(id, Stage.CONTROL_READY)
        assertTrue(journal.report().single().contains("outcome=VIDEO_WRITING"))
        assertTrue(journal.report().single().contains("receiver_display=unknown"))
    }

    @Test fun registeredAndRepeatedInvitesDoNotClaimRecordOrRestartAttempt() {
        var now = 100L
        val journal = ConnectionDiagnostics { now }
        val id = journal.begin("known-wifi", "bmw activation=true")
        now += 20; journal.mark(id, Stage.CTRL_REGISTERED)
        now += 10; journal.mark(id, Stage.CTRL_INVITED)
        now += 10; journal.mark(id, Stage.CTRL_INVITED)
        val report = journal.report().single()
        assertTrue(report.contains("outcome=WAITING"))
        assertTrue(report.contains("record=false invite=true"))
        assertTrue(report.contains("CTRL_INVITED@30ms"))
        assertEquals(1, Regex("CTRL_INVITED@").findAll(report).count())
    }

    @Test fun staleCallbacksAndDuplicateFinishCannotChangeNewRound() {
        var now = 0L
        val j = ConnectionDiagnostics { now }
        val old = j.begin("manual", "bmw")
        now = 50
        val active = j.begin("retry", "bmw")
        j.mark(old, Stage.RECORD)
        j.finish(old, "FAILED", "late EOF")
        j.mark(active, Stage.RECORD)
        j.finish(active, "STOPPED", "user")
        j.mark(active, Stage.CTRL_INVITED)
        j.finish(active, "FAILED", "duplicate EOF")
        assertTrue(j.report().first().contains("outcome=SUPERSEDED"))
        assertTrue(j.report().last().contains("outcome=STOPPED"))
        assertTrue(j.report().last().contains("record=true invite=false"))
        assertFalse(j.report().last().contains("duplicate EOF"))
    }

    @Test fun recordIsActiveUntilARealStopAndHistoryIsBounded() {
        var now = 1L
        val j = ConnectionDiagnostics { now }
        repeat(12) { now++; j.begin("manual", "bmw") }
        assertEquals(8, j.report().size)
        j.mark(12, Stage.RECORD)
        assertTrue(j.report().last().contains("outcome=ACTIVATING"))
        now += 10
        j.finish(12, "STOPPED", "user\nstop")
        val report = j.report().last()
        now += 1000
        assertEquals(report, j.report().last())
        assertFalse(report.contains('\n'))
    }
}
