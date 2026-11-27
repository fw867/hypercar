package io.github.hypercar.session

import android.bluetooth.BluetoothAdapter

internal data class OldDeviceReadiness(
    val attempt: Long,
    val network: String?,
    val srv: Srv?,
    val scanMode: Int?,
    val rfcommAccepting: Boolean,
) {
    data class Srv(val host: String, val port: Int)

    val connectable: Boolean? get() = when (scanMode) {
        BluetoothAdapter.SCAN_MODE_CONNECTABLE, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> true
        BluetoothAdapter.SCAN_MODE_NONE -> false
        else -> null
    }
    val discoverable: Boolean? get() = connectable?.let { scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }
    val ready: Boolean get() = network != null && srv != null && connectable == true && rfcommAccepting

    fun diagnosticFields(): String = "attempt=$attempt ready=$ready wifiBound=${network != null} " +
        "network=${network ?: "none"} srvSent=${srv != null} srvHost=${srv?.host ?: "none"} " +
        "srvPort=${srv?.port ?: "none"} btConnectable=${connectable ?: "unknown"} " +
        "btDiscoverable=${discoverable ?: "unknown"} rfcommAccepting=$rfcommAccepting"
}
