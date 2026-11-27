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

    fun recover(
        targetSsid: String,
        owned: () -> Boolean,
        current: () -> SavedNetwork?,
        isCurrent: (SavedNetwork) -> Boolean,
        disconnect: () -> Boolean,
        scan: () -> Unit,
    ): Boolean {
        if (closed || attempted || targetSsid.isBlank() || !owned()) return false
        val previous = current()?.takeIf { it.id >= 0 && it.ssid.isNotBlank() && it.ssid != targetSsid } ?: return false
        attempted = true
        // Release only the current STA association. A saved network preference outlives the shell.
        if (!owned() || !isCurrent(previous) || !disconnect()) return false
        scan()
        return true
    }

    fun close() {
        closed = true
    }
}
