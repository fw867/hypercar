package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2NowPlayingTest {
    private fun frame(id: Int) = Iap2Csm.Frame(id, byteArrayOf())
    private fun controls(output: List<Iap2NowPlayingSession.Output>) = output.filterIsInstance<Iap2NowPlayingSession.Output.Control>()
    private fun files(output: List<Iap2NowPlayingSession.Output>) = output.filterIsInstance<Iap2NowPlayingSession.Output.File>().map { it.bytes }
    private fun artId(body: ByteArray): Int? = Iap2Csm.parseParams(Iap2Csm.parseParams(body).first { it.id == 0 }.value)
        .firstOrNull { it.id == 26 }?.value?.single()?.toInt()?.and(255)
    private fun subscribed(provider: () -> Iap2NowPlaying): Iap2NowPlayingSession =
        Iap2NowPlayingSession(provider).also { it.authenticated(); it.handle(frame(0x5000)) }

    @Test fun wireFieldsUseAlbumSixAndSingleByteArtworkIdentifier() {
        val body = Iap2NowPlaying("歌曲", "歌手", "专辑", "播放器", 120_000, 4_000, 1).body(7)
        val groups = Iap2Csm.parseParams(body)
        val item = Iap2Csm.parseParams(groups.first { it.id == 0 }.value).associateBy { it.id }
        assertEquals("专辑", item.getValue(6).asStringOrNull())
        assertEquals("歌曲", item.getValue(1).asStringOrNull())
        assertEquals("歌手", item.getValue(12).asStringOrNull())
        assertArrayEquals(byteArrayOf(7), item.getValue(26).value)
        assertArrayEquals(byteArrayOf(0,1,0xd4.toByte(),0xc0.toByte()), item.getValue(4).value)
        val encoded = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, body)
        val decoded = Iap2Csm.Parser().offer(encoded).single()
        assertEquals(0x5001, decoded.messageId)
        assertEquals(listOf(0,1), Iap2Csm.parseParams(decoded.body).map { it.id })
        assertEquals(0x5002, Iap2MessageIds.STOP_NOW_PLAYING_UPDATES)
    }
    @Test fun stringsAndTimesAreBoundedAndNulCannotTruncateFollowingFields() {
        val body = Iap2NowPlaying("a\u0000b" + "字".repeat(40000), durationMs=-1, elapsedMs=Long.MAX_VALUE).body(null)
        assertTrue(body.size < 4000)
        val group = Iap2Csm.parseParams(body)
        val item = Iap2Csm.parseParams(group.first().value)
        assertTrue(item.first { it.id==1 }.asStringOrNull()!!.startsWith("ab"))
        assertArrayEquals(byteArrayOf(0,0,0,0), item.first { it.id==4 }.value)
        assertNull(artId(body))
        val playback = Iap2Csm.parseParams(group.last().value)
        assertArrayEquals(byteArrayOf(-1,-1,-1,-1), playback.first { it.id==1 }.value)
    }
    @Test fun earlySubscribeWaitsForAuthenticationAndEarlyStopCancelsIt() {
        val session = Iap2NowPlayingSession()
        assertTrue(session.handle(frame(0x5000)))
        assertTrue(session.poll(0, 2048, true).isEmpty())
        session.handle(frame(0x5002));session.authenticated()
        assertTrue(session.poll(1, 2048, true).isEmpty())
        session.handle(frame(0x5000))
        assertEquals(1, controls(session.poll(2, 2048, true)).size)
        assertFalse(session.handle(frame(0x5001))) // An incoming update is never a Stop.
    }
    @Test fun lateMetadataChangesAndPlaybackProgressUpdateOnlyAfterSubscription() {
        var state = Iap2NowPlaying()
        val session = subscribed { state }
        assertEquals(1,controls(session.poll(0, 2048, false)).size)
        assertTrue(session.poll(1500,2048,false).isEmpty())
        state = Iap2NowPlaying(title="new",status=1)
        assertEquals(1,controls(session.poll(3000,2048,false)).size)
        state = state.copy(elapsedMs=1500)
        assertTrue(session.poll(4500,2048,false).isEmpty())
        assertEquals(1,controls(session.poll(9000,2048,false)).size)
        session.handle(frame(0x5002));state=state.copy(title="later")
        assertTrue(session.poll(12000,2048,false).isEmpty())
    }
    @Test fun artIsDeclaredOnlyWithUsableBytesAndNegotiatedFileSession() {
        for (data in listOf(null, byteArrayOf(), ByteArray(Iap2NowPlayingSession.MAX_ARTWORK+1))) {
            val session=subscribed { Iap2NowPlaying(artwork=data) }
            val output=session.poll(0,2048,true)
            assertNull(artId(controls(output).single().body));assertTrue(files(output).isEmpty())
        }
        val unsupported=subscribed { Iap2NowPlaying(artwork=byteArrayOf(1)) }
        assertNull(artId(controls(unsupported.poll(0,2048,false)).single().body))
        val tooSmall=subscribed { Iap2NowPlaying(artwork=byteArrayOf(1)) }
        assertNull(artId(controls(tooSmall.poll(0,11,true)).single().body))
    }
    @Test fun artworkWaitsForStartUsesNegotiatedChunksAndWaitsForSuccess() {
        val data=ByteArray(7000) { it.toByte() }
        val session=subscribed { Iap2NowPlaying(artwork=data) }
        val setup=session.poll(0,512,true)
        val id=artId(controls(setup).single().body)!!
        assertTrue(setup.first() is Iap2NowPlayingSession.Output.Control)
        assertArrayEquals(byteArrayOf(id.toByte(),4,0,0,0,0,0,0,0x1b,0x58,0,2),files(setup).single())
        assertTrue(files(session.poll(1,512,true)).isEmpty())
        session.fileResponse(byteArrayOf((id+1).toByte(),1),2)
        assertTrue(files(session.poll(2,512,true)).isEmpty())
        session.fileResponse(byteArrayOf(id.toByte(),1),3)
        val chunks=mutableListOf<ByteArray>()
        for(t in 4L..20L) chunks+=files(session.poll(t,512,true))
        assertTrue(chunks.all { it.size<=512 })
        assertEquals(0x80,chunks.first()[1].toInt() and 255)
        assertEquals(0x40,chunks.last()[1].toInt() and 255)
        assertArrayEquals(data,chunks.fold(byteArrayOf()) { a,b->a+b.copyOfRange(2,b.size) })
        assertTrue(session.hasFileWork)
        session.fileResponse(byteArrayOf(id.toByte(),5),21)
        assertFalse(session.hasFileWork)
    }
    @Test fun singleChunkCoverIsC0AndRepeatedStartDoesNotReplayData() {
        val data=byteArrayOf(1,2,3)
        val session=subscribed { Iap2NowPlaying(artwork=data) }
        val id=artId(controls(session.poll(0,2048,true)).single().body)!!
        session.fileResponse(byteArrayOf(id.toByte(),1),1)
        assertArrayEquals(byteArrayOf(id.toByte(),0xc0.toByte(),1,2,3),files(session.poll(2,2048,true)).single())
        session.fileResponse(byteArrayOf(id.toByte(),1),3)
        assertTrue(files(session.poll(4,2048,true)).isEmpty())
    }
    @Test fun trackChangeCancelsOldCoverAndLateOldResponsesAreIgnored() {
        var state=Iap2NowPlaying(title="first",artwork=byteArrayOf(1))
        val session=subscribed { state }
        val first=artId(controls(session.poll(0,2048,true)).single().body)!!
        state=state.copy(title="next",artwork=byteArrayOf(2))
        val next=session.poll(1500,2048,true)
        val second=artId(controls(next).single().body)!!
        assertNotEquals(first,second)
        assertArrayEquals(byteArrayOf(first.toByte(),2),files(next).first())
        session.fileResponse(byteArrayOf(first.toByte(),1),1501)
        assertTrue(files(session.poll(1502,2048,true)).isEmpty())
        session.fileResponse(byteArrayOf(second.toByte(),1),1503)
        assertEquals(2,files(session.poll(1504,2048,true)).single().last().toInt())
    }
    @Test fun timeoutRemovesArtworkPromiseAndDoesNotRetryForever() {
        val data=byteArrayOf(1)
        val session=subscribed { Iap2NowPlaying(artwork=data) }
        session.poll(0,2048,true)
        val timeout=session.poll(5000,2048,true)
        assertNull(artId(controls(timeout).single().body))
        assertEquals(2,files(timeout).single()[1].toInt())
        assertFalse(session.hasFileWork)
        assertTrue(files(session.poll(10000,2048,true)).isEmpty())
    }
    @Test fun stopAndResubscribeUseFreshTransferAndResetRequiresNewAuthAndSubscription() {
        val data=byteArrayOf(1)
        val session=subscribed { Iap2NowPlaying(artwork=data) }
        val first=artId(controls(session.poll(0,2048,true)).single().body)!!
        session.handle(frame(0x5002));session.poll(1,2048,true)
        session.handle(frame(0x5000))
        val second=artId(controls(session.poll(2,2048,true)).single().body)!!
        assertNotEquals(first,second)
        session.reset();session.handle(frame(0x5000))
        assertTrue(session.poll(3,2048,true).isEmpty())
        session.authenticated()
        val third=artId(controls(session.poll(4,2048,true)).single().body)!!
        assertNotEquals(second,third)
    }
    @Test fun exhaustedIdentifiersDoNotReuseCachedCarArtwork() {
        var state=Iap2NowPlaying(artwork=byteArrayOf(1))
        val session=subscribed { state }
        for (id in 1..255) {
            state=state.copy(artwork=byteArrayOf(id.toByte()))
            assertEquals(id,artId(controls(session.poll(id*1500L,2048,true)).single().body))
        }
        state=state.copy(artwork=byteArrayOf(0))
        val output=session.poll(256*1500L,2048,true)
        assertNull(artId(controls(output).single().body))
        assertTrue(files(output).none { it[1].toInt()==4 })
    }
    @Test fun providerFailureIsRetriedWithoutResettingTheAuthenticatedLink() {
        var broken=true
        val session=subscribed { if(broken) error("fixture") else Iap2NowPlaying(title="recovered") }
        assertTrue(session.poll(0,2048,false).isEmpty())
        broken=false
        assertEquals(1,controls(session.poll(1500,2048,false)).size)
    }
    @Test fun rejectionClearsArtworkPromiseAndLeavesTextSubscriptionAlive() {
        val data=byteArrayOf(1)
        val session=subscribed { Iap2NowPlaying(title="keep",artwork=data) }
        val id=artId(controls(session.poll(0,2048,true)).single().body)!!
        session.fileResponse(byteArrayOf(id.toByte(),6),1)
        val output=session.poll(2,2048,true)
        assertNull(artId(controls(output).single().body))
        assertTrue(files(output).isEmpty())
        assertFalse(session.hasFileWork)
    }
    @Test fun bluetoothResyncDoesNotAuthenticateAnUnauthenticatedSession() {
        val session=Iap2NowPlayingSession()
        session.restartLink();session.handle(frame(0x5000))
        assertTrue(session.poll(0,2048,false).isEmpty())
        session.authenticated()
        assertEquals(1,controls(session.poll(1,2048,false)).size)
        session.restartLink()
        assertTrue(session.poll(2,2048,false).isEmpty())
        session.handle(frame(0x5000))
        assertEquals(1,controls(session.poll(3,2048,false)).size)
    }

}
