package io.github.hypercar.headunit

import android.content.Context

import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程车机档案：从 GitHub 拉一份 JSON，**新增车型不用发版**。
 *
 * 为什么需要：内置档案（BMW / WinPlay / Audi）改一次就得重新出包、装 APK。
 * 但车机档案例行是"小改一个布尔"——让它走远程配置文件，用户点一下就能拿到新车型，
 * 我这边改完 JSON 直接 push 即可。
 *
 * 设计取舍：
 *  - **覆盖语义**：远程同 id 的档案会**覆盖**内置的（修内置的错不用发版）；
 *    远程独有的 id 追加在后面。内置档案永远存在，断网也不会"一个都没有"。
 *  - **缓存优先**：上次拉到的 JSON 存 [SettingsStore]，启动时直接用它，联网失败不影响可用性。
 *  - **无依赖**：用 `HttpURLConnection` + `org.json`（都是系统自带），不引 OkHttp。
 *
 * 文件格式见仓库 `profiles/headunits.json`，字段与 [HeadUnitProfile] 一一对应，
 * 除 `id/label` 外都可省略（省略即取默认值 = 真 iPhone 实测行为）。
 */
object RemoteProfiles {

    /**
     * 本 App 理解的配置 schema 版本。
     *
     * 为什么需要：远程同 id 会覆盖内置，而**旧缓存**里可能写着已经验证是错的字段值
     * （2026-10-25 就踩了这个：内置已把宝马的 `sendCarPlayAvailability` 等改回 `true`，
     * 但设备上缓存的 v1 JSON 仍是 `false`，覆盖后 CarPlay 图标继续不出现）。
     * 规则：**`_schema` 低于本常量的远程条目，只能新增车型，不能覆盖内置档案**
     * ——这样"修内置档案"必须显式升 schema 才会生效，旧缓存不会把修复顶回去。
     */
    const val SCHEMA = 3

    /** 档案列表地址（raw.githubusercontent.com，改 `main` 为其它分支即可灰度）。 */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json"

    /** 设置页可改下载源（自建镜像/私有分支），空则用 [DEFAULT_URL]。 */
    fun url(): String =
        SettingsStore.remoteProfilesUrl.ifBlank { DEFAULT_URL }

    /**
     * 解析结果缓存：设置页每次重组都会调 [cached]，重复 parse 几百字节
     * 虽然不贵，但没必要——按原始 JSON 串做一次记忆化。
     */
    private var memoRaw: String? = null
    private var memoList: List<HeadUnitProfile> = emptyList()
    private var memoSchema: Int = 0

    /** 上次拉取到的远程档案（解析失败时返回空表，不抛异常）。 */
    fun cached(): List<HeadUnitProfile> {
        ensureParsed()
        return memoList
    }

    /** 上次拉取到的远程配置的 `_schema`（0 = 旧格式/未声明）。 */
    fun cachedSchema(): Int {
        ensureParsed()
        return memoSchema
    }

    private fun ensureParsed() {
        val raw = SettingsStore.remoteHeadUnitProfilesJson
        if (raw.isBlank()) {
            memoRaw = raw
            memoList = emptyList()
            memoSchema = 0
            return
        }
        if (raw == memoRaw) return
        val parsed = runCatching { parseRaw(raw) }.getOrDefault(Parsed(0, emptyList()))
        memoRaw = raw
        memoList = parsed.profiles
        memoSchema = parsed.schema
    }

    /** 远程档案里是否有这个 id（用于 UI 打「远程」角标）。 */
    fun isRemote(id: String): Boolean = cached().any { it.id == id }

    /**
     * 把内置表与远程表合并：内置保持原顺序（远程可覆盖同 id），远程独有项追加。
     *
     * @param remoteSchema 远程配置声明的 `_schema`。**低于 [SCHEMA] 时不允许覆盖内置**
     *    （旧缓存会把已经修好的内置值顶回去，2026-10-25 现场教训），只能新增车型。
     */
    fun merge(
        builtin: List<HeadUnitProfile>,
        remote: List<HeadUnitProfile>,
        remoteSchema: Int = SCHEMA,
    ): List<HeadUnitProfile> {
        if (remote.isEmpty()) return builtin
        val remoteById = remote.associateBy { it.id }
        val merged =
            builtin.map { base ->
                val fromRemote = remoteById[base.id]
                if (fromRemote != null && remoteSchema >= SCHEMA) fromRemote else base
            }
        val builtinIds = builtin.map { it.id }.toSet()
        return merged + remote.filter { it.id !in builtinIds }
    }

