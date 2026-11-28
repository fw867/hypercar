package io.github.hypercar.yadea

import android.content.Context
import android.content.SharedPreferences
import io.github.hypercar.session.StartupRequirements
import io.github.hypercar.settings.ProjectionTarget

internal enum class YadeaSource(val key: String, val title: String) {
    CarWith("carwith", "CarWith"),
    SystemCarLife("system_carlife", "CarLife+");

    fun installed(context: Context): Boolean = when (this) {
        CarWith -> StartupRequirements.carWithInstalled(context)
        SystemCarLife -> StartupRequirements.systemCarLifePackage(context) != null
    }
}

internal enum class YadeaConnectionMode(val key: String, val label: String) {
    Manual("manual", "手动 IP"),
    P2p("p2p", "P2P 扫描"),
}

internal class YadeaSettings(private val preferences: () -> SharedPreferences) {
    var source: YadeaSource?
        get() = preferences().getString("yadea_source", null)?.let { saved ->
            YadeaSource.entries.firstOrNull { it.key == saved }
        }
        set(value) = preferences().edit().putString("yadea_source", value?.key).apply()

    var instrumentIp: String
        get() = preferences().getString("yadea_instrument_ip", "").orEmpty()
        set(value) = preferences().edit().putString("yadea_instrument_ip", value).apply()

    var connectionMode: YadeaConnectionMode
        get() = if (preferences().getString("yadea_connection_mode", null) == YadeaConnectionMode.P2p.key)
            YadeaConnectionMode.P2p else YadeaConnectionMode.Manual
        set(value) = preferences().edit().putString("yadea_connection_mode", value.key).apply()

    fun allowsWifiDirect(target: ProjectionTarget): Boolean =
        target == ProjectionTarget.Yadea && connectionMode == YadeaConnectionMode.P2p
}
