package io.github.hypercar.navigation

internal data class TimedNavigationCue(val cue: NavigationCue, val updatedAt: Long)

/** A removed notification or a late callback from a previous session cannot revive an old arrow. */
internal class NavigationCueStore(private val maxAgeMillis: Long = 90_000L) {
    private data class Entry(val key: String, val sample: TimedNavigationCue)
    private var generation = 0L
    private var entry: Entry? = null
    @Synchronized fun generation(): Long = generation
    @Synchronized fun put(generation: Long, key: String, cue: NavigationCue, updated: Long) {
        if (generation == this.generation) entry = Entry(key, TimedNavigationCue(cue, updated))
    }
    @Synchronized fun remove(key: String) { if (entry?.key == key) entry = null }
    @Synchronized fun clear() { generation++; entry = null }
    @Synchronized fun timedSnapshot(now: Long): TimedNavigationCue? =
        entry?.sample?.takeIf { now - it.updatedAt in 0..maxAgeMillis }

    fun snapshot(now: Long): NavigationCue? = timedSnapshot(now)?.cue
}
