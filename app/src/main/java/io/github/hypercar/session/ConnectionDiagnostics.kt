package io.github.hypercar.session

import io.github.hypercar.iap2.RfcommStamp

/** Bounded evidence journal. Tokens reject late events; a missing milestone is never inferred as success. */
class ConnectionDiagnostics(private val clockMillis: () -> Long) {
    internal data class AclWake(val peer: String, val observedAt: Long)

    private class AclEdges {
        var up: Long? = null
        var down: Long? = null
        var profileDrop = false
            private set
        val disconnected: Boolean get() = down?.let { it >= (up ?: Long.MIN_VALUE) } == true

        fun record(connected: Boolean, at: Long, profileDrop: Boolean = false) {
            if (connected) up = maxOf(up ?: Long.MIN_VALUE, at)
            else if (at >= (down ?: Long.MIN_VALUE)) {
                down = at
                this.profileDrop = profileDrop
            }
        }
    }

    private val idleAcl = linkedMapOf<String, AclEdges>()

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
        val acl: LinkedHashMap<String, AclEdges> = linkedMapOf(),
        var admittedAclWake: AclWake? = null,
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
        val videoSavedAt = mutableMapOf<String, Long>()
        private val keyframeRequests = linkedMapOf<KeyframeRequest, MutableMap<String, MediaEvent>>()

        fun recordKeyframe(key: KeyframeRequest, event: MediaEvent): Boolean {
            val group = keyframeRequests.getOrPut(key) { mutableMapOf() }
            if (group.putIfAbsent(event.kind, event) != null) return false
            if (keyframeRequests.size > MAX_KEYFRAME_REQUESTS) {
                val oldest = keyframeRequests.minBy { (_, events) -> events.values.minOf { it.at } }.key
                keyframeRequests.remove(oldest)
                if (oldest == key) return false
            }
            return true
        }

        fun hasMediaEvents() = mediaRecent.isNotEmpty() || keyframeRequests.isNotEmpty()

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

