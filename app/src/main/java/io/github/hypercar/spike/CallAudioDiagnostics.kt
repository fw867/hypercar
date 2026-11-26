package io.github.hypercar.spike

internal data class CallAudioDiagnosticBatch(val firstSequence: Long, val lines: List<String>)

internal class CallAudioDiagnostics {
    private val entries = ArrayDeque<String>()
    private var nextSequence = 1L

    @Synchronized
    fun record(line: String) {
        if (entries.size == 48) entries.removeFirst()
        entries.addLast(line)
        nextSequence++
    }

    @Synchronized
    fun readAfter(publishedThrough: Long): CallAudioDiagnosticBatch {
        val oldest = nextSequence - entries.size
        val first = publishedThrough.coerceIn(0L, nextSequence - 1) + 1
        val available = maxOf(first, oldest)
        return CallAudioDiagnosticBatch(available, entries.drop((available - oldest).toInt()))
    }
}

/** Shared by the monitor and exporter within one shell binding. */
internal class CallAudioDiagnosticDelivery {
    @Volatile var publishedThrough = 0L
        private set

    @Synchronized
    fun publish(batch: CallAudioDiagnosticBatch, log: (String) -> Unit) {
        if (batch.lines.isEmpty()) return
        val missing = batch.firstSequence - publishedThrough - 1
        if (missing > 0) log("identity_diagnostics dropped=$missing")
        batch.lines.forEachIndexed { offset, line ->
            val sequence = batch.firstSequence + offset
            if (sequence > publishedThrough) {
                log(line)
                publishedThrough = sequence
            }
        }
    }
}
