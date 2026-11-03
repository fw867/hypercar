package io.github.hypercar.session

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import io.github.hypercar.perm.AppPermissions
import io.github.hypercar.perm.PermGroup
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools

object StartupRequirements {
    const val CARWITH_PACKAGE = "com.miui.carlink"

    @Suppress("DEPRECATION")
    fun carWithInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(CARWITH_PACKAGE, 0).enabled
    }.getOrDefault(false)

    fun read(context: Context): StartupReadiness = StartupReadiness(
        source = SettingsStore.carSource.takeIf { SettingsStore.carSourceSelected },
        missingPermissions = AppPermissions.missingRequired(context).map {
            when (it) {
                PermGroup.Nearby -> "授予附近设备权限（蓝牙与 Wi-Fi）"
                PermGroup.Location -> "授予定位权限"
                PermGroup.Notification -> "允许通知权限"
                PermGroup.Microphone -> "授予麦克风权限"
            }
        },
        bluetoothEnabled = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false),
        wifiEnabled = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false),
        locationEnabled = AppPermissions.isLocationServiceOn(context),
        shizukuReady = ShizukuTools.isReady,
        carWithInstalled = carWithInstalled(context),
        overlayAllowed = AppPermissions.hasOverlay(context),
    )
}
