package io.github.hypercar.update

import android.content.Context
import io.github.hypercar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 自动更新：读 GitHub Releases API 比较版本。
 *
 * 版本约定（见 app/build.gradle.kts 与 .github/workflows/android-ci.yml）：
 *  - CI 每次推送 `versionName = 0.1.0.<run_number>`、`versionCode = 10000 + run`
 *  - Release tag = `v0.1.0.<run_number>`，资产名 `HyperCar-<版本>-release.apk`
 * 所以「本地版本 < 最新 tag」即视为有新版本；本地 develop 构建没有 run_number
 * （`0.1.0`），因此永远会被判定为"可更新"——这是期望行为。
 *
 * 不引第三方 HTTP/JSON 依赖：`HttpURLConnection` + `org.json` 足够，
 * 也避免为了一个检查功能把 APK 撑大（本项目刚把体积压到 3.3MB）。
 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSize: Long,
    val publishedAt: String,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latest: String) : UpdateUiState
    data class Available(val info: ReleaseInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

object UpdateCenter {

    private const val API = "https://api.github.com/repos/fw867/hypercar/releases/latest"
    private const val PREFS = "hypercar_update"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_DISMISSED = "dismissed_tag"

    /** 自动检查间隔：6 小时（手动检查不受限）。 */
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    /** 当前安装版本（带 run_number 的形式，如 0.1.0.42）。 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    /** 启动时是否该自动检查（距上次检查超过 6 小时）。 */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    /** 用户已经点过"稍后"的版本不再自动弹窗（仍可在设置里手动检查）。 */
    fun isDismissed(context: Context, tag: String): Boolean =
        prefs(context).getString(KEY_DISMISSED, null) == tag

    fun dismiss(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_DISMISSED, tag).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 查询最新 Release 并与本地版本比较。
     * @param force 手动检查：忽略 6 小时间隔（但仍会写入检查时间）
     */
    suspend fun check(context: Context) {
        _state.value = UpdateUiState.Checking
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        _state.value = try {
            val latest = fetchLatest(context)
            when {
                latest == null -> UpdateUiState.Failed("仓库还没有发布任何 Release")
                isNewer(latest.version, currentVersion) -> UpdateUiState.Available(latest)
                else -> UpdateUiState.UpToDate(latest.version)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            UpdateUiState.Failed(e.message ?: "检查失败")
        }
    }

    private suspend fun fetchLatest(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        // 会话期间进程被绑到车机热点（无外网）——必须挑一条真有外网的网络，
        // 否则「检查更新」100% 失败（2026-10-25 验证）。
        val conn = io.github.hypercar.net.InternetRoute.open(context, API).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub 要求带 User-Agent，否则 403
            setRequestProperty("User-Agent", "HyperCar/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code == 403) {
                // 未认证请求 60 次/小时；超限时给出可读提示
                throw IllegalStateException("GitHub API 限流（稍后再试）")
            }
            if (code !in 200..299) throw IllegalStateException("GitHub 返回 HTTP $code")

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").ifBlank { return@withContext null }
            val version = tag.removePrefix("v").removePrefix("V")
            val apk = json.optJSONArray("assets")
                ?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                }
            ReleaseInfo(
                tag = tag,
                version = version,
                notes = json.optString("body").trim(),
                pageUrl = json.optString("html_url"),
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
                apkSize = apk?.optLong("size") ?: 0L,
                publishedAt = json.optString("published_at"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 版本比较：按 `.` 拆段做数值比较，缺失段按 0 处理（`0.1.0` < `0.1.0.42`）。
     * 非数字后缀（如 `-beta`）忽略，只比较数字段。
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun segments(v: String) = v.split('.', '-', '+')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

}
