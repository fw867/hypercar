package io.github.hypercar.carui

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One recognition epoch, bounded in memory; never stores speech on disk. */
internal class ExternalPcmQueue {
    private val packets = ArrayBlockingQueue<ByteArray>(8)
    @Volatile var ended = false
        private set
    @Synchronized fun offer(pcm: ByteArray, rate: Int): Boolean {
        if (ended || rate != 16000 || pcm.isEmpty() || pcm.size > 8192 || pcm.size % 2 != 0) return false
        return packets.offer(pcm.copyOf())
    }
    fun poll(): ByteArray? = packets.poll(40, TimeUnit.MILLISECONDS)
    fun drained(): Boolean = ended && packets.isEmpty()
    @Synchronized fun end() { ended = true }
    @Synchronized fun cancel() { ended = true; packets.clear() }
}
