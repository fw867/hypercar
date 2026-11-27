package io.github.hypercar.spike

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserServiceBindingsTest {
    @Test fun aNewBrokerCanBindWhileBothOldUnbindsAreBlocked() {
        val bindings = UserServiceBindings()
        val registered = CountDownLatch(2)
        val unbinding = CountDownLatch(2)
        val release = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val old = listOf(UserServiceBinding(), UserServiceBinding())
        val fresh = UserServiceBinding()
        try {
            old.forEach { attempt ->
                bindings.start("old", attempt, { registered.countDown(); true }) {
                    unbinding.countDown()
                    release.await()
                }
            }
            assertTrue(registered.await(3, TimeUnit.SECONDS))
            old.forEach { it.close() }
            assertTrue(unbinding.await(3, TimeUnit.SECONDS))
            bindings.start("new", fresh, { replacement.countDown(); true }) {}
            assertTrue(replacement.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            fresh.close()
            old.forEach { it.close() }
        }
    }

    @Test fun oneBlockedBindingAllowsAnotherAttemptAndSaturationRejectsWithoutQueuing() = runBlocking {
        val bindings = UserServiceBindings()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retried = CountDownLatch(1)
        val old = UserServiceBinding()
        val retry = UserServiceBinding()
        val rejected = UserServiceBinding()
        val unwantedCalls = AtomicInteger()
        try {
            bindings.start("same", old, {
                started.countDown()
                release.await()
                true
            }) {}
            assertTrue(started.await(3, TimeUnit.SECONDS))
            old.close()
            bindings.start("same", retry, { retried.countDown(); true }) {}
            assertTrue(retried.await(3, TimeUnit.SECONDS))
            bindings.start("same", rejected, { unwantedCalls.incrementAndGet(); true }) { unwantedCalls.incrementAndGet() }
            assertTrue(rejected.finished.isCompleted)
            assertFalse(rejected.finished.await())
            assertEquals(0, unwantedCalls.get())
        } finally {
            release.countDown()
            old.close()
            retry.close()
            rejected.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun expiryBeforeDispatchSkipsBothRegistrationAndRemoval() = runTest {
        val bindings = UserServiceBindings(StandardTestDispatcher(testScheduler))
        val expired = UserServiceBinding()
        val calls = mutableListOf<String>()
        bindings.start("broker", expired, { calls.add("bind"); true }) { calls.add("unbind") }
        expired.close()
        runCurrent()
        assertFalse(expired.finished.await())
        assertTrue(calls.isEmpty())
    }
}
