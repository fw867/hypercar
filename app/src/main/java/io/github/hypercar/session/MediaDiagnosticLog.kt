package io.github.hypercar.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Serial, bounded handoff from native callbacks to the existing log writer. */
internal class MediaDiagnosticLog(
    capacity: Int = 128,
    private val write: suspend (String) -> Unit,
) {
    private sealed interface Entry {
        data class Line(val text: String) : Entry
        data class Barrier(val done: CompletableDeferred<Unit>) : Entry
    }

    private val entries = Channel<Entry>(capacity)
    private val dropped = AtomicInteger()

    fun offer(line: String) {
        if (entries.trySend(Entry.Line(line)).isFailure) dropped.incrementAndGet()
    }

    suspend fun run() {
        for (entry in entries) {
            val overflow = dropped.getAndSet(0)
            if (overflow > 0) write("VIDEO-LOG: 队列已满，丢弃 $overflow 条诊断")
            when (entry) {
                is Entry.Line -> write(entry.text)
                is Entry.Barrier -> entry.done.complete(Unit)
            }
        }
    }

    suspend fun flush() {
        withTimeoutOrNull(2_000) {
            val done = CompletableDeferred<Unit>()
            entries.send(Entry.Barrier(done))
            done.await()
        }
    }
}
