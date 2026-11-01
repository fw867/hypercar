package io.github.hypercar.spike

import android.content.Context
import android.os.Looper
import android.util.Log
import io.github.hypercar.bluetooth.DiscoverabilityLease
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 由 shell 启动的独立有界进程。父服务死亡时 stdin EOF，仍能恢复蓝牙状态。 */
object BluetoothDiscoveryWatchdog {
    @JvmStatic fun main(args: Array<String>) {
        var controller: DiscoverabilityLease? = null
        val token = args.firstOrNull() ?: return
        val endResult = AtomicReference<String?>(null)
        val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bt-discovery-lease").apply { isDaemon = true }
        }
        try {
            Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val port = ShellBluetoothDiscovery.AdapterPort(context)
            if (token == "--probe") {
                println("OK mode=${port.mode()} timeout=${port.timeoutSeconds()}s")
                return
            }
            val seconds = args.getOrNull(1)?.toIntOrNull() ?: error("缺少窗口时长")
            val done = CountDownLatch(1)
            val lease = DiscoverabilityLease(port,
                schedule = { delay, close ->
                    val task = timer.schedule({ try { close() } finally { done.countDown() } }, delay, TimeUnit.MILLISECONDS)
                    Closeable { task.cancel(false) }
                }, log = { endResult.set(it); Log.i("HyperCar-BT-Discovery", it) },
            )
            controller = lease
            val result = lease.begin(token, seconds)
            println(result)
            System.out.flush()
            if (!result.startsWith("OK")) return
            Thread({
                try { System.`in`.read() } finally {
                    lease.end(token)
                    done.countDown()
                }
            }, "bt-discovery-owner").apply { isDaemon = true; start() }
            done.await(seconds + 2L, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            println("ERR 可发现守护进程：${t.cause?.message ?: t.message}")
        } finally {
            val fallback = controller?.end(token)
            if (controller != null) println("END ${endResult.get() ?: fallback}")
            timer.shutdownNow()
        }
    }
}
