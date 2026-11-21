package io.github.hypercar.net

import java.net.InetAddress

/** Bounds passive observation's LinkProperties refresh across both multicast workers. */
internal class MdnsLocalAddressCache(
    private val elapsedRealtime: () -> Long,
    private val load: () -> Set<InetAddress>?,
) {
    private var loadedAt: Long? = null
    private var addresses: Set<InetAddress>? = null

    @Synchronized fun current(): Set<InetAddress>? {
        val now = elapsedRealtime()
        // Cache null too: never classify a packet against a stale successful lookup on failure.
        if (loadedAt?.let { now - it < 1_000L } == true) return addresses
        addresses = load()
        loadedAt = elapsedRealtime()
        return addresses
    }
}
