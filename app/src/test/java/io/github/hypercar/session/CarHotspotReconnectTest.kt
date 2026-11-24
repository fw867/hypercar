package io.github.hypercar.session

import io.github.hypercar.iap2.AirplayRouteBinding
import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.iap2.Iap2EndpointGeneration
import io.github.hypercar.iap2.Iap2Messages
import io.github.hypercar.net.BluetoothHotspot
import io.github.hypercar.net.CarWifiRequestIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHotspotReconnectTest {
    private val receiver = "02:11:22:33:44:AA"
    private val other = "02:11:22:33:44:BB"

    @Test
    fun manualReconnectToASelectedPairedCarMayRestoreWifi() {
        assertNull(CarHotspotReconnect.credentialBlockReason(receiver, receiver.lowercase(), paired = true, desktop = false))
    }

    @Test
    fun desktopReceiversKeepTheExistingPath() {
        assertEquals("desktop_receiver", CarHotspotReconnect.credentialBlockReason(receiver, receiver, paired = true, desktop = true))
    }

    @Test
    fun automaticRestoreNeedsARecentAclForTheSelectedReceiver() {
        val acl = CarHotspotReconnect.AclTrigger()
        assertNull(acl.candidate(receiver, 1_000))
        acl.connected(receiver, 1_000)
        assertNull(acl.candidate(null, 1_001))
        assertNull(acl.candidate(other, 1_002))
        assertNull(acl.candidate(receiver, 999))
        assertNotNull(acl.candidate(receiver.lowercase(), 1_003))
    }

    @Test
    fun duplicateAclCannotSpendAnotherCacheAttemptUntilTheNextConnection() {
        val acl = CarHotspotReconnect.AclTrigger()
        acl.connected(receiver, 1_000)
        val edge = checkNotNull(acl.candidate(receiver, 1_000))
        assertTrue(acl.submit(edge, 1_000))
        acl.connected(receiver.lowercase(), 1_050)
        assertNull(acl.candidate(receiver, 1_050))
        acl.disconnected(other)
        assertTrue(acl.isCurrent(edge))
        acl.disconnected(receiver)
        assertFalse(acl.isCurrent(edge))
        acl.connected(receiver, 1_100)
        assertNotNull(acl.candidate(receiver, 1_100))
    }

    @Test
    fun expiredOrClearedAclCannotWakeCachedWifi() {
        val acl = CarHotspotReconnect.AclTrigger()
        acl.connected(receiver, 1_000)
        assertNull(acl.candidate(receiver, 61_000))
        acl.disconnected(receiver)
        acl.connected(receiver, 62_000)
        acl.disconnected()
        assertNull(acl.candidate(receiver, 62_000))
    }

    @Test
    fun newAclCanRetryCacheWhileTheSameAutomaticAttemptIsWaiting() {
        val acl = CarHotspotReconnect.AclTrigger()
        val attempt = CarHotspotReconnect.RestoreAttempt(acl)
        acl.connected(receiver, 1_000)
        val first = checkNotNull(attempt.candidate(receiver, 1_000)?.acl)
        acl.disconnected(receiver) // The first edge drops before the grace period finishes.
        assertFalse(acl.isCurrent(first))
        assertNull(attempt.candidate(receiver, 1_500))
        acl.connected(receiver, 1_600)
        val second = checkNotNull(attempt.candidate(receiver, 1_600))
        assertTrue(attempt.submit(second, 1_600))
        acl.connected(receiver.lowercase(), 1_610)
        assertNull(attempt.candidate(receiver, 1_610))

        val manual = CarHotspotReconnect.RestoreAttempt(null)
        assertNotNull(manual.candidate(receiver, 1_000))
        assertNull(manual.candidate(receiver, 1_600))
    }

    @Test fun filteredAclDisconnectReleasesCacheTriggerWithoutForwardingSessionTeardown() {
        val acl = CarHotspotReconnect.AclTrigger()
        acl.connected(receiver, 100)
        val old = checkNotNull(acl.candidate(receiver, 100))
        assertTrue(acl.submit(old, 100))
        assertFalse(acl.onDisconnect(receiver, profileDrop = true))
        assertFalse(acl.isCurrent(old))
        acl.connected(receiver, 200)
        assertNotNull(acl.candidate(receiver, 200))
        assertTrue(acl.onDisconnect(receiver, profileDrop = false))
    }

    @Test fun blockedRestoreDoesNotSpendTheNewAclUntilAndroidSubmission() {
        val acl = CarHotspotReconnect.AclTrigger()
        val attempt = CarHotspotReconnect.RestoreAttempt(acl)
        acl.connected(receiver, 100)
        val old = checkNotNull(attempt.candidate(receiver, 100))
        assertTrue(attempt.submit(old, 100))
        acl.onDisconnect(receiver, profileDrop = true)
        acl.connected(receiver, 200)
        val new = checkNotNull(attempt.candidate(receiver, 200))
        // The previous hotspot remains bound, so the request is skipped before submission.
        assertFalse(CarHotspotReconnect.canReplaceBinding(1, null, "car", "car"))
        assertSame(new.acl, attempt.candidate(receiver, 1_000)?.acl)
        assertFalse(attempt.submit(old, 1_000))
        assertTrue(CarHotspotReconnect.canReplaceBinding<Int>(null, null, null, "car"))
        assertTrue(attempt.submit(new, 1_001))
        assertFalse(attempt.submit(new, 1_001))
        assertNull(attempt.candidate(receiver, 1_001))
    }

    @Test fun synchronousAndroidRejectionReturnsOnlyTheSubmittingAclOpportunity() {
        val acl = CarHotspotReconnect.AclTrigger()
        val attempt = CarHotspotReconnect.RestoreAttempt(acl)
        acl.connected(receiver, 100)
        val first = checkNotNull(attempt.candidate(receiver, 100))
        assertTrue(runCatching {
            attempt.submit(first, 1_000) { throw IllegalStateException("Android rejected") }
        }.isFailure)
        assertSame(first.acl, attempt.candidate(receiver, 1_001)?.acl)
        assertTrue(runCatching {
            attempt.submit(first, 1_002) {
                acl.onDisconnect(receiver, profileDrop = true)
                acl.connected(receiver, 1_003)
                val next = checkNotNull(attempt.candidate(receiver, 1_003))
                check(attempt.submit(next, 1_003))
                throw IllegalStateException("old submission rejected")
            }
        }.isFailure)
        assertNull(attempt.candidate(receiver, 1_004))
    }

    @Test fun delayedLossFromTheOldRequestCannotStopTheSameAttemptAfterPasswordChange() {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val oldPassword = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        var oldRequestIsCurrent = true
        val mayStop = { inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = true) { oldRequestIsCurrent } }
        assertTrue(mayStop())
        inbox.offer(CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot))
        assertEquals("new-passphrase", inbox.poll()?.passphrase)
        assertFalse(mayStop())
        oldRequestIsCurrent = false
        assertFalse(inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = false) { oldRequestIsCurrent })
        assertTrue(inbox.canStopAfterWifiLoss(CarWifiRequestIdentity(hotspot.ssid, "new-passphrase"),
            replacementAllowed = true) { true })
    }

    @Test fun nativeAdoptionAndCredentialPublicationShareOneCommitLock() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val boundary = Any()
        val inbox = CarHotspotReconnect.EndpointInbox(boundary, hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val readyToPublish = java.util.concurrent.CountDownLatch(1)
        val published = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            readyToPublish.countDown()
            inbox.offer(CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
                bluetoothHotspot = hotspot))
            published.countDown()
        }
        handOffControlSocket(Mutex(), boundary, closeUnclaimed = { error("adopted socket closed") },
            checkCurrent = { check(!inbox.hasPendingCredentials()) }, prepare = {},
            start = { _, transfer ->
                transfer {
                    assertTrue(Thread.holdsLock(boundary))
                    writer.start()
                    assertTrue(readyToPublish.await(1, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(1L, published.count)
                }
            })
        assertTrue(published.await(1, java.util.concurrent.TimeUnit.SECONDS))
        writer.join(1_000)
        assertEquals("new-passphrase", inbox.poll()?.passphrase)
    }

    @Test fun lossAfterNativeHandoffRejectsPendingVideoActivationAndKeepsNewCredentials() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val oldPassword = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        val newEndpoint = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val starting = ProactiveActivation()
        assertTrue(inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = false, activation = starting) { true })
        inbox.offer(newEndpoint)
        assertFalse(inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = false, activation = starting) { false })
        assertFalse(starting.result.isCompleted)

        assertFalse(inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = false, activation = starting) { true })
        assertTrue(starting.result.isCompleted)
        starting.videoReady()
        assertNotNull(starting.result.await())
        assertTrue(starting.requiresFreshInvitation)
        assertSame(newEndpoint, inbox.poll())

        val ready = ProactiveActivation().apply { videoReady() }
        assertTrue(inbox.canStopAfterWifiLoss(oldPassword, replacementAllowed = false, activation = ready) { true })
        assertNull(ready.result.await())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun lossBeforeCredentialsWaitsWithinTheSameActivationRecoveryWindow() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val old = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        val oldEndpoint = CarPlayEndpoint(hotspot.ssid, "old-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val newEndpoint = oldEndpoint.copy(passphrase = "new-passphrase")
        val inbox = CarHotspotReconnect.EndpointInbox(
            nowMillis = { testScheduler.currentTime }, hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val activation = ProactiveActivation()
        assertTrue(activation.interceptWifiLoss(old, 100))
        val loss = checkNotNull(activation.wifiLoss)
        assertTrue(activation.interceptWifiLoss(old, 200))
        assertSame(loss, activation.wifiLoss)
        assertNotNull(activation.result.await())

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            inbox.awaitReplacementCredentials(loss.credentials, 1_000)
        }
        inbox.offer(oldEndpoint)
        kotlinx.coroutines.delay(200)
        assertFalse(waiting.isCompleted)
        inbox.offer(newEndpoint)
        assertSame(newEndpoint, waiting.await())
        assertSame(newEndpoint, inbox.newestCredentials(oldEndpoint))

        val empty = CarHotspotReconnect.EndpointInbox(
            nowMillis = { testScheduler.currentTime }, hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        empty.offer(oldEndpoint)
        assertNull(empty.awaitReplacementCredentials(loss.credentials, 1_200))
        assertEquals(1_200L, testScheduler.currentTime)
        assertFalse(ProactiveActivation().apply { videoReady() }.interceptWifiLoss(old, 100))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun beforeDeadlinePublicationSurvivesDelayedWakeAndTimeoutClosesOnlyTheExpiredInbox() =
        kotlinx.coroutines.test.runTest {
            val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
            val old = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
            val fresh = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
                bluetoothHotspot = hotspot)
            var elapsed = 0L
            fun inbox() = CarHotspotReconnect.EndpointInbox(
                nowMillis = { elapsed }, hasFreshCredentials = { it.canRequestHotspot(hotspot) })
            val timely = inbox()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                timely.awaitReplacementCredentials(old, 1_000)
            }
            // Enqueue before the deadline clock ticks, with the waiter already delayed until the
            // scheduler has both the timeout and the channel continuation ready to run.
            testScheduler.advanceTimeBy(1_000)
            elapsed = 999
            timely.offer(fresh)
            elapsed = 1_001
            testScheduler.runCurrent()
            assertSame(fresh, waiting.await())
            assertSame(fresh, timely.poll())

            val expired = inbox()
            val deadline = elapsed + 1_000
            val timingOut = async(start = CoroutineStart.UNDISPATCHED) {
                expired.awaitReplacementCredentials(old, deadline)
            }
            testScheduler.advanceTimeBy(1_000)
            elapsed = deadline + 1
            expired.offer(fresh)
            testScheduler.runCurrent()
            assertNull(timingOut.await())
            expired.offer(fresh)
            assertNull(expired.poll())
        }

    @Test fun recoveryUsesEnqueueValidityEvenAfterAnotherSsidIsAnnounced() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val otherHotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI-NEXT"))
        val fresh = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val late = fresh.copy(ssid = otherHotspot.ssid, bluetoothHotspot = otherHotspot)
        var current = hotspot
        var elapsed = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasFreshCredentials = { it.canRequestHotspot(current) })
        val lost = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        inbox.beginWifiRecovery(lost, 12_000)
        // A credential rejected at enqueue cannot become timely when the announced SSID changes.
        inbox.offer(fresh.copy(bluetoothHotspot = BluetoothHotspot.fromPeer(other, hotspot.ssid)))
        inbox.offer(fresh)
        inbox.offer(late)
        elapsed = 12_001L
        var lateCommitted = false
        inbox.offer(late) {
            current = otherHotspot
            lateCommitted = true
        }

        assertSame(fresh, inbox.awaitReplacementCredentials(lost, 12_000))
        assertTrue(inbox.isAccepting())
        assertFalse(lateCommitted)
        assertSame(fresh, inbox.newestCredentials(fresh))
        inbox.finishRecoveryJoin()
        assertTrue(lateCommitted)
        assertSame(late, inbox.newestCredentials(fresh))
    }

    @Test fun recoveryKeepsTheTimelyPasswordWhenOldCredentialsArriveOnEitherSideOfSelection() =
        kotlinx.coroutines.test.runTest {
            val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
            val old = CarPlayEndpoint(hotspot.ssid, "old-passphrase", 0, null, null, null, emptyList(), null,
                bluetoothHotspot = hotspot)
            val fresh = old.copy(passphrase = "new-passphrase")
            var elapsed = 11_998L
            val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
                hasFreshCredentials = { it.canRequestHotspot(hotspot) })
            inbox.offer(fresh)
            elapsed++
            inbox.offer(old)
            elapsed = 12_001L
            inbox.offer(old)

            val selected = inbox.awaitReplacementCredentials(CarWifiRequestIdentity(hotspot.ssid, "old-passphrase"), 12_000)
            assertSame(fresh, selected)
            var published = false
            inbox.offer(old) { published = true }
            assertFalse(published)
            assertSame(fresh, inbox.newestCredentials(old))
            assertSame(fresh, inbox.poll())
            assertNull(inbox.poll())
        }

    @Test fun recoveryWithoutALostPasswordKeepsTheSelectedNetworkThroughInvitation() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val old = CarPlayEndpoint(hotspot.ssid, "old-passphrase", 7000, null, null, null, listOf("192.168.5.1"), null,
            bluetoothHotspot = hotspot)
        val fresh = old.copy(passphrase = "new-passphrase")
        var elapsed = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        inbox.beginWifiRecovery(lost = null, deadlineMillis = 12_000)
        assertTrue(inbox.offer(fresh))
        elapsed = 12_001
        assertFalse(inbox.offer(old) { throw AssertionError("late report committed before selection") })

        assertSame(fresh, inbox.awaitReplacementCredentials(null, 12_000))
        assertSame(fresh, inbox.poll())
        assertFalse(inbox.offer(old) { throw AssertionError("late report committed after selection") })
        assertFalse(inbox.credentialsChanged { it.passphrase == fresh.passphrase })
        assertEquals("invited", inbox.whenCredentialsDrained { "invited" })
        inbox.finishRecovery()
        assertTrue(inbox.offer(old))
    }

    @Test fun addressOnly4301WaitsWithTheDeferredCredentialsAndForTheirBinding() = kotlinx.coroutines.test.runTest {
        val first = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val second = checkNotNull(BluetoothHotspot.fromPeer(receiver, "NEXT-WIFI"))
        val selected = CarPlayEndpoint(first.ssid, "selected-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = first)
        val deferred = selected.copy(ssid = second.ssid, passphrase = "deferred-passphrase", bluetoothHotspot = second)
        val report = selected.copy(ipAddresses = listOf("192.168.6.2"), airPlayPort = 7300,
            requiresBoundAirplayRoute = true, addressOnlyAirplayReport = true, airplayRouteBinding = AirplayRouteBinding())
        var time = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time }, hasFreshCredentials = { it.passphrase != null })
        val lost = CarWifiRequestIdentity(first.ssid, "lost-passphrase")
        inbox.beginWifiRecovery(lost, 12_000)
        assertTrue(inbox.offer(selected))
        time = 12_001
        var committed = false
        assertFalse(inbox.offer(deferred) { committed = true })
        var releasedRoute: CarPlayEndpoint? = null
        repeat(20) { index ->
            assertFalse(inbox.offer(report.copy(airPlayPort = 7000 + index), onDeferredAddress = { releasedRoute = it }))
        }
        assertFalse(inbox.offer(report, onDeferredAddress = { releasedRoute = it }) {
            throw AssertionError("address-only report committed with the old credential")
        })
        assertSame(selected, inbox.awaitReplacementCredentials(lost, 12_000))
        assertSame(selected, inbox.newestCredentials(selected))
        assertFalse(committed)
        assertNull(releasedRoute)
        inbox.finishRecoveryJoin()
        assertTrue(committed)

        val route = checkNotNull(releasedRoute)
        assertSame(route, inbox.newestCredentials(selected))
        assertEquals(deferred.ssid, route.ssid)
        assertEquals(deferred.passphrase, route.passphrase)
        assertEquals(7300, route.airPlayPort)
        assertNull(route.verifiedAirplayEndpoint(second.ssid, 11L, hasMatchingCredentials = false) { true })
        val verified = checkNotNull(route.verifiedAirplayEndpoint(second.ssid, 12L, hasMatchingCredentials = true) { true })
        assertEquals(listOf("192.168.6.2"), verified.ipAddresses)
        assertEquals(12L, verified.verifiedAirplayNetworkHandle)
    }

    @Test fun verifiedAddressOnly4301KeepsItsOwnerWhileAnotherCredentialIsDeferred() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val selected = CarPlayEndpoint(hotspot.ssid, "selected-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val deferred = selected.copy(passphrase = "deferred-passphrase")
        val route = selected.copy(ipAddresses = listOf("192.168.5.2"), airPlayPort = 7200, addressOnlyAirplayReport = true,
            requiresBoundAirplayRoute = true, airplayRouteBinding = AirplayRouteBinding())
        val verified = checkNotNull(route.verifiedAirplayEndpoint(hotspot.ssid, 11L) { true })
        var time = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time }, hasFreshCredentials = { it.passphrase != null })
        val lost = CarWifiRequestIdentity(hotspot.ssid, "lost-passphrase")
        inbox.beginWifiRecovery(lost, 12_000)
        assertTrue(inbox.offer(selected))
        time = 12_001
        assertFalse(inbox.offer(deferred))
        assertTrue(inbox.offer(verified, onDeferredAddress = { throw AssertionError("a verified route was moved to deferred credentials") }))
        assertSame(selected, inbox.awaitReplacementCredentials(lost, 12_000))
        assertSame(verified, inbox.newestCredentials(selected))
        inbox.finishRecoveryJoin()
        assertSame(deferred, inbox.newestCredentials(selected))
        assertNull(verified.verifiedAirplayEndpoint(hotspot.ssid, 12L) { true })
    }

    @Test fun unknownOldPasswordPinsTheFirstValidCredentialsBeforeTheDeadline() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val fresh = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val old = fresh.copy(passphrase = "old-passphrase", airPlayPort = 7200, ipAddresses = listOf("192.168.5.2"))
        var elapsed = 200L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        inbox.beginWifiRecovery(lost = null, deadlineMillis = 12_000)
        assertTrue(inbox.offer(fresh.copy(bluetoothHotspot = null)))
        assertTrue(inbox.offer(fresh))
        elapsed++
        assertFalse(inbox.offer(old) { throw AssertionError("conflicting credentials committed before the waiter ran") })
        assertSame(fresh, inbox.awaitReplacementCredentials(null, 12_000))
        assertSame(fresh, inbox.newestCredentials(old))
    }

    @Test fun deferredCredentialsDoNotCarryPreviouslyVerifiedAddressesAtCommit() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val selected = CarPlayEndpoint(hotspot.ssid, "selected-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val deferred = selected.copy(passphrase = "deferred-passphrase", airPlayPort = 7200, ipAddresses = listOf("192.168.5.2"),
            requiresBoundAirplayRoute = true, airplayRouteBinding = AirplayRouteBinding())
        val verified = checkNotNull(deferred.verifiedAirplayEndpoint(hotspot.ssid, 11L) { true })
        var time = 11_999L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time }, hasFreshCredentials = { it.passphrase != null })
        val lost = CarWifiRequestIdentity(hotspot.ssid, "lost-passphrase")
        inbox.beginWifiRecovery(lost, 12_000)
        inbox.offer(selected)
        time++
        time++
        var committed: CarPlayEndpoint? = null
        assertFalse(inbox.offer(verified, onDeferredAccept = { committed = it }))
        assertSame(selected, inbox.awaitReplacementCredentials(lost, 12_000))
        inbox.finishRecoveryJoin()

        val actual = checkNotNull(committed)
        assertEquals(verified.passphrase, actual.passphrase)
        assertTrue(actual.ipAddresses.isEmpty())
        assertEquals(0, actual.airPlayPort)
        assertSame(actual, inbox.newestCredentials(selected))
    }

    @Test fun recoveryRejectsAQueued4301ThatWasResolvedFromTheLostPassword() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val lost = CarWifiRequestIdentity(hotspot.ssid, "lost-passphrase")
        val selected = CarPlayEndpoint(hotspot.ssid, "selected-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val old4301 = selected.copy(airPlayPort = 7200, ipAddresses = listOf("192.168.5.2"),
            replayedWifiConfiguration = Iap2Messages.WifiConfiguration(hotspot.ssid, "lost-passphrase", 6, 2))
        var time = 100L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time }, hasFreshCredentials = { it.passphrase != null })
        inbox.offer(selected)
        time++
        inbox.offer(old4301)
        inbox.beginWifiRecovery(lost, 12_000)
        assertFalse(inbox.offer(old4301) { throw AssertionError("old 0x4301 was published after loss") })
        assertSame(selected, inbox.awaitReplacementCredentials(lost, 12_000))
        assertSame(selected, inbox.poll())
        assertNull(inbox.poll())
    }

    @Test fun aSecondLossCannotSelectARetiredCredentialStillQueuedFromTheFirstRecovery() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val first = CarPlayEndpoint(hotspot.ssid, "first-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val second = first.copy(passphrase = "second-passphrase")
        val latest = first.copy(passphrase = "latest-passphrase")
        var time = 11_999L
        var retired: String? = null
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { time }, isRetired = { it.passphrase == retired },
            hasFreshCredentials = { it.passphrase != null })
        val lost = CarWifiRequestIdentity(hotspot.ssid, "original-passphrase")
        inbox.beginWifiRecovery(lost, 12_000)
        inbox.offer(first)
        time = 12_001
        inbox.offer(second)
        assertSame(first, inbox.awaitReplacementCredentials(lost, 12_000))
        inbox.finishRecoveryJoin { retired = first.passphrase; true }
        val nextLoss = CarWifiRequestIdentity(hotspot.ssid, "second-passphrase")
        inbox.beginWifiRecovery(nextLoss, 24_000)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { inbox.awaitReplacementCredentials(nextLoss, 24_000) }
        assertFalse(waiting.isCompleted)
        assertFalse(inbox.offer(first))
        assertTrue(inbox.offer(latest))
        assertSame(latest, waiting.await())
    }

    @Test fun credentialsArrivingWhileHandoffWaitsForSessionLockCloseTheConnectedSocket() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val lock = Mutex(locked = true)
        var closed = 0
        var started = false
        val handoff = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                handOffControlSocket(lock, Any(), closeUnclaimed = { closed++ }, checkCurrent = {
                    if (inbox.credentialsChanged { it.passphrase == "old-passphrase" }) {
                        throw CancellationException("stale credentials")
                    }
                }, prepare = { 41 }, start = { _, transfer -> transfer { started = true } })
            }
        }
        inbox.offer(CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot))
        lock.unlock()
        assertTrue(handoff.await().exceptionOrNull() is CancellationException)
        assertFalse(started)
        assertEquals(1, closed)
    }

    @Test fun credentialsUpdatedWhilePreparingNativeConfigAreCheckedAtTransfer() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        var closed = 0
        var started = false
        val result = runCatching {
            handOffControlSocket(Mutex(), Any(), closeUnclaimed = { closed++ }, checkCurrent = {
                if (inbox.credentialsChanged { it.passphrase == "old-passphrase" }) {
                    throw CancellationException("stale credentials")
                }
            }, prepare = {
                inbox.offer(CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
                    bluetoothHotspot = hotspot))
            }, start = { _, transfer -> transfer { started = true } })
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(started)
        assertEquals(1, closed)
    }

    @Test
    fun aCacheNeedsAnExplicitTargetAndMatchingBluetoothAndCarPlayPairing() {
        assertEquals("target_not_selected", CarHotspotReconnect.credentialBlockReason(null, receiver, paired = true, desktop = false))
        assertEquals("bluetooth_not_bonded", CarHotspotReconnect.credentialBlockReason(receiver, null, paired = true, desktop = false))
        assertEquals("bluetooth_not_bonded", CarHotspotReconnect.credentialBlockReason(receiver, other, paired = true, desktop = false))
        assertEquals("carplay_not_paired", CarHotspotReconnect.credentialBlockReason(receiver, receiver, paired = false, desktop = false))
    }

    @Test
    fun automaticCachedWifiWaitsForAnInvitationWhenIap2OnlyReportsAnAddress() {
        assertFalse(CarHotspotReconnect.canUseIap2Activation(automaticProvisionalWifi = true, freshCredentials = false))
        assertTrue(CarHotspotReconnect.canUseIap2Activation(automaticProvisionalWifi = true, freshCredentials = true))
        assertTrue(CarHotspotReconnect.canUseIap2Activation(automaticProvisionalWifi = false, freshCredentials = false))
    }

    @Test
    fun cachingRequiresAnOwnedProtectedLocalNetworkFromTheCurrentBluetoothPeer() {
        assertTrue(CarHotspotReconnect.canRemember("test-password", true, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("", true, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", false, true, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, false, true, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, true, false, desktop = false))
        assertFalse(CarHotspotReconnect.canRemember("test-password", true, true, true, desktop = true))
    }

    @Test
    fun discoveryStartedBeforeCachedJoinCannotAdoptItsNetworkDuringOrAfterCancellation() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        val discovery = handoff.discoveryToken()
        assertTrue(handoff.begin(request, receiver))
        assertTrue(handoff.isJoining(request))
        assertNull(handoff.discoveryToken())
        assertFalse(handoff.canCommitDiscovery(discovery))

        handoff.finishJoining(request)
        assertFalse(handoff.canCommitDiscovery(discovery))
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun losingProvisionalNetworkOnlyAffectsItsOwnRequestAndAllowsDiscoveryAfterCleanup() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        handoff.bind(request, 4)
        handoff.finishJoining(request)
        assertSame(request, handoff.provisionalRequest(4))
        assertNull(handoff.provisionalRequest(5))
        assertNull(handoff.discoveryToken())
        handoff.clear(Any())
        assertSame(request, handoff.provisionalRequest(4))
        handoff.clear(request)
        assertNull(handoff.provisionalRequest(4))
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun acceptedActivationPromotesCachedNetworkToNormalLossHandling() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        handoff.bind(request, 4)
        handoff.confirm(5)
        assertSame(request, handoff.provisionalRequest(4))
        handoff.confirm(4)
        assertNull(handoff.provisionalRequest(4))
        assertSame(request, handoff.cachedRequest)
        assertTrue(handoff.canCommitDiscovery(handoff.discoveryToken()))
    }

    @Test
    fun switchingTargetsInvalidatesCachedWifiUntilCarPlayHasConnected() {
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        assertFalse(handoff.matchesTarget(other, established = false))
        handoff.bind(request, 4)
        assertTrue(handoff.matchesTarget(receiver.lowercase(), established = false))
        assertFalse(handoff.matchesTarget(null, established = false))
        handoff.confirm(4)
        assertFalse(handoff.matchesTarget(other, established = false))
        assertTrue(handoff.matchesTarget(other, established = true))
        handoff.clear(request)
        assertTrue(handoff.matchesTarget(other, established = false))
    }

    @Test
    fun addressOnlyEndpointWaitsForCachedWifiOrLiveCredentials() {
        assertFalse(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = false, freshCredentials = false))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = true, freshCredentials = false))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = true, carNetworkReady = false, freshCredentials = true))
        assertTrue(CarHotspotReconnect.canUseEndpoint(hasCache = false, carNetworkReady = false, freshCredentials = false))
    }

    @Test
    fun addressOnly4301WaitsForQueued5703EvenWhenCacheWasSkipped() {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val addressOnly = CarPlayEndpoint(null, null, 7000, null, null, null, listOf("192.168.5.1"), null)
        val liveWifi = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        inbox.offer(addressOnly)
        inbox.offer(liveWifi)

        assertSame(addressOnly, inbox.poll())
        assertFalse(inbox.canUseEndpoint(hasCache = false, carNetworkReady = false, freshCredentials = false))
        assertFalse(inbox.canUseEndpoint(hasCache = false, carNetworkReady = true, freshCredentials = false))
        assertSame(liveWifi, inbox.poll())
        assertTrue(inbox.canUseEndpoint(hasCache = false, carNetworkReady = false, freshCredentials = true))
    }

    @Test
    fun credentialsArrivingAfterAnEmptyPollPrecedeCachedInvitationConfirmation() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val handoff = CarHotspotReconnect.Handoff<Any, Int>()
        val request = Any()
        assertTrue(handoff.begin(request, receiver))
        handoff.bind(request, 4)
        assertNull(inbox.receive(1))

        val liveWifi = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        inbox.offer(liveWifi)
        assertNull(inbox.whenCredentialsDrained { handoff.confirm(4); "confirm" })
        assertSame(request, handoff.provisionalRequest(4))

        assertSame(liveWifi, inbox.poll())
        assertEquals("confirm", inbox.whenCredentialsDrained { handoff.confirm(4); "confirm" })
        assertNull(handoff.provisionalRequest(4))
    }

    @Test fun credentialsArrivingAfterInvitationSelectionStillBlockTheOldWifiDial() = runBlocking {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val oldRequest = CarWifiRequestIdentity(hotspot.ssid, "cached-password")
        val newer = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        assertEquals("selected", inbox.whenCredentialsDrained { "selected" })
        assertFalse(inbox.credentialsChanged { oldRequest.matches(it.ssid, it.passphrase) })
        var opened = false
        val connector = AirplayEndpointConnector(
            current = { !inbox.credentialsChanged { oldRequest.matches(it.ssid, it.passphrase) } },
            now = { 0L },
            open = { opened = true; 41 },
            close = { _: Int -> },
            invalidate = {},
            updates = { inbox.offer(newer); emptyList() },
            discover = { emptyList() },
            pause = {},
        )
        val result = runCatching { connector.connect(listOf(AirplayTarget("192.168.5.1", 7000)), 1_000) }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(opened)
        assertSame(newer, inbox.poll())
        assertFalse(inbox.credentialsChanged { oldRequest.matches(it.ssid, it.passphrase) })
    }

    @Test fun newestPasswordForSameSsidReplacesTheAlreadyAppliedRequest() {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val old = CarPlayEndpoint(hotspot.ssid, "old-passphrase", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot)
        val new = old.copy(passphrase = "new-passphrase")
        val newest = old.copy(passphrase = "most-recent-passphrase")
        val applied = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        inbox.offer(old)
        val current = checkNotNull(inbox.poll())
        inbox.offer(new)
        inbox.offer(newest)
        val latest = inbox.newestCredentials(current)
        assertSame(newest, latest)
        assertTrue(applied.requiresCredentialUpdate(latest.ssid, latest.passphrase))
        assertFalse(applied.requiresCredentialUpdate(current.ssid, current.passphrase))
        assertFalse(applied.requiresCredentialUpdate(current.ssid, null))
    }

    @Test fun retiredJoinRetriesBeforeAndAfterSameCredentialsAreReannounced() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val source = Iap2EndpointGeneration()
        val obsolete = CarPlayEndpoint(hotspot.ssid, "new-passphrase", 7000, null, null, null,
            listOf("192.168.5.1"), null, bluetoothHotspot = hotspot, sourceGeneration = source)
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        inbox.offer(obsolete)
        assertSame(obsolete, inbox.poll())
        assertFalse(inbox.shouldRetryJoin(obsolete))

        source.retire()
        assertTrue(inbox.shouldRetryJoin(obsolete))
        assertNull(inbox.poll())
        val refreshed = obsolete.copy(sourceGeneration = Iap2EndpointGeneration())
        assertTrue(inbox.offer(refreshed))
        assertTrue(inbox.shouldRetryJoin(obsolete))
        assertSame(refreshed, inbox.poll())
        assertFalse(inbox.shouldRetryJoin(refreshed))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun retiredRecoveryJoinRetainsLossProtectionWhileWaitingForTheNewSource() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val source = Iap2EndpointGeneration()
        val old = CarPlayEndpoint(hotspot.ssid, "old-passphrase", 0, null, null, null,
            emptyList(), null, bluetoothHotspot = hotspot)
        val replacement = old.copy(passphrase = "new-passphrase", sourceGeneration = source)
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { testScheduler.currentTime },
            hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        val lost = CarWifiRequestIdentity(hotspot.ssid, "old-passphrase")
        inbox.beginWifiRecovery(lost, 1_000)
        assertTrue(inbox.offer(replacement))
        assertSame(replacement, inbox.awaitReplacementCredentials(lost, 1_000))
        source.retire()
        inbox.finishRecoveryJoin()

        assertTrue(inbox.shouldRetryJoin(replacement))
        assertFalse(inbox.offer(old))
        assertNull(inbox.poll())
        kotlinx.coroutines.delay(1_100)
        val refreshed = replacement.copy(sourceGeneration = Iap2EndpointGeneration())
        assertTrue(inbox.offer(refreshed))
        assertSame(refreshed, inbox.poll())
    }

    @Test fun unknownPasswordRecoveryReleasesARetiredPinToTheNewSource() = kotlinx.coroutines.test.runTest {
        val source = Iap2EndpointGeneration()
        val firstHotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-OLD"))
        val nextHotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-NEW"))
        val first = CarPlayEndpoint(firstHotspot.ssid, "first-password", 0, null, null, null,
            emptyList(), null, bluetoothHotspot = firstHotspot, sourceGeneration = source)
        var elapsed = 100L
        var announced = firstHotspot
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasProposedCredentials = { it.canRequestHotspot(it.bluetoothHotspot) && it.passphrase != null },
            hasFreshCredentials = { it.canRequestHotspot(announced) })
        inbox.beginWifiRecovery(null, 1_000)
        assertTrue(inbox.offer(first))
        assertSame(first, inbox.awaitReplacementCredentials(null, 1_000))

        source.retire()
        inbox.finishRecoveryJoin()
        val replacement = first.copy(ssid = nextHotspot.ssid, passphrase = "next-password",
            bluetoothHotspot = nextHotspot, sourceGeneration = Iap2EndpointGeneration())
        elapsed = 200
        assertFalse(replacement.canRequestHotspot(announced))
        assertFalse(inbox.offer(replacement.copy(bluetoothHotspot = firstHotspot)))
        assertTrue(inbox.offer(replacement, onAccept = { announced = nextHotspot }))
        assertFalse(inbox.offer(replacement.copy(passphrase = "unselected-password")))
        assertSame(replacement, inbox.poll())
        assertFalse(inbox.offer(replacement.copy(passphrase = "unselected-password")))
    }

    @Test fun unknownPasswordRecoveryDefersLateNewSourceUntilTheRetiredJoinEnds() = kotlinx.coroutines.test.runTest {
        val hotspot = checkNotNull(BluetoothHotspot.fromPeer(receiver, "CAR-WIFI"))
        val source = Iap2EndpointGeneration()
        val first = CarPlayEndpoint(hotspot.ssid, "first-password", 0, null, null, null, emptyList(), null,
            bluetoothHotspot = hotspot, sourceGeneration = source)
        var elapsed = 100L
        val inbox = CarHotspotReconnect.EndpointInbox(nowMillis = { elapsed },
            hasFreshCredentials = { it.canRequestHotspot(hotspot) })
        inbox.beginWifiRecovery(null, 1_000)
        assertTrue(inbox.offer(first))
        assertSame(first, inbox.awaitReplacementCredentials(null, 1_000))
        source.retire()
        elapsed = 1_100
        val replacement = first.copy(passphrase = "next-password", sourceGeneration = Iap2EndpointGeneration())
        var committed: CarPlayEndpoint? = null
        assertFalse(inbox.offer(replacement, onDeferredAccept = { committed = it }))
        assertFalse(inbox.offer(replacement.copy(passphrase = "unselected-password")))
        assertNull(committed)
        assertNull(inbox.poll())

        inbox.finishRecoveryJoin()
        assertSame(replacement, committed)
        assertSame(replacement, inbox.poll())
        assertFalse(inbox.offer(replacement.copy(passphrase = "unselected-password")))
    }

    @Test fun switchSsidRetiresAnOldHintEvenWhenItsSourceIsNoLongerUsable() {
        val source = Iap2EndpointGeneration()
        val previous = CarPlayEndpoint("old-ap", "old-password", 7200, null, null, null,
            listOf("192.168.5.2", "fe80::2%wlan0"), "192.168.5.1", sourceGeneration = source)
        val next = CarPlayEndpoint("new-ap", "new-password", 0, null, null, null, emptyList(), null)
        val inbox = CarHotspotReconnect.EndpointInbox(hasFreshCredentials = { it.passphrase != null })
        source.retire()

        val transition = inbox.classifyHintTransition(previous, next)
        assertNull(transition.mergeFrom)
        assertTrue(transition.invalidateLateAddress)
        assertEquals(setOf("192.168.5.2", "fe80::2", "192.168.5.1"), transition.retiredSsidAddresses)
        val replay = next.copy(airPlayPort = 7200, ipAddresses = listOf("192.168.5.2"),
            addressOnlyAirplayReport = true, requiresBoundAirplayRoute = true, airplayRouteBinding = AirplayRouteBinding())
        assertNull(replay.verifiedAirplayEndpoint("new-ap", 11L, transition.retiredSsidAddresses) { true })
    }

    @Test
    fun ordinaryVwAdvertisementNetworkAllowsCacheButVerifiedCarNetworkTakesPriority() {
        val home = 3
        assertTrue(CarHotspotReconnect.canReplaceBinding(home, home, "Home", "Car"))
        assertFalse(CarHotspotReconnect.canReplaceBinding(home, home, "Car", "Car"))
        assertFalse(CarHotspotReconnect.canReplaceBinding(home, null, "AnotherCarNetwork", "Car"))
        assertTrue(CarHotspotReconnect.canReplaceBinding<Int>(null, null, null, "Car"))
    }
}
