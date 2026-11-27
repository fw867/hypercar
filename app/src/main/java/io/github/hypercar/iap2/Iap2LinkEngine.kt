package io.github.hypercar.iap2

import java.io.ByteArrayOutputStream

/** 手机端回显 SYN|ACK 时声明的发送窗口（真机 iPhone / AADisplay 同值 0x7F）。 */
private const val PHONE_DECLARED_MAX_OUTGOING = 0x7F

/**
 * iAP2 链路引擎（发送端/iPhone 角色；链路层协议对称）。
 *
 * 握手（无线模式，手机为同步发起方——DiPlay/xcertplay 的 openWireless 以
 * initiateNegotiation=false 等待手机先发同步包）：
 *  1. start() 发送 marker `FF 55 02 00 EE 10`，随即发送同步包（FF5A 帧，
 *     control=0x80，载荷 = SynchronizationPayload）
 *  2. 对端收到 marker 后进入协商并发送自己的同步包；收到纯 SYN 即回 SYN|ACK
 *  3. 协商状态下收到 ACK / 数据帧即进入 NORMAL（控制会话可用）
 *
 * 数据帧：control=0x40（捎带 ACK），seq=发送序号（u8 回绕，初始 99），
 * ack=已按序收到的最新序号。NORMAL 态再次收到检测标记时，在同一传输上重建链路协商。
 *
 * **BMW §9h 修复（2026-10-19 BMW 87866 实车定谳，必须保留）**：
 *  - 每条**纯 SYN**（SYN 位=1 且 ACK 位=0）都回一帧 SYN|ACK——BMW 每轮重试都用
 *    新序号重开协商，旧「只回第一条」实现让它 14 轮全部卡死（133 条 SYN / 0 条 ACK）；
 *  - SYN|ACK **消耗一个新发送序号**（对端按 `SYN.seq + 1` 校验回包，同号被判重复丢弃）；
 *  - 同一 SYN 的字节级重发保持幂等：原样重放上一帧、不推进序号
 *    （DiPlay 隧道模式每 500ms 重发同一 SYN，靠这条维持原行为）；
 *  - **纯 SYN 判据必须是 `control and ACK == 0`**：对所有带 SYN 位的帧都回 SYN|ACK
 *    会与对端形成无限乒乓（两个引擎互喂时挂死并空转 CPU）；
 *  - 回显用本方声明 maxOutgoing=0x7F（原样回显会把发送窗口限死在对端的小窗口里）。
 *
 * 简化项（RFCOMM 为可靠有序流）：不做乱序缓冲与主动 EAK，保留重复帧检测、
 * 发送窗口与重传计时；另加 NORMAL 态裸 ACK 保活（§9j：BMW 声明 ackTimeout=3s，
 * 等待窗口内 RFCOMM 完全静默 5s 会触发空闲拆链——按对端声明值发裸 ACK 即可）。
 */
