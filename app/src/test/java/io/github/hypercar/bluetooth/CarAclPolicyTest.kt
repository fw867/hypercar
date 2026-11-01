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

    @Test fun bmwOnlyListensAndDropsProfilesBeforeIap2() {
        // 2026-11-01 按现场证据调整：上车时手机侧 HFP/A2DP 回连会抢占 ACL，车机随后
        // Remote Terminated（dumpsys：11:05:10.840 HFP 回连 → 11:05:13.038 车机拆链），
        // iAP2 从未打开。改成「只配对、不连接配置文件」，与 cardeck 一致。
        // 出站仍然关闭：宝马车机是主动连入方，我们不去拨它。
        assertFalse(HeadUnitProfiles.BMW_ID85.allowOutboundIap2)
        assertTrue(HeadUnitProfiles.BMW_ID85.dropProfilesBeforeIap2)
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
