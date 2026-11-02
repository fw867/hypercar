package io.github.hypercar.session

/** Advisory only: each stage retains its existing permission and degraded-mode handling. */
object ConnectionPreflight {
    data class State(val bluetoothEnabled: Boolean?, val bluetoothPermission: Boolean,
        val wifiEnabled: Boolean?, val shizukuReady: Boolean)

    fun findings(state: State): List<String> = buildList {
        when (state.bluetoothEnabled) {
            false -> add("蓝牙未开启：请打开手机蓝牙")
            null -> add("蓝牙状态未知：待系统权限或服务就绪后重查")
            true -> Unit
        }
        if (!state.bluetoothPermission) add("缺少附近设备连接权限：请在应用权限中允许")
        if (state.wifiEnabled == false) add("Wi-Fi 未开启：车机热点阶段需要 Wi-Fi")
        if (!state.shizukuReady) add("Shizuku 未就绪：连接可走现有降级路径，自绘界面与系统音频需要授权")
    }
}
