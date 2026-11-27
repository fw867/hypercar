package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDiagnosticLogTest {
    @Test fun aStalledWriterDoesNotBlockProducersAndExportWaitsForAcceptedLines() = runTest {
        val enteredWriter = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lines = mutableListOf<String>()
        val log = MediaDiagnosticLog(capacity = 2) { line ->
            enteredWriter.complete(Unit)
            release.await()
            lines.add(line)
        }
        val first = "PHONE-HID: first"
        val second = "HID-INPUT: second"
        val third = "PHONE-KEY: dropped source=system-carlife"
        assertTrue(MediaDiagnosticLog.queuesNativeLine(first))
        assertTrue(MediaDiagnosticLog.queuesNativeLine(second))
        assertTrue(MediaDiagnosticLog.queuesNativeLine(third))
        assertTrue(MediaDiagnosticLog.queuesNativeLine("EVENT: 车机命令 type=hidSendReport（首次）source=event"))
        assertTrue(MediaDiagnosticLog.queuesNativeLine("TOUCH: 车机(123,456)"))
        assertTrue(MediaDiagnosticLog.queuesInputLine("IAP2-MEDIA: StartHID"))
        assertTrue(MediaDiagnosticLog.queuesInputLine("HS#5 +100ms attempt=2 IAP2-MEDIA: StartHID"))
        assertTrue(MediaDiagnosticLog.queuesInputLine("WIFI-IAP#2: RX phase=READY id=0x6802 body=6B"))
        assertFalse(MediaDiagnosticLog.queuesInputLine("WIFI-IAP#2: RX phase=READY id=0x1d01 body=6B"))
        assertTrue(MediaDiagnosticLog.queuesInputLine("MEDIA-KEY-GATE: result=voice-forward"))
        assertTrue(MediaDiagnosticLog.queuesInputLine("CARUI: console input BACK display=2 failed"))
        assertFalse(MediaDiagnosticLog.queuesNativeLine("EVENT: 车机命令 type=forceKeyFrame（首次）source=event"))
        log.offer(first)
        backgroundScope.launch { log.run() }
        enteredWriter.await()
        log.offer(second)
        log.offer(third)
        log.offer("PHONE-HID: overflow")
        assertEquals(emptyList<String>(), lines)
        release.complete(Unit)
        log.flush()
        assertEquals(listOf(first, second, third), lines.filterNot { it.startsWith("VIDEO-LOG:") })
        assertEquals(1, lines.count { it == "VIDEO-LOG: 队列已满，丢弃 1 条诊断" })
    }
}
