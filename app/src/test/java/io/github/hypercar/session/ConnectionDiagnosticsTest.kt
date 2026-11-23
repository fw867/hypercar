package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test
import io.github.hypercar.session.ConnectionDiagnostics.Stage

class ConnectionDiagnosticsTest {
    @Test fun mediaSummaryKeepsFirstFrameTouchAndFeedbackFailuresWhenRecentEntriesRollOver() {
        val journal = ConnectionDiagnostics { 1_000L }
        val first = journal.begin("manual", "bmw_id9")
        journal.engineStarted(first, 10, null, 1_000, 5_000)
        fun event(at: Int, detail: String, engine: Long = 10) =
            journal.mediaEvent(first, engine, "MEDIA-TIMELINE: $at $detail")
        event(1_002, "hid_touch_in RTSP#3 bytes=6 mapped=false")
        event(1_001, "first_video_written socket_write")
        event(1_003, "feedback_timeout RTSP#2 CSeq=20")
        event(1_004, "feedback_error RTSP#2 CSeq=21 control_closed")
        event(1_005, "feedback_error WRONG_ENGINE", 11)
        for (n in 1..20) event(1_010 + n, "feedback_ok RTSP#2 CSeq=${21 + n} status=200")
        val line = journal.report().single()
        assertTrue(line.indexOf("1001ms first_video_written") in 0 until line.indexOf("1002ms hid_touch_in"))
        assertTrue(line.contains("1003ms feedback_timeout RTSP#2 CSeq=20"))
        assertTrue(line.contains("1004ms feedback_error RTSP#2 CSeq=21"))
        assertTrue(line.contains("feedback_ok RTSP#2 CSeq=41"))
        assertFalse(line.contains("feedback_ok RTSP#2 CSeq=23"))
        assertFalse(line.contains("WRONG_ENGINE"))
        val next = journal.begin("manual", "other")
        journal.engineStarted(next, 11, null, 2_000, 6_000)
        assertFalse(journal.report().last().contains("media_timeline"))
    }

    @Test fun developerSnapshotKeepsLatestObservationsAndDoesNotBorrowFromOtherAttempts() {
        var now = 100L
        val journal = ConnectionDiagnostics { now }
        val first = journal.begin("manual", "bmw_id85")
        val mdns = ConnectionDiagnostics.Observation.MDNS_LOCAL_ANNOUNCEMENT
        val marker = ConnectionDiagnostics.Observation.CARD_UNLOCKED
        now = 110; assertTrue(journal.observe(first, mdns, "IPv4 port=12345"))
        now = 120; assertTrue(journal.observe(first, marker))
        now = 130; journal.observe(first, mdns, "IPv6 port=12345")
        journal.mark(first, Stage.CTRL_REGISTERED, "12345")
        journal.mark(first, Stage.CTRL_TCP_ACCEPTED)
        journal.finish(first, "FAILED", "timeout")
        assertFalse(journal.observe(first, marker))
        val ended = journal.connectionSnapshot(first, null)!!
        assertTrue(ended.contains("最近已结束"))
        assertTrue(ended.contains("最近一段 ctrl 监听的注册端口：12345"))
        assertTrue(ended.contains("已刷卡：+20 ms"))
        assertTrue(ended.contains("+30 ms（IPv6 port=12345）"))
        assertTrue(ended.contains("App 接收 ctrl TCP：+30 ms"))
        assertTrue(ended.contains("收到有效 HTTP 邀请：未记录"))
        assertFalse(ended.contains("IPv4"))
        val next = journal.begin("retry", "bmw_id85")
        assertFalse(journal.observe(first, mdns))
        assertNull(journal.connectionSnapshot(first, null))
        val live = journal.connectionSnapshot(next, 54321)!!
        assertTrue(live.contains("当前 ctrl 端口：54321"))
        assertTrue(live.contains("已刷卡：未记录"))
        assertFalse(live.contains("12345"))
    }

