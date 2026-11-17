package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class PausedSeekUpdateTest {
    private fun subscribed(provider: () -> Iap2NowPlaying, progress: Boolean = true) =
        Iap2NowPlayingSession(provider).also {
            it.authenticated()
            val fields = if (progress) intArrayOf(0,1) else intArrayOf(0)
            it.handle(Iap2Csm.Frame(0x5000,Iap2Csm.group(1,
                *fields.map { id -> Iap2Csm.param(id,byteArrayOf()) }.toTypedArray())))
        }

    private fun updates(session: Iap2NowPlayingSession, now: Long) =
        session.poll(now,2048,false).filterIsInstance<Iap2NowPlayingSession.Output.Control>()

    private fun assertProgress(out: List<Iap2NowPlayingSession.Output.Control>, status: Int, position: Long) {
        assertEquals(1,out.size)
        assertArrayEquals(Iap2Csm.group(1,Iap2Csm.u8(0,status),Iap2Csm.u32(1,position)),out.single().body)
    }

    // Former standalone failure probe: no new subscription, title or playback-state event.
    @Test fun pausedSeekIsReportedWithoutAnotherSubscriptionOrStateChange() {
        var state = Iap2NowPlaying(title="paused",status=2,elapsedMs=1000)
        val session = subscribed({ state })
        updates(session,0)
        state = state.copy(elapsedMs=30000)
        assertProgress(updates(session,6000),2,30000)
    }

    @Test fun pausedSeeksCoalesceAtSampleIntervalAndDoNotRepeatWhenUnchanged() {
        var state = Iap2NowPlaying(status=2,elapsedMs=1000)
        val session = subscribed({ state })
        updates(session,0)
        state = state.copy(elapsedMs=20000)
        assertTrue(updates(session,50).isEmpty())
        state = state.copy(elapsedMs=30000)
        assertTrue(updates(session,99).isEmpty())
        assertProgress(updates(session,100),2,30000)
        assertTrue(updates(session,200).isEmpty())
        state = state.copy(elapsedMs=0) // Seek backwards to the start.
        assertProgress(updates(session,300),2,0)
        assertTrue(updates(session,6000).isEmpty())
    }

    @Test fun playingProgressRetainsFiveSecondCadenceAfterPauseSeekAndResume() {
        var state = Iap2NowPlaying(status=1,elapsedMs=1000)
        val session = subscribed({ state })
        assertProgress(updates(session,0),1,1000)
        state = state.copy(elapsedMs=2000)
        assertTrue(updates(session,100).isEmpty())
        assertTrue(updates(session,4999).isEmpty())
        state = state.copy(elapsedMs=6000)
        assertProgress(updates(session,5100),1,6000)
        state = state.copy(status=2)
        assertProgress(updates(session,5200),2,6000)
        state = state.copy(elapsedMs=30000)
        assertProgress(updates(session,5300),2,30000)
        state = state.copy(status=1)
        assertProgress(updates(session,5400),1,30000)
        state = state.copy(elapsedMs=31000)
        assertTrue(updates(session,5500).isEmpty())
        assertTrue(updates(session,10300).isEmpty())
        state = state.copy(elapsedMs=35000)
        assertProgress(updates(session,10400),1,35000)
    }

    @Test fun unrequestedProgressAndStoppedSubscriptionNeverSendSeekUpdates() {
        var state = Iap2NowPlaying(status=2,elapsedMs=1000)
        val unrequested = subscribed({ state },progress=false)
        val stopped = subscribed({ state })
        updates(unrequested,0); updates(stopped,0)
        stopped.handle(Iap2Csm.Frame(0x5002,byteArrayOf()))
        state = state.copy(elapsedMs=30000)
        assertTrue(updates(unrequested,6000).isEmpty())
        assertTrue(updates(stopped,6000).isEmpty())
    }

    @Test fun identicalClampedWirePositionsDoNotTriggerDuplicateSeekUpdates() {
        var state = Iap2NowPlaying(status=2,elapsedMs=-1)
        val session = subscribed({ state })
        assertProgress(updates(session,0),2,0)
        state = state.copy(elapsedMs=-2)
        assertTrue(updates(session,100).isEmpty())
        state = state.copy(elapsedMs=Long.MAX_VALUE)
        assertProgress(updates(session,200),2,0xffff_ffffL)
        state = state.copy(elapsedMs=Long.MAX_VALUE-1)
        assertTrue(updates(session,300).isEmpty())
    }

    @Test fun stopOrReauthenticationDiscardsOldSeekAndReanchorsProgressCadence() {
        for (reauthenticate in listOf(false,true)) {
            var state = Iap2NowPlaying(status=2,elapsedMs=1000)
            val session = subscribed({ state })
            updates(session,20000)
            state = state.copy(elapsedMs=90000)
            assertProgress(updates(session,21000),2,90000)
            if (reauthenticate) session.reset()
            else session.handle(Iap2Csm.Frame(0x5002,byteArrayOf()))
            state = state.copy(elapsedMs=3000)
            assertTrue(updates(session,30000).isEmpty())
            if (reauthenticate) session.authenticated()
            assertTrue(updates(session,30001).isEmpty()) // Authentication alone is not a subscription.
            session.handle(Iap2Csm.Frame(0x5000,Iap2Csm.group(1,
                Iap2Csm.param(0,byteArrayOf()),Iap2Csm.param(1,byteArrayOf()))))
            assertProgress(updates(session,30002),2,3000)
            state = state.copy(status=1)
            assertProgress(updates(session,30102),1,3000)
            state = state.copy(elapsedMs=4000)
            assertTrue(updates(session,30202).isEmpty())
            assertTrue(updates(session,35002).isEmpty())
            state = state.copy(elapsedMs=8000)
            assertProgress(updates(session,35102),1,8000)
        }
    }

    @Test fun resetWithNoCurrentPlayerReportsEmptySnapshotInsteadOfOldProgress() {
        var state = Iap2NowPlaying(status=2,elapsedMs=90000)
        val session = subscribed({ state })
        updates(session,0)
        session.reset(); state = Iap2NowPlaying(); session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000,Iap2Csm.group(1,
            Iap2Csm.param(0,byteArrayOf()),Iap2Csm.param(1,byteArrayOf()))))
        assertProgress(updates(session,100),0,0)
    }

    @Test fun pausedSeekDoesNotRepeatDefaultsOrChangeEmptyAndRealArtworkTransactions() {
        var state = Iap2NowPlaying(title="real",status=2,elapsedMs=1000,artwork=byteArrayOf(7,8))
        val session = Iap2NowPlayingSession({ state },initializeArtworkWithEmptyFile=true)
        session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000,
            Iap2Csm.group(0,*intArrayOf(0,1,26).map { Iap2Csm.param(it,byteArrayOf()) }.toTypedArray()) +
                Iap2Csm.group(1,*intArrayOf(0,1,5).map { Iap2Csm.param(it,byteArrayOf()) }.toTypedArray())))
        fun files(out: List<Iap2NowPlayingSession.Output>) =
            out.filterIsInstance<Iap2NowPlayingSession.Output.File>().map { it.bytes }
        fun fields(out: List<Iap2NowPlayingSession.Output>) = Iap2Csm.parseParams(
            out.filterIsInstance<Iap2NowPlayingSession.Output.Control>().single().body).flatMap { group ->
            Iap2Csm.parseParams(group.value).map { (group.id to it.id) to it }
        }.toMap()
        val first = session.poll(0,2048,true)
        assertEquals(Iap2Csm.Param(0,ByteArray(8)),fields(first)[0 to 0])
        assertEquals(Iap2Csm.Param(5,byteArrayOf(0)),fields(first)[1 to 5])
        assertEquals("real",fields(first).getValue(0 to 1).asStringOrNull())
        assertArrayEquals(byteArrayOf(0x80.toByte(),4,0,0,0,0,0,0,0,0,0,2),files(first).single())
        state = state.copy(elapsedMs=9000)
        val seek = session.poll(100,2048,true)
        assertTrue(files(seek).isEmpty())
        assertFalse(fields(seek).containsKey(0 to 0))
        assertFalse(fields(seek).containsKey(1 to 5))
        assertArrayEquals(byteArrayOf(0,0,0x23,0x28),fields(seek).getValue(1 to 1).value)
        session.fileResponse(byteArrayOf(0x80.toByte(),1),101)
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(session.poll(102,2048,true)).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),5),103)
        val real = session.poll(200,2048,true)
        assertFalse(fields(real).containsKey(1 to 5))
        assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,2,0,2),files(real).single())
        session.fileResponse(byteArrayOf(0x81.toByte(),1),201)
        assertArrayEquals(byteArrayOf(0x81.toByte(),0xc0.toByte(),7,8),files(session.poll(202,2048,true)).single())
        session.fileResponse(byteArrayOf(0x81.toByte(),5),203)
        state = state.copy(status=1)
        assertEquals(1,fields(session.poll(300,2048,true)).getValue(1 to 0).value.single().toInt())
        state = state.copy(elapsedMs=10000)
        assertTrue(session.poll(400,2048,true).isEmpty())
        assertTrue(session.poll(5200,2048,true).isEmpty())
        state = state.copy(elapsedMs=14000)
        assertArrayEquals(byteArrayOf(0,0,0x36,0xb0.toByte()),fields(session.poll(5300,2048,true)).getValue(1 to 1).value)
        assertFalse(session.hasFileWork)
    }
}
