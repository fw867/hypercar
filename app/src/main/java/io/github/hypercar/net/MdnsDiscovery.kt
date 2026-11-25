package io.github.hypercar.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Dual-family discovery loop. All IO is multicast on transports supplied by the bound network. */
internal class MdnsDiscovery(private val log: (String) -> Unit) {
    data class Reply(val bytes: ByteArray, val scopeId: Int = 0)
    interface Transport : AutoCloseable {
        fun send(query: ByteArray)
        fun receive(timeoutMillis: Int): Reply?
    }

    suspend fun browse(timeoutMillis: Long, open: (Int) -> Transport,
        accept: (AirplayDnsRecords.Endpoint) -> Boolean): AirplayDnsRecords.Endpoint? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val guard = Any()
            val transports = mutableListOf<Transport>()
            fun closeAll() = synchronized(guard) {
                transports.forEach { runCatching { it.close() } }
                transports.clear()
            }
            // Cancellation closes blocked receives directly, rather than waiting for an IO timeout.
            continuation.invokeOnCancellation { closeAll() }
            try {
                val deadline = System.nanoTime() / 1_000_000 + timeoutMillis.coerceAtLeast(0)
                for (family in listOf(4, 6)) {
                    if (!continuation.isActive) break
                    try {
                        val transport = open(family)
                        synchronized(guard) {
                            if (continuation.isActive) transports += transport else transport.close()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { log("MDNS: IPv$family unavailable: ${error.message}") }
                }
                val records = AirplayDnsRecords()
                val attempts = mutableMapOf<AirplayDnsRecords.Question, Pair<Long, Int>>()
                var collectUntil = Long.MAX_VALUE
                while (continuation.isActive && System.nanoTime() / 1_000_000 < deadline) {
                    val active = synchronized(guard) { transports.toList() }
                    if (active.isEmpty()) break
                    val now = System.nanoTime() / 1_000_000
                    val due = records.pendingQuestions().filter { question ->
                        val previous = attempts[question]
                        previous == null || now - previous.first >= minOf(1_000L, 300L shl minOf(previous.second - 1, 2))
                    }
                    if (due.isNotEmpty()) {
                        // Keep datagrams small when several receivers advertise at once.
                        due.chunked(8).forEach { questions ->
                            val query = AirplayDnsRecords.query(questions)
                            active.forEach { transport ->
                                try { transport.send(query) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) {
                                    log("MDNS: query failed: ${error.message}")
                                    runCatching { transport.close() }
                                    synchronized(guard) { transports.remove(transport) }
                                }
                            }
                        }
                        due.forEach { attempts[it] = now to ((attempts[it]?.second ?: 0) + 1) }
                    }
                    for (transport in active) {
                        if (!continuation.isActive) break
                        val remaining = deadline - System.nanoTime() / 1_000_000
                        if (remaining <= 0) break
                        try {
                            val reply = transport.receive(minOf(50L, remaining).toInt())
                            if (reply != null) {
                                records.accept(reply.bytes, reply.bytes.size, reply.scopeId)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (continuation.isActive) log("MDNS: receive failed: ${error.message}")
                            runCatching { transport.close() }
                            synchronized(guard) { transports.remove(transport) }
                        }
                    }
                    if (records.first(accept) == null) {
                        collectUntil = Long.MAX_VALUE
                    } else {
                        // A usable A may precede a separately queried AAAA (or another A).
                        // Give a newly available receiver the same bounded collection window.
                        val collectedAt = System.nanoTime() / 1_000_000
                        if (collectUntil == Long.MAX_VALUE) collectUntil = minOf(deadline, collectedAt + 250)
                        if (collectedAt >= collectUntil) break
                    }
                }
                if (continuation.isActive) continuation.resume(records.first(accept))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { closeAll() }
        }
    }
}
