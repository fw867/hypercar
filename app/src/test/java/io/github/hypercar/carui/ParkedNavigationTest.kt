package io.github.hypercar.carui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedNavigationTest {
    @Test fun idleEvidenceOnlyAppliesToTheExactRetiringDisplay() {
        val retiring = MapPreview.ProjectionDisplay(ticket = 4, id = 17)
        val evidence = retiring.idleAtDisconnect(4) { false }
        assertNotNull(retiring.parkedNavigation(evidence))
        assertNull(MapPreview.ProjectionDisplay(4, 17).parkedNavigation(evidence))
        assertNull(MapPreview.ProjectionDisplay(5, 17).parkedNavigation(evidence))
        assertNull(retiring.idleAtDisconnect(5) { false })
        assertNull(MapPreview.ProjectionDisplay(4, -1).idleAtDisconnect(4) { false })
    }

    @Test fun anUnobservedOrActiveRouteRemainsProtected() {
        for (route in listOf(null, true)) {
            val display = MapPreview.ProjectionDisplay(ticket = 1, id = 13)
            assertNull(display.parkedNavigation(display.idleAtDisconnect(1) { route }))
        }
    }

    @Test fun anObservedIdleMapCanBeReleased() {
        val display = MapPreview.ProjectionDisplay(ticket = 1, id = 13)
        val navigation = display.parkedNavigation(display.idleAtDisconnect(1) { false })!!
        assertFalse(navigation.canRelease(null))
        assertFalse(navigation.canRelease(true))
        assertTrue(navigation.canRelease(false))
        navigation.observe(true)
        assertFalse(navigation.canRelease(false))
    }
}
