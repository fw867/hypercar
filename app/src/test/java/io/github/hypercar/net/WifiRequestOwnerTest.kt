package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class WifiRequestOwnerTest {
    private val line = "WifiNetworkFactory: mActiveSpecificNetworkRequest NetworkRequest [ REQUEST id=12, [ Transports: WIFI Capabilities: NOT_RESTRICTED&TRUSTED&NOT_VPN Forbidden: LOCAL_NETWORK Specifier: <WifiNetworkSpecifier [, SSID Match pattern=PatternMatcher{LITERAL: BMW test}, BSSID=null]> Uid: 10434 RequestorUid: 10434 RequestorPkg: io.github.hypercar UnderlyingNetworks: Null] ]"
    @Test fun selectsOnlyExactAppUidAndSsid() {
        assertTrue(WifiRequestOwner.matches(line, 10434, "BMW test"))
        assertFalse(WifiRequestOwner.matches(line, 1043, "BMW test"))
        assertFalse(WifiRequestOwner.matches(line, 10434, "BMW"))
        assertFalse(WifiRequestOwner.matches(line.replace("io.github.hypercar", "io.github.hypercar.other"), 10434, "BMW test"))
        assertFalse(WifiRequestOwner.matches(line.replace("RequestorUid: 10434", "RequestorUid: 10435"), 10434, "BMW test"))
    }
    @Test fun rejectsInternetInactiveAndUnknownFormats() {
        assertFalse(WifiRequestOwner.matches(line.replace("Capabilities: ", "Capabilities: INTERNET&"), 10434, "BMW test"))
        assertFalse(WifiRequestOwner.matches(line.replace("mActiveSpecificNetworkRequest", "mConnectedSpecificNetworkRequest"), 10434, "BMW test"))
        assertFalse(WifiRequestOwner.matches("WifiNetworkFactory: mActiveSpecificNetworkRequest null", 10434, "BMW test"))
        assertFalse(WifiRequestOwner.matches(line.replace("LITERAL:", "PREFIX:"), 10434, "BMW test"))
    }
}
