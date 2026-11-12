package io.github.hypercar.carui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import io.github.hypercar.navigation.AmapLinkNavigation
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only, projection-scoped diagnostics. No network binding, location requests or settings writes. */
internal object MapDiagnostics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Some vendor DNS implementations ignore interruption: bound both threads and queued work.
    private val probes = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "map-network-probe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private var job: Job? = null
    private var providerId: String? = null
    private var generation = 0L

    @Synchronized fun start(context: Context, provider: MapProvider, display: Int, presentation: MapPresentation, carMode: Boolean) {
        if (!SettingsStore.debugLogEnabled) { stop(); return }
        HyperCarRepo.log("MAP-SCENE: attached provider=${provider.id} display=$display mode=$presentation carMode=$carMode; rendered route/car marker not observable")
        if (job?.isActive == true && providerId == provider.id) return
        stop()
        val token = generation
        providerId = provider.id
        val app = context.applicationContext
        job = scope.launch {
            var round = 0
            while (isActive && current(token) && SettingsStore.debugLogEnabled) {
                try {
                    snapshot(app, provider, token, round++ % 4 == 0)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { emit(token, "MAP-DIAG: snapshot error=${error.javaClass.simpleName}") }
                delay(30_000)
            }
        }
    }

    @Synchronized fun stop() {
        generation++
        job?.cancel()
        job = null
        providerId = null
    }
    @Synchronized private fun current(token: Long) = token == generation
    private fun emit(token: Long, line: String) { if (current(token) && SettingsStore.debugLogEnabled) HyperCarRepo.log(line) }

    @Suppress("DEPRECATION")
    private suspend fun snapshot(context: Context, provider: MapProvider, token: Long, probe: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val bound = cm.boundNetworkForProcess
        val active = cm.activeNetwork
        val networks = cm.allNetworks.toList()
        val privateDns = Settings.Global.getString(context.contentResolver, "private_dns_mode")
            ?.takeIf { it in setOf("off", "opportunistic", "hostname") } ?: "unknown"
        val cue = AmapLinkNavigation.snapshot().takeIf { provider == MapProvider.AMAP }
        emit(token, "MAP-NET: processBound=$bound appActive=$active privateDns=$privateDns vpn=${networks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }}")
        emit(token, "MAP-NAV: provider=${provider.id} guidanceState=${cue?.state} maneuver=${cue?.maneuver}; route/position rendering unknown; callback is not internet proof")
        val cellular = networks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
        val selected = listOfNotNull(bound, active, cellular).distinct().take(3)
        for (network in selected) {
            val caps = cm.getNetworkCapabilities(network)
            val lp = cm.getLinkProperties(network)
            val kind = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                else -> "OTHER"
            }
            emit(token, "MAP-NET: network=$network transport=$kind validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} dnsCount=${lp?.dnsServers?.size} gatewayPresent=${lp?.routes?.any { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }} privateDnsActive=${lp?.isPrivateDnsActive}")
        }
        if (probe && ShizukuTools.isReady) {
            // Only bounded current state; never persist raw dumps (which may contain exact locations).
            val connectivity = ShizukuTools.exec("dumpsys connectivity | head -n 30", 4000)
            emit(token, "MAP-NET: systemDefault=${MapDiagnosticText.systemDefault(connectivity.out)} shellExit=${connectivity.code}")
            val location = ShizukuTools.exec("dumpsys location | head -n 240", 4000)
            emit(token, "MAP-LOC: ${MapDiagnosticText.location(location.out, provider.packageName, SystemClock.elapsedRealtime())} shellExit=${location.code}; system cache, not map-consumption confirmation")
        } else if (probe) emit(token, "MAP-LOC: shell unavailable; systemDefault/GPS cache unknown")
        if (probe) for (network in selected) {
            currentCoroutineContext().ensureActive()
            if (!current(token)) return
            emit(token, "MAP-PROBE: network=$network ${probe(network)}; one public host only, not all-app connectivity proof")
        }
    }

    private fun probe(network: Network): String {
        val future = try { probes.submit<String> {
            val start = SystemClock.elapsedRealtime()
            val addresses = try { network.getAllByName("www.amap.com").size }
                catch (error: Exception) { return@submit "dns=${error.javaClass.simpleName} https=not-tested" }
            var connection: HttpURLConnection? = null
            try {
                connection = network.openConnection(URL("https://www.amap.com/")) as HttpURLConnection
                connection.connectTimeout = 1500
                connection.readTimeout = 1500
                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD"
                "dns=ok($addresses) https=${connection.responseCode} elapsed=${SystemClock.elapsedRealtime() - start}ms"
            } catch (error: Exception) { "dns=ok($addresses) https=${error.javaClass.simpleName}" }
            finally { connection?.disconnect() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { return "busy (previous DNS/probe still pending)" }
        return try { future.get(4, TimeUnit.SECONDS) }
        catch (_: java.util.concurrent.TimeoutException) { "timeout=4000ms dns/https stage unknown" }
        catch (error: Exception) { "probe=${error.javaClass.simpleName}" }
        finally { future.cancel(true); probes.purge() }
    }
}

/** Whitelist output: never return a raw provider line, coordinate, SSID, DNS hostname or route text. */
internal object MapDiagnosticText {
    fun systemDefault(text: String): String = Regex("Active default network: (\\d+|none)")
        .find(text)?.groupValues?.get(1) ?: "unknown"

    fun location(text: String, pkg: String, now: Long): String {
        val gps = text.substringAfter("    gps provider:", "").substringBefore("GNSS_KPI_START")
        val fix = gps.lineSequence().firstOrNull { it.contains("last location=Location[gps ") }
        val accuracy = fix?.let { Regex("hAcc=([0-9.]+)").find(it)?.groupValues?.get(1) } ?: "unknown"
        val elapsed = fix?.let { Regex("et=\\+((?:\\d+(?:ms|[dhms]))+)(?=\\s)").find(it)?.groupValues?.get(1) }
        val stamp = elapsed?.let(::durationMillis)
        val age = stamp?.takeIf { it <= now }?.let { (now - it) / 1000 }?.toString() ?: "unknown"
        val enabled = Regex("enabled=(true|false)").find(gps)?.groupValues?.get(1) ?: "unknown"
        val started = Regex("mStarted=(true|false)").find(gps)?.groupValues?.get(1) ?: "unknown"
        // Top-of-dump listeners only, not historical registrations.
        val listeners = text.substringBefore("Historical Records").lineSequence().filter { it.contains(pkg) }.toList()
        val high = listeners.any { it.contains("HIGH_ACCURACY") }
        return "gpsEnabled=$enabled gpsStarted=$started accuracyM=$accuracy fixAgeS=$age mapListeners=${listeners.size} mapHighAccuracyRequest=$high (bounded snapshot)"
    }
    private fun durationMillis(text: String): Long? {
        val units = Regex("(\\d+)(ms|d|h|m|s)").findAll(text).toList()
        if (units.joinToString("") { it.value } != text) return null
        return runCatching { units.fold(0L) { sum, match ->
            val factor = when (match.groupValues[2]) { "d" -> 86_400_000L; "h" -> 3_600_000L; "m" -> 60_000L; "s" -> 1000L; else -> 1L }
            Math.addExact(sum, Math.multiplyExact(match.groupValues[1].toLong(), factor))
        } }.getOrNull()
    }
}
