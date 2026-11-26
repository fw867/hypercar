package io.github.hypercar.spike

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallIdentityTest {
    private val telephony = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"
    private fun confirmed(id: String = "TC@4", vararg ended: String) = TelecomCallSnapshot.Observed(id, ended.toSet())

    private fun call(id: Int, state: String, component: String = telephony, voip: Boolean = false) =
        "[Call id=TC@$id, state=$state, tpac=ComponentInfo{$component}, 1, UserHandle{0}, " +
            "cmgr=null, handle=tel:***, vidst=A, childs(0), has_parent(false), cap=[], prop=[]], voip=$voip"

    private fun telecomDump(foreground: String, other: String) = sequence {
        yield("CallsManager:")
        yield("  mCalls:")
        if (other != "null") yield("    $other")
        if (foreground != "null") yield("    $foreground")
        yield("  mCallAudioManager:")
        yield("    Foreground call:")
        yield("    $foreground")
    }

    @Test fun requiresOneTelecomLegEvenWhenForegroundLegIsActive() {
        assertEquals("TC@4", ShellCallIdentity.parse(telecomDump(call(4, "ACTIVE"), "null")))
        for (other in listOf("RINGING", "ON_HOLD", "ACTIVE", "CONNECTING")) {
            assertNull(ShellCallIdentity.parse(telecomDump(call(4, "ACTIVE"), call(9, other))))
        }
    }

    @Test fun returnedLegGetsANewKeyAfterWatchdogObservesSwap() {
        var elapsed = 0L
        var active = "TC@4"
        val identity = ShellCallIdentity({ elapsed }, { confirmed(active) })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val first = checkNotNull(identity.current())
        elapsed += 200
        assertEquals(first, identity.current())

        active = "TC@9"
        elapsed += 200
        assertNotEquals(first, identity.current())
        assertFalse(identity.matchesCached(first))

        active = "TC@4"
        elapsed += 200
        val returned = checkNotNull(identity.current())
        assertNotEquals(first, returned)
        assertTrue(identity.matchesCached(returned))

        elapsed += 501
        assertFalse(identity.matchesCached(returned))
        val fresh = identity.current()
        assertNotNull(fresh)
        assertNotEquals(returned, fresh)
    }

    @Test fun waitingLegRetiresIdentityBeforeRefreshAndResumingNeedsFreshOffer() {
        var reads = 0
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ 0L }, { reads++; confirmed() }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.trackingReady(monitor)
        identity.onPreciseCallState(monitor, 1, singleActive)
        val original = checkNotNull(identity.current())

        identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
        assertFalse(identity.matchesCached(original))
        assertEquals(listOf(original), retired)
        assertNull(identity.current())
        identity.onPreciseCallState(monitor, 1, singleActive.copy(background = 2))
        assertNull(identity.current())
        assertEquals(1, reads)

        identity.onPreciseCallState(monitor, 1, singleActive)
        assertNotEquals(original, checkNotNull(identity.current()))
        assertEquals(2, reads)
    }

    @Test fun expiredPcmLeasesKeepTheSameCallRetryKey() {
        var elapsed = 0L
        var active = "TC@4"
        val identity = ShellCallIdentity({ elapsed }, { confirmed(active) })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val first = checkNotNull(identity.currentBridgeIdentity())

        repeat(2) {
            elapsed += 600
            val resumed = checkNotNull(identity.currentBridgeIdentity())
            assertNotEquals(first.lease, resumed.lease)
            assertFalse(identity.matchesCached(first.lease))
            assertEquals(first.retryKey, resumed.retryKey)
        }

        elapsed += 200
        active = "TC@9"
        assertNotEquals(first.retryKey, checkNotNull(identity.currentBridgeIdentity()).retryKey)
    }

    @Test fun repeatedUnchangedActiveStateDoesNotRetireTheCurrentAudioBridge() {
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ 0L }, { confirmed() }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1, 2))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.onPreciseCallState(monitor, 2, idle)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.onPreciseCallState(monitor, 2, idle)

        assertTrue(retired.isEmpty())
        assertTrue(identity.matchesCached(original))
        assertEquals(original, identity.current())
    }

    @Test fun disconnectFencesTheOldLegAndAllowsAReplacementWithTheSameRoles() {
        var elapsed = 0L
        var active = confirmed()
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, { active }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        identity.onDisconnected(monitor, 1)
        assertEquals(listOf(original), retired)
        assertNull(identity.current())
        assertFalse(identity.matchesCached(original))

        elapsed = 250
        active = confirmed("TC@8", "TC@4")
        assertNull(identity.current())
        elapsed = 450
        assertNotEquals(original, checkNotNull(identity.current()))
    }

    @Test fun roleChangeBeforeDisconnectUsesPriorConfirmedLeg() {
        var elapsed = 0L
        var active = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { active })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
        identity.onDisconnected(monitor, 1)
        identity.onPreciseCallState(monitor, 1, singleActive)
        assertNull(identity.current())
        assertFalse(identity.matchesCached(original))

        elapsed = 250
        active = confirmed("TC@8", "TC@4")
        assertNull(identity.current())
        elapsed = 450
        assertNotEquals(original, checkNotNull(identity.current()))
    }

    @Test fun subscriptionReregistrationDoesNotPermitADisconnectedLeg() {
        var elapsed = 0L
        var active = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { active })
        fun register(): Long {
            val generation = identity.beginTracking()
            identity.trackSubscriptions(generation, setOf(1))
            identity.onPreciseCallState(generation, 1, singleActive)
            identity.trackingReady(generation)
            return generation
        }
        val first = register()
        val original = checkNotNull(identity.current())
        identity.onDisconnected(first, 1)
        assertNull(identity.current())
        register()
        elapsed = 250
        active = confirmed("TC@4", "TC@4")
        assertNull(identity.current())

        active = confirmed("TC@8", "TC@4")
        register()
        assertNull(identity.current())
        elapsed = 450
        assertNotEquals(original, checkNotNull(identity.current()))
    }

    @Test fun delayedTerminalRoleAndDisconnectUseTheOldCallIdFromTelecom() {
        var elapsed = 0L
        var active = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { active })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
        active = confirmed("TC@8")
        identity.onPreciseCallState(monitor, 1, singleActive)
        val replacement = checkNotNull(identity.current())
        identity.onPreciseCallState(monitor, 1, singleActive.copy(foreground = 7))
        identity.onDisconnected(monitor, 1)
        assertFalse(identity.matchesCached(replacement))
        identity.onPreciseCallState(monitor, 1, singleActive)
        assertNull(identity.current())
        elapsed = 275
        active = confirmed("TC@8", "TC@4")
        assertNull(identity.current())
        elapsed = 475
        val reopened = checkNotNull(identity.current())
        assertNotEquals(original, reopened)
        assertNotEquals(replacement, reopened)

        // Precise-state repeats do not create a second disconnect observation.
        elapsed = 750
        identity.onPreciseCallState(monitor, 1, singleActive)
        assertEquals(reopened, identity.current())

        identity.onDisconnected(monitor, 1)
        elapsed = 1000
        assertNull(identity.current())
        elapsed = 1200
        assertNotEquals(reopened, checkNotNull(identity.current()))
    }

    @Test fun secondDisconnectRequiresPostEventRevalidationAndRejectsTheEndedLeg() {
        var elapsed = 0L
        var active = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { active })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        checkNotNull(identity.current())

        elapsed = 200
        active = confirmed("TC@8")
        val replacement = checkNotNull(identity.current())
        identity.onDisconnected(monitor, 1)
        elapsed += 100
        identity.onDisconnected(monitor, 1)
        assertFalse(identity.matchesCached(replacement))
        elapsed += 150
        active = confirmed("TC@8", "TC@4")
        assertNull(identity.current())
        elapsed = 550
        assertNull(identity.current())
        elapsed += 200
        active = confirmed("TC@9", "TC@4", "TC@8")
        assertNull(identity.current())
        elapsed += 200
        assertNotNull(identity.current())
        elapsed += 200
        active = confirmed("TC@8")
        assertNull(identity.current())
    }

    @Test fun disconnectBeforeFirstSnapshotDefersInitialAdmission() {
        var elapsed = 0L
        var reads = 0
        var result = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { reads++; result })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onDisconnected(monitor, 1)
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)

        assertNull(identity.current())
        assertEquals(0, reads)
        elapsed = 250
        assertNull(identity.current())
        elapsed = 450
        result = TelecomCallSnapshot.Observed(null, noCalls = true)
        assertNull(identity.current())
        elapsed = 650
        result = confirmed("TC@8")
        assertNotNull(identity.current())
        assertEquals(3, reads)
    }

    @Test fun rejectedWaitingCallDoesNotFenceTheContinuingForeground() {
        var elapsed = 0L
        var active = confirmed()
        val identity = ShellCallIdentity({ elapsed }, { active })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
        identity.onDisconnected(monitor, 1)
        identity.onPreciseCallState(monitor, 1, singleActive)
        active = confirmed("TC@4", "TC@8")
        elapsed = 250
        assertNull(identity.current())
        elapsed = 450
        assertNotEquals(original, checkNotNull(identity.current()))
    }

    @Test fun a240msReadFitsContinuousRefreshWithoutExtendingTheLease() {
        var elapsed = 0L
        val budgets = mutableListOf<Long>()
        val identity = ShellCallIdentity({ elapsed }, { budget ->
            budgets += budget
            elapsed += minOf(240, budget)
            if (budget >= 240) confirmed() else TelecomCallSnapshot.Unavailable()
        })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        repeat(3) { assertEquals(original, identity.current()) }
        assertEquals(listOf(400L, 260L, 260L, 260L), budgets)
        assertTrue(identity.matchesCached(original))
    }

    @Test fun missingPreciseRoleCanUseTwoConsistentTelecomSnapshotsAfterRegistrationSettles() {
        var elapsed = 0L
        var reads = 0
        val identity = ShellCallIdentity({ elapsed }, { reads++; confirmed() })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1, 2))
        identity.onPreciseCallState(generation, 1, singleActive)
        assertNull(identity.currentBridgeIdentity())
        assertEquals(0, reads)

        identity.trackingReady(generation)
        assertNull(identity.currentBridgeIdentity())
        assertEquals(0, reads)

        elapsed += 500
        assertNull(identity.currentBridgeIdentity())
        assertEquals(1, reads)
        assertNull(identity.currentBridgeIdentity())
        assertEquals(1, reads)

        elapsed += 200
        val verified = checkNotNull(identity.currentBridgeIdentity())
        assertTrue(identity.matchesCached(verified.lease))
        assertEquals(2, reads)
        elapsed += 200
        assertEquals(verified, identity.currentBridgeIdentity())
        assertEquals(verified.lease, identity.current(allowFallback = true))
        assertTrue(identity.confirmsOpen(verified.lease))
    }

    @Test fun fallbackRevokesOnKnownConflictAndAnUnknownFieldCannotClearIt() {
        var elapsed = 0L
        var reads = 0
        val revoked = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, { reads++; confirmed() }, revoked::add)
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1, 2))
        identity.trackingReady(generation)
        identity.onPreciseCallState(generation, 1, singleActive)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        val current = checkNotNull(identity.currentBridgeIdentity())

        identity.onPreciseCallState(generation, 2, idle.copy(ringing = 6))
        assertEquals(listOf(current.lease), revoked)
        assertFalse(identity.matchesCached(current.lease))
        identity.onPreciseCallState(generation, 2, PreciseCallRoles(-1, -1, -1))
        elapsed = 1_200
        assertNull(identity.currentBridgeIdentity())
        assertEquals(2, reads)

        identity.onPreciseCallState(generation, 2, idle.copy(background = -1))
        elapsed = 1_700
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_900
        assertNotEquals(current.lease, checkNotNull(identity.currentBridgeIdentity()).lease)
    }

    @Test fun confirmedIdleAndTwoReportedActiveLegsDoNotFallBack() {
        var elapsed = 0L
        var reads = 0
        val identity = ShellCallIdentity({ elapsed }, { reads++; confirmed() })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1, 2))
        identity.trackingReady(generation)
        identity.onPreciseCallState(generation, 1, idle)
        identity.onPreciseCallState(generation, 2, idle)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        identity.onPreciseCallState(generation, 1, singleActive)
        identity.onPreciseCallState(generation, 2, singleActive.copy(ringing = -1))
        elapsed = 1_000
        assertNull(identity.currentBridgeIdentity())
        assertEquals(0, reads)

        identity.onPreciseCallState(generation, 2, PreciseCallRoles(-1, -1, -1))
        elapsed = 1_500
        assertNull(identity.currentBridgeIdentity())
        assertEquals(0, reads)

        identity.onPreciseCallState(generation, 2, idle.copy(background = -1))
        elapsed = 2_000
        assertNull(identity.currentBridgeIdentity())
        elapsed = 2_200
        assertNotNull(identity.currentBridgeIdentity())
        assertEquals(2, reads)
    }

    @Test fun mismatchesAndUnavailableTelecomReadsResetTheFallbackProof() {
        var elapsed = 0L
        var result: TelecomCallSnapshot = confirmed("TC@4")
        val events = CallAudioDiagnostics()
        val identity = ShellCallIdentity({ elapsed }, { result }, trace = events::record)
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.trackingReady(generation)
        identity.onPreciseCallState(generation, 1, PreciseCallRoles(-1, -1, -1))
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        result = confirmed("TC@7")
        assertNull(identity.currentBridgeIdentity())
        elapsed = 900
        result = TelecomCallSnapshot.Unavailable()
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_100
        result = confirmed("TC@7")
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_300
        val verified = checkNotNull(identity.currentBridgeIdentity())
        elapsed = 1_500
        result = TelecomCallSnapshot.Unavailable()
        assertNull(identity.currentBridgeIdentity())
        assertFalse(identity.matchesCached(verified.lease))

        val diagnostics = events.readAfter(0).lines
        assertTrue(diagnostics.any { it.contains("ready=true subscriptions=[1]") })
        assertTrue(diagnostics.any { it.contains("sub=1 ringing=-1 foreground=-1 background=-1") })
        assertTrue(diagnostics.any { it.contains("gate=unknown_roles") && it.contains("query=fallback") })
        assertTrue(diagnostics.any { it.contains("telecom query=fallback result=active_pstn=TC@7") })
        assertTrue(diagnostics.any { it.contains("identity=telecom_fallback_unconfirmed") })
        assertTrue(diagnostics.any { it.contains("identity=telecom_confirmed") })
        assertTrue(events.readAfter(diagnostics.size.toLong()).lines.isEmpty())
    }

    @Test fun fallbackCannotReauthorizeATelecomLegMarkedEndedAfterDisconnect() {
        var elapsed = 0L
        var result = confirmed("TC@4")
        val identity = ShellCallIdentity({ elapsed }, { result })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.trackingReady(generation)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        val original = checkNotNull(identity.currentBridgeIdentity())
        assertTrue(identity.onDisconnected(generation, 1))
        assertFalse(identity.matchesCached(original.lease))

        elapsed = 950
        result = confirmed("TC@4", "TC@4")
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_150
        result = confirmed("TC@4") // The terminal record can rotate out of the next snapshot.
        assertNull(identity.currentBridgeIdentity())

        elapsed = 1_350
        result = confirmed("TC@7")
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_550
        assertNotEquals(original.lease, checkNotNull(identity.currentBridgeIdentity()).lease)
    }

    @Test fun refreshKeepsCompetingRolesOnlyForSubscriptionsStillActive() {
        var elapsed = 0L
        var reads = 0
        val identity = ShellCallIdentity({ elapsed }, { reads++; confirmed() })
        val original = identity.beginTracking()
        identity.trackSubscriptions(original, setOf(1, 2))
        identity.onPreciseCallState(original, 1, singleActive)
        identity.onPreciseCallState(original, 2, singleActive)
        identity.trackingReady(original)

        val refreshed = identity.beginTracking()
        identity.trackSubscriptions(refreshed, setOf(1, 2))
        identity.onPreciseCallState(refreshed, 1, singleActive)
        identity.trackingReady(refreshed)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        assertNull(identity.currentBridgeIdentity())
        assertEquals(0, reads)

        val removed = identity.beginTracking()
        identity.trackSubscriptions(removed, setOf(1))
        identity.trackingReady(removed)
        elapsed = 1_200
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_400
        assertNotNull(identity.currentBridgeIdentity())
    }

    @Test fun emptyTelecomSnapshotRetiresTheUnverifiedCandidate() {
        var elapsed = 0L
        var snapshot = confirmed("TC@4")
        val identity = ShellCallIdentity({ elapsed }, { snapshot })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.trackingReady(generation)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        snapshot = TelecomCallSnapshot.Observed(null, noCalls = true)
        assertNull(identity.currentBridgeIdentity())

        snapshot = confirmed("TC@4")
        elapsed = 900
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_100
        assertNull(identity.currentBridgeIdentity())
        assertEquals("telecom_leg_disconnected", identity.diagnosticStatus())
    }

    @Test fun disconnectDoesNotEraseThePendingCallThatAnEmptySnapshotMustRetire() {
        var elapsed = 0L
        var snapshot = confirmed("TC@4")
        val identity = ShellCallIdentity({ elapsed }, { snapshot })
        val generation = identity.beginTracking()
        identity.trackSubscriptions(generation, setOf(1))
        identity.trackingReady(generation)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        assertTrue(identity.onDisconnected(generation, 1))

        elapsed = 750
        snapshot = TelecomCallSnapshot.Observed(null, noCalls = true)
        assertNull(identity.currentBridgeIdentity())
        snapshot = confirmed("TC@4")
        elapsed = 950
        assertNull(identity.currentBridgeIdentity())
        elapsed = 1_150
        assertNull(identity.currentBridgeIdentity())
        assertEquals("telecom_leg_disconnected", identity.diagnosticStatus())
    }

    @Test fun staleSubscriptionRefreshCannotReachTheListenerReplacement() {
        val identity = ShellCallIdentity({ 0L }, { confirmed() })
        val old = identity.beginTracking()
        val current = identity.beginTracking()
        assertEquals(setOf(1), identity.queryTrackedSubscriptions(current) { setOf(1) })
        identity.onPreciseCallState(current, 1, singleActive)
        identity.trackingReady(current)
        val active = checkNotNull(identity.currentBridgeIdentity())

        assertNull(identity.queryTrackedSubscriptions(old) { error("stale refresh must not query subscriptions") })
        assertEquals(active, identity.currentBridgeIdentity())

        assertNull(identity.queryTrackedSubscriptions(current) {
            identity.beginTracking()
            setOf(2)
        })
        assertFalse(identity.matchesCached(active.lease))
    }

    @Test fun exportCanPublishPendingMonitorDiagnosticsWithoutDuplicates() {
        val events = CallAudioDiagnostics()
        val delivery = CallAudioDiagnosticDelivery()
        val published = mutableListOf<String>()
        events.record("registration failed")

        val pendingMonitor = events.readAfter(delivery.publishedThrough)
        events.record("identity unconfirmed")
        val export = events.readAfter(delivery.publishedThrough)
        delivery.publish(export, published::add)
        delivery.publish(pendingMonitor, published::add)

        assertEquals(listOf("registration failed", "identity unconfirmed"), published)
        assertTrue(events.readAfter(delivery.publishedThrough).lines.isEmpty())
    }

    @Test fun watchdogUsesTheRefreshDeadlineEvenWhenItJustReadTheCache() {
        var elapsed = 0L
        var duration = 0L
        val identity = ShellCallIdentity({ elapsed }, { budget ->
            elapsed += minOf(duration, budget)
            if (duration <= budget) confirmed() else TelecomCallSnapshot.Unavailable()
        })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 199
        assertEquals(original, identity.current())
        assertEquals(1L, identity.millisUntilRefresh())
        elapsed += identity.millisUntilRefresh()
        duration = 240
        assertEquals(original, identity.current())
        assertEquals(0L, identity.millisUntilRefresh())
    }

    @Test fun telecomHistoryMatchesTerminalEventsToTheRecordId() {
        val lines = telecomDump(call(8, "ACTIVE"), "null") + sequenceOf(
            "Historical Events:",
            "  CallTC@4 (2 events):",
            "    10:40:03.123 - SET_DISCONNECTING",
            "    10:40:03.125 - SET_DISCONNECTED (cause)",
            "  CallTC@8 (1 events):",
            "    10:40:03.400 - SET_ACTIVE",
            "  unrelated block",
            "    10:40:04.000 - SET_DISCONNECTED",
        )
        val snapshot = ShellCallIdentity.parseSnapshot(lines) as TelecomCallSnapshot.Observed
        assertEquals("TC@8", snapshot.activeCallId)
        assertEquals(setOf("TC@4"), snapshot.endedCallIds)
        assertFalse(snapshot.noCalls)
        val idleDump = telecomDump("null", "null").map {
            if (it == "CallsManager:" || it == "  mCalls:") "$it " else it
        }
        assertTrue((ShellCallIdentity.parseSnapshot(idleDump) as TelecomCallSnapshot.Observed).noCalls)
    }

    private fun samsungCall(id: Int) = call(id, "ACTIVE") +
        "caps=[Capabilities: CAPABILITY_HOLD CAPABILITY_MUTE], props=[Properties:], conf(false), had_child(false)"

    @Test fun oneUi85ActivePstnRecordMustMatchTheForegroundRecord() {
        val active = samsungCall(170)
        val snapshot = ShellCallIdentity.parseSnapshot(telecomDump(active, "null")) as TelecomCallSnapshot.Observed
        assertEquals("TC@170", snapshot.activeCallId)
        assertFalse(snapshot.noCalls)
        assertNull(ShellCallIdentity.parse(telecomDump(active, samsungCall(171))))
        assertNull(ShellCallIdentity.parse(sequenceOf(
            "CallsManager:", "  mCalls:", "    $active", "  mCallAudioManager:",
            "    Foreground call:", "    ${samsungCall(171)}",
        )))
    }

    @Test fun oneUi85RejectsVoipConferenceAndMalformedCallRecords() {
        val active = samsungCall(170)
        for (invalid in listOf(
            active.replace("voip=falsecaps", "voip=truecaps"),
            active.replace("conf(false)", "conf(true)"),
            active.replace("had_child(false)", "had_child(true)"),
            active.replace("childs(0)", "childs(1)"),
            active.replace("has_parent(false)", "has_parent(true)"),
            active.replace("state=ACTIVE", "state=ON_HOLD"),
            active.replace(telephony, "example.voip/.Connections"),
            active.substringBefore(", conf(false)"),
        )) {
            assertNull(ShellCallIdentity.parse(telecomDump(invalid, "null")))
        }
    }

    @Test fun oneUi85NestedHistoryRevokesOnlyTheCallWithTheTerminalEvent() {
        val snapshot = ShellCallIdentity.parseSnapshot(telecomDump(samsungCall(170), "null") + sequenceOf(
            "      Historical Events:",
            "        CallTC@169 (2 events):",
            "          10:13:15.005 - SET_DISCONNECTING",
            "          10:13:15.010 - SET_DISCONNECTED (cause)",
            "        CallTC@170 (1 events):",
            "          10:13:37.838 - SET_ACTIVE",
            "        Unrelated data:",
            "          10:13:37.900 - SET_DISCONNECTED",
            "      Another section:",
            "        CallTC@170 (1 events):",
            "          10:13:38.001 - SET_DISCONNECTED",
        )) as TelecomCallSnapshot.Observed
        assertEquals(setOf("TC@169"), snapshot.endedCallIds)
        assertEquals("TC@170", snapshot.activeCallId)
    }

    @Test fun aTransportMissRetainsTheVerifiedIdentityAtStartButASecondMissRevokes() {
        var elapsed = 0L
        var result: TelecomCallSnapshot = confirmed()
        val retired = mutableListOf<String>()
        val budgets = mutableListOf<Long>()
        val identity = ShellCallIdentity({ elapsed }, { budgets += it; result }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        result = TelecomCallSnapshot.Unavailable()
        assertEquals(original, identity.current())
        assertTrue(identity.confirmsOpen(original))
        elapsed = 280
        result = confirmed()
        assertEquals(original, identity.current())
        assertTrue(identity.confirmsOpen(original))
        assertEquals(listOf(400L, 300L, 400L), budgets)

        elapsed = 480
        result = TelecomCallSnapshot.Unavailable()
        assertEquals(original, identity.current())
        elapsed = 780
        assertNull(identity.current())
        assertFalse(identity.matchesCached(original))
        assertEquals(listOf(original), retired)
    }

    @Test fun aSlowSingleMissAllowsAnActiveBridgeAndStartCheckToReachTheSuccessfulRetry() {
        var elapsed = 0L
        var read = 0
        val identity = ShellCallIdentity({ elapsed }, { budget ->
            when (++read) {
                2 -> {
                    elapsed += budget
                    TelecomCallSnapshot.Unavailable()
                }
                3 -> {
                    elapsed += 240
                    confirmed()
                }
                else -> confirmed()
            }
        })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        assertEquals(original, identity.current())
        assertEquals(500L, elapsed)
        assertTrue(identity.matchesCached(original))
        assertTrue(identity.confirmsStart(original))
        assertEquals(740L, elapsed)
        assertEquals(3, read)
        assertTrue(identity.matchesCached(original))
    }

    @Test fun aFailedStartupQueryWaitsForItsOnlyRetryBeforeStartingAnything() {
        for (firstQueryDuration in listOf(20L, 100L)) {
            for (replacement in listOf(false, true)) {
                var elapsed = 0L
                var read = 0
                val retired = mutableListOf<String>()
                val slept = mutableListOf<Long>()
                val identity = ShellCallIdentity({ elapsed }, {
                    when (++read) {
                        2 -> {
                            elapsed += firstQueryDuration
                            TelecomCallSnapshot.Unavailable()
                        }
                        3 -> confirmed(if (replacement) "TC@9" else "TC@4")
                        else -> confirmed()
                    }
                }, retired::add, sleepForStartRetry = {
                    slept += it
                    elapsed += it
                })
                val monitor = identity.beginTracking()
                identity.trackSubscriptions(monitor, setOf(1))
                identity.onPreciseCallState(monitor, 1, singleActive)
                identity.trackingReady(monitor)
                val original = checkNotNull(identity.current())

                elapsed = 200
                var startCount = 0
                assertEquals(!replacement, identity.confirmsStart(original) {
                    assertEquals(3, read)
                    ++startCount
                    true
                })
                assertEquals(3, read)
                assertEquals(if (replacement) 0 else 1, startCount)
                assertEquals(if (firstQueryDuration < 80) listOf(80L - firstQueryDuration) else emptyList(), slept)
                assertEquals(if (replacement) listOf(original) else emptyList(), retired)
            }
        }
    }

    @Test fun preciseStartupWaitsForTheInFlightForegroundRefresh() {
        var elapsed = 0L
        val reading = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, {
            if (elapsed == 0L) confirmed() else {
                reading.countDown()
                check(finishRead.await(5, TimeUnit.SECONDS))
                confirmed("TC@9")
            }
        }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        val refresh = FutureTask { identity.current() }.also { Thread(it).start() }
        val start = FutureTask { identity.confirmsStart(original) }
        val starter = Thread(start)
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            starter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!start.isDone && starter.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertEquals(Thread.State.BLOCKED, starter.state)
        } finally {
            finishRead.countDown()
        }
        assertNotEquals(original, refresh.get(5, TimeUnit.SECONDS))
        assertFalse(start.get(5, TimeUnit.SECONDS))
        assertEquals(listOf(original), retired)
    }

    @Test fun repeatFailureOrNoRefreshCannotKeepThePreciseLeasePastItsGrace() {
        for (retry in listOf(false, true)) {
            var elapsed = 0L
            var result: TelecomCallSnapshot = confirmed()
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())
            result = TelecomCallSnapshot.Unavailable()

            elapsed = 200
            assertEquals(original, identity.current())
            elapsed = 500
            assertTrue(identity.matchesCached(original))
            if (retry) {
                assertNull(identity.current())
                assertEquals(listOf(original), retired)
            } else {
                elapsed = 980
            }
            assertFalse(identity.matchesCached(original))
        }
    }

    @Test fun disconnectOrDifferentForegroundRevokesDuringTheRefreshGrace() {
        for (disconnect in listOf(false, true)) {
            var elapsed = 0L
            var result: TelecomCallSnapshot = confirmed()
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())

            elapsed = 200
            result = TelecomCallSnapshot.Unavailable()
            assertEquals(original, identity.current())
            elapsed = 500
            assertTrue(identity.matchesCached(original))
            if (disconnect) {
                identity.onDisconnected(monitor, 1)
            } else {
                result = confirmed("TC@9")
                assertNotEquals(original, identity.current())
            }
            assertEquals(listOf(original), retired)
            assertFalse(identity.matchesCached(original))
        }
    }

    @Test fun successfulQueryDiagnosticsIncludeDurationBudgetAndExitCodeWithoutCallRecords() {
        var elapsed = 0L
        val trace = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, { elapsed += 53; confirmed() }, trace = trace::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        assertNotNull(identity.current())
        assertTrue(trace.any {
            "duration_ms=53" in it && "timeout_ms=400" in it && "exit_code=0" in it && "timed_out=false" in it
        })
    }

    @Test fun failedQueryDiagnosticsDistinguishTimeoutAndNonzeroExit() {
        val dump = telecomDump(call(4, "ACTIVE"), "null")
        val ordinary = ShellCallIdentity.parseSnapshot(dump)
        val signalled = ShellCallIdentity.parseSnapshot(dump + "partial outputtimeout: sending signal KILL to command /system/bin/dumpsys")
        for ((snapshot, duration, diagnostic) in listOf(
            Triple(ShellCallIdentity.withProcessExit(ordinary, 124), 401L, "true failure=timeout"),
            Triple(ShellCallIdentity.withProcessExit(signalled, 137), 451L, "true failure=timeout"),
            Triple(ShellCallIdentity.withProcessExit(ordinary, 137), 451L, "unknown failure=sigkill"),
            Triple(ShellCallIdentity.withProcessExit(ordinary, 137), 15L, "unknown failure=sigkill"),
            Triple(ShellCallIdentity.withProcessExit(ordinary, 1), 15L, "false failure=nonzero_exit"),
            Triple(ShellCallIdentity.withProcessExit(ordinary, null), 450L, "true failure=timeout"),
        )) {
            val result = snapshot as TelecomCallSnapshot.Unavailable
            var elapsed = 0L
            val trace = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { elapsed += duration; result }, trace = trace::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            assertNull(identity.current())
            assertTrue(trace.any {
                "result=unavailable" in it && "duration_ms=$duration" in it && "timeout_ms=400" in it &&
                    "exit_code=${result.exitCode ?: "none"}" in it && "timed_out=$diagnostic" in it
            })
        }
    }

    @Test fun incompleteDumpIsNotAnAuthoritativeEmptyCallList() {
        for (lines in listOf(
            emptySequence(),
            sequenceOf("CallsManager:"),
            sequenceOf("CallsManager:", "  mCalls:"),
            sequenceOf("CallsManager:", "  mCalls:", "    ${call(4, "ACTIVE")}"),
            sequenceOf("Telecom unavailable"),
        )) {
            val result: TelecomCallSnapshot = ShellCallIdentity.parseSnapshot(lines)
            assertTrue(result is TelecomCallSnapshot.Unavailable)
        }
    }

    @Test fun truncatedForegroundRetainsTheLeaseUntilTheBoundedRetry() {
        val dump = telecomDump(call(4, "ACTIVE"), "null").toList()
        for (truncated in listOf(
            dump.take(4), dump.take(5), dump.take(5) + "    [Call id=TC@4,",
            dump.take(5) + "    [Call id=TC@4, cap=[]",
            dump + "Error in poll while dumping service telecom : Interrupted system call",
            dump + "Error with service 'telecom' while dumping: Failed transaction",
        )) {
            var elapsed = 0L
            var result = ShellCallIdentity.parseSnapshot(dump.asSequence())
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())

            elapsed = 200
            result = ShellCallIdentity.parseSnapshot(truncated.asSequence())
            assertEquals(original, identity.current())
            assertTrue(retired.isEmpty())
            elapsed = 280
            result = ShellCallIdentity.parseSnapshot((dump.take(5) + "    null").asSequence())
            assertNull(identity.current())
            assertEquals(listOf(original), retired)
        }
    }

    @Test fun completeMainListCanRevokeTheOldLeaseWithoutAForegroundRecord() {
        for (list in listOf(
            listOf(call(4, "DISCONNECTED")),
            listOf(call(9, "ACTIVE")),
            listOf(call(4, "ACTIVE"), call(9, "ON_HOLD")),
        )) {
            var elapsed = 0L
            var result: TelecomCallSnapshot = confirmed()
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())

            elapsed = 200
            result = ShellCallIdentity.parseSnapshot(
                sequenceOf("CallsManager:", "  mCalls:") + list.asSequence().map { "    $it" } +
                    sequenceOf("  mCallAudioManager:", "    Foreground call:"),
            )
            assertNull(identity.current())
            assertEquals(listOf(original), retired)
        }
    }

    @Test fun startupRechecksTelecomWhenRoutePreflightRunsPastTheCacheDeadline() {
        var elapsed = 0L
        val identity = ShellCallIdentity({ elapsed }, { confirmed(if (elapsed < 200) "TC@4" else "TC@9") })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 199
        assertFalse(identity.confirmsStart(original, authorize = { error("old lease authorized") }) {
            elapsed++
            true
        })
        assertFalse(identity.matchesCached(original))
    }

    @Test fun startupRechecksWhenBluetoothPreflightCrossesANewlyRefreshedDeadline() {
        var elapsed = 0L
        var foreground = "TC@4"
        val identity = ShellCallIdentity({ elapsed }, { confirmed(foreground) })
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        assertFalse(identity.confirmsStart(original, authorize = { error("old endpoint started") }) {
            elapsed = 400
            foreground = "TC@9"
            true
        })
        assertFalse(identity.matchesCached(original))
    }

    @Test fun failedDumpStillRevokesWhenItsCompleteMainListDisprovesTheOldCall() {
        val completeList = telecomDump(call(9, "ACTIVE"), "null").take(5).toList()
        val terminalList = telecomDump(call(4, "DISCONNECTED"), "null").take(5).toList()
        for (header in listOf(completeList, terminalList)) {
            for ((exit, marker) in listOf(
                124 to emptySequence(), 137 to emptySequence(),
                0 to sequenceOf("*** SERVICE 'telecom' DUMP TIMEOUT (1000ms) EXPIRED ***"),
            )) {
                var elapsed = 0L
                var result: TelecomCallSnapshot = confirmed()
                val retired = mutableListOf<String>()
                val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
                val monitor = identity.beginTracking()
                identity.trackSubscriptions(monitor, setOf(1))
                identity.onPreciseCallState(monitor, 1, singleActive)
                identity.trackingReady(monitor)
                val original = checkNotNull(identity.current())

                elapsed = 200
                result = ShellCallIdentity.withProcessExit(
                    ShellCallIdentity.parseSnapshot(header.asSequence() + marker), exit,
                )
                assertNull(identity.current())
                assertEquals(listOf(original), retired)
            }
        }
    }

    @Test fun timedOutDumpPreservesTerminalEventsEvenWhenTheMainListIsIncomplete() {
        for (endedCall in listOf("TC@4", "TC@8")) {
            var elapsed = 0L
            var result: TelecomCallSnapshot = confirmed()
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())

            elapsed = 200
            val dump = ShellCallIdentity.parseSnapshot(sequenceOf(
                "CallsManager:", "  mCalls:", "    [Call id=TC@4,",
                "  Historical Events:", "    Call$endedCall (1 events):", "      10:13:15.005 - SET_DISCONNECTING",
                "timeout: sending signal KILL to command /system/bin/dumpsys",
            ))
            result = ShellCallIdentity.withProcessExit(dump, 137)
            if (endedCall == "TC@4") {
                assertNull(identity.current())
                assertEquals(listOf(original), retired)
            } else {
                assertEquals(original, identity.current())
                assertTrue(retired.isEmpty())
            }
        }
    }

    @Test fun fallbackStartupRechecksADueSnapshotBeforeUsingThePreviousLease() {
        var elapsed = 0L
        var result = confirmed()
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1, 2))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        elapsed = 500
        assertNull(identity.currentBridgeIdentity())
        elapsed = 700
        val original = checkNotNull(identity.currentBridgeIdentity())

        elapsed = 900
        result = confirmed("TC@9")
        assertFalse(identity.confirmsStart(original.lease))
        assertEquals(listOf(original.lease), retired)
        assertFalse(identity.matchesCached(original.lease))
    }

    @Test fun bothAttemptsInAFailedRefreshAreLoggedWithTheirOwnTiming() {
        var elapsed = 0L
        var reads = 0
        val trace = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, {
            when (++reads) {
                1 -> confirmed()
                else -> {
                    elapsed += reads.toLong() * 10
                    TelecomCallSnapshot.Unavailable(1, TelecomCallSnapshot.Unavailable.Reason.NONZERO_EXIT)
                }
            }
        }, trace = trace::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        assertEquals(original, identity.current())
        elapsed = 280
        assertNull(identity.current())
        val failures = trace.filter { "result=unavailable" in it }
        assertEquals(2, failures.size)
        assertTrue("duration_ms=20" in failures[0] && "timeout_ms=300" in failures[0])
        assertTrue("duration_ms=30" in failures[1] && "timeout_ms=400" in failures[1])
    }

    @Test fun aLateSuccessfulSnapshotCannotKeepTheOldLeaseOrPublishAReplacement() {
        for (late in listOf(
            confirmed("TC@9"),
            TelecomCallSnapshot.Observed(null, noCalls = true),
            confirmed("TC@4", "TC@4"),
        )) {
            var elapsed = 0L
            var reads = 0
            val retired = mutableListOf<String>()
            val identity = ShellCallIdentity({ elapsed }, {
                if (++reads == 1) confirmed() else {
                    elapsed += 501
                    late
                }
            }, retired::add)
            val monitor = identity.beginTracking()
            identity.trackSubscriptions(monitor, setOf(1))
            identity.onPreciseCallState(monitor, 1, singleActive)
            identity.trackingReady(monitor)
            val original = checkNotNull(identity.current())

            elapsed = 200
            assertNull(identity.current())
            assertEquals(listOf(original), retired)
            assertFalse(identity.matchesCached(original))
        }
    }

    @Test fun successfulReadWithNoEligibleForegroundRevokesImmediately() {
        var elapsed = 0L
        var result: TelecomCallSnapshot = confirmed()
        val retired = mutableListOf<String>()
        val identity = ShellCallIdentity({ elapsed }, { result }, retired::add)
        val monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        identity.onPreciseCallState(monitor, 1, singleActive)
        identity.trackingReady(monitor)
        val original = checkNotNull(identity.current())

        elapsed = 200
        result = TelecomCallSnapshot.Unavailable()
        assertEquals(original, identity.current())
        elapsed = 500
        result = TelecomCallSnapshot.Observed(null)
        assertNull(identity.current())
        assertEquals(listOf(original), retired)
        assertFalse(identity.matchesCached(original))
    }

    @Test fun recoveryRequiresAllSubscriptionsAndIgnoresCallbacksFromPreviousRegistration() {
        val identity = ShellCallIdentity({ 0L }, { confirmed() })
        val firstMonitor = identity.beginTracking()
        identity.trackSubscriptions(firstMonitor, setOf(1, 2))
        identity.onPreciseCallState(firstMonitor, 2, singleActive)
        identity.trackingReady(firstMonitor)
        assertNull(identity.current())

        identity.onPreciseCallState(firstMonitor, 1, idle)
        val original = checkNotNull(identity.current())
        identity.onPreciseCallState(firstMonitor, 1, idle.copy(ringing = 5))
        assertFalse(identity.matchesCached(original))
        assertNull(identity.current())

        val replacement = identity.beginTracking()
        identity.trackSubscriptions(replacement, setOf(2))
        identity.onPreciseCallState(firstMonitor, 2, singleActive)
        identity.trackingReady(replacement)
        assertNull(identity.current())
        identity.onPreciseCallState(replacement, 2, singleActive)
        assertNotEquals(original, checkNotNull(identity.current()))
    }

    @Test fun boundaryDuringTelecomReadCannotPublishStaleIdentity() {
        lateinit var identity: ShellCallIdentity
        var monitor = 0L
        var waitDuringRead = false
        var elapsed = 0L
        val trace = mutableListOf<String>()
        identity = ShellCallIdentity({ elapsed }, {
            if (waitDuringRead) {
                identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
                identity.onPreciseCallState(monitor, 1, singleActive)
                elapsed += 41
            }
            confirmed()
        }, trace = trace::add)
        monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        assertNull(identity.current())
        identity.onPreciseCallState(monitor, 1, singleActive)
        assertNull(identity.current())
        identity.trackingReady(monitor)
        waitDuringRead = true
        assertNull(identity.current())
        assertTrue(trace.any {
            "result=discarded" in it && "duration_ms=41" in it && "exit_code=0" in it && "TC@" !in it
        })
        waitDuringRead = false
        assertNotNull(identity.current())
    }

    private val idle = PreciseCallRoles(ringing = 0, foreground = 0, background = 0)
    private val singleActive = idle.copy(foreground = 1)

    @Test fun acceptsLegacyTelecomRecord() {
        assertEquals("TC@4", ShellCallIdentity.parse(telecomDump(
            call(4, "ACTIVE").removeSuffix(", voip=false"), "null",
        )))
    }

    @Test fun requiresAnActivePstnForegroundRecord() {
        val anotherCall = call(4, "ACTIVE")
        for (foreground in listOf("null", call(9, "ON_HOLD"), call(9, "RINGING"),
            call(9, "ACTIVE", "example.voip/.Connections"), call(9, "ACTIVE", voip = true))) {
            assertNull(ShellCallIdentity.parse(telecomDump(foreground, anotherCall)))
        }
        assertNull(ShellCallIdentity.parse(sequenceOf("  Log:", "    Foreground call:", "    $anotherCall")))
    }

    @Test fun rejectsMalformedTelecomIdsAndComponentDelimiters() {
        val active = call(4, "ACTIVE")
        for (invalid in listOf(active.replace("TC@4", "TC@４"), active.replace("TC@4", "TC@"),
            active.replace("Service},", "Service, "), active.replace("Service},", "Service}x,"))) {
            assertNull(ShellCallIdentity.parse(telecomDump(invalid, "null")))
        }
    }
}
