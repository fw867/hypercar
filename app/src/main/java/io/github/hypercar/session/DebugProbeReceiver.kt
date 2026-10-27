package io.github.hypercar.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * **调试用**广播入口（不用连车机、不依赖界面操作）：
 *
 * - `am broadcast -a io.github.hypercar.PROBE_CONNECT   -p io.github.hypercar`
 * - `am broadcast -a io.github.hypercar.PROBE_DISCONNECT -p io.github.hypercar`
 * - `am broadcast -a io.github.hypercar.PROBE_CAST      -p io.github.hypercar`
 *   → 让 CarLife 进入会话，并把本 App 绑成它的投屏宿主（等价 CarWith 的动作，见 §25）
 */
class DebugProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            HyperCarRepo.init(context)
            when (intent.action) {
                "io.github.hypercar.PROBE_EXPERIMENT" -> {
                    // 一键打开「CarLife 优先启动」实验并立刻连接，省得手动翻设置页
                                HyperCarRepo.connect(context)
                }
                "io.github.hypercar.PROBE_CONNECT" -> HyperCarRepo.connect(context)
                "io.github.hypercar.PROBE_RESET_RES" -> {
                    // 车机（DiPlay）不下发分辨率 → 按自定义值来：默认 2400x1080
                    val w = intent.getIntExtra("w", 2400).coerceAtLeast(2)
                    val h = intent.getIntExtra("h", 1080).coerceAtLeast(2)
                    io.github.hypercar.settings.SettingsStore.carlifeWidth = w
                    io.github.hypercar.settings.SettingsStore.carlifeHeight = h
                    HyperCarRepo.log("RES: 已把推送分辨率设为 ${w}x${h}（车机不下发分辨率时使用）")
                }
                "io.github.hypercar.PROBE_CARLIFE_ONLY" -> HyperCarRepo.startCarLifeOnly(context, intent.getStringExtra("pkg"))
                "io.github.hypercar.PROBE_DISCONNECT" -> HyperCarRepo.disconnect()
                "io.github.hypercar.PROBE_CAST" -> {
                    // 目标包名与连接类型可覆盖：--es pkg com.baidu.carlife.xiaomi --ei usbType 0|1|2
                    val pkg = intent.getStringExtra("pkg")
                        ?: HyperCarRepo.resolveInstalledCarLifePackage(context)
                    val usbType = intent.getIntExtra(
                        "usbType",
                        io.github.hypercar.carlife.CastHostService.TYPE_WIFI_HOTSPOT,
                    )
                    HyperCarRepo.log("CASTHOST: 触发投屏宿主绑定 target=$pkg usb_type=$usbType")
                    io.github.hypercar.carlife.CastHostService.startCarLifeSession(context, pkg, usbType)
                }
                else -> {
                    java.io.File(context.getExternalFilesDir(null), "cast_probe.txt").writeText("")
                    HyperCarRepo.startCarLifeDisplayProbe()
                }
            }
        }
    }
}
