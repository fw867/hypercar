package io.github.hypercar.net

internal class MdnsSourceLogGate(private val nowMillis: () -> Long) {
    private val seen = HashSet<String>()
    private var windowStart: Long? = null
    private var emitted = 0
    private var suppressed = 0L

    @Synchronized fun admit(key: String): Boolean {
        if (key in seen) return false
        val now = nowMillis()
        if (windowStart?.let { now - it in 0 until 15_000L } != true) {
            windowStart = now
            emitted = 0
        }
        if (seen.size >= 64 || emitted >= 24) { suppressed++; return false }
        seen.add(key)
        emitted++
        return true
    }

    @Synchronized fun summary(): String = "source_history=${seen.size} source_suppressed=$suppressed"
}
