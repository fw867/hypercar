package io.github.hypercar.net

import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test

class CtrlPortTest {
    @Test fun newSessionReusesReleasedPort() {
        val port = bindCtrlPort(0) { error(it) }.use { it.localPort }
        bindCtrlPort(port) { error(it) }.use { assertEquals(port, it.localPort) }
    }

    @Test fun occupiedPortFallsBackAndReportsCause() {
        ServerSocket(0).use { occupied ->
            var fallback = ""
            bindCtrlPort(occupied.localPort) { fallback = it }.use {
                assertTrue(it.localPort > 0 && it.localPort != occupied.localPort)
                assertTrue(fallback.startsWith("BindException:"))
            }
        }
    }
}
