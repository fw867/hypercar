package io.github.hypercar.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.hypercar.core.RustCoreBridge
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class CallAudioOfferEnd {
    WITHDRAWN, IDENTITY_UNCONFIRMED, OTHER;

    companion object {
        fun fromStatus(status: String?): CallAudioOfferEnd {
            val endpoint = status?.substringAfter(" endpoint=", "") ?: return OTHER
            return when {
                endpoint == ShellCallAudio.OPEN_IDENTITY_UNCONFIRMED -> IDENTITY_UNCONFIRMED
                endpoint == ShellCallAudio.CONTROLLER_REVOKED ||
                    endpoint == ShellCallAudio.CALL_NO_LONGER_ELIGIBLE ||
                    endpoint.startsWith(ShellCallAudio.IDENTITY_REVOKED) ||
                    endpoint.startsWith(ShellCallAudio.IDENTITY_LOST) -> WITHDRAWN
                else -> OTHER
            }
        }
    }
}

internal class ShellCallEndpoint(private val identity: ShellCallIdentity, private val lease: String,
                                  private val lifetime: CallAudioServiceLifetime = CallAudioServiceLifetime(),
                                  private val onRetired: () -> Unit = {}) {
    private val live = AtomicBoolean(true)
    private val stateLock = Any()
    private val audioLock = Any()
    private val runningTag = Any()
    private var firstFrameWritten = false

    fun isLive(): Boolean = live.get()

    fun <T : Any> runWhileAuthorized(startBefore: Long? = null, action: () -> T): T? = synchronized(stateLock) {
        if (live.get() && !lifetime.stopping) identity.withCurrentLease(lease, startBefore, action) else null
    }

    fun <T : Any> runAudioWhileAuthorized(startBefore: Long? = null, action: () -> T): T? = synchronized(audioLock) {
        if (runWhileAuthorized(startBefore) { true } == true && live.get()) action() else null
    }

    fun writeFirstFrame(deadline: Long, send: () -> Boolean): Boolean = runWhileAuthorized(startBefore = deadline) {
        lifetime.whileRunning {
            if (firstFrameWritten || !send()) false else {
                firstFrameWritten = true
                true
            }
        } == true
    } == true

    fun acknowledgeStart(): Boolean = runWhileAuthorized {
        lifetime.whileRunning { firstFrameWritten && identity.markRunning(lease, runningTag) } == true
    } == true

    fun retire(stopTransport: () -> Unit = {}): Boolean {
        // Cancel new admissions and wake the socket without waiting for Android audio.
        val wasLive = synchronized(stateLock) {
            live.getAndSet(false).also { if (it) stopTransport() }
        }
        if (wasLive) {
            identity.markStopped(runningTag)
            onRetired()
        }
        // An admitted native call must leave the media lock before revocation completes.
        return synchronized(audioLock) { wasLive }
    }
}

/** Linearizes the service's stop with short endpoint publication and startup operations. */
internal class CallAudioServiceLifetime {
    @Volatile var stopping = false
        private set

    @Synchronized fun startStopping(): Boolean {
        if (stopping) return false
        stopping = true
        return true
    }

    @Synchronized fun <T : Any> whileRunning(action: () -> T): T? = if (stopping) null else action()
}

/** Use separate workers for the volume write and its readback. A newer write supersedes an older read. */
internal class CallOutputRestoration(
    private val isMuted: () -> Boolean,
    private val unmute: () -> Unit,
    private val finish: (Long) -> Boolean,
    private val retryWhenMuted: Boolean = true,
    waitForMute: Boolean = false,
) {
    private class Write(val generation: Long) {
        @Volatile var verified = false
    }
    private val written = AtomicReference<Write?>(null)
    @Volatile private var waitingForMute = waitForMute

    fun write(generation: Long, authorize: () -> Boolean = { true }) {
        if (waitingForMute || written.get()?.generation == generation || !authorize()) return
        if (runCatching(unmute).isSuccess) written.set(Write(generation))
    }

    fun hasReadback(): Boolean = waitingForMute || written.get()?.verified == false

    fun verify(current: () -> Boolean = { true }): Boolean {
        if (!current()) return false
        if (waitingForMute) {
            if (runCatching(isMuted).getOrDefault(false) && current()) waitingForMute = false
            return false
        }
        val candidate = written.get() ?: return false
        if (candidate.verified) return true
        val muted = runCatching(isMuted).getOrNull() ?: return false
        if (!current()) return false
        if (muted) {
            if (retryWhenMuted) written.compareAndSet(candidate, null)
            return false
        }
        if (written.get() !== candidate || !finish(candidate.generation)) return false
        candidate.verified = true
        return true
    }
}

/** A Binder read may outlive its offer. Allow the next offer to verify independently. */
internal class CallOutputReadbacks(
    private val executor: Executor = Executors.newFixedThreadPool(MAX_READERS) { task ->
        Thread(task, "hypercar-call-restore-read").apply { isDaemon = true }
    },
) {
    private val readers = mutableSetOf<CallOutputRestoration>()

    @Synchronized fun hasCapacity() = readers.size < MAX_READERS

    fun check(owner: CallOutputRestoration, current: () -> Boolean, verified: () -> Unit) {
        if (!owner.hasReadback()) return
        synchronized(this) { if (!hasCapacity() || !readers.add(owner)) return }
        executor.execute {
            try {
                if (owner.verify(current) && current()) verified()
            } finally {
                synchronized(this) { readers.remove(owner) }
            }
        }
    }

    companion object { const val MAX_READERS = 2 }
}

