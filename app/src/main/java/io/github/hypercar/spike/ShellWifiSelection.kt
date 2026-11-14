@file:Suppress("DEPRECATION")
package io.github.hypercar.spike

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import io.github.hypercar.net.WifiRequestOwner
import io.github.hypercar.net.WifiRequestRegistration
import io.github.hypercar.net.WifiHandoffLease
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
        val wifi: WifiManager, val callbackType: Class<*>, val callback: Any, val recovery: WifiHandoffLease)
    private var lease: Lease? = null

    @Synchronized fun start(owner: IBinder, token: String, ssid: String, bssid: String, uid: Int): String {
        lease?.let { stop(it.token) }
        if (token.isBlank() || ssid.isBlank() || uid < 10000) return "ERR invalid Wi-Fi selection scope"
        return try {
            val (wifi, connectivity) = ShellCarWifi.managers()
            val recovery = WifiHandoffLease()
            val type = Class.forName("android.net.wifi.WifiManager\$NetworkRequestMatchCallback")
            fun savedNetworks(): List<WifiConfiguration> = try { wifi.configuredNetworks.orEmpty() }
            catch (denied: SecurityException) {
                Log.w("HyperCar", "WIFI-SELECT: saved network permission denied; handoff unavailable")
                throw denied
            }
            val gate = WifiRequestRegistration()
            var selection: Any? = null
            var selected = false
            var registered = false
            lateinit var register: () -> Unit
            fun choose(candidates: List<ScanResult>, cached: Boolean, since: Long = 0) {
                synchronized(this) {
                    if (lease?.token != token || selected || selection == null) return
                    val matching = candidates.filter { it.SSID == ssid && (bssid.isBlank() || it.BSSID.equals(bssid, true)) &&
                        (!cached || (android.os.SystemClock.elapsedRealtime() - it.timestamp / 1000 in 0..30_000 &&
                            it.timestamp / 1000 >= since)) }
                    // A polled cache without an AP hint must not choose between same-name APs.
                    if (cached && bssid.isBlank() && matching.map { it.BSSID.lowercase() }.distinct().size != 1) return
                    val ap = matching.maxByOrNull { it.level } ?: return
                    if (!activeRequestOwned(uid, ssid)) return
                    val config = WifiConfiguration().apply { SSID = "\"$ssid\""; BSSID = ap.BSSID }
                    val selectionType = Class.forName("android.net.wifi.WifiManager\$NetworkRequestUserSelectionCallback")
                    selectionType.getMethod("select", WifiConfiguration::class.java).invoke(selection, config)
                    selected = true
                    Log.i("HyperCar", "WIFI-SELECT: selected own local-only request uid=$uid cached_exact_ap=$cached")
                }
            }
            fun pollRecovery(since: Long, until: Long) {
                worker.schedule({
                    synchronized(this) {
                        if (lease?.token == token && !selected && android.os.SystemClock.elapsedRealtime() < until) {
                            try { choose(wifi.scanResults, cached = true, since = since) }
                            catch (_: SecurityException) {
                                Log.w("HyperCar", "WIFI-SELECT: recovery scan permission denied")
                            } catch (error: Exception) {
                                Log.w("HyperCar", "WIFI-SELECT: recovery scan read failed ${error.javaClass.simpleName}")
                            }
                            if (!selected) pollRecovery(since, until)
                        }
                    }
                }, 400, TimeUnit.MILLISECONDS)
            }
            fun armRecovery() {
                worker.schedule({
                    synchronized(this) {
                        if (lease?.token != token || selected || selection == null) return@synchronized
                        try {
                            val since = android.os.SystemClock.elapsedRealtime()
                            val recovered = recovery.recover(ssid,
                                owned = { lease?.token == token && !selected && selection != null && activeRequestOwned(uid, ssid) },
                                current = {
                                    val info = wifi.connectionInfo
                                    val name = info.ssid.trim('"')
                                    val internet = connectivity.allNetworks.any { network ->
                                        val caps = connectivity.getNetworkCapabilities(network)
                                        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true &&
                                            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                                    }
                                    if (!internet || name.contains("unknown", true)) null else
                                        WifiHandoffLease.currentSavedNetwork(info.networkId, name,
                                            savedNetworks().filter {
                                                it.networkId == info.networkId && it.SSID?.trim('"') == name
                                            }.map { config ->
                                                WifiHandoffLease.SavedNetwork(config.networkId, name,
                                                    config.javaClass.getField("allowAutojoin").getBoolean(config))
                                            })
                                },
                                isCurrent = { previous ->
                                    val info = wifi.connectionInfo
                                    info.networkId == previous.id && info.ssid.trim('"') == previous.ssid
                                },
                                autojoin = { previous, enabled ->
                                    // Never restore a deleted/replaced saved config under a recycled ID.
                                    if (savedNetworks().any {
                                        it.networkId == previous.id && it.SSID?.trim('"') == previous.ssid
                                    } == true) WifiManager::class.java.getMethod("allowAutojoin",
                                        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                                        .invoke(wifi, previous.id, enabled)
                                },
                                disconnect = { wifi.disconnect() },
                                scan = { Log.i("HyperCar", "WIFI-SELECT: bounded other-WiFi handoff; scan_requested=${wifi.startScan()}") },
                            )
                            if (recovered) pollRecovery(since, since + 8_000)
                        } catch (error: Exception) {
                            Log.w("HyperCar", "WIFI-SELECT: handoff recovery failed ${(error.cause ?: error).javaClass.simpleName}")
                        }
                    }
                }, 2_000, TimeUnit.MILLISECONDS)
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
                            Log.i("HyperCar", "WIFI-SELECT: own selection callback ready; bounded recovery armed")
                            armRecovery()
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
            lease = Lease(token, owner, death, wifi, type, callback, recovery)
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
        runCatching { current.recovery.close() }.onFailure {
            Log.w("HyperCar", "WIFI-SELECT: previous autojoin restore failed ${it.javaClass.simpleName}")
        }
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
