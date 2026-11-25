package io.github.hypercar.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.hypercar.iap2.CarPlayEndpoint
import io.github.hypercar.net.CarWifiJoinPolicy
import io.github.hypercar.net.CarWifiRequestIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Cached access to the local-only hotspot. This never represents AirPlay activation. */
internal object CarHotspotReconnect {
    // Keep the original alias and directory so upgrades can read existing BMW caches.
    private const val KEY_ALIAS = "bmw-local-wifi"
    private const val NONCE_BYTES = 12

    class Credentials(val ssid: String, val passphrase: String, val securityType: Int? = null) {
        internal fun encode(): ByteArray = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeUTF(ssid)
                it.writeUTF(passphrase)
                it.writeInt(securityType ?: -1)
            }
        }.toByteArray()

        companion object {
            internal fun decode(bytes: ByteArray): Credentials = DataInputStream(ByteArrayInputStream(bytes)).use {
                val ssid = it.readUTF()
                val password = it.readUTF()
                // Previous records end immediately after the password.
                val security = if (it.available() == 0) null else it.readInt().takeIf { value -> value >= 0 }
                Credentials(ssid, password, security)
            }
        }
    }

    data class HintTransition(
        val mergeFrom: CarPlayEndpoint?,
        val retiredSsidAddresses: Set<String>,
        val invalidateLateAddress: Boolean,
    )

    /** A genuine classic Bluetooth edge permits one automatic cache check. */
    class AclTrigger {
        class Edge(val receiver: String, val observedAt: Long)

        private var current: Edge? = null
        private var claimed = false

        @Synchronized fun connected(receiver: String, observedAt: Long) {
            if (current?.receiver.equals(receiver, ignoreCase = true)) return
            current = Edge(receiver, observedAt)
            claimed = false
        }

        @Synchronized fun disconnected(receiver: String? = null) {
            if (receiver == null || current?.receiver.equals(receiver, ignoreCase = true)) current = null
        }

        fun onDisconnect(receiver: String, profileDrop: Boolean): Boolean {
            disconnected(receiver)
            return !profileDrop
        }

        @Synchronized fun candidate(target: String?, now: Long): Edge? {
            val edge = current ?: return null
            if (claimed || !edge.receiver.equals(target, ignoreCase = true) || now - edge.observedAt !in 0..30_000L) return null
            return edge
        }

        fun submit(edge: Edge, now: Long, request: () -> Unit = {}): Boolean {
            synchronized(this) {
                if (candidate(edge.receiver, now) !== edge) return false
                claimed = true
            }
            try {
                request()
                return true
            } catch (failure: Throwable) {
                synchronized(this) {
                    if (current === edge) claimed = false
                }
                throw failure
            }
        }

        @Synchronized fun isCurrent(edge: Edge): Boolean = current === edge
    }

    class RestoreAttempt(private val autoAcl: AclTrigger?) {
        class Trigger(val receiver: String, val acl: AclTrigger.Edge?)

        private var manualClaimed = false

        fun candidate(receiver: String, now: Long): Trigger? {
            if (autoAcl != null) return autoAcl.candidate(receiver, now)?.let { Trigger(receiver, it) }
            if (manualClaimed) return null
            manualClaimed = true
            return Trigger(receiver, null)
        }

        fun submit(trigger: Trigger, now: Long, request: () -> Unit = {}): Boolean {
            val acl = autoAcl
            if (acl != null) return trigger.acl?.let { acl.submit(it, now, request) } == true
            request()
            return true
        }
    }

    /** Serialize endpoint arrivals with the decision to activate on a cached network. */
    class EndpointInbox(
        private val lock: Any = Any(),
        private val standby: AtomicReference<CarPlayEndpoint?> = AtomicReference(null),
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        private val isRetired: (CarPlayEndpoint) -> Boolean = { false },
        private val hasProposedCredentials: ((CarPlayEndpoint) -> Boolean)? = null,
        private val hasFreshCredentials: (CarPlayEndpoint) -> Boolean,
    ) {
        private class Arrival(val endpoint: CarPlayEndpoint, val enqueuedAt: Long, val freshAtEnqueue: Boolean)
        private class DeferredArrival(val endpoint: CarPlayEndpoint, val enqueuedAt: Long,
            val commit: (CarPlayEndpoint) -> Unit, val publish: (CarPlayEndpoint) -> Unit) {
            var followingAddress: DeferredArrival? = null
        }

        private class WifiRecovery(val lost: CarWifiRequestIdentity?, val deadlineMillis: Long) {
            var selected: CarPlayEndpoint? = null
            var joining = true
            val deferred = ArrayDeque<DeferredArrival>()

            fun hasRetiredUnknownSelection(): Boolean = lost == null && selected?.sourceIsCurrent == false

            fun replacesLost(endpoint: CarPlayEndpoint): Boolean {
                if (endpoint.wifiSecurityType != null && endpoint.passphrase?.let {
                        CarWifiJoinPolicy.security(endpoint.wifiSecurityType, it) == null
                    } == true) return false
                return !matchesLostRequest(lost, endpoint)
            }

            fun matchesSelection(endpoint: CarPlayEndpoint, pinned: CarPlayEndpoint?): Boolean =
                endpoint.passphrase == null || pinned?.let {
                    it.matchesHotspotRequest(endpoint) && it.bluetoothHotspot == endpoint.bluetoothHotspot
                } == true

            fun defer(endpoint: CarPlayEndpoint, enqueuedAt: Long, commit: (CarPlayEndpoint) -> Unit,
                publish: (CarPlayEndpoint) -> Unit) {
                val repeat = deferred.indexOfLast { it.endpoint == endpoint }
                val followingAddress = if (repeat >= 0) deferred.removeAt(repeat).followingAddress else null
                if (deferred.size >= 16) deferred.removeFirst()
                deferred.addLast(DeferredArrival(endpoint, enqueuedAt, commit, publish).apply {
                    this.followingAddress = followingAddress
                })
            }
        }

        private companion object {
            fun matchesLostRequest(lost: CarWifiRequestIdentity?, endpoint: CarPlayEndpoint): Boolean =
                lost?.matchesRequest(endpoint.ssid, endpoint.passphrase, endpoint.wifiSecurityType) == true ||
                    endpoint.replayedWifiConfiguration?.let {
                        lost?.matchesRequest(it.ssid, it.passphrase, it.securityType)
                    } == true
        }

        private val pending = ArrayDeque<Arrival>()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var latestReceivedCredentials: CarPlayEndpoint? = null
        private var wifiRecovery: WifiRecovery? = null
        private var closed = false

        fun isAccepting(): Boolean = synchronized(lock) { !closed }

        fun offer(
            endpoint: CarPlayEndpoint,
            onDeferredAccept: ((CarPlayEndpoint) -> Unit)? = null,
            onDeferredAddress: (CarPlayEndpoint) -> Unit = {},
            onDeferredPublish: (CarPlayEndpoint) -> Unit = {},
            onAccept: () -> Unit = {},
        ): Boolean =
            synchronized(lock) {
                if (closed || !canQueue(endpoint)) return@synchronized false
                val enqueuedAt = nowMillis()
                val recovery = wifiRecovery
                // A new SSID is only published by onAccept. Check its proposed
                // credentials here, then validate the current hotspot in enqueue.
                val replacesRetiredSelection = recovery?.hasRetiredUnknownSelection() == true &&
                    !endpoint.addressOnlyAirplayReport && (hasProposedCredentials ?: hasFreshCredentials)(endpoint)
                if (recovery != null) {
                    if (endpoint.addressOnlyAirplayReport && endpoint.verifiedAirplayNetworkHandle == null && recovery.joining) {
                        val peer = endpoint.bluetoothHotspot?.peer ?: recovery.selected?.bluetoothHotspot?.peer
                        val future = recovery.deferred.lastOrNull {
                            it.endpoint.sourceIsCurrent && it.endpoint.bluetoothHotspot?.peer?.equals(peer, ignoreCase = true) == true
                        }
                        if (future != null) {
                            // A route-only report received behind pending credentials is
                            // provisional until the matching network has actually bound.
                            val route = endpoint.copy(
                                ssid = future.endpoint.ssid, passphrase = future.endpoint.passphrase,
                                wifiSecurityType = future.endpoint.wifiSecurityType, wifiChannel = future.endpoint.wifiChannel,
                                bluetoothHotspot = future.endpoint.bluetoothHotspot,
                                requiresMatchingAirplayCredentials = true,
                            )
                            future.followingAddress = DeferredArrival(route, enqueuedAt, {}, onDeferredAddress)
                            return@synchronized false
                        }
                    }
                    // With no old password to compare, the first eligible report holds
                    // the request even while the worker is still cleaning up the old session.
                    val pinned = recovery.selected ?: if (recovery.lost == null || enqueuedAt > recovery.deadlineMillis) {
                        timelyReplacement(recovery)
                    } else null
                    if (replacesRetiredSelection && recovery.joining && enqueuedAt > recovery.deadlineMillis) {
                        // A timely selection already started recovery. Hold the first
                        // still-current successor until that older join has settled.
                        if (recovery.deferred.none { canQueue(it.endpoint) }) {
                            recovery.defer(withoutVerifiedRoute(endpoint), enqueuedAt, onDeferredAccept ?: { onAccept() },
                                onDeferredPublish)
                        }
                        return@synchronized false
                    }
                    if (!replacesRetiredSelection && (recovery.joining || recovery.lost == null) &&
                        (pinned != null || enqueuedAt > recovery.deadlineMillis) && !recovery.matchesSelection(endpoint, pinned)) {
                        // An unknown lost password cannot be distinguished from a rollback.
                        // For a known loss, hold newer reports without publishing or committing them.
                        if (recovery.lost != null && !endpoint.addressOnlyAirplayReport) {
                            recovery.defer(withoutVerifiedRoute(endpoint), enqueuedAt, onDeferredAccept ?: { onAccept() },
                                onDeferredPublish)
                        }
                        return@synchronized false
                    }
                }
                endpoint.whileSourceCurrent {
                    onAccept()
                    enqueue(endpoint, enqueuedAt)
                    if (replacesRetiredSelection) recovery?.selected = endpoint
                }
            }

        private fun withoutVerifiedRoute(endpoint: CarPlayEndpoint): CarPlayEndpoint {
            if (endpoint.verifiedAirplayNetworkHandle == null) return endpoint
            return endpoint.copy(ipAddresses = emptyList(), gateway = null, airPlayPort = 0,
                requiresBoundAirplayRoute = false, requiresMatchingAirplayCredentials = false, airplayRouteBinding = null)
        }

        private fun canQueue(endpoint: CarPlayEndpoint): Boolean =
            endpoint.sourceIsCurrent && !isRetired(endpoint) && wifiRecovery?.replacesLost(endpoint) != false

        fun accepts(endpoint: CarPlayEndpoint): Boolean = synchronized(lock) { !closed && canQueue(endpoint) }

        fun classifyHintTransition(previous: CarPlayEndpoint?, next: CarPlayEndpoint): HintTransition = synchronized(lock) {
            val ssidChanged = previous?.ssid != null && next.ssid != null && previous.ssid != next.ssid
            val passwordChanged = previous?.passphrase != null && next.passphrase != null && previous.passphrase != next.passphrase
            // Routing history must survive retirement of the source, while the
            // retired credentials and endpoints must not participate in merging.
            val retiredAddresses = if (ssidChanged) {
                val old = checkNotNull(previous)
                (old.ipAddresses + listOfNotNull(old.gateway)).mapTo(mutableSetOf()) { it.substringBefore('%') }
            } else emptySet()
            HintTransition(previous?.takeIf { !closed && canQueue(it) }, retiredAddresses, ssidChanged || passwordChanged)
        }

        private fun enqueue(endpoint: CarPlayEndpoint, enqueuedAt: Long) {
            val fresh = hasFreshCredentials(endpoint)
            pending.addLast(Arrival(endpoint, enqueuedAt, fresh))
            if (fresh) latestReceivedCredentials = endpoint
            wake.trySend(Unit)
        }

        fun poll(): CarPlayEndpoint? = synchronized(lock) {
            if (closed) return@synchronized null
            val buffered = standby.getAndSet(null)?.takeIf(::canQueue)
            if (buffered != null) {
                if (latestReceivedCredentials == null && hasFreshCredentials(buffered)) latestReceivedCredentials = buffered
                return@synchronized buffered
            }
            var next = pending.removeFirstOrNull()
            while (next != null && !canQueue(next.endpoint)) next = pending.removeFirstOrNull()
            next?.endpoint
        }

        suspend fun receive(timeoutMillis: Long): CarPlayEndpoint? {
            poll()?.let { return it }
            // A timeout can race the wakeup, but only poll removes the actual endpoint.
            withTimeoutOrNull(timeoutMillis) { wake.receiveCatching() }
            return poll()
        }

        fun canUseEndpoint(hasCache: Boolean, carNetworkReady: Boolean, freshCredentials: Boolean): Boolean = synchronized(lock) {
            (freshCredentials || !credentialsPending()) && CarHotspotReconnect.canUseEndpoint(hasCache, carNetworkReady, freshCredentials)
        }

        fun <T> whenCredentialsDrained(activate: () -> T): T? = synchronized(lock) {
            if (credentialsPending()) null else activate()
        }

        fun hasPendingCredentials(): Boolean = synchronized(lock) { credentialsPending() }

        fun credentialsChanged(matchesApplied: (CarPlayEndpoint) -> Boolean): Boolean = synchronized(lock) {
            newestPendingCredentials()?.let { !matchesApplied(it) } == true
        }

        fun shouldRetryJoin(endpoint: CarPlayEndpoint): Boolean =
            !endpoint.sourceIsCurrent ||
                credentialsChanged { endpoint.matchesHotspotRequest(it) }

        fun beginWifiRecovery(lost: CarWifiRequestIdentity?, deadlineMillis: Long) = synchronized(lock) {
            if (!closed && wifiRecovery?.deadlineMillis != deadlineMillis) wifiRecovery = WifiRecovery(lost, deadlineMillis)
        }

        private fun timelyReplacement(recovery: WifiRecovery): CarPlayEndpoint? {
            fun eligible(arrival: Arrival): Boolean = arrival.enqueuedAt <= recovery.deadlineMillis &&
                arrival.freshAtEnqueue && canQueue(arrival.endpoint)
            return (if (recovery.lost == null) pending.firstOrNull(::eligible) else pending.lastOrNull(::eligible))?.endpoint
        }

        suspend fun awaitReplacementCredentials(
            lostCredentials: CarWifiRequestIdentity?,
            deadlineMillis: Long,
            publishLatest: (CarPlayEndpoint) -> Unit = {},
            onSelected: (CarPlayEndpoint) -> Unit = {},
        ): CarPlayEndpoint? {
            val recovery = synchronized(lock) {
                beginWifiRecovery(lostCredentials, deadlineMillis)
                wifiRecovery ?: return null
            }
            while (true) {
                val signalled = withTimeoutOrNull((deadlineMillis - nowMillis()).coerceAtLeast(0)) {
                    while (synchronized(lock) { timelyReplacement(recovery) == null }) {
                        if (wake.receiveCatching().isClosed) return@withTimeoutOrNull false
                    }
                    true
                } == true
                // Enqueue and the timeout decision share this lock. If DETECT retired a
                // chosen report before it was committed, keep waiting within the same budget.
                synchronized(lock) {
                    if (closed || wifiRecovery !== recovery) return null
                    val selected = timelyReplacement(recovery)
                    if (selected == null) {
                        if (signalled) return@synchronized
                        close()
                        return null
                    }
                    if (!selected.whileSourceCurrent {
                        recovery.selected = selected
                        pending.removeAll { !canQueue(it.endpoint) || !recovery.matchesSelection(it.endpoint, selected) }
                        standby.updateAndGet { it?.takeIf { endpoint -> canQueue(endpoint) && recovery.matchesSelection(endpoint, selected) } }
                        latestReceivedCredentials = pending.lastOrNull { it.freshAtEnqueue }?.endpoint ?: selected
                        onSelected(selected)
                    }) return@synchronized
                    latestReceivedCredentials?.let(publishLatest)
                    return selected
                }
            }
        }

        fun finishRecoveryJoin(accept: (CarPlayEndpoint) -> Boolean = { true }) = synchronized(lock) {
            val recovery = wifiRecovery ?: return@synchronized
            recovery.joining = false
            fun release(arrival: DeferredArrival, endpoint: CarPlayEndpoint): Boolean {
                var accepted = false
                endpoint.whileSourceCurrent {
                    if (canQueue(endpoint) && accept(endpoint)) {
                        arrival.commit(endpoint)
                        enqueue(endpoint, arrival.enqueuedAt)
                        accepted = true
                    }
                }
                if (accepted) arrival.publish(endpoint)
                return accepted
            }
            while (true) {
                val deferred = recovery.deferred.removeFirstOrNull() ?: break
                val endpoint = withoutVerifiedRoute(deferred.endpoint)
                if (!release(deferred, endpoint)) continue
                if (recovery.hasRetiredUnknownSelection()) recovery.selected = endpoint
                val route = deferred.followingAddress ?: continue
                if (route.endpoint.verifiedAirplayNetworkHandle == null) release(route, route.endpoint)
            }
        }

        fun finishRecovery() = synchronized(lock) { wifiRecovery = null }

        fun canStopAfterWifiLoss(
            lostCredentials: CarWifiRequestIdentity?,
            replacementAllowed: Boolean,
            activation: ProactiveActivation? = null,
            ownerIsCurrent: () -> Boolean,
        ): Boolean = synchronized(lock) {
            if (!ownerIsCurrent()) return@synchronized false
            if (!replacementAllowed && activation == null) return@synchronized true
            if (replacementAllowed && wifiRecovery?.let { it.joining && it.deferred.any { arrival -> canQueue(arrival.endpoint) } } == true) {
                return@synchronized false
            }
            val latest = latestReceivedCredentials?.takeIf { canQueue(it) && hasFreshCredentials(it) } ?: newestPendingCredentials()
            if (latest == null || matchesLostRequest(lostCredentials, latest)) {
                return@synchronized true
            }
            // Releasing the pending activation wakes the same connect job. It retires the
            // old engine and applies the credentials still in this inbox.
            if (activation != null) !activation.interceptTerminal("视频就绪前旧 Wi-Fi 已断开，处理本轮新热点凭据")
            else !replacementAllowed
        }

        fun newestCredentials(current: CarPlayEndpoint): CarPlayEndpoint = synchronized(lock) {
            newestPendingCredentials() ?: current
        }

        private fun credentialsPending(): Boolean = newestPendingCredentials() != null

        private fun newestPendingCredentials(): CarPlayEndpoint? {
            if (closed) return null
            return pending.lastOrNull { canQueue(it.endpoint) && hasFreshCredentials(it.endpoint) }?.endpoint
                ?: standby.get()?.takeIf { canQueue(it) && hasFreshCredentials(it) }
        }

        fun close() = synchronized(lock) {
            closed = true
            wake.close()
            pending.clear()
            latestReceivedCredentials = null
            wifiRecovery = null
        }
    }

    /** Network discovery may run outside the session lock. Its result must survive the same handoff epoch. */
    class Handoff<R : Any, N : Any> {
        private data class State<R, N>(
            val request: R? = null, val receiver: String? = null, val network: N? = null, val provisional: Boolean = false,
        )
        private val state = AtomicReference(State<R, N>())

        val cachedRequest: R? get() = state.get().takeIf { it.network != null }?.request
        val provisionalNetwork: N? get() = state.get().takeIf { it.provisional }?.network

        fun discoveryToken(): Any? = state.get().takeUnless { it.request != null && (it.network == null || it.provisional) }
        fun canCommitDiscovery(token: Any?): Boolean = token != null && token === state.get()

        fun begin(request: R, receiver: String): Boolean {
            val previous = state.get()
            return previous.request == null && state.compareAndSet(previous, State(request, receiver))
        }

        fun matchesTarget(target: String?, established: Boolean): Boolean =
            established || state.get().receiver?.equals(target, ignoreCase = true) != false

        fun isJoining(request: R): Boolean = state.get().let { it.request === request && it.network == null }

        fun bind(request: R, network: N) {
            val previous = state.get()
            check(previous.request === request && previous.network == null)
            check(state.compareAndSet(previous, previous.copy(network = network, provisional = true)))
        }

        fun finishJoining(request: R) {
            val previous = state.get()
            if (previous.request === request && previous.network == null) state.compareAndSet(previous, State())
        }

        fun confirm(network: N) {
            val previous = state.get()
            if (previous.provisional && previous.network == network)
                state.compareAndSet(previous, previous.copy(provisional = false))
        }

        fun provisionalRequest(network: N): R? = state.get().takeIf { it.provisional && it.network == network }?.request

        fun clear(request: R) {
            val previous = state.get()
            if (previous.request === request) state.compareAndSet(previous, State())
        }
    }

    fun credentialBlockReason(target: String?, bonded: String?, paired: Boolean, desktop: Boolean): String? = when {
        target == null || PairingEvidence.receiverKey(target) == null -> "target_not_selected"
        !target.equals(bonded, ignoreCase = true) -> "bluetooth_not_bonded"
        !paired -> "carplay_not_paired"
        desktop -> "desktop_receiver"
        else -> null
    }

    fun canRemember(
        passphrase: String?, freshHotspot: Boolean, ownedRequest: Boolean, localOnly: Boolean, desktop: Boolean,
    ): Boolean = !passphrase.isNullOrEmpty() && freshHotspot && ownedRequest && localOnly && !desktop

    fun canUseEndpoint(hasCache: Boolean, carNetworkReady: Boolean, freshCredentials: Boolean): Boolean =
        !hasCache || carNetworkReady || freshCredentials

    fun canUseIap2Activation(automaticProvisionalWifi: Boolean, freshCredentials: Boolean): Boolean =
        !automaticProvisionalWifi || freshCredentials

    fun <N> canReplaceBinding(bound: N?, invitationOnly: N?, currentSsid: String?, cachedSsid: String): Boolean =
        bound == null || (bound == invitationOnly && currentSsid != cachedSsid)

    @Synchronized
    fun save(context: Context, receiver: String, credentials: Credentials) {
        val peer = PairingEvidence.receiverKey(receiver) ?: return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
        cipher.updateAAD(peer.encodeToByteArray())
        val payload = credentials.encode()
        val ciphertext = cipher.doFinal(payload)
        check(cipher.iv.size == NONCE_BYTES)
        val file = file(context, peer)
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(ciphertext)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun load(context: Context, receiver: String): Credentials? {
        val peer = PairingEvidence.receiverKey(receiver) ?: return null
        val file = file(context, peer)
        if (!file.baseFile.isFile) return null
        val stored = file.readFully()
        val key = key(create = false) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored, 0, NONCE_BYTES))
        cipher.updateAAD(peer.encodeToByteArray())
        val decoded = cipher.doFinal(stored, NONCE_BYTES, stored.size - NONCE_BYTES)
        return Credentials.decode(decoded)
    }

    @Synchronized
    fun forget(context: Context, receiver: String) {
        PairingEvidence.receiverKey(receiver)?.let { file(context, it).delete() }
    }

    @Synchronized
    fun forgetAll(context: Context) {
        directory(context).listFiles().orEmpty()
            .filter { it.name.matches(Regex("bt_[0-9a-f]{12}(?:\\.new|\\.bak)?")) }
            .forEach { it.delete() }
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "bmw-local-wifi")

    private fun file(context: Context, peer: String) = AtomicFile(File(directory(context), peer))

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}
