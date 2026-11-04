package io.github.hypercar.spike

import android.bluetooth.BluetoothAdapter
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.hypercar.bluetooth.DiscoverabilityLease
import java.time.Duration
import java.io.BufferedReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 仅在 shell UserService 内调用，使用 shell 自己的权限和 attribution。 */
internal class ShellBluetoothDiscovery {
    private val reader = Executors.newCachedThreadPool { r ->
        Thread(r, "bt-discoverable-start").apply { isDaemon = true }
    }
    private data class Owner(val token: String, val binder: IBinder, val death: IBinder.DeathRecipient,
        val process: java.lang.Process, val output: BufferedReader)
    private var owner: Owner? = null

    @Synchronized fun start(context: Context?, binder: IBinder, token: String, seconds: Int): String {
        owner?.let {
            if (it.process.isAlive) return "SKIP 已有可发现请求"
            clearOwner(it.token)
        }
        if (seconds !in 1..30) return "ERR 窗口必须为 1..30 秒"
        return try {
            val apk = context?.applicationInfo?.sourceDir ?: error("缺少 shell APK 路径")
            val process = ProcessBuilder("/system/bin/app_process", "/system/bin",
                "io.github.hypercar.spike.BluetoothDiscoveryWatchdog", token, seconds.toString())
                .redirectErrorStream(true).apply { environment()["CLASSPATH"] = apk }.start()
            val death = IBinder.DeathRecipient { stop(token) }
            val output = process.inputStream.bufferedReader()
            owner = Owner(token, binder, death, process, output)
            binder.linkToDeath(death, 0)
            val result = reader.submit<String> {
                run {
                    var line = output.readLine()
                    while (line != null && !line.startsWith("OK") && !line.startsWith("ERR") && !line.startsWith("SKIP")) {
                        line = output.readLine()
                    }
                    line ?: "ERR 可发现守护进程未返回结果"
                }
            }.get(5, TimeUnit.SECONDS)
            if (!result.startsWith("OK")) stop(token)
            result
        } catch (t: Exception) {
            stop(token)
            "ERR shell 蓝牙可发现：${t.cause?.message ?: t.message}"
        }
    }

    @Synchronized fun stop(token: String): String {
        val request = owner?.takeIf { it.token == token } ?: return "SKIP 未开启本轮可发现"
        // 管道 EOF 是独立守护进程的提前结束信号；UserService 被杀时也会自动产生 EOF。
        runCatching { request.process.outputStream.close() }
        val ended = runCatching { request.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        val result = if (ended) runCatching {
            request.output.readLines().lastOrNull { it.startsWith("END ") }?.removePrefix("END ")
        }.getOrNull() else null
        clearOwner(token)
        return result ?: if (ended) "ERR 可发现守护进程已结束但无清理回执"
            else "ERR 已请求结束可发现，守护进程尚未退出"
    }

    private fun clearOwner(token: String) {
        owner?.takeIf { it.token == token }?.let {
            runCatching { it.binder.unlinkToDeath(it.death, 0) }
            runCatching { it.process.outputStream.close() }
            // 启动超时的读取线程可能还阻塞在 readLine；不能在连接线程等待它的 reader 锁。
            if (!it.process.isAlive) runCatching { it.output.close() }
            owner = null
        }
    }

    internal class AdapterPort(context: Context?) : DiscoverabilityLease.Port {
        private val adapter: BluetoothAdapter
        init {
            // Headless shell 不一定初始化蓝牙模块；否则 getDefaultAdapter 会静默返回 null。
            val init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            synchronized(ShellBluetoothDiscovery::class.java) {
                if (init.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                    val service = Class.forName("android.os.BluetoothServiceManager")
                    init.getMethod("setBluetoothServiceManager", service).invoke(null, service.getConstructor().newInstance())
                }
            }
            val attribution = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
            val create = BluetoothAdapter::class.java.declaredMethods.firstOrNull {
                it.name == "createAdapter" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Context::class.java || it.parameterTypes[0] == AttributionSource::class.java)
            } ?: error("系统未提供适配的蓝牙入口")
            create.isAccessible = true
            val argument = if (create.parameterTypes[0] == Context::class.java) {
                object : ContextWrapper(context ?: error("缺少 shell Context")) {
                    override fun getPackageName() = "com.android.shell"
                    override fun getOpPackageName() = "com.android.shell"
                    override fun getAttributionSource() = attribution
                }
            } else attribution
            adapter = create.invoke(null, argument) as? BluetoothAdapter ?: error("蓝牙适配器不可用")
            check(adapter.isEnabled) { "蓝牙未开启" }
        }

        // Executed in the Shizuku UserService with shell UID, not the application process.
        @android.annotation.SuppressLint("MissingPermission")
        override fun mode() = adapter.scanMode
        override fun timeoutSeconds(): Long {
            val result = BluetoothAdapter::class.java.getMethod("getDiscoverableTimeout").invoke(adapter)
            return when (result) { is Duration -> result.seconds; is Number -> result.toLong(); else -> error("无法读取蓝牙超时") }
        }
        override fun setTimeoutSeconds(seconds: Long) {
            val method = BluetoothAdapter::class.java.methods.firstOrNull {
                it.name == "setDiscoverableTimeout" && it.parameterTypes.contentEquals(arrayOf(Duration::class.java))
            } ?: error("系统不支持有界可发现超时")
            checked(method.invoke(adapter, Duration.ofSeconds(seconds)))
        }
        override fun setMode(mode: Int) {
            val method = BluetoothAdapter::class.java.getMethod("setScanMode", Int::class.javaPrimitiveType)
            checked(method.invoke(adapter, mode))
        }
        private fun checked(result: Any?) {
            check(result == null || result == true || (result is Int && result == 0)) { "蓝牙 API 拒绝，返回=$result" }
        }
    }
}
