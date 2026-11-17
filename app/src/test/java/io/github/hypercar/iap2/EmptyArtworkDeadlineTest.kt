package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class EmptyArtworkDeadlineTest {
    private fun session() = Iap2NowPlayingSession(
        { Iap2NowPlaying(artwork=byteArrayOf(7)) }, initializeArtworkWithEmptyFile=true
    ).also { it.authenticated(); it.handle(Iap2Csm.Frame(0x5000,byteArrayOf())); it.poll(0,2048,true) }

    private fun files(session: Iap2NowPlayingSession, now: Long) = session.poll(now,2048,true)
        .filterIsInstance<Iap2NowPlayingSession.Output.File>().map { it.bytes }

    @Test fun expiredStartMustNotReviveEmptyTransferBeforeTimeoutPoll() {
        val session=session()
        // Original audit reproducer: transport drains incoming events before checking timeouts.
        session.fileResponse(byteArrayOf(0x80.toByte(),1),5001)
        val packets=files(session,5001)
        assertArrayEquals(byteArrayOf(0x80.toByte(),2),packets.first())
        assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,1,0,2),packets.last())
    }

    @Test fun startAndCompletionAtOrAfterEitherDeadlineCancelBeforeUpdatingRealArtwork() {
        for (started in listOf(false,true)) for (command in listOf(1,5)) for (late in listOf(0L,1L)) {
            val session=session()
            val deadline=if (started) {
                session.fileResponse(byteArrayOf(0x80.toByte(),1),1)
                assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(session,2).single())
                5002L
            } else 5000L
            // A recent poll must not defer the replacement behind the 100ms metadata interval.
            assertTrue(files(session,deadline-1).isEmpty())
            session.fileResponse(byteArrayOf(0x80.toByte(),command.toByte()),deadline+late)
            val packets=files(session,deadline+late)
            assertEquals(2,packets.size)
            assertArrayEquals(byteArrayOf(0x80.toByte(),2),packets.first())
            assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,1,0,2),packets.last())
            session.fileResponse(byteArrayOf(0x80.toByte(),1),deadline+late+1)
            session.fileResponse(byteArrayOf(0x80.toByte(),5),deadline+late+1)
            assertTrue(session.hasFileWork)
            assertTrue(files(session,deadline+late+2).isEmpty())
            session.fileResponse(byteArrayOf(0x81.toByte(),1),deadline+late+3)
            assertArrayEquals(byteArrayOf(0x81.toByte(),0xc0.toByte(),7),files(session,deadline+late+4).single())
            session.fileResponse(byteArrayOf(0x81.toByte(),5),deadline+late+5)
            assertFalse(session.hasFileWork)
        }
    }

    @Test fun responsesJustBeforeDeadlineRemainValid() {
        val session=session()
        session.fileResponse(byteArrayOf(0x80.toByte(),1),4999)
        assertArrayEquals(byteArrayOf(0x80.toByte(),0xc0.toByte()),files(session,4999).single())
        session.fileResponse(byteArrayOf(0x80.toByte(),5),9998)
        val real=files(session,9998)
        assertArrayEquals(byteArrayOf(0x81.toByte(),4,0,0,0,0,0,0,0,1,0,2),real.single())
    }
}
