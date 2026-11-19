package io.github.hypercar.iap2

import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** JNI callbacks never block the RTSP reader; a bounded worker owns each protocol engine. */
object WifiIap2Bridge {
    private var active: Worker? = null
    private val liveVehicleStatus = MutableStateFlow(Iap2VehicleStatus())
    val vehicleStatus = liveVehicleStatus.asStateFlow()

    @Synchronized fun start(id: Long, deviceId: String, deviceName: String) {
        active?.close()
        active = Worker(id, deviceId, deviceName).also { it.thread.start() }
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

    @Synchronized private fun isCurrent(id: Long): Boolean = active?.let { it.id == id && !it.closed } == true

    @Synchronized private fun publishVehicle(worker: Worker, status: Iap2VehicleStatus) {
        if (active === worker && !worker.closed) {
            liveVehicleStatus.value = status
        }
    }

    @Synchronized private fun clearVehicle(worker: Worker) {
        if (active === worker) liveVehicleStatus.value = Iap2VehicleStatus()
    }

    private class Worker(val id: Long, deviceId: String, deviceName: String) {
        val input = ArrayBlockingQueue<ByteArray>(64)
        @Volatile var closed = false
        private val protocol = WifiIap2Protocol(deviceId, { HyperCarRepo.log("WIFI-IAP#$id: $it") },
            navigationEnabled = { HyperCarRepo.hudNavigationAllowed },
            navigationCue = { io.github.hypercar.navigation.NavigationSources.snapshot() },
            deviceName = deviceName,
            nowPlayingProvider = Iap2NowPlayingSource::snapshot,
            onVehicleStatus = { publishVehicle(this, it) },
            onMediaKey = { usage -> HyperCarRepo.onIap2MediaKey(usage) { isCurrent(id) } })
        val thread = Thread({
            var readyReported = false
            var lastPhase = ""
            var queuedFrames = 0L
            var queuedBytes = 0L
            try {
                while (!closed && !protocol.failed) {
                    val bytes = input.poll(20, TimeUnit.MILLISECONDS)
                    val now = System.nanoTime() / 1_000_000
                    val output = if (bytes == null) protocol.advance(now) else protocol.feed(bytes, now)
                    if (protocol.diagnosticPhase != lastPhase) {
                        lastPhase = protocol.diagnosticPhase
                        RustCoreBridge.nativeSetWifiIap2Phase(id, lastPhase)
                        HyperCarRepo.log("WIFI-IAP#$id: phase=$lastPhase")
                    }
                    // takeOutput batches several complete packets (ACK + identification + certificate).
                    // The box sends each separately; don't rely on BMW consuming concatenated frames.
                    val frames = WifiIap2Framing.split(output)
                    val hudFrames = protocol.takeHudOutput()
                    var offset = 0
                    if (frames.size > 1) HyperCarRepo.log("WIFI-IAP#$id: split output into ${frames.size} commands bytes=${frames.joinToString { it.size.toString() }}")
                    if (frames.any {
                        val hud = hudFrames.firstOrNull { frame -> frame.offset == offset }
                        offset += it.size
                        val accepted = RustCoreBridge.nativeSendIap2(id, it, hud?.messageId ?: 0, hud?.serial ?: 0)
                        if (accepted) { queuedFrames++; queuedBytes += it.size }
                        !accepted
                    }) {
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
            } finally {
                closed = true
                protocol.endHudTrace()
                clearVehicle(this)
                HyperCarRepo.log("WIFI-IAP#$id: END ${protocol.diagnosticSummary} native_queued_frames=$queuedFrames native_queued_bytes=$queuedBytes input_pending=${input.size}")
                RustCoreBridge.nativeMarkWifiIap2Ready(id, false)
                RustCoreBridge.nativeSetWifiIap2Phase(id, if (protocol.failed) "FAILED" else "STOPPED")
                input.clear()
            }
        }, "wifi-iap2-$id").apply { isDaemon = true }

        fun close() { closed = true; clearVehicle(this); thread.interrupt(); input.clear() }
    }
}
