package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class WifiStationIdentityTest {
    private fun info(ip: String = "172.16.1.2", mac: String = "06:12:34:56:78:90") =
        "WifiInfo: SSID: \"car\", BSSID: 12:22:33:44:55:66, MAC: $mac, IP: /$ip, Supplicant state: COMPLETED"

    @Test fun stationAddressIsSelectedByBoundNetworksIpAndIsNotApBssid() {
        assertEquals("06:12:34:56:78:90", WifiStationIdentity.fromStatus(info(), setOf("172.16.1.2")))
        assertNull(WifiStationIdentity.fromStatus(info(), setOf("192.168.1.2")))
        assertEquals("06:12:34:56:78:90", WifiStationIdentity.fromStatus(info("192.168.1.2") + "\n" + info(), setOf("172.16.1.2")))
        assertNull(WifiStationIdentity.fromStatus(info() + "\n" + info(mac = "06:aa:bb:cc:dd:ee"), setOf("172.16.1.2")))
    }

    @Test fun redactedInvalidAndQuotedAddressesNeverBecomeStationIdentity() {
        for (mac in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:12:34:56:78:90", "invalid")) {
            assertNull(WifiStationIdentity.normalize(mac))
            assertNull(WifiStationIdentity.fromStatus(info(mac = mac), setOf("172.16.1.2")))
        }
        assertNull(WifiStationIdentity.fromStatus(info().replace("MAC: 06:12:34:56:78:90, ", ""), setOf("172.16.1.2")))
        val quoted = "WifiInfo: SSID: \"fake, MAC: 06:12:34:56:78:90, IP: /172.16.1.2\", BSSID: 12:22:33:44:55:66"
        assertNull(WifiStationIdentity.fromStatus(quoted, setOf("172.16.1.2")))
        assertNull(WifiStationIdentity.fromStatus(info(), emptySet()))
    }
}
