package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2DetectionRestartTest {
    private fun packet(control: Int, seq: Int, ack: Int, session: Int = 0, body: ByteArray = byteArrayOf()): ByteArray {
        val n = 9 + if (body.isEmpty()) 0 else body.size + 1
        val h = byteArrayOf(0xff.toByte(), 0x5a, (n shr 8).toByte(), n.toByte(), control.toByte(), seq.toByte(), ack.toByte(), session.toByte(), 0)
        h[8] = (-h.sumOf { it.toInt() and 255 }).toByte()
        return if (body.isEmpty()) h else h + body + (-body.sumOf { it.toInt() and 255 }).toByte()
    }
    private fun syn(session: Int = 10, seq: Int = 40) = packet(0x80, seq, 0, body =
        Iap2LinkEngine.SynchronizationPayload(4, 65535, 4000, 1000, 4, 1,
            listOf(Iap2LinkEngine.SessionDescriptor(session, 0, 2))).encode())
    private fun normal(reliable: Boolean = false): Iap2LinkEngine {
        val link = Iap2LinkEngine(reliableTransport = reliable)
        link.start(0, passive = true)
        link.feed(syn(), 1)
        val answer = link.takeOutput()
        link.feed(packet(0x40, 40, answer[5].toInt() and 255), 2)
        link.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, link.state)
        return link
    }

    @Test fun fragmentedDetectRestartsBluetoothAndWifiWithoutOldRetries() {
        for (reliable in listOf(false, true)) for (cut in 1..5) {
            val link = normal(reliable)
            var restarts = 0
            link.onDetectionRestart = { restarts++ }
            repeat(8) { link.sendControl(Iap2Csm.encode(0x4300), 3) }
            if (reliable) assertTrue(link.queuedControlPackets > 0)
            link.takeOutput()
            link.feed(Iap2LinkEngine.MARKER.copyOfRange(0, cut), 4)
            assertEquals(Iap2LinkEngine.State.NORMAL, link.state)
            assertTrue(link.takeOutput().isEmpty())
            link.feed(Iap2LinkEngine.MARKER.copyOfRange(cut, 6), 5)
            assertEquals(1, restarts)
            assertEquals(Iap2LinkEngine.State.NEGOTIATING, link.state)
            assertNull(link.peerSynchronization)
            assertEquals(0, link.outstandingPackets)
            assertEquals(0, link.queuedControlPackets)
            assertArrayEquals(Iap2LinkEngine.MARKER, link.takeOutput())
            link.advanceTime(50_000)
            assertTrue(link.takeOutput().isEmpty()) // No old CSM retry or proactive SYN.
            assertFalse(link.isDead)
        }
    }

    @Test fun sameReadDetectAndSynAdoptNewSessionAndDeliver4301() {
        val link = normal(true)
        link.sendControl(Iap2Csm.encode(0x4e0c), 3) // Old unconsumed output must disappear.
        link.feed(Iap2LinkEngine.MARKER + syn(session = 1, seq = 90), 4)
        val reply = link.takeOutput()
        assertArrayEquals(Iap2LinkEngine.MARKER, reply.copyOfRange(0, 6))
        val synAck = reply.copyOfRange(6, reply.size)
        assertEquals(0xc0, synAck[4].toInt() and 255)
        link.feed(packet(0x40, 90, synAck[5].toInt() and 255), 5)
        val csm = Iap2Csm.encode(0x4301)
        link.feed(packet(0x40, 91, synAck[5].toInt() and 255, 1, csm), 6)
        assertEquals(Iap2LinkEngine.State.NORMAL, link.state)
        assertArrayEquals(csm, (link.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        link.sendControl(Iap2Csm.encode(0x4e09), 7)
        val output = link.takeOutput()
        assertEquals(1, output[7].toInt()) // New control session, not stale session 10.
    }

    @Test fun repeatedMarkerIsBoundedButPeerCanRetry() {
        val link = normal()
        var restarts = 0
        link.onDetectionRestart = { restarts++ }
        link.feed(Iap2LinkEngine.MARKER, 10)
        assertArrayEquals(Iap2LinkEngine.MARKER, link.takeOutput())
        repeat(20) { link.feed(Iap2LinkEngine.MARKER, 11) }
        assertTrue(link.takeOutput().isEmpty())
        link.feed(Iap2LinkEngine.MARKER, 1010)
        assertArrayEquals(Iap2LinkEngine.MARKER, link.takeOutput())
        assertEquals(1, restarts)
    }

    @Test fun markerInsideDataAndMalformedMarkerDoNotRestart() {
        val link = normal()
        var restarts = 0
        link.onDetectionRestart = { restarts++ }
        val csm = Iap2Csm.encode(0x5000, Iap2LinkEngine.MARKER)
        link.feed(packet(0x40, 41, 100, 10, csm), 3)
        assertArrayEquals(csm, (link.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        link.takeOutput()
        link.feed(byteArrayOf(0xff.toByte(), 0x55, 2, 0, 0, 0), 4)
        assertEquals(0, restarts)
        assertTrue(link.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NORMAL, link.state)
    }

    @Test fun staleAckAndControlCannotReopenLinkBeforeNewSyn() {
        val link = normal(true)
        link.feed(Iap2LinkEngine.MARKER, 3)
        link.takeOutput()
        link.feed(packet(0x40, 41, 104), 4)
        link.feed(packet(0x40, 42, 104, 10, Iap2Csm.encode(0x4301)), 5)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, link.state)
        assertEquals(0, link.outstandingPackets)
        assertNull(link.pollEvent())
        assertTrue(link.takeOutput().isEmpty())
    }

    @Test fun detectDoesNotReviveClosedLink() {
        val link = normal()
        link.feedEof()
        link.feed(Iap2LinkEngine.MARKER + syn(), 4)
        assertEquals(Iap2LinkEngine.State.DEAD, link.state)
        assertTrue(link.takeOutput().isEmpty())
    }

    @Test fun initialActiveEchoDoesNotRestartOrPingPong() {
        val link = Iap2LinkEngine()
        link.start(0)
        link.takeOutput()
        link.feed(Iap2LinkEngine.MARKER, 1)
        assertTrue(link.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, link.state)
    }

    @Test fun duplicateSynRemainsIdempotentAfterRestart() {
        val link = normal()
        link.feed(Iap2LinkEngine.MARKER + syn(), 3)
        val first = link.takeOutput().drop(6).toByteArray()
        link.feed(syn(), 4)
        assertArrayEquals(first, link.takeOutput())
    }
}
