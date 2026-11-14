package io.github.hypercar.net

/** One scoped attempt to unblock scans while an owned local-only request waits on another AP. */
internal class WifiHandoffLease {
    data class SavedNetwork(val id: Int, val ssid: String, val autojoin: Boolean)
    companion object {
        /** Some ROMs duplicate the same primary STA config in getConfiguredNetworks(). */
        fun currentSavedNetwork(id: Int, ssid: String, candidates: List<SavedNetwork>): SavedNetwork? =
            candidates.filter { it.id == id && it.ssid == ssid }.distinct().singleOrNull()
    }
    private var attempted = false
    private var closed = false
    private var restore: (() -> Unit)? = null

    fun recover(
        targetSsid: String,
        owned: () -> Boolean,
        current: () -> SavedNetwork?,
        isCurrent: (SavedNetwork) -> Boolean,
        autojoin: (SavedNetwork, Boolean) -> Unit,
        disconnect: () -> Boolean,
        scan: () -> Unit,
    ): Boolean {
        if (closed || attempted || targetSsid.isBlank() || !owned()) return false
        val previous = current()?.takeIf { it.id >= 0 && it.ssid.isNotBlank() && it.ssid != targetSsid } ?: return false
        attempted = true
        try {
            if (previous.autojoin) {
                // Store rollback before the Binder call: it may apply a change and then throw.
                restore = { autojoin(previous, true) }
                autojoin(previous, false)
            }
            // Recheck live identity, not the now-mutated autojoin metadata or duplicated list.
            if (!owned() || !isCurrent(previous)) {
                close(); return false
            }
            if (!disconnect()) { close(); return false }
            scan()
            return true
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    fun close() {
        closed = true
        val action = restore
        action?.invoke()
        restore = null
    }
}
