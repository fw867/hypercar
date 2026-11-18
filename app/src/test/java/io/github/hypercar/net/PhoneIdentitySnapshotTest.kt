package io.github.hypercar.net

import io.github.hypercar.settings.SavedPhoneMac
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

    private suspend fun PhoneIdentitySnapshot.resolve(selection: String?, provider: suspend () -> String?, stable: () -> String) =
        pin({ SavedPhoneMac.Selection(selection, 0) }, { provider() }, stable).address

    @Test fun clearingAfterBluetoothPinsFallbackBeforeAirplayStartsSurvivesFirstFrame() = runTest {
        var saved: String? = b
        val settings = SavedPhoneMac({ saved }, { saved = it })
        val snapshot = PhoneIdentitySnapshot()
        val bluetooth = snapshot.pin(settings::selection, { a }) { b }
        assertEquals(b, bluetooth.address)
        settings.edit(null)
        val airplay = snapshot.pin(settings::selection, { a }) { b }
        assertSame(bluetooth, airplay)
        assertFalse(settings.remember(airplay.settingsRevision, airplay.address))
        assertNull(saved)

        snapshot.invalidate() // Fresh invitation with the same peer and possibly the very same MAC.
        val next = snapshot.pin(settings::selection, { null }) { b }
        assertEquals(b, next.address)
        assertFalse(settings.remember(airplay.settingsRevision, airplay.address))
        assertTrue(settings.remember(next.settingsRevision, next.address))
    }

    @Test fun editDuringAirplayLookupCannotGiveOldResultANewRevision() = runTest {
        var saved: String? = null
        val settings = SavedPhoneMac({ saved }, { saved = it })
        val snapshot = PhoneIdentitySnapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            snapshot.pin(settings::selection, { entered.complete(Unit); release.await(); null }) { b }
        }
        entered.await()
        settings.edit(a)
        settings.edit(null)
        release.complete(Unit)
        val airplay = first.await()
        assertSame(airplay, snapshot.pin(settings::selection, { a }) { b })
        assertFalse(settings.remember(airplay.settingsRevision, airplay.address))
        assertNull(saved)
    }

    private suspend fun PhoneIdentitySnapshot.prefetchAddress(selection: String?, provider: suspend () -> String?) =
        prefetch({ SavedPhoneMac.Selection(selection, 0) }, { provider() })

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
    }

    @Test fun missingProviderOrReservedResultUsesTheSamePersistedFallback() = runTest {
        for (provider in listOf<suspend () -> String?>({ null }, { "02:00:00:00:00:00" }, { throw IllegalStateException() })) {
            val snapshot = PhoneIdentitySnapshot()
            assertEquals(b, snapshot.resolve(null, provider) { b })
            assertEquals(b, snapshot.resolve(null, { a }) { b })
        }
    }

    @Test fun manualChangeBeforeFirstUseWinsButCannotSplitActiveTransports() = runTest {
        val snapshot = PhoneIdentitySnapshot()
        assertEquals(a, snapshot.prefetchAddress(null) { a })
        assertEquals(b, snapshot.resolve(b, { error("manual should win") }) { a })
        assertEquals(b, snapshot.resolve(a, { a }) { b })
        snapshot.invalidate()
        assertEquals(a, snapshot.resolve(a, { b }) { b })
    }

    @Test fun failedPrefetchRetriesOnceForRacingHandshakeAndAirplay() = runTest {
        for (liveValue in listOf(a, null)) {
            val snapshot = PhoneIdentitySnapshot()
            assertNull(snapshot.prefetchAddress(null) { null })
            var queries = 0
            val query: suspend () -> String? = { queries++; delay(10); liveValue }
            val bluetooth = async { snapshot.resolve(null, query) { b } }
            val airplay = async { snapshot.resolve(null, query) { b } }
            assertEquals(liveValue ?: b, bluetooth.await())
            assertEquals(bluetooth.await(), airplay.await())
            assertEquals(liveValue ?: b, snapshot.prefetchAddress(null) { a })
            assertEquals(1, queries)
        }
    }

    @Test fun cachedRealAddressPromotesWithoutRequeryAndUsedFallbackLastsUntilReset() = runTest {
        val snapshot = PhoneIdentitySnapshot()
        assertEquals(a, snapshot.prefetchAddress(null) { a })
        assertEquals(a, snapshot.resolve(null, { fail("unexpected lookup"); null }) { b })
        snapshot.invalidate()
        assertEquals(b, snapshot.resolve(null, { null }) { b }) // Bluetooth handshake already selected it.
        assertEquals(b, snapshot.resolve(null, { a }) { b })
        snapshot.invalidate()
        assertEquals(a, snapshot.resolve(null, { a }) { b })
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
        assertTrue(old.await())
        assertEquals(b, snapshot.resolve(null, { b }) { a })
    }
}
