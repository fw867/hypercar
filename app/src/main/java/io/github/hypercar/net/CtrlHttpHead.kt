package io.github.hypercar.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/** Preserve the existing chunked read/partial-head behavior, but retain its termination reason. */
internal data class CtrlHttpHead(val text: String, val termination: String) {
    companion object {
        fun read(input: InputStream): CtrlHttpHead {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(2048)
            var reason = "LIMIT"
            while (buffer.size() < 16 * 1024) {
                val count = try { input.read(chunk) }
                catch (_: SocketTimeoutException) { reason = "TIMEOUT"; break }
                catch (e: IOException) { reason = "IO:${e.javaClass.simpleName}"; break }
                if (count < 0) { reason = "EOF"; break }
                buffer.write(chunk, 0, count)
                if (buffer.toString("ISO-8859-1").contains("\r\n\r\n")) { reason = "COMPLETE"; break }
            }
            return CtrlHttpHead(buffer.toString("ISO-8859-1"), reason)
        }
    }
}
