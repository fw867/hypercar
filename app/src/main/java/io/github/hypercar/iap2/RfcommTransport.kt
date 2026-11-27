package io.github.hypercar.iap2

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One lifetime from pending dial or admission through the last channel callback. */
internal class RfcommTransport<S : Closeable>(
    val socket: S,
    private val onSocketClosed: (RfcommTransport<S>) -> Unit = {},
) : Closeable {
    companion object {
        private val registrations = AtomicLong()
        val lastRegistration: Long get() = registrations.get()
    }

    private val registration = registrations.incrementAndGet()
    fun endpointGeneration() = Iap2EndpointGeneration(registration) { !localStopRequested }

    private enum class End { STOPPING, LOCAL, IO }
    private val end = AtomicReference<End?>()
    val isCancelled: Boolean get() = end.get() == End.LOCAL
    val localStopRequested: Boolean get() = when (end.get()) {
        End.STOPPING, End.LOCAL -> true
        else -> false
    }
    @Volatile var channel: Closeable? = null

    private var writingMfi = false

    fun noteLocalStop() {
        val interruptWrite = synchronized(this) {
            end.compareAndSet(null, End.STOPPING)
            writingMfi
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
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return 0
        if (!containsMfi) {
            write(bytes)
        } else {
            try {
                writeMfi { write(bytes) }
            } catch (error: IOException) {
                val status = if (localStopRequested || Thread.currentThread().isInterrupted) "cancelled" else "write_failed"
                frames.forEach { engine.mfiLog?.invoke("MFi RFCOMM $status ${it.label}") }
                throw error
            }
            frames.forEach { engine.mfiLog?.invoke("MFi RFCOMM written ${it.label}") }
        }
        return bytes.size
    }

    fun writeMfi(write: () -> Unit) {
        commitMfi { writingMfi = true }
        try {
            write()
        } finally {
            synchronized(this) { writingMfi = false }
        }
    }

    fun endIo() {
        end.compareAndSet(null, End.IO)
        end.compareAndSet(End.STOPPING, End.LOCAL)
        runCatching { socket.close() }
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

internal class RfcommClose(private val mark: () -> Unit, val close: () -> Unit) {
    val registrationCutoff = RfcommTransport.lastRegistration
    fun noteLocalStop() = mark()
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
        val published = listOfNotNull(session.get(), pending.get(), current.get())
        val owners = (if (includeWaiting) published + awaitingHandshake else published).distinct()
        return RfcommClose({ owners.forEach { it.noteLocalStop() } }) {
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
