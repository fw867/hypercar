package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.session.ConnectionDiagnostics.Stage

class ConnectionDiagnosticsTest {
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
