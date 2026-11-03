package io.github.hypercar.bluetooth

import org.junit.Assert.*
import org.junit.Test

class CarTargetPolicyTest {
    private val bmw = "AA:BB:CC:DD:EE:01"
    private val diplay = "AA:BB:CC:DD:EE:02"
    private val headset = "AA:BB:CC:DD:EE:03"

    @Test fun clearingBmwTargetAllowsDiPlayEvenWhenOldAclArrivesAgain() {
        val bonded = listOf(diplay, bmw, headset)
        val services = setOf(diplay)
        assertEquals(listOf(bmw), CarTargetPolicy.outgoingCandidates(bmw, null, bonded) { false })
        assertEquals(listOf(bmw, diplay), CarTargetPolicy.outgoingCandidates(null, bmw, bonded) { it in services })
        assertTrue(CarTargetPolicy.acceptsIncoming(null, diplay))
        assertFalse(CarTargetPolicy.acceptsIncoming(bmw, diplay))
    }

    @Test fun explicitTargetRemainsExclusiveAndDoesNotNeedSdp() {
        assertEquals(listOf(bmw), CarTargetPolicy.outgoingCandidates(bmw.lowercase(), diplay,
            listOf(diplay, bmw)) { error("Explicit target must bypass SDP") })
        assertTrue(CarTargetPolicy.acceptsIncoming(bmw, bmw.lowercase()))
        assertFalse(CarTargetPolicy.acceptsIncoming(bmw, diplay))
    }

    @Test fun automaticWithoutAclIncludesOnlyIap2Devices() {
        assertEquals(listOf(diplay), CarTargetPolicy.outgoingCandidates(null, null,
            listOf(headset, diplay)) { it == diplay })
    }

    @Test fun staleAclCannotInventAnUnpairedCandidate() {
        assertEquals(listOf(diplay), CarTargetPolicy.outgoingCandidates(null, bmw,
            listOf(headset, diplay)) { it == diplay })
    }

    @Test fun automaticWakeRecognisesDiPlayByServiceOrPriorCarPlayConnection() {
        assertTrue(CarTargetPolicy.acceptsAcl(null, diplay, "Xiaomi phone", false, true))
        assertTrue(CarTargetPolicy.acceptsAcl(null, diplay, "Xiaomi phone", true, false))
        assertFalse(CarTargetPolicy.acceptsAcl(null, headset, "Headset", false, false))
        assertTrue(CarTargetPolicy.acceptsAcl(null, bmw, "BMW 1234", false, false))
    }

    @Test fun explicitBmwStillRejectsOtherKnownOrAdvertisedReceivers() {
        assertFalse(CarTargetPolicy.acceptsAcl(bmw, diplay, "DiPlay", true, true))
        assertTrue(CarTargetPolicy.acceptsAcl(bmw, bmw.lowercase(), "BMW", false, false))
    }
}
