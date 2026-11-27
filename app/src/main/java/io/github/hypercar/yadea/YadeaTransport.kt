package io.github.hypercar.yadea

import android.net.Network
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** A single blocking session. Only [close] may run concurrently with the session thread. */
internal class YadeaTransport : Closeable {
    private val lifecycle = Any()
    private var closed = false
    private var sockets: Sockets? = null

    @Volatile private var session: Session? = null

    fun connect(host: String, tcpPort: Int = 8888, udpPort: Int = 8889, network: Network? = null): YadeaDisplay {
        require(host.isNotBlank()) { "Yadea host is empty" }
        require(tcpPort in 1..65535 && udpPort in 1..65535) { "Invalid Yadea port" }
        val opened = synchronized(lifecycle) {
            check(!closed && sockets == null) { "YadeaTransport can only be used for one session" }
            val tcp = Socket()
            try {
                Sockets(tcp, DatagramSocket(null)).also { sockets = it }
            } catch (error: Throwable) {
                runCatching { tcp.close() }
                throw error
            }
        }

        try {
            val address = if (network == null) InetAddress.getByName(host) else network.getByName(host)
            network?.bindSocket(opened.tcp)
            network?.bindSocket(opened.udp)
            opened.tcp.tcpNoDelay = true
            opened.tcp.connect(InetSocketAddress(address, tcpPort), CONNECT_TIMEOUT_MS)
            opened.udp.connect(InetSocketAddress(address, udpPort))

            val active = Session(opened)
            active.write(YadeaWire.startup(LocalDateTime.now()))
            active.lastHeartbeatNanos = System.nanoTime()
            val response = active.awaitReply(YadeaWire.START_RESPONSE, START_TIMEOUT_MS, minPayloadBytes = 8)
            val display = response?.let(YadeaWire::display) ?: YadeaWire.fallbackDisplay
            synchronized(lifecycle) {
                if (closed) throw SocketException("Yadea session was closed")
                session = active
            }
            return display
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** The caller supplies an encoded frame and paces delivery at the returned display FPS. */
    fun sendJpeg(jpeg: ByteArray, capturedAtMs: Long = System.currentTimeMillis()) {
        require(jpeg.isNotEmpty()) { "Yadea JPEG is empty" }
        val active = activeSession()
        try {
            YadeaWire.forEachImageDatagram(jpeg, active.nextSequence++, capturedAtMs) { datagram ->
                active.sockets.udp.send(DatagramPacket(datagram, datagram.size))
            }
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    /** Call regularly on the session thread, including while the image source is idle. */
    fun checkConnection() {
        val active = activeSession()
        try {
            active.drainReplies()
            active.readReplies(HEALTH_READ_TIMEOUT_MS)
            active.drainReplies()
            if (System.nanoTime() - active.lastHeartbeatNanos >= HEARTBEAT_INTERVAL_NANOS) {
                active.write(YadeaWire.heartbeat(LocalDateTime.now()))
                active.lastHeartbeatNanos = System.nanoTime()
            }
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    /** Send NAV_END and wait briefly for its reply before releasing both sockets. */
    fun finish() {
        try {
            val active = session ?: return
            active.write(YadeaWire.end())
            try {
                active.awaitReply(YadeaWire.END_RESPONSE, END_TIMEOUT_MS)
            } catch (_: EOFException) {
                // A peer that has already disconnected cannot acknowledge NAV_END.
            }
        } finally {
            close()
        }
    }

    override fun close() {
        val opened = synchronized(lifecycle) {
            closed = true
            session = null
            sockets.also { sockets = null }
        } ?: return
        try {
            opened.tcp.close()
        } catch (_: IOException) {
            // Closing UDP is still required if TCP close reports an error.
        } finally {
            opened.udp.close()
        }
    }

    private fun activeSession(): Session = session ?: throw SocketException("Yadea session is not connected")

    private class Sockets(val tcp: Socket, val udp: DatagramSocket)

    private class Session(val sockets: Sockets) {
        private val input = sockets.tcp.getInputStream()
        private val output = sockets.tcp.getOutputStream()
        private val reader = YadeaWire.ReplyReader()
        var lastHeartbeatNanos = 0L
        var nextSequence = 1

        fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        fun awaitReply(command: Int, timeoutMs: Int, minPayloadBytes: Int = 0): ByteArray? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (true) {
                var reply = pollReply()
                while (reply != null) {
                    if (reply.command == command && reply.payload.size >= minPayloadBytes) return reply.payload
                    reply = pollReply()
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val readTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).toInt().coerceAtLeast(1)
                readReplies(readTimeout)
            }
        }

        fun readReplies(timeoutMs: Int) {
            if (sockets.tcp.soTimeout != timeoutMs) sockets.tcp.soTimeout = timeoutMs
            try {
                reader.readFrom(input)
            } catch (_: SocketTimeoutException) {
                // A quiet TCP connection is healthy. The caller owns the overall deadline.
            }
        }

        fun drainReplies() {
            while (pollReply() != null) {
                // Status and rate replies have no action in the JPEG sending session.
            }
        }

        private fun pollReply(): YadeaWire.Reply? {
            val reply = reader.poll() ?: return null
            if (reply.command == YadeaWire.NEGATIVE_RESPONSE) {
                val code = reply.payload.firstOrNull()?.let { "0x${(it.toInt() and 0xFF).toString(16)}" }
                throw IOException("Yadea instrument rejected the request (NRC ${code ?: "unknown"})")
            }
            return reply
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val START_TIMEOUT_MS = 8_000
        const val END_TIMEOUT_MS = 5_000
        const val HEALTH_READ_TIMEOUT_MS = 1
        const val HEARTBEAT_INTERVAL_NANOS = 1_000_000_000L
    }
}
