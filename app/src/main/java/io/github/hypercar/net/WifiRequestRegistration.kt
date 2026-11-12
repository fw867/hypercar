package io.github.hypercar.net

/** A requestNetwork Binder return precedes WifiNetworkFactory's asynchronous activation. */
internal class WifiRequestRegistration(private val limit: Int = 10) {
    private var attempts = 0
    private var acknowledged = false
    fun beginAttempt(): Boolean {
        if (acknowledged || attempts >= limit) return false
        attempts++
        return true
    }
    fun acknowledge() { acknowledged = true }
    fun retryAfterEarlyAbort(): Boolean = !acknowledged && attempts < limit
}
