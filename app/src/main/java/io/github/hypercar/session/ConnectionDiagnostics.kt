package io.github.hypercar.session

/** Bounded evidence journal. Tokens reject late events; a missing milestone is never inferred as success. */
class ConnectionDiagnostics(private val clockMillis: () -> Long) {
    enum class Stage {
        REQUESTED, DEPENDENCIES_CHECKED, BLUETOOTH_LISTENING, IAP2_STARTED,
        IAP2_PARAMETERS, WIFI_BOUND, CTRL_REGISTERED, CTRL_INVITED, AIRPLAY_START,
        RECORD, RESOURCES_ACCEPTED, CONTROL_READY, SCREEN_SETUP, VIDEO_CHANNEL_READY,
        FIRST_VIDEO_FRAME, WAITING_INVITATION, UI_STARTED,
        BT_INBOUND_ADMITTED, CTRL_TCP_ACCEPTED, CTRL_INVITE_HEAD, CTRL_INVITE_ACK,
    }
    private data class Attempt(
        val id: Long, val source: String, val profile: String, val started: Long, val startedUptime: Long,
        val reached: LinkedHashMap<Stage, Long> = linkedMapOf(),
        val notes: LinkedHashMap<Stage, String> = linkedMapOf(),
        var outcome: String = "WAITING", var reason: String = "", var ended: Long? = null,
        var transport: Transport? = null,
        val earlyInbound: ArrayDeque<Pair<String, Long>> = ArrayDeque(),
    )
    private data class Transport(
        val engine: Long, val peer: String?, val anchorUptime: Long, val anchorEpoch: Long,
        var rtsp: Long? = null, var opened: Long? = null, var closed: Long? = null,
        var closeReason: String? = null, var tcp: String? = null, var reentry: Long? = null,
        var lastInbound: Long? = null,
    ) {
        fun accepted(at: Long) {
            if (at <= (lastInbound ?: -1L)) return
            if (lastInbound != null || opened?.let { at >= it } == true) reentry = at
            lastInbound = at
        }
        fun summary() = "diag_clock=uptime_ms anchor_uptime_ms=$anchorUptime anchor_unix_ms=$anchorEpoch " +
            "event_rtsp=${rtsp ?: "unknown"} event_open_ms=${opened ?: "unknown"} event_close_ms=${closed ?: "unknown"} " +
            "event_close=[${closeReason ?: "unknown"}] video_tcp=[${tcp ?: "unknown"}] rfcomm_reentry_ms=${reentry ?: "unknown"}"
    }
    private var serial = 0L
    private val history = ArrayDeque<Attempt>()

    @Synchronized fun engineStarted(id: Long, engine: Long, peer: String?, uptime: Long, epoch: Long) {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        val t = Transport(engine, peer, uptime, epoch)
        for ((address, at) in a.earlyInbound) if (address.equals(peer, true)) t.accepted(at)
        a.earlyInbound.clear()
        a.transport = t
    }

    @Synchronized fun eventChannel(id: Long, engine: Long, rtsp: Long, at: Long, cause: String, tcp: String) {
        // Teardown can finish after a new invitation. Enrich only the original engine's row.
        val t = history.find { it.id == id }?.transport?.takeIf { it.engine == engine } ?: return
        if (t.closed != null || (t.rtsp != null && t.rtsp != rtsp)) return
        t.rtsp = rtsp
        if (cause == "open") t.opened = at else {
            t.closed = at
            t.closeReason = clean(cause)
            if (tcp.isNotEmpty()) t.tcp = tcp
        }
    }

    @Synchronized fun rfcommAccepted(id: Long, peer: String?, at: Long) {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null && at >= it.startedUptime } ?: return
        if (peer == null) return
        // Raw accepts are logged separately; only sockets admitted to an iAP2 worker reach this stage.
        a.reached.putIfAbsent(Stage.BT_INBOUND_ADMITTED, (clockMillis() - a.started).coerceAtLeast(0))
        val t = a.transport
        if (t == null) {
            a.earlyInbound.addLast(peer to at)
            if (a.earlyInbound.size > 16) a.earlyInbound.removeFirst()
        } else if (at >= t.anchorUptime && peer.equals(t.peer, true)) t.accepted(at)
    }

    @Synchronized fun videoSample(id: Long, engine: Long, sample: String) {
        val t = history.lastOrNull()?.takeIf { it.id == id && it.ended == null }?.transport ?: return
        if (t.engine == engine && t.closed == null) t.tcp = sample
    }

    @Synchronized fun begin(source: String, profile: String, startedUptime: Long = clockMillis()): Long {
        history.lastOrNull()?.takeIf { it.ended == null }?.let {
            it.outcome = "SUPERSEDED"; it.ended = clockMillis()
        }
        val attempt = Attempt(++serial, clean(source), clean(profile), clockMillis(), startedUptime)
        attempt.reached[Stage.REQUESTED] = 0L
        history.addLast(attempt)
        while (history.size > 8) history.removeFirst()
        return attempt.id
    }

    @Synchronized fun mark(id: Long, stage: Stage, detail: String = "") {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        a.reached.putIfAbsent(stage, (clockMillis() - a.started).coerceAtLeast(0))
        if (stage == Stage.RECORD) a.outcome = "ACTIVATING"
        if (stage == Stage.CONTROL_READY && Stage.FIRST_VIDEO_FRAME !in a.reached) a.outcome = "CONTROL_READY"
        if (stage == Stage.FIRST_VIDEO_FRAME) a.outcome = "VIDEO_WRITING"
        if (stage == Stage.WAITING_INVITATION) a.outcome = "WAITING_INVITATION"
        if (detail.isNotBlank()) a.notes[stage] = clean(detail)
    }

    @Synchronized fun finish(id: Long, outcome: String, reason: String) {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        a.outcome = clean(outcome); a.reason = clean(reason); a.ended = clockMillis()
    }

    @Synchronized fun report(): List<String> = history.map { a ->
        val elapsed = ((a.ended ?: clockMillis()) - a.started).coerceAtLeast(0)
        val milestones = a.reached.entries.joinToString(",") { (stage, ms) -> "$stage@${ms}ms" }
        val notes = a.notes.entries.joinToString("; ") { (stage, note) -> "$stage:$note" }
        "ATTEMPT#${a.id} source=${a.source} profile=${a.profile} outcome=${a.outcome} elapsed=${elapsed}ms " +
            "record=${Stage.RECORD in a.reached} invite=${Stage.CTRL_INVITED in a.reached} " +
            "classic_iap2_started=${Stage.IAP2_STARTED in a.reached} iap2_parameters=${Stage.IAP2_PARAMETERS in a.reached} " +
            "receiver_display=unknown receiver_audio=unknown " +
            "last=${a.reached.keys.last()} milestones=[$milestones] notes=[$notes] " +
            (a.transport?.let { it.summary() + " " } ?: "") + "reason=${a.reason}"
    }

    private fun clean(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(300)
}
