package io.github.hypercar.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BmwCaffExperimentTest {
    @Test fun normalRoundsKeepAutomaticFallbackUnlessExplicitlyArmed() {
        val experiment = BmwCaffExperiment()
        assertNull(experiment.takeForRound(manual = true, eligibleTarget = "AA"))
        experiment.arm("AA")
        repeat(2) { assertNull(experiment.takeForRound(manual = false, eligibleTarget = "AA")) }
        assertEquals("AA", experiment.armedTarget.value)
        assertEquals("aa", experiment.takeForRound(manual = true, eligibleTarget = "aa"))
        assertNull(experiment.takeForRound(manual = true, eligibleTarget = "AA"))
    }

    @Test fun changedTargetOrProfileCannotReuseTheOptIn() {
        val experiment = BmwCaffExperiment()
        experiment.arm("AA")
        assertNull(experiment.takeForRound(manual = true, eligibleTarget = "BB"))
        assertNull(experiment.armedTarget.value)
        experiment.arm("AA")
        assertNull(experiment.takeForRound(manual = true, eligibleTarget = null))
        assertNull(experiment.armedTarget.value)
    }

    @Test fun clearingPendingOptInDoesNotChangeAnAlreadySelectedRound() {
        val experiment = BmwCaffExperiment()
        experiment.arm("AA")
        val selected = experiment.takeForRound(manual = true, eligibleTarget = "AA")
        experiment.arm("AA")
        experiment.clear()
        assertEquals("AA", selected)
        assertNull(experiment.takeForRound(manual = true, eligibleTarget = "AA"))
    }
}
