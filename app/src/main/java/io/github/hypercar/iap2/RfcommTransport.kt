package io.github.hypercar.iap2

import io.github.hypercar.navigation.HudSourceWrites
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One lifetime from pending dial or admission through the last channel callback. */
internal class RfcommTransport<S : Closeable>(
    val socket: S,
    private val onSocketClosed: (RfcommTransport<S>) -> Unit = {},
) : Closeable {
    companion object {
        private const val HUD_FIRST_BYTE_TIMEOUT_MILLIS = 1_000L
        private val hudWriteWatchdog = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "iap2-rfcomm-hud-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val registrations = AtomicLong()
        val lastRegistration: Long get() = registrations.get()
    }

    internal val registration = registrations.incrementAndGet()
    fun endpointGeneration() = Iap2EndpointGeneration(registration,
        publisher = { !endpointPublicationRevoked }, current = { !localStopRequested })
    @Volatile private var endpointPublicationRevoked = false
    fun revokeEndpointPublication() { endpointPublicationRevoked = true }
    @Volatile var socketClosed = false
        private set

    private enum class End { STOPPING, LOCAL, IO }
    private enum class HudAdmission { ADMITTED, READ_PENDING, REVOKED }
    private data class IoState(val end: End? = null, val writeCommitted: Boolean = false, val readPending: Boolean = false)
    private val io = AtomicReference(IoState())
    private val closingSocket = AtomicBoolean()
    private val afterClose = AtomicReference<(() -> Unit)?>(null)
    val isCancelled: Boolean get() = io.get().end == End.LOCAL
    val endedByIo: Boolean get() = io.get().end == End.IO
    val localStopRequested: Boolean get() = when (io.get().end) {
        End.STOPPING, End.LOCAL -> true
        else -> false
    }
    @Volatile var channel: Closeable? = null

    fun setSocketReadPending(pending: Boolean) {
        io.updateAndGet { it.copy(readPending = pending) }
    }

    fun noteLocalStop() {
        if (markLocalStop()) endIo()
    }

    fun markLocalStop(): Boolean = io.updateAndGet { state ->
        if (state.end == null) state.copy(end = End.STOPPING) else state
    }.writeCommitted

    /** Serialize bookkeeping, with lock-free cancellation checked at admission. */
    @Synchronized fun <T> commitMfi(action: () -> T): T {
        if (localStopRequested || Thread.currentThread().isInterrupted) throw InterruptedIOException("MFi 已取消")
        return action()
    }

    /** Hold the stop guard for admission only. The drain may block or fail synchronously. */
    fun sendMfiControl(engine: Iap2LinkEngine, bytes: ByteArray, nowMillis: Long, awaitAck: Boolean,
                       result: Boolean, drain: () -> IOException?): Boolean {
        val accepted = try {
            commitMfi { engine.sendMfiControl(bytes, nowMillis, awaitAck, result) }
        } catch (cancelled: InterruptedIOException) {
            if (result) engine.mfiLog?.invoke("MFi RFCOMM cancelled id=0xaa05 before_enqueue")
            throw cancelled
        }
        drain()?.let { throw it }
        return accepted
    }

    /** One writer under the channel lock. A stop may close the socket while write is blocked. */
    fun writeOutputFrom(engine: Iap2LinkEngine, stream: () -> OutputStream): Int =
        writeOutput(engine, prepare = { stream()::write })

    fun writeOutput(engine: Iap2LinkEngine, write: (ByteArray) -> Unit): Int =
        writeOutput(engine, prepare = { write })

    private fun writeOutput(engine: Iap2LinkEngine, prepare: () -> (ByteArray) -> Unit): Int {
        val containsMfi = engine.outputContainsMfi
        val frames = engine.pendingMfiResults()
        val hudStart = engine.takeSocketWrites().singleOrNull { it.hudFirst }
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return 0
        val write = prepare()
        val prefixSize = hudStart?.offset ?: bytes.size
        if (prefixSize != 0) {
            val prefix = if (prefixSize == bytes.size) bytes else bytes.copyOfRange(0, prefixSize)
            if (!containsMfi) {
                writeInterruptibly { write(prefix) }
            } else {
                try {
                    writeMfi { write(prefix) }
                } catch (error: IOException) {
                    val status = if (localStopRequested || Thread.currentThread().isInterrupted) "cancelled" else "write_failed"
                    frames.forEach { engine.mfiLog?.invoke("MFi RFCOMM $status ${it.label}") }
                    throw error
                }
                frames.forEach { engine.mfiLog?.invoke("MFi RFCOMM written ${it.label}") }
            }
        }
        if (hudStart != null) {
            var admission = HudAdmission.REVOKED
            val stamp = HudSourceWrites.acquireIfCurrent {
                if (!engine.canStartRfcommHudWrite(hudStart.ticket)) return@acquireIfCurrent false
                admission = admitHudWrite()
                admission == HudAdmission.ADMITTED
            }
            if (stamp == 0L) {
                engine.socketWriteFinished(hudStart.ticket, null, sourceRevoked = admission != HudAdmission.READ_PENDING)
                return prefixSize
            }
            try {
                try {
                    // The first byte orders source publication against submission. Release
                    // that lock before writing the rest of the frame to the blocking stream.
                    writeFirstHudByte { write(byteArrayOf(bytes[prefixSize])) }
                } finally {
                    HudSourceWrites.release(stamp)
                }
                if (localStopRequested) throw InterruptedIOException("RFCOMM HUD 已取消")
                write(bytes.copyOfRange(prefixSize + 1, bytes.size))
                engine.socketWriteFinished(hudStart.ticket, System.nanoTime() / 1_000_000)
            } finally {
                finishWrite()
            }
        }
        return bytes.size
    }

    private fun admitHudWrite(): HudAdmission {
        if (io.get().end != null || Thread.currentThread().isInterrupted) return HudAdmission.REVOKED
        // The reader has bytes but is waiting for the channel lock held by this writer.
        if (commitWrite(yieldToReader = true)) return HudAdmission.ADMITTED
        val state = io.get()
        return if (state.end == null && state.readPending) HudAdmission.READ_PENDING else HudAdmission.REVOKED
    }

    private fun writeFirstHudByte(write: () -> Unit) {
        val pending = AtomicBoolean(true)
        val timeout = hudWriteWatchdog.schedule({
            // BluetoothSocket.close() aborts the blocked write from outside the channel lock.
            if (pending.compareAndSet(true, false)) endIo()
        }, HUD_FIRST_BYTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        try {
            val writeError = try {
                write()
                null
            } catch (error: IOException) {
                error
            }
            if (!pending.compareAndSet(true, false)) {
                throw IOException("RFCOMM HUD first-byte write timed out", writeError)
            }
            writeError?.let { throw it }
        } finally {
            pending.set(false)
            timeout.cancel(false)
        }
    }

    fun writeMfi(write: () -> Unit) = writeInterruptibly(write)

    private fun writeInterruptibly(write: () -> Unit) {
        if (!commitWrite()) throw InterruptedIOException("RFCOMM 已结束")
        try {
            if (io.get().end != null) throw InterruptedIOException("RFCOMM 已结束")
            write()
        } finally {
            finishWrite()
        }
    }

    // This CAS orders submission against cancellation without holding a lock during socket I/O.
    private fun commitWrite(yieldToReader: Boolean = false): Boolean {
        while (!Thread.currentThread().isInterrupted) {
            val state = io.get()
            if (state.end != null || state.writeCommitted || (yieldToReader && state.readPending)) return false
            if (io.compareAndSet(state, state.copy(writeCommitted = true))) return true
        }
        return false
    }

    private fun finishWrite() { io.updateAndGet { it.copy(writeCommitted = false) } }

    fun endIo() {
        io.updateAndGet { state ->
            when (state.end) {
                null -> state.copy(end = End.IO)
                End.STOPPING -> state.copy(end = End.LOCAL)
                else -> state
            }
        }
        if (socketClosed || !closingSocket.compareAndSet(false, true)) return
        try {
            if (!socketClosed && runCatching { socket.close() }.isSuccess) {
                socketClosed = true
                onSocketClosed(this)
            }
        } finally {
            closingSocket.set(false)
            notifyClosed()
        }
    }

    /** The worker may finish while a different thread is still closing its socket. */
    fun finishWhenSocketClosed(action: () -> Unit) {
        afterClose.set(action)
        notifyClosed()
    }

    private fun notifyClosed() {
        if (socketClosed) afterClose.getAndSet(null)?.invoke()
    }

    fun readFailed(error: Throwable, finishChannel: (Throwable) -> Unit) {
        endIo() // The writer may hold the channel lock until this socket is closed.
        finishChannel(error)
    }

    override fun close() {
        cancelSocket()
        closeChannel()
    }

    fun cancelSocket() {
        noteLocalStop()
        endIo()
    }

    fun finish() {
        endIo()
        closeChannel()
    }

    private fun closeChannel() {
        channel?.let {
            runCatching { it.close() }
            channel = null
        }
    }
}

internal class RfcommClose(
    private val mark: () -> Unit,
    val exactRegistration: Long? = null,
    private val markOnly: () -> Unit = mark,
    val close: () -> Unit,
) {
    val registrationCutoff = if (exactRegistration == null) RfcommTransport.lastRegistration else 0L
    fun noteLocalStop() = mark()
    fun markBeforeDeferredClose() = markOnly()
}

/** Admission publishes these under the Bluetooth monitor. Workers only clear their own references. */
internal class RfcommConnections<S : Closeable> {
    val session = AtomicReference<RfcommTransport<S>?>()
    val current = AtomicReference<RfcommTransport<S>?>()
    val pending = AtomicReference<RfcommTransport<S>?>()
    private val awaitingHandshake = mutableSetOf<RfcommTransport<S>>()

    @Synchronized fun register(socket: S): RfcommTransport<S> =
        RfcommTransport(socket, ::removePendingHandshake).also { awaitingHandshake.add(it) }

    @Synchronized private fun removePendingHandshake(owner: RfcommTransport<S>) {
        awaitingHandshake.remove(owner)
    }

    @Synchronized fun admit(owner: RfcommTransport<S>) {
        session.set(owner)
        current.set(owner)
        awaitingHandshake.remove(owner)
    }

    @Synchronized fun captureClose(includeWaiting: Boolean = true,
                                  afterClose: (List<RfcommTransport<S>>) -> Unit = {}): RfcommClose {
        return snapshot(owners(includeWaiting), null, afterClose)
    }

    @Synchronized fun captureWifiClose(registration: Long): RfcommClose =
        snapshot(owners(includeWaiting = false).filter { it.registration == registration }, registration) {}

    private fun owners(includeWaiting: Boolean): List<RfcommTransport<S>> {
        val published = listOfNotNull(session.get(), pending.get(), current.get())
        return (if (includeWaiting) published + awaitingHandshake else published).distinct()
    }

    private fun snapshot(owners: List<RfcommTransport<S>>, exactRegistration: Long?,
                         afterClose: (List<RfcommTransport<S>>) -> Unit): RfcommClose =
        RfcommClose({ owners.filter { it.markLocalStop() }.forEach { it.endIo() } }, exactRegistration,
            markOnly = { owners.forEach { it.markLocalStop() } }) {
            for (owner in owners) {
                owner.close()
                forget(owner)
            }
            afterClose(owners)
        }

    fun forget(owner: RfcommTransport<S>) {
        session.compareAndSet(owner, null)
        pending.compareAndSet(owner, null)
        current.compareAndSet(owner, null)
    }
}
