package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        log.offer("first")
        backgroundScope.launch { log.run() }
        enteredWriter.await()
        log.offer("second")
        log.offer("third")
        log.offer("overflow")
        assertEquals(emptyList<String>(), lines)
        release.complete(Unit)
        log.flush()
        assertEquals(listOf("first", "second", "third"), lines.filterNot { it.startsWith("VIDEO-LOG:") })
        assertEquals(1, lines.count { it == "VIDEO-LOG: 队列已满，丢弃 1 条诊断" })
    }
}
