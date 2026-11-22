package io.github.hypercar.session

import io.github.hypercar.shizuku.ExecResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarWithStartupDiagnosticsTest {
    @Test fun reportsPassivePortStateAndSeparatesUnreadableFromAbsent() {
        fun state(out: String, code: Int = 0) =
            CarWithStartupDiagnostics.socketState(ExecResult(code, out, ""))
        assertEquals("LISTEN@0100007F:1C48", state("0100007F:1C48 0A"))
        assertEquals("ESTABLISHED@0100007F:1C48", state("0100007F:1C48 01"))
        assertEquals("no_entry", state(""))
        assertEquals("unavailable(exit=1)", state("", 1))
    }

    private fun tcpFile(content: String): File =
        Files.createTempFile("hc-net", ".txt").toFile().apply { writeText(content) }

    private val TCP_SAMPLE = """
        sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
         0: 0100007F:1C48 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0
         1: 0100007F:2030 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12346 1 0000000000000000 100 0 0 10 0
         2: 0100007F:2031 0100007F:1C48 01 00000000:00000000 00:00000000 00000000     0        0 12347 1 0000000000000000 100 0 0 10 0
    """.trimIndent()

    @Test fun localPortStateDetectsListen() {
        val tcp = tcpFile(TCP_SAMPLE)
        val tcp6 = tcpFile("sl local_address rem_address st\n")
        val tables = listOf(tcp.path, tcp6.path)
        // 7240 = 0x1C48
        assertEquals("LISTEN@0100007F:1C48", CarWithStartupDiagnostics.localPortState(7240, tables))
        // 8240 = 0x2030（LISTEN）+ 0x2031 作为 rem_address 不应误配
        assertEquals("LISTEN@0100007F:2030", CarWithStartupDiagnostics.localPortState(8240, tables))
        assertTrue(CarWithStartupDiagnostics.isListening(7240, tables))
        assertFalse(CarWithStartupDiagnostics.isListening(9440, tables))
        assertEquals("no_entry", CarWithStartupDiagnostics.localPortState(9440, tables))
    }

    @Test fun localPortStateEstablishedCountsAsListening() {
        val tcp = tcpFile(
            "sl local_address rem_address st\n" +
            "0: 0100007F:1C48 0100007F:D000 01 00000000:00000000 00:00000000 00000000 0 0 1 1 0000 100 0 0 10 0\n"
        )
        assertEquals("ESTABLISHED@0100007F:1C48", CarWithStartupDiagnostics.localPortState(7240, listOf(tcp.path)))
        assertTrue(CarWithStartupDiagnostics.isListening(7240, listOf(tcp.path)))
    }

    @Test fun localPortStateUnreadableReturnsNull() {
        val state = CarWithStartupDiagnostics.localPortState(7240, listOf("/nonexistent/proc/tcp"))
        assertNull(state)
        assertFalse(CarWithStartupDiagnostics.isListening(7240, listOf("/nonexistent/proc/tcp")))
    }
}
