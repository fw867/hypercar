package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2NowPlayingTest {
    @Test fun bothEmptyHandshakeTimeoutsReleaseRealCoverAndIgnoreLateOldReplies() {
        for (started in listOf(false,true)) {
            var state=Iap2NowPlaying(artwork=byteArrayOf(7,8))
            val session=initializing { state }
            session.poll(0,2048,true)
            if (started) {
                session.fileResponse(byteArrayOf(0x80.toByte(),1),1)
                assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(session.poll(2,2048,true)).single())
            }
            val expired=session.poll(5002,2048,true)
            assertArrayEquals(byteArrayOf(0x80.toByte(),2),files(expired).first())
            assertEquals(0x81,artId(controls(expired).single().body))
            for (command in listOf(1,5)) session.fileResponse(byteArrayOf(0x80.toByte(),command.toByte()),5003)
            assertTrue(session.hasFileWork)
            assertTrue(session.poll(5004,2048,true).isEmpty())
            session.fileResponse(byteArrayOf(0x81.toByte(),1),5005)
            assertArrayEquals(byteArrayOf(0x81.toByte(),0xc0.toByte(),7,8),files(session.poll(5006,2048,true)).single())
            session.fileResponse(byteArrayOf(0x81.toByte(),5),5007)
            assertFalse(session.hasFileWork)
            state=state.copy(artwork=byteArrayOf(9))
            assertEquals(0x82,artId(controls(session.poll(5200,2048,true)).single().body))
        }
    }

    @Test fun stopBeforeOrAfterEmptyStartDoesNotRestartInitializationWithinSameAuthentication() {
        for (started in listOf(false,true)) {
            val session=initializing { Iap2NowPlaying(artwork=byteArrayOf(4)) }
            session.poll(0,2048,true)
            if (started) {
                session.fileResponse(byteArrayOf(0x80.toByte(),1),1)
                session.poll(2,2048,true)
            }
            session.handle(frame(0x5002))
            assertArrayEquals(byteArrayOf(0x80.toByte(),2),files(session.poll(3,2048,true)).single())
            for (command in listOf(1,5)) session.fileResponse(byteArrayOf(0x80.toByte(),command.toByte()),4)
            assertTrue(session.poll(6000,2048,true).isEmpty())
            session.handle(frame(0x5000))
            val out=session.poll(6001,2048,true)
            assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,1,0,2),files(out).single())
        }
    }

    @Test fun reauthenticationCreatesOneNewEmptyTransactionWithDistinctId() {
        val session=initializing()
        session.poll(0,2048,true)
        session.reset()
        for (command in listOf(1,5)) session.fileResponse(byteArrayOf(0x80.toByte(),command.toByte()),1)
        assertTrue(session.poll(2,2048,true).isEmpty())
        session.authenticated()
        assertTrue(session.poll(3,2048,true).isEmpty()) // A fresh subscription is still required.
        session.handle(frame(0x5000))
        assertEquals(0x81,artId(controls(session.poll(4,2048,true)).single().body))
        for (command in listOf(1,5)) session.fileResponse(byteArrayOf(0x80.toByte(),command.toByte()),5)
        assertTrue(session.poll(6,2048,true).isEmpty())
        assertTrue(session.hasFileWork)
        session.handle(frame(0x5000))
        assertTrue(files(session.poll(7,2048,true)).isEmpty())
        session.fileResponse(byteArrayOf(0x81.toByte(),1),8)
        assertArrayEquals(byteArrayOf(0x81.toByte(),0xc0.toByte()),files(session.poll(9,2048,true)).single())
    }

    private fun initializing(provider: () -> Iap2NowPlaying = { Iap2NowPlaying() }) =
        Iap2NowPlayingSession(provider, initializeArtworkWithEmptyFile = true).also {
            it.authenticated(); it.handle(frame(0x5000))
        }

    @Test fun emptyInitializationKeepsMetadataLiveAndThenTransfersLatestRealArtwork() {
        var state = Iap2NowPlaying(title="first", status=1, artwork=byteArrayOf(1))
        val session = initializing { state }
        val first = session.poll(0,2048,true)
        assertTrue(first.first() is Iap2NowPlayingSession.Output.Control)
        assertArrayEquals(state.body(0x80), controls(first).single().body) // Other fields unchanged.
        assertArrayEquals(byteArrayOf(0x80.toByte(),4,0,0,0,0,0,0,0,0,0,2),files(first).single())
        state = state.copy(title="latest", artwork=byteArrayOf(2,3))
        val changed = session.poll(100,2048,true)
        assertArrayEquals(state.body(0x80), controls(changed).single().body)
        assertTrue(files(changed).isEmpty())
        session.handle(frame(0x5000)) // Repeated subscription cannot restart the empty transaction.
        assertTrue(files(session.poll(101,2048,true)).isEmpty())
        session.fileResponse(byteArrayOf(0x81.toByte(),1),102)
        assertTrue(files(session.poll(103,2048,true)).isEmpty())
        session.fileResponse(byteArrayOf(0x80.toByte(),1),104)
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(session.poll(105,2048,true)).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),1),106)
        assertTrue(files(session.poll(107,2048,true)).isEmpty())
        session.fileResponse(byteArrayOf(0x80.toByte(),5),108)
        val real = session.poll(201,2048,true)
        assertEquals(0x81,artId(controls(real).single().body))
        assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,2,0,2),files(real).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),5),202)
        assertTrue(session.hasFileWork)
        session.fileResponse(byteArrayOf(0x81.toByte(),1),203)
        assertArrayEquals(byteArrayOf(0x81.toByte(),0xc0.toByte(),2,3),files(session.poll(204,2048,true)).single())
    }

    @Test fun emptyInitializationCompletesWithoutAnImageAndDoesNotRepeat() {
        var state = Iap2NowPlaying()
        val session = initializing { state }
        session.poll(0,2048,true)
        session.fileResponse(byteArrayOf(0x80.toByte(),1),1)
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()), files(session.poll(2,2048,true)).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),5),3)
        assertFalse(session.hasFileWork)
        assertTrue(session.poll(6000,2048,true).isEmpty())
        state = state.copy(artwork=byteArrayOf(7))
        val real = session.poll(6100,2048,true)
        assertEquals(0x81,artId(controls(real).single().body))
        assertEquals(1,files(real).single()[9].toInt())
    }

    @Test fun emptyInitializationRequiresAuthenticationArtworkSubscriptionAndFileCapacity() {
        val session = Iap2NowPlayingSession(initializeArtworkWithEmptyFile=true)
        session.handle(frame(0x5000))
        assertTrue(session.poll(0,2048,true).isEmpty())
        session.authenticated()
        for ((time,capacity,supported) in listOf(Triple(1L,2048,false),Triple(101L,11,true))) {
            val out=session.poll(time,capacity,supported)
            assertTrue(files(out).isEmpty())
            controls(out).forEach { assertNull(artId(it.body)) }
        }
        session.handle(Iap2Csm.Frame(0x5000,Iap2Csm.group(0,Iap2Csm.param(1,byteArrayOf()))))
        assertTrue(files(session.poll(202,2048,true)).isEmpty())
        session.handle(Iap2Csm.Frame(0x5000,Iap2Csm.group(0,Iap2Csm.param(26,byteArrayOf()))))
        val out=session.poll(203,2048,true)
        assertArrayEquals(Iap2Csm.group(0,Iap2Csm.u8(26,0x80)),controls(out).single().body)
        assertEquals(0,files(out).single()[9].toInt())
    }

    @Test fun emptyInitializationTimeoutOrRejectionDoesNotBlockRealArtworkOrRetryEmpty() {
        for (rejection in listOf(false,true)) {
            val session=initializing { Iap2NowPlaying(title="keep",artwork=byteArrayOf(4)) }
            session.poll(0,2048,true)
            if (rejection) session.fileResponse(byteArrayOf(0x80.toByte(),6),1)
            val out=session.poll(5000,2048,true)
            assertEquals(0x81,artId(controls(out).single().body))
            val packets=files(out)
            if (!rejection) assertArrayEquals(byteArrayOf(0x80.toByte(),2),packets.first())
            assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,1,0,2),packets.last())
            session.fileResponse(byteArrayOf(0x80.toByte(),1),5001)
            assertTrue(files(session.poll(5002,2048,true)).isEmpty())
        }
    }

    @Test fun stoppingCancelsEmptyInitializationAndNewAuthenticationUsesFreshId() {
        val session=initializing()
        session.poll(0,2048,true)
        session.handle(frame(0x5002))
        assertArrayEquals(byteArrayOf(0x80.toByte(),2),files(session.poll(1,2048,true)).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),1),2)
        assertTrue(session.poll(3,2048,true).isEmpty())
        session.handle(frame(0x5000))
        assertTrue(files(session.poll(4,2048,true)).isEmpty()) // Only once per authenticated link.
        session.reset();session.authenticated();session.handle(frame(0x5000))
        val fresh=session.poll(5,2048,true)
        assertEquals(0x81,artId(controls(fresh).single().body))
        session.fileResponse(byteArrayOf(0x80.toByte(),1),6)
        assertTrue(files(session.poll(7,2048,true)).isEmpty())
        assertTrue(session.hasFileWork)
    }

    @Test fun bmwExplicitSubscriptionUsesPlayerMetadataAndFixedPointSpeed() {
        val details = io.github.hypercar.carui.MediaPlaybackDetails(itemId = 0x0102030405060708,
            trackNumber = 4, trackCount = 15, discNumber = 2, genre = "Jazz", composer = "A",
            liked = true, queueIndex = 3, queueCount = 8, speed = 1.25f)
        val session = Iap2NowPlayingSession({ Iap2NowPlaying(details = details) })
        session.authenticated()
        fun group(id: Int, vararg ids: Int) = Iap2Csm.group(id, *ids.map { Iap2Csm.param(it, byteArrayOf()) }.toTypedArray())
        session.handle(Iap2Csm.Frame(0x5000, group(0, 0, 7, 8, 9, 10, 16, 18, 21, 22, 23, 24, 27) +
            group(1, 2, 3, 12) + group(2, 0, 1, 6)))
        val expected = Iap2Csm.group(0, Iap2Csm.u64(0, 0x0102030405060708), Iap2Csm.u16(7, 4),
            Iap2Csm.u16(8, 15), Iap2Csm.u16(9, 2), Iap2Csm.string(16, "Jazz"), Iap2Csm.string(18, "A"),
            Iap2Csm.u8(21, 0), Iap2Csm.u8(22, 0), Iap2Csm.u8(23, 1),
            Iap2Csm.u16(10, 0), Iap2Csm.u8(24, 0), Iap2Csm.u16(27, 0)) +
            Iap2Csm.group(1, Iap2Csm.u32(2, 3), Iap2Csm.u32(3, 8), Iap2Csm.u16(12, 125))
        assertArrayEquals(expected, controls(session.poll(0, 2048, true)).single().body)
    }
    @Test fun explicitSpeedReportsUnknownWhenPlayerProvidesNoSpeed() {
        val session = Iap2NowPlayingSession({ Iap2NowPlaying(status = 2) })
        session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000, Iap2Csm.group(1, Iap2Csm.param(12, byteArrayOf()))))
        assertArrayEquals(Iap2Csm.group(1, Iap2Csm.u16(12, 0)), controls(session.poll(0, 2048, false)).single().body)
    }
    @Test fun explicitPlaybackCapabilitiesAndBundleUseOnlyRequestedRealFields() {
        val logs = mutableListOf<String>()
        var state = Iap2NowPlaying(title="private-title", appBundleId="com.example.player")
        val session = Iap2NowPlayingSession({ state }, logs::add)
        session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000, Iap2Csm.param(1,
            Iap2Csm.param(13, byteArrayOf()) + Iap2Csm.param(14, byteArrayOf()) +
                Iap2Csm.param(16, byteArrayOf()) + Iap2Csm.param(99, byteArrayOf()))))
        fun playback(now: Long) = Iap2Csm.parseParams(Iap2Csm.parseParams(
            controls(session.poll(now,2048,true)).single().body).single().value).associateBy { it.id }
        val fields = playback(0)
        assertEquals(setOf(13,14,16), fields.keys)
        assertArrayEquals(byteArrayOf(0), fields.getValue(13).value)
        assertArrayEquals(byteArrayOf(0), fields.getValue(14).value)
        assertEquals("com.example.player", fields.getValue(16).asStringOrNull())
        assertTrue(logs.any { "unsupported" in it && "99" in it })
        assertFalse(logs.any { "private-title" in it || "com.example.player" in it })
        state = state.copy(appBundleId="com.example.other")
        assertEquals("com.example.other", playback(1500).getValue(16).asStringOrNull())
    }

    @Test fun newPlaybackFieldsStayAbsentFromLegacyAndUnrequestedSubscriptions() {
        val state = Iap2NowPlaying(appBundleId="com.example.player")
        fun fields(session: Iap2NowPlayingSession) = Iap2Csm.parseParams(Iap2Csm.parseParams(
            controls(session.poll(0,2048,true)).single().body).first { it.id == 1 }.value).map { it.id }
        assertEquals(listOf(0,1,7), fields(subscribed { state }))
        val explicit = Iap2NowPlayingSession({ state }).also { it.authenticated() }
        explicit.handle(Iap2Csm.Frame(0x5000, Iap2Csm.param(1, Iap2Csm.param(0,byteArrayOf()))))
        assertEquals(listOf(0), fields(explicit))
        val unknown = Iap2NowPlayingSession({ state }).also { it.authenticated() }
        unknown.handle(Iap2Csm.Frame(0x5000, Iap2Csm.param(1, Iap2Csm.param(99,byteArrayOf()))))
        assertTrue(unknown.poll(0,2048,true).isEmpty())
    }

    @Test fun explicitSubscriptionEmitsOnlyRequestedSupportedFields() {
        val session = Iap2NowPlayingSession({ Iap2NowPlaying(title="track", artist="artist", status=1) })
        session.authenticated()
        // Independent wire fixture: group 0/title and group 1/status; both request flags are empty.
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,0,0,4,0,1,0,8,0,1,0,4,0,0)))
        val groups = Iap2Csm.parseParams(controls(session.poll(0,2048,true)).single().body)
        assertEquals(listOf(0,1), groups.map { it.id })
        assertEquals(listOf(1), Iap2Csm.parseParams(groups[0].value).map { it.id })
        assertEquals(listOf(0), Iap2Csm.parseParams(groups[1].value).map { it.id })
        assertTrue(session.poll(6000,2048,true).isEmpty()) // No elapsed-time subscription.
    }

    @Test fun subscriptionWithoutArtworkDoesNotStartFileTransfer() {
        val session = Iap2NowPlayingSession({ Iap2NowPlaying(title="track", artwork=byteArrayOf(1,2,3)) })
        session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,0,0,4,0,1)))
        val output = session.poll(0,2048,true)
        assertNull(artId(controls(output).single().body))
        assertTrue(files(output).isEmpty())
        assertFalse(session.hasFileWork)
    }

    @Test fun malformedSubscriptionDoesNotEnableUpdatesOrReplaceAnExistingSelection() {
        val session = Iap2NowPlayingSession()
        session.authenticated()
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,0,0,5,0,1)))
        assertTrue(session.poll(0,2048,true).isEmpty())
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,1,0,4,0,0)))
        val first = controls(session.poll(1,2048,true)).single().body
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,0,0,4,0,1,0)))
        assertTrue(session.poll(2,2048,true).isEmpty())
        assertEquals(listOf(1), Iap2Csm.parseParams(first).map { it.id })
    }

    @Test fun resubscriptionCancelsArtworkWhenItIsNoLongerRequested() {
        val session = subscribed { Iap2NowPlaying(artwork=byteArrayOf(1)) }
        val id = artId(controls(session.poll(0,2048,true)).single().body)!!
        session.handle(Iap2Csm.Frame(0x5000, byteArrayOf(0,8,0,1,0,4,0,0)))
        val output = session.poll(1,2048,true)
        assertEquals(listOf(1), Iap2Csm.parseParams(controls(output).single().body).map { it.id })
        assertArrayEquals(byteArrayOf(id.toByte(),2),files(output).single())
        assertFalse(session.hasFileWork)
    }
    private fun frame(id: Int) = Iap2Csm.Frame(id, byteArrayOf())
    private fun controls(output: List<Iap2NowPlayingSession.Output>) = output.filterIsInstance<Iap2NowPlayingSession.Output.Control>()
    private fun files(output: List<Iap2NowPlayingSession.Output>) = output.filterIsInstance<Iap2NowPlayingSession.Output.File>().map { it.bytes }
    private fun artId(body: ByteArray): Int? = Iap2Csm.parseParams(Iap2Csm.parseParams(body).first { it.id == 0 }.value)
        .firstOrNull { it.id == 26 }?.value?.single()?.toInt()?.and(255)
    private fun subscribed(provider: () -> Iap2NowPlaying): Iap2NowPlayingSession =
        Iap2NowPlayingSession(provider).also { it.authenticated(); it.handle(frame(0x5000)) }

    @Test fun wireFieldsUseAlbumSixAndSingleByteArtworkIdentifier() {
        val body = Iap2NowPlaying("歌曲", "歌手", "专辑", "播放器", 120_000, 4_000, 1).body(0x80)
        val groups = Iap2Csm.parseParams(body)
        val item = Iap2Csm.parseParams(groups.first { it.id == 0 }.value).associateBy { it.id }
        assertEquals("专辑", item.getValue(6).asStringOrNull())
        assertEquals("歌曲", item.getValue(1).asStringOrNull())
        assertEquals("歌手", item.getValue(12).asStringOrNull())
        assertArrayEquals(byteArrayOf(0x80.toByte()), item.getValue(26).value)
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
    @Test fun quickTrackChangeAndLateCoverReachAnExistingSubscriber() {
        var state = Iap2NowPlaying(title = "first", artwork = byteArrayOf(1))
        val session = subscribed { state }
        val first = artId(controls(session.poll(0, 2048, true)).single().body)!!
        state = state.copy(title = "second", artwork = null)
        val update = controls(session.poll(100, 2048, true)).single()
        assertNull(artId(update.body))
        val item = Iap2Csm.parseParams(update.body).first { it.id == 0 }
        assertEquals("second", Iap2Csm.parseParams(item.value).first { it.id == 1 }.asStringOrNull())
        state = state.copy(artwork = byteArrayOf(2))
        val second = artId(controls(session.poll(200, 2048, true)).single().body)!!
        assertNotEquals(first, second)
        session.fileResponse(byteArrayOf(first.toByte(), 1), 201)
        assertTrue(session.poll(201, 2048, true).isEmpty())
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
        assertEquals(0x80, id) // Device -> accessory namespace, as in the box's 80 04 SETUP.
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
    @Test fun completedArtworkIdentifiersWrapWithinDeviceNamespace() {
        var state=Iap2NowPlaying(artwork=byteArrayOf(1))
        val session=subscribed { state }
        repeat(300) { index ->
            val now = index * 100L
            state=state.copy(artwork=byteArrayOf(index.toByte()))
            val output = session.poll(now,2048,true)
            val id = artId(controls(output).single().body)!!
            assertEquals(0x80 + index % 128, id)
            assertTrue(output.first() is Iap2NowPlayingSession.Output.Control)
            assertArrayEquals(byteArrayOf(id.toByte(),4,0,0,0,0,0,0,0,1,0,2),files(output).single())
            assertTrue(files(session.poll(now+1,2048,true)).isEmpty())
            session.fileResponse(byteArrayOf(id.toByte(),1),now+2)
            assertArrayEquals(byteArrayOf(id.toByte(),0xc0.toByte(),index.toByte()),
                files(session.poll(now+3,2048,true)).single())
            session.fileResponse(byteArrayOf(id.toByte(),5),now+4)
            assertFalse(session.hasFileWork)
        }
    }
    @Test fun wrappingAfterTrackChangesCancelsOldTransferBeforeSetupAndIgnoresItsReplies() {
        var state=Iap2NowPlaying(artwork=byteArrayOf(1))
        val session=subscribed { state }
        var previous: Int? = null
        repeat(260) { index ->
            val now = index * 100L
            state=state.copy(artwork=byteArrayOf(index.toByte()))
            val output=session.poll(now,2048,true)
            val id=artId(controls(output).single().body)!!
            assertEquals(0x80 + index % 128,id)
            val packets=files(output)
            previous?.let { old ->
                assertNotEquals(old,id)
                assertEquals(2,packets.size)
                assertArrayEquals(byteArrayOf(old.toByte(),2),packets.first())
                session.fileResponse(byteArrayOf(old.toByte(),1),now+1)
                session.fileResponse(byteArrayOf(old.toByte(),5),now+2)
                assertTrue(files(session.poll(now+3,2048,true)).isEmpty())
                assertTrue(session.hasFileWork)
            }
            assertArrayEquals(byteArrayOf(id.toByte(),4),packets.last().copyOfRange(0,2))
            previous=id
        }
    }
    @Test fun artworkBodyNeverAdvertisesAnAccessoryDirectionIdentifier() {
        for (id in listOf(0,1,127,256)) assertNull(artId(Iap2NowPlaying().body(id)))
        for (id in listOf(128,255)) assertEquals(id,artId(Iap2NowPlaying().body(id)))
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
