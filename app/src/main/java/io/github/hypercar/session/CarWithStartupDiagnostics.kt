package io.github.hypercar.session

import io.github.hypercar.shizuku.ExecResult
import io.github.hypercar.shizuku.ShizukuTools
import java.io.File
import java.util.Locale

internal object CarWithStartupDiagnostics {
    private val LOCAL_TABLES = listOf("/proc/net/tcp", "/proc/net/tcp6")

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

    /**
     * 免 Shizuku 的本地监听表探测：普通应用可直接读 /proc/net/tcp[6]（只读，不连端口）。
     * @return null=监听表不可读；否则该端口的状态描述（如 "LISTEN@0100007F:1C48"），无条目时 "no_entry"。
     */
    fun localPortState(port: Int, tables: List<String> = LOCAL_TABLES): String? {
        val hex = String.format(Locale.ROOT, "%04X", port)
        val states = mutableListOf<String>()
        var readable = false
        for (path in tables) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            readable = true
            lines.drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 4) return@forEach
                if (!parts[1].endsWith(":$hex")) return@forEach
                val label = when (parts[3]) {
                    "0A" -> "LISTEN"
                    "01" -> "ESTABLISHED"
                    else -> parts[3]
                }
                states.add("$label@${parts[1]}")
            }
        }
        if (!readable) return null
        return states.distinct().take(8).joinToString(",").ifEmpty { "no_entry" }
    }

    /** 端口上已有 LISTEN 或 ESTABLISHED 条目即视为源端就绪；监听表不可读时返回 false。 */
    fun isListening(port: Int, tables: List<String> = LOCAL_TABLES): Boolean =
        localPortState(port, tables)?.split(",")?.any { it.startsWith("LISTEN") || it.startsWith("ESTABLISHED") } == true

    suspend fun collect(sinceMillis: Long, emit: (String) -> Unit) {
        if (!ShizukuTools.isReady) {
            emit("7240 ${localPortState(7240)?.let { "本地监听表 $it" } ?: "监听表不可读"}；CarWith 系统日志不可读（Shizuku 未就绪）")
            return
        }
        // 1C48 is 7240. Read the local endpoint only; never connect to the CMD socket.
        for (table in listOf("tcp", "tcp6")) {
            val rows = ShizukuTools.exec("awk '\$2 ~ /:1C48$/ { print \$2, \$4 }' /proc/net/$table")
            emit("$table port=7240 ${socketState(rows)}")
        }
        val runtime = ShizukuTools.exec(
            """echo "CarWith.enable=$(settings get global ex_func_carWith) carlink.pid=$(pidof com.miui.carlink) carlife.pid=$(pidof com.baidu.carlife.xiaomi)"
               dumpsys -t 2 activity services com.miui.carlink | grep -iE 'ServiceRecord.*CarlinkService' | head -n 2""".trimIndent()
        )
        emit("runtime=${runtime.out.lineSequence().joinToString(" ").take(480)} stderr=${runtime.err.take(120)}")
        val since = String.format(Locale.ROOT, "%d.%03d", sinceMillis / 1000, sinceMillis % 1000)
        val markers = "CarlifeAdbActivity|CarlifeDialogActivity|CarlinkService|com.miui.carlink.*PermissionActivity|" +
            "connect has started!|isShowCta|connectSwitch.*is false|carWithEnable.*is false"
        val events = ShizukuTools.exec("logcat -d -T '$since' -v brief -e '$markers' HyperCar:S HyperCar-Core:S adbd:S '*:V' | tail -n 12")
        val lines = events.out.lineSequence().filter { it.isNotBlank() && !it.startsWith("---------") }.toList()
        lines.forEach { emit("system=${it.take(360)}") }
        if (lines.isEmpty() || events.err.isNotBlank()) {
            emit("system_events=${if (lines.isEmpty()) "none_visible" else "partial"} stderr=${events.err.take(160)}")
        }
    }
}
