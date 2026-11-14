package io.github.hypercar.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneIdentitySnapshotTest {
    private val a = "02:11:22:33:44:55"
    private val b = "02:66:77:88:99:00"

    @Test fun reservedMalformedAndMulticastAddressesCannotBecomePhoneIdentity() {
        for (value in listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF", "03:11:22:33:44:55", "not a MAC"))
            assertNull(value, PhoneIdentitySnapshot.normalize(value))
        assertEquals(a, PhoneIdentitySnapshot.normalize(" 02:11:22:33:44:55 "))
        assertEquals("AC:DE:12:34:56:78", PhoneIdentitySnapshot.normalize("ac:de:12:34:56:78"))
    }

    @Test fun concurrentTransportReadersShareTheFirstResolvedIdentity() = runTest {
        val snapshot = PhoneIdentitySnapshot()
        var queries = 0
        val provider: suspend () -> String = { queries++; delay(20); a }
        val first = async { snapshot.resolve(null, provider) { b } }
        val second = async { snapshot.resolve(null, provider) { b } }
        assertEquals(a, first.await()); assertEquals(a, second.await())
        assertEquals(1, queries)
        assertEquals(a, snapshot.peek())
    }

    @Test fun missingProviderOrReservedResultUsesTheSamePersistedFallback() = runTest {
        for (provider in listOf<suspend () -> String?>({ null }, { "02:00:00:00:00:00" }, { throw IllegalStateException() })) {
            val snapshot = PhoneIdentitySnapshot()
            assertEquals(b, snapshot.resolve(null, provider) { b })
            assertEquals(b, snapshot.resolve(null, { a }) { b })
        }
    }

    @Test fun changedManualSelectionOrNewLifecycleCannotReuseOldIdentity() = runTest {
        val snapshot = PhoneIdentitySnapshot()
        assertEquals(a, snapshot.resolve(null, { a }) { b })
        assertEquals(b, snapshot.resolve(b, { b }) { a })
        snapshot.invalidate()
        assertNull(snapshot.peek())
        assertEquals(a, snapshot.resolve(b, { a }) { b })
    }

    @Test fun invalidatedInFlightQueryCannotPublishIntoTheNextLifecycle() = runTest {
        val snapshot = PhoneIdentitySnapshot()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = async {
            try { snapshot.resolve(null, { entered.complete(Unit); finish.await(); a }) { b }; false }
            catch (_: CancellationException) { true }
        }
        entered.await(); snapshot.invalidate(); finish.complete(Unit)
        assertTrue(old.await()); assertNull(snapshot.peek())
        assertEquals(b, snapshot.resolve(null, { b }) { a })
    }
}
