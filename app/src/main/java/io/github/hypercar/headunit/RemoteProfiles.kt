package io.github.hypercar.headunit

import android.content.Context

import io.github.hypercar.BuildConfig
import io.github.hypercar.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
 * 除 `id` 外都可省略；省略字段继承同 id 内置档案，新 id 使用 BMW 默认模板。
 */
object RemoteProfiles {

    /**
     * 本 App 理解的配置 schema 版本。
     *
     * 为什么需要：远程同 id 会覆盖内置，而**旧缓存**里可能写着已经验证是错的字段值
     * （2026-10-25 就踩了这个：内置已把宝马的 `sendCarPlayAvailability` 等改回 `true`，
     * 但设备上缓存的 v1 JSON 仍是 `false`，覆盖后 CarPlay 图标继续不出现）。
     * 规则：**`_schema` 低于本档案要求版本的条目，只能新增车型，不能覆盖该内置档案**
     * ——这样"修内置档案"必须显式升 schema 才会生效，旧缓存不会把修复顶回去。
     */
    const val SCHEMA = 13
    private const val PROFILE_DESCRIPTIONS_SCHEMA = 13

    /** 档案列表地址（raw.githubusercontent.com，改 `main` 为其它分支即可灰度）。 */
    const val DEFAULT_URL =
        "https://raw.githubusercontent.com/fw867/hypercar/main/profiles/headunits.json"

    /** 设置页可改下载源（自建镜像/私有分支），空则用 [DEFAULT_URL]。 */
    fun url(): String =
        SettingsStore.remoteProfilesUrl.ifBlank { DEFAULT_URL }

    /**
     * 原始 JSON、档案和 schema 一起发布。刷新与建链可能在不同线程。
     */
    @Volatile private var memo: Pair<String, Parsed>? = null

    internal fun cached(): Parsed {
        val raw = SettingsStore.remoteHeadUnitProfilesJson
        memo?.takeIf { it.first == raw }?.let { return it.second }
        val parsed = runCatching { parseSnapshot(raw) }.getOrDefault(Parsed(0, emptyList()))
        memo = raw to parsed
        return parsed
    }

    private fun HeadUnitProfile.supportsAppVersion(appVersionCode: Int): Boolean =
        minVersionCode?.let { appVersionCode >= it } ?: true

    /** MGU resource lifecycle changed in schema 12; preserve other profiles' cache contracts. */
    internal fun canOverrideBuiltin(id: String, schema: Int): Boolean =
        schema >= when (id) {
            "bmw_id85" -> 12
            "bmw_id6" -> 11
            "mercedes_mbux" -> 10
            "bmw_id9" -> 9
            else -> 8
        }

    /**
     * 把内置表与远程表合并：内置保持原顺序（远程可覆盖同 id），远程独有项追加。
     *
     * @param remoteSchema 远程配置声明的 `_schema`。**低于本档案要求版本时不允许覆盖内置**
     *    （旧缓存会把已经修好的内置值顶回去，2026-10-25 现场教训），只能新增车型。
     */
    fun merge(
        builtin: List<HeadUnitProfile>,
        remote: List<HeadUnitProfile>,
        remoteSchema: Int = SCHEMA,
        appVersionCode: Int = BuildConfig.VERSION_CODE,
    ): List<HeadUnitProfile> = mergeCatalog(builtin, Parsed(remoteSchema, remote), appVersionCode).profiles

    internal data class Selection(
        val profile: HeadUnitProfile?,
        val lockedProfile: HeadUnitProfile? = null,
        val remote: Boolean = false,
    ) {
        val label: String get() = profile?.label ?: lockedProfile?.label ?: "档案不可用"
        val blockReason: String? get() = when {
            profile != null -> null
            lockedProfile != null -> "所选车机档案需要 App 安装编号 ${lockedProfile.minVersionCode} 或更高，请升级或重新选择"
            else -> "所选车机档案已不可用，请在「我的车机」重新选择"
        }
    }

    internal data class Catalog(
        val profiles: List<HeadUnitProfile>,
        val remoteIds: Set<String>,
        private val locked: Map<String, HeadUnitProfile>,
    ) {
        fun select(id: String): Selection {
            val profile = profiles.firstOrNull { it.id == id }
            return if (profile != null) Selection(profile, remote = id in remoteIds) else Selection(null, locked[id])
        }
    }

