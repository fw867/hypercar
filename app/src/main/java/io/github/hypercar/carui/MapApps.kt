package io.github.hypercar.carui

import android.content.Context

enum class MapPresentation(val mode: Int) { CARD(2), FULLSCREEN(1) }

enum class MapProvider(val id: String, val label: String, val packageName: String) {
    AMAP("amap", "高德地图", "com.autonavi.minimap"),
    BAIDU("baidu", "百度地图", "com.baidu.BaiduMap");

    fun carLaunchCommand(displayId: Int, presentation: MapPresentation, initial: Boolean): String {
        val activity = when (this) {
            AMAP -> "com.autonavi.map.activity." + if (initial) "UcarSplashActivity" else "UcarMapActivity"
            BAIDU -> "com.baidu.hicar.HiCarWelcomeScreen"
        }
        // CarWith uses the native preview contract, not a crop of the full map scene.
        val category = if (this == AMAP && presentation == MapPresentation.CARD) "MAP_PREVIEW" else "UCAR"
        val mode = if (this == AMAP) presentation.mode else 1
        // Amap reads this option only in loadMainMap(), not when screenMode changes.
        // Our card and full-screen share one activity, so both must initialize horizontal
        // navigation. A portrait preview option would leak into full-screen and enlarge/crop UI.
        return "am start --activity-single-top -n $packageName/$activity " +
            "-a com.ucar.intent.action.UCAR -c com.ucar.intent.category.$category " +
            "--ez isUcarMode true --ei screenMode $mode --ei displayId $displayId " +
            "--ez isUCarNaviUseVerticalScreenLayout false --display $displayId"
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