    @Test fun aclTriggerKeepsPreAttemptTimeAndDelayedForwardCannotOverwriteNewerEdge() {
        var now = 100L
        val journal = ConnectionDiagnostics { now }
        val up = ConnectionDiagnostics.Observation.ACL_UP
        val down = ConnectionDiagnostics.Observation.ACL_DOWN
        val attempt = journal.begin("acl", "bmw_id85")
        journal.observe(attempt, up, at = 80L)
        assertTrue(journal.connectionSnapshot(attempt, null)!!.contains("车机蓝牙 ACL 连上：-20 ms"))
        now = 140L
        journal.observe(attempt, down)
        now = 150L
        journal.observe(attempt, up)
        journal.observe(attempt, up, at = 80L)
        val snapshot = journal.connectionSnapshot(attempt, null)!!
        assertTrue(snapshot.contains("车机蓝牙 ACL 断开：+40 ms"))
        assertTrue(snapshot.contains("车机蓝牙 ACL 连上：+50 ms"))
    }

    @Test fun newCtrlListenerDoesNotBorrowOldNetworkEvidence() {
        var now = 100L
        val journal = ConnectionDiagnostics { now }
        val attempt = journal.begin("manual", "bmw_id85")
        val mdns = ConnectionDiagnostics.Observation.MDNS_LOCAL_ANNOUNCEMENT
        now = 110
        journal.observe(attempt, ConnectionDiagnostics.Observation.CARD_UNLOCKED)
        journal.observe(attempt, mdns, "IPv4 port=12345")
        journal.mark(attempt, Stage.CTRL_REGISTERED, "12345")
        journal.mark(attempt, Stage.CTRL_INVITED)
        now = 130
        journal.newCtrlEpoch(attempt)
        journal.mark(attempt, Stage.CTRL_REGISTERED, "54321")
        val switched = journal.connectionSnapshot(attempt, 54321)!!
        assertFalse(switched.contains("12345"))
        assertTrue(switched.contains("收到有效 HTTP 邀请：未记录"))
        assertTrue(switched.contains("已刷卡：+10 ms"))
        assertTrue(journal.report().single().contains("invite=true"))
        now = 140
        journal.mark(attempt, Stage.CTRL_INVITED)
        assertTrue(journal.connectionSnapshot(attempt, 54321)!!.contains("收到有效 HTTP 邀请：+40 ms"))
    }

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

    @Test fun tcpHeadAndAckDoNotImplyInvitationAndStaleCallbacksDoNotFillNewRound() {
        var now = 100L
        val journal = ConnectionDiagnostics { now }
        val old = journal.begin("known-wifi", "bmw_id85", 90)
        now += 5; journal.rfcommAccepted(old, "AA", 91)
        now += 5; journal.mark(old, Stage.CTRL_TCP_ACCEPTED)
        now += 5; journal.mark(old, Stage.CTRL_INVITE_HEAD)
        now += 5; journal.mark(old, Stage.CTRL_INVITE_ACK)
        val active = journal.begin("retry", "bmw_id85", 112)
        journal.mark(old, Stage.CTRL_INVITED)
        journal.rfcommAccepted(active, "AA", 91)
        val (first, second) = journal.report()
        assertTrue(first, first.contains("BT_INBOUND_ADMITTED@5ms,CTRL_TCP_ACCEPTED@10ms,CTRL_INVITE_HEAD@15ms,CTRL_INVITE_ACK@20ms"))
        assertTrue(first.contains("invite=false"))
        assertTrue(second.contains("milestones=[REQUESTED@0ms]"))
    }

    @Test fun waitingForCtrlTcpTracksOnlyTheCurrentListenerAndAttempt() {
        val journal = ConnectionDiagnostics { 0L }
        val id = journal.begin("manual", "bmw_id85")
        assertFalse(journal.isWaitingForCtrlTcp(id))

        journal.mark(id, Stage.CTRL_REGISTERED, "36037")
        assertTrue(journal.isWaitingForCtrlTcp(id))
        journal.mark(id, Stage.CTRL_TCP_ACCEPTED)
        assertFalse(journal.isWaitingForCtrlTcp(id))

        journal.newCtrlEpoch(id)
        assertFalse(journal.isWaitingForCtrlTcp(id))
        journal.mark(id, Stage.CTRL_REGISTERED, "38919")
        assertTrue(journal.isWaitingForCtrlTcp(id))
        journal.finish(id, "STOPPED", "user")
        assertFalse(journal.isWaitingForCtrlTcp(id))
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
