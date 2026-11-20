package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGuidanceLayoutTest {
    @Test fun spaciousCardKeepsOriginalGeometry() {
        val layout = MapGuidanceLayout.forCard(520f, 300f)
        assertFalse(layout.compact)
        assertEquals(94f, layout.start, 0f)
        assertEquals(14f, layout.edge, 0f)
        assertEquals(290f, layout.maxPanelWidth, 0f)
    }

    @Test fun eitherDimensionCanTriggerCompactLayout() {
        val narrow = MapGuidanceLayout.forCard(380f, 400f)
        val short = MapGuidanceLayout.forCard(900f, 299f)
        assertTrue(narrow.compact)
        assertTrue(short.compact)
        assertEquals(94f, narrow.start, 0f)
        assertTrue(narrow.maxPanelWidth < short.maxPanelWidth)
        assertEquals(220f, short.maxPanelWidth, 0f)
    }

    @Test fun narrowCardsReserveTextSpaceBeforeFullBadgeClearance() {
        val layout = MapGuidanceLayout.forCard(225f, 350f)
        assertTrue(layout.start < 94f)
        assertEquals(156f, layout.maxPanelWidth, 0f)
        assertTrue(layout.showArrow)
        val tiny = MapGuidanceLayout.forCard(128f, 160f)
        assertFalse(tiny.showArrow)
        assertTrue(tiny.start + tiny.maxPanelWidth + tiny.edge <= 128f)
    }
}
