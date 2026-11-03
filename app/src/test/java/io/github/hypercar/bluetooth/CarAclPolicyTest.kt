package io.github.hypercar.bluetooth

import android.bluetooth.BluetoothDevice
import io.github.hypercar.headunit.HeadUnitProfiles
import io.github.hypercar.headunit.RemoteProfiles
import org.junit.Assert.*
import org.junit.Test

class CarAclPolicyTest {
    @Test fun leTransportNeverTriggersCarPlayEvenForDualModeCar() {
        assertFalse(CarAclPolicy.isClassicCarLink(BluetoothDevice.TRANSPORT_LE, BluetoothDevice.DEVICE_TYPE_DUAL))
    }

    @Test fun leOnlyKeyWithMissingTransportIsAlsoIgnored() {
        assertFalse(CarAclPolicy.isClassicCarLink(BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.DEVICE_TYPE_LE))
    }

    @Test fun classicCarConnectionStillAutoStarts() {
        assertTrue(CarAclPolicy.isClassicCarLink(BluetoothDevice.TRANSPORT_BREDR, BluetoothDevice.DEVICE_TYPE_DUAL))
        assertTrue(CarAclPolicy.isClassicCarLink(BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.DEVICE_TYPE_UNKNOWN))
    }

    @Test fun bmwOnlyListensAndPreservesAudioProfiles() {
        // 用户实测断音频配置未改善二次上车，恢复保留；仍由宝马主动发起 iAP2。
        assertFalse(HeadUnitProfiles.BMW_ID85.allowOutboundIap2)
        assertFalse(HeadUnitProfiles.BMW_ID85.dropProfilesBeforeIap2)
        assertTrue(HeadUnitProfiles.GENERIC.allowOutboundIap2)
        assertTrue(HeadUnitProfiles.WINPLAY.allowOutboundIap2)
    }

    @Test fun cachedOldProfilesCannotRestoreAggressiveBluetoothPolicy() {
        val safe = HeadUnitProfiles.BMW_ID85
        val stale = safe.copy(dropProfilesBeforeIap2 = true, allowOutboundIap2 = true)
        for (schema in 0..2) {
            assertEquals(safe, RemoteProfiles.merge(listOf(safe), listOf(stale), schema).single())
        }
    }
}
