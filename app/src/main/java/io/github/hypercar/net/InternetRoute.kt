@file:Suppress("DEPRECATION")   // 平台 API / 旧图标名已废弃但当前无替代，统一在此抑制
package io.github.hypercar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「走有外网的那条网络」打开 HTTP 连接。
 *
 * 为什么需要：会话期间 [io.github.hypercar.net.WifiJoiner] 会把**整个进程**
 * `bindProcessToNetwork(车机热点)`，而车机热点没有外网（Direct AP 连默认网关都是 0.0.0.0）。
 * 于是 `URL(url).openConnection()` 会 100% 失败——「从 GitHub 更新车型列表」
 * （[io.github.hypercar.headunit.RemoteProfiles.refresh]）和「检查更新」
 * （[io.github.hypercar.update.UpdateChecker]）都会这样踩坑（2026-10-25 验证）。
 *
 * 做法：挑一个带 `NET_CAPABILITY_INTERNET` 的网络（**优先 VALIDATED**，其次蜂窝），
 * 用 `Network.openConnection(URL)` 建连接——它走该网络的 socket factory，**不受进程级绑定影响**。
 * 找不到合适网络时回退普通 `URL.openConnection()`（保持原行为，不让更新功能整体不可用）。
 */
object InternetRoute {
    private const val TAG = "NET-ROUTE"

    fun open(
        context: Context,
        url: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000,
    ): HttpURLConnection {
        val target = URL(url)
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = pickInternetNetwork(cm)
        val conn =
            if (network != null) {
                runCatching { network.openConnection(target) as HttpURLConnection }
                    .onFailure { Log.i(TAG, "network.openConnection 失败，回退默认网络：${it.message}") }
                    .getOrNull()
            } else {
                null
            } ?: (target.openConnection() as HttpURLConnection)
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        return conn
    }

    /**
     * 选一个能上外网的网络：VALIDATED 优先（真正通过联网校验），其次任何带 INTERNET
     * 能力且**不是**车机热点（无 VALIDATED）的网络。都没有返回 null。
     */
    private fun pickInternetNetwork(cm: ConnectivityManager?): Network? {
        if (cm == null) return null
        val candidates =
            runCatching { cm.allNetworks.toList() }
                .getOrElse { return null }
                .mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        return@mapNotNull null
                    }
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                        return@mapNotNull null // 需要网页认证的网络，连 GitHub 也是失败
                    }
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    network to validated
                }
        return candidates.firstOrNull { it.second }?.first
            ?: candidates.firstOrNull { (network, _) ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }?.first
    }
}
