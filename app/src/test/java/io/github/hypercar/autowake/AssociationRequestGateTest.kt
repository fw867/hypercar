package io.github.hypercar.autowake

import org.junit.Assert.*
import org.junit.Test

class AssociationRequestGateTest {
    @Test fun onlyOneSystemRequestCanBeOutstanding() {
        val gate = AssociationRequestGate()
        val ticket = gate.begin("car")!!
        assertNull(gate.begin("car"))
        assertNull(gate.begin("another car"))
        assertTrue(gate.isActive(ticket))
    }

    @Test fun duplicatePendingCallbackOnlyLaunchesOneDialog() {
        val gate = AssociationRequestGate()
        val ticket = gate.begin("car")!!
        assertTrue(gate.deliverPending(ticket))
        assertFalse(gate.deliverPending(ticket))
    }

    @Test fun bothCompletionOrdersDeliverOnlyOneSuccess() {
        for (order in listOf(listOf("created", "activity result"), listOf("activity result", "created"))) {
            val gate = AssociationRequestGate()
            val ticket = gate.begin("car")!!
            var results = 0
            order.forEach { if (gate.finish(ticket)) results++ }
            assertEquals(1, results)
            assertFalse(gate.deliverPending(ticket))
        }
    }

    @Test fun cancelledOrTimedOutRequestsCannotAffectARetry() {
        val gate = AssociationRequestGate()
        val old = gate.begin("car")!!
        assertTrue(gate.finish(old))
        val next = gate.begin("car")!!
        assertNotEquals(old.id, next.id)
        assertFalse(gate.deliverPending(old))
        assertFalse(gate.finish(old))
        assertTrue(gate.isActive(next))
        assertTrue(gate.deliverPending(next))
        assertTrue(gate.finish(next))
    }
}
