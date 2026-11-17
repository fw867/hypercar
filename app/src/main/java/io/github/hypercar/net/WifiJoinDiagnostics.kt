package io.github.hypercar.net

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executor

/** Observations for one local-only request. Never scan, select an AP or modify the request. */
internal class WifiJoinDiagnostics(
    context: Context,
    private val target: String,
    private val output: (String) -> Unit,
) {
    val id: String = UUID.randomUUID().toString()
    private val started = SystemClock.elapsedRealtime()
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var previousStation: String? = null
    private var removeListener: (() -> Unit)? = null

    fun log(message: String) = output("request_id=$id elapsed_ms=${SystemClock.elapsedRealtime() - started} $message")

    fun start(specifier: WifiNetworkSpecifier, isCurrent: () -> Boolean) {
        val importance = runCatching {
            ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance
        }.getOrNull()
        log("request_context sdk=${Build.VERSION.SDK_INT} process_importance=$importance wifi_enabled=${runCatching { wifi?.isWifiEnabled }.getOrNull()} " +
            "local_only_multi_sta=${runCatching { wifi?.isStaConcurrencyForLocalOnlyConnectionsSupported }.getOrNull()}")
        station()
        scanCache("before_request")
        if (Build.VERSION.SDK_INT >= 34 && wifi != null) {
            try {
                removeListener = listenForFailure(wifi, specifier, isCurrent)
                log("framework_failure_listener=registered")
            } catch (e: RuntimeException) {
                log("framework_failure_listener=unavailable error=${e.javaClass.simpleName}")
            }
        } else log("framework_failure_listener=unsupported")
    }

    @RequiresApi(34)
    private fun listenForFailure(wifi: WifiManager, specifier: WifiNetworkSpecifier, isCurrent: () -> Boolean): () -> Unit {
        val listener = WifiManager.LocalOnlyConnectionFailureListener { failed, code ->
            if (isCurrent() && failed == specifier) log("framework_failure=${failureReason(code)} code=$code")
        }
        wifi.addLocalOnlyConnectionFailureListener(Executor { it.run() }, listener)
        return { wifi.removeLocalOnlyConnectionFailureListener(listener) }
    }

    // On multi-STA phones this snapshot may describe another connection. Always expose
    // whether the visible SSID matches, and retain unknown when Android redacts it.
    @Suppress("DEPRECATION")
    fun station() {
        val snapshot = runCatching {
            val info = wifi?.connectionInfo
            "system_sta ssid_match=${ssidMatch(info?.ssid, target)} supplicant=${info?.supplicantState ?: "unknown"}"
        }.getOrElse { "system_sta unavailable=${it.javaClass.simpleName}" }
        if (snapshot != previousStation) {
            previousStation = snapshot
            log(snapshot)
        }
    }

    fun lost(cm: ConnectivityManager, lost: Network) {
        lossSnapshot(cm, lost, "callback")
        // Let teardown proceed. Re-sample the original handle even after its request has ended.
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            lossSnapshot(cm, lost, "after_1s")
            delay(2000)
            lossSnapshot(cm, lost, "after_3s")
        }
    }

    @Suppress("DEPRECATION")
    private fun lossSnapshot(cm: ConnectivityManager, lost: Network, phase: String) {
        val state = runCatching {
            val station = wifi?.connectionInfo
            val visible = cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                val props = cm.getLinkProperties(network)
                val ipv4 = props?.linkAddresses?.filter { it.address is java.net.Inet4Address }?.map { it.toString() }
                "${network.networkHandle}/ssid_match=${ssidMatch((caps.transportInfo as? WifiInfo)?.ssid, target)}/ipv4=$ipv4/routes=${props?.routes?.size}"
            }
            "supplicant=${station?.supplicantState} sta_ssid_match=${ssidMatch(station?.ssid, target)} " +
                "lost_handle_visible=${cm.getNetworkCapabilities(lost) != null} visible_wifi=$visible"
        }.getOrElse { "snapshot_error=${it.javaClass.simpleName}" }
        log("loss_snapshot phase=$phase handle=${lost.networkHandle} $state")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanCache(phase: String) {
        val snapshot = runCatching {
            val scans = wifi?.scanResults ?: return@runCatching "unavailable"
            val newest = scans.filter { it.SSID == target }.maxOfOrNull { it.timestamp }
            "entries=${scans.size} target_seen=${newest != null} newest_age_ms=${newest?.let { (SystemClock.elapsedRealtime() - it / 1000).coerceAtLeast(0) }}"
        }.getOrElse { "unavailable=${it.javaClass.simpleName}" }
        log("scan_cache phase=$phase $snapshot")
    }

    fun close() {
        runCatching { removeListener?.invoke() }.onFailure { log("failure_listener_remove_error=${it.javaClass.simpleName}") }
        removeListener = null
        station()
        scanCache("end_request")
    }

    companion object {
        fun ssidMatch(reportedSsid: String?, target: String): String {
            val name = reportedSsid?.removeSurrounding("\"")
            return if (name.isNullOrBlank() || name == WifiManager.UNKNOWN_SSID) "unknown" else (name == target).toString()
        }

        fun failureReason(code: Int): String = when (code) {
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_ASSOCIATION -> "association"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_AUTHENTICATION -> "authentication"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_IP_PROVISIONING -> "ip_provisioning"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NOT_FOUND -> "ap_not_found"
            WifiManager.STATUS_LOCAL_ONLY_CONNECTION_FAILURE_NO_RESPONSE -> "ap_no_response"
            6 -> "user_reject" // STATUS_LOCAL_ONLY_CONNECTION_FAILURE_USER_REJECT, public in API 36.
            else -> "unknown"
        }
    }
}
