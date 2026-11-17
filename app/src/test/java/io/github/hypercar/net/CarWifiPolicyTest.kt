package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiPolicyTest {
    @Test fun repeatInvitationCanUseItsVerifiedHandleWithRedactedSsid() {
        fun reuse(current: String?, sameNetwork: Boolean) = CarWifiPolicy.canReuseInvitedCar(
            knownCar = true, sameNetwork = sameNetwork, peerMatches = true, announcedSsid = null,
            currentSsid = current, hasAddress = true, routeToPeer = true, boundSsid = "car")
        assertTrue(reuse(null, true))
        assertFalse(reuse(null, false))
        assertFalse(reuse("home", true))
    }
    @Test fun invitationBeforeBluetoothCredentialsReusesKnownCarWithoutInternetValidation() {
        val verified = CarWifiPolicy.canReuseInvitedCar(true, true, true, null, "car", true, true)
        assertTrue(verified)
        assertTrue(CarWifiPolicy.safe(internet = true, validated = false, verifiedRequest = verified))
        assertTrue(CarWifiPolicy.canReuseInvitedCar(true, true, true, "car", "car", true, true))
    }

    @Test fun invitationCannotAuthorizeOtherNetworksOrOverrideDifferentHotspotInformation() {
        assertFalse(CarWifiPolicy.canReuseInvitedCar(false, true, true, null, "home", true, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, false, true, null, "car", true, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, true, false, null, "car", true, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, true, true, "other", "car", true, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, true, true, null, null, true, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, true, true, null, "car", false, true))
        assertFalse(CarWifiPolicy.canReuseInvitedCar(true, true, true, null, "car", true, false))
    }
    @Test fun pairedCarsFreshHotspotMatchesCurrentAssociationWithoutRejoining() {
        assertTrue(CarWifiPolicy.samePairedHotspot(true, true, "car", "car"))
        assertFalse(CarWifiPolicy.samePairedHotspot(true, true, "car", "home"))
        assertFalse(CarWifiPolicy.samePairedHotspot(false, true, "car", "car"))
        assertFalse(CarWifiPolicy.samePairedHotspot(true, false, "car", "car"))
        assertFalse(CarWifiPolicy.samePairedHotspot(true, true, "car", null))
        assertFalse(CarWifiPolicy.samePairedHotspot(true, true, "", ""))
        assertTrue(CarWifiPolicy.safe(internet = true, validated = false, verifiedRequest = true))
        assertFalse(CarWifiPolicy.safe(internet = true, validated = false, verifiedRequest = false))
    }
    @Test fun unvalidatedSystemInternetApCannotBypassLocalOnlyJoin() {
        assertFalse(CarWifiPolicy.safe(internet = true, validated = false))
        assertTrue(CarWifiPolicy.safe(internet = false, validated = false))
        assertTrue(CarWifiPolicy.safe(internet = true, validated = true)) // WinPlay on home Wi-Fi.
    }
    @Test fun desktopReuseRequiresFreshSoftwareIdentityMatchingSsidAndWorkingInternet() {
        assertTrue(CarWifiPolicy.canReuseDesktop(true, "home", "home", true, true))
        assertFalse(CarWifiPolicy.canReuseDesktop(false, "home", "home", true, true)) // BMW keeps strict BSSID checks.
        assertFalse(CarWifiPolicy.canReuseDesktop(true, "home", "home", true, false))
        assertFalse(CarWifiPolicy.canReuseDesktop(true, "home", "other", true, true))
        assertFalse(CarWifiPolicy.canReuseDesktop(true, "home", "home", false, true))
        assertFalse(CarWifiPolicy.canReuseDesktop(true, null, null, true, true))
    }
}
