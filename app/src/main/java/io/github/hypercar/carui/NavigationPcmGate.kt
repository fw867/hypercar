package io.github.hypercar.carui

/** Turns continuous UID capture into bounded navigation utterances. */
internal class NavigationPcmGate(private val sink: Sink, private val silenceMs: Long = 400) {
    interface Sink {
        fun start()
        fun pcm(samples: ShortArray)
        fun stop()
    }
    private data class Quiet(val at: Long, val pcm: ShortArray)
    private val preroll = ArrayDeque<Quiet>()
    private var buffered = 0
    private var active = false
    private var closed = false
    private var lastTime: Long? = null
    private var lastSound = 0L
    init { require(silenceMs > 0) }

    @Synchronized fun accept(pcm: ShortArray, nowMillis: Long) {
        if (closed || pcm.isEmpty()) return
        if (lastTime?.let { nowMillis < it } == true) {
            end(); clearPreroll()
        }
        lastTime = nowMillis
        if (active && elapsed(nowMillis, lastSound) >= silenceMs) end()
        while (preroll.firstOrNull()?.let { elapsed(nowMillis, it.at) > 50 } == true) {
            buffered -= preroll.removeFirst().pcm.size
        }
        if (pcm.any { it.toInt() != 0 }) {
            if (!active) {
                active = true
                sink.start()
                preroll.forEach { sink.pcm(it.pcm) }
                clearPreroll()
            }
            lastSound = nowMillis
            sink.pcm(pcm)
        } else if (active) {
            sink.pcm(pcm)
        } else {
            // Largest supported input: 96kHz stereo; keep at most 60ms of samples.
            val kept = if (pcm.size > 11520) pcm.copyOfRange(pcm.size - 11520, pcm.size) else pcm.copyOf()
            preroll += Quiet(nowMillis, kept); buffered += kept.size
            while (buffered > 11520) {
                val first = preroll.removeFirst(); buffered -= first.pcm.size
                val excess = (first.pcm.size - (11520 - buffered)).coerceAtLeast(0)
                if (excess < first.pcm.size) {
                    val tail = first.pcm.copyOfRange(excess, first.pcm.size)
                    preroll.addFirst(Quiet(first.at, tail)); buffered += tail.size
                }
            }
        }
    }
    private fun elapsed(now: Long, before: Long): Long =
        (now - before).takeIf { now >= before && it >= 0 } ?: Long.MAX_VALUE
    private fun clearPreroll() { preroll.clear(); buffered = 0 }
    private fun end() { if (active) { active = false; sink.stop() } }
    @Synchronized fun close() { end(); closed = true; clearPreroll(); lastTime = null }
    @Synchronized fun reset() { close(); closed = false }
}
