package io.github.hypercar.net

import io.github.hypercar.settings.SavedPhoneMac
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LateBluetoothMacTest {
    private val old = "84:AE:DE:56:D3:76"
    private val manual = "02:66:77:88:99:00"
    private val fallback = "F6:6F:1B:BF:FC:CE"

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun blockedBinderCannotDelayEitherTransportOrPublishAcrossASamePeerReinvite() = runTest {
        for (bluetoothFirst in listOf(true, false)) {
            var saved: String? = null
            val settings = SavedPhoneMac({ saved }, { saved = it })
            val snapshot = PhoneIdentitySnapshot(
                nowMillis = { testScheduler.currentTime },
                rememberHardwareMac = { revision, mac -> settings.remember(revision, mac) },
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            var reads = 0
            val lookup = BluetoothMacLookup {
                reads++
                entered.countDown()
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        // A stalled Binder or pipe can keep blocking even after cancellation.
                    }
                }
                returned.countDown()
                old
            }
            suspend fun bluetooth() = snapshot.pin(settings::selection, lookup::resolve) { fallback }.address
            suspend fun airplay() = snapshot.pin(settings::selection, lookup::resolve) { fallback }.address
            val standby = async(start = CoroutineStart.UNDISPATCHED) {
                snapshot.prefetch(settings::selection, lookup::resolve)
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                advanceTimeBy(600)
                val first = async(start = CoroutineStart.UNDISPATCHED) { if (bluetoothFirst) bluetooth() else airplay() }
                val other = async(start = CoroutineStart.UNDISPATCHED) { if (bluetoothFirst) airplay() else bluetooth() }
                if (!bluetoothFirst) settings.edit(manual)
                advanceTimeBy(1_400)
                runCurrent()
                assertTrue(first.isCompleted && other.isCompleted)
                assertEquals(if (bluetoothFirst) fallback else manual, first.await())
                assertEquals(first.await(), other.await())
                standby.await()
                assertEquals(if (bluetoothFirst) null else manual, saved)
                // A full new connection from the same peer may start before the old Binder call returns.
                snapshot.invalidate()
                settings.edit(manual)
                assertEquals(manual, if (bluetoothFirst) airplay() else bluetooth())
            } finally {
                release.countDown()
            }
            assertTrue(returned.await(3, TimeUnit.SECONDS))
            assertEquals(manual, saved)
            assertEquals(manual, bluetooth())
            assertEquals(manual, airplay())
            assertEquals(1, reads)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun handshakeRetryUsesOnlyTheBudgetLeftByStandby() = runTest {
        val snapshot = PhoneIdentitySnapshot(nowMillis = { testScheduler.currentTime })
        val settings = SavedPhoneMac({ null }, {})
        val release = CompletableDeferred<Unit>()
        var queries = 0
        val lookup: suspend () -> String? = {
            if (++queries == 1) {
                delay(650)
                null
            } else {
                release.await()
                old
            }
        }
        suspend fun pin() = snapshot.pin(settings::selection, lookup) { fallback }
        val standby = async(start = CoroutineStart.UNDISPATCHED) { snapshot.prefetch(settings::selection, lookup) }
        advanceTimeBy(250)
        val first = async(start = CoroutineStart.UNDISPATCHED) { pin() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { pin() }
        advanceTimeBy(1_749)
        assertFalse(first.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        val metDeadline = first.isCompleted && second.isCompleted
        release.complete(Unit)
        assertTrue("queued transports still waiting after shared deadline", metDeadline)
        assertNull(standby.await())
        assertSame(first.await(), second.await())
        assertEquals(fallback, first.await().address)
        assertEquals(2, queries)
    }

    @Test fun newInvitationCanPinWhilePreviousStandbyStillOwnsItsLookup() = runTest {
        var saved: String? = null
        val settings = SavedPhoneMac({ saved }, { saved = it })
        val snapshot = PhoneIdentitySnapshot(rememberHardwareMac = { rev, mac -> settings.remember(rev, mac) })
        val release = CompletableDeferred<Unit>()
        val previous = async(start = CoroutineStart.UNDISPATCHED) {
            snapshot.prefetch(settings::selection) { release.await(); old }
        }
        snapshot.invalidate()
        val newPin = snapshot.pin(settings::selection, { null }) { fallback }
        release.complete(Unit)
        try {
            previous.await()
            fail("invalidated lookup published a result")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The retired generation has no authority to save or pin the result.
        }
        assertNull(saved)
        assertEquals(fallback, newPin.address)
        assertSame(newPin, snapshot.pin(settings::selection, { old }) { fallback })
    }

    @Test fun clearedStandbyLookupIsRetriedAndOnlyTheNewConnectionAdoptsLaterEdits() = runTest {
        var saved: String? = null
        val settings = SavedPhoneMac({ saved }, { saved = it })
        val snapshot = PhoneIdentitySnapshot(rememberHardwareMac = { rev, mac -> settings.remember(rev, mac) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = "84:AE:DE:56:D3:78"
        var queries = 0
        val lookup: suspend () -> String? = {
            if (++queries == 1) {
                entered.complete(Unit)
                release.await()
                old
            } else fresh
        }
        suspend fun pin() = snapshot.pin(settings::selection, lookup) { fallback }
        val standby = async { snapshot.prefetch(settings::selection, lookup) }
        entered.await()
        settings.edit(null)
        release.complete(Unit)
        assertNull(standby.await())
        assertNull(saved)
        val connected = pin()
        assertEquals(fresh, connected.address)
        assertEquals(fresh, saved)
        settings.edit(manual)
        assertSame(connected, pin())
        snapshot.invalidate()
        assertEquals(manual, pin().address)
        assertEquals(2, queries)
    }

    @Test fun lateShellReplyCannotBecomeTheIdentityOfAReinvitation() = runTest {
        for (edit in listOf(manual, null)) for (bluetoothFirst in listOf(true, false)) {
            var saved: String? = null
            val settings = SavedPhoneMac({ saved }, { saved = it })
            val snapshot = PhoneIdentitySnapshot(rememberHardwareMac = { rev, mac -> settings.remember(rev, mac) })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var queries = 0
            val lookup: suspend () -> String? = {
                if (++queries == 1) {
                    entered.complete(Unit)
                    release.await()
                    old
                } else null // Shizuku unavailable on the next connection.
            }
            suspend fun airplay() = snapshot.pin(settings::selection, lookup) { fallback }
            suspend fun bluetooth() = airplay().address
            val pending = async { airplay() }
            entered.await()
            settings.edit(edit)
            release.complete(Unit)
            val initial = pending.await()
            assertEquals(edit, saved)
            assertEquals(edit ?: fallback, initial.address)
            assertSame(initial, airplay()) // Media engine restart, same link.
            if (edit == null) assertFalse(settings.remember(initial.settingsRevision, initial.address))
            snapshot.invalidate()
            val addresses = if (bluetoothFirst) listOf(bluetooth(), airplay().address)
                else listOf(airplay().address, bluetooth())
            assertEquals(List(2) { edit ?: fallback }, addresses)
            assertEquals(if (edit == null) 2 else 1, queries)
            assertEquals(edit, saved)
        }
    }
}
