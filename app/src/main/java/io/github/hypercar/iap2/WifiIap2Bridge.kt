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
    private val liveVehicleLocation = MutableStateFlow<Iap2VehicleLocation?>(null)
    val vehicleLocation = liveVehicleLocation.asStateFlow()
    private val liveWheelSpeed = MutableStateFlow<Iap2WheelSpeed?>(null)
    val wheelSpeed = liveWheelSpeed.asStateFlow()

    @Synchronized fun destinationTarget() = active?.takeUnless { it.closed }?.destinationTarget

    @Synchronized fun start(id: Long, deviceId: String, deviceName: String, profileId: String) {
        active?.close()
        active = Worker(id, deviceId, deviceName, profileId).also { it.thread.start() }
    }

    @Synchronized fun receive(id: Long, ticket: Long, bytes: ByteArray, writtenSynAck: Long): Boolean {
        val worker = active?.takeIf { it.id == id && !it.closed } ?: return false
        if (bytes.size > 65535 || worker.input.remainingCapacity() <= 1 ||
            !worker.input.offer(Input.Bytes(ticket, bytes, writtenSynAck))) {
            HyperCarRepo.log("WIFI-IAP#$id: inbound queue overflow; stopping this link")
            worker.close()
            return false
        }
        return true
    }

    @Synchronized fun screenSetupAccepted(id: Long) {
        active?.takeIf { it.id == id && !it.closed }?.let {
            if (!it.input.offer(Input.ScreenSetupAccepted)) {
                HyperCarRepo.log("WIFI-IAP#$id: screen SETUP signal skipped: inbound queue full")
            }
        }
    }

    @Synchronized fun stop(id: Long) {
        if (active?.id == id) { active?.close(); active = null }
    }

    internal fun canStartHudWrite(id: Long, generation: Long, ticket: Long): Boolean {
        val worker = synchronized(this) { active?.takeIf { it.id == id && !it.closed } } ?: return false
        return worker.canStartHudWrite(generation, ticket)
    }

    @Synchronized private fun isCurrent(id: Long): Boolean = active?.let { it.id == id && !it.closed } == true

    @Synchronized private fun publishVehicle(worker: Worker, status: Iap2VehicleStatus) {
        if (active === worker && !worker.closed) {
            liveVehicleStatus.value = status
        }
    }

    @Synchronized private fun publishVehicleLocation(worker: Worker, location: Iap2VehicleLocation?) {
        if (active !== worker || worker.closed) return
        liveVehicleLocation.value = location
        RustCoreBridge.nativePublishCarWithGps(worker.id, location?.carLifeGpsFields(System.currentTimeMillis()))
    }

    @Synchronized private fun publishWheelSpeed(worker: Worker, speed: Iap2WheelSpeed?) {
        if (active === worker && !worker.closed) liveWheelSpeed.value = speed
    }

    @Synchronized private fun clearVehicle(worker: Worker) {
        if (active !== worker) return
        liveVehicleStatus.value = Iap2VehicleStatus()
        liveVehicleLocation.value = null
        RustCoreBridge.nativePublishCarWithGps(worker.id, null)
        liveWheelSpeed.value = null
    }

    private sealed interface Input {
        class Bytes(val ticket: Long, val data: ByteArray, val writtenSynAck: Long) : Input
        data object ScreenSetupAccepted : Input
    }

    private class Worker(val id: Long, deviceId: String, deviceName: String, profileId: String) {
        private class Output(val bytes: ByteArray, val trace: Iap2LinkEngine.HudFrame?, val ticket: Long, val hudFirst: Boolean)
        private val pending = ArrayDeque<Output>()
        // Reserve one slot for the ordered screen SETUP signal.
        val input = ArrayBlockingQueue<Input>(65)
        @Volatile var closed = false
        private val protocol = WifiIap2Protocol(deviceId, { HyperCarRepo.log("WIFI-IAP#$id: $it") },
            navigationEnabled = { HyperCarRepo.hudNavigationAllowed },
            navigationCue = { io.github.hypercar.navigation.NavigationSources.snapshot() },
            deviceName = deviceName,
            nowPlayingProvider = Iap2NowPlayingSource::snapshot,
            onVehicleStatus = { publishVehicle(this, it) },
            onMediaKey = { usage -> HyperCarRepo.onIap2MediaKey(usage) { isCurrent(id) } },
            profileId = profileId, onPeerSynAccepted = { RustCoreBridge.nativeWifiIap2PeerSyn(id) },
            onVehicleLocation = { publishVehicleLocation(this, it) }, onWheelSpeed = { publishWheelSpeed(this, it) },
            callStateSource = Iap2CallStateSource(),
            onTransportReady = { ready, generation -> RustCoreBridge.nativeMarkWifiIap2Ready(id, generation, ready) },
            transportCapacity = { generation -> RustCoreBridge.nativeWifiIap2SendCapacity(id, generation) - pending.size },
            allowBulkStart = { input.isEmpty() && RustCoreBridge.nativeWifiIap2IngressFence(id, 0) == 0L },
            cancelPendingWrite = { generation, ticket -> RustCoreBridge.nativeCancelWifiIap2Write(id, generation, ticket) },
            socketWriteFeedback = true,
            destinationTransportCurrent = { generation -> RustCoreBridge.nativeWifiIap2DestinationCurrent(id, generation) })
        val destinationTarget get() = protocol.destinationTarget
        fun canStartHudWrite(generation: Long, ticket: Long) = !closed && protocol.canStartHudWrite(generation, ticket)
        val thread = Thread({
            var lastPhase = ""
            var queuedFrames = 0L
            var queuedBytes = 0L
            var retryFence = 0L
            var destinationFence = 0L
            val batch = ArrayList<Input>(65)
            try {
                while (!closed && !protocol.failed) {
                    input.poll(20, TimeUnit.MILLISECONDS)?.let(batch::add)
                    input.drainTo(batch, 64)
                    val now = System.nanoTime() / 1_000_000
                    val previousGeneration = protocol.outputGeneration
                    for (event in batch) {
                        when (event) {
                            is Input.Bytes -> {
                                protocol.ingest(event.data, now, event.writtenSynAck)
                                RustCoreBridge.nativeFinishWifiIap2Ingress(id, event.ticket)
                            }
                            Input.ScreenSetupAccepted -> protocol.screenSetupAccepted()
                        }
                        if (closed || protocol.failed) break
                    }
                    batch.clear()
                    if (closed || protocol.failed) break
                    val activeGeneration = protocol.outputGeneration
                    if (previousGeneration != activeGeneration) {
                        pending.clear()
                        retryFence = 0
                        destinationFence = 0
                    }
                    RustCoreBridge.nativeTakeWifiIap2Writes(id, activeGeneration)?.let { completed ->
                        for (index in completed.indices step 2) {
                            val result = completed[index + 1]
                            protocol.socketWriteFinished(completed[index], result.takeIf { it >= 0 }, sourceRevoked = result == -2L)
                        }
                    }
                    // Drain a fixed set of earlier ingress before expiry or destination confirmation.
                    retryFence = if (protocol.retryDeadlineReached(now))
                        RustCoreBridge.nativeWifiIap2IngressFence(id, retryFence) else 0L
                    destinationFence = if (protocol.hasWrittenDestinationOffer)
                        RustCoreBridge.nativeWifiIap2IngressFence(id, destinationFence) else 0L
                    val output = protocol.advance(now, allowRetryExpiry = retryFence == 0L,
                        allowDestinationConfirmation = destinationFence == 0L)
                    if (protocol.failed || closed) break
                    if (protocol.diagnosticPhase != lastPhase) {
                        lastPhase = protocol.diagnosticPhase
                        RustCoreBridge.nativeSetWifiIap2Phase(id, lastPhase)
                        HyperCarRepo.log("WIFI-IAP#$id: phase=$lastPhase")
                    }
                    // takeOutput batches several complete packets (ACK + identification + certificate).
                    // The box sends each separately; don't rely on BMW consuming concatenated frames.
                    val frames = WifiIap2Framing.split(output)
                    val hudFrames = protocol.takeHudOutput()
                    val socketWrites = protocol.takeSocketWrites().associateBy { it.offset }
                    var offset = 0
                    if (frames.size > 1) HyperCarRepo.log("WIFI-IAP#$id: split output into ${frames.size} commands bytes=${frames.joinToString { it.size.toString() }}")
                    if (pending.size + frames.size > 512) {
                        HyperCarRepo.log("WIFI-IAP#$id: persistent control output backlog")
                        break
                    }
                    frames.forEach {
                        val hud = hudFrames.firstOrNull { frame -> frame.offset == offset }
                        val write = socketWrites[offset]
                        val ticket = if (write?.cancellable == true) -write.ticket else write?.ticket ?: 0L
                        offset += it.size
                        pending.addLast(Output(it, hud, ticket, write?.hudFirst == true))
                    }
                    val capacity = RustCoreBridge.nativeWifiIap2SendCapacity(id, activeGeneration)
                    if (capacity < 0) break
                    var accepted = true
                    for (index in 0 until minOf(capacity, pending.size)) {
                        val frame = pending.first()
                        if (!RustCoreBridge.nativeSendIap2(id, activeGeneration, frame.bytes,
                                frame.trace?.messageId ?: 0, frame.trace?.serial ?: 0, frame.ticket, frame.hudFirst)) {
                            accepted = false
                            break
                        }
                        pending.removeFirst()
                        queuedFrames++
                        queuedBytes += frame.bytes.size
                    }
                    if (!accepted) {
                        HyperCarRepo.log("WIFI-IAP#$id: transport closed during enqueue")
                        break
                    }
                    if (pending.isNotEmpty()) continue
                    protocol.outputEnqueued()
                    if (input.isEmpty() && protocol.takeStartupDetect() &&
                        !RustCoreBridge.nativeSendWifiIap2Detect(id, activeGeneration)) {
                        HyperCarRepo.log("WIFI-IAP#$id: startup DETECT could not be queued")
                    }
                }
            } catch (_: InterruptedException) {
                // Normal generation teardown.
            } catch (error: Exception) {
                HyperCarRepo.log("WIFI-IAP#$id: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                protocol.revokeDestinationTransport()
                closed = true
                RustCoreBridge.nativeMarkWifiIap2Ready(id, protocol.outputGeneration, false)
                protocol.end()
                clearVehicle(this)
                HyperCarRepo.log("WIFI-IAP#$id: END ${protocol.diagnosticSummary} native_queued_frames=$queuedFrames native_queued_bytes=$queuedBytes input_pending=${input.size}")
                RustCoreBridge.nativeSetWifiIap2Phase(id, if (protocol.failed) "FAILED" else "STOPPED")
                pending.clear()
                input.clear()
            }
        }, "wifi-iap2-$id").apply { isDaemon = true }

        fun close() {
            protocol.revokeDestinationTransport()
            closed = true
            RustCoreBridge.nativeMarkWifiIap2Ready(id, protocol.outputGeneration, false)
            clearVehicle(this)
            thread.interrupt()
            input.clear()
        }
    }
}
