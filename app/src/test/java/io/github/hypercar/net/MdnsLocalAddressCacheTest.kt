package io.github.hypercar.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MdnsLocalAddressCacheTest {
    private val old = setOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)))
    private val updated = setOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 3)))

    @Test fun repeatedPacketsRefreshAtMostOncePerSecond() {
        var now = 0L
        var loads = 0
        val cache = MdnsLocalAddressCache({ now }) { if (++loads == 1) old else updated }
        assertEquals(old, cache.current())
        for (offset in 1L..999L) {
            now = offset
            assertEquals(old, cache.current())
        }
        assertEquals(1, loads)
        now = 1_000L
        assertEquals(updated, cache.current())
        assertEquals(2, loads)
    }

    @Test fun missingLinkDoesNotReuseExpiredAddressesOrQueryOnEveryPacket() {
        var now = 10_000L
        var loads = 0
        val cache = MdnsLocalAddressCache({ now }) {
            when (++loads) {
                1 -> old
                2 -> null
                else -> updated
            }
        }
        assertEquals(old, cache.current())
        now += 1_000L
        assertNull(cache.current())
        now += 999L
        assertNull(cache.current())
        assertEquals(2, loads)
        now++
        assertEquals(updated, cache.current())
        assertEquals(3, loads)
    }
}
