package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class MfiLengthMemoryTest {
    private val baseline = listOf(32, 20, 16)

    @Test fun disconnectsRotateAcrossIndependentConnectionsEvenWithOldFailures() {
        var memory = MfiLengthMemory.Entry(rejected = baseline.toSet())
        val attempts = mutableListOf<Int>()
        repeat(6) {
            val length = MfiLengthMemory.orderAttempts(baseline, memory).first()
            attempts += length
            memory = memory.copy(lastDisconnected = length)
        }
        assertEquals(listOf(32, 20, 16, 32, 20, 16), attempts)
    }

    @Test fun provenResponseRemainsPreferredUntilADisconnect() {
        val memory = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(32, MfiLengthMemory.orderAttempts(baseline, memory).first())
        assertEquals(20, MfiLengthMemory.orderAttempts(baseline, memory.copy(lastDisconnected = 32)).first())
    }
}
