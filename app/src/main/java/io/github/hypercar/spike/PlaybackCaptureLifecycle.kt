package io.github.hypercar.spike

internal data class PlaybackUidRule(val uid: Int, val exclude: Boolean)
internal fun playbackUidRules(navigationUid: Int): List<PlaybackUidRule> {
    require(navigationUid > 0) { "navigation UID must identify an installed application" }
    return listOf(PlaybackUidRule(navigationUid, true), PlaybackUidRule(navigationUid, false))
}

/** Own resources as soon as they are acquired; all startup failures share this rollback. */
internal class PlaybackCaptureCleanup {
    private var released = false
    private val actions = ArrayDeque<() -> Unit>()
    @Synchronized fun own(close: () -> Unit) {
        if (released) {
            runCatching(close)
            error("capture startup already retired")
        }
        actions.addFirst(close)
    }
    fun close() {
        val pending = synchronized(this) {
            if (released) return
            released = true
            actions.toList().also { actions.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}
