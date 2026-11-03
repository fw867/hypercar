package io.github.hypercar.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Shizuku 在本机的四种状态（UI 据此给不同的下一步动作）。 */
enum class ShizukuState {
    /** 没装 Shizuku app。 */
    NotInstalled,

    /** 装了但服务没跑（需要在 Shizuku 里启动：无线调试配对 / root）。 */
    NotRunning,

    /** 服务在跑，但本应用还没被授权（可以弹授权框）。 */
    NotGranted,

    /** 已授权，`cmd wifi` 等 shell 能力可用。 */
    Ready,
}

/** 一次 shell 命令的执行结果。 */
data class ExecResult(
    val code: Int,
    val out: String,
    val err: String,
) {
    /** exit=0。注意：对 `cmd wifi connect-network` 而言 exit=0 **不代表已连上**（见 WifiJoiner）。 */
    val ok: Boolean get() = code == 0
}

/**
 * 把任意字符串安全地嵌进 shell 单行命令：整体用单引号包裹，内部单引号转义为 `'\''`。
 * SSID/PSK 可能含空格、`$`、`"`、中文——不转义会被 sh 拆词或做变量展开。
 */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Shizuku shell 网关：以 shell（uid 2000）身份执行命令，等同 `adb shell`。
 *
 * 激活：开发者选项 → 无线调试 → 配对 → Shizuku App 启动（免 root）。
 * 依赖 `dev.rikka.shizuku:api:13.1.5`；`newProcess` 走 `IShizukuService` binder
 * （与 cardeck `shizuku/Shizuku.kt` 同款做法）。
 *
 * **主线程守卫**：每条 exec 都是 `newProcess` + 进程等待，正常 ~100ms、超时上限可达数秒；
 * cardeck 两次真机 ANR 的共同根因就是主线程 Shell.exec。这里检测到主线程调用时先记日志、
 * 再断言抛 [IllegalStateException]——调用方必须切到后台（如 Dispatchers.IO / Bg.run）。
 *
 * 注：cardeck 用 addLifecycleObserver 自动感知 binder；本类简化为**手动 API**
 * （[isReady] / [requestPermissionIfNeeded]），由会话层在合适时机查询与请求授权。
 */
object ShizukuTools {
    private const val TAG = "ShizukuTools"

    /** Shizuku 授权请求码（授权结果回调按它过滤）。 */
    const val PERMISSION_REQUEST_CODE = 201

    /** 主线程调用 exec 的累计次数（诊断用；出现即说明有新代码走了 UI 线程）。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /** Shizuku 服务在运行且本应用已获授权。 */
    val isReady: Boolean
        get() = try {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    /** Shizuku app 是否已安装（manifest 里有 `<queries>`，Android 11+ 才能查到）。 */
    @Suppress("DEPRECATION")
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    /**
     * 当前状态。UI 用它决定给"安装 / 打开 / 授权 / 已就绪"哪个动作。
     *
     * 判定顺序：先看 binder 是否活着（`pingBinder`），再看本应用是否被授权。
     */
    fun state(context: Context): ShizukuState {
        val alive = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return if (isInstalled(context)) ShizukuState.NotRunning else ShizukuState.NotInstalled
        }
        val granted = runCatching {
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.Ready else ShizukuState.NotGranted
    }

    /** 打开 Shizuku app 主界面（未运行/未安装时引导用户去处理）。 */
    fun openShizukuApp(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** GitHub 最新正式发布页，随上游更新而变化，不固定 APK 版本。 */
    fun openShizukuSite(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 需要时请求 Shizuku 授权，结果经 [onResult] 回调（true=已授权）。
     * 已授权 / 未安装 / 未运行时立即回调 false 或 true，不弹窗。
     */
    fun requestPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                onResult(false)
                return
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onResult(true)
                return
            }
            val listener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this) }
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            onResult(false)
        }
    }

    /**
     * 以 shell 身份执行单行命令（调用方自行拼好；参数一律用 [shellQuote] 转义）。
     *
     * 阻塞实现跑在 [Dispatchers.IO]；**必须在后台上下文调用**——在主线程上下文调用会
     * 记日志并抛 [IllegalStateException]（见类注释的 ANR 教训）。
     *
     * @param timeoutMs 等待进程退出的上限；超时后 destroy 并返回已收集的输出。
     */
    suspend fun exec(command: String, timeoutMs: Long = 8_000): ExecResult {
        assertNotMainThread(command)
        return withContext(Dispatchers.IO) { doExec(command, timeoutMs) }
    }

    /** 主线程守卫：记录并断言，绝不放行到进程等待。 */
    private fun assertNotMainThread(command: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainThreadViolations++
            Log.e(TAG, "主线程调用 shell（第 $mainThreadViolations 次）cmd=${command.take(80)}——必须切到后台线程")
            throw IllegalStateException("ShizukuTools.exec 禁止主线程调用（cmd=${command.take(60)}）")
        }
    }

    private fun doExec(command: String, timeoutMs: Long): ExecResult {
        val binder = rikka.shizuku.Shizuku.getBinder()
            ?: return ExecResult(-1, "", "Shizuku 未连接")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            // stderr 独立线程排空：否则 stderr 填满管道会让进程阻塞在写端、stdout 永远读不完
            val errRef = AtomicReference<ByteArray?>(null)
            val errThread = Thread {
                try {
                    process.errorStream.use { pfd ->
                        errRef.set(FileInputStream(pfd.fileDescriptor).readBytes())
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val out = try {
                process.inputStream.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                }
            } catch (_: Throwable) {
                ""
            }
            val finished = try {
                process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())
            } catch (_: Throwable) {
                false
            }
            if (!finished) runCatching { process.destroy() }
            runCatching { errThread.join(1_000) }
            val code = try {
                process.exitValue()
            } catch (_: Throwable) {
                -1
            }
            ExecResult(code, out, errRef.get()?.decodeToString()?.trim() ?: "")
        } catch (t: Throwable) {
            ExecResult(-1, "", "命令执行失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
