package io.github.hypercar.iap2

import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** JNI callbacks never block the RTSP reader; a bounded worker owns each protocol engine. */
object WifiIap2Bridge {
    private var active: Worker? = null

    @Synchronized fun start(id: Long, deviceId: String) {
        active?.close()
        active = Worker(id, deviceId).also { it.thread.start() }
    }

    @Synchronized fun receive(id: Long, bytes: ByteArray) {
        active?.takeIf { it.id == id }?.let {
            if (bytes.size > 65535 || !it.input.offer(bytes)) {
                HyperCarRepo.log("WIFI-IAP#$id: inbound queue overflow; stopping this link")
                it.close()
            }
        }
    }

    @Synchronized fun stop(id: Long) {
        if (active?.id == id) { active?.close(); active = null }
    }

    private class Worker(val id: Long, deviceId: String) {
        val input = ArrayBlockingQueue<ByteArray>(64)
        @Volatile private var closed = false
        private val protocol = WifiIap2Protocol(deviceId, { HyperCarRepo.log("WIFI-IAP#$id: $it") })
        val thread = Thread({
            var readyReported = false
            try {
                while (!closed && !protocol.failed) {
                    val bytes = input.poll(20, TimeUnit.MILLISECONDS)
                    val now = System.nanoTime() / 1_000_000
                    val output = if (bytes == null) protocol.advance(now) else protocol.feed(bytes, now)
                    // takeOutput batches several complete packets (ACK + identification + certificate).
                    // The box sends each separately; don't rely on BMW consuming concatenated frames.
                    val frames = WifiIap2Framing.split(output)
                    if (frames.size > 1) HyperCarRepo.log("WIFI-IAP#$id: split output into ${frames.size} commands bytes=${frames.joinToString { it.size.toString() }}")
                    if (frames.any { !RustCoreBridge.nativeSendIap2(id, it) }) {
                        HyperCarRepo.log("WIFI-IAP#$id: transport closed or output queue full")
                        break
                    }
                    if (protocol.ready && !readyReported) {
                        readyReported = true
                        RustCoreBridge.nativeMarkWifiIap2Ready(id, true)
                    }
                }
            } catch (_: InterruptedException) {
                // Normal generation teardown.
            } catch (error: Exception) {
                HyperCarRepo.log("WIFI-IAP#$id: ${error.javaClass.simpleName}: ${error.message}")
            } finally { closed = true; RustCoreBridge.nativeMarkWifiIap2Ready(id, false); input.clear() }
        }, "wifi-iap2-$id").apply { isDaemon = true }

        fun close() { closed = true; thread.interrupt(); input.clear() }
    }
}
