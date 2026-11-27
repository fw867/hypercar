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

    companion object {
        fun queuesInputLine(line: String): Boolean =
            line.startsWith("PHONE-HID: ") || line.startsWith("HID-INPUT: ") ||
                line.startsWith("HID-KEY: ") || line.startsWith("HID-LAYOUT: ") ||
                line.startsWith("PHONE-KEY: ") || line.startsWith("VOICE-KEY: ") ||
                line.startsWith("CARWITH-KEY: ") || line.startsWith("CARWITH-TOUCH: ") ||
                line.startsWith("EVENT: HID 报告 ") || line.startsWith("TOUCH: 车机(") ||
                line.startsWith("EVENT: 车机命令 type=hidSendReport（") ||
                line.startsWith("EVENT: 车机命令 type=requestSiri（") ||
                line.startsWith("MEDIA-KEY: ") || line.startsWith("MEDIA-KEY-GATE: ") ||
                line.startsWith("CARUI: console input ") ||
                line.startsWith("IAP2-MEDIA: ") || line.startsWith("iap2 rx=0x6800 non-media/undeclared HID ") ||
                isScopedHidLine(line)

        private val iap2HidFrame = Regex("""\b(?:RX|ACK-only) .*\bid=0x680[0-3](?: |$)""")

        private fun isScopedHidLine(line: String): Boolean =
            (line.startsWith("HS#") || line.startsWith("WIFI-IAP#")) &&
                (" IAP2-MEDIA: " in line || " AirPlay HID END " in line ||
                    " iap2 rx=0x6800 non-media/undeclared HID " in line || iap2HidFrame.containsMatchIn(line))

        fun queuesNativeLine(line: String): Boolean = queuesInputLine(line) ||
            line.startsWith("VIDEO-FEEDBACK: ") || line.startsWith("VIDEO-QUEUE: ") ||
            line.startsWith("VIDEO-QOS: ") || line.startsWith("VIDEO: reset source ") ||
            line.startsWith("VIDEO: keepAlive written ") || line.startsWith("CLUSTER: keepAlive written ")
    }
}
