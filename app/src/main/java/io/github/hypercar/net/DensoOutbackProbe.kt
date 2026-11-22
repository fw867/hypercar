package io.github.hypercar.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One diagnostic request. Endpoint selection and pairing stay with the normal invitation flow. */
internal object DensoOutbackProbe {
    private const val INFO_TIMEOUT_MS = 2_000

    fun matches(model: String?, manufacturer: String?): Boolean =
        model.equals("Outback", ignoreCase = true) && manufacturer.equals("DENSO", ignoreCase = true)

    suspend fun probe(
        mayProbe: () -> Boolean,
        discover: suspend () -> AirplayServiceProbe.Endpoint?,
        requestInfo: suspend (AirplayServiceProbe.Endpoint) -> Int?,
    ): String {
        if (!mayProbe()) return "skipped"
        val endpoint = discover()
        if (!mayProbe()) return "skipped"
        if (endpoint == null) return "mdns_not_found"
        val target = "target=${endpoint.host} port=${endpoint.port}"
        val status = try {
            requestInfo(endpoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return "$target error=${error.javaClass.simpleName} ${error.message.orEmpty()}"
        }
        if (!mayProbe()) return "skipped"
        return if (status == null) "$target timeout" else "$target status=$status"
    }

    suspend fun requestInfo(
        endpoint: AirplayServiceProbe.Endpoint, open: () -> Socket, mayProbe: () -> Boolean,
    ): Int? = withTimeoutOrNull(INFO_TIMEOUT_MS.toLong()) {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { result ->
                try {
                    open().use { socket ->
                        result.invokeOnCancellation { runCatching { socket.close() } }
                        if (!result.isActive || !mayProbe()) {
                            result.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        socket.soTimeout = INFO_TIMEOUT_MS
                        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), INFO_TIMEOUT_MS)
                        if (!result.isActive || !mayProbe()) {
                            result.resume(null)
                            return@suspendCancellableCoroutine
                        }
                        val body = EMPTY_PLIST
                        val request = "GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/770.8.1\r\n" +
                            "Content-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(request.toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                        val line = ByteArrayOutputStream()
                        val reader = socket.getInputStream()
                        repeat(512) {
                            val byte = reader.read()
                            if (byte < 0) throw IOException("missing RTSP status")
                            if (byte == '\n'.code) {
                                val status = RTSP_STATUS.find(line.toString("US-ASCII"))?.groupValues?.get(1)?.toInt()
                                    ?: throw IOException("invalid RTSP status")
                                result.resume(status)
                                return@suspendCancellableCoroutine
                            }
                            line.write(byte)
                        }
                        throw IOException("RTSP status too long")
                    }
                } catch (error: Exception) {
                    if (result.isActive) result.resumeWithException(error)
                }
            }
        }
    }

    private val RTSP_STATUS = Regex("""^RTSP/1\.0 (\d{3})(?:\s|$)""")
    // Binary property list containing an empty dictionary, as used by the AirPlay engine.
    private val EMPTY_PLIST = ("62706c6973743030d008" + "0000000000000101000000000000000100000000000000000000000000000009")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
