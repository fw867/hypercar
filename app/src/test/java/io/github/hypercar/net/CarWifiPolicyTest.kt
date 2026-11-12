package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiPolicyTest {
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
