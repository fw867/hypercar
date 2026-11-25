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
        val identity = ShellCallIdentity({ elapsed }, { active })
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
        val identity = ShellCallIdentity({ 0L }, { reads++; "TC@4" }, retired::add)
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

    @Test fun recoveryRequiresAllSubscriptionsAndIgnoresCallbacksFromPreviousRegistration() {
        val identity = ShellCallIdentity({ 0L }, { "TC@4" })
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
            "TC@4"
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