/** A verified, temporary mute over the existing handset route and volume. */
internal class LocalCallMute(
    private val elapsed: () -> Long,
    private val handsetRoute: () -> Boolean,
    private val isMuted: () -> Boolean,
    private val setMuted: (Boolean) -> Unit,
    private val trace: (String) -> Unit = {},
    private val persist: (CallOutputJournal.State) -> Boolean = { true },
    private val renewRecoveryScope: () -> Boolean = { true },
    private val authorizeRestore: (Boolean) -> Boolean = { true },
    private val dispatchCurrent: () -> Boolean = { true },
    private val commitDispatch: () -> Boolean = { true },
    private val cancelUndispatched: () -> Unit = { persist(CallOutputJournal.State.CLEAR) },
) {
    // Serialize disk updates for this owner independently of watchdog and platform writes.
    private val journalLock = Any()
    private var ownsMute = false
    private var suppressed = false
    private var retiring = false
    private var restoreRequested = false
    private var muteInFlight = false
    private var muteUnconfirmed = false
    private var muteGeneration = 0L
    private var loggedRestoreFailure = false
    private var until = 0L
    private var nextRouteCheck = 0L
    private var nextScopeRenewal = 0L
    private var uncertainUntil = 0L
    private var restoreUntil: Long? = null
    val restoration = CallOutputRestoration(isMuted, { setMuted(false) }, ::finishRestore)

    fun confirm(validUntil: Long, authorize: () -> Boolean) {
        val now = elapsed()
        if (validUntil <= now || validUntil - now > LEASE_MS || !authorize()) return
        if (!onHandset()) {
            requestRestore()
            return
        }
        expire()
        val muted = runCatching(isMuted).getOrNull()
        val ownedGeneration = synchronized(this) {
            if (retiring || restoreRequested || suppressed) return
            if (ownsMute) {
                if (muted == false) {
                    suppressed = true
                    if (muteUnconfirmed) restoreRequested = true
                    trace("local_output mute_superseded")
                } else if (muted == true && elapsed() < validUntil) {
                    until = maxOf(until, validUntil)
                    if (muteUnconfirmed || elapsed() < nextScopeRenewal) return
                    nextScopeRenewal = elapsed() + 1_000
                } else return
                muteGeneration
            } else {
                if (muted != false) {
                    suppressed = muted == true
                    return
                }
                null
            }
        }
        if (ownedGeneration != null) {
            synchronized(journalLock) {
                synchronized(this) {
                    if (!ownsMute || muteGeneration != ownedGeneration || muteUnconfirmed) return
                    if (muted == true && restoreRequested) return
                }
                if (muted == true) {
                    if (!renewRecoveryScope()) failOpen()
                } else {
                    val cleared = persist(CallOutputJournal.State.CLEAR)
                    synchronized(this) {
                        if (muteGeneration == ownedGeneration && ownsMute) {
                            if (cleared) ownsMute = false else restoreRequested = true
                        }
                    }
                }
            }
            return
        }
        if (!onHandset() || !runCatching(authorize).getOrDefault(false) || elapsed() >= validUntil) return
        synchronized(journalLock) {
            synchronized(this) { if (retiring || restoreRequested || ownsMute || suppressed) return }
            if (!persist(CallOutputJournal.State.MUTE_UNCONFIRMED)) return
            // The Android route query can block. Run it after all journal I/O and outside the
            // watchdog monitor, then fence cancellation, cached identity and time at dispatch.
            val handset = onHandset()
            val dispatched = synchronized(this) {
                val current = handset && !retiring && !restoreRequested && dispatchCurrent()
                val dispatchTime = elapsed()
                if (current && dispatchTime < validUntil && commitDispatch()) {
                    ownsMute = true
                    muteInFlight = true
                    muteGeneration++
                    until = validUntil
                    nextRouteCheck = dispatchTime + ROUTE_CHECK_MS
                    nextScopeRenewal = dispatchTime + 1_000
                    uncertainUntil = dispatchTime + CallOutputRecoveryScope.WINDOW_MS
                    true
                } else false
            }
            if (!dispatched) {
                cancelUndispatched()
                return
            }
        }
        val applied = runCatching { setMuted(true) }.isSuccess
        synchronized(journalLock) {
            synchronized(this) {
                muteUnconfirmed = !applied
                // An early compensation may precede the actual platform write.
                if (applied && restoreRequested) restoreUntil = null
                muteInFlight = false
                muteGeneration++
            }
            if (applied && !persist(CallOutputJournal.State.RESTORE)) {
                failOpen()
                return
            }
        }
        if (!applied || !authorize() || elapsed() >= validUntil) {
            requestRestore()
        } else if (runCatching(isMuted).getOrDefault(false) && onHandset()) {
            trace("local_output muted")
        } else {
            requestRestore()
        }
    }

    @Synchronized fun expire() {
        if (ownsMute && elapsed() >= until) restoreRequested = true
    }

    @Synchronized private fun failOpen() {
        suppressed = true
        if (ownsMute) restoreRequested = true
        trace("local_output recovery journal write failed")
    }

    fun checkRoute() {
        synchronized(this) {
            val now = elapsed()
            if (!ownsMute || restoreRequested || now < nextRouteCheck) return
            nextRouteCheck = now + ROUTE_CHECK_MS
        }
        if (!onHandset()) requestRestore()
    }

    @Synchronized fun requestRestore(retire: Boolean = false) {
        if (retire) retiring = true
        if (ownsMute) restoreRequested = true
    }

    @Synchronized fun needsRestore(): Boolean {
        if (!ownsMute || !restoreRequested) return false
        val now = elapsed()
        val deadline = restoreUntil ?: (now + CallOutputRecoveryScope.WINDOW_MS).also { restoreUntil = it }
        return now < deadline && (!muteUnconfirmed || now < uncertainUntil)
    }

    @Synchronized fun tracksRestore() = needsRestore() || muteInFlight && restoreRequested

    fun writeRestore() {
        val generation = synchronized(this) {
            if (!ownsMute) return
            restoreRequested = true
            if (!needsRestore()) return
            muteGeneration
        }
        restoration.write(generation) {
            val confirmed = synchronized(this) { !muteInFlight && !muteUnconfirmed }
            runCatching { authorizeRestore(confirmed) }.getOrDefault(false) && synchronized(this) {
                generation == muteGeneration && needsRestore()
            }
        }
    }

    private fun finishRestore(generation: Long): Boolean = synchronized(journalLock) {
        // An earlier unmute readback cannot settle a write whose mute was still in flight.
        val current = synchronized(this) {
            if (!ownsMute) return true
            !muteInFlight && !muteUnconfirmed && muteGeneration == generation
        }
        val cleared = current && persist(CallOutputJournal.State.CLEAR)
        synchronized(this) {
            if (cleared && muteGeneration == generation) {
                ownsMute = false
                restoreRequested = false
                restoreUntil = null
                loggedRestoreFailure = false
                trace("local_output restored")
            } else if (!loggedRestoreFailure) {
                loggedRestoreFailure = true
                trace("local_output restore_unconfirmed")
            }
            !ownsMute
        }
    }

    private fun onHandset() = runCatching(handsetRoute).getOrDefault(false)

    companion object {
        const val LEASE_MS = 650L
        private const val ROUTE_CHECK_MS = 100L
    }
}

/** Keeps a failed restore alive after an offer closes and serializes it with the next mute. */
internal class CallOutputMuteWatchdog {
    private var watching: LocalCallMute? = null
    private var pendingRestore: LocalCallMute? = null

    fun update(active: LocalCallMute?): LocalCallMute? {
        if (pendingRestore?.tracksRestore() == false) pendingRestore = null
        if (watching !== active) {
            watching?.let { previous ->
                previous.requestRestore(retire = true)
                if (previous.tracksRestore()) pendingRestore = previous
            }
            watching = active
        }
        active?.expire()
        return pendingRestore ?: active?.takeIf(LocalCallMute::tracksRestore)
    }
}

