package io.github.hypercar.bluetooth

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class DiscoverabilityLeaseTest {
    private class Radio : DiscoverabilityLease.Port {
        var scan = 21
        var timeout = 60L
        var failEnable = false
        var writes = 0
        override fun mode() = scan
        override fun timeoutSeconds() = timeout
        override fun setTimeoutSeconds(seconds: Long) { timeout = seconds; writes++ }
        override fun setMode(mode: Int) {
            if (mode == 23 && failEnable) error("API rejected")
            scan = mode; writes++
        }
    }
    private class Fixture {
        val radio = Radio()
        var expiry: (() -> Unit)? = null
        var scheduledFor = 0L
        val lease = DiscoverabilityLease(radio,
            schedule = { millis, callback -> scheduledFor = millis; expiry = callback; Closeable {} }, log = {},
        )
    }

    @Test fun expiryRestoresConnectableModeAndOriginalTimeout() {
        val f = Fixture()
        assertTrue(f.lease.begin("a", 30).startsWith("OK"))
        assertEquals(23, f.radio.scan)
        assertEquals(30L, f.radio.timeout)
        assertEquals(30_000L, f.scheduledFor)
        f.expiry!!.invoke()
        assertEquals(21, f.radio.scan)
        assertEquals(60L, f.radio.timeout)
    }

    @Test fun earlyEndAndRepeatedEndAreSafe() {
        val f = Fixture()
        f.lease.begin("a", 30)
        f.lease.end("a")
        val writes = f.radio.writes
        f.expiry!!.invoke()
        f.lease.end("a")
        assertEquals(writes, f.radio.writes)
    }

    @Test fun alreadyDiscoverableOrNotConnectableIsLeftUntouched() {
        for (mode in listOf(20, 23)) {
            val f = Fixture(); f.radio.scan = mode
            assertTrue(f.lease.begin("a", 30).startsWith("SKIP"))
            assertEquals(mode, f.radio.scan)
            assertEquals(0, f.radio.writes)
        }
    }

    @Test fun userChangingTheTimeoutKeepsTheirDiscoverableWindow() {
        val f = Fixture(); f.lease.begin("a", 30)
        f.radio.timeout = 120
        f.lease.end("a")
        assertEquals(23, f.radio.scan)
        assertEquals(120L, f.radio.timeout)
    }

    @Test fun stoppingDoesNotReenableAnAdapterTheUserDisabled() {
        val f = Fixture(); f.lease.begin("a", 30)
        f.radio.scan = 20
        f.lease.end("a")
        assertEquals(20, f.radio.scan)
    }

    @Test fun staleTokenOrTimerCannotCloseANewerWindow() {
        val f = Fixture(); f.lease.begin("a", 30)
        val staleExpiry = f.expiry!!
        f.lease.end("a"); f.lease.begin("b", 30)
        staleExpiry(); f.lease.end("a")
        assertEquals(23, f.radio.scan)
        assertEquals(30L, f.radio.timeout)
        f.lease.end("b")
    }

    @Test fun rejectedEnableRollsBackChangedTimeout() {
        val f = Fixture(); f.radio.failEnable = true
        assertTrue(f.lease.begin("a", 30).startsWith("ERR"))
        assertEquals(21, f.radio.scan)
        assertEquals(60L, f.radio.timeout)
    }

    @Test fun refusesUnboundedOrOverlappingWindows() {
        val f = Fixture()
        assertTrue(f.lease.begin("a", 0).startsWith("ERR"))
        assertTrue(f.lease.begin("a", 31).startsWith("ERR"))
        assertEquals(0, f.radio.writes)
        f.lease.begin("a", 30)
        assertTrue(f.lease.begin("b", 30).startsWith("SKIP"))
        f.lease.end("a")
    }
}
