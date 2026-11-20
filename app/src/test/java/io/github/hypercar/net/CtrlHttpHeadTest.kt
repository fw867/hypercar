package io.github.hypercar.net

import java.io.ByteArrayInputStream
import io.github.hypercar.net.CtrlHttpHead.Reply
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class CtrlHttpHeadTest {
    @Test fun eofPreservesEmptyAndPartialHeaders() {
        for (text in listOf("", "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: car")) {
            val head = CtrlHttpHead.read(ByteArrayInputStream(text.toByteArray()))
            assertEquals(text, head.text); assertEquals("EOF", head.termination)
        }
    }

    @Test(timeout = 4000) fun timeoutIsDistinctFromEofForEmptyAndPartialHeaders() {
        for (text in listOf("", "GET /ctrl-int/1/connect HTTP/1.1\r\n")) {
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { peer ->
                    server.accept().use { socket ->
                        peer.getOutputStream().write(text.toByteArray()); peer.getOutputStream().flush()
                        socket.soTimeout = 100
                        val head = CtrlHttpHead.read(socket.getInputStream())
                        assertEquals(text, head.text); assertEquals("TIMEOUT", head.termination)
                    }
                }
            }
        }
    }

    @Test fun completeConnectGetsExactlyAnEmpty200AndNoBodyBytesAreConsumed() {
        val head = "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: phone.local.\r\nAirPlay-Receiver-Device-ID: 12345\r\n\r\n"
        val trailing = "AirPlay-Receiver-Device-ID: ignored\r\n"
        val input = ByteArrayInputStream((head + trailing).toByteArray())
        val result = CtrlHttpHead.read(input)
        assertEquals(head, result.text)
        assertEquals("COMPLETE", result.termination)
        val decision = result.decide()
        assertEquals(Reply.OK, decision.reply)
        assertEquals("12345", decision.request?.receiverId)
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", decision.reply.encode().toString(Charsets.US_ASCII))
        assertEquals(trailing, input.readBytes().toString(Charsets.US_ASCII))
        assertEquals(Reply.OK, read("GET /ctrl-int/1/connect?x=1 HTTP/1.0\r\n\r\n").decide().reply)
    }

    @Test fun onlyCompleteWellFormedConnectCanSucceed() {
        val start = "GET /ctrl-int/1/connect HTTP/1.1"
        for (bad in listOf(start, "$start\r\nHost: phone", "$start\nHost: phone\n\n", "$start\nHost: phone\r\n\r\n",
            "$start\r\n Host: phone\r\n\r\n", "$start\r\nHost : phone\r\n\r\n", "$start\r\nHost: phone\u0000\r\n\r\n",
            "GET /ctrl-int/1/connect HTTP/garbage\r\n\r\n")) {
            assertEquals(bad, Reply.BAD_REQUEST, read(bad).decide().reply)
        }
        for (other in listOf("PUT /ctrl-int/1/connect HTTP/1.1", "GET /ctrl-int/1/disconnect HTTP/1.1",
            "GET /ctrl-int/1/connected HTTP/1.1")) {
            assertEquals(other, Reply.NOT_FOUND, read("$other\r\nHost: phone\r\n\r\n").decide().reply)
        }
    }

    @Test fun exactReadBudgetAndDeadlineNeverMistakePartialConnectForSuccess() {
        val prefix = "GET /ctrl-int/1/connect HTTP/1.1\r\nX-Pad: "
        val capped = prefix + "A".repeat(16 * 1024 - prefix.length - 4) + "\r\n\r\n"
        assertEquals(Reply.OK, read(capped).decide().reply)
        val limited = read(capped.replace("X-Pad: ", "X-Pad: A"))
        assertEquals(16 * 1024, limited.text.length)
        assertEquals("LIMIT", limited.termination)
        assertEquals(Reply.HEAD_TOO_LARGE, limited.decide().reply)
        var budget = prefix.length
        val timedOut = CtrlHttpHead.read(ByteArrayInputStream(capped.toByteArray())) { budget-- > 0 }
        assertEquals(prefix, timedOut.text)
        assertEquals(Reply.REQUEST_TIMEOUT, timedOut.decide().reply)
        val complete = "GET /ctrl-int/1/connect HTTP/1.1\r\n\r\n"
        budget = complete.length
        val lateTerminator = CtrlHttpHead.read(ByteArrayInputStream(complete.toByteArray())) { budget-- > 0 }
        assertEquals(complete, lateTerminator.text)
        assertEquals(Reply.REQUEST_TIMEOUT, lateTerminator.decide().reply)
    }

    private fun read(text: String) = CtrlHttpHead.read(ByteArrayInputStream(text.toByteArray()))
}
