package io.github.hypercar.carui

import android.content.Context

enum class MapProvider(val id: String, val label: String, val packageName: String) {
    AMAP("amap", "高德地图", "com.autonavi.minimap"),
    BAIDU("baidu", "百度地图", "com.baidu.BaiduMap");

    val supportsLayoutIntent: Boolean get() = this == AMAP

    fun carLaunchCommand(displayId: Int, mode: Int, initial: Boolean): String {
        val activity = when (this) {
            AMAP -> "com.autonavi.map.activity." + if (initial) "UcarSplashActivity" else "UcarMapActivity"
            BAIDU -> "com.baidu.hicar.HiCarWelcomeScreen"
        }
        // 百度始终使用完整车联任务，卡片/全屏只重绑 Surface，不向导航任务再次发送 Intent。
        val screenMode = if (this == BAIDU) 1 else mode
        return "am start --activity-single-top -n $packageName/$activity " +
            "-a com.ucar.intent.action.UCAR -c com.ucar.intent.category.UCAR " +
            "--ez isUcarMode true --ei screenMode $screenMode --ei displayId $displayId " +
            "--ez isUCarNaviUseVerticalScreenLayout false --display $displayId"
    }

    fun cruiseCommand(displayId: Int): String {
        return "am start -a android.intent.action.VIEW -d '$cruiseUri' -p $packageName --display $displayId"
    }

    val cruiseUri: String get() = when (this) {
            AMAP -> "amapuri://drive/CarCruisePage?sourceApplication=io.github.hypercar&clearStack=0"
            // 百度识别这个来源后，只从地图首页进入巡航；正在导航/规划时会拒绝覆盖。
            BAIDU -> "baidumap://hicarmap/cruiserPage?src=autoCruise"
    }
}

object MapApps {
    fun installed(context: Context): List<MapProvider> = MapProvider.entries.filter { provider ->
        runCatching { context.packageManager.getApplicationInfo(provider.packageName, 0).enabled }.getOrDefault(false)
    }

    fun resolve(preferred: MapProvider, installed: List<MapProvider>): MapProvider? =
        preferred.takeIf { it in installed } ?: installed.firstOrNull()

    fun selected(context: Context): MapProvider? = resolve(CarUiSettings.defaultMap, installed(context))
    fun fromId(id: String?): MapProvider = MapProvider.entries.firstOrNull { it.id == id } ?: MapProvider.AMAP
}
