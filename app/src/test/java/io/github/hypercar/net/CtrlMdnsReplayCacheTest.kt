package io.github.hypercar.net

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CtrlMdnsReplayCacheTest {
    private val source = InetAddress.getByName("192.168.4.2")
    private val addresses = setOf(source)
    private val mac = "84:ae:de:56:d3:76"
    private var now = 10_000L
    private val cache = CtrlMdnsReplayCache { now }

    private fun packet(name: String, port: Int = 7000) = MdnsDiagnosticPacket.Announcement(
        "$name._carplay-ctrl._tcp.local", "phone.local", port,
        setOf("id=$mac", "srcvers=770.8.1"), addresses, ttlMillis = 2_000,
    )

    private fun record(name: String, marker: Int, port: Int = 7000, epoch: Long = cache.epoch()) {
        cache.record(4, byteArrayOf(marker.toByte()), packet(name, port), source, now, epoch)
    }

    private fun current(since: Long = 0) = cache.eligible("phone", 7000, mac, "770.8.1", addresses, since, null)

    @Test fun otherLocalRegistrationsDoNotReplaceTheMatchingAnnouncement() {
        record("phone", 1)
        record("other", 2)
        record("phone", 3, port = 8000)
        assertArrayEquals(byteArrayOf(1), current().single().bytes)
        now++
        record("phone", 4)
        assertArrayEquals(byteArrayOf(4), current().single().bytes)
        assertTrue(current(since = now + 1).isEmpty())
    }

    @Test fun expirationAndAddressEpochInvalidateEvenAnAlreadySelectedPacket() {
        record("phone", 1)
        val selected = current().single()
        assertTrue(cache.canSend(selected))
        now += 2_000
        assertFalse(cache.canSend(selected))
        assertTrue(current().isEmpty())

        val oldEpoch = cache.epoch()
        record("phone", 2)
        val beforeChange = current().single()
        cache.invalidate()
        record("phone", 3, epoch = oldEpoch)
        assertFalse(cache.canSend(beforeChange))
        assertTrue(current().isEmpty())
        record("phone", 4)
        assertArrayEquals(byteArrayOf(4), current().single().bytes)
    }

    @Test fun replayWaitsOneSecondAfterAnOriginalOrManuallySubmittedAnnouncement() {
        record("phone", 1)
        val selected = current().single()
        assertFalse(cache.replayDue(selected))
        now += 999
        assertFalse(cache.replayDue(selected))
        now++
        assertTrue(cache.replayDue(selected))
        assertTrue(cache.claimReplay(selected))
        assertFalse(cache.claimReplay(selected))
        now++
        cache.noteLocalSrv(4, "phone._carplay-ctrl._tcp.local", 7000, "phone.local", now, cache.epoch())
        now += 999
        assertFalse(cache.replayDue(selected))
    }

    @Test fun aSplitSrvTargetChangeInvalidatesOldCopiesOnBothAddressFamilies() {
        record("phone", 1)
        cache.record(6, byteArrayOf(2), packet("phone"), source, now, cache.epoch())
        record("other", 3)
        val old = current()
        now++
        cache.noteLocalSrv(6, "phone._carplay-ctrl._tcp.local", 7000, "new.local", now, cache.epoch())
        assertTrue(current().isEmpty())
        assertTrue(old.none(cache::canSend))
        assertEquals(1, cache.eligible("other", 7000, mac, "770.8.1", addresses, 0, null).size)
        cache.record(4, byteArrayOf(1), packet("phone"), source, now - 1, cache.epoch())
        assertTrue(current().isEmpty())
        cache.record(6, byteArrayOf(4), packet("phone").copy(host = "new.local"), source, now, cache.epoch())
        assertEquals("new.local", current().single().metadata.host)
    }
}
