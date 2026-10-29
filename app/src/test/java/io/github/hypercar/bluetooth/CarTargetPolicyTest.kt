package io.github.hypercar.bluetooth

import org.junit.Assert.*
import org.junit.Test

class CarTargetPolicyTest {
    @Test fun returningToAutomaticAcceptsAnotherCarDespiteCachedAcl() {
        val oldCar = "AA:BB:CC:DD:EE:01"
        val newCar = "AA:BB:CC:DD:EE:02"
        assertFalse(CarTargetPolicy.acceptsIncoming(oldCar, newCar))
        assertEquals(oldCar, CarTargetPolicy.outgoingTarget(null, oldCar))
        assertTrue(CarTargetPolicy.acceptsIncoming(null, newCar))
    }

    @Test fun explicitTargetFiltersIncomingAndOverridesOutgoingHint() {
        assertTrue(CarTargetPolicy.acceptsIncoming("AA:BB", "aa:bb"))
        assertFalse(CarTargetPolicy.acceptsIncoming("AA:BB", "CC:DD"))
        assertEquals("AA:BB", CarTargetPolicy.outgoingTarget("AA:BB", "CC:DD"))
    }
}
