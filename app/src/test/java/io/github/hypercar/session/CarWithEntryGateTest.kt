package io.github.hypercar.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CarWithEntryGateTest {
    @Test fun pauseDuringShizukuFailureDiscardsFallbackEvenAfterSameAddressReinvites() = runBlocking {
        for (reinvite in listOf(false, true)) {
            val gate = CarWithEntryGate()
            val inShell = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Boolean>()
            val intents = mutableListOf<Long>()
            gate.begin(11)
            assertTrue(gate.claimReopen(11))
            val old = async {
                gate.launch(11, { inShell.complete(Unit); result.await() }, { intents += 11L })
            }
            inShell.await()
            gate.retire(11)
            if (reinvite) gate.begin(12)
            result.complete(false)
            old.await()
            assertTrue(intents.isEmpty())
            assertFalse(gate.claimReopen(11))
            if (reinvite) {
                gate.retire(11)
                assertTrue(gate.claimReopen(12))
                gate.launch(12, { false }, { intents += 12L })
                assertEquals(listOf(12L), intents)
                assertFalse(gate.claimReopen(12))
            }
        }
    }

    @Test fun mediaReattachKeepsOneReopenAndPauseBeforeDispatchSuppressesBothLaunchPaths() = runBlocking {
        val gate = CarWithEntryGate()
        gate.begin(21)
        assertTrue(gate.claimReopen(21))
        assertFalse(gate.claimReopen(21))
        gate.retire(21)
        gate.launch(21, { error("stale Shizuku launch") }, { error("stale intent") })
        gate.begin(22)
        assertTrue(gate.claimReopen(22))
        assertTrue(gate.launch(22, { true }, { error("duplicate intent") }))
    }
}
