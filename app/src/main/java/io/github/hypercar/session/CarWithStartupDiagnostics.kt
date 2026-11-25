package io.github.hypercar.session

import io.github.hypercar.shizuku.ExecResult
import io.github.hypercar.shizuku.ShizukuTools
import java.util.Locale

internal object CarWithStartupDiagnostics {
    fun socketState(result: ExecResult): String {
        if (!result.ok) return "unavailable(exit=${result.code})"
        return result.out.lineSequence().filter { it.isNotBlank() }.map { row ->
            val (local, state) = row.split(' ', limit = 2)
            val label = when (state) {
                "0A" -> "LISTEN"
                "01" -> "ESTABLISHED"
                else -> state
            }
            "$label@$local"
        }.distinct().take(8).joinToString(",").ifEmpty { "no_entry" }
    }

    suspend fun collect(sinceMillis: Long, emit: (String) -> Unit) {
        if (!ShizukuTools.isReady) {
            emit("7240 系统监听表与车联引擎系统日志不可读（Shizuku 未就绪）；已建立 CMD 与协议选择日志仍有效")
            return
        }
        // 1C48 is 7240. Read the local endpoint only; never connect to the CMD socket.
        for (table in listOf("tcp", "tcp6")) {
            val rows = ShizukuTools.exec("awk '\$2 ~ /:1C48$/ { print \$2, \$4 }' /proc/net/$table")
            emit("$table port=7240 ${socketState(rows)}")
        }
        val runtime = ShizukuTools.exec(
            """echo "CarWith.enable=$(settings get global ex_func_carWith) carlink.pid=$(pidof com.miui.carlink) carlife.pid=$(pidof com.baidu.carlife.xiaomi) samsung.link.pid=$(pidof com.samsung.android.carlink) samsung.life.pid=$(pidof com.baidu.carlife.samsung) honor.life.pid=$(pidof com.baidu.carlife.honor)"
               dumpsys -t 2 activity services com.miui.carlink | grep -iE 'ServiceRecord.*CarlinkService' | head -n 2""".trimIndent()
        )
        emit("runtime=${runtime.out.lineSequence().joinToString(" ").take(480)} stderr=${runtime.err.take(120)}")
        val since = String.format(Locale.ROOT, "%d.%03d", sinceMillis / 1000, sinceMillis % 1000)
        val markers = "CarlifeAdbActivity|CarlifeDialogActivity|CarlinkService|com.miui.carlink.*PermissionActivity|" +
            "connect has started!|isShowCta|connectSwitch.*is false|carWithEnable.*is false|" +
            "samsung_carlife_begin_to_listened|com.baidu.carlife.samsung|com.baidu.carlife.honor"
        val events = ShizukuTools.exec("logcat -d -T '$since' -v brief -e '$markers' HyperCar:S HyperCar-Core:S adbd:S '*:V' | tail -n 12")
        val lines = events.out.lineSequence().filter { it.isNotBlank() && !it.startsWith("---------") }.toList()
        lines.forEach { emit("system=${it.take(360)}") }
        if (lines.isEmpty() || events.err.isNotBlank()) {
            emit("system_events=${if (lines.isEmpty()) "none_visible" else "partial"} stderr=${events.err.take(160)}")
        }
    }
}
