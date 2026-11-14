package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException

class WifiHandoffLeaseTest {
    private class Network {
        var state = WifiHandoffLease.SavedNetwork(7, "home", true)
        var owned = true
        var allowDisconnect = true
        var loseOwnerOnDisable = false
        var changeNetworkOnDisable = false
        var scanFailure = false
        val events = mutableListOf<String>()
        fun run(lease: WifiHandoffLease, target: String = "car") = lease.recover(target,
            owned = { owned }, current = { state },
            isCurrent = { old -> state.id == old.id && state.ssid == old.ssid },
            autojoin = { old, enabled ->
                events += "autojoin:${old.id}:$enabled"
                if (!enabled && loseOwnerOnDisable) owned = false
                if (!enabled && changeNetworkOnDisable) state = state.copy(id = 8, ssid = "other")
            },
            disconnect = { events += "disconnect"; allowDisconnect },
            scan = { events += "scan"; if (scanFailure) throw CancellationException("cancelled") },
        )
    }
    @Test fun duplicatedRomConfigurationsResolveOnlyWhenAllIdentityAndAutojoinValuesAgree() {
        val home=WifiHandoffLease.SavedNetwork(7,"home",true)
        assertEquals(home,WifiHandoffLease.currentSavedNetwork(7,"home",listOf(home,home.copy())))
        assertNull(WifiHandoffLease.currentSavedNetwork(7,"home",listOf(home,home.copy(autojoin=false))))
        assertEquals(home,WifiHandoffLease.currentSavedNetwork(7,"home",listOf(home,home.copy(ssid="other"))))
        assertNull(WifiHandoffLease.currentSavedNetwork(7,"home",listOf(home.copy(id=8))))
    }

    @Test fun oneHandoffRestoresOnlyItsPreviousAutojoinExactlyOnce() {
        val n=Network(); val lease=WifiHandoffLease()
        assertTrue(n.run(lease))
        assertFalse(n.run(lease))
        assertEquals(listOf("autojoin:7:false","disconnect","scan"), n.events)
        lease.close();lease.close()
        assertEquals(listOf("autojoin:7:false","disconnect","scan","autojoin:7:true"),n.events)
    }
    @Test fun foreignRequestsCurrentCarAndCancelledLeasesNeverDisconnectAnything() {
        val n=Network();val lease=WifiHandoffLease()
        n.owned=false; assertFalse(n.run(lease));assertTrue(n.events.isEmpty())
        n.owned=true;assertFalse(n.run(lease,"home"));assertTrue(n.events.isEmpty())
        lease.close();assertFalse(n.run(lease));assertTrue(n.events.isEmpty())
    }
    @Test fun ownershipOrCurrentApChangesRollBackBeforeAnyDisconnect() {
        for (changeNetwork in listOf(false,true)) {
            val n=Network();val lease=WifiHandoffLease()
            n.loseOwnerOnDisable=!changeNetwork;n.changeNetworkOnDisable=changeNetwork
            assertFalse(n.run(lease))
            assertEquals(listOf("autojoin:7:false","autojoin:7:true"),n.events)
            assertFalse(n.run(lease))
        }
    }
    @Test fun rejectedDisconnectAndCancelledScanRestoreTheOldSetting() {
        val rejected=Network().apply { allowDisconnect=false }
        assertFalse(rejected.run(WifiHandoffLease()))
        assertEquals(listOf("autojoin:7:false","disconnect","autojoin:7:true"),rejected.events)
        val cancelled=Network().apply { scanFailure=true }
        try { cancelled.run(WifiHandoffLease());fail("must propagate cancellation") }
        catch (_: CancellationException) { }
        assertEquals(listOf("autojoin:7:false","disconnect","scan","autojoin:7:true"),cancelled.events)
    }
    @Test fun userDisabledAutojoinIsNeverEnabledByCleanup() {
        val n=Network().apply { state=state.copy(autojoin=false) };val lease=WifiHandoffLease()
        assertTrue(n.run(lease));lease.close()
        assertEquals(listOf("disconnect","scan"),n.events)
    }
}
