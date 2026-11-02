package io.github.hypercar.iap2

import org.junit.Assert.*
import org.junit.Test

class MfiLengthMemoryTest {
    @Test fun sameModelOnDifferentCarsCannotShareSuccessOrFailureHistory() {
        val first = MfiLengthMemory.receiverKey("BMW MGU-55", "00:11:22:33:44:55")
        val second = MfiLengthMemory.receiverKey("BMW MGU-55", "00:11:22:33:44:66")
        MfiLengthMemory.clearAll(null)
        try {
            MfiLengthMemory.recordSuccess(null, first, 20)
            MfiLengthMemory.recordDisconnected(null, first, 32)
            assertEquals(20, MfiLengthMemory.load(null, first).succeeded)
            assertTrue(MfiLengthMemory.load(null, second).isEmpty)
        } finally { MfiLengthMemory.clearAll(null) }
    }

    @Test fun peerKeysNormalizeAddressesButSeparateTransportAndLongIdentities() {
        val key = MfiLengthMemory.receiverKey("BMW MGU", "AA:BB:CC:DD:EE:FF")
        assertEquals(key, MfiLengthMemory.receiverKey("bmw mgu", "aa-bb-cc-dd-ee-ff"))
        assertNotEquals(key, MfiLengthMemory.receiverKey("BMW MGU", "AA:BB:CC:DD:EE:FF", "wifi"))
        val prefix = "receiver".repeat(20)
        assertNotEquals(MfiLengthMemory.receiverKey(prefix + "一", "AA:BB:CC:DD:EE:FF"),
            MfiLengthMemory.receiverKey(prefix + "二", "AA:BB:CC:DD:EE:FF"))
        assertFalse(key.contains("aabbccddeeff"))
    }

    @Test fun knownPeerDoesNotImportAmbiguousLegacyModelHistory() {
        MfiLengthMemory.clearAll(null)
        try {
            MfiLengthMemory.recordSuccess(null, "BMW MGU", 16)
            assertEquals(16, MfiLengthMemory.load(null, "BMW MGU").succeeded)
            assertTrue(MfiLengthMemory.load(null,
                MfiLengthMemory.receiverKey("BMW MGU", "AA:BB:CC:DD:EE:FF")).isEmpty)
        } finally { MfiLengthMemory.clearAll(null) }
    }
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
