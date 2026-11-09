package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Test

class CarActivationWakeEvidenceTest {
    private val car = "AA:BB:CC:DD:EE:01"
    private val other = "AA:BB:CC:DD:EE:02"

    @Test fun repeatedConnectedCallbacksAreNotNewWakeEvidence() {
        val signals = CarActivationWakeEvidence()
        signals.observe(car, true, 1_000)
        val first = signals.snapshot(car)!!
        signals.observe(car.lowercase(), true, 20_000)
        assertEquals(first, signals.snapshot(car))
    }

    @Test fun disconnectStartsQuietIntervalWithoutInventingAnotherConnection() {
        val signals = CarActivationWakeEvidence()
        signals.observe(car, true, 1_000)
        val wake = signals.snapshot(car)!!.connectedRevision
        signals.observe(car, false, 2_000)
        assertEquals(wake, signals.snapshot(car)!!.connectedRevision)
        assertEquals(2_000L, signals.snapshot(car)!!.lastChangedAt)
        signals.observe(car, false, 5_000)
        assertEquals(2_000L, signals.snapshot(car)!!.lastChangedAt)
        signals.observe(car, true, 6_000)
        assertTrue(signals.snapshot(car)!!.connectedRevision > wake)
    }

    @Test fun anotherReceiverCannotOverwriteTheCarsEvidence() {
        val signals = CarActivationWakeEvidence()
        signals.observe(car, true, 1_000)
        val first = signals.snapshot(car)
        signals.observe(other, true, 2_000)
        assertEquals(first, signals.snapshot(car))
        assertNotEquals(first!!.receiver, signals.snapshot(other)!!.receiver)
    }

    @Test fun missingConnectionAndInvalidReceiverNeverInventWakeEvidence() {
        val signals = CarActivationWakeEvidence()
        signals.observe(car, false, 1_000)
        assertEquals(0L, signals.snapshot(car)!!.connectedRevision)
        signals.observe("bad", true, 2_000)
        assertNull(signals.snapshot("bad"))
        assertNull(signals.snapshot(null))
    }
}
