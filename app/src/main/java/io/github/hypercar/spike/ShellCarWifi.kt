@file:Suppress("DEPRECATION")
package io.github.hypercar.spike

import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Looper

/** Shell-only migration of the currently connected car AP. No credentials leave WifiService. */
object ShellCarWifi {
    internal fun managers(): Pair<WifiManager, ConnectivityManager> {
        if (Looper.myLooper() == null) Looper.prepare()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        val system = at.getMethod("getSystemContext").invoke(thread) as Context
        val context = object : ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
            override fun getPackageName() = "com.android.shell"
            override fun getOpPackageName() = "com.android.shell"
            override fun getApplicationContext(): Context = this
            override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build()
            override fun createDeviceContext(deviceId: Int): Context = this
        }
        // SystemContext's cached WifiManager still attributes calls to android/uid1000.
        // Build one with the actual shell context rather than modifying that cached manager.
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifi") as android.os.IBinder
        val serviceType = Class.forName("android.net.wifi.IWifiManager")
        val service = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        val wifi = runCatching {
            WifiManager::class.java.getConstructor(Context::class.java, serviceType).newInstance(context, service)
        }.getOrElse {
            WifiManager::class.java.getConstructor(Context::class.java, serviceType, Looper::class.java)
                .newInstance(context, service, Looper.myLooper())
        }
        val connectivityBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "connectivity") as android.os.IBinder
        val connectivityType = Class.forName("android.net.IConnectivityManager")
        val connectivityService = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, connectivityBinder)
        val cm = ConnectivityManager::class.java.getConstructor(Context::class.java, connectivityType)
            .newInstance(context, connectivityService)
        return wifi to cm
    }

    @JvmStatic fun main(args: Array<String>) {
        var phase = "context"
        try {
            check(android.os.Process.myUid() == 2000) { "shell UID required" }
            val ssid = args.singleOrNull()?.takeIf { it.isNotBlank() } ?: error("SSID required")
            val (wifi, cm) = managers()
            val allowAutojoin = WifiManager::class.java.getMethod("allowAutojoin",
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            if (ssid == "--probe") {
                println("OK shell Wi-Fi context and per-network autojoin API available; read-only probe"); return
            }
            val info = wifi.connectionInfo
            phase = "current-network"
            if (info.ssid.trim('"') != ssid || info.networkId < 0) {
                println("SKIP current Wi-Fi is not the requested car AP"); return
            }
            // A validated internet AP (e.g. WinPlay on home Wi-Fi) must remain untouched.
            if (cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }) { println("SKIP validated internet Wi-Fi"); return }
            val id = info.networkId
            phase = "saved-config"
            val config = wifi.configuredNetworks?.singleOrNull { it.networkId == id && it.SSID.trim('"') == ssid }
            if (config == null) { println("SKIP not a saved system Wi-Fi connection"); return }
            if (runCatching { config.javaClass.getField("fromWifiNetworkSpecifier").getBoolean(config) }.getOrDefault(false)) {
                println("SKIP already a local-only specifier connection"); return
            }
            phase = "autojoin"
            allowAutojoin.invoke(wifi, id, false)
            val latest = wifi.connectionInfo
            if (latest.networkId != id || latest.ssid.trim('"') != ssid) {
                println("OK car autojoin disabled; current Wi-Fi changed, no disconnect"); return
            }
            phase = "disconnect"
            check(wifi.disconnect()) { "disconnect request rejected" }
            println("OK old car system Wi-Fi disconnected; autojoin disabled only for network=$id; use local-only request")
        } catch (e: SecurityException) {
            println("ERR migration phase=$phase SecurityException: ${e.message?.take(220)}")
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            println("ERR migration phase=$phase ${cause.javaClass.simpleName}: ${cause.message?.take(220)}")
            kotlin.system.exitProcess(1)
        }
    }
}
