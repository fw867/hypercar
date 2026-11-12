@file:Suppress("DEPRECATION")
package io.github.hypercar.spike

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import io.github.hypercar.net.WifiRequestOwner
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

    @Synchronized fun start(owner: IBinder, token: String, ssid: String, uid: Int): String {
        lease?.let { stop(it.token) }
        if (token.isBlank() || ssid.isBlank() || uid < 10000) return "ERR invalid Wi-Fi selection scope"
        return try {
            val wifi = ShellCarWifi.managers().first
            val type = Class.forName("android.net.wifi.WifiManager\$NetworkRequestMatchCallback")
            var selection: Any? = null
            var selected = false
            val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "HyperCarWifiSelection"
                    "onUserSelectionCallbackRegistration" -> { selection = args?.getOrNull(0); null }
                    "onMatch" -> {
                        val candidates = (args?.getOrNull(0) as? List<*>)?.filterIsInstance<ScanResult>().orEmpty()
                        runCatching { synchronized(this) {
                            if (lease?.token == token && !selected && selection != null) {
                                val ap = candidates.filter { it.SSID == ssid }.maxByOrNull { it.level }
                                if (ap != null && activeRequestOwned(uid, ssid)) {
                                    val config = WifiConfiguration().apply { SSID = "\"$ssid\""; BSSID = ap.BSSID }
                                    // The platform copies credentials from the app's original request.
                                    val selectionType = Class.forName("android.net.wifi.WifiManager\$NetworkRequestUserSelectionCallback")
                                    selectionType.getMethod("select", WifiConfiguration::class.java).invoke(selection, config)
                                    selected = true
                                    Log.i("HyperCar", "WIFI-SELECT: selected matched AP for own local-only request uid=$uid")
                                }
                            }
                        } }.onFailure {
                            Log.w("HyperCar", "WIFI-SELECT: selection failed ${(it.cause ?: it).javaClass.simpleName}")
                            stop(token)
                        }
                        null
                    }
                    "onAbort", "onUserSelectionConnectSuccess", "onUserSelectionConnectFailure" -> {
                        Log.i("HyperCar", "WIFI-SELECT: ${method.name}")
                        stop(token); null
                    }
                    else -> null
                }
            }
            val death = IBinder.DeathRecipient { stop(token) }
            lease = Lease(token, owner, death, wifi, type, callback)
            owner.linkToDeath(death, 0)
            wifi.javaClass.getMethod("registerNetworkRequestMatchCallback", java.util.concurrent.Executor::class.java, type)
                .invoke(wifi, worker, callback)
            worker.schedule({ stop(token) }, 60, TimeUnit.SECONDS)
            "OK shell local-only selector armed; own uid/SSID checked before selection"
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

    private fun activeRequestOwned(uid: Int, ssid: String): Boolean {
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
