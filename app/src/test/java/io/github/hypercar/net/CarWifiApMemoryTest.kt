package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test

class CarWifiApMemoryTest {
    @Test fun storageIsScopedToReceiverAndExactSsidWithoutDelimiterCollisions() {
        val car = "9C:DF:03:5D:FB:90"
        val key = CarWifiApMemory.key(car, "车机|Wi-Fi")!!
        assertEquals(key, CarWifiApMemory.key(car.lowercase(), "车机|Wi-Fi"))
        assertNotEquals(key, CarWifiApMemory.key("9c:df:03:5d:fb:91", "车机|Wi-Fi"))
        assertNotEquals(key, CarWifiApMemory.key(car, "车机"))
        assertNull(CarWifiApMemory.key("not-a-receiver", "car"))
        assertNull(CarWifiApMemory.key(car, ""))
    }

    private fun info(ip: String = "172.16.1.2", bssid: String = "3a:f9:f5:c6:8c:3c") =
        "WifiInfo: SSID: \"car\", BSSID: $bssid, MAC: 06:12:34:56:78:90, IP: /$ip, Supplicant state: COMPLETED"

    @Test fun learnsTheActualApOfTheSuccessfulNetworkNotPhoneOrReceiverMac() {
        assertEquals("3a:f9:f5:c6:8c:3c", CarWifiApMemory.fromStatus(info(), setOf("172.16.1.2")))
        assertNull(CarWifiApMemory.fromStatus(info(), setOf("192.168.1.2")))
        assertEquals("3a:f9:f5:c6:8c:3c", CarWifiApMemory.fromStatus(
            info("192.168.1.2", "12:22:33:44:55:66") + "\n" + info(), setOf("172.16.1.2")))
        assertNull(CarWifiApMemory.fromStatus(info() + "\n" + info(bssid = "12:22:33:44:55:66"), setOf("172.16.1.2")))
        assertNull(CarWifiApMemory.fromStatus(info(), emptySet()))
    }

    @Test fun malformedPlaceholderAndQuotedMetadataCannotBecomeAnApHint() {
        for (value in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:22:33:44:55:66", "invalid")) {
            assertNull(CarWifiApMemory.fromStatus(info(bssid = value), setOf("172.16.1.2")))
        }
        assertNull(CarWifiApMemory.fromStatus(
            "WifiInfo: SSID: \"fake, BSSID: 12:22:33:44:55:66, IP: /172.16.1.2\"", setOf("172.16.1.2")))
        assertNull(CarWifiApMemory.fromStatus(info().replace("COMPLETED", "DISCONNECTED"), setOf("172.16.1.2")))
    }
}