    internal fun mergeCatalog(
        builtin: List<HeadUnitProfile>,
        remote: Parsed,
        appVersionCode: Int = BuildConfig.VERSION_CODE,
    ): Catalog {
        val builtinIds = builtin.map { it.id }.toSet()
        val applicable = remote.profiles.filter {
            it.supportsAppVersion(appVersionCode) && (it.id !in builtinIds || canOverrideBuiltin(it.id, remote.schema))
        }.associateBy { it.id }
        val active = builtin.map { base ->
            val update = applicable[base.id] ?: return@map base.connectionPolicy()
            val current = when {
                remote.schema >= PROFILE_DESCRIPTIONS_SCHEMA -> update
                base.verified && !update.verified ->
                    update.copy(model = base.model, notes = base.notes, verified = true, pairingHint = base.pairingHint)
                else -> update.copy(notes = base.notes)
            }
            current.connectionPolicy()
        } + applicable.values.filter { it.id !in builtinIds }
        val locked = remote.profiles.filterNot { it.supportsAppVersion(appVersionCode) }
            .groupBy { it.id }.mapValues { (_, profiles) -> profiles.minBy { checkNotNull(it.minVersionCode) } }
        return Catalog(active, applicable.keys, locked)
    }

    /** 从 [url] 拉取并落盘；返回当前版本实际应用的远程档案数。 */
    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        val body = httpGet(context, url())
        // 先解析再落盘：解析不了就保持旧缓存，避免把设置页搞成空列表
        val parsed = parseSnapshot(body)
        require(parsed.profiles.isNotEmpty()) { "配置里没有任何档案" }
        SettingsStore.remoteHeadUnitProfilesJson = body
        SettingsStore.remoteProfilesFetchedAt = System.currentTimeMillis()
        memo = body to parsed
        mergeCatalog(HeadUnitProfiles.builtin, parsed).remoteIds.size
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
    fun parse(raw: String): List<HeadUnitProfile> = parseSnapshot(raw).profiles

    /** 解析结果（含顶层 `_schema`；旧格式没写就按 0 处理）。 */
    data class Parsed(
        val schema: Int,
        val profiles: List<HeadUnitProfile>,
    )

    internal fun parseSnapshot(raw: String): Parsed {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return Parsed(0, readProfiles(JSONArray(trimmed)))
        val root = JSONObject(trimmed)
        val schema = if (root.has("_schema")) root.optInt("_schema", 0) else root.optInt("schema", 0)
        val profiles = root.optJSONArray("profiles")?.let { readProfiles(it) }.orEmpty()
        // Old clients read only "profiles". Keep gated entries in a separate array.
        val versioned = root.optJSONArray("versionedProfiles")?.let { readProfiles(it, requireVersion = true) }.orEmpty()
        return Parsed(schema, profiles + versioned)
    }

