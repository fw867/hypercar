package io.github.hypercar.iap2

import io.github.hypercar.navigation.HudSourceWrites
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
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
    fun endpointGeneration() = Iap2EndpointGeneration(registration) { !localStopRequested }
    @Volatile internal var wifiLossPredecessorAttempt: Long? = null
    @Volatile internal var ownedWifiNetwork: Long? = null
    @Volatile var socketClosed = false
        private set

    private enum class End { STOPPING, LOCAL, IO }
    private enum class HudAdmission { ADMITTED, READ_PENDING, REVOKED }
    private val end = AtomicReference<End?>()
    val isCancelled: Boolean get() = end.get() == End.LOCAL
    val endedByIo: Boolean get() = end.get() == End.IO
    val localStopRequested: Boolean get() = when (end.get()) {
        End.STOPPING, End.LOCAL -> true
        else -> false
    }
    @Volatile var channel: Closeable? = null

    private var abortWriteOnStop = false
    private var socketReadPending = false

    @Synchronized fun setSocketReadPending(pending: Boolean) {
        socketReadPending = pending
    }

    fun noteLocalStop() {
        val interruptWrite = synchronized(this) {
            end.compareAndSet(null, End.STOPPING)
            abortWriteOnStop
        }
        if (interruptWrite) endIo()
    }

    /** Serialize MFi bookkeeping or queue admission with a local stop. Never do socket I/O here. */
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
    fun writeOutput(engine: Iap2LinkEngine, write: (ByteArray) -> Unit): Int {
        val containsMfi = engine.outputContainsMfi
        val frames = engine.pendingMfiResults()
        val hudStart = engine.takeSocketWrites().singleOrNull { it.hudFirst }
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return 0
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
                synchronized(this) { abortWriteOnStop = false }
            }
        }
        return bytes.size
    }

    @Synchronized private fun admitHudWrite(): HudAdmission {
        if (end.get() != null || Thread.currentThread().isInterrupted) return HudAdmission.REVOKED
        // The reader has bytes but is waiting for the channel lock held by this writer.
        if (socketReadPending) return HudAdmission.READ_PENDING
        // A stop that follows admission closes this captured socket outside the monitor.
        abortWriteOnStop = true
        return HudAdmission.ADMITTED
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
        synchronized(this) {
            if (end.get() != null || Thread.currentThread().isInterrupted)
                throw InterruptedIOException("RFCOMM 已结束")
            abortWriteOnStop = true
        }
        try {
            write()
        } finally {
            synchronized(this) { abortWriteOnStop = false }
        }
    }

    fun endIo() {
        end.compareAndSet(null, End.IO)
        end.compareAndSet(End.STOPPING, End.LOCAL)
        runCatching { socket.close() }.onSuccess { socketClosed = true }
        onSocketClosed(this)
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
    val preservedRegistrations: Set<Long> = emptySet(),
    val close: () -> Unit,
) {
    val registrationCutoff = RfcommTransport.lastRegistration
    fun noteLocalStop() = mark()
    fun retires(registration: Long): Boolean =
        registration <= registrationCutoff && registration !in preservedRegistrations
}

/** Admission publishes these under the Bluetooth monitor. Workers only clear their own references. */
internal class RfcommConnections<S : Closeable> {
    val session = AtomicReference<RfcommTransport<S>?>()
    val current = AtomicReference<RfcommTransport<S>?>()
    val pending = AtomicReference<RfcommTransport<S>?>()
    private val awaitingHandshake = mutableSetOf<RfcommTransport<S>>()

    @Synchronized fun register(socket: S, wifiLossPredecessorAttempt: Long? = null): RfcommTransport<S> =
        RfcommTransport(socket, ::removePendingHandshake).also {
            it.wifiLossPredecessorAttempt = wifiLossPredecessorAttempt
            awaitingHandshake.add(it)
        }

    @Synchronized private fun removePendingHandshake(owner: RfcommTransport<S>) {
        awaitingHandshake.remove(owner)
    }

    @Synchronized fun admit(owner: RfcommTransport<S>) {
        session.set(owner)
        current.set(owner)
        awaitingHandshake.remove(owner)
    }

    @Synchronized fun captureClose(includeWaiting: Boolean = true,
                                  wifiLossAttempt: Long? = null,
                                  wifiLossNetwork: Long? = null,
                                  afterClose: (List<RfcommTransport<S>>) -> Unit = {}): RfcommClose {
        val published = listOfNotNull(session.get(), pending.get(), current.get())
        val captured = (if (includeWaiting) published + awaitingHandshake else published).distinct()
        val (preserved, owners) = captured.partition {
            wifiLossAttempt != null && it.wifiLossPredecessorAttempt == wifiLossAttempt &&
                (it.ownedWifiNetwork == null || it.ownedWifiNetwork != wifiLossNetwork)
        }
        return RfcommClose({ owners.forEach { it.noteLocalStop() } }, preserved.mapTo(mutableSetOf()) { it.registration }) {
            for (owner in owners) {
                owner.close()
                forget(owner)
            }
            afterClose(owners)
        }
    }

    fun forget(owner: RfcommTransport<S>) {
        session.compareAndSet(owner, null)
        pending.compareAndSet(owner, null)
        current.compareAndSet(owner, null)
    }
}
