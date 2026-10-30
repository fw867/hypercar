package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class DisplayLayoutTest {
    private val bmw = DisplayLayout(2736,800,DisplayRect(0,0,2736,798),DisplayRect(4,4,2732,794),false)
    @Test fun bmwRetainsReportedAsymmetricEdgesWithoutChangingCanvas() {
        assertEquals(DisplayRect(4,4,2732,794), bmw.contentBounds(2736,800))
        assertEquals(DisplayRect(4,4,2732,794), bmw.backgroundBounds(2736,800))
        assertEquals(DisplayRect(0,0,2736,798), bmw.copy(drawOutside=true).backgroundBounds(2736,800))
    }
    @Test fun coordinateScalingRoundsTowardSafeInterior() {
        val scaled = bmw.contentBounds(1368,400)
        assertEquals(DisplayRect(2,2,1366,397), scaled)
        val odd = bmw.contentBounds(1920,558)
        assertTrue(odd.x >= 4.0*1920/2736)
        assertTrue(odd.y >= 4.0*558/800)
        assertTrue(odd.right <= 1920 && odd.bottom <= 798.0*558/800)
    }
    @Test fun invalidSafeAreaFallsBackToClippedView() {
        assertEquals(DisplayRect(0,0,2736,798), bmw.copy(safe=DisplayRect(4000,0,20,20)).contentBounds(2736,800))
        assertEquals(DisplayRect(20,10,780,470), DisplayLayout(800,480,DisplayRect(20,10,1000,600),DisplayRect(0,0,0,0),false).contentBounds(800,480))
    }
    @Test fun fullScreenMapHomeControlAndItsHitBoxStayInsideSafeArea() {
        CarDisplayLayout.state.value = bmw.copy(safe=DisplayRect(140,60,2400,680))
        try {
            val button = CarDisplayLayout.homeButtonBounds(2736,800)
            assertTrue(button.x >= 140 && button.y >= 60 && button.right <= 2540 && button.bottom <= 740)
            assertEquals(104,button.width)
            assertEquals(104,button.height)
        } finally { CarDisplayLayout.state.value = null }
    }
    @Test fun missingLayoutRestoresFullScreenBounds() {
        CarDisplayLayout.state.value = null
        assertEquals(DisplayRect(0,0,2400,1080), CarDisplayLayout.contentBounds(2400,1080))
    }
}
