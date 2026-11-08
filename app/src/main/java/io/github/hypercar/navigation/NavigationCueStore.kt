package io.github.hypercar.navigation

/** A removed notification or a late callback from a previous session cannot revive an old arrow. */
internal class NavigationCueStore(private val maxAgeMillis: Long = 90_000L) {
    private data class Entry(val key: String, val cue: NavigationCue, val updated: Long)
    private var generation = 0L
    private var entry: Entry? = null
    @Synchronized fun generation(): Long = generation
    @Synchronized fun put(generation: Long, key: String, cue: NavigationCue, updated: Long) {
        if (generation == this.generation) entry = Entry(key, cue, updated)
    }
    @Synchronized fun remove(key: String) { if (entry?.key == key) entry = null }
    @Synchronized fun clear() { generation++; entry = null }
    @Synchronized fun snapshot(now: Long): NavigationCue? = entry?.takeIf { now - it.updated in 0..maxAgeMillis }?.cue
}
