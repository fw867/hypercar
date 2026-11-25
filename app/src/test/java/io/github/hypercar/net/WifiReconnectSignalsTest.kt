package io.github.hypercar.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiReconnectSignalsTest {
    @Test fun addressUpdateDuringTheFirstCheckTriggersOneMoreCheck() = runTest {
        val signals = WifiReconnectSignals()
        val firstCheck = CompletableDeferred<Unit>()
        val releaseFirstCheck = CompletableDeferred<Unit>()
        val nextCheck = CompletableDeferred<Unit>()
        var checks = 0
        val worker = backgroundScope.launch {
            signals.collect {
                checks++
                if (checks == 1) {
                    firstCheck.complete(Unit)
                    releaseFirstCheck.await()
                } else nextCheck.complete(Unit)
            }
        }
        signals.signal()
        firstCheck.await()
        repeat(3) { signals.signal() }
        releaseFirstCheck.complete(Unit)
        nextCheck.await()
        assertEquals(2, checks)
        worker.cancel()
    }
}
