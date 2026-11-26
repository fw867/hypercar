package io.github.hypercar.spike

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
            if (budget >= 240) confirmed() else TelecomCallSnapshot.Unavailable
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

    @Test fun watchdogUsesTheRefreshDeadlineEvenWhenItJustReadTheCache() {
        var elapsed = 0L
        var duration = 0L
        val identity = ShellCallIdentity({ elapsed }, { budget ->
            elapsed += minOf(duration, budget)
            if (duration <= budget) confirmed() else TelecomCallSnapshot.Unavailable
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
        val snapshot = ShellCallIdentity.parseSnapshot(lines)
        assertEquals("TC@8", snapshot.activeCallId)
        assertEquals(setOf("TC@4"), snapshot.endedCallIds)
        assertFalse(snapshot.noCalls)
        val idleDump = telecomDump("null", "null").map {
            if (it == "CallsManager:" || it == "  mCalls:") "$it " else it
        }
        assertTrue(ShellCallIdentity.parseSnapshot(idleDump).noCalls)
    }

    private fun samsungCall(id: Int) = call(id, "ACTIVE") +
        "caps=[Capabilities: CAPABILITY_HOLD CAPABILITY_MUTE], props=[Properties:], conf(false), had_child(false)"

    @Test fun oneUi85ActivePstnRecordMustMatchTheForegroundRecord() {
        val active = samsungCall(170)
        val snapshot = ShellCallIdentity.parseSnapshot(telecomDump(active, "null"))
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
        ))
        assertEquals(setOf("TC@169"), snapshot.endedCallIds)
        assertEquals("TC@170", snapshot.activeCallId)
    }

    @Test fun aTransportMissCannotEraseOrExtendTheLastVerifiedIdentity() {
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
        result = TelecomCallSnapshot.Unavailable
        assertEquals(original, identity.current())
        assertFalse(identity.confirmsOpen(original))
        elapsed = 280
        result = confirmed()
        assertEquals(original, identity.current())
        assertTrue(identity.confirmsOpen(original))
        assertEquals(listOf(400L, 300L, 220L), budgets)

        elapsed = 480
        result = TelecomCallSnapshot.Unavailable
        assertEquals(original, identity.current())
        elapsed = 780
        assertNull(identity.current())
        assertFalse(identity.matchesCached(original))
        assertEquals(listOf(original), retired)
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
        identity = ShellCallIdentity({ 0L }, {
            if (waitDuringRead) {
                identity.onPreciseCallState(monitor, 1, singleActive.copy(ringing = 6))
                identity.onPreciseCallState(monitor, 1, singleActive)
            }
            confirmed()
        })
        monitor = identity.beginTracking()
        identity.trackSubscriptions(monitor, setOf(1))
        assertNull(identity.current())
        identity.onPreciseCallState(monitor, 1, singleActive)
        assertNull(identity.current())
        identity.trackingReady(monitor)
        waitDuringRead = true
        assertNull(identity.current())
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
