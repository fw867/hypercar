@file:Suppress("DEPRECATION")
package io.github.hypercar.spike

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import io.github.hypercar.net.WifiRequestOwner
import io.github.hypercar.net.WifiRequestRegistration
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shell NETWORK_SETTINGS selects an already requested AP; never creates a system Wi-Fi network.
 * One app-owned lease, bounded by owner death, explicit cancellation and an independent deadline.
 */
internal class ShellWifiSelection {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "car-wifi-selection").apply { isDaemon = true }
    }
    private data class Lease(val token: String, val binder: IBinder, val death: IBinder.DeathRecipient,
        val wifi: WifiManager, val callbackType: Class<*>, val callback: Any)
    private var lease: Lease? = null

    @Synchronized fun start(owner: IBinder, token: String, ssid: String, bssid: String, uid: Int): String {
        lease?.let { stop(it.token) }
        if (token.isBlank() || ssid.isBlank() || uid < 10000) return "ERR invalid Wi-Fi selection scope"
        return try {
            val wifi = ShellCarWifi.managers().first
            val type = Class.forName("android.net.wifi.WifiManager\$NetworkRequestMatchCallback")
            val gate = WifiRequestRegistration()
            var selection: Any? = null
            var selected = false
            var registered = false
            lateinit var register: () -> Unit
            fun choose(candidates: List<ScanResult>, cached: Boolean) {
                synchronized(this) {
                    if (lease?.token != token || selected || selection == null) return
                    val ap = candidates.filter { it.SSID == ssid && (bssid.isBlank() || it.BSSID.equals(bssid, true)) &&
                        (!cached || android.os.SystemClock.elapsedRealtime() - it.timestamp / 1000 in 0..300_000) }
                        .maxByOrNull { it.level } ?: return
                    if (!activeRequestOwned(uid, ssid)) return
                    val config = WifiConfiguration().apply { SSID = "\"$ssid\""; BSSID = ap.BSSID }
                    val selectionType = Class.forName("android.net.wifi.WifiManager\$NetworkRequestUserSelectionCallback")
                    selectionType.getMethod("select", WifiConfiguration::class.java).invoke(selection, config)
                    selected = true
                    Log.i("HyperCar", "WIFI-SELECT: selected own local-only request uid=$uid cached_exact_ap=$cached")
                }
            }
            val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                try {
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        "toString" -> "HyperCarWifiSelection"
                        "onUserSelectionCallbackRegistration" -> {
                            gate.acknowledge()
                            selection = args?.getOrNull(0)
                            // A fresh iAP2 MAC corroborated by a matching scan can be selected
                            // directly. No need to wait for a second scan that this ROM aborts.
                            if (bssid.isNotBlank()) {
                                try { choose(wifi.scanResults, cached = true) }
                                catch (_: SecurityException) {
                                    Log.w("HyperCar", "WIFI-SELECT: cached scan permission denied; wait for owned onMatch callback")
                                }
                            }
                            null
                        }
                        "onMatch" -> {
                            choose((args?.getOrNull(0) as? List<*>)?.filterIsInstance<ScanResult>().orEmpty(), cached = false)
                            null
                        }
                        "onAbort" -> {
                            if (gate.retryAfterEarlyAbort()) {
                                Log.i("HyperCar", "WIFI-SELECT: factory not active yet; bounded registration retry")
                                worker.schedule({ register() }, 150, TimeUnit.MILLISECONDS)
                            } else {
                                Log.i("HyperCar", "WIFI-SELECT: active request ended")
                                stop(token)
                            }
                            null
                        }
                        "onUserSelectionConnectSuccess", "onUserSelectionConnectFailure" -> {
                            Log.i("HyperCar", "WIFI-SELECT: ${method.name}"); stop(token); null
                        }
                        else -> null
                    }
                } catch (e: Exception) {
                    Log.w("HyperCar", "WIFI-SELECT: callback failed ${(e.cause ?: e).javaClass.simpleName}")
                    stop(token); null
                }
            }
            register = {
                synchronized(this) {
                    if (lease?.token == token) {
                        if (!gate.beginAttempt()) stop(token)
                        else if (!activeRequestOwned(uid, ssid)) {
                            worker.schedule({ register() }, 150, TimeUnit.MILLISECONDS)
                        } else {
                            try {
                                if (registered) wifi.javaClass.getMethod("unregisterNetworkRequestMatchCallback", type).invoke(wifi, callback)
                                wifi.javaClass.getMethod("registerNetworkRequestMatchCallback", java.util.concurrent.Executor::class.java, type)
                                    .invoke(wifi, worker, callback)
                                registered = true
                            } catch (e: Exception) {
                                Log.w("HyperCar", "WIFI-SELECT: registration failed ${(e.cause ?: e).javaClass.simpleName}")
                                stop(token)
                            }
                        }
                    }
                }
            }
            val death = IBinder.DeathRecipient { stop(token) }
            lease = Lease(token, owner, death, wifi, type, callback)
            owner.linkToDeath(death, 0)
            worker.execute { register() }
            worker.schedule({ stop(token) }, 60, TimeUnit.SECONDS)
            "OK shell selector armed; await own factory request before registration"
        } catch (e: Exception) {
            stop(token)
            "ERR Wi-Fi selector ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message?.take(160)}"
        }
    }

    @Synchronized fun stop(token: String): String {
        val current = lease?.takeIf { it.token == token } ?: return "SKIP Wi-Fi selector inactive"
        lease = null
        runCatching { current.binder.unlinkToDeath(current.death, 0) }
        runCatching { current.wifi.javaClass.getMethod("unregisterNetworkRequestMatchCallback", current.callbackType)
            .invoke(current.wifi, current.callback) }
        return "OK Wi-Fi selector released"
    }

    companion object {
    fun activeRequestOwned(uid: Int, ssid: String): Boolean {
        // The callback API does not expose the requestor. Read only the active factory request;
        // never approve another app's request, even when it names the same AP.
        val process = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "wifi").redirectErrorStream(true).start()
        return try {
            val own = process.inputStream.bufferedReader().useLines { lines ->
                lines.any { WifiRequestOwner.matches(it, uid, ssid) }
            }
            own
        } finally { process.destroy() }
    }
    }
}
