package io.github.hypercar.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build

/** Resolve afresh at session start; never change adapter settings or pairing identity. */
internal object LocalDeviceName {
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    @android.annotation.SuppressLint("MissingPermission")
    fun snapshot(): String {
        val name = runCatching {
            app?.getSystemService(BluetoothManager::class.java)?.adapter?.name
        }.getOrNull()
        return clean(name) ?: clean(Build.MODEL) ?: "HyperCar"
    }

    private fun clean(value: String?): String? =
        value?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
}
