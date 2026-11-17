package io.github.hypercar.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WifiRequestAssociationTest {
    @Test fun cleanupKeepsDeliveryAfterTheCallersLastSnapshot() {
        val state = WifiRequestAssociation<Any, Int>()
        val owner = Any()
        assertTrue(state.register(owner))
        assertNull(state.network())
        state.available(owner, 7)
        repeat(3) { assertEquals(7, state.finish(keepConnected = true) { fail("withdrew the car hotspot") }) }
        assertTrue(state.lost(owner, 7))
        state.available(owner, 8) // delayed cleanup for network 7 runs after its replacement arrives
        assertEquals(8, state.finish(keepConnected = true) { fail("withdrew replacement") })
        val detached = mutableListOf<Any?>()
        assertNull(state.finish(keepConnected = false, detached::add))
        assertEquals(listOf(owner), detached)
    }

    @Test fun cleanupBeforeDeliveryWithdrawsPendingAndPreventsLateRegistration() {
        val state = WifiRequestAssociation<Any, Int>()
        val owner = Any()
        assertTrue(state.register(owner))
        state.finish(keepConnected = true) { assertSame(owner, it) }
        assertFalse(state.available(owner, 8))
        assertFalse(state.register(Any()))
        val scanning = WifiRequestAssociation<Any, Int>()
        scanning.finish(keepConnected = true) { assertNull(it) }
        assertFalse(scanning.register(owner))
    }
    @Test fun unavailableCannotEraseAnAlreadyDeliveredAssociationDuringCleanup() {
        val state = WifiRequestAssociation<Any, Int>()
        val owner = Any()
        state.register(owner)
        state.available(owner, 7)
        assertFalse(state.unavailable(owner))
        assertNull(state.cancelPending(owner))
        assertTrue(state.owns(7))
    }
    @Test fun availableBeforeWaitSignalSurvivesCancellationAndCapabilityGapsUntilActualLoss() {
        val state = WifiRequestAssociation<Any, Any>()
        val owner = Any(); val network = Any()
        state.register(owner)
        assertTrue(state.available(owner, network))
        // The independent wait latch/result may still be unset here. Cleanup must use
        // the association registry, never that later signal or a capabilities snapshot.
        assertNull(state.cancelPending(owner)) // Caller must not unregister this request.
        assertTrue(state.owns(network))
        assertTrue(state.lost(owner, network))
        assertFalse(state.owns(network))
        assertSame(owner, state.release())
        assertNull(state.release())
    }

    @Test fun concurrentDeliveryAndCancellationNeverLeaveAnUnownedAssociation() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(300) {
                val state = WifiRequestAssociation<Any, Any>()
                val owner = Any(); val network = Any(); val start = CountDownLatch(1)
                state.register(owner)
                val delivery = pool.submit<Boolean> { start.await(); state.available(owner, network) }
                val cancellation = pool.submit<Any?> { start.await(); state.cancelPending(owner) }
                start.countDown()
                val accepted = delivery.get(2, TimeUnit.SECONDS)
                val unregister = cancellation.get(2, TimeUnit.SECONDS)
                if (accepted) {
                    assertNull(unregister)
                    assertSame(network, state.network())
                    assertSame(owner, state.owner())
                } else {
                    assertSame(owner, unregister)
                    assertNull(state.network())
                    assertNull(state.owner())
                }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun oldCallbacksCannotReplaceOrClearANewerRequest() {
        val state = WifiRequestAssociation<Any, Any>()
        val old = Any(); val current = Any(); val network = Any()
        state.register(old); state.release(); state.register(current)
        assertFalse(state.available(old, Any()))
        assertTrue(state.available(current, network))
        assertFalse(state.unavailable(old))
        assertFalse(state.lost(old, network))
        assertNull(state.cancelPending(old))
        assertTrue(state.owns(network))
    }

    @Test fun lossOfOldBoundNetworkCannotClearNewAssociationOnTheSameRequest() {
        val state = WifiRequestAssociation<Any, Any>()
        val owner = Any(); val old = Any(); val current = Any()
        state.register(owner)
        state.available(owner, old)
        state.available(owner, current)
        assertFalse(state.lost(owner, old, boundFallback = true))
        assertNull(state.cancelPending(owner))
        assertTrue(state.owns(current))
    }
}
