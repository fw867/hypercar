package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneProjectionSizeTest {
    private val phone = PhoneProjectionSize(1200, 2600, 480)

    @Test fun expandedRegionUsesFullAvailableHeight() {
        assertEquals(480 to 1040, phone.fit(2000, 1040))
    }

    @Test fun narrowCardUsesFullAvailableWidth() {
        assertEquals(360 to 780, phone.fit(360, 1040))
    }

    @Test fun expandedDisplayMatchesItsCanvasAndExposesTabletWidth() {
        assertEquals(PhoneProjectionSize(2200, 800, 213), phone.expanded(2200, 800))
        assertEquals(PhoneProjectionSize(600, 1000, 160), phone.expanded(600, 1000))
    }
}
