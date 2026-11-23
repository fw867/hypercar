package io.github.hypercar.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Keeps the receive time of observations made before the driver marks a click. */
internal class CtrlDiscoveryTrace(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    scope: CoroutineScope,
    log: (String) -> Unit,
) {
    private data class Event(val elapsedMs: Long, val epochMs: Long, val details: String)
    private class Window(val marker: Long, val at: Long) {
        var emitted = 0
        fun format(event: Event): String? {
            // Keep endpoint transitions even if noisy discovery traffic exhausts the log budget.
            val endpoint = event.details.startsWith("event=listener-") || event.details.startsWith("event=click-state")
            if (!endpoint && ++emitted > 128) {
                return if (emitted == 129) "CTRL-CLICK: marker=$marker observationLimit=128" else null
            }
            return "CTRL-CLICK: marker=$marker deltaMs=${event.elapsedMs - at} " +
                "epochMs=${event.epochMs} elapsedMs=${event.elapsedMs} ${event.details}"
        }
    }

    // Keep a marker and its pre-click history together. Producers only enqueue; the writer owns I/O.
    private val pending = Channel<List<String>>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    init { scope.launch { for (lines in pending) lines.forEach(log) } }

    private val history = ArrayDeque<Event>()
    private var sequence = 0L
    private val windows = ArrayDeque<Window>()

    @Synchronized fun record(details: String) {
        val event = Event(elapsed(), wall(), details)
        remember(event)
        publishToWindows(event)
    }

    private fun publishToWindows(event: Event) {
        expireWindows(event.elapsedMs)
        val lines = windows.mapNotNull { it.format(event) }
        if (lines.isNotEmpty()) pending.trySend(lines)
    }

    private fun expireWindows(now: Long) {
        while (windows.firstOrNull()?.let { now - it.at > 30_000 } == true) windows.removeFirst()
    }

    fun enqueue(line: String) { pending.trySend(listOf(line)) }

    @Synchronized fun mark(prefix: List<String> = emptyList(), clickState: String? = null) {
        val at = elapsed()
        val epochMs = wall()
        if (clickState != null) {
            val state = Event(at, epochMs, clickState)
            remember(state)
            publishToWindows(state)
        } else {
            prune(at)
            expireWindows(at)
        }
        val newWindow = Window(++sequence, at)
        windows.addLast(newWindow)
        pending.trySend(prefix + "CTRL-CLICK: marker=${newWindow.marker} epochMs=$epochMs elapsedMs=$at" +
            history.mapNotNull(newWindow::format))
    }

    private fun remember(event: Event) {
        prune(event.elapsedMs)
        if (history.size == 64) history.removeFirst()
        history.addLast(event)
    }

    private fun prune(at: Long) {
        while (history.firstOrNull()?.let { at - it.elapsedMs > 15_000 } == true) history.removeFirst()
    }
}
