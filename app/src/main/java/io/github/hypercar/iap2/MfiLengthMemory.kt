package io.github.hypercar.iap2

import android.content.Context

/**
 * MFi 挑战长度的**按车机持久化记忆**。
 *
 * 为什么需要（cardeck 2026-10-19 奥迪 MMI 3315 实测）：20B 挑战在该车机上静默 6 秒
 * 无应答，改发 32B 后 0.64 秒就回签名——每次连接白等 6 秒。而不同接收端对长度的偏好
 * 相反：
 *  - DiPlay（Carlinkit AuthV3）：只接受 **32B**，长度不符直接断链；
 *  - Audi MMI 6318：20B 秒答，32B 曾 0.4 秒拆链；
 *  - Audi MMI 3315：20B 静默，**32B** 才应答。
 * 所以既不能全局写死某一档，也不能只靠进程内记忆（App 重启即丢，每次重连都重付 6 秒）。
 * 这里按「车机身份（name+model+manufacturer）」持久化三件事：
 *
 *  - [Entry.succeeded]：最近一次**成功拿到签名**的长度 → 下次直接首选，省掉试探；
 *  - [Entry.silent]：窗口内无应答的长度 → 排到后面（但仍保留为兜底，车机固件可能变）；
 *  - [Entry.rejected]：旧版本留下的失败推断，仅用于排序，不视为协议拒绝证据。
 *  - [Entry.lastDisconnected]：最近一次挑战期间断链的候选 → 下次连接从下一候选开始。
 *
 * 排序规则见 [orderAttempts]（纯函数）：成功档位永远第一；其余沿用调用方给的基线顺序，
 * 最后才是 silent 与 rejected。若基线里所有档位都被判过 rejected，则全部照常尝试，
 * 避免永久卡死。
 */
object MfiLengthMemory {
    /** 单个车机的 MFi 记忆。 */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
        val lastDisconnected: Int? = null,
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty() && lastDisconnected == null
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_DISCONNECTED = "disconnected_"

    /** 车机身份 → 存储键。只用稳定字段，避免蓝牙名里的可变后缀影响命中。 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** 读取某车机的记忆（无 context 时退化为进程内内存表，便于单测）。 */
    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        val ok = prefs.getInt(KEY_OK + key, 0).takeIf { it > 0 }
        return Entry(
            succeeded = ok,
            silent = readLengths(prefs.getString(KEY_SILENT + key, null)),
            rejected = readLengths(prefs.getString(KEY_REJECTED + key, null)),
            lastDisconnected = prefs.getInt(KEY_DISCONNECTED + key, 0).takeIf { it > 0 },
        )
    }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            // 成功即证伪该档位的 silent/rejected 旧结论（固件升级后行为可能变）
            Entry(succeeded = length, silent = entry.silent - length, rejected = entry.rejected - length)
        }
    }

    fun recordSilent(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(silent = entry.silent + length, rejected = entry.rejected - length)
            }
        }
    }

    /**
     * 只记录传输层观察，不把断链等同于“不支持该长度”。保留以前的成功记录，
     * 但断链后的试探轮次暂时让位于跨连接轮换，直到再次收到可归属的响应。
     */
    fun recordDisconnected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            entry.copy(lastDisconnected = length)
        }
    }

    /** 打印用：把记忆压成一行日志（便于对照实测）。 */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "无"
        } else {
            buildList {
                entry.succeeded?.let { add("曾收到响应=${it}B") }
                if (entry.silent.isNotEmpty()) add("静默=${entry.silent.sorted().joinToString("/")}B")
                if (entry.rejected.isNotEmpty()) add("历史失败推断=${entry.rejected.sorted().joinToString("/")}B")
                entry.lastDisconnected?.let { add("上次断链挑战=${it}B（原因未定，下轮换候选）") }
            }.joinToString("，")
        }

    /**
     * 计算本轮的尝试顺序（纯函数）。
     *
     * @param baseline 调用方按接收端类型给出的基线顺序（长度列表）。
     * @param entry 该车机的持久化记忆。
     * @return 依次尝试的长度；保证非空且覆盖 baseline 的全部档位。
     */
    fun orderAttempts(
        baseline: List<Int>,
        entry: Entry,
    ): List<Int> {
        // 即使所有旧候选都标记过 rejected，也不能每轮又从 32B 开始。
        // 在独立连接间轮换，避免第一候选立即断链时永远触及不了后面的长度。
        val candidates = baseline.distinct()
        val last = candidates.indexOf(entry.lastDisconnected)
        if (last >= 0) return candidates.drop(last + 1) + candidates.take(last + 1)
        // 「成功但又被硬拒绝」= 记忆矛盾（迟到应答误记），不能继续享受第一名——
        // 降级为普通档参与排序（排在被拒档之前，仍留候选内：车机固件可能变）
        val succeeded = entry.succeeded?.takeIf { it !in entry.rejected }
        val rest = baseline.filter { it != succeeded }
        // 只有「还存在没被判死的档位」时才把 rejected 排到最后；
        // 全部 rejected 说明与记忆矛盾（换固件/换车同一身份），照常全试。
        val allRejected = rest.isNotEmpty() && rest.all { it in entry.rejected }
        val rejected = if (allRejected) emptySet() else entry.rejected.filter { it in baseline }.toSet()
        val silent = entry.silent.filter { it in rest && it !in rejected }.toSet()
        val normal = rest.filter { it !in silent && it !in rejected }
        val silentOrdered = rest.filter { it in silent }
        val rejectedOrdered = rest.filter { it in rejected }
        return (listOfNotNull(succeeded) + normal + silentOrdered + rejectedOrdered).distinct()
    }

    // ---- 内部 ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val current = load(context, identityText)
            val next = transform(current)
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.succeeded != null) putInt(KEY_OK + key, next.succeeded) else remove(KEY_OK + key)
                    putString(KEY_SILENT + key, next.silent.sorted().joinToString(","))
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                    if (next.lastDisconnected != null) putInt(KEY_DISCONNECTED + key, next.lastDisconnected) else remove(KEY_DISCONNECTED + key)
                }.apply()
        }
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
