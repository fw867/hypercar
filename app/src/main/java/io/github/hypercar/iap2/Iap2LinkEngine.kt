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
 * ack=已按序收到的最新序号。对端 marker 会被帧头扫描自然丢弃。
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
    private val retransmissionTimeoutMillis: Long = 4_000L,
    private val maxRetransmissions: Int = 4,
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
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val awaitAck: Boolean = true,
    )

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
     * **本方声明的发送窗口**（LSP maxOutgoing 语义 = 声明方自己的未确认包上限）：
     * 发送窗口按此计，而非对端声明值——回显时我们会声明 0x7F（ownSyncOf），
     * 按对端值计窗会把突发消息卡死在对端的小窗口里（MIB3 突发 8 条 × 对端 4）。
     */
    private var declaredMaxOutgoing = maxOutgoing

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
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    /** 上一次回显 SYN 时对端的 SYN 序号与我方序号（重传同一 SYN 时原样重放）。 */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0

    /** 最近一次出向帧时刻（NORMAL 态裸 ACK 保活的基准）。 */
    private var lastOutputMillis: Long = 0

    val isDead: Boolean get() = state == State.DEAD

    /** 进入协商：发 marker + 同步包（发送端=发起方）。 */
    fun start(nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
        lastOutputMillis = nowMillis
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        if (state == State.DEAD) return
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
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? {
        val keepAlive = keepAliveDeadlineMillis()
        return listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline, keepAlive).minOrNull()
    }

    fun advanceTime(nowMillis: Long) {
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
        if (retransmitDeadline != null && retransmitDeadline!! <= nowMillis) {
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
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        sendBacklog += Packet(0, bytes.copyOf(), awaitAck = awaitAck)
        drainSendWindow(nowMillis)
        return true
    }

    // ---- 接收解析 ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // 丢弃帧头前的杂字节（含对端 marker FF55…）
        while (receive.size >= 2 &&
            !(receive[0] == 0xFF.toByte() && receive[1] == 0x5A.toByte())
        ) {
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
                state == State.NEGOTIATING
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
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) {
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // 序号属于整条链路；控制、EA、文件会话共同推进接收水位后再分派。
            // 但协商状态只能由控制会话推进：EA/文件会话早到不能把链路提前切到 NORMAL。
            if (sessionId == controlSessionId()) completeNegotiationIfPending(nowMillis)
            handleData(sequence, payload!!, sessionId)
        }
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
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
        if (lastEchoPeerSequence == peerSequence) {
            writePacket(ownSyncOf(sync).encode(), lastEchoOwnSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
            return
        }
        val ownSequence = nextSequence(sentSequence).also { sentSequence = it }
        writePacket(ownSyncOf(sync).encode(), ownSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
        lastEchoPeerSequence = peerSequence
        lastEchoOwnSequence = ownSequence
    }

    /**
     * 回显用的「我方参数」：会话表/超时沿用对端声明（手机端无特殊要求），
     * 但 **maxOutgoing 提到 0x7F**——原样回显会把我们自己的发送窗口限死在对端的
     * 小窗口（MIB3 突发 8 条在 maxOutgoing=4 下积压；真机 iPhone/AADisplay 均声明
     * 0x7F）。maxLength 保持对端值（=我们的收包上限，勿放大）。
     */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(maxOutgoing = PHONE_DECLARED_MAX_OUTGOING).also {
            declaredMaxOutgoing = PHONE_DECLARED_MAX_OUTGOING
        }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        completeNegotiationIfPending(nowMillis)
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll { sequenceDistance(it.sequence, acknowledgement) !in 1..window }
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
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
        if (sessionId == controlSessionId()) {
            if (pendingEvents.size >= 256) {
                die("event queue overflow")
                return
            }
            pendingEvents += Event.Control(payload)
        } else {
            // 上层不消费该会话，仍须累计确认且对重复帧去重，不能让对端窗口被占满。
            unknownSessionDropped++
            if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, payload)
        }
        sendAcknowledgement()
    }

    // ---- 发送 ----

    private fun drainSendWindow(nowMillis: Long) {
        if (state != State.NORMAL) return
        while (sendBacklog.isNotEmpty()) {
            val packet = sendBacklog.first()
            if (lastSentAcknowledged != null &&
                sequenceDistance(sentSequence, lastSentAcknowledged!!) >= declaredMaxOutgoing
            ) {
                return
            }
            sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.deadlineMillis = nowMillis + retransmissionTimeoutMillis
            writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
            if (!packet.awaitAck) continue // 发后不管：不追踪、不重传（见 sendControl 注释）
            val peer = peerSynchronization
            if (peer == null || peer.maxRetransmissions > 0) {
                unacknowledged += packet
                retransmitDeadline = packet.deadlineMillis
            } else {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    /** 出向控制消息的会话 ID：采纳了对端表就用其 control ID（Audi=1），否则本表 10。 */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        next.deadlineMillis = nowMillis + retransmissionTimeoutMillis
        next.retransmissions += 1
        if (next.retransmissions > maxRetransmissions) {
            die("packet ${next.sequence} not acknowledged")
            return
        }
        writePacket(next.payload, next.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = retransmissionTimeoutMillis.toInt().coerceIn(0, 0xFFFF),
                acknowledgementTimeoutMillis = DEFAULT_ACK_TIMEOUT_MILLIS,
                maxRetransmissions = maxRetransmissions,
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
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    /** NORMAL 态保活截止时刻：对端声明的 ackTimeout（无对端值/未进 NORMAL 时为 null）。 */
    private fun keepAliveDeadlineMillis(): Long? {
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

    /** 单调时钟基准（与调用方喂进 feed/advanceTime 的 nowMillis 同源）。 */
    private fun now(): Long = System.nanoTime() / 1_000_000L

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        output.reset()
        pendingEvents.clear()
        sendBacklog.clear()
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
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
