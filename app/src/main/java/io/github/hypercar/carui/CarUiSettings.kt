package io.github.hypercar.carui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class CarThemeMode(val label: String) {
    AUTO("自动"),
    LIGHT("白天"),
    DARK("暗夜"),
}

/**
 * 车机 UI 自己的偏好（与手机 App 的 SettingsStore 分开，避免车机设置污染手机侧）。
 */
object CarUiSettings {
    private const val PREFS = "car_ui_settings"

    private var prefs: android.content.SharedPreferences? = null

    var autoCruise: Boolean by mutableStateOf(true)
        private set
    var defaultMap: MapProvider by mutableStateOf(MapProvider.AMAP)
        private set
    var defaultMusicPackage: String by mutableStateOf("")
        private set
    var autoPlayOnConnect: Boolean by mutableStateOf(false)
        private set
    var themeMode: CarThemeMode by mutableStateOf(CarThemeMode.AUTO)
        private set
    var wallpaper: CarWallpaper by mutableStateOf(CarWallpaper.GUANGYING)
        private set
    /** 车机最近的日夜信号；null = 还没收到，自动主题回退到手机系统深色模式。 */
    var carNight: Boolean? by mutableStateOf(null)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        autoCruise = p.getBoolean("auto_cruise", true)
        defaultMap = MapApps.fromId(p.getString("default_map", MapProvider.AMAP.id))
        defaultMusicPackage = p.getString("default_music", "").orEmpty()
        autoPlayOnConnect = p.getBoolean("auto_play", false)
        themeMode = runCatching {
            CarThemeMode.valueOf(p.getString("theme_mode", CarThemeMode.AUTO.name)!!)
        }.getOrDefault(CarThemeMode.AUTO)
        wallpaper = CarWallpapers.fromId(p.getString("wallpaper", CarWallpaper.GUANGYING.id))
    }

    fun updateAutoCruise(value: Boolean) {
        autoCruise = value
        prefs?.edit()?.putBoolean("auto_cruise", value)?.apply()
    }

    fun updateDefaultMap(provider: MapProvider) {
        defaultMap = provider
        prefs?.edit()?.putString("default_map", provider.id)?.apply()
    }

    fun updateDefaultMusicPackage(packageName: String) {
        defaultMusicPackage = packageName
        prefs?.edit()?.putString("default_music", packageName)?.apply()
    }

    fun updateAutoPlayOnConnect(value: Boolean) {
        autoPlayOnConnect = value
        prefs?.edit()?.putBoolean("auto_play", value)?.apply()
    }

    fun onCarNight(night: Boolean) {
        carNight = night
    }

    fun updateWallpaper(wallpaper: CarWallpaper) {
        this.wallpaper = wallpaper
        prefs?.edit()?.putString("wallpaper", wallpaper.id)?.apply()
    }

    fun updateThemeMode(mode: CarThemeMode) {
        themeMode = mode
        prefs?.edit()?.putString("theme_mode", mode.name)?.apply()
    }
}
