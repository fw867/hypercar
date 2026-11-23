package io.github.hypercar.net

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch

class CtrlDiscoveryTraceTest {
    @Test fun markerIncludesRecentHistoryAndAnExactClockAnchor() = runBlocking {
        var elapsed = 100L
        val lines = mutableListOf<String>()
        val wroteThree = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val trace = CtrlDiscoveryTrace({ elapsed }, { 1_000L + elapsed }, scope) {
                lines.add(it)
                if (lines.size == 3) wroteThree.complete(Unit)
            }
            trace.record("query=old")
            elapsed = 16_000
            trace.record("query=recent")
            trace.mark()
            elapsed += 2
            trace.record("srv=phone.local:49152")
            elapsed += 30_001
            trace.record("query=outside")

            withTimeout(2_000) { wroteThree.await() }
            assertEquals(3, lines.size)
            assertEquals("CTRL-CLICK: marker=1 epochMs=17000 elapsedMs=16000", lines[0])
            assertEquals("CTRL-CLICK: marker=1 deltaMs=0 epochMs=17000 elapsedMs=16000 query=recent", lines[1])
            assertEquals("CTRL-CLICK: marker=1 deltaMs=2 epochMs=17002 elapsedMs=16002 srv=phone.local:49152", lines[2])
        } finally { scope.cancel() }
    }

    @Test fun overlappingMarkersKeepEndpointChangesUntilTheirOwnDeadline() = runBlocking {
        var now = 0L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lines = mutableListOf<String>()
        try {
            val trace = CtrlDiscoveryTrace({ now }, { 100_000 + now }, scope, lines::add)
            trace.mark(clickState = "event=click-state currentPort=37611")
            now = 5_000
            trace.mark(clickState = "event=click-state currentPort=37611")
            repeat(140) { trace.record("event=query") }
            now = 30_000
            trace.record("event=listener-close closedPort=37611")
            now++
            trace.record("event=listener-open currentPort=45678")
            assertEquals(listOf(1, 2), lines.filter { "event=listener-close" in it }.map { it.substringAfter("marker=").substringBefore(" ").toInt() })
            assertEquals(1, lines.count { "marker=2 " in it && "event=listener-open" in it })
            assertEquals(0, lines.count { "marker=1 " in it && "event=listener-open" in it })
        } finally { scope.cancel() }
    }

    @Test fun recordingInvitationDoesNotWaitForDiskWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CountDownLatch(1)
        val invitationWritten = CompletableDeferred<Unit>()
        try {
            val trace = CtrlDiscoveryTrace({ 10 }, { 20 }, scope) {
                if ("deltaMs" !in it) {
                    writerStarted.complete(Unit)
                    releaseWriter.await()
                } else if ("event=tcp-accepted" in it) invitationWritten.complete(Unit)
            }
            trace.mark()
            withTimeout(2_000) { writerStarted.await() }
            val producerDone = CompletableDeferred<Unit>()
            scope.launch {
                trace.record("event=tcp-accepted")
                producerDone.complete(Unit)
            }
            withTimeout(2_000) { producerDone.await() }
            releaseWriter.countDown()
            withTimeout(2_000) { invitationWritten.await() }
        } finally {
            releaseWriter.countDown()
            scope.cancel()
        }
    }
}
