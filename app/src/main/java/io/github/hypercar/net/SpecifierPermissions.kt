package io.github.hypercar.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 所有车机局域网连接（WifiNetworkSpecifier）的运行时权限预检。
 *
 * specifier 的 `requestNetwork` 在缺权限时会**直接 onUnavailable**（没有任何系统引导），
 * 所以必须在连接前主动检查、缺了给出可操作的引导，而不是让用户看到一次莫名的连车失败。
 *
 * 分版本（对照 WifiJoiner 注释）：
 *  - **Android 13+（API 33+）**：需 `NEARBY_WIFI_DEVICES`（本项目 manifest 已 `neverForLocation` 声明）。
 *  - **Android 12 / 12L（API 31-32）**：需 `ACCESS_FINE_LOCATION`，**且定位服务必须开启**——
 *    只授权限不开定位，requestNetwork 照样失败。
 *
 * Shizuku 不再通过系统 connect-network 入网，因此有 Shizuku 时也必须满足这些权限。
 */
object SpecifierPermissions {

    /** 当前 SDK 下 specifier 路径需要的运行时权限（单元素数组）。 */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 所需权限是否已全部授予。 */
    fun isGranted(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android 12/12L 还要求定位服务全局开启（否则 requestNetwork 仍失败）；
     * 13+ 用 NEARBY_WIFI_DEVICES，不依赖定位服务，恒 true。
     */
    fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        return runCatching {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        }.getOrDefault(false)
    }

    /** specifier 路径是否就绪：权限齐 +（12/12L）定位服务开。 */
    fun isReady(context: Context): Boolean = isGranted(context) && isLocationEnabled(context)

    /** 缺权限时的人话标签（引导卡片与失败原因共用）。 */
    fun permissionLabel(): String =
        if (Build.VERSION.SDK_INT >= 33) "附近设备" else "定位"

    /**
     * 降级模式下的失败原因（预检不通过时给出，可操作）。已就绪返回 null。
     * 顺序：先权限、后定位服务——用户先授权、再开定位是自然操作序。
     */
    fun degradeBlockReason(context: Context): String? = when {
        !isGranted(context) ->
            "需授予「${permissionLabel()}」权限才能连接车机局域网"
        !isLocationEnabled(context) ->
            "需在系统里开启定位服务才能连接车机局域网"
        else -> null
    }
}
