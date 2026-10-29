package io.github.hypercar.bluetooth

import android.bluetooth.BluetoothDevice

/** CarPlay 的自动唤醒只跟随经典蓝牙；数字钥匙/近场服务的 LE ACL 不属于投屏会话。 */
object CarAclPolicy {
    fun isClassicCarLink(transport: Int, deviceType: Int): Boolean =
        transport != BluetoothDevice.TRANSPORT_LE && deviceType != BluetoothDevice.DEVICE_TYPE_LE
}
