package io.github.hypercar.iap2

import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/** One Wi-Fi link per AirPlay generation. All methods run on the bridge's single worker. */
internal class WifiIap2Protocol(
    private val deviceId: String,
    private val log: (String) -> Unit,
    private val nonce: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) },
    private val navigationEnabled: () -> Boolean = { false },
    private val navigationCue: () -> io.github.hypercar.navigation.NavigationCue? = { null },
    private val deviceName: String = "HyperCar",
    private val onMediaKey: (Int) -> Unit = {},
) {
    private val engine = Iap2LinkEngine(reliableTransport = true, declaredWindow = 32)
    private var parser = Iap2Csm.Parser()
    private val media = Iap2MediaSession(log, onMediaKey)
    private var navigation = RouteGuidanceSession(log)
    private var started = false
    private var phase = "LINK"
    private var deadline = 0L
    private var nextWaitLog = 0L
    private var retriedCertificate = false
    private var tracedFrames = 0
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
    private val deferred = linkedMapOf<Int, Iap2Csm.Frame>()
    val ready: Boolean get() = phase == "READY" && !failed && engine.queuedControlPackets == 0
    var failed = false
        private set

    init {
        engine.onDetectionRestart = {
            log("LINK DETECT restart phase=$phase; reset old CSM/auth/subscriptions, retain AirPlay transport")
            parser = Iap2Csm.Parser()
            media.reset()
            navigation = RouteGuidanceSession(log)
            phase = "LINK"
            deadline = now + 15_000
            nextWaitLog = 0
            retriedCertificate = false
            seen.clear()
            unknown.clear()
            deferred.clear()
        }
        // Metadata only: never log CSM certificate/challenge/signature payloads.
        engine.onFrameReceived = { ctl, seq, ack, session, size -> trace("RX", ctl, seq, ack, session, size) }
        engine.onFrameSent = { ctl, seq, ack, session, size -> trace("TX", ctl, seq, ack, session, size) }
        engine.onPeerSynchronizationAdopted = { sync ->
            log("LINK sessions=${sync.sessions.joinToString { "${it.id}/${it.kind}/${it.version}" }} peerWindow=${sync.maxOutgoing} localWindow=32 maxLength=${sync.maxLength} retry=${sync.retransmissionTimeoutMillis} maxRetries=${sync.maxRetransmissions} ack=${sync.acknowledgementTimeoutMillis}")
        }
    }

    fun feed(bytes: ByteArray, nowMillis: Long): ByteArray {
        if (failed) return ByteArray(0)
        if (bytes.isEmpty()) return advance(nowMillis)
        now = nowMillis
        if (!started) {
            started = true
            deadline = now + 15_000
            engine.start(now, passive = true)
            log("LINK peer bytes=${bytes.size}; reply DETECT, await peer SYN")
        }
        engine.feed(bytes, now)
        return advance(now)
    }

    fun advance(nowMillis: Long): ByteArray {
        if (failed) return ByteArray(0)
        now = nowMillis
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
            phase = "IDENTIFICATION"
            deadline = now + 15_000
            send(0x1d00)
        }
        while (true) {
            when (val event = engine.pollEvent() ?: break) {
                is Iap2LinkEngine.Event.Control -> parser.offer(event.bytes).forEach(::handle)
                is Iap2LinkEngine.Event.Dead -> fail(event.reason ?: "link closed")
            }
        }
        if (phase in listOf("CERTIFICATE", "CHALLENGE") && !failed && now >= nextWaitLog) {
            log("WAIT phase=$phase remaining=${(deadline-now).coerceAtLeast(0)}ms pending=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets}; RTSP write/200 does not prove peer link acceptance")
            nextWaitLog = now + 2_000
        }
        if (started && phase != "READY" && now > deadline) fail("phase=$phase timeout")
        if (phase == "READY" && !failed && engine.queuedControlPackets == 0 && engine.outstandingPackets < 8) {
            navigation.updates(now, navigationEnabled(), navigationCue()).forEach { send(it.messageId, it.body) }
        }
        return engine.takeOutput()
    }

    private fun handle(frame: Iap2Csm.Frame) {
        if (failed) return
        val id = frame.messageId
        if (media.handle(frame)) return
        if (id == 0x1d01 && phase == "READY") {
            media.identify(Iap2Messages.parseIdentification(frame.body).hidComponents)
            send(0x1d02)
            return
        }
        if (phase != "READY" || seen.add(id)) log("RX phase=$phase id=0x${id.toString(16)} body=${frame.body.size}B")
        if (id == 0x5200 || id == 0x5203) {
            // Retain bounded per-component subscriptions, including those arriving before auth.
            // advance() emits no navigation data until READY.
            navigation.handle(frame)
            return
        }
        if (id == 0xae02) {
            deferred.remove(0xae00) // StopPowerUpdates cancels an early subscription as well.
            return // No CSM reply defined; link ACK was already emitted.
        }
        if (phase != "READY" && id in listOf(0xae00, 0x4154, 0x4157, 0x4170, 0xad00, 0x5000)) {
            deferred[id] = frame
            log("DEFER phase=$phase id=0x${id.toString(16)} until authentication completes")
            return
        }
        when (id) {
            0x1d01 -> if (phase == "IDENTIFICATION") {
                val identification = Iap2Messages.parseIdentification(frame.body)
                media.identify(identification.hidComponents)
                val serialSupported = identification.declaresSend(0xaa06) == true
                send(0x1d02)
                phase = "CERTIFICATE"
                deadline = now + 10_000
                nextWaitLog = now + 2_000
                // Box firmware 0x60a74/0x634b8 checks AA06 support and adds empty parameter 0.
                // Unknown/unsupported peers retain CatPlay's default direct certificate request.
                log("AUTH certificate request=${if (serialSupported) "serial-first (peer declares AA06)" else "direct (AA06 not declared)"}")
                if (serialSupported) send(0xaa00, Iap2Csm.param(0, ByteArray(0))) else send(0xaa00)
            }
            0xaa06 -> if (phase == "CERTIFICATE" && !retriedCertificate) {
                retriedCertificate = true
                send(0xaa00)
            }
            0xaa01 -> if (phase == "CERTIFICATE") {
                if (Iap2Csm.parseParams(frame.body).none { it.id == 0 && it.value.isNotEmpty() }) {
                    fail("empty certificate")
                    return
                }
                phase = "CHALLENGE"
                deadline = now + 10_000
                nextWaitLog = now + 2_000
                val challenge = nonce()
                require(challenge.size == 32)
                send(0xaa02, Iap2Csm.param(0, challenge))
            }
            0xaa03 -> if (phase == "CHALLENGE") {
                if (Iap2Csm.parseParams(frame.body).none { it.id == 0 && it.value.isNotEmpty() }) {
                    fail("empty challenge response")
                    return
                }
                send(0xaa05)
                phase = "READY"
                log("READY challenge response received; compatibility result only, certificate/signature not verified")
                greetings()
                media.authenticated()
                val pending = deferred.values.toList()
                deferred.clear()
                pending.forEach(::handle)
            }
            0xaa04, 0x1d03 -> fail("peer rejected id=0x${id.toString(16)}")
            0xae00 -> if (phase == "READY") {
                send(0xae01, Iap2Csm.u8(4, 1))
                send(0xae01, Iap2Csm.u8(5, 1))
                send(0xae01, Iap2Csm.u16(6, 100))
            }
            0x4154 -> if (phase == "READY") send(0x4155, Iap2Csm.u8(2, 0))
            0x4157 -> if (phase == "READY") {
                // CatPlay CommunicationsUpdate: 5..8 are bridge capabilities, 12..17 call controls.
                // No telephony bridge is wired. Do not invent SIM/signal/registration state,
                // advertise working call controls, or emit undefined parameter 18.
                send(0x4158, *intArrayOf(5, 6, 7, 8, 12, 13, 14, 15, 16, 17)
                    .map { Iap2Csm.u8(it, 0) }.toTypedArray())
            }
            0x4170 -> if (phase == "READY") send(0x4171, Iap2Csm.u8(0, 0), Iap2Csm.u16(2, 0), Iap2Csm.u8(5, 0), Iap2Csm.u16(7, 0))
            // Firmware 0x63cb4..0x63cc8: AD00 -> AD01, parameter 0 (u8) = 1.
            0xad00 -> if (phase == "READY") send(0xad01, Iap2Csm.u8(0, 1))
            0x5000 -> if (phase == "READY") {
                send(0x5001,
                    Iap2Csm.group(0, Iap2Csm.u8(1, 0), Iap2Csm.u32(4, 0), Iap2Csm.u8(6, 0), Iap2Csm.u8(12, 0), Iap2Csm.u8(26, 0)),
                    Iap2Csm.group(1, Iap2Csm.u8(0, 0), Iap2Csm.u32(1, 0), Iap2Csm.u8(5, 0), Iap2Csm.u8(6, 0), Iap2Csm.u8(7, 0), Iap2Csm.u8(16, 0)))
            }
            // Vehicle/location/HID subscriptions still receive link ACK. Unknown payloads are not guessed.
            else -> if (unknown.add(id)) log("ACK-only id=0x${id.toString(16)} body=${frame.body.size}B")
        }
    }

    private fun greetings() {
        val zone = TimeZone.getDefault()
        send(0x4e0c, Iap2Csm.string(0, UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString().uppercase()))
        send(0xa100, Iap2Csm.param(6, ByteArray(0)))
        send(0x4e0a, Iap2Csm.string(0, "zh"))
        send(0x4e09, Iap2Csm.string(0, deviceName))
        log("device name=$deviceName")
        send(0x4e0b, Iap2Messages.deviceTime(System.currentTimeMillis() / 1000, zone.getOffset(System.currentTimeMillis()) / 60_000, 0))
    }

    private fun send(id: Int, vararg parts: ByteArray) {
        if (failed) return
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        if (!engine.sendControl(Iap2Csm.encode(id, body), now)) fail("link refused id=0x${id.toString(16)}")
        else log("QUEUED phase=$phase id=0x${id.toString(16)} body=${body.size}B pending=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets}")
    }

    private fun trace(direction: String, ctl: Int, seq: Int, ack: Int, session: Int, size: Int) {
        if (tracedFrames++ < 32) log("LINK $direction ctl=0x${ctl.toString(16)} seq=$seq ack=$ack session=$session bytes=$size")
    }

    private fun fail(reason: String) {
        if (!failed) log("FAILED $reason")
        failed = true
        engine.feedEof()
    }
}
