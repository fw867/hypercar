package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** One bounded, CRLF-terminated request head; never interprets body bytes as headers. */
internal data class CtrlHttpHead(val text: String, val termination: String) {
    enum class Reply(val status: Int, private val reason: String) {
        OK(200, "OK"),
        BAD_REQUEST(400, "Bad Request"),
        NOT_FOUND(404, "Not Found"),
        REQUEST_TIMEOUT(408, "Request Timeout"),
        STALE_NETWORK(409, "Conflict"),
        HEAD_TOO_LARGE(431, "Request Header Fields Too Large");

        fun encode(): ByteArray =
            "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }

    data class Request(val line: String, val diagnosticHeaders: List<Pair<String, String>>) {
        val receiverId: String? get() = diagnosticHeaders.firstOrNull { it.first == "airplay-receiver-device-id" }?.second
    }

    data class Decision(val reply: Reply, val request: Request? = null)

    fun decide(): Decision {
        when (termination) {
            "LIMIT" -> return Decision(Reply.HEAD_TOO_LARGE)
            "TIMEOUT" -> return Decision(Reply.REQUEST_TIMEOUT)
            "COMPLETE" -> Unit
            else -> return Decision(Reply.BAD_REQUEST)
        }
        if (!text.endsWith(HEAD_END)) return Decision(Reply.BAD_REQUEST)
        val lines = text.dropLast(HEAD_END.length).split("\r\n")
        val line = lines.first()
        val fields = requestFields(line) ?: return Decision(Reply.BAD_REQUEST)
        val diagnostics = mutableListOf<Pair<String, String>>()
        for (header in lines.drop(1)) {
            val colon = header.indexOf(':')
            if (colon <= 0 || !header.substring(0, colon).all(::isTokenChar)) return Decision(Reply.BAD_REQUEST)
            val value = header.substring(colon + 1)
            if (value.any { it != '\t' && (it < ' ' || it == '\u007f') }) return Decision(Reply.BAD_REQUEST)
            val name = header.substring(0, colon).lowercase()
            if (name in DIAGNOSTIC_HEADERS && diagnostics.none { it.first == name }) {
                diagnostics += name to value.trim(' ', '\t').take(256)
            }
        }
        val reply = if (isConnect(fields)) Reply.OK else Reply.NOT_FOUND
        return Decision(reply, Request(line, diagnostics))
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024
        private const val HEAD_END = "\r\n\r\n"
        private val DIAGNOSTIC_HEADERS = setOf("host", "user-agent", "airplay-receiver-device-id")

        private fun isTokenChar(ch: Char): Boolean =
            ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z' || ch in "!#$%&'*+-.^_`|~"

        private fun requestFields(line: String): List<String>? = line.split(' ', limit = 3).takeIf { fields ->
            fields.size == 3 && fields[0].isNotEmpty() && fields[0].all(::isTokenChar) &&
                fields[1].isNotEmpty() && fields[1].all { it in '!'..'~' && it != '#' } &&
                (fields[2] == "HTTP/1.0" || fields[2] == "HTTP/1.1")
        }

        private fun isConnect(fields: List<String>): Boolean =
            fields[0] == "GET" && fields[1].substringBefore('?') == "/ctrl-int/1/connect"

        internal fun isConnectRequestLine(line: String): Boolean = requestFields(line)?.let(::isConnect) == true

        // The production input is buffered. Stop at the first delimiter even if body bytes arrived with the head.
        fun read(input: InputStream, withinDeadline: () -> Boolean = { true }): CtrlHttpHead {
            val buffer = ByteArrayOutputStream()
            var reason = "LIMIT"
            var tail = 0
            while (buffer.size() < MAX_BYTES) {
                if (!withinDeadline()) { reason = "TIMEOUT"; break }
                val next = try { input.read() }
                catch (_: SocketTimeoutException) { reason = "TIMEOUT"; break }
                catch (e: IOException) { reason = "IO:${e.javaClass.simpleName}"; break }
                if (next < 0) { reason = "EOF"; break }
                buffer.write(next)
                tail = (tail shl 8) or next
                if (tail == 0x0d0a0d0a) {
                    reason = if (withinDeadline()) "COMPLETE" else "TIMEOUT"
                    break
                }
            }
            return CtrlHttpHead(buffer.toString("ISO-8859-1"), reason)
        }
    }
}