    /** 从 [url] 拉取并落盘；返回本次拿到的档案数（失败抛异常，由调用方展示）。 */
    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        val body = httpGet(context, url())
        // 先解析再落盘：解析不了就保持旧缓存，避免把设置页搞成空列表
        val parsed = parseRaw(body)
        require(parsed.profiles.isNotEmpty()) { "配置里没有任何档案" }
        SettingsStore.remoteHeadUnitProfilesJson = body
        SettingsStore.remoteProfilesFetchedAt = System.currentTimeMillis()
        memoRaw = null // 让 cached() 重新解析
        parsed.profiles.size
    }

    private fun httpGet(
        context: Context,
        url: String,
    ): String {
        // **不能直接用 URL.openConnection()**：会话期间进程被绑到车机热点（无外网），
        // 更新车型列表会 100% 失败。InternetRoute 会挑一条真有外网的网络。
        val conn = io.github.hypercar.net.InternetRoute.open(context, url).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 兼容两种顶层形态：`[ {...}, {...} ]` 或 `{ "profiles": [ ... ] }`
     * （带 `version`/`_schema` 之类元信息时用后者更自然）。
     */
    fun parse(raw: String): List<HeadUnitProfile> = parseRaw(raw).profiles

    /** 解析结果（含顶层 `_schema`；旧格式没写就按 0 处理）。 */
    data class Parsed(
        val schema: Int,
        val profiles: List<HeadUnitProfile>,
    )

    private fun parseRaw(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return Parsed(0, (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::readProfile) })
        }
        val root = JSONObject(trimmed)
        val schema = if (root.has("_schema")) root.optInt("_schema", 0) else root.optInt("schema", 0)
        val array = root.optJSONArray("profiles") ?: JSONArray()
        return Parsed(schema, (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::readProfile) })
    }

    private fun readProfile(o: JSONObject): HeadUnitProfile? {
        val id = o.optString("id").trim()
        if (id.isEmpty()) return null
        // 默认值取**同 id 的内置档案**（不是一律取宝马）：远程只覆盖它显式写出的字段，
        // 没写的沿用内置值——否则新增的字段（如 dropProfilesBeforeIap2）会被数据类默认值
        // 悄悄覆盖，出现「内置已修好、远程一更新又变回去」的冲突。
        val defaults = HeadUnitProfiles.builtin.firstOrNull { it.id == id } ?: HeadUnitProfiles.BMW_ID85
        return HeadUnitProfile(
            id = id,
            label = o.optString("label").ifBlank { defaults.label },
            model = o.optString("model", defaults.model),
            notes = o.optString("notes", defaults.notes),
            verified = o.optBoolean("verified", defaults.verified),
            validatesDeviceId = o.optBoolean("validatesDeviceId", defaults.validatesDeviceId),
            sendCarPlayAvailability =
                o.optBoolean("sendCarPlayAvailability", defaults.sendCarPlayAvailability),
            openEaSessions = o.optBoolean("openEaSessions", defaults.openEaSessions),
            sendTransportIdentifier =
                o.optBoolean("sendTransportIdentifier", defaults.sendTransportIdentifier),
            replyPowerUpdates = o.optBoolean("replyPowerUpdates", defaults.replyPowerUpdates),
            dropProfilesBeforeIap2 =
                o.optBoolean("dropProfilesBeforeIap2", defaults.dropProfilesBeforeIap2),
            allowOutboundIap2 = o.optBoolean("allowOutboundIap2", defaults.allowOutboundIap2),
            abortWhenCarPlayWindowClosed =
                o.optBoolean("abortWhenCarPlayWindowClosed", defaults.abortWhenCarPlayWindowClosed),
            preferMdnsAirplay = o.optBoolean("preferMdnsAirplay", defaults.preferMdnsAirplay),
            fallbackAirplayPorts = readPorts(o, defaults.fallbackAirplayPorts),
            touchDisplay = o.optString("touchDisplay", "").ifBlank { defaults.touchDisplay },
            pairingHint = o.optString("pairingHint", "").ifBlank { null },
        )
    }

    private fun readPorts(o: JSONObject, fallback: List<Int>): List<Int> {
        val arr = o.optJSONArray("fallbackAirplayPorts") ?: return fallback
        val ports = (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..65535 }
        return ports.ifEmpty { fallback }
    }

    /** 把档案写回 JSON（导出/调试用；也方便「复制当前配置到剪贴板」这类扩展）。 */
    fun encode(list: List<HeadUnitProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("label", p.label)
                    put("model", p.model)
                    put("notes", p.notes)
                    put("verified", p.verified)
                    put("validatesDeviceId", p.validatesDeviceId)
                    put("sendCarPlayAvailability", p.sendCarPlayAvailability)
                    put("openEaSessions", p.openEaSessions)
                    put("sendTransportIdentifier", p.sendTransportIdentifier)
                    put("replyPowerUpdates", p.replyPowerUpdates)
                    put("dropProfilesBeforeIap2", p.dropProfilesBeforeIap2)
                    put("allowOutboundIap2", p.allowOutboundIap2)
                    put("abortWhenCarPlayWindowClosed", p.abortWhenCarPlayWindowClosed)
                    put("preferMdnsAirplay", p.preferMdnsAirplay)
                    put("fallbackAirplayPorts", JSONArray(p.fallbackAirplayPorts))
                    p.touchDisplay?.let { put("touchDisplay", it) }
                    p.pairingHint?.let { put("pairingHint", it) }
                },
            )
        }
        return JSONObject().put("profiles", arr).toString(2)
    }
}