/** The app owns the file. Only the Shizuku process holding this descriptor's lock may change volume. */
internal class CallOutputJournal(
    bootCount: Int,
    private val readAt: (Long, ByteArray) -> Int,
    private val writeAt: (Long, ByteArray) -> Int,
    private val sync: () -> Unit,
    private val mapSelector: () -> ByteBuffer,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class State { CLEAR, RESTORE, MUTE_UNCONFIRMED }
    data class Record(val state: State, val scope: CallOutputRecoveryScope?)

    private val boot = bootCount.toLong() + 1
    private val confirmed = (1L shl 32) + boot
    private var writer: Any? = null
    private data class Previous(val selector: Byte, val writer: Any?, val committed: Byte, val mapping: ByteBuffer)
    private var beforeDispatch: Previous? = null
    private data class RollbackRetry(val failures: Int, val due: Long)
    @Volatile private var rollback: RollbackRetry? = null

    @Synchronized fun arm(owner: Any, scope: CallOutputRecoveryScope?): Boolean = runCatching {
        check(beforeDispatch == null && rollback == null)
        stage(State.MUTE_UNCONFIRMED, checkNotNull(scope)) { previous, next ->
            val mapping = mapSelector()
            val prepared = Previous(previous, writer, next, mapping)
            mapping.put(0, previous) // Fault in the writable selector before the dispatch fence.
            select((next + PREPARED_OFFSET).toByte())
            beforeDispatch = prepared
            writer = owner
        }
    }.isSuccess

    @Synchronized fun commitArm(owner: Any): Boolean {
        val prepared = beforeDispatch ?: return false
        if (writer !== owner || rollback != null) return false
        // Dispatch starts at this shared byte store. A new shell sees either the original
        // record or the complete unconfirmed write, including when the Binder reply is lost.
        prepared.mapping.put(0, prepared.committed)
        beforeDispatch = null
        return true
    }

    @Synchronized fun abortArm(owner: Any): Boolean {
        val previous = beforeDispatch ?: return false
        if (writer !== owner) return false
        val restored = runCatching {
            select(previous.selector)
            writer = previous.writer
            beforeDispatch = null
        }.isSuccess
        rollback = if (restored) null else {
            val failures = minOf((rollback?.failures ?: 0) + 1, MAX_ROLLBACK_FAILURES)
            val delay = 250L shl minOf(3, failures - 1)
            RollbackRetry(failures, elapsed() + delay)
        }
        return restored
    }

    fun watchRollback(activeOffer: Boolean): Boolean =
        rollback?.let { activeOffer || it.failures < MAX_ROLLBACK_FAILURES } == true

    fun rollbackDue() = rollback?.let { elapsed() >= it.due } == true

    @Synchronized fun retryRollback() {
        if (rollbackDue()) writer?.let(::abortArm)
    }

    @Synchronized fun persistFor(owner: Any, state: State, scope: CallOutputRecoveryScope? = null): Boolean =
        beforeDispatch == null && rollback == null && writer === owner && runCatching {
            publish(state, scope)
        }.isSuccess

    @Synchronized fun current(): Record = decode(records(selected()))

    // Each slot contains the current record followed by the most recently superseded record.
    // Legacy files stored the two 24-byte records first, then their optional 4-byte PIDs.
    private fun records(selector: Byte, preparingWrite: Boolean = false): ByteArray {
        val slot = slot(selector)
        val bytes = ByteArray(SLOT_SIZE)
        if (slot != null) {
            val count = readAt(slotOffset(slot), bytes)
            if (count == SLOT_SIZE) {
                if (selector.toInt() == SELECT_A + PREPARED_OFFSET || selector.toInt() == SELECT_B + PREPARED_OFFSET) {
                    // No dispatch was committed. The archive holds the exact displaced record,
                    // including a clear record; recovery never acts on the staged candidate.
                    bytes.copyInto(bytes, 0, RECORD_SIZE, SLOT_SIZE)
                    bytes.fill(0, RECORD_SIZE)
                }
                return bytes
            }
            // A damaged legacy header may happen to start with a selector byte.
            // If its slot does not exist, the header supplies no recognized boot marker.
            check(count == 0)
        }
        val count = readAt(0L, bytes)
        // Extending a short legacy file to store its first slot fills the gap with zeroes.
        // Strip an incomplete scope or PID first so that padding cannot turn it into evidence.
        val partialField = when (count) {
            in 9..23 -> 8
            in 33..47 -> 32
            in 49..51 -> 48
            in 53..55 -> 52
            else -> count
        }
        if (partialField < count) {
            bytes.fill(0, partialField, count)
            if (preparingWrite) {
                val zeros = ByteArray(count - partialField)
                check(writeAt(partialField.toLong(), zeros) == zeros.size)
            }
        }
        val migrated = ByteArray(SLOT_SIZE)
        bytes.copyInto(migrated, 0, 0, 24)
        bytes.copyInto(migrated, RECORD_SIZE, 24, 48)
        if (count >= 52) bytes.copyInto(migrated, 24, 48, 52)
        if (count >= 56) bytes.copyInto(migrated, RECORD_SIZE + 24, 52, 56)
        return migrated
    }

    private fun decode(bytes: ByteArray): Record {
        val fields = ByteBuffer.wrap(bytes)
        val state = when (fields.long) {
            confirmed -> State.RESTORE
            boot, -boot -> State.MUTE_UNCONFIRMED // Older versions used +boot before dispatch.
            else -> State.CLEAR
        }
        val callNumber = fields.long
        val expiresAt = fields.long
        val pid = fields.int.takeIf { it > 1 }
        val scope = if (state != State.CLEAR && callNumber >= 0 && expiresAt > 0)
            CallOutputRecoveryScope(callNumber, expiresAt, pid) else null
        return Record(state, scope)
    }

    private fun selected(): Byte {
        val selector = ByteArray(1)
        readAt(0L, selector) // Empty files use the same zero prefix as a legacy clear marker.
        return selector[0]
    }

    private fun slot(selector: Byte): Int? = when (selector.toInt()) {
        SELECT_A, SELECT_A + PREPARED_OFFSET -> 0
        SELECT_B, SELECT_B + PREPARED_OFFSET -> 1
        else -> null // Legacy marker or an incomplete header.
    }

    private fun slotOffset(slot: Int) = (LEGACY_SIZE + slot * SLOT_SIZE).toLong()

    private fun select(selector: Byte) {
        check(writeAt(0L, byteArrayOf(selector)) == 1)
    }

    private fun publish(state: State, scope: CallOutputRecoveryScope?) =
        stage(state, scope) { _, next -> select(next) }

    private fun stage(state: State, scope: CallOutputRecoveryScope?, finish: (Byte, Byte) -> Unit) {
        require(state == State.CLEAR || scope != null)
        val marker = when (state) {
            State.CLEAR -> 0L
            State.RESTORE -> confirmed
            State.MUTE_UNCONFIRMED -> -boot
        }
        val previous = selected()
        val next = if (slot(previous) == 0) 1 else 0
        val bytes = records(previous, preparingWrite = true)
        if (state == State.MUTE_UNCONFIRMED)
            bytes.copyInto(bytes, RECORD_SIZE, 0, RECORD_SIZE)
        ByteBuffer.wrap(bytes).putLong(marker).putLong(scope?.callNumber ?: 0L)
            .putLong(scope?.expiresAt ?: 0L).putInt(scope?.telecomPid ?: 0)
        check(writeAt(slotOffset(next), bytes) == bytes.size)
        sync()
        finish(previous, (if (next == 0) SELECT_A else SELECT_B).toByte())
    }

    private companion object {
        const val LEGACY_SIZE = 56 // Active and archived legacy records, including both Telecom PIDs.
        const val RECORD_SIZE = 28
        const val SLOT_SIZE = 2 * RECORD_SIZE
        const val SELECT_A = 0x41
        const val SELECT_B = 0x42
        const val PREPARED_OFFSET = 2
        const val MAX_ROLLBACK_FAILURES = 5
    }

    fun recover(isMuted: () -> Boolean, retryWhenMuted: Boolean = true, unmute: () -> Unit) = CallOutputRestoration(
        isMuted, unmute, finish = {
        // The old process may have died after AudioService accepted the mute. Keep watching
        // that write for this boot unless its completion was recorded before the handoff.
        synchronized(this) {
            writer == null && runCatching {
                check(current().state == State.RESTORE)
                publish(State.CLEAR, null)
            }.isSuccess
        }
    }, retryWhenMuted = retryWhenMuted, waitForMute = current().state == State.MUTE_UNCONFIRMED)
}

