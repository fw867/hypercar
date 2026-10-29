package io.github.hypercar.carui

internal object AutoCruisePolicy {
    const val DELAY_MS = 10_000L

    // Only inspect the active-fragment record, not historical transactions or saved pages.
    fun baiduIsIdle(activityDump: String): Boolean {
        val active = activityDump.substringAfter("Active Fragments:", "")
            .substringBefore("Added Fragments:")
        if (active.isEmpty()) return false
        return active.contains("HiCarMapFramePage{") &&
            !active.contains("HiCarCruiserFragment{") &&
            !Regex("(?i)(navi|route|search|cruis)[A-Za-z]*\\{").containsMatchIn(active)
    }
}
