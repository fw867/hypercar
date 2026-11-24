package io.github.hypercar.iap2

import io.github.hypercar.net.CarWifiRequestIdentity
import java.util.Locale

/** Credential history belongs to a connection attempt, even when RFCOMM has to reconnect. */
class Iap2WifiCredentialHistory {
    private val receivers = HashMap<String, Receiver>()

    @Synchronized
    internal fun forReceiver(address: String?): Receiver {
        val key = address?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: return Receiver()
        return receivers.getOrPut(key, ::Receiver)
    }

    class Receiver internal constructor() {
        private class Hotspot(var credential: Iap2Messages.WifiConfiguration? = null) {
            val supersededCredentials = HashMap<String, Iap2Messages.WifiConfiguration?>()
            val cacheBindings = HashSet<CacheBinding>()
            val cachePasswordsReplacedByDifferentSsid = HashSet<String>()
            val retiredRecoveryPasswords = HashSet<String>()
            var start: Iap2Messages.StartSession? = null
        }

        internal class CacheBinding(val ssid: String, val password: String)

        internal data class Resolution(
            val start: Iap2Messages.StartSession,
            val credential: Iap2Messages.WifiConfiguration?,
            val replayedPassword: Boolean = false,
        )

        private val hotspots = HashMap<String, Hotspot>()
        private var activeSsid: String? = null

        @Synchronized
        internal fun beginCacheBinding(ssid: String, password: String): CacheBinding =
            CacheBinding(ssid, password).also { hotspots.getOrPut(ssid, ::Hotspot).cacheBindings.add(it) }

        @Synchronized
        internal fun finishCacheBinding(binding: CacheBinding, bound: Boolean) {
            val hotspot = hotspots[binding.ssid] ?: return
            if (hotspot.cacheBindings.remove(binding) && bound) rememberAppliedCache(binding.ssid, binding.password)
        }

        @Synchronized
        internal fun rememberAppliedCache(ssid: String, password: String) {
            val hotspot = hotspots.getOrPut(ssid, ::Hotspot)
            // The cache supplies a historical password. Bluetooth supplies the current configuration.
            if (hotspot.credential?.passphrase != password) hotspot.supersededCredentials.putIfAbsent(password, null)
        }

        @Synchronized
        internal fun remember(credential: Iap2Messages.WifiConfiguration) {
            if (isRetiredRecoveryCredential(credential.ssid, credential.passphrase)) return
            val hotspot = hotspots.getOrPut(credential.ssid, ::Hotspot)
            // A 0x5703 can supersede the cached password before the cache ever binds.
            for ((ssid, entry) in hotspots) {
                for (binding in entry.cacheBindings) {
                    if (ssid != credential.ssid) entry.cachePasswordsReplacedByDifferentSsid.add(binding.password)
                    else if (binding.password != credential.passphrase) entry.supersededCredentials.putIfAbsent(binding.password, null)
                }
            }
            rememberCurrent(hotspot, credential)
            activeSsid = credential.ssid
        }

        private fun rememberCurrent(hotspot: Hotspot, credential: Iap2Messages.WifiConfiguration) {
            hotspot.credential?.takeIf { it.passphrase != credential.passphrase }?.let {
                hotspot.supersededCredentials[it.passphrase] = it
            }
            hotspot.supersededCredentials.remove(credential.passphrase)
            hotspot.cachePasswordsReplacedByDifferentSsid.remove(credential.passphrase)
            hotspot.credential = credential
        }

        @Synchronized
        internal fun restoreSelected(ssid: String, password: String, lost: CarWifiRequestIdentity? = null) {
            if (isRetiredRecoveryCredential(ssid, password)) return
            val hotspot = hotspots[ssid] ?: return
            if (hotspot.credential?.passphrase != password) {
                val selected = hotspot.supersededCredentials[password] ?: return
                rememberCurrent(hotspot, selected)
            }
            if (lost != null && !lost.matches(ssid, password)) retireRecoveryCredential(lost.ssid, lost.passphrase)
            activeSsid = ssid
        }

        @Synchronized
        internal fun retireRecoveryCredential(ssid: String, password: String) {
            hotspots.getOrPut(ssid, ::Hotspot).retiredRecoveryPasswords.add(password)
        }

        @Synchronized
        internal fun isRetiredRecoveryCredential(ssid: String, password: String): Boolean =
            hotspots[ssid]?.retiredRecoveryPasswords?.contains(password) == true

        @Synchronized
        internal fun commitStart(resolution: Resolution) {
            val credential = resolution.credential ?: return
            val hotspot = hotspots.getOrPut(credential.ssid, ::Hotspot)
            if (resolution.start.wirelessAp != null && !resolution.replayedPassword) {
                rememberCurrent(hotspot, credential)
                activeSsid = credential.ssid
            }
            hotspot.start = resolution.start
        }

        @Synchronized
        internal fun resolve(
            start: Iap2Messages.StartSession,
            currentWifi: Iap2Messages.WifiConfiguration?,
        ): Resolution? {
            val ap = start.wirelessAp
            if (ap == null) return Resolution(start, hotspots[activeSsid]?.credential ?: currentWifi)
            val hotspot = hotspots[ap.ssid]
            if (hotspot?.retiredRecoveryPasswords?.contains(ap.passphrase) == true) return null
            if (ap.ssid != activeSsid && hotspot?.cachePasswordsReplacedByDifferentSsid?.contains(ap.passphrase) == true) return null
            val current = hotspot?.credential
            if (hotspot != null && current != null && ap.passphrase != current.passphrase &&
                (ap.passphrase in hotspot.supersededCredentials || hotspot.cacheBindings.any { it.password == ap.passphrase })) {
                val previous = hotspot.start
                val endpoint = Iap2Messages.StartSession(
                    airPlayPort = start.airPlayPort.takeIf { it > 0 } ?: previous?.airPlayPort ?: 0,
                    deviceIdentifier = previous?.deviceIdentifier,
                    publicKeyHex = previous?.publicKeyHex,
                    sourceVersion = previous?.sourceVersion,
                    ipAddresses = start.ipAddresses.ifEmpty { previous?.ipAddresses.orEmpty() },
                    wirelessAp = null,
                    sdkVersion = previous?.sdkVersion,
                    mutualAuth = previous?.mutualAuth,
                    airplayRouteBinding = previous?.airplayRouteBinding?.takeIf { start.ipAddresses.isEmpty() }
                        ?: AirplayRouteBinding(),
                    replayedWifiConfiguration = ap,
                )
                return Resolution(endpoint, current, replayedPassword = true)
            }
            return Resolution(start, ap)
        }
    }
}