internal data class CallOutputRecoveryScope(val callNumber: Long, val expiresAt: Long, val telecomPid: Int? = null) {
    fun remaining(now: Long) = now >= 0 && expiresAt > now && expiresAt - now <= WINDOW_MS
    fun atWriteCompletion(now: Long) = if (telecomPid != null)
        copy(expiresAt = now + WINDOW_MS) else this
    fun allows(now: Long, currentCall: Long?, confirmed: Boolean = false, currentPid: Int? = null): Boolean {
        if (telecomPid != null && currentPid != telecomPid) return false
        if (currentCall == callNumber) return remaining(now) || confirmed && telecomPid != null && now >= expiresAt
        return confirmed && currentCall == ShellCallIdentity.NO_RECOVERY_CALL && remaining(now)
    }

    companion object {
        const val WINDOW_MS = 6_000L
    }
}

/** Live PSTN downlink/extraction and uplink/injection owned by the authorized shell service. */
internal class ShellCallAudio(
    private val callIdentity: ShellCallIdentity,
    private val trace: (String) -> Unit = {},
    private val lifetime: CallAudioServiceLifetime = CallAudioServiceLifetime(),
    private val readbacks: CallOutputReadbacks = CallOutputReadbacks(),
) {
    private class UnconfirmedCallIdentity : IllegalStateException()

    private class Session(
        val token: Long, val offer: Long, val callId: String, val owner: IBinder, val death: IBinder.DeathRecipient,
        val socket: ParcelFileDescriptor, val record: AudioRecord, val track: AudioTrack,
        val endpoint: ShellCallEndpoint, val outputMute: LocalCallMute,
    ) {
        val endReason = AtomicReference<String?>()
        val muteDeadline = AtomicLong(0)
    }

    private data class ClosedOffer(val token: Long, val offer: Long, val status: String)

    @Volatile private var session: Session? = null
    @Volatile private var status = "idle"
    private val closedOffers = ArrayDeque<ClosedOffer>()
    private val outputWatchdog = CallOutputMuteWatchdog()
    private val outputWatchdogRunning = AtomicBoolean(false)
    private val muteWorkInFlight = AtomicBoolean(false)
    private val inputRouteDiagnosticInFlight = AtomicBoolean(false)
    private val restoreWorkInFlight = AtomicBoolean(false)
    private val outputExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "hypercar-call-output").apply { isDaemon = true }
        }
    }
    private val muteExecutor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "hypercar-call-mute").apply { isDaemon = true } }
    }
    private val restoreExecutor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "hypercar-call-restore").apply { isDaemon = true } }
    }
    private val journalRegistrationLock = Any()
    @Volatile private var journal: CallOutputJournal? = null
    private var journalDescriptor: ParcelFileDescriptor? = null
    @Volatile private var recoverOnConnect: CallOutputRestoration? = null
    @Volatile private var reconnectUntil = 0L
    private val stopping get() = lifetime.stopping

    fun setOutputJournal(context: Context, descriptor: ParcelFileDescriptor, bootCount: Int): Boolean =
        synchronized(journalRegistrationLock) {
            try {
                if (stopping) return@synchronized false
                if (journalDescriptor != null) return@synchronized true
                val file = descriptor.fileDescriptor
                // Map only when arming on an accepted descriptor. A staged slot guarantees a
                // nonempty file and the descriptor and its mapping live until UserService exit.
                val selector by lazy { checkNotNull(RustCoreBridge.nativeMapCallOutputSelector(descriptor.fd)) }
                val state = CallOutputJournal(bootCount,
                    readAt = { offset, bytes -> Os.pread(file, bytes, 0, bytes.size, offset) },
                    writeAt = { offset, bytes -> Os.pwrite(file, bytes, 0, bytes.size, offset) },
                    sync = { Os.fsync(file) }, mapSelector = { selector })
                val (pending, evidence) = state.current()
                val now = SystemClock.elapsedRealtime()
                val scope = evidence?.takeIf {
                    it.callNumber >= 0 && (it.remaining(now) ||
                        pending == CallOutputJournal.State.RESTORE && it.telecomPid != null && now >= it.expiresAt)
                }
                val recovery = if (pending == CallOutputJournal.State.CLEAR || scope == null) null else {
                    val volume by lazy { audioManager(context) }
                    val issuedUnmute = AtomicBoolean(false)
                    state.recover({ volume.isStreamMute(AudioManager.STREAM_VOICE_CALL) }, retryWhenMuted = false) {
                        val confirmed = pending == CallOutputJournal.State.RESTORE
                        check(canRestoreCallOutput(scope, confirmed, volume) && recoveryWindowOpen())
                        check(issuedUnmute.compareAndSet(false, true))
                        setHandsetMute(volume, false)
                    }
                }
                if (lifetime.whileRunning {
                        reconnectUntil = if (recovery == null) 0 else SystemClock.elapsedRealtime() + CallOutputRecoveryScope.WINDOW_MS
                        recoverOnConnect = recovery
                        journalDescriptor = descriptor
                        journal = state
                    } == null) return@synchronized false
                if (pending == CallOutputJournal.State.MUTE_UNCONFIRMED) trace("local_output old mute write unconfirmed")
                if (pending != CallOutputJournal.State.CLEAR && scope == null)
                    trace("local_output recovery evidence lacks a current call scope; record retained")
                ensureOutputWatchdog()
                true
            } catch (error: Exception) {
                trace("local_output recovery journal unavailable ${error.javaClass.simpleName}")
                false
            } finally {
                // OFD locks belong to an open file description, so closing a redelivery is safe.
                if (journalDescriptor !== descriptor) runCatching { descriptor.close() }
            }
        }

    fun hasOutputJournal(): Boolean = !stopping && journal != null

    private fun callRecoveryScope(identity: String, pid: Int?): CallOutputRecoveryScope? = runCatching {
        val number = callIdentity.verifiedCallNumber(identity) ?: return@runCatching null
        CallOutputRecoveryScope(number, SystemClock.elapsedRealtime() + CallOutputRecoveryScope.WINDOW_MS, pid)
    }.getOrNull()

    private fun recoveryWindowOpen() = SystemClock.elapsedRealtime() < reconnectUntil

    private fun persistLocalMute(owner: Any, scope: AtomicReference<CallOutputRecoveryScope?>,
                                 callId: String, telecomPid: Int?, state: CallOutputJournal.State): Boolean {
        val ledger = journal ?: return false
        if (state == CallOutputJournal.State.MUTE_UNCONFIRMED) {
            if (recoverOnConnect != null || !readbacks.hasCapacity()) return false
            val evidence = callRecoveryScope(callId, telecomPid) ?: return false
            if (!ledger.arm(owner, evidence)) return false
            scope.set(evidence)
            return true
        }
        if (state == CallOutputJournal.State.CLEAR) return ledger.persistFor(owner, state)
        val completed = scope.get()?.atWriteCompletion(SystemClock.elapsedRealtime()) ?: return false
        scope.set(completed)
        return ledger.persistFor(owner, state, completed)
    }

    private fun canRestoreCallOutput(scope: CallOutputRecoveryScope, confirmed: Boolean, manager: AudioManager): Boolean {
        val mode = manager.mode
        val pid = scope.telecomPid?.let { ShellCallIdentity.recoveryTelecomPid() }
        if (scope.telecomPid != null && pid != scope.telecomPid) return false
        val call = ShellCallIdentity.recoveryCallNumber() ?: return false
        return scope.allows(SystemClock.elapsedRealtime(), call, confirmed, pid) &&
            mode == if (call == ShellCallIdentity.NO_RECOVERY_CALL) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_CALL
    }

    fun hasActiveResources(): Boolean = session != null || outputWatchdogRunning.get() ||
        muteWorkInFlight.get() || restoreWorkInFlight.get()

    private fun ensureOutputWatchdog() {
        if (outputWatchdogRunning.compareAndSet(false, true)) outputExecutor.execute(::watchOutput)
    }

    private fun watchOutput() {
        fun liveSession() = session?.takeIf { it.endpoint.isLive() }
        val active = if (stopping) null else liveSession()
        val pending = outputWatchdog.update(active?.outputMute)
        val reconnect = recoverOnConnect?.takeIf { recoveryWindowOpen() }
        // A late volume read can only update its recovery object, and journal clears check the owner.
        // A dispatched volume write must finish before another offer can take over.
        if (reconnect == null && recoverOnConnect != null && !restoreWorkInFlight.get()) {
            recoverOnConnect = null
            trace("local_output recovery window expired; record retained")
        }
        val restoration = reconnect ?: pending?.takeIf { it.needsRestore() }?.restoration
        if (restoration != null && restoreWorkInFlight.compareAndSet(false, true)) {
            restoreExecutor.execute {
                try {
                    if (reconnect == null) pending?.writeRestore() else reconnect.write(0)
                } finally {
                    restoreWorkInFlight.set(false)
                    if (reconnect != null && !recoveryWindowOpen()) ensureOutputWatchdog()
                }
            }
        }
        if (restoration != null) {
            readbacks.check(restoration, current = {
                if (reconnect == null) pending?.needsRestore() == true
                else recoverOnConnect === reconnect && recoveryWindowOpen()
            }) {
                if (reconnect != null && recoverOnConnect === reconnect) {
                    recoverOnConnect = null
                    trace("local_output recovered after shell reconnect")
                }
            }
        }
        val rollback = journal?.takeIf { it.watchRollback(active != null) }
        if (rollback?.rollbackDue() == true && muteWorkInFlight.compareAndSet(false, true)) {
            muteExecutor.execute {
                try { rollback.retryRollback() } finally { muteWorkInFlight.set(false) }
            }
        } else if (rollback == null && recoverOnConnect == null && pending == null && active != null && !restoreWorkInFlight.get() &&
            muteWorkInFlight.compareAndSet(false, true)) {
            muteExecutor.execute {
                try {
                    active.outputMute.checkRoute()
                    val deadline = active.muteDeadline.getAndSet(0)
                    if (deadline != 0L) active.outputMute.confirm(deadline) {
                        !stopping && session === active && active.endpoint.runWhileAuthorized(startBefore = Long.MAX_VALUE) {
                            runCatching { socketOpen(active.socket.fileDescriptor) }.getOrDefault(false)
                        } == true
                    }
                } finally {
                    muteWorkInFlight.set(false)
                    ensureOutputWatchdog()
                }
            }
        }
        if (pending?.needsRestore() == true || reconnect != null || active != null || rollback != null) {
            outputExecutor.schedule(::watchOutput, if (active != null) 50 else 250, TimeUnit.MILLISECONDS)
        } else {
            outputWatchdogRunning.set(false)
            if (pending?.needsRestore() == true || (!stopping && liveSession() != null) ||
                (recoverOnConnect != null && (recoveryWindowOpen() || !restoreWorkInFlight.get())) ||
                journal?.watchRollback(false) == true) ensureOutputWatchdog()
        }
    }

    fun stopForServiceExit() {
        ensureOutputWatchdog()
        session?.let { revoke(it, "shell_destroyed") }
    }

    private fun audioManager(context: Context) = AudioManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(PlaybackAudioContext(context))

    private fun setHandsetMute(manager: AudioManager, muted: Boolean) {
        manager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }

    fun diagnosticStatus(): String = session?.endReason?.get() ?: status

    fun diagnosticStatus(token: Long, offer: Long): String? {
        val active = session
        if (active?.token == token && active.offer == offer) {
            active.endReason.get()?.let { return "identity=${callIdentity.diagnosticStatus()} endpoint=$it" }
        }
        return synchronized(closedOffers) {
            closedOffers.lastOrNull { it.token == token && it.offer == offer }?.status
        }
    }

    private val pstnRedirectionMode by lazy { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) }

    private fun createDownlink(context: Context, manager: AudioManager, format: AudioFormat): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(manager, format) as AudioRecord
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("shell RECORD_AUDIO denied")
        }
        // AudioManager's convenience method builds the endpoint without the manager's context.
        val builder = AudioRecord.Builder().setContext(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK).setAudioFormat(format)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, pstnRedirectionMode)
        return builder.build()
    }

    private fun createUplink(context: Context, manager: AudioManager, format: AudioFormat): AudioTrack {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(manager, format) as AudioTrack
        }
        val usage = AudioAttributes::class.java.getField("USAGE_CALL_ASSISTANT").getInt(null)
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Int::class.javaPrimitiveType)
            .invoke(attributes, usage)
        val builder = AudioTrack.Builder().setContext(context)
            .setAudioAttributes(attributes.build()).setAudioFormat(format)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType)
            .invoke(builder, pstnRedirectionMode)
        return builder.build()
    }

    private fun traceInputRoutes(manager: AudioManager, token: Long, offer: Long) {
        // A stalled AudioService inventory query must not delay a retry or retire an endpoint.
        if (!inputRouteDiagnosticInFlight.compareAndSet(false, true)) return
        Thread({
            try {
                val inputs = runCatching {
                    manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }.distinct().sorted().toString()
                }.getOrElse { "unavailable_${it.javaClass.simpleName}" }
                trace("route input_types=$inputs token=$token offer=$offer")
            } finally {
                inputRouteDiagnosticInFlight.set(false)
            }
        }, "hypercar-call-route-diagnostics").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun open(context: Context, owner: IBinder, token: Long, offer: Long, callId: String, rate: Int,
             route: ICallAudioRoute): ParcelFileDescriptor? {
        if (stopping || token <= 0 || offer <= 0 || rate !in setOf(16000, 24000, 44100, 48000) || session != null) return null
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var sockets: Array<ParcelFileDescriptor>? = null
        var death: IBinder.DeathRecipient? = null
        var routeManager: AudioManager? = null
        var stage = "eligibility"
        return try {
            val shellContext = PlaybackAudioContext(context)
            val manager = audioManager(context)
            routeManager = manager
            // TelecomManager.isInEmergencyCall() returns false on IPC failure.
            // Query its service directly so a failed or missing reply closes the bridge.
            val telecomBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.TELECOM_SERVICE) as? IBinder ?: error("telecom service unavailable")
            val telecomService = Class.forName("com.android.internal.telecom.ITelecomService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, telecomBinder)
                ?: error("telecom service unavailable")
            val emergency = Class.forName("com.android.internal.telecom.ITelecomService")
                .getMethod("isInEmergencyCall")
            fun safeCall() = manager.mode == AudioManager.MODE_IN_CALL && emergency.invoke(telecomService) == false
            check(safeCall()) { "regular cellular call unavailable" }
            if (!callIdentity.confirmsNewOffer(callId)) throw UnconfirmedCallIdentity()
            check(AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(manager) == true) {
                "cellular call audio unavailable on this device"
            }
            stage = "select telephony uplink"
            val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            val rates = telephony?.sampleRates
            val rateSupported = rates != null && (rates.isEmpty() || rate in rates)
            val selection = when {
                rates == null -> "no_telephony_output"
                !rateSupported -> "rate_not_advertised"
                else -> "eligible"
            }
            trace("route select token=$token offer=$offer requested_rate=$rate " +
                "output_types=${outputs.map { it.type }.distinct().sorted()} selected_type=${telephony?.type} " +
                "device_rates=${rates?.contentToString()} result=$selection")
            checkNotNull(telephony) { "telephony output unavailable" }
            check(rateSupported) { "telephony output does not support $rate" }
            fun format(mask: Int) = AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            stage = "create downlink"
            val downlink = createDownlink(shellContext, manager, format(AudioFormat.CHANNEL_IN_MONO))
            record = downlink
            stage = "create uplink"
            check(safeCall()) { "regular cellular call ended while opening endpoints" }
            val uplink = createUplink(shellContext, manager, format(AudioFormat.CHANNEL_OUT_MONO))
            track = uplink
            stage = "validate endpoints"
            trace("route format token=$token offer=$offer record_state=${downlink.state} record_rate=${downlink.sampleRate} " +
                "record_mask=0x${downlink.channelConfiguration.toString(16)} record_encoding=${downlink.audioFormat} " +
                "track_state=${uplink.state} track_rate=${uplink.sampleRate} " +
                "track_mask=0x${uplink.channelConfiguration.toString(16)} track_encoding=${uplink.audioFormat}")
            check(downlink.state == AudioRecord.STATE_INITIALIZED && uplink.state == AudioTrack.STATE_INITIALIZED)
            check(downlink.sampleRate == rate && uplink.sampleRate == rate && downlink.channelCount == 1 && uplink.channelCount == 1)
            val preferred = uplink.setPreferredDevice(telephony)
            trace("route preferred token=$token offer=$offer selected_type=${telephony.type} accepted=$preferred")
            check(preferred) { "telephony uplink route rejected" }
            stage = "attach endpoints"
            val pair = ParcelFileDescriptor.createSocketPair()
            sockets = pair
            val ownerDeath = IBinder.DeathRecipient { close(token, "owner_disconnected") }
            death = ownerDeath
            owner.linkToDeath(ownerDeath, 0)
            check(owner.isBinderAlive && safeCall())
            val telecomPid = ShellCallIdentity.recoveryTelecomPid()
            if (!callIdentity.confirmsOpen(callId)) throw UnconfirmedCallIdentity()
            val localRecoveryScope = AtomicReference<CallOutputRecoveryScope?>()
            val journalOwner = Any()
            val endpoint = ShellCallEndpoint(callIdentity, callId, lifetime, ::ensureOutputWatchdog)
            val outputMute = LocalCallMute({ System.nanoTime() / 1_000_000 }, {
                val device = manager.communicationDevice?.type
                manager.mode == AudioManager.MODE_IN_CALL &&
                    (device == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || device == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }, { manager.isStreamMute(AudioManager.STREAM_VOICE_CALL) }, { setHandsetMute(manager, it) },
                { trace("$it token=$token offer=$offer") }, { state ->
                    persistLocalMute(journalOwner, localRecoveryScope, callId, telecomPid, state)
                }, renewRecoveryScope = {
                    val renewed = callRecoveryScope(callId, telecomPid)
                    if (renewed != null && journal?.persistFor(journalOwner, CallOutputJournal.State.RESTORE, renewed) == true) {
                        localRecoveryScope.set(renewed)
                        true
                    } else false
                }, authorizeRestore = { confirmed ->
                    localRecoveryScope.get()?.let { canRestoreCallOutput(it, confirmed, manager) } == true
                }, dispatchCurrent = {
                    runCatching { socketOpen(pair[0].fileDescriptor) }.getOrDefault(false) &&
                        !stopping && session?.endpoint === endpoint && endpoint.isLive() &&
                        callIdentity.muteDispatchCurrent(callId)
                }, commitDispatch = { journal?.commitArm(journalOwner) == true },
                cancelUndispatched = { journal?.abortArm(journalOwner) })
            val active = Session(token, offer, callId, owner, ownerDeath, pair[0], downlink, uplink, endpoint, outputMute)
            if (endpoint.runWhileAuthorized(startBefore = Long.MAX_VALUE) {
                lifetime.whileRunning { session = active } != null
            } != true) throw UnconfirmedCallIdentity()
            ensureOutputWatchdog()
            status = "endpoints_ready_rate_$rate"
            val watchdog = Thread({
                var reason = CALL_NO_LONGER_ELIGIBLE
                try {
                    while (active.endpoint.isLive() && safeCall()) {
                        if (callIdentity.current(allowFallback = true) != callId) {
                            reason = "$IDENTITY_LOST${callIdentity.diagnosticStatus()}"
                            break
                        }
                        SystemClock.sleep(callIdentity.millisUntilRefresh())
                    }
                } catch (error: Exception) { reason = failureStatus("call_state", error) }
                finally { close(active, reason) }
            }, "hypercar-call-state")
            val transport = Thread({
                var reason = "uplink_closed"
                try {
                    transfer(active, rate, manager, route) {
                        val eligible = safeCall()
                        val authorized = callIdentity.matchesCached(callId)
                        if (!authorized) active.endReason.compareAndSet(null, "$IDENTITY_LOST${callIdentity.diagnosticStatus()}")
                        else if (!eligible) active.endReason.compareAndSet(null, CALL_NO_LONGER_ELIGIBLE)
                        authorized && eligible
                    }
                }
                catch (error: Exception) { reason = failureStatus("transfer", error) }
                finally { close(active, reason) }
            }, "hypercar-call-uplink")
            listOf(watchdog, transport).forEach { it.isDaemon = true; it.start() }
            Log.i("HyperCar", "CALL-AUDIO: shell endpoints ready rate=$rate")
            pair[1]
        } catch (error: Exception) {
            close(token)
            death?.let { runCatching { owner.unlinkToDeath(it, 0) } }
            runCatching { record?.release() }
            runCatching { track?.release() }
            sockets?.forEach { runCatching { it.close() } }
            status = if (error is UnconfirmedCallIdentity) OPEN_IDENTITY_UNCONFIRMED
                else failureStatus("${stage.replace(' ', '_')}_rate_$rate", error)
            if (error !is UnconfirmedCallIdentity && stage != "eligibility") {
                val cause = error.cause ?: error
                val platformFrame = cause.stackTrace.firstOrNull { it.className.startsWith("android.media.") }
                trace("route open_failed token=$token offer=$offer endpoint=$status " +
                    "platform=${platformFrame?.let { "${it.className}.${it.methodName}" } ?: "none"}")
                routeManager?.let { traceInputRoutes(it, token, offer) }
            }
            Log.i("HyperCar", "CALL-AUDIO: $status")
            null
        }
    }

    private fun socketOpen(fd: FileDescriptor): Boolean {
        val state = StructPollfd().apply { this.fd = fd; events = 0 }
        return Os.poll(arrayOf(state), 0) == 0 || (state.revents.toInt() and SOCKET_HANGUP) == 0
    }

    private fun transfer(active: Session, rate: Int, manager: AudioManager, route: ICallAudioRoute, safeCall: () -> Boolean) {
        val fd = active.socket.fileDescriptor
        val input = DataInputStream(FileInputStream(fd))
        val socketState = arrayOf(StructPollfd().apply { this.fd = fd; events = 0 })
        fun transportOpen(): Boolean = Os.poll(socketState, 0) == 0 ||
            (socketState[0].revents.toInt() and SOCKET_HANGUP) == 0
        fun ownsEndpoint(): Boolean = active.endpoint.isLive() && safeCall() && transportOpen()
        // Rust sends this byte only after the active session accepts the call stream.
        check(input.read() == 1)
        fun bluetoothReady(): Boolean {
            if (!ownsEndpoint()) return false
            return runCatching { route.mayStart() }.getOrDefault(false).also { allowed ->
                if (!allowed) active.endReason.compareAndSet(null, "phone_route_revoked")
            }
        }
        if (!callIdentity.confirmsAfterRouteCheck(active.callId, ::bluetoothReady) { deadline ->
                active.endpoint.runAudioWhileAuthorized(startBefore = deadline) { active.record.startRecording() } != null
            }) {
            return recordDeniedLease(active, OPEN_IDENTITY_UNCONFIRMED)
        }
        // Opening the recorder can consume the proof's lifetime. Confirm again before playing into the call.
        if (!callIdentity.confirmsStart(active.callId, authorize = { deadline ->
                active.endpoint.runAudioWhileAuthorized(startBefore = deadline) {
                    active.track.play()
                    check(active.record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    check(active.track.playState == AudioTrack.PLAYSTATE_PLAYING)
                } != null
            }, preflight = ::ownsEndpoint)) return recordDeniedLease(active, OPEN_IDENTITY_UNCONFIRMED)
        // Some devices report the actual route only once playback has started. Prime it with silence.
        val silence = ByteArray(rate / 50 * 2)
        if (!ownsEndpoint()) return
        val primed = active.endpoint.runAudioWhileAuthorized {
            active.track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
        } ?: return recordDeniedLease(active)
        val routeStarted = SystemClock.elapsedRealtime()
        val routeDeadline = routeStarted + 300
        while (active.track.routedDevice?.type != AudioDeviceInfo.TYPE_TELEPHONY &&
            SystemClock.elapsedRealtime() < routeDeadline && active.endpoint.isLive()
        ) {
            SystemClock.sleep(10)
        }
        val routedType = active.track.routedDevice?.type
        trace("route start token=${active.token} offer=${active.offer} requested_rate=$rate routed_type=$routedType " +
            "primed_bytes=$primed expected_bytes=${silence.size} wait_ms=${SystemClock.elapsedRealtime() - routeStarted}")
        if (routedType != AudioDeviceInfo.TYPE_TELEPHONY) {
            active.endReason.compareAndSet(null, "uplink_route_rejected_type_$routedType")
            traceInputRoutes(manager, active.token, active.offer)
            Log.w("HyperCar", "CALL-AUDIO: shell uplink route rejected actualType=$routedType rate=$rate")
            error("telephony uplink route unavailable")
        }
        Log.i("HyperCar", "CALL-AUDIO: shell uplink routed to telephony rate=$rate")
        Thread({
            var reason = "downlink_call_no_longer_eligible"
            try {
                val writable = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLOUT.toShort() })
                val frames = if (rate >= 44100) 352 else rate / 50
                val buffer = ByteArray(frames * 2)
                var ready = false
                while (active.endpoint.isLive()) {
                    val count = active.record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0 || count % 2 != 0) {
                        reason = "downlink_read_$count"
                        break
                    }
                    if (!ready) {
                        // A partial startup write never carries the trailing READY byte.
                        val startup = ByteBuffer.allocate(count + 3).putShort(count.toShort())
                            .put(buffer, 0, count).put(1).array()
                        if (!callIdentity.confirmsAfterRouteCheck(active.callId, ::bluetoothReady) { deadline ->
                                active.endpoint.writeFirstFrame(deadline) {
                                    socketOpen(fd) && Os.sendto(fd, startup, 0, startup.size,
                                        MSG_DONTWAIT or MSG_NOSIGNAL, null, 0) == startup.size
                                }
                            }) {
                            reason = OPEN_IDENTITY_UNCONFIRMED
                            break
                        }
                        ready = true
                        continue
                    }
                    if (!sendDownlink(active, fd, writable, buffer, count, safeCall)) break
                }
            } catch (error: Exception) {
                reason = failureStatus("downlink", error)
            } finally { close(active, reason) }
        }, "hypercar-call-downlink").apply { isDaemon = true; start() }
        val buffer = ByteArray(0x7ffe)
        val readable = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        while (ownsEndpoint()) {
            if (Os.poll(readable, 100) == 0) continue
            if (!ownsEndpoint()) return
            val count = try { input.readUnsignedShort() } catch (_: EOFException) { return }
            if (count == STARTED_FRAME) {
                if (!ownsEndpoint() || !active.endpoint.acknowledgeStart()) return recordDeniedLease(active)
                continue
            }
            if (count == OUTPUT_RECEIPT_FRAME) {
                val validUntil = input.readLong()
                if (ownsEndpoint()) active.muteDeadline.set(validUntil)
                continue
            }
            check(count in 2..buffer.size && count % 2 == 0) { "invalid call microphone frame" }
            input.readFully(buffer, 0, count)
            // POLLHUP is reported even if the retired peer left unread PCM in the socket.
            if (!ownsEndpoint()) return
            var offset = 0
            while (offset < count && !manager.isMicrophoneMute) {
                val routedType = active.track.routedDevice?.type
                if (routedType != AudioDeviceInfo.TYPE_TELEPHONY) {
                    active.endReason.compareAndSet(null, "uplink_route_lost_type_$routedType")
                    trace("route lost token=${active.token} offer=${active.offer} requested_rate=$rate routed_type=$routedType")
                    traceInputRoutes(manager, active.token, active.offer)
                    return
                }
                if (!ownsEndpoint()) return
                val written = active.endpoint.runAudioWhileAuthorized {
                    active.track.write(buffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                } ?: return recordDeniedLease(active)
                check(written >= 0) { "call track write=$written" }
                if (written == 0) SystemClock.sleep(5)
                offset += written
            }
        }
    }

    private fun recordDeniedLease(active: Session, fallback: String? = null) {
        val reason = if (!callIdentity.matchesCached(active.callId)) "$IDENTITY_LOST${callIdentity.diagnosticStatus()}"
            else fallback
        if (reason != null) active.endReason.compareAndSet(null, reason)
    }

    private fun sendDownlink(active: Session, fd: FileDescriptor, writable: Array<StructPollfd>,
                             bytes: ByteArray, count: Int, safeCall: () -> Boolean): Boolean {
        var offset = 0
        while (offset < count) {
            if (!active.endpoint.isLive() || !safeCall()) return false
            val sent = active.endpoint.runWhileAuthorized {
                try {
                    Os.sendto(fd, bytes, offset, count - offset,
                        MSG_DONTWAIT or MSG_NOSIGNAL, null, 0)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN) throw error
                    0
                }
            } ?: run {
                recordDeniedLease(active)
                return false
            }
            if (sent == 0 && Os.poll(writable, 50) > 0 &&
                (writable[0].revents.toInt() and SOCKET_HANGUP) != 0
            ) return false
            offset += sent
        }
        return true
    }

    fun revoke(callId: String) {
        val active = session?.takeIf { it.callId == callId } ?: return
        revoke(active, "$IDENTITY_REVOKED${callIdentity.diagnosticStatus()}")
    }

    fun revokeOffer(token: Long, offer: Long) {
        val active = session?.takeIf { it.token == token && it.offer == offer } ?: return
        revoke(active, CONTROLLER_REVOKED)
    }

    private fun revoke(active: Session, reason: String) {
        active.endReason.compareAndSet(null, reason)
        active.endpoint.retire {
            runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        }
    }

    @Synchronized
    fun close(token: Long, reason: String = "controller_closed") {
        session?.takeIf { it.token == token }?.let { close(it, reason) }
    }

    @Synchronized
    private fun close(active: Session, reason: String) {
        if (session !== active) return
        active.endpoint.retire {
            runCatching { Os.shutdown(active.socket.fileDescriptor, OsConstants.SHUT_RDWR) }
        }
        status = active.endReason.get() ?: reason
        val outcome = "identity=${callIdentity.diagnosticStatus()} endpoint=$status"
        synchronized(closedOffers) {
            if (closedOffers.size == 8) closedOffers.removeFirst()
            closedOffers.addLast(ClosedOffer(active.token, active.offer, outcome))
        }
        Log.i("HyperCar", "CALL-AUDIO: shell endpoints stopping token=${active.token} offer=${active.offer} $outcome")
        session = null
        runCatching { active.socket.close() }
        runCatching { active.record.stop() }
        runCatching { active.track.pause() }
        runCatching { active.track.flush() }
        runCatching { active.record.release() }
        runCatching { active.track.release() }
        runCatching { active.owner.unlinkToDeath(active.death, 0) }
    }

    private fun failureStatus(stage: String, error: Exception) =
        "${stage}_failed_${(error.cause ?: error).javaClass.simpleName}"

    companion object {
        // Linux send flags that Android's public OsConstants does not expose.
        private const val MSG_DONTWAIT = 0x40
        private const val MSG_NOSIGNAL = 0x4000
        private const val OUTPUT_RECEIPT_FRAME = 0x8000
        private const val STARTED_FRAME = 0x8001
        private val SOCKET_HANGUP = OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
        const val OPEN_IDENTITY_UNCONFIRMED = "open_identity_unconfirmed"
        const val IDENTITY_REVOKED = "identity_revoked_"
        const val IDENTITY_LOST = "identity_lost_"
        const val CONTROLLER_REVOKED = "controller_revoked"
        const val CALL_NO_LONGER_ELIGIBLE = "call_no_longer_eligible"
    }
}