    private fun readProfiles(array: JSONArray, requireVersion: Boolean = false): List<HeadUnitProfile> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { readProfile(it, requireVersion) }
        }

    private fun readProfile(o: JSONObject, requireVersion: Boolean): HeadUnitProfile? {
        val id = o.optString("id").trim()
        if (id.isEmpty()) return null
        val minVersionCode = if (requireVersion || o.has("minVersionCode")) {
            (o.opt("minVersionCode") as? Number)?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        } else null
        // 默认值取**同 id 的内置档案**（不是一律取宝马）：远程只覆盖它显式写出的字段，
        // 没写的沿用内置值——否则新增的字段（如 dropProfilesBeforeIap2）会被数据类默认值
        // 悄悄覆盖，出现「内置已修好、远程一更新又变回去」的冲突。
        val builtin = HeadUnitProfiles.builtin.firstOrNull { it.id == id }
        val defaults = builtin ?: HeadUnitProfiles.BMW_ID85
        return HeadUnitProfile(
            id = id,
            label = o.optString("label").ifBlank { defaults.label },
            model = o.optString("model", defaults.model),
            notes = o.optString("notes", defaults.notes),
            verified = o.optBoolean("verified", builtin?.verified ?: false),
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
            requireAirplayActivation =
                o.optBoolean("requireAirplayActivation", defaults.requireAirplayActivation),
            advertiseOnCurrentWifi = o.optBoolean("advertiseOnCurrentWifi", defaults.advertiseOnCurrentWifi),
            requireFreshCtrlInvite = o.optBoolean("requireFreshCtrlInvite", defaults.requireFreshCtrlInvite),
            useScreenResourceLifecycle = o.optBoolean("useScreenResourceLifecycle", defaults.useScreenResourceLifecycle),
            mbuxScreenBootstrap = o.optBoolean("mbuxScreenBootstrap", defaults.mbuxScreenBootstrap),
            id9ScreenBootstrap = o.optBoolean("id9ScreenBootstrap", defaults.id9ScreenBootstrap),
            nbtAudioFirst = o.optBoolean("nbtAudioFirst", defaults.nbtAudioFirst),
            fallbackAirplayPorts = readPorts(o, defaults.fallbackAirplayPorts),
            touchDisplay = if (o.has("touchDisplay")) {
                o.optString("touchDisplay", "").ifBlank { null }
            } else defaults.touchDisplay,
            pairingHint = if (o.has("pairingHint")) {
                o.optString("pairingHint", "").ifBlank { null }
            } else defaults.pairingHint,
            minVersionCode = minVersionCode,
            listing = readListing(o, builtin?.listing ?: HeadUnitListing()),
        ).connectionPolicy()
    }

    private fun readListing(json: JSONObject, fallback: HeadUnitListing): HeadUnitListing {
        fun readList(key: String, default: List<String>): List<String> {
            val values = json.optJSONArray(key) ?: return default
            return (0 until values.length()).mapNotNull { index ->
                (values.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct()
        }
        return HeadUnitListing(
            category = HeadUnitCategory.entries.firstOrNull { it.key == json.optString("category") }
                ?: fallback.category,
            brandId = json.optString("brandId", fallback.brandId).trim().lowercase(Locale.ROOT),
            brandName = json.optString("brandName", fallback.brandName).trim(),
            vehicleModels = readList("vehicleModels", fallback.vehicleModels),
            platformName = json.optString("platformName", fallback.platformName).trim(),
            aliases = readList("aliases", fallback.aliases),
        )
    }

    private fun readPorts(o: JSONObject, fallback: List<Int>): List<Int> {
        val arr = o.optJSONArray("fallbackAirplayPorts") ?: return fallback
        val ports = (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..65535 }
        return ports.ifEmpty { fallback }
    }

    /** 把档案写回 JSON（导出/调试用；也方便「复制当前配置到剪贴板」这类扩展）。 */
    fun encode(list: List<HeadUnitProfile>): String {
        val profiles = JSONArray()
        val versioned = JSONArray()
        list.forEach { p ->
            val entries = if (p.minVersionCode == null) profiles else versioned
            entries.put(
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
                    put("requireAirplayActivation", p.requireAirplayActivation)
                    put("advertiseOnCurrentWifi", p.advertiseOnCurrentWifi)
                    put("requireFreshCtrlInvite", p.requireFreshCtrlInvite)
                    put("useScreenResourceLifecycle", p.useScreenResourceLifecycle)
                    put("mbuxScreenBootstrap", p.mbuxScreenBootstrap)
                    put("id9ScreenBootstrap", p.id9ScreenBootstrap)
                    put("nbtAudioFirst", p.nbtAudioFirst)
                    put("fallbackAirplayPorts", JSONArray(p.fallbackAirplayPorts))
                    put("touchDisplay", p.touchDisplay ?: "")
                    put("pairingHint", p.pairingHint ?: "")
                    p.minVersionCode?.let { put("minVersionCode", it) }
                    put("category", p.listing.category.key)
                    put("brandId", p.listing.brandId)
                    put("brandName", p.listing.brandName)
                    put("vehicleModels", JSONArray(p.listing.vehicleModels))
                    put("platformName", p.listing.platformName)
                    put("aliases", JSONArray(p.listing.aliases))
                },
            )
        }
        return JSONObject().put("_schema", SCHEMA).put("profiles", profiles).apply {
            if (versioned.length() > 0) put("versionedProfiles", versioned)
        }.toString(2)
    }
}
