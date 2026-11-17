package io.github.hypercar.iap2

import io.github.hypercar.carui.MediaPlaybackDetails
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class FirstNowPlayingDefaultsTest {
    // iPhone AirPlay recovery capture, first-unified.jsonl L1784196: complete 214-byte CSM.
    // Compare fields, not order: the box emits artwork 26 before chapter count 27.
    private val iphone = Base64.getDecoder().decode(
        "QEAA1lABAGgAAAAMAAAAAAAAAAAAAAAFAAEAAAgABAAAAAAABQAGAAAGAAcAAAAGAAgAAAAGAAkAAAAGAAoAAAAFAAwAAAUAEAAABQASAAAFABUAAAUAFgAABQAXAAAFABgAAAYAGwAAAAUAGoAAaAABAAUAAAAACAABAAAAAAAIAAIAAAAAAAgAAwAAAAAACAAEAAAAAAAFAAUAAAUABgAABQAHAAAFAAgAAAUACQAABQAKAAAMAAsAAAAAAAAAAAAGAAwAAAAFAA0AAAUADgAABQAQAA=="
    ).copyOfRange(6, 214)

    private fun fields(body: ByteArray): Map<Pair<Int, Int>, Iap2Csm.Param> =
        Iap2Csm.parseParams(body).flatMap { group ->
            Iap2Csm.parseParams(group.value).map { (group.id to it.id) to it }
        }.toMap()

    private fun request(group: Int, vararg ids: Int) =
        Iap2Csm.group(group, *ids.map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray())

    private fun fullRequest() = request(0, 0,1,4,6,7,8,9,10,12,16,18,21,22,23,24,26,27) +
        request(1, 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,16) + request(2, 0,1,6,12,14,16,18)

    private fun start(session: Iap2NowPlayingSession, body: ByteArray = fullRequest()) {
        session.handle(Iap2Csm.Frame(0x5000, body))
    }

    private fun controls(out: List<Iap2NowPlayingSession.Output>) =
        out.filterIsInstance<Iap2NowPlayingSession.Output.Control>()

    private fun firstBody(session: Iap2NowPlayingSession, now: Long = 0) =
        controls(session.poll(now, 2048, true)).single().body

    @Test fun firstEmptySnapshotMatchesCapturedDefaultsAndLeavesEmptyFileHandshakeIntact() {
        val session = Iap2NowPlayingSession(initializeArtworkWithEmptyFile = true)
        session.authenticated(); start(session)
        val out = session.poll(0, 2048, true)
        val body = controls(out).single().body
        assertEquals(fields(iphone), fields(body))
        assertEquals(214, Iap2Csm.encode(0x5001, body).size)
        assertTrue(out.first() is Iap2NowPlayingSession.Output.Control)
        assertArrayEquals(byteArrayOf(0x80.toByte(),4,0,0,0,0,0,0,0,0,0,2),
            out.filterIsInstance<Iap2NowPlayingSession.Output.File>().single().bytes)
        assertTrue(session.poll(100, 2048, true).isEmpty())
        session.fileResponse(byteArrayOf(0x80.toByte(),1), 101)
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),
            session.poll(102,2048,true).filterIsInstance<Iap2NowPlayingSession.Output.File>().single().bytes)
    }

    @Test fun firstSnapshotPreservesEveryAvailableRealField() {
        val state = Iap2NowPlaying(title="Song", artist="Artist", album="Album", appName="Player",
            durationMs=123456, elapsedMs=2345, status=1, appBundleId="org.example.player",
            details=MediaPlaybackDetails(itemId=0x0102030405060708L, trackNumber=4, trackCount=15,
                discNumber=2, genre="Jazz", composer="Composer", liked=true, queueIndex=3,
                queueCount=8, speed=1.25f))
        val session = Iap2NowPlayingSession({ state }, initializeArtworkWithEmptyFile=true)
        session.authenticated(); start(session)
        val actual = fields(firstBody(session))
        val real = fields(state.body(0x80, explicitPlayback=true))
        assertEquals(fields(iphone).keys, actual.keys)
        real.forEach { (key, value) -> assertEquals("real field $key", value, actual[key]) }
        fields(iphone).filterKeys { it !in real }.forEach { (key, value) -> assertEquals(value, actual[key]) }
    }

    @Test fun laterUpdatesKeepExistingOmissionRulesWithoutAnExtraDefaultRemovalUpdate() {
        var state = Iap2NowPlaying()
        val session = Iap2NowPlayingSession({ state })
        session.authenticated(); start(session)
        assertTrue(fields(firstBody(session)).containsKey(0 to 0))
        assertTrue(session.poll(100,2048,true).isEmpty())
        state = state.copy(title="new", status=1)
        assertArrayEquals(state.body(null, explicitPlayback=true), firstBody(session,200))
        assertTrue(session.poll(300,2048,true).isEmpty())
    }

    @Test fun repeatsAndStopDoNotReinitializeButNewAuthenticationAndNewSessionDo() {
        val session = Iap2NowPlayingSession()
        session.authenticated(); start(session)
        assertTrue(fields(firstBody(session)).containsKey(1 to 5))
        start(session)
        assertFalse(fields(firstBody(session,1)).containsKey(1 to 5))
        session.handle(Iap2Csm.Frame(0x5002, byteArrayOf()))
        start(session)
        assertFalse(fields(firstBody(session,2)).containsKey(1 to 5))
        session.reset(); session.authenticated(); start(session)
        assertTrue(fields(firstBody(session,3)).containsKey(1 to 5))
        val replacement = Iap2NowPlayingSession()
        replacement.authenticated(); start(replacement)
        assertTrue(fields(firstBody(replacement)).containsKey(1 to 5))
    }

    @Test fun onlyRequestedKnownDefaultsAreAddedWithoutInventingQueueTransfersOrArtwork() {
        val session = Iap2NowPlayingSession()
        session.authenticated()
        start(session, request(0,10,24,27,99) + request(1,5,13,14,17,99) + request(2,0))
        val actual = fields(firstBody(session))
        val keys = setOf(0 to 10, 0 to 24, 0 to 27, 1 to 5, 1 to 13, 1 to 14)
        assertEquals(fields(iphone).filterKeys { it in keys }, actual)
    }

    @Test fun legacyEmptySubscriptionRemainsUnchanged() {
        val state = Iap2NowPlaying(title="legacy", status=1)
        val session = Iap2NowPlayingSession({ state })
        session.authenticated(); start(session, byteArrayOf())
        assertArrayEquals(state.body(null), firstBody(session))
    }

    @Test fun missingAuthenticationOrSnapshotDoesNotConsumeFirstResponse() {
        var fail = true
        val session = Iap2NowPlayingSession({ if (fail) error("not ready") else Iap2NowPlaying() })
        start(session)
        assertTrue(session.poll(0,2048,true).isEmpty())
        session.authenticated()
        assertTrue(session.poll(1,2048,true).isEmpty())
        fail = false
        assertTrue(fields(firstBody(session,2)).containsKey(0 to 0))
    }

    @Test fun unsupportedOnlyRequestDoesNotConsumeFirstActualResponse() {
        val session = Iap2NowPlayingSession()
        session.authenticated(); start(session, request(2,0) + request(1,17,99))
        assertTrue(session.poll(0,2048,true).isEmpty())
        start(session, request(0,10))
        assertArrayEquals(Iap2Csm.group(0,Iap2Csm.u16(10,0)), firstBody(session,1))
    }

    @Test fun lateRealValuesIncludingZeroAndFalseAreSentDuringEmptyArtworkWait() {
        var state = Iap2NowPlaying()
        val session = Iap2NowPlayingSession({ state }, initializeArtworkWithEmptyFile=true)
        session.authenticated(); start(session)
        assertEquals(fields(iphone),fields(firstBody(session)))
        // A real zero/false is still an available value, even when the initial default was identical.
        state = Iap2NowPlaying(title="late",status=1,details=MediaPlaybackDetails(
            itemId=0,trackNumber=0,trackCount=0,discNumber=0,genre="Jazz",composer="A",
            liked=false,queueIndex=0,queueCount=0,speed=0f))
        assertTrue(session.poll(99,2048,true).isEmpty())
        val zero = session.poll(100,2048,true)
        assertArrayEquals(state.body(0x80,explicitPlayback=true),controls(zero).single().body)
        assertTrue(zero.none { it is Iap2NowPlayingSession.Output.File })
        state = state.copy(title="latest",details=state.details.copy(itemId=42,trackNumber=3,
            trackCount=12,discNumber=2,liked=true,queueIndex=2,queueCount=12,speed=1.25f))
        val latest = session.poll(200,2048,true)
        assertArrayEquals(state.body(0x80,explicitPlayback=true),controls(latest).single().body)
        assertTrue(session.hasFileWork) // Metadata is live before the peer starts the empty file.
        assertTrue(session.poll(300,2048,true).isEmpty())
    }

    @Test fun elapsedOnlyUpdatesKeepExistingCadenceAndCurrentRealValues() {
        var state = Iap2NowPlaying(title="playing",status=1,elapsedMs=1000)
        val session = Iap2NowPlayingSession({ state })
        session.authenticated(); start(session)
        firstBody(session)
        state = state.copy(elapsedMs=2000)
        assertTrue(session.poll(100,2048,true).isEmpty())
        state = state.copy(elapsedMs=6000)
        assertArrayEquals(state.body(null,explicitPlayback=true),firstBody(session,5000))
        state = state.copy(status=2,elapsedMs=6100)
        assertArrayEquals(state.body(null,explicitPlayback=true),firstBody(session,5100))
    }
}
