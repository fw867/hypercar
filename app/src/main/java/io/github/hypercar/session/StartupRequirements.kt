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
    internal val SYSTEM_CARLIFE_PACKAGES = listOf("com.samsung.android.carlink",
        "com.samsung.android.smartcarlink", "com.baidu.carlife.samsung", "com.baidu.carlife.honor", "com.baidu.carlife")

    @Suppress("DEPRECATION")
    fun systemCarLifePackage(context: Context): String? = SYSTEM_CARLIFE_PACKAGES.firstOrNull {
        runCatching { context.packageManager.getApplicationInfo(it,0).enabled }.getOrDefault(false)
    }

    /** Explicit user action only: never call from the source dialer/reconnect callback. */
    fun openSystemCarLife(context: Context): Boolean {
        val pkg=systemCarLifePackage(context) ?: return false
        if (pkg == "com.samsung.android.carlink") {
            val opened=runCatching {
                context.startActivity(android.content.Intent().setClassName(pkg,
                    "com.samsung.android.carlink.setting.activity.carlife.CarLifeConnectCarActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        val intent=context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));true}.getOrDefault(false)
    }

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
