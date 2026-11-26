package io.github.hypercar.net

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CarWifiPolicyTest {
    @Test fun aVisibleStationSsidDuringAuthenticationIsJoiningEvidence() {
        val state = android.net.wifi.SupplicantState.FOUR_WAY_HANDSHAKE
        assertEquals("Car AP", CarWifiPolicy.joiningStationSsid("\"Car AP\"", state))
        assertEquals("Car AP", CarWifiPolicy.joiningStationSsid("Car AP", android.net.wifi.SupplicantState.AUTHENTICATING))
        assertNull(CarWifiPolicy.joiningStationSsid("<unknown ssid>", state))
        assertNull(CarWifiPolicy.joiningStationSsid("\"Car AP\"", android.net.wifi.SupplicantState.SCANNING))
        assertNull(CarWifiPolicy.joiningStationSsid("\"Car AP\"", android.net.wifi.SupplicantState.DISCONNECTED))
    }

    @Test fun authenticatingStationCannotIdentifyTheCurrentlyConnectedNetwork() {
        assertNull(CarWifiPolicy.connectedStationSsid("\"Car AP\"", android.net.wifi.SupplicantState.AUTHENTICATING))
        assertEquals("Car AP", CarWifiPolicy.connectedStationSsid("\"Car AP\"", android.net.wifi.SupplicantState.COMPLETED))
    }

    @Test fun usableCarWifiAddressesExcludePlaceholdersButKeepIpv6LinkLocal() {
        for (value in listOf("0.0.0.0", "127.0.0.1", "169.254.1.2", "224.0.0.251", "::", "::1", "ff02::fb")) {
            assertFalse(value, CarWifiPolicy.isUsableAddress(InetAddress.getByName(value)))
        }
        for (value in listOf("192.168.4.2", "fe80::12", "fd00::12")) {
            assertTrue(value, CarWifiPolicy.isUsableAddress(InetAddress.getByName(value)))
        }
    }

    @Test fun onlyTheAndroidPlaceholderIsTreatedAsRedacted() {
        assertNull(CarWifiPolicy.visibleSsid(null))
        assertNull(CarWifiPolicy.visibleSsid("<unknown ssid>"))
        assertNull(CarWifiPolicy.visibleSsid("\"\""))
        assertEquals("Car unknown edition", CarWifiPolicy.visibleSsid("\"Car unknown edition\""))
        assertEquals("<unknown ssid>", CarWifiPolicy.visibleSsid("\"<unknown ssid>\""))
        assertEquals(" Car Wi-Fi ", CarWifiPolicy.visibleSsid("\" Car Wi-Fi \""))
    }
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
