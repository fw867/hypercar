package io.github.hypercar.iap2

import java.security.SecureRandom
import java.util.TimeZone

/** One Wi-Fi link per AirPlay generation. All methods run on the bridge's single worker. */
internal class WifiIap2Protocol(
    private val deviceId: String,
    private val log: (String) -> Unit,
    private val nonce: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
    private val navigationEnabled: () -> Boolean = { false },
    private val navigationCue: () -> io.github.hypercar.navigation.NavigationCue? = { null },
    private val deviceName: String = "HyperCar",
    private val onMediaKey: (Int) -> Unit = {},
    private val nowPlayingProvider: () -> Iap2NowPlaying = { Iap2NowPlaying() },
    onVehicleStatus: (Iap2VehicleStatus) -> Unit = {},
    profileId: String = "",
    private val onPeerSynAccepted: () -> Unit = {},
    onVehicleLocation: (Iap2VehicleLocation?) -> Unit = {},
    callStateSource: Iap2CallStateSource? = null,
    private val onTransportReady: (Boolean) -> Boolean = { true },
) {
    private val audiMib3 = profileId == "audi_mib3"
    private val engine = Iap2LinkEngine(reliableTransport = true, declaredWindow = 32)
    private var parser = Iap2Csm.Parser()
    private val media = Iap2MediaSession(log, onMediaKey)
    private val nowPlaying = Iap2NowPlayingSession(nowPlayingProvider, log, initializeArtworkWithEmptyFile = true)
    private val vehicle = Iap2VehicleStatusSession(onVehicleStatus, log)
    private val location = Iap2VehicleLocationSession(onVehicleLocation)
    private var identification: Iap2Messages.Identification? = null
    private var authenticated = false
    private var navigation = RouteGuidanceSession(log)
    private val subscriptions = Iap2StatusSubscriptions(log = log, deviceName = deviceName,
        callState = { callStateSource?.snapshot() ?: Iap2CallSample.Unavailable },
        observeCalls = { callStateSource?.setSubscribed(it) })
    private var started = false
    private var phase = "LINK"
    private var deadline = 0L
    private var nextWaitLog = 0L
    private var retriedCertificate = false
    private var tracedFrames = 0
    private var receivedFrames = 0L
    private var sentFrames = 0L
    private var lastRx = "none"
    private var lastTx = "none"
    private var lastReceivedMessage: Int? = null
    private var lastQueuedMessage: Int? = null
    private var receivedNowPlayingStarts = 0L
    private var queuedNowPlayingUpdates = 0L
    private var locationFrames = 0L
    private var challengeSequence: Int? = null
    private var challengeAcknowledged = false
    private var challengeResponseReceived = false
    private var screenSetupSeen = false
    private var startupDetectTaken = false
    private var peerSynSeen = false
    private val challengeDiagnostic: String get() =
        "aa02_seq=${challengeSequence ?: "none"} aa02_ack=$challengeAcknowledged aa03_rx=$challengeResponseReceived"
    val diagnosticSummary: String get() = "phase=$diagnosticPhase pending=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets} " +
        "rx_frames=$receivedFrames tx_frames=$sentFrames $lastRx $lastTx " +
        "${media.diagnosticSummary} rx_5000=$receivedNowPlayingStarts queued_5001=$queuedNowPlayingUpdates " +
        "last_rx_csm=${lastReceivedMessage?.let { "0x${it.toString(16)}" } ?: "none"} " +
        "last_queued_csm=${lastQueuedMessage?.let { "0x${it.toString(16)}" } ?: "none"} " +
        "$challengeDiagnostic; link output/queue only, RTSP acceptance separate"
    private var now = 0L
    private var peerWaitSince: Long? = null
    private var nextPeerWaitLog = 0L
    val diagnosticPhase: String get() = when {
        failed -> "FAILED"
        !started && peerWaitSince?.let { now - it >= 15_000 } == true -> "WAIT_PEER_LONG"
        !started -> "WAIT_PEER"
        phase == "READY" -> "READY_PENDING"
        else -> phase
    }
    private val seen = mutableSetOf<Int>()
    private val unknown = mutableSetOf<Int>()
    val ready: Boolean get() = phase == "READY" && !failed && engine.queuedControlPackets == 0
    private var readyReported = false
    var failed = false
        private set

    init {
        engine.hudLog = log
        engine.deferInitialDetectEcho = audiMib3
        engine.onPeerAcknowledgement = { ack, lastSent ->
            val sequence = challengeSequence
            if (sequence != null && !challengeAcknowledged) {
                // Accept only cumulative ACKs that cover AA02 without acknowledging unsent bytes.
                val distanceToLastSent = (lastSent - sequence) and 255
                val distanceToAck = (ack - sequence) and 255
                if (distanceToLastSent < 128 && distanceToAck <= distanceToLastSent) {
                    challengeAcknowledged = true
                    log("AUTH AA02 cumulative ACK seq=$sequence peer_ack=$ack")
                }
            }
        }
        engine.onDetectionRestart = {
            readyReported = false
            // Invalidate the old generation before any new output is handed to the writer.
            onTransportReady(false)
            if (challengeSequence != null) log("AUTH LINK reset $challengeDiagnostic")
            challengeSequence = null
            challengeAcknowledged = false
            challengeResponseReceived = false
            log("LINK DETECT restart phase=$phase; reset old CSM/auth/subscriptions, retain AirPlay transport")
            parser = Iap2Csm.Parser()
            media.reset()
            nowPlaying.reset()
            vehicle.reset()
            location.reset()
            locationFrames = 0L
            identification = null
            authenticated = false
            subscriptions.reset()
            navigation = RouteGuidanceSession(log)
            phase = "LINK"
            deadline = now + 15_000
            nextWaitLog = 0
            retriedCertificate = false
            seen.clear()
            unknown.clear()
        }
        // Metadata only: never log CSM certificate/challenge/signature payloads.
        engine.onFrameReceived = { ctl, seq, ack, session, size -> trace("RX", ctl, seq, ack, session, size) }
        engine.onFrameSent = { ctl, seq, ack, session, size -> trace("TX", ctl, seq, ack, session, size) }
        engine.onPeerSynchronizationAdopted = { sync ->
            peerSynSeen = true
            if (audiMib3) onPeerSynAccepted()
            log("LINK sessions=${sync.sessions.joinToString { "${it.id}/${it.kind}/${it.version}" }} peerWindow=${sync.maxOutgoing} localWindow=32 maxLength=${sync.maxLength} retry=${sync.retransmissionTimeoutMillis} maxRetries=${sync.maxRetransmissions} ack=${sync.acknowledgementTimeoutMillis} maxCumAck=${sync.maxAcknowledgements}")
        }
    }

    fun screenSetupAccepted() {
        if (!audiMib3 || screenSetupSeen) return
        screenSetupSeen = true
        if (peerSynSeen) log("LINK startup DETECT skipped: peer SYN already accepted")
    }

    fun takeStartupDetect(): Boolean {
        if (!screenSetupSeen || startupDetectTaken || peerSynSeen || engine.hasPartialInput || failed) return false
        startupDetectTaken = true
        return true
    }

    fun takeHudOutput() = engine.takeHudOutput()

    /** The native writer adds its FIFO readiness barrier after these authentication frames. */
    fun outputEnqueued() {
        if (ready && !readyReported) {
            // The just-enqueued authentication frames may have filled the native queue.
            readyReported = onTransportReady(true)
        }
    }

    fun end() {
        subscriptions.reset()
        location.reset()
        engine.endHudTrace("transport_ended")
    }

    fun feed(bytes: ByteArray, nowMillis: Long): ByteArray {
        if (failed) return ByteArray(0)
        if (bytes.isEmpty()) return advance(nowMillis)
        now = nowMillis
        if (!started) {
            started = true
            deadline = now + 15_000
            engine.start(now, passive = true)
            log("LINK peer bytes=${bytes.size}; " +
                if (audiMib3) "parse immediately; startup DETECT gated on screen SETUP" else "reply DETECT, await peer SYN")
        }
        engine.feed(bytes, now)
        return advance(now)
    }

    fun advance(nowMillis: Long): ByteArray {
        if (failed) return ByteArray(0)
        now = nowMillis
        media.diagnosticsTick()
        location.expire(now)
        if (!started) {
            if (peerWaitSince == null) peerWaitSince = now
            if (now >= nextPeerWaitLog) {
                log("WAIT_PEER elapsed=${now - peerWaitSince!!}ms; no Wi-Fi iAP2 peer bytes; media session remains independent")
                nextPeerWaitLog = now + 10_000
            }
            return ByteArray(0)
        }
        engine.advanceTime(now)
        if (phase == "LINK" && engine.state == Iap2LinkEngine.State.NORMAL) {
            // xiap2 0x6b530 selects the opening CSM from the accepted control session version.
            if (engine.controlSessionVersion == 1) requestCertificate() else requestIdentification()
        }
        while (true) {
            when (val event = engine.pollEvent() ?: break) {
                is Iap2LinkEngine.Event.Control -> parser.offer(event.bytes).forEach(::handle)
                is Iap2LinkEngine.Event.FileTransfer -> nowPlaying.fileResponse(event.bytes, now)
                is Iap2LinkEngine.Event.Dead -> fail(event.reason ?: "link closed")
            }
        }
        if (phase in listOf("CERTIFICATE", "CHALLENGE") && !failed && now >= nextWaitLog) {
            log("WAIT phase=$phase remaining=${(deadline-now).coerceAtLeast(0)}ms pending=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets}; RTSP write/200 does not prove peer link acceptance")
            nextWaitLog = now + 2_000
        }
        if (started && phase != "READY" && now > deadline) fail("phase=$phase timeout")
        if (phase == "READY" && !failed && engine.queuedControlPackets == 0 &&
            (engine.zeroRetryAndAckMode || engine.outstandingPackets < 8)) {
            subscriptions.drain().forEach { send(it.messageId, it.body) }
            navigation.updates(now, navigationEnabled(), navigationCue()).forEach { send(it.messageId, it.body) }
            val limit = (engine.peerSynchronization?.maxLength ?: 0xffff) - Iap2LinkEngine.HEADER_BYTES - 1
            nowPlaying.poll(now, limit, engine.fileTransferSession != null).forEach {
                when (it) {
                    is Iap2NowPlayingSession.Output.Control -> send(Iap2MessageIds.NOW_PLAYING_UPDATE, it.body)
                    is Iap2NowPlayingSession.Output.File -> if (!engine.sendFileTransfer(it.bytes, now))
                        log("NOWPLAYING file packet not queued; optional artwork will expire")
                }
            }
        }
        return engine.takeOutput()
    }

    private fun handle(frame: Iap2Csm.Frame) {
        if (failed) return
        val id = frame.messageId
        lastReceivedMessage = id
        if (id == Iap2MessageIds.START_NOW_PLAYING_UPDATES) receivedNowPlayingStarts++
        if (id == Iap2MessageIds.VEHICLE_STATUS_UPDATE) {
            if (phase == "READY") vehicle.receive(frame.body)
            return
        }
        if (id == Iap2MessageIds.LOCATION_INFORMATION) {
            if (phase == "READY") {
                val wasFixed = location.hasFix
                location.receive(frame.body, now)
                locationFrames++
                if (locationFrames <= 3 || locationFrames % 60 == 0L || location.hasFix != wasFixed) {
                    log("GPS: RX id=0xfffb count=$locationFrames bytes=${frame.body.size} fixValid=${location.hasFix}")
                }
            }
            return
        }
        if (media.handle(frame) || nowPlaying.handle(frame)) return
        if (subscriptions.handle(frame)) {
            if (phase != "READY") log("DEFER phase=$phase id=0x${id.toString(16)} until identification and authentication complete")
            return
        }
        if (phase != "READY" || seen.add(id)) log("RX phase=$phase id=0x${id.toString(16)} body=${frame.body.size}B")
        if (id == 0x5200 || id == 0x5203) {
            // Retain bounded per-component subscriptions, including those arriving before auth.
            // advance() emits no navigation data until READY.
            navigation.handle(frame)
            return
        }
        when (id) {
            0x1d01 -> if (phase == "IDENTIFICATION" || phase == "READY") acceptIdentification(frame.body)
            0xaa06 -> if (phase == "CERTIFICATE" && !retriedCertificate) {
                retriedCertificate = true
                send(0xaa00)
            }
            0xaa01 -> if (phase == "CERTIFICATE") {
                val certificate = Iap2Csm.nonEmptyParameter(frame.body, 0)
                if (certificate == null) {
                    fail("empty certificate")
                    return
                }
                val certificateParamBytes = certificate.value.size + Iap2Csm.PARAM_HEADER_BYTES
                val challengeSize = mfiChallengeLengthForCertificateParam(certificateParamBytes)
                phase = "CHALLENGE"
                deadline = now + 10_000
                nextWaitLog = now + 2_000
                val challenge = nonce(challengeSize)
                require(challenge.size == challengeSize)
                log("AUTH AA02 selected certificateParamBytes=$certificateParamBytes challengeBytes=$challengeSize")
                send(0xaa02, Iap2Csm.param(0, challenge))
            }
            0xaa03 -> if (phase == "CHALLENGE") {
                challengeResponseReceived = true
                log("AUTH AA03 received $challengeDiagnostic")
                if (!Iap2Csm.hasNonEmptyParameter(frame.body, 0)) {
                    fail("invalid challenge response parameter")
                    return
                }
                send(0xaa05)
                if (failed) return
                authenticated = true
                if (identification == null) requestIdentification() else enterReady()
            }
            0xaa04, 0x1d03 -> fail("peer rejected id=0x${id.toString(16)}")
            // Unknown payloads still receive the link ACK.
            else -> if (unknown.add(id)) log("ACK-only id=0x${id.toString(16)} body=${frame.body.size}B")
        }
    }

    private fun requestIdentification() {
        phase = "IDENTIFICATION"
        deadline = now + 15_000
        send(0x1d00)
    }

    private fun requestCertificate(serialSupported: Boolean = false) {
        phase = "CERTIFICATE"
        deadline = now + 10_000
        nextWaitLog = now + 2_000
        // Box firmware 0x60a74/0x634b8 checks AA06 support before adding an empty parameter 0.
        val request = when {
            serialSupported -> "serial-first (peer declares AA06)"
            identification == null -> "direct (control version 1)"
            else -> "direct (AA06 not declared)"
        }
        log("AUTH certificate request=$request")
        if (serialSupported) send(0xaa00, Iap2Csm.param(0, ByteArray(0))) else send(0xaa00)
    }

    private fun acceptIdentification(body: ByteArray) {
        if (engine.controlSessionVersion == 1 &&
            Iap2Csm.parseParams(body).sumOf { it.value.size + Iap2Csm.PARAM_HEADER_BYTES } != body.size) {
            fail("invalid identification parameters")
            return
        }
        val peer = Iap2Messages.parseIdentification(body)
        identification = peer
        navigation.declareReceiveSupport(peer.acceptsRouteGuidance)
        subscriptions.identify(peer.model, peer.manufacturer)
        media.identify(peer.hidComponents)
        send(0x1d02)
        if (failed) return
        when {
            phase == "READY" -> {
                subscribeVehicle(peer)
                subscribeLocation(peer)
            }
            authenticated -> enterReady()
            else -> requestCertificate(peer.declaresSend(0xaa06) == true)
        }
    }

    private fun enterReady() {
        phase = "READY"
        log("READY challenge response received and identification accepted; compatibility result only, certificate/signature not verified")
        greetings()
        media.authenticated()
        nowPlaying.authenticated()
        subscriptions.authenticated()
        identification?.let(::subscribeLocation)
    }

    private fun greetings() {
        val zone = TimeZone.getDefault()
        send(0x4e0c, Iap2Csm.string(0, Iap2DeviceUuid.value))
        // iPhone requests configuration again on its authenticated AirPlay endpoint.
        // One request per authentication; 5703 stays ACK-only and never triggers Wi-Fi joining.
        send(0x5702)
        identification?.let(::subscribeVehicle)
        send(0x4e0a, Iap2Csm.string(0, "zh"))
        send(0x4e09, Iap2Csm.string(0, deviceName))
        log("device name=$deviceName")
        send(0x4e0b, Iap2Messages.deviceTime(System.currentTimeMillis() / 1000, zone.getOffset(System.currentTimeMillis()) / 60_000, 0))
    }

    private fun subscribeVehicle(peer: Iap2Messages.Identification) {
        vehicle.subscribe(peer)?.let { send(Iap2MessageIds.START_VEHICLE_STATUS_UPDATES, it) }
    }

    private fun subscribeLocation(peer: Iap2Messages.Identification) {
        if (!failed) location.subscribe(peer).forEach { send(it.messageId, it.body) }
    }

    private fun send(id: Int, vararg parts: ByteArray) {
        if (failed) return
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val frame = Iap2Csm.encode(id, body)
        val limit = (engine.peerSynchronization?.maxLength ?: 0xffff) - Iap2LinkEngine.HEADER_BYTES - 1
        if (limit <= 0) { fail("invalid negotiated payload limit"); return }
        var offset = 0
        while (offset < frame.size && !failed) {
            val end = minOf(frame.size, offset + limit)
            val hudMessageId = if (id == 0x5201 || id == 0x5202) id else 0
            val onSequenceAssigned: ((Int) -> Unit)? =
                if (id == 0xaa02 && end == frame.size) {
                    { sequence ->
                        challengeSequence = sequence
                        log("AUTH AA02 final_seq=$sequence")
                    }
                } else null
            if (!engine.sendControl(frame.copyOfRange(offset, end), now, hudMessageId = hudMessageId,
                    onSequenceAssigned = onSequenceAssigned)) {
                fail("link refused id=0x${id.toString(16)}")
            }
            offset = end
        }
        if (!failed) {
            lastQueuedMessage = id
            if (id == Iap2MessageIds.NOW_PLAYING_UPDATE) queuedNowPlayingUpdates++
            log("QUEUED phase=$phase id=0x${id.toString(16)} body=${body.size}B pending=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets}")
        }
    }

    private fun trace(direction: String, ctl: Int, seq: Int, ack: Int, session: Int, size: Int) {
        if (direction == "RX") {
            receivedFrames++
            lastRx = "rx_seq=$seq rx_ack=$ack rx_session=$session rx_bytes=$size"
        } else {
            sentFrames++
            lastTx = "tx_seq=$seq tx_ack=$ack tx_session=$session tx_bytes=$size"
        }
        if (tracedFrames++ < 128) log("LINK $direction ctl=0x${ctl.toString(16)} seq=$seq ack=$ack session=$session bytes=$size")
        else if (tracedFrames == 129) log("LINK trace limit reached; terminal summary continues counting all frames")
    }

    private fun fail(reason: String) {
        if (!failed) log("FAILED $reason")
        failed = true
        location.reset()
        engine.feedEof()
    }
}