class Iap2LinkEngine(
    private val maxOutgoing: Int = 4,
    retransmissionTimeoutMillis: Long = 4_000L,
    maxRetransmissions: Int = 4,
    private val reliableTransport: Boolean = false,
    private val declaredWindow: Int = PHONE_DECLARED_MAX_OUTGOING,
    private val socketWriteFeedback: Boolean = false,
) {
    enum class State { IDLE, NEGOTIATING, NORMAL, DEAD }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    )

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < 10 || u8(bytes[0]) != SYNCHRONIZATION_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var offset = 10
                while (offset + 3 <= bytes.size) {
                    sessions +=
                        SessionDescriptor(
                            u8(bytes[offset]),
                            u8(bytes[offset + 1]),
                            u8(bytes[offset + 2]),
                        )
                    offset += 3
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class Event {
        class FileTransfer(val bytes: ByteArray) : Event()
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private enum class MfiControl { OTHER, RESULT }

    private class BulkMessage(
        val canStart: () -> Boolean,
        val commitStart: (() -> Boolean)?,
        val onDiscarded: (() -> Unit)?,
    ) {
        var started = false
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long? = null,
        val awaitAck: Boolean = true,
        val sessionId: Int? = null,
        val optional: Boolean = false,
        val mfi: MfiControl? = null,
        val hudMessageId: Int = 0,
        var hudSerial: Long = 0,
        val onSequenceAssigned: ((Int) -> Unit)? = null,
        val bulk: BulkMessage? = null,
        val onWritten: (() -> Unit)? = null,
    ) {
        var writeTicket = 0L
        var retryDueAt: Long? = null
    }

    private class PendingStart(val packet: Packet) {
        var acknowledged = false
        var cancelRequested = false
        var revoked = false
    }
    internal data class SocketWrite(val offset: Int, val ticket: Long, val cancellable: Boolean)
    internal var withdrawSocketWrite: ((Long) -> Unit)? = null
    private var nextWriteTicket = 0L
    private var pendingStart: PendingStart? = null
    private val socketWrites = mutableListOf<SocketWrite>()
    private val writeCallbacks = mutableMapOf<Long, () -> Unit>()

    internal fun takeSocketWrites(): List<SocketWrite> = socketWrites.toList().also { socketWrites.clear() }

    internal fun socketWriteFinished(ticket: Long, writtenAt: Long?) {
        if (state == State.DEAD) return
        val onWritten = writeCallbacks.remove(ticket)
        val start = pendingStart
        if (start != null && start.packet.writeTicket == ticket) {
            pendingStart = null
            val packet = start.packet
            val group = checkNotNull(packet.bulk)
            if (writtenAt == null) {
                val tail = sendBacklog.filter { it.bulk === group }
                sendBacklog.removeAll { it.bulk === group }
                if (!start.revoked && group.canStart()) {
                    val index = sendBacklog.indexOfFirst { it.bulk?.started == false }
                    sendBacklog.addAll(if (index < 0) sendBacklog.size else index, listOf(packet) + tail)
                } else group.onDiscarded?.invoke()
                if (!zeroRetryAndAckMode) pendingReliableAck = true
                return
            }
            // No other data sequence was assigned while this first fragment was cancellable.
            sentSequence = packet.sequence
            group.started = true
            group.commitStart?.invoke()
            trackHudAcknowledgement(packet)
            if (packet.awaitAck && agreedRetryCount > 0 && retryTimeoutMillis > 0) {
                unacknowledged += packet
                packet.deadlineMillis = writtenAt + retryTimeoutMillis
                updateRetransmitDeadline()
            }
            if (start.acknowledged) handleAcknowledgement(packet.sequence, writtenAt)
            onWritten?.invoke()
            return
        }
        unacknowledged.firstOrNull { it.writeTicket == ticket }?.let {
            val retryDue = it.retryDueAt
            it.retryDueAt = null
            if (retryDue != null && writtenAt != null) it.retransmissions++
            it.deadlineMillis = if (writtenAt != null) writtenAt + retryTimeoutMillis else retryDue
            updateRetransmitDeadline()
        }
        if (writtenAt != null) onWritten?.invoke()
    }

    internal data class HudFrame(val offset: Int, val messageId: Int, val sequence: Int, val serial: Long) {
        val label get() = "id=0x${messageId.toString(16)} seq=$sequence hud_serial=$serial"
    }

    private val hudOutput = mutableListOf<HudFrame>()
    private val hudAwaitingAck = linkedMapOf<Int, HudFrame>()
    private var nextHudSerial = 0L
    private var hudAckWatermark: Int? = null
    internal var hudLog: ((String) -> Unit)? = null
    internal var onPeerAcknowledgement: ((ack: Int, lastSent: Int) -> Unit)? = null

    internal fun takeHudOutput(): List<HudFrame> = hudOutput.toList().also { hudOutput.clear() }

    internal fun endHudTrace(reason: String) {
        hudAwaitingAck.values.forEach { hudLog?.invoke("HUD LINK ${it.label} ack=unconfirmed reason=$reason") }
        hudAwaitingAck.clear()
        hudOutput.clear()
        hudAckWatermark = null
    }

    var state: State = State.IDLE
        private set

    /** 对端同步包（最近一次收到的）。 */
    var peerSynchronization: SynchronizationPayload? = null
        private set

    private var sentSequence = INITIAL_SEQUENCE
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0

    /**
     * 采纳的对端控制会话 ID（收到对端 sync 载荷时确定）。
     * Audi 真车字节级实测（cardeck 2026-10-09）：车机（MIB3）以发起方姿态发 SYN，声明
     * 会话表 {1:control, 2:EA}，手机回显其表后它以 **1 号会话**通信——
     * CSM 发在 10 号会被静默丢弃（链路层照常 ACK，极具迷惑性）。
     * BMW/DiPlay 声明 10，采纳后与默认一致，行为不变。
     */
    private var adoptedPeerControlSessionId: Int? = null

    /** 采纳的对端 sync 载荷（首次采纳时定格）。 */
    private var adoptedSynchronization: SynchronizationPayload? = null

    /**
     * 本方声明的接收窗口：限制对端向本端的未确认发送量。
     * Wi-Fi 出站按对端声明窗口计数，与 CatPlay 相同；蓝牙保留历史兼容行为，
     * 暂不改变已实车跑通的突发发送策略。
     */
    private var declaredMaxOutgoing = maxOutgoing

    /** Wi-Fi flow control follows the receiver's offer, independently of retransmission policy. */
    val peerReceiveWindow: Int? get() = peerSynchronization?.maxOutgoing
    internal val controlSessionVersion: Int?
        get() = peerSynchronization?.sessions?.firstOrNull {
            it.kind == CONTROL_KIND && it.id != 0 && it.id == controlSessionId()
        }?.version
    val queuedControlPackets: Int get() = sendBacklog.size
    internal val hasBulkControl: Boolean get() = pendingStart != null || sendBacklog.any { it.bulk != null }
    internal val hasNonBulkControl: Boolean get() = sendBacklog.any { it.bulk == null && !it.optional }
    val outstandingPackets: Int get() = lastSentAcknowledged?.let { sequenceDistance(sentSequence,it) } ?: 0

    /** 诊断：首次采纳对端会话表时回调（打印对端提议的会话表，便于真车排查）。 */
    var onPeerSynchronizationAdopted: ((SynchronizationPayload) -> Unit)? = null

    /** 诊断：丢弃未知会话 ID 的数据帧时回调（(sessionId, payload)）。 */
    var onUnknownSessionData: ((Int, ByteArray) -> Unit)? = null

    /** 诊断：每个入向链路帧的摘要，真车排查用。 */
    var onFrameReceived: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /** 诊断：每个出向链路帧的摘要（仅链路层帧，用于对照真车协商时序）。 */
    var onFrameSent: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    private var unknownSessionDropped = 0

    private val unacknowledged = ArrayList<Packet>()
    private val sendBacklog = ArrayDeque<Packet>()
    private val pendingEvents = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private var outputPacketCount = 0
    private var flushOutputLimit: Int? = null
    private var newBulkOutputLimit = 0
    internal data class MfiResultFrame(val sequence: Int, val retry: Int, val fragmentBytes: Int) {
        val label get() = "id=0xaa05 seq=$sequence retry=$retry fragment_bytes=$fragmentBytes"
    }
    internal var mfiLog: ((String) -> Unit)? = null
    private val mfiResults = mutableListOf<MfiResultFrame>()
    internal var outputContainsMfi = false
        private set
    internal fun pendingMfiResults(): List<MfiResultFrame> = mfiResults.toList()

    internal fun reportDiscardedMfi(reason: String) {
        val log = mfiLog ?: return
        sendBacklog.forEach {
            if (it.mfi == MfiControl.RESULT) log("MFi RFCOMM discarded id=0xaa05 seq=unassigned fragment_bytes=${it.payload.size} reason=$reason")
        }
        mfiResults.forEach { log("MFi RFCOMM discarded ${it.label} reason=$reason") }
    }
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null
    private val localRetryTimeoutMillis = retransmissionTimeoutMillis.coerceIn(0L, 0xFFFFL)
    private val localRetryCount = maxRetransmissions.coerceIn(0, 0xFF)
    // Passive links echo the peer's proposal. Active links have already advertised local limits.
    private val agreedRetryTimeoutMillis: Long
        get() = peerSynchronization?.retransmissionTimeoutMillis?.toLong()?.let {
            if (passiveNegotiation) it else minOf(it, localRetryTimeoutMillis)
        } ?: localRetryTimeoutMillis
    private val agreedRetryCount: Int
        get() = peerSynchronization?.maxRetransmissions?.let {
            if (passiveNegotiation) it else minOf(it, localRetryCount)
        } ?: localRetryCount
    // Preserve the local timeout fallback for peers that specify retries with a zero interval.
    private val retryTimeoutMillis: Long
        get() = agreedRetryTimeoutMillis.takeIf { it > 0 } ?: localRetryTimeoutMillis

    /** 上一次回显 SYN 时对端的 SYN 序号与我方序号（重传同一 SYN 时原样重放）。 */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0
    private var lastEchoTicket = 0L
    private var inboundWrittenSynAck = 0L

    /** 最近一次出向帧时刻（NORMAL 态裸 ACK 保活的基准）。 */
    private var lastOutputMillis: Long = 0
    private var passiveNegotiation = false
    private var lastMarkerEchoMillis: Long? = null
    internal var deferInitialDetectEcho = false
    private var previouslyNegotiated = false
    internal val hasPartialInput: Boolean get() =
        receive.size > 1 || (receive.size == 1 && receive[0] == 0xff.toByte())

    /** Same-transport detection restart: consumers must discard partial CSMs from the old link. */
    internal var onDetectionRetiring: (() -> Unit)? = null
    var onDetectionRestart: (() -> Unit)? = null
    // Wi-Fi drains CSM responses before flushing a required standalone ACK.
    private var pendingReliableAck = false
    internal val zeroRetryAndAckMode: Boolean
        get() {
            val peer = peerSynchronization ?: return false
            return reliableTransport && state == State.NORMAL &&
                peer.retransmissionTimeoutMillis == 0 && peer.acknowledgementTimeoutMillis == 0 &&
                peer.maxRetransmissions == 0 && peer.maxAcknowledgements == 0
        }

    val isDead: Boolean get() = state == State.DEAD

    /** 进入协商：发 marker + 同步包（发送端=发起方）。 */
    fun start(nowMillis: Long, passive: Boolean = false) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        if (passive) {
            passiveNegotiation = true
            lastOutputMillis = nowMillis
            return // 对端先发探测和 SYN。收到 FF 55 再回，不主动发同步包。
        }
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
        lastOutputMillis = nowMillis
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
        writtenSynAck: Long = 0L,
    ) {
        if (state == State.DEAD) return
        inboundWrittenSynAck = writtenSynAck
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                die("inbound frame exceeds limit")
                return
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // 排空所有完整帧
            }
        }
    }

    fun feedEof() = die(null)

    fun takeOutput(): ByteArray {
        if (pendingReliableAck && pendingStart == null) writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
        val bytes = output.toByteArray()
        resetOutput()
        return bytes
    }

    private fun resetOutput() {
        output.reset()
        outputPacketCount = 0
        outputContainsMfi = false
        mfiResults.clear()
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? {
        val keepAlive = keepAliveDeadlineMillis()
        return listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline, keepAlive).minOrNull()
    }

    internal fun retryDeadlineReached(nowMillis: Long) = retransmitDeadline?.let { it <= nowMillis } == true

    fun advanceTime(nowMillis: Long, allowRetryExpiry: Boolean = true) {
        if (state == State.DEAD) return
        if (markerDeadline != null && markerDeadline!! <= nowMillis) {
            markerDeadline = null
            if (state == State.NEGOTIATING && appendOutput(MARKER)) {
                markerDeadline = nowMillis + MARKER_RESEND_MILLIS
            }
        }
        if (syncDeadline != null && syncDeadline!! <= nowMillis) {
            syncDeadline = null
            if (state == State.NEGOTIATING) {
                // 已采纳对端表：只补裸 ACK（回显仅首次发一次，避免协商轮次膨胀）；
                // 未采纳：继续重发本表 SYN
                if (adoptedSynchronization != null) {
                    sendAcknowledgement()
                } else {
                    sendSynchronization()
                }
                syncDeadline = nowMillis + SYNC_RESEND_MILLIS
            }
        }
        if (allowRetryExpiry && retryDeadlineReached(nowMillis)) {
            retransmitDeadline = null
            if (state == State.NORMAL) retransmitDue(nowMillis)
        }
        // NORMAL 态裸 ACK 保活（§9j 未定项②落地）：等待窗口内 RFCOMM 静默会触发
        // BMW 空闲拆链；按对端声明的 ackTimeout 发裸 ACK（无对端值时 1s 兜底）。
        if (state == State.NORMAL) {
            val keepAlive = keepAliveDeadlineMillis()
            if (keepAlive != null && keepAlive <= nowMillis) {
                sendAcknowledgement()
            }
        }
    }

    /**
     * 排入一个完整的控制会话载荷。窗口满时进入积压队列，由 ACK 或 pump 驱动重试。
     * 返回 false 仅代表链路已死。
     *
     * [awaitAck]=false：发后不管——不计入未确认清单、不重传，**链路不会因它未被 ACK
     * 而死亡**。用于「车机接受但从不 ACK」的消息（Audi 对 0x4E0E 实测如此，重传
     * 看门狗曾据此在 +20s 杀掉整条蓝牙链路）。
     */
    fun sendControl(
        bytes: ByteArray,
        nowMillis: Long,
        awaitAck: Boolean = true,
        hudMessageId: Int = 0,
        onSequenceAssigned: ((Int) -> Unit)? = null,
    ): Boolean = enqueueControl(bytes, nowMillis, awaitAck, hudMessageId, onSequenceAssigned, mfi = null)

    internal fun sendMfiControl(bytes: ByteArray, nowMillis: Long, awaitAck: Boolean, result: Boolean = false): Boolean =
        enqueueControl(bytes, nowMillis, awaitAck, 0, null, if (result) MfiControl.RESULT else MfiControl.OTHER)

    private fun enqueueControl(
        bytes: ByteArray, nowMillis: Long, awaitAck: Boolean, hudMessageId: Int,
        onSequenceAssigned: ((Int) -> Unit)?, mfi: MfiControl?,
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        val packet = Packet(0, bytes.copyOf(), awaitAck = awaitAck, hudMessageId = hudMessageId, mfi = mfi,
            onSequenceAssigned = onSequenceAssigned)
        // Once a CSM starts, keep its remaining fragments contiguous in the control session.
        val waitingGroup = pendingStart?.packet?.bulk
        val bulkStart = sendBacklog.indexOfFirst { it.bulk?.started == false && it.bulk !== waitingGroup }
        if (bulkStart < 0) sendBacklog.addLast(packet) else sendBacklog.add(bulkStart, packet)
        // The first optional sequence is provisional until the transport reports a write.
        pendingStart?.let { start ->
            if (!start.cancelRequested) {
                start.cancelRequested = true
                withdrawSocketWrite?.invoke(start.packet.writeTicket)
            }
        }
        if (mfi == MfiControl.RESULT) mfiLog?.invoke("MFi RFCOMM enqueued id=0xaa05 fragment_bytes=${bytes.size}")
        drainSendWindow(nowMillis)
        return true
    }

    /** Optional complete CSM. Only the application worker may start it, after handling inbound messages. */
    internal fun queueBulkControl(
        bytes: ByteArray, awaitAck: Boolean, hudMessageId: Int, canStart: () -> Boolean,
        commitStart: (() -> Boolean)? = null,
        onWritten: (() -> Unit)? = null,
        onDiscarded: (() -> Unit)? = null,
    ): Boolean {
        val limit = (peerSynchronization?.maxLength ?: 0xffff) - HEADER_BYTES - 1
        if (state != State.NORMAL || limit <= 0) return false
        val group = BulkMessage(canStart, commitStart, onDiscarded)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + limit)
            sendBacklog.addLast(Packet(0, bytes.copyOfRange(offset, end), awaitAck = awaitAck,
                hudMessageId = hudMessageId, bulk = group, onWritten = onWritten.takeIf { end == bytes.size }))
            offset = end
        }
        return true
    }

    internal fun discardUnstartedBulkControl() {
        val start = pendingStart
        val waitingGroup = start?.packet?.bulk
        val invalid = sendBacklog.mapNotNull { it.bulk }.toSet()
            .filterTo(mutableSetOf()) { it !== waitingGroup && !it.started && !it.canStart() }
        sendBacklog.removeAll { it.bulk in invalid }
        invalid.forEach { it.onDiscarded?.invoke() }
        if (start != null && waitingGroup?.canStart() == false) {
            start.revoked = true
            if (!start.cancelRequested) {
                start.cancelRequested = true
                withdrawSocketWrite?.invoke(start.packet.writeTicket)
            }
        }
    }

    internal fun flushBulkControl(
        nowMillis: Long, capacity: Int, allowBulkStart: Boolean = true, reserveForNewBulk: Int = 0,
    ) {
        flushOutputLimit = minOf(capacity.coerceAtLeast(0), outputPacketCount + 8)
        newBulkOutputLimit = (capacity - reserveForNewBulk).coerceAtLeast(0)
        try {
            drainSendWindow(nowMillis, allowBulkStart)
        } finally {
            flushOutputLimit = null
        }
    }

    /** Only a file-transfer session actually negotiated by this peer may carry artwork. */
    val fileTransferSession: Int? get() = peerSynchronization?.sessions
        ?.firstOrNull { it.kind == 1 && it.version == 2 && it.id != controlSessionId() && it.id in 1..255 }?.id

    fun sendFileTransfer(bytes: ByteArray, nowMillis: Long): Boolean {
        val session = fileTransferSession ?: return false
        val limit = (peerSynchronization?.maxLength ?: 0) - HEADER_BYTES - 1
        if (state != State.NORMAL || bytes.size !in 2..limit || sendBacklog.size >= 8) return false
        sendBacklog += Packet(0, bytes.copyOf(), sessionId = session, optional = true)
        drainSendWindow(nowMillis)
        return true
    }

    // ---- 接收解析 ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // Detect is valid between frames, never inside a length-delimited frame payload.
        // Keep a partial marker until the next read; malformed prefixes still resynchronize.
        while (receive.size >= 2 &&
            !(receive[0] == 0xFF.toByte() && receive[1] == 0x5A.toByte())
        ) {
            if ((state == State.NORMAL || (passiveNegotiation && state == State.NEGOTIATING)) &&
                receive[0] == 0xFF.toByte() && receive[1] == 0x55.toByte()) {
                if (receive.size < MARKER.size) return false
                if (receive.peek(MARKER.size).contentEquals(MARKER)) {
                    receive.discard(MARKER.size)
                    if (deferInitialDetectEcho && !previouslyNegotiated) return true
                    if (state == State.NORMAL || (reliableTransport && lastEchoPeerSequence != null)) {
                        restartDetection(nowMillis)
                    }
                    // A reflected echo must not create a tight DETECT ping-pong. Real peer
                    // retries (typically one second) still get a reply if the first is lost.
                    if (lastMarkerEchoMillis?.let { nowMillis - it >= 250L } != false) {
                        appendOutput(MARKER)
                        lastMarkerEchoMillis = nowMillis
                    }
                    return true
                }
            }
            receive.discard(1)
        }
        if (receive.size < HEADER_BYTES) return false
        val header = receive.peek(HEADER_BYTES)
        if (!checksumValid(header)) {
            receive.discard(1)
            return true
        }
        val length = readU16(header, 2)
        if (length < HEADER_BYTES) {
            receive.discard(1)
            return true
        }
        if (receive.size < length) return false
        val frame = receive.take(length)
        val payloadWithChecksum = frame.copyOfRange(HEADER_BYTES, length)
        if (payloadWithChecksum.isNotEmpty() && !checksumValid(payloadWithChecksum)) {
            receive.discard(1)
            return true
        }
        val payload =
            if (payloadWithChecksum.isEmpty()) {
                null
            } else {
                payloadWithChecksum.copyOf(payloadWithChecksum.size - 1)
            }
        processFrame(
            control = u8(frame[4]),
            sequence = u8(frame[5]),
            ack = u8(frame[6]),
            sessionId = u8(frame[7]),
            payload = payload,
            nowMillis = nowMillis,
        )
        return true
    }

    private fun restartDetection(nowMillis: Long) {
        onDetectionRetiring?.invoke()
        reportDiscardedMfi("link_reset")
        state = State.NEGOTIATING
        passiveNegotiation = true
        peerSynchronization = null
        adoptedSynchronization = null
        adoptedPeerControlSessionId = null
        // The refreshed Wi-Fi link must not reuse the preceding SYN|ACK sequence.
        sentSequence = if (reliableTransport) pendingStart?.packet?.sequence ?: sentSequence else INITIAL_SEQUENCE
        endHudTrace("link_reset")
        lastSentAcknowledged = null
        lastReceivedInOrder = 0
        lastEchoPeerSequence = null
        lastEchoOwnSequence = 0
        lastEchoTicket = 0
        declaredMaxOutgoing = maxOutgoing
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        pendingStart = null
        socketWrites.clear()
        writeCallbacks.clear()
        pendingReliableAck = false
        lastMarkerEchoMillis = null
        lastOutputMillis = nowMillis
        unacknowledged.clear()
        sendBacklog.clear()
        pendingEvents.clear()
        resetOutput()
        // Keep unread bytes: the new SYN can share this read with DETECT.
        onDetectionRestart?.invoke()
    }

    private fun processFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray?,
        nowMillis: Long,
    ) {
        onFrameReceived?.invoke(control, sequence, ack, sessionId, payload?.size ?: 0)
        if (control and CONTROL_RESET != 0) {
            die("peer sent reset")
            return
        }
        if (control and CONTROL_SYNCHRONIZE != 0) {
            val sync = SynchronizationPayload.decode(payload ?: ByteArray(0))
            // **只有纯 SYN（不带 ACK 位）才回 SYN|ACK**：SYN|ACK 说明对端在回应我们的
            // 发起方 SYN，属于「它已应答」的信号，再回一条就会形成双方互回 SYN|ACK
            // 的无限乒乓（单测暴露：两个引擎互相喂帧时协商永不收敛、CPU 空转）。
            val isPureSyn = (control and CONTROL_ACKNOWLEDGEMENT) == 0
            if (sync != null && isPureSyn && state == State.NEGOTIATING) {
                val firstAdoption = adoptedSynchronization == null
                peerSynchronization = sync
                if (firstAdoption) adoptedSynchronization = sync
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                lastReceivedInOrder = sequence
                // **每次**纯 SYN 都只回一帧 SYN|ACK（不回裸 ACK）：SYN|ACK 自带确认位与
                // ack 字段，裸 ACK 本就冗余；且裸 ACK 用当前 sentSequence、SYN|ACK 会自增，
                // 交错发送会让下一条裸 ACK 复用 SYN|ACK 的序号——BMW 把同序号帧当重复丢弃。
                sendSynchronizationEcho(sync, sequence)
                if (firstAdoption) onPeerSynchronizationAdopted?.invoke(sync)
            } else if (sync != null &&
                !isPureSyn &&
                adoptedSynchronization == null &&
                state == State.NEGOTIATING &&
                (!reliableTransport || (!passiveNegotiation && ack == sentSequence))
            ) {
                // 发起方收到 SYN|ACK（对端对我们 SYN 的应答，载荷=对端表）：
                // 只采纳、**不回显**（回显会形成互回 SYN|ACK 的乒乓）。
                peerSynchronization = sync
                adoptedSynchronization = sync
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                // 水位必须同时前移（纯 SYN 分支同样处理）：停在 0 时对端首条数据帧
                // 会被判成「过期帧」，只回 ack=0——对端发送窗口被这个假 ACK 毒死。
                lastReceivedInOrder = sequence
                completeNegotiationIfPending(nowMillis)
            }
        }
        if (control and CONTROL_ACKNOWLEDGEMENT != 0 && peerSynchronization != null) {
            // A passive Wi-Fi link only opens when the peer confirms this round's SYN|ACK.
            if (reliableTransport && state == State.NEGOTIATING && passiveNegotiation) {
                val echoWasWritten = !socketWriteFeedback ||
                    (lastEchoTicket != 0L && inboundWrittenSynAck == lastEchoTicket)
                if (control and CONTROL_SYNCHRONIZE != 0 || ack != lastEchoOwnSequence || !echoWasWritten) return
            }
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // A trailing old ACK/data packet after DETECT cannot establish the new link.
            if (peerSynchronization == null) return
            // 序号属于整条链路；控制、EA、文件会话共同推进接收水位后再分派。
            // 但协商状态只能由控制会话推进：EA/文件会话早到不能把链路提前切到 NORMAL。
            if (sessionId == controlSessionId()) completeNegotiationIfPending(nowMillis)
            handleData(sequence, payload!!, sessionId)
        }
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
            previouslyNegotiated = true
            markerDeadline = null
            syncDeadline = null
            lastOutputMillis = nowMillis
            drainSendWindow(nowMillis)
        }
    }

    /**
     * 回显对端 sync（SYN|ACK，载荷与对端完全一致）——声明「手机采纳车机会话表」。
     *
     * **必须消耗一个新发送序号**：对端按自己的序号账法校验回包（catplay
     * `NegotiationTx::SynAck` 用 `take_own_seq()` 自增）。旧实现复用 sentSequence
     * （发 SYN 后从未自增），于是 SYN 与 SYN|ACK 同为 seq=99，BMW 判为重复帧丢弃——
     * 14 轮协商全部超时、车机一条 ACK 都没回（§9h 实车日志定谳）。
     *
     * 对**同一个** SYN 的重发保持字节级幂等：重放上次那一帧，不推进序号。
     */
    private fun sendSynchronizationEcho(
        sync: SynchronizationPayload,
        peerSequence: Int,
    ) {
        if (lastEchoPeerSequence != peerSequence) {
            lastEchoOwnSequence = nextSequence(sentSequence)
            sentSequence = lastEchoOwnSequence
            lastEchoPeerSequence = peerSequence
            if (socketWriteFeedback) lastEchoTicket = ++nextWriteTicket
        }
        if (lastEchoTicket != 0L) socketWrites += SocketWrite(output.size(), lastEchoTicket, cancellable = false)
        writePacket(ownSyncOf(sync).encode(), lastEchoOwnSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
    }

    /**
     * 回显用的「我方参数」：会话表沿用对端声明，重传参数按双方协商值，
     * 但 **maxOutgoing 提到 0x7F**——原样回显会把我们自己的发送窗口限死在对端的
     * 小窗口（MIB3 突发 8 条在 maxOutgoing=4 下积压；真机 iPhone/AADisplay 均声明
     * 0x7F）。maxLength 保持对端值（=我们的收包上限，勿放大）。
     */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(
            maxOutgoing = declaredWindow,
            retransmissionTimeoutMillis = agreedRetryTimeoutMillis.toInt(),
            maxRetransmissions = agreedRetryCount,
        ).also {
            declaredMaxOutgoing = declaredWindow
        }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        val start = pendingStart
        if (start != null && acknowledgement == start.packet.sequence) {
            // The peer can ACK before the native writer publishes its completion.
            start.acknowledged = true
            return
        }
        val previousAck = lastSentAcknowledged.takeUnless { zeroRetryAndAckMode }
        val confirmedCount = previousAck?.let { sequenceDistance(acknowledgement, it) }
        // A cumulative ACK can repeat the watermark or advance through packets
        // already sent. Half the sequence space separates old from new.
        if (confirmedCount != null && confirmedCount > minOf(127, outstandingPackets)) return
        onPeerAcknowledgement?.invoke(acknowledgement, sentSequence)
        completeNegotiationIfPending(nowMillis)
        if (hudAwaitingAck.isNotEmpty()) {
            val previous = hudAckWatermark
            if (previous != null) {
                val advance = sequenceDistance(acknowledgement, previous)
                if (advance in 1..127 && advance <= sequenceDistance(sentSequence, previous)) {
                    hudAwaitingAck.values.removeAll { frame ->
                        val covered = sequenceDistance(frame.sequence, previous) in 1..advance
                        if (covered) hudLog?.invoke("HUD LINK ${frame.label} peer_ack=$acknowledgement covered=true")
                        covered
                    }
                    hudAckWatermark = acknowledgement
                }
            }
        }
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll {
            val confirmed = if (previousAck != null && confirmedCount != null)
                sequenceDistance(it.sequence, previousAck) in 1..confirmedCount
            else sequenceDistance(it.sequence, acknowledgement) !in 1..window
            if (confirmed && it.retryDueAt != null) withdrawSocketWrite?.invoke(it.writeTicket)
            confirmed
        }
        updateRetransmitDeadline()
        drainSendWindow(nowMillis)
    }

    private fun handleData(
        sequence: Int,
        payload: ByteArray,
        sessionId: Int,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement() // 重复或过期：重新确认当前序号
            return
        }
        // RFCOMM 为可靠有序流，不会丢包；序号跳变只可能是对端把 ACK/SYN 也计入了序号
        // （各家实现账法不一致）。因此接受任意前进量并推进水位，避免死锁式丢帧。
        lastReceivedInOrder = sequence
        if (sessionId == controlSessionId() || sessionId == fileTransferSession) {
            if (pendingEvents.size >= 256) {
                die("event queue overflow")
                return
            }
            pendingEvents += if (sessionId == controlSessionId()) Event.Control(payload) else Event.FileTransfer(payload)
        } else {
            // 上层不消费该会话，仍须累计确认且对重复帧去重，不能让对端窗口被占满。
            unknownSessionDropped++
            if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, payload)
        }
        sendAcknowledgement()
    }

    // ---- 发送 ----

    private fun drainSendWindow(nowMillis: Long, allowBulkStart: Boolean = true) {
        if (state != State.NORMAL || pendingStart != null) return
        val peer = peerSynchronization
        // Box firmware uses the same negotiated mode for the send window and standalone ACKs.
        val sendWindow = if (reliableTransport) (peer?.maxOutgoing ?: maxOutgoing).coerceIn(1, 127)
            else declaredMaxOutgoing
        val retryCount = agreedRetryCount
        val retryTimeout = retryTimeoutMillis
        while (sendBacklog.isNotEmpty()) {
            val flushLimit = flushOutputLimit
            if (flushLimit != null && outputPacketCount >= flushLimit) return
            if (!zeroRetryAndAckMode && lastSentAcknowledged != null && outstandingPackets >= sendWindow) return
            val bulk = sendBacklog.first().bulk
            if (bulk != null) {
                if (flushLimit == null) return
                if (!bulk.started) {
                    if (!allowBulkStart || outputPacketCount >= newBulkOutputLimit) return
                    if (!bulk.canStart() || (!socketWriteFeedback && bulk.commitStart?.invoke() == false)) {
                        while (sendBacklog.firstOrNull()?.bulk === bulk) sendBacklog.removeFirst()
                        bulk.onDiscarded?.invoke()
                        continue
                    }
                    if (socketWriteFeedback) {
                        val first = sendBacklog.removeFirst()
                        first.sequence = nextSequence(sentSequence)
                        assignHudSerial(first)
                        pendingStart = PendingStart(first)
                        writeDataPacket(first, cancellable = true, needsCompletion = true, firstAttempt = true)
                        return
                    }
                    bulk.started = true
                }
            }
            val packet = sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.onSequenceAssigned?.invoke(packet.sequence)
            assignHudSerial(packet)
            trackHudAcknowledgement(packet)
            val trackForRetry = packet.awaitAck && retryCount > 0 && retryTimeout > 0
            writeDataPacket(packet, needsCompletion = socketWriteFeedback && (trackForRetry || packet.onWritten != null),
                firstAttempt = true)
            if (!packet.awaitAck) continue // 发后不管：不追踪、不重传（见 sendControl 注释）
            if (trackForRetry) {
                unacknowledged += packet
                if (!socketWriteFeedback) {
                    packet.deadlineMillis = nowMillis + retryTimeout
                    updateRetransmitDeadline()
                }
            } else if (!reliableTransport && retryCount == 0) {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    private fun assignHudSerial(packet: Packet) {
        if (packet.hudMessageId != 0) packet.hudSerial = ++nextHudSerial
    }

    private fun trackHudAcknowledgement(packet: Packet) {
        hudAwaitingAck.values.removeAll { frame ->
            val expired = sequenceDistance(packet.sequence, frame.sequence) >= 127 || frame.sequence == packet.sequence
            if (expired) hudLog?.invoke("HUD LINK ${frame.label} ack=unconfirmed reason=sequence_window")
            expired
        }
        if (packet.hudMessageId != 0) {
            if (hudAwaitingAck.isEmpty()) hudAckWatermark = lastSentAcknowledged
            hudAwaitingAck[packet.sequence] = HudFrame(0, packet.hudMessageId, packet.sequence, packet.hudSerial)
        }
    }

    private fun updateRetransmitDeadline() {
        retransmitDeadline = unacknowledged.mapNotNull { it.deadlineMillis }.minOrNull()
    }

    /** 出向控制消息的会话 ID：采纳了对端表就用其 control ID（Audi=1），否则本表 10。 */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis ?: Long.MAX_VALUE } ?: return
        if (next.deadlineMillis == null) return
        if (next.optional && next.retransmissions >= 1) {
            // Artwork has its own bounded business timeout. Its loss must not kill CarPlay.
            unacknowledged.remove(next)
            updateRetransmitDeadline()
            return
        }
        if (next.retransmissions >= agreedRetryCount) {
            die("packet ${next.sequence} not acknowledged")
            return
        }
        if (socketWriteFeedback) {
            next.retryDueAt = next.deadlineMillis
            next.deadlineMillis = null
        } else {
            next.retransmissions++
            next.deadlineMillis = nowMillis + retryTimeoutMillis
        }
        writeDataPacket(next, needsCompletion = socketWriteFeedback, cancellable = socketWriteFeedback)
        updateRetransmitDeadline()
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = localRetryTimeoutMillis.toInt(),
                acknowledgementTimeoutMillis = DEFAULT_ACK_TIMEOUT_MILLIS,
                maxRetransmissions = localRetryCount,
                maxAcknowledgements = 3,
                sessions =
                    listOf(
                        SessionDescriptor(SESSION_CONTROL, 0, 2),
                        SessionDescriptor(SESSION_EA, 2, 1),
                        SessionDescriptor(SESSION_FILE_TRANSFER, 1, 2),
                    ),
            ).encode()
        writePacket(payload, sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        // xiap2 a48f8/a5ee8 skip standalone ACKs and their timer in this mode.
        // Receive sequencing still advances, and business/file replies carry the current ACK.
        if (zeroRetryAndAckMode) return
        if (reliableTransport) {
            pendingReliableAck = true
            return
        }
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    /** NORMAL 态保活截止时刻：对端声明的 ackTimeout（无对端值/未进 NORMAL 时为 null）。 */
    private fun keepAliveDeadlineMillis(): Long? {
        if (reliableTransport) return null
        if (state != State.NORMAL) return null
        val interval = (peerSynchronization?.acknowledgementTimeoutMillis ?: DEFAULT_ACK_TIMEOUT_MILLIS).coerceIn(100, 10_000)
        return lastOutputMillis + interval
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
    ) {
        if (state == State.DEAD) return
        // Every ACK-bearing packet confirms the current receive watermark, including CSM replies.
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) pendingReliableAck = false
        onFrameSent?.invoke(control, sequence, lastReceivedInOrder, sessionId, payload?.size ?: 0)
        val length = payload?.size?.plus(HEADER_BYTES + 1) ?: HEADER_BYTES
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = lastReceivedInOrder.toByte()
        frame[7] = sessionId.toByte()
        var sum = 0
        for (index in 0 until HEADER_BYTES - 1) sum += u8(frame[index])
        frame[HEADER_BYTES - 1] = (-sum and 0xFF).toByte()
        if (payload != null) {
            payload.copyInto(frame, HEADER_BYTES)
            var payloadSum = 0
            payload.forEach { payloadSum += u8(it) }
            frame[frame.size - 1] = (-payloadSum and 0xFF).toByte()
        }
        lastOutputMillis = now()
        appendOutput(frame)
    }

    private fun writeDataPacket(
        packet: Packet, needsCompletion: Boolean = false, cancellable: Boolean = false, firstAttempt: Boolean = false,
    ) {
        if (needsCompletion) {
            packet.writeTicket = ++nextWriteTicket
            socketWrites += SocketWrite(output.size(), packet.writeTicket, cancellable)
            if (firstAttempt) packet.onWritten?.let { writeCallbacks[packet.writeTicket] = it }
        }
        if (packet.mfi != null && state != State.DEAD) {
            outputContainsMfi = true
            if (packet.mfi == MfiControl.RESULT) {
                mfiResults += MfiResultFrame(packet.sequence, packet.retransmissions, packet.payload.size)
            }
        }
        if (state != State.DEAD && packet.hudMessageId != 0) {
            hudOutput += HudFrame(output.size(), packet.hudMessageId, packet.sequence, packet.hudSerial)
        }
        writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, packet.sessionId ?: controlSessionId())
        if (firstAttempt && !socketWriteFeedback) packet.onWritten?.invoke()
    }

    /** 单调时钟基准（与调用方喂进 feed/advanceTime 的 nowMillis 同源）。 */
    private fun now(): Long = System.nanoTime() / 1_000_000L

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        reportDiscardedMfi("link_ended")
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        pendingStart = null
        socketWrites.clear()
        writeCallbacks.clear()
        resetOutput()
        endHudTrace("link_ended")
        pendingEvents.clear()
        sendBacklog.clear()
        pendingReliableAck = false
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
        outputPacketCount++
        return true
    }

    private class ReceiveBuffer {
        private val bytes = ByteArray(0xFFFF + 8)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(
            source: ByteArray,
            offset: Int,
            count: Int,
        ) {
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray = bytes.copyOfRange(head, head + count)

        fun take(count: Int): ByteArray {
            val out = peek(count)
            discard(count)
            return out
        }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val SESSION_CONTROL = 10
        const val SESSION_EA = 11
        const val SESSION_FILE_TRANSFER = 12

        /** 会话描述符里的类型字段：0 = 控制会话（xcertplay/extractor/catplay 一致）。 */
        private const val CONTROL_KIND = 0

        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        const val HEADER_BYTES = 9
        private const val SYNCHRONIZATION_VERSION = 1
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNC_RESEND_MILLIS = 500L
        private const val ACK_SLACK = 10

        /** 无对端声明时的 ackTimeout 兜底（同时用作裸 ACK 保活间隔）。 */
        private const val DEFAULT_ACK_TIMEOUT_MILLIS = 1_000

        private fun sequenceDistance(
            sequence: Int,
            previous: Int,
        ): Int = (sequence - previous) and 0xFF

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

        private fun checksumValid(bytes: ByteArray): Boolean {
            var sum = 0
            bytes.forEach { sum += u8(it) }
            return sum and 0xFF == 0
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun writeU16(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value ushr 8)
            out.write(value)
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
