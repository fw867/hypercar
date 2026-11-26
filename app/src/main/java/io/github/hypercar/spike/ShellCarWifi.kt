@file:Suppress("DEPRECATION")
package io.github.hypercar.spike

import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.net.ConnectivityManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Looper
import io.github.hypercar.net.CarWifiPolicy

/** Shell-attributed managers used by the app-owned, reversible Wi-Fi selection lease. */
object ShellCarWifi {
    internal fun hasSavedWifiWithAutojoin(ssid: String): Boolean {
        if (ssid.isBlank()) return false
        val (wifi, _) = managers()
        val saved = try { wifi.configuredNetworks.orEmpty() }
        catch (_: SecurityException) { return false }
        return saved.any { config ->
            config.networkId >= 0 && CarWifiPolicy.visibleSsid(config.SSID) == ssid &&
                config.javaClass.getField("allowAutojoin").getBoolean(config)
        }
    }

    internal fun isWifiStationJoiningOrConnected(ssid: String): Boolean {
        if (ssid.isBlank()) return false
        val (wifi, _) = managers()
        val station = (try { wifi.connectionInfo } catch (_: SecurityException) { return false }) ?: return false
        val name = if (station.supplicantState == SupplicantState.COMPLETED) CarWifiPolicy.visibleSsid(station.ssid)
        else CarWifiPolicy.joiningStationSsid(station.ssid, station.supplicantState)
        return name == ssid
    }

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

}
