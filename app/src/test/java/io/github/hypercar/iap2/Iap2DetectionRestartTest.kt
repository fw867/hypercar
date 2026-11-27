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
    private fun syn(session: Int = 10, seq: Int = 40, zeroRetryAndAck: Boolean = false) = packet(0x80, seq, 0, body =
        Iap2LinkEngine.SynchronizationPayload(4, 65535,
            if (zeroRetryAndAck) 0 else 4000, if (zeroRetryAndAck) 0 else 1000,
            if (zeroRetryAndAck) 0 else 4, if (zeroRetryAndAck) 0 else 1,
            listOf(Iap2LinkEngine.SessionDescriptor(session, 0, 2))).encode())
    private fun normal(reliable: Boolean = false, zeroRetryAndAck: Boolean = false, window: Int = 127): Iap2LinkEngine {
        val link = Iap2LinkEngine(reliableTransport = reliable, declaredWindow = window)
        link.start(0, passive = true)
        link.feed(syn(zeroRetryAndAck = zeroRetryAndAck), 1)
        val answer = link.takeOutput()
        link.feed(packet(0x40, 40, answer[5].toInt() and 255), 2)
        link.takeOutput()
        assertEquals(Iap2LinkEngine.State.NORMAL, link.state)
        return link
    }

    @Test fun authenticationOutputRemainsProtectedAcrossWindowWaitAndRetransmit() {
        val link = normal(window = 1)
        val result = Iap2Csm.encode(0xaa05)
        link.sendControl(Iap2Csm.encode(0x4300), 3)
        val first = link.takeOutput()
        link.sendMfiControl(result, 4, awaitAck = true, result = true)
        assertEquals(1, link.queuedControlPackets)
        assertFalse(link.outputContainsMfi)
        link.feed(packet(0x40, 40, first[5].toInt() and 255), 5)
        assertTrue(link.outputContainsMfi)
        val sent = link.takeOutput()
        assertArrayEquals(result, sent.copyOfRange(9, sent.size - 1))
        assertFalse(link.outputContainsMfi)
        link.advanceTime(4006)
        assertTrue(link.outputContainsMfi)
        assertTrue(link.takeOutput().size >= sent.size)
        assertFalse(link.outputContainsMfi)
    }

    @Test fun localStopBeforeAckOrRetryPreventsAnotherResultFromReachingTheSocket() {
        for (alreadySent in listOf(false, true)) {
            val link = normal(window = 1)
            val transport = RfcommTransport(java.io.Closeable {})
            val result = Iap2Csm.encode(0xaa05)
            val lines = mutableListOf<String>()
            link.mfiLog = lines::add
            if (alreadySent) {
                link.sendMfiControl(result, 3, awaitAck = true, result = true)
                transport.writeOutput(link) { assertArrayEquals(result, it.copyOfRange(9, it.size - 1)) }
                transport.noteLocalStop()
                link.advanceTime(4004)
            } else {
                link.sendControl(Iap2Csm.encode(0x4300), 3)
                val seq = link.takeOutput()[5].toInt() and 255
                link.sendMfiControl(result, 4, awaitAck = true, result = true)
                transport.noteLocalStop()
                link.feed(packet(0x40, 40, seq), 5)
            }
            assertThrows(java.io.InterruptedIOException::class.java) {
                transport.writeOutput(link) { fail("cancelled 0xaa05 reached the socket") }
            }
            assertEquals(if (alreadySent) 1 else 0, lines.count { "RFCOMM written " in it })
            assertTrue(lines.last().contains("RFCOMM cancelled id=0xaa05"))
            assertTrue(lines.last().contains("retry=${if (alreadySent) 1 else 0} "))
        }
    }

    @Test fun synchronousWriteFailureReachesCallerAfterQueueAdmission() {
        val link = normal()
        val lines = mutableListOf<String>()
        link.mfiLog = lines::add
        val transport = RfcommTransport(java.io.Closeable {})
        val error = java.io.IOException("socket write failed")
        val seen = assertThrows(java.io.IOException::class.java) {
            transport.sendMfiControl(link, Iap2Csm.encode(0xaa05), 3, true, result = true) {
                try {
                    transport.writeOutput(link) { throw error }
                    null
                } catch (failure: java.io.IOException) {
                    failure
                }
            }
        }
        assertSame(error, seen)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("RFCOMM enqueued id=0xaa05"))
        assertTrue(lines[1].contains("RFCOMM write_failed id=0xaa05 seq=101 retry=0 "))
    }

    @Test fun delayedFragmentAndRetryAreReportedOnlyAfterTheirWritesReturn() {
        val link = normal(window = 1)
        val transport = RfcommTransport(java.io.Closeable {})
        val lines = mutableListOf<String>()
        link.mfiLog = lines::add
        link.sendControl(byteArrayOf(1), 3)
        val firstSeq = link.takeOutput()[5].toInt() and 255
        link.sendMfiControl(byteArrayOf(0), 4, true, result = true) // A continuation fragment has no CSM header.
        assertEquals(0, transport.writeOutput(link) { fail("window still full") })
        assertEquals(1, lines.size)
        link.feed(packet(0x40, 40, firstSeq), 5)
        transport.writeOutput(link) { assertEquals(1, lines.size) }
        assertTrue(lines.last().contains("RFCOMM written id=0xaa05 seq=102 retry=0 fragment_bytes=1"))
        link.advanceTime(4006)
        transport.writeOutput(link) { assertEquals(2, lines.size) }
        assertTrue(lines.last().contains("RFCOMM written id=0xaa05 seq=102 retry=1 fragment_bytes=1"))
    }

    @Test fun resetAndEofDiscardQueuedAndBufferedFragmentsWithoutReportingAWireWrite() {
        for (eof in listOf(false, true)) {
            val link = normal(window = 1)
            val lines = mutableListOf<String>()
            link.mfiLog = lines::add
            repeat(2) { link.sendMfiControl(byteArrayOf(0), 3, true, result = true) }
            if (eof) link.feedEof() else link.feed(Iap2LinkEngine.MARKER, 4)
            val reason = if (eof) "link_ended" else "link_reset"
            assertEquals(2, lines.count { "discarded id=0xaa05" in it && "reason=$reason" in it })
            assertTrue(lines.any { "seq=unassigned" in it })
            assertTrue(lines.any { "seq=101" in it })
            assertFalse(lines.any { "RFCOMM written" in it })
        }
    }

    @Test fun zeroAckSuppressionIsWifiOnlyAndNewSynReplacesThePolicy() {
        for (reliable in listOf(false, true)) {
            val link = normal(reliable, zeroRetryAndAck = true)
            val data = Iap2Csm.encode(0x1234)
            link.feed(packet(0x40, 41, 100, 10, data), 3)
            assertArrayEquals(data, (link.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
            assertEquals(reliable, link.takeOutput().isEmpty())

            // Renegotiate on the same transport. The preceding zero-ACK policy must expire.
            link.feed(Iap2LinkEngine.MARKER + syn(seq = 90), 4)
            val output = link.takeOutput()
            assertArrayEquals(Iap2LinkEngine.MARKER, output.copyOfRange(0, 6))
            val sync = output.copyOfRange(6, output.size)
            link.feed(packet(0x40, 90, sync[5].toInt() and 255), 5)
            link.takeOutput()
            link.feed(packet(0x40, 91, sync[5].toInt() and 255, 10, data), 6)
            assertArrayEquals(data, (link.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
            val ack = link.takeOutput()
            assertEquals(9, ack.size)
            assertEquals(91, ack[6].toInt() and 255)
        }
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

    @Test fun aRetiredProductiveEndpointNoLongerBlocksReplacementRfcomm() {
        val admission = Iap2RfcommEndpointState()
        val oldGeneration = Iap2EndpointGeneration()
        val endpoint = CarPlayEndpoint("car", "password", 0, null, null, null, emptyList(), null,
            sourceGeneration = oldGeneration)
        admission.accepted(endpoint)
        assertTrue(admission.hasCurrentEndpoint)
        oldGeneration.retire()
        assertFalse(admission.hasCurrentEndpoint)
        admission.accepted(endpoint)
        assertFalse(admission.hasCurrentEndpoint)

        val newGeneration = Iap2EndpointGeneration()
        admission.accepted(endpoint.copy(ssid = null, sourceGeneration = newGeneration))
        assertFalse(admission.hasCurrentEndpoint)
        admission.accepted(endpoint.copy(sourceGeneration = newGeneration))
        admission.accepted(endpoint)
        assertTrue(admission.hasCurrentEndpoint)
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

    @Test fun staleOrFutureAckDoesNotChangeAFullWifiWindow() {
        val link = normal(reliable = true)
        repeat(5) { link.sendControl(Iap2Csm.encode(0x4e09), 3) }
        val sent = WifiIap2Framing.split(link.takeOutput())
        assertEquals(4, sent.size)
        val lastSent = sent.last()[5].toInt() and 255
        for (invalid in listOf((lastSent - 5) and 255, (lastSent + 1) and 255)) {
            link.feed(packet(0x40, 40, invalid), 4)
            assertEquals(4, link.outstandingPackets)
            assertEquals(1, link.queuedControlPackets)
            assertTrue(link.takeOutput().isEmpty())
        }
        // A duplicate ACK is harmless. A real advancing ACK opens one place.
        link.feed(packet(0x40, 40, (lastSent - 4) and 255), 5)
        assertTrue(link.takeOutput().isEmpty())
        link.feed(packet(0x40, 40, (lastSent - 3) and 255), 6)
        assertEquals(0, link.queuedControlPackets)
        assertEquals(1, WifiIap2Framing.split(link.takeOutput()).size)
        assertEquals(4, link.outstandingPackets)
        val inbound = Iap2Csm.encode(0x5000)
        link.feed(packet(0x40, 41, (lastSent - 4) and 255, 10, inbound), 7)
        assertArrayEquals(inbound, (link.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        assertEquals(4, link.outstandingPackets)
    }

    @Test fun cumulativeAckKeepsUnconfirmedBluetoothPacketsPastPeerReceiveWindowHint() {
        val link = normal()
        repeat(20) { link.sendControl(Iap2Csm.encode(0x4e09), 3) }
        val sent = WifiIap2Framing.split(link.takeOutput())
        assertEquals(20, sent.size)
        fun seq(index: Int) = sent[index][5].toInt() and 255
        link.feed(packet(0x40, 40, seq(0)), 4)
        link.feed(packet(0x40, 40, seq(14)), 5)
        assertEquals(5, link.outstandingPackets)
        link.advanceTime(4003)
        val retried = WifiIap2Framing.split(link.takeOutput()).single { it.size > 9 }
        assertEquals(seq(15), retried[5].toInt() and 255)
    }

    @Test fun cumulativeAckAdvancesThroughSequenceWrap() {
        val link = normal(reliable = true)
        for (expected in 101..254) {
            link.sendControl(Iap2Csm.encode(0x4e09), 3)
            assertEquals(expected, link.takeOutput()[5].toInt() and 255)
            link.feed(packet(0x40, 40, expected), 3)
        }
        repeat(2) { link.sendControl(Iap2Csm.encode(0x4e09), 3) }
        val sent = WifiIap2Framing.split(link.takeOutput())
        assertEquals(listOf(255, 0), sent.map { it[5].toInt() and 255 })
        link.feed(packet(0x40, 40, 253), 4)
        assertEquals(2, link.outstandingPackets)
        link.feed(packet(0x40, 40, 0), 5)
        assertEquals(0, link.outstandingPackets)
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
