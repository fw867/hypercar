package io.github.hypercar.perm

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 首启权限引导的**唯一权威清单**。
 *
 * 为什么要单独一份：此前只在降级连接时申请「附近设备/定位」，而
 * `BLUETOOTH_CONNECT` 从未被申请——但 iAP2 RFCOMM、上车 ACL 广播唤醒
 * 全都依赖它，等于秒连链路在用户手动授权前根本起不来。
 * HyperOS / MIUI 对侧载应用还会把通知等默认拒绝，必须主动申请并给出状态。
 *
 * 分版本说明：
 *  - **Nearby（附近设备）**：Android 12+ 的 `BLUETOOTH_CONNECT/SCAN` 与 13+ 的
 *    `NEARBY_WIFI_DEVICES` 同属系统「附近设备」组，一次弹窗即可覆盖。
 *  - **Location（定位）**：仅 Android 12/12L 的 `WifiNetworkSpecifier` 需要；
 *    13+ 因为有 `neverForLocation` 的附近设备权限，不再需要定位。
 *  - **Notification（通知）**：13+ 默认拒绝；前台服务通知被隐藏时部分 ROM 会回收服务。
 *
 * 特殊权限（不在运行时弹窗，只能深链系统页）：
 *  - `SYSTEM_ALERT_WINDOW`：Android 12+ 后台启动前台服务的一条豁免（上车自动唤醒要用）。
 *  - 电池优化白名单：避免熄屏后被判低电耗限流。
 *  - 自启动（HyperOS/MIUI 专有）：不授权则杀进程后收不到蓝牙广播。
 */
enum class PermGroup {
    /** 蓝牙 + Wi-Fi 附近设备（连接车机热点 / iAP2 / 上车唤醒）。 */
    Nearby,

    /** 定位（仅 Android 12/12L 的 Wi-Fi specifier 需要）。 */
    Location,

    /** 通知（前台服务常驻通知）。 */
    Notification,

    /** 麦克风（音频采集预留：本机自采自编的音轨）。 */
    Microphone,
}

/** 一次性快照，供 UI 判断显示哪些行、是否有待办。 */
data class PermissionSummary(
    val nearby: Boolean,
    val location: Boolean,
    val locationRequired: Boolean,
    val notification: Boolean,
    val notificationRequired: Boolean,
    val overlay: Boolean,
    val batteryWhitelisted: Boolean,
    /** 用户已选择"不再提示可选优化"（自启动/电池等无法回读的项）。 */
    val optionalDismissed: Boolean = false,
    /** 自启动状态：true/false 为读到；**null = 系统没有公开接口，未知**。 */
    val autostart: Boolean? = null,
    /**
     * **通知使用权**（`NotificationListenerService` 被用户勾选）。
     *
     * 不在运行时权限体系里，只能深链系统「通知使用权」页；车机音乐卡片靠它拿
     * `MediaSessionManager.getActiveSessions`，没有它就是空的。
     */
    val notificationAccess: Boolean = false,
)

object AppPermissions {

    /** 某组在**当前系统版本**下实际需要申请的运行时权限。 */
    fun perms(group: PermGroup): Array<String> = when (group) {
        PermGroup.Nearby -> buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()

        PermGroup.Location -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        PermGroup.Notification ->
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

        PermGroup.Microphone -> arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    /** 当前版本下**必需**的运行时权限组。 */
    fun requiredGroups(): List<PermGroup> = buildList {
        add(PermGroup.Nearby)
        if (Build.VERSION.SDK_INT <= 32) add(PermGroup.Location)
        if (Build.VERSION.SDK_INT >= 33) add(PermGroup.Notification)
            // 音频经 AudioPlaybackCapture 采集时再申请（当前无麦克风代码）
    }

    /** 缺失的必需组（用于一键申请与连接前预检）。 */
    fun missingRequired(context: Context): List<PermGroup> =
        requiredGroups().filterNot { isGranted(context, it) }

    /** 首启自动申请只发起一次（避免每次进前台都弹窗骚扰）。 */
    fun wasPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /**
     * 可选优化是否已被用户收起。
     *
     * 为什么需要它：自启动在 HyperOS 没有公开查询接口、电池白名单在部分 ROM 上
     * 也不回写 AOSP 标志——这两项**永远无法被应用确认为"已完成"**，
     * 若不做收起，首页会永久挂着提示卡。
     */
    fun isOptionalDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPTIONAL_DISMISSED, false)

    fun dismissOptional(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OPTIONAL_DISMISSED, true).apply()
    }

    private const val PREFS = "hypercar_permissions"
    private const val KEY_PROMPTED = "onboarding_prompted"
    private const val KEY_OPTIONAL_DISMISSED = "optional_dismissed"

    fun isGranted(context: Context, group: PermGroup): Boolean {
        val list = perms(group)
        if (list.isEmpty()) return true
        return list.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 定位服务是否开启（仅 12/12L 的 specifier 需要）。 */
    fun isLocationServiceOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return true
        return runCatching {
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
        }.getOrDefault(false)
    }

    fun hasOverlay(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true

    fun isBatteryWhitelisted(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * 自启动状态探测。
     *
     * Android 没有公开 API；但 MIUI / HyperOS 把自启动实现为一个 AppOps 项
     * `android:auto_start`，可以用 `unsafeCheckOpNoThrow` 读到（非标准接口，
     * 不同版本可能返回 DEFAULT / 抛异常）。
     *
     * @return true=已允许、false=已禁止、**null=读不到（按未知处理，不画状态点）**
     */
    fun autostartState(context: Context): Boolean? = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val mode = appOps.unsafeCheckOpNoThrow(
            "android:auto_start",
            context.applicationInfo.uid,
            context.packageName,
        )
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> false
            else -> null
        }
    }.getOrNull()

    fun summary(context: Context): PermissionSummary = PermissionSummary(
        nearby = isGranted(context, PermGroup.Nearby),
        location = isGranted(context, PermGroup.Location),
        locationRequired = Build.VERSION.SDK_INT <= 32,
        notification = isGranted(context, PermGroup.Notification),
        notificationRequired = Build.VERSION.SDK_INT >= 33,
        overlay = hasOverlay(context),
        batteryWhitelisted = isBatteryWhitelisted(context),
        optionalDismissed = isOptionalDismissed(context),
        autostart = autostartState(context),
        notificationAccess = hasNotificationAccess(context),
    )

    /** 通知使用权（只能深链系统页授权，无法用 requestPermissions 申请）。 */
    fun hasNotificationAccess(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    // ------------------------------------------------------------ 深链

    /** 应用详情页（权限被永久拒绝时唯一的恢复入口）。 */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 悬浮窗（显示在其他应用上层）授权页。 */
    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /**
     * 电池优化白名单。优先弹系统"是否允许后台运行"对话框
     * （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，manifest 已声明权限），
     * 部分 ROM 不认则退回列表页。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (direct) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    fun openLocationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 自启动管理页（HyperOS / MIUI 专有，非 AOSP 标准 API）。
     * 返回 false 表示没找到对应页面，调用方应退回应用详情页。
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            // HyperOS / MIUI
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // HyperOS 新版入口
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.autostart.AutoStartManagementActivity",
            ),
            // 华为 / 荣耀
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            // ColorOS / realme
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )
        for (component in candidates) {
            val ok = runCatching {
                context.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

