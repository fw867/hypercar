package io.github.hypercar.spike

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOutputReadbacksTest {
    @Test fun expiredReadCannotSettleOldJournalOrBlockTheNextOffer() {
        val workers = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val oldCleared = AtomicBoolean(false)
        val nextCleared = CountDownLatch(1)
        val old = CallOutputRestoration({
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            false
        }, {}, { oldCleared.set(true); true })
        val next = CallOutputRestoration({ false }, {}, { nextCleared.countDown(); true })
        try {
            val reads = CallOutputReadbacks(workers)
            val shell = ShellCallAudio(ShellCallIdentity(), readbacks = reads)
            old.write(1)
            next.write(1)
            reads.check(old, current::get) {}
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            current.set(false)
            assertFalse(shell.hasActiveResources())
            reads.check(next, { true }) {}
            assertTrue(nextCleared.await(2, TimeUnit.SECONDS))
            release.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
            assertFalse(oldCleared.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun stalledReadsAreBoundedAndDuplicatesDontQueue() {
        val queue = ArrayDeque<Runnable>()
        val reads = CallOutputReadbacks(queue::addLast)
        val offers = List(3) { CallOutputRestoration({ true }, {}, { true }, waitForMute = true) }
        repeat(5) { offers.forEach { reads.check(it, { true }) {} } }
        assertEquals(2, queue.size)
        assertFalse(reads.hasCapacity())
        queue.removeFirst().run()
        assertTrue(reads.hasCapacity())
        reads.check(offers.last(), { true }) {}
        assertEquals(2, queue.size)
    }
}
