package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LatestMusicQueryTest {
    @Test fun blockedPlayerDoesNotBlockCallerAndOnlyLatestReplacementRuns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val errors = LinkedBlockingQueue<Throwable>()
        val query = LatestMusicQuery<Int>("test-blocked-player", { it() }, { errors.add(it) })
        try {
            query.submit({ started.countDown(); release.await(); 0 }, { results.add(it) })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // All submissions return while the first simulated Binder is still blocked.
            for (i in 1..100) query.submit({ calls.add(i); i }, {
                results.add(it); completed.countDown()
            })
            assertTrue(calls.isEmpty())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(100), calls)
            assertEquals(listOf(100), results)
            assertTrue(errors.isEmpty())
        } finally { release.countDown() }
    }

    @Test fun detachDiscardsAResultAlreadyPostedToTheOwnerThread() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val query = LatestMusicQuery<Int>("test-detach", { deliveries.add(it) }, { throw AssertionError(it) })
        var applied = false
        query.submit({ 1 }, { applied = true })
        val delivery = deliveries.poll(2, TimeUnit.SECONDS)
        assertNotNull(delivery)
        query.invalidate()
        delivery!!.invoke()
        assertFalse(applied)
    }

    @Test fun switchingPlayerDiscardsOldResultsEvenIfDeliveryWasQueued() {
        val deliveries = LinkedBlockingQueue<() -> Unit>()
        val query = LatestMusicQuery<String>("test-switch", { deliveries.add(it) }, { throw AssertionError(it) })
        val results = mutableListOf<String>()
        query.submit({ "old" }, { results.add(it) })
        val old = deliveries.poll(2, TimeUnit.SECONDS)!!
        query.submit({ "new" }, { results.add(it) })
        val current = deliveries.poll(2, TimeUnit.SECONDS)!!
        old(); current()
        assertEquals(listOf("new"), results)
    }

    @Test fun deadPlayerFailureDoesNotPreventNextQuery() {
        val errors = LinkedBlockingQueue<Throwable>()
        val results = LinkedBlockingQueue<Int>()
        val query = LatestMusicQuery<Int>("test-failure", { it() }, { errors.add(it) })
        query.submit({ throw IllegalStateException("dead session") }, { results.add(it) })
        assertTrue(errors.poll(2, TimeUnit.SECONDS) is IllegalStateException)
        query.submit({ 42 }, { results.add(it) })
        assertEquals(42, results.poll(2, TimeUnit.SECONDS))
    }
}