        fun mediaTimeline() = if (!hasMediaEvents()) "" else
            (mediaKeyEvents.values + mediaRecent + keyframeRequests.values.flatMap { it.values })
                .distinct().sortedBy { it.at }.joinToString(" | ", prefix = " media_timeline=[", postfix = "]") {
                    "${it.at}ms ${it.kind} ${it.detail}"
                }
    }
    private data class MediaEvent(val at: Long, val kind: String, val detail: String)
    private data class KeyframeRequest(val connection: ULong, val sequence: ULong)
    private val keyframeIdentity = Regex("""\bconn=(\d+) request=(\d+)\b""")
    private val keyframeEvents = setOf("keyframe_request", "keyframe_source_sync", "keyframe_sender_sync",
        "keyframe_tcp_write", "keyframe_end")

    private companion object {
        const val MAX_KEYFRAME_REQUESTS = 4
    }
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
        if (kind in keyframeEvents) {
            keyframeIdentity.find(event.detail)?.destructured?.let { (conn, seq) ->
                val connection = conn.toULongOrNull() ?: return false
                val request = seq.toULongOrNull() ?: return false
                return t.recordKeyframe(KeyframeRequest(connection, request), event)
            }
        }
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
            "feedback_queued" -> if (event.detail.endsWith("afterTimeout=true") || " afterRejection=true " in event.detail) {
                t.mediaKeyEvents["last_feedback_retry"] = event
                true
            } else t.mediaKeyEvents.putIfAbsent("first_feedback_queued", event) == null
            "feedback_ok" -> t.mediaKeyEvents.putIfAbsent("first_feedback_ok", event) == null
            "screen_keepalive_write", "keyframe_request", "video_write_slow", "video_write_end" -> {
                if ((t.mediaKeyEvents[kind]?.at ?: -1L) > at) return false
                t.mediaKeyEvents[kind] = event
                val saved = t.videoSavedAt[kind]
                (saved == null || at - saved >= 10_000).also { if (it) t.videoSavedAt[kind] = at }
            }
            else -> return false
        }
        t.mediaRecent.addLast(event)
        if (t.mediaRecent.size > 12) t.mediaRecent.removeFirst()
        return persist
    }

    @Synchronized internal fun mediaSummaries(): Map<String, String> = buildMap {
        for (attempt in history) {
            val transport = attempt.transport?.takeIf { it.hasMediaEvents() } ?: continue
            val key = "$mediaProcess/${attempt.id}/${transport.engine}"
            put(key, "ATTEMPT#${attempt.id} process=$mediaProcess engine=${transport.engine} profile=${attempt.profile} " +
                "outcome=${attempt.outcome} ${transport.clockAndEvent()}${transport.mediaTimeline()} reason=${attempt.reason}")
        }
    }

    @Synchronized internal fun exportSnapshot() = report() to mediaSummaries()

    @Synchronized internal fun captureRfcommStamp(uptime: Long, publishedAttempt: Long): RfcommStamp {
        val attempt = history.lastOrNull()
            ?.takeIf { it.id == publishedAttempt && it.ended == null && uptime >= it.startedUptime }
        return RfcommStamp(attempt?.id ?: 0L, uptime)
    }

    fun rfcommAccepted(id: Long, peer: String?, at: Long) =
        rfcommAdmitted(peer, RfcommStamp(id, at), RfcommStamp(id, at))

    @Synchronized internal fun rfcommAdmitted(peer: String?, accepted: RfcommStamp, admitted: RfcommStamp) {
        val a = history.lastOrNull()?.takeIf {
            it.id == admitted.attempt && it.ended == null && admitted.uptime >= it.startedUptime
        } ?: return
        if (peer == null) return
        // Standby may hand a socket across a round. Only the admission belongs to the new round.
        a.reached.putIfAbsent(Stage.BT_INBOUND_ADMITTED, (clockMillis() - a.started).coerceAtLeast(0))
        if (accepted.attempt != admitted.attempt || accepted.uptime < a.startedUptime || accepted.uptime > admitted.uptime) return
        val at = accepted.uptime
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

    @Synchronized internal fun reserveAttemptId(): Long = ++serial

    @Synchronized internal fun begin(
        source: String, profile: String, startedUptime: Long = clockMillis(), aclWake: AclWake? = null,
    ): Long {
        val now = clockMillis()
        history.lastOrNull()?.takeIf { it.ended == null }?.let {
            it.outcome = "SUPERSEDED"; it.ended = now
        }
        val attempt = Attempt(reserveAttemptId(), clean(source), clean(profile), now, startedUptime)
        // Only the original idle edge that actually opens this round may predate it.
        if (aclWake != null) {
            val peer = aclWake.peer.uppercase()
            val pending = idleAcl[peer]
            if (pending != null && pending.up == aclWake.observedAt && !pending.disconnected) {
                attempt.acl[peer] = AclEdges().also { it.record(true, aclWake.observedAt) }
                attempt.admittedAclWake = AclWake(peer, aclWake.observedAt)
            }
        }
        idleAcl.clear()
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

    /** Called for the original broadcast, with the round bound under the same lock as begin/finish. */
    @Synchronized internal fun recordAcl(peer: String, connected: Boolean, observedAt: Long, profileDrop: Boolean = false): Long? {
        val latest = history.lastOrNull()
        val active = latest?.takeIf { it.ended == null }
        val address = peer.uppercase()
        if (latest != null && observedAt < (latest.ended ?: latest.started)) {
            // The inherited connect can predate this round. Its later disconnect still invalidates it.
            val wake = active?.admittedAclWake
            if (!connected && wake?.peer == address && observedAt >= wake.observedAt) {
                active.acl[address]?.record(false, observedAt, profileDrop)
            }
            return null
        }
        val peers = active?.acl ?: idleAcl
        val edges = peers.getOrPut(address) { AclEdges() }
        if (peers.size > 16) peers.remove(peers.keys.first())
        edges.record(connected, observedAt, profileDrop)
        return active?.id
    }

    private fun Attempt.aclFor(peer: String?): AclEdges? =
        if (peer.isNullOrBlank()) acl.values.singleOrNull() else acl[peer.uppercase()]

    @Synchronized internal fun currentAclWakePeer(id: Long): String? {
        val attempt = history.lastOrNull()?.takeIf { it.id == id && it.ended == null } ?: return null
        val wake = attempt.admittedAclWake ?: return null
        val connected = attempt.acl.entries.singleOrNull { it.value.up != null && !it.value.disconnected } ?: return null
        return wake.peer.takeIf { connected.key == it && connected.value.up == wake.observedAt }
    }

    @Synchronized internal fun aclObservation(id: Long, peer: String? = null): String {
        val attempt = history.lastOrNull()?.takeIf { it.id == id && it.ended == null }
        val target = peer ?: attempt?.transport?.peer
        val edges = attempt?.aclFor(target)
        return when {
            edges?.disconnected == true && edges.profileDrop -> "本轮最近观察到车机 ACL 断开（应用主动降级）"
            edges?.disconnected == true -> "本轮最近观察到车机 ACL 断开"
            edges?.up != null -> "本轮最近观察到车机 ACL 连入"
            target.isNullOrBlank() && (attempt?.acl?.size ?: 0) > 1 -> "本轮观察到多台车机的 ACL，当前对端尚未确认"
            else -> "本轮尚未观察到车机 ACL 连入"
        }
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

    @Synchronized internal fun connectionSnapshot(id: Long, listeningPort: Int?, aclPeer: String? = null): String? {
        val a = history.lastOrNull()?.takeIf { it.id == id } ?: return null
        val target = aclPeer ?: a.transport?.peer
        val acl = a.aclFor(target)
        fun formatTime(at: Long?) = at?.let { "${if (it >= 0) "+" else ""}$it ms" } ?: "未记录"
        return buildString {
            appendLine("HyperCar 连接快照 · #${a.id} · ${if (a.ended == null) "进行中" else "最近已结束"}")
            appendLine("来源=${a.source} 档案=${a.profile} 状态=${a.outcome}；时间以本轮开始为 0 ms，负值表示本轮开始前")
            appendLine("当前 ctrl 端口：${listeningPort ?: "未监听"}；最近一段 ctrl 监听的注册端口：${a.ctrlPort ?: "未记录"}")
            for (kind in Observation.entries) {
                val event = a.observations[kind]
                val detail = event?.second.orEmpty()
                appendLine("${kind.label}：${formatTime(event?.first)}${if (detail.isBlank()) "" else "（$detail）"}")
            }
            if (target.isNullOrBlank() && a.acl.size > 1) {
                appendLine("车机蓝牙 ACL：本轮观察到多台车机，当前对端尚未确认")
            } else {
                appendLine("车机蓝牙 ACL 连上：${formatTime(acl?.up?.minus(a.started))}")
                appendLine("车机蓝牙 ACL 断开：${formatTime(acl?.down?.minus(a.started))}" +
                    if (acl?.profileDrop == true) "（应用主动降级）" else "")
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
