package io.github.hypercar.spike

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import io.github.hypercar.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * SPIKE-P0：绑定 Shizuku UserService（shell 进程），在那里建 D2。
 *
 * 调用方必须在 IO 线程：bind 本身要切主线程（Shizuku 回调走主线程），
 * 建屏/释放是 binder 事务，别放主线程。
 */
object SpikeShell {

    @Volatile
    private var service: ISpikeShell? = null

    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null

    fun isConnected(): Boolean = service != null

    /** 绑定（幂等）。返回人话结果，写进 spike 报告。 */
    suspend fun connect(context: Context): String {
        service?.let { return "已连接" }
        if (!Shizuku.pingBinder()) return "Shizuku 未运行"
        val deferred = CompletableDeferred<String>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    deferred.complete("onServiceConnected binder=null")
                    return
                }
                service = ISpikeShell.Stub.asInterface(binder)
                deferred.complete("connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                SpikeReport.add("SHELL", "user service 断开")
            }
        }
        connection = conn
        val a = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, SpikeShellService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("spike")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        args = a
        // 注意：**不要**在 Main 线程调 bindUserService。Shizuku 13.6 在某些机型上会同步等待
        // 用户服务把 binder 交回来；放主线程会与「回调也要主线程」互相等死（真机踩过：
        // 主线程卡住、日志停在 bind 前，行为像 ANR 但没有 ANR 记录）。
        SpikeReport.add("SHELL", "调用 bindUserService → ${context.packageName}:spike")
        val called = try {
            Shizuku.bindUserService(a, conn)
            SpikeReport.add("SHELL", "bindUserService 已返回")
            true
        } catch (t: Throwable) {
            SpikeReport.add(
                "SHELL",
                "bindUserService 异常: ${t.javaClass.simpleName}: ${t.message}",
            )
            false
        }
        if (!called) return "bindUserService 调用失败"
        val result = withTimeoutOrNull(12_000) { deferred.await() } ?: "绑定超时（12s）"
        SpikeReport.add("SHELL", "bind 结果: $result")
        return result
    }

    fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int = 0,
        name: String = "hypercar-d2",
    ): String = runCatching {
        service?.createDisplay(surface, width, height, dpi, flags, name) ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    fun releaseDisplay(): String = runCatching {
        service?.releaseDisplay() ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    /** P2：只释放指定副屏（D1/D2 并存）。 */
    fun releaseDisplay(displayId: Int): String = runCatching {
        service?.releaseDisplayById(displayId) ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    fun status(): String = runCatching {
        service?.status() ?: "ERR 未连接"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    /** 真息屏：物理面板断电（系统保持唤醒），false=断电，true=点亮。 */
    fun setPhysicalScreenPower(on: Boolean): Boolean = runCatching {
        service?.setPhysicalScreenPower(on) ?: false
    }.getOrDefault(false)

    fun motion(displayId: Int, action: Int, x: Int, y: Int, downTimeMs: Long): String =
        call("motion") { it.motion(displayId, action, x, y, downTimeMs) }

    fun tap(displayId: Int, x: Int, y: Int): String =
        call("tap") { it.tap(displayId, x, y) }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        call("swipe") { it.swipe(displayId, x1, y1, x2, y2, durationMs) }

    fun pinch(displayId: Int, cx: Int, cy: Int, from: Int, to: Int, steps: Int): String =
        call("pinch") { it.pinch(displayId, cx, cy, from, to, steps) }

    fun inputState(): String = call("inputState") { it.inputState() }

    private inline fun call(name: String, block: (ISpikeShell) -> String): String =
        runCatching { service?.let(block) ?: "ERR 未连接" }.getOrElse {
            "ERR $name ${it.javaClass.simpleName}: ${it.message}"
        }

    fun disconnect() {
        val a = args
        val c = connection
        if (a != null && c != null) {
            runCatching { Shizuku.unbindUserService(a, c, false) }
        }
        service = null
        connection = null
        args = null
    }
}
