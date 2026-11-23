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
        val observations: MutableMap<Observation, Pair<Long, String>> = mutableMapOf(),
        val ctrlReached: MutableMap<Stage, Long> = mutableMapOf(),
        var ctrlPort: String? = null,
    )
    private data class Transport(
        val engine: Long, val peer: String?, val anchorUptime: Long, val anchorEpoch: Long,
        var rtsp: Long? = null, var opened: Long? = null, var closed: Long? = null,
        var closeReason: String? = null, var tcp: String? = null, var reentry: Long? = null,
        var lastInbound: Long? = null,
    ) {
        val mediaRecent = ArrayDeque<MediaEvent>()
        val mediaKeyEvents = linkedMapOf<String, MediaEvent>()

        fun accepted(at: Long) {
            if (at <= (lastInbound ?: -1L)) return
            if (lastInbound != null || opened?.let { at >= it } == true) reentry = at
            lastInbound = at
        }
        fun clockAndEvent() = "diag_clock=uptime_ms anchor_uptime_ms=$anchorUptime anchor_unix_ms=$anchorEpoch " +
            "event_rtsp=${rtsp ?: "unknown"} event_open_ms=${opened ?: "unknown"} event_close_ms=${closed ?: "unknown"} " +
            "event_close=[${closeReason ?: "unknown"}]"

        fun summary() = clockAndEvent() +
            " video_tcp=[${tcp ?: "unknown"}] rfcomm_reentry_ms=${reentry ?: "unknown"}" + mediaTimeline()

        fun mediaTimeline() = if (mediaRecent.isEmpty()) "" else (mediaKeyEvents.values + mediaRecent)
                .distinct().sortedBy { it.at }.joinToString(" | ", prefix = " media_timeline=[", postfix = "]") {
                    "${it.at}ms ${it.kind} ${it.detail}"
                }
    }
    private data class MediaEvent(val at: Long, val kind: String, val detail: String)
    private val mediaProcess = java.util.UUID.randomUUID()
    private var serial = 0L
    private val history = ArrayDeque<Attempt>()
    private val ctrlStages = setOf(Stage.CTRL_REGISTERED, Stage.CTRL_TCP_ACCEPTED, Stage.CTRL_INVITE_HEAD,
        Stage.CTRL_INVITE_ACK, Stage.CTRL_INVITED)

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

    @Synchronized internal fun mediaEvent(id: Long, engine: Long, line: String): Boolean {
        val t = history.find { it.id == id }?.transport?.takeIf { it.engine == engine } ?: return false
        val parts = line.removePrefix("MEDIA-TIMELINE: ").split(' ', limit = 3)
        if (parts.size != 3) return false
        val at = parts[0].toLongOrNull()?.takeIf { it >= t.anchorUptime } ?: return false
        val kind = parts[1]
        val event = MediaEvent(at, kind, clean(parts[2]))
        val persist = when (kind) {
            "first_video_written", "hid_touch_in" -> {
                val first = t.mediaKeyEvents.putIfAbsent(kind, event) == null
                val firstMapped = kind == "hid_touch_in" && event.detail.endsWith("mapped=true") &&
                    t.mediaKeyEvents.putIfAbsent("first_mapped_touch", event) == null
                first || firstMapped
            }
            "feedback_timeout", "feedback_error" -> {
                t.mediaKeyEvents.putIfAbsent("first_$kind", event)
                t.mediaKeyEvents["last_$kind"] = event
                true
            }
            "feedback_queued" -> if (event.detail.endsWith("afterTimeout=true")) {
                t.mediaKeyEvents["last_feedback_retry"] = event
                true
            } else t.mediaKeyEvents.putIfAbsent("first_feedback_queued", event) == null
            "feedback_ok" -> t.mediaKeyEvents.putIfAbsent("first_feedback_ok", event) == null
            else -> return false
        }
        t.mediaRecent.addLast(event)
        if (t.mediaRecent.size > 12) t.mediaRecent.removeFirst()
        return persist
    }

    @Synchronized internal fun mediaSummaries(): Map<String, String> = buildMap {
        for (attempt in history) {
            val transport = attempt.transport?.takeIf { it.mediaRecent.isNotEmpty() } ?: continue
            val key = "$mediaProcess/${attempt.id}/${transport.engine}"
            put(key, "ATTEMPT#${attempt.id} process=$mediaProcess engine=${transport.engine} profile=${attempt.profile} " +
                "outcome=${attempt.outcome} ${transport.clockAndEvent()}${transport.mediaTimeline()} reason=${attempt.reason}")
        }
    }

    @Synchronized internal fun exportSnapshot() = report() to mediaSummaries()

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
        val elapsed = (clockMillis() - a.started).coerceAtLeast(0)
        a.reached.putIfAbsent(stage, elapsed)
        if (stage in ctrlStages) a.ctrlReached.putIfAbsent(stage, elapsed)
        if (stage == Stage.CTRL_REGISTERED) a.ctrlPort = detail.takeIf { it.isNotBlank() }?.let(::clean)
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

    internal enum class Observation(val label: String) {
        CARD_UNLOCKED("已刷卡"), ABOUT_TO_SELECT_CARPLAY("即将点旧设备 CarPlay"),
        OLD_CARPLAY_ERROR("旧设备 CarPlay 报错"), NEW_LIST_PHONE_SELECTED("在连接新设备列表点手机"),
        ACL_UP("车机蓝牙 ACL 连上"), ACL_DOWN("车机蓝牙 ACL 断开"),
        MDNS_LOCAL_ANNOUNCEMENT("本机完整 mDNS 通告（最近观察）"),
        MDNS_PEER_QUERY("对端 CarPlay ctrl mDNS 查询（最近观察）"),
    }

    /** Latest occurrence per kind; the full timeline remains in the normal session log. */
    @Synchronized internal fun observe(id: Long, kind: Observation, detail: String = "", at: Long = clockMillis()): Boolean {
        val attempt = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return false
        val offset = at - attempt.started
        if (offset >= (attempt.observations[kind]?.first ?: Long.MIN_VALUE)) {
            attempt.observations[kind] = offset to clean(detail)
        }
        return true
    }

    /** Keep the attempt-wide report, but do not mix ctrl evidence from different listeners. */
    @Synchronized internal fun newCtrlEpoch(id: Long) {
        val a = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return
        a.ctrlReached.clear()
        a.ctrlPort = null
        a.observations.remove(Observation.MDNS_LOCAL_ANNOUNCEMENT)
        a.observations.remove(Observation.MDNS_PEER_QUERY)
    }

    @Synchronized internal fun isWaitingForCtrlTcp(id: Long): Boolean {
        val current = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return false
        return Stage.CTRL_REGISTERED in current.ctrlReached && Stage.CTRL_TCP_ACCEPTED !in current.ctrlReached
    }

    @Synchronized internal fun connectionSnapshot(id: Long, listeningPort: Int?): String? {
        val a = history.lastOrNull()?.takeIf { it.id == id } ?: return null
        return buildString {
            appendLine("HyperCar 连接快照 · #${a.id} · ${if (a.ended == null) "进行中" else "最近已结束"}")
            appendLine("来源=${a.source} 档案=${a.profile} 状态=${a.outcome}；时间以本轮开始为 0 ms，负值表示本轮开始前")
            appendLine("当前 ctrl 端口：${listeningPort ?: "未监听"}；最近一段 ctrl 监听的注册端口：${a.ctrlPort ?: "未记录"}")
            for (kind in Observation.entries) {
                val event = a.observations[kind]
                val detail = event?.second.orEmpty()
                appendLine("${kind.label}：${event?.let { "${if (it.first >= 0) "+" else ""}${it.first} ms" } ?: "未记录"}${if (detail.isBlank()) "" else "（$detail）"}")
            }
            fun stage(label: String, stage: Stage) {
                val at = if (stage in ctrlStages) a.ctrlReached[stage] else a.reached[stage]
                appendLine("$label：${at?.let { "+$it ms" } ?: "未记录"}")
            }
            stage("绑定车机 Wi-Fi", Stage.WIFI_BOUND)
            stage("蓝牙 …cafe 入站交给 iAP2", Stage.BT_INBOUND_ADMITTED)
            stage("App 接收 ctrl TCP", Stage.CTRL_TCP_ACCEPTED)
            stage("读取 HTTP 邀请请求头", Stage.CTRL_INVITE_HEAD)
            stage("收到有效 HTTP 邀请", Stage.CTRL_INVITED)
            append("未记录不代表未发生；App 未接收 TCP 不代表车机没发 SYN。")
        }
    }

    private fun clean(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(300)
}
