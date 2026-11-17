package io.github.hypercar.session

import io.github.hypercar.shizuku.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CarWithStartupDiagnosticsTest {
    @Test fun reportsPassivePortStateAndSeparatesUnreadableFromAbsent() {
        fun state(out: String, code: Int = 0) =
            CarWithStartupDiagnostics.socketState(ExecResult(code, out, ""))
        assertEquals("LISTEN@0100007F:1C48", state("0100007F:1C48 0A"))
        assertEquals("ESTABLISHED@0100007F:1C48", state("0100007F:1C48 01"))
        assertEquals("no_entry", state(""))
        assertEquals("unavailable(exit=1)", state("", 1))
    }
}
