package io.github.hypercar.session

import io.github.hypercar.iap2.RfcommClose
import io.github.hypercar.net.WifiRequestAssociation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WifiLossSnapshotsTest {
    @Test fun activationRecoveryKeepsTheOriginalEmptyCloseSnapshot() {
        val recovery = ProactiveActivation()
        val later = RfcommClose({}) { fail("later RFCOMM was collected") }
        assertTrue(recovery.interceptWifiLoss(null, 10, null))
        assertTrue(recovery.interceptWifiLoss(null, 11, later))
        assertEquals(10L, recovery.wifiLoss?.observedAt)
        assertNull(recovery.wifiLoss?.rfcommClose)
    }
    @Test fun oldNetworkCannotRetireARegistrationAlreadyBoundToItsReplacement() {
        val losses = WifiLossSnapshots<String>(7)
        losses.bindRfcomm("old network", 12)
        losses.bindRfcomm("replacement", 12)
        assertNull(losses.first("old network") {
            assertNull(it)
            null
        })
        losses.bindRfcomm("old network", 13)
        assertNull(losses.first("old network") { fail("late binding replaced first loss"); null })
        assertEquals(12L, losses.first("replacement") {
            RfcommClose({}, exactRegistration = it) {}
        }!!.exactRegistration)
    }

    @Test fun concurrentLossCallbacksTakeOneSnapshot() {
        val losses = WifiLossSnapshots<String>(1)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val captures = AtomicInteger()
        try {
            val reports = List(2) {
                pool.submit(Callable {
                    start.await()
                    losses.first("network") { captures.incrementAndGet(); null }
                })
            }
            start.countDown()
            reports.forEach { assertNull(it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, captures.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun eitherCallbackCanFinishLastWithoutClosingLaterInbound() {
        val losses = WifiLossSnapshots<String>(1)
        val closed = mutableListOf<String>()
        val joiner = losses.first("network") { RfcommClose({}) { closed += "old" } }
        val monitor = losses.first("network") { error("recaptured after CarPlay tap") }
        assertSame(joiner, monitor)
        monitor!!.close()
        assertEquals(listOf("old"), closed)
        assertNull(losses.first("another network") { null })
        assertNull(losses.first("another network") { error("replaced empty snapshot") })
        losses.retire()
        assertNull(losses.first("network") { error("retired attempt") })
        val successor = WifiLossSnapshots<String>(2)
        assertNotNull(successor.first("network") { RfcommClose({}) { closed += "next" } })
    }

    @Test fun delayedSpecifierLossAfterAnEmptyMonitorSnapshotCannotCloseTheAdoptersRfcomm() {
        val request = WifiRequestAssociation<Any, String>(1)
        val callback = Any()
        request.register(callback)
        request.available(callback, "network")
        val original = WifiLossSnapshots<String>(1)
        request.monitorLost(original.attempt, "network")
        assertNull(original.first("network") { null })
        original.retire()

        // Android redelivers a usable handle before the specifier's older onLost arrives.
        request.available(callback, "network")
        assertTrue(request.adopt("network", 2) { true })
        assertNull(request.lost(callback, "network", boundFallback = true))
        assertTrue(request.owns("network"))
        assertEquals(2L, request.lost(callback, "network"))
    }

    @Test fun nativeTerminationAloneAllowsAHealthyLeaseToCaptureTheAdoptersGenuineWifiLoss() {
        val request = WifiRequestAssociation<Any, String>(1)
        val callback = Any()
        request.register(callback)
        request.available(callback, "network")
        val original = WifiLossSnapshots<String>(1)
        assertNull(original.first("network") { null })
        original.retire()
        assertEquals("network", request.finish(keepConnected = true) { fail("withdrew live request") })

        val successor = WifiLossSnapshots<String>(2)
        assertTrue(request.adopt("network", successor.attempt) { true })
        assertEquals(successor.attempt, request.lost(callback, "network"))
        var closed = false
        successor.first("network") { RfcommClose({}) { closed = true } }!!.close()
        assertTrue(closed)
    }
}
