package io.github.hypercar.net

import java.io.ByteArrayInputStream
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

    @Test fun completeHeaderAndSizeLimitAreDistinct() {
        val complete = "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: car\r\n\r\n"
        assertEquals("COMPLETE", CtrlHttpHead.read(ByteArrayInputStream(complete.toByteArray())).termination)
        assertEquals("LIMIT", CtrlHttpHead.read(ByteArrayInputStream(ByteArray(17000) { 65 })).termination)
    }
}
