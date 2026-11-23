package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class CarReceiverUiTest {
    @Test fun unclassifiedProjectionObeysEveryDeclaredRestrictionAndUnlocksWhenRestored() {
        val ui = ReceiverUiStore()
        for (element in listOf("musicLists", "softPhoneKeypad", "japanMaps", "nonMusicLists", "softKeyboard")) {
            ui.handle("setLimitedUI", """{"limitedUI":true,"limitedUIElements":["$element"]}""")
            assertFalse(element, ui.state.value.limits.allowsProjection("custom.player", null))
        }
        ui.handle("setLimitedUI", """{"limitedUI":false}""")
        assertTrue(ui.state.value.limits.allowsProjection("custom.player", null))
    }

    @Test fun knownProjectionPackagesApplyOnlyRelevantRestrictions() {
        val dialer = "custom.dialer"
        val music = "com.tencent.qqmusic"
        val video = "com.ss.android.ugc.aweme"
        fun allowed(element: String, pkg: String) = UiLimits(true, setOf(element)).allowsProjection(pkg, dialer)
        assertFalse(allowed("musicLists", music))
        assertTrue(allowed("musicLists", video))
        assertTrue(allowed("musicLists", dialer))
        assertTrue(allowed("softPhoneKeypad", music))
        assertTrue(allowed("softPhoneKeypad", video))
        assertFalse(allowed("softPhoneKeypad", dialer))
        for (pkg in listOf(music, video, dialer)) {
            assertTrue(allowed("japanMaps", pkg))
            assertFalse(allowed("nonMusicLists", pkg))
            assertFalse(allowed("softKeyboard", pkg))
        }
    }

    @Test fun onlyDeclaredElementsAreLimitedUntilRestored() {
        val ui = ReceiverUiStore()
        ui.handle("setLimitedUI", """{"limitedUI":true,"limitedUIElements":["musicLists","future"]}""")
        val limits = ui.state.value.limits
        assertTrue(limits.musicLists)
        assertTrue(limits.blockMusicApp)
        assertFalse(limits.blockMapApp)
        assertFalse(limits.nonMusicLists)
        ui.handle("setLimitedUI", """{"limitedUI":"false"}""")
        assertEquals(limits, ui.state.value.limits)
        ui.handle("setLimitedUI", """{"limitedUI":false}""")
        assertEquals(UiLimits(), ui.state.value.limits)
        ui.handle("setLimitedUI", """{"limitedUI":true,"limitedUIElements":[]}""")
        assertTrue(ui.state.value.limits.enabled)
        assertTrue(ui.state.value.limits.elements.isEmpty())
    }

    @Test fun routeIsAllowlistedAndNewRouteSupersedesAnUnconsumedRoute() {
        val ui = ReceiverUiStore()
        ui.handle("requestUI", """{"url":"maps:"}""")
        val first = ui.state.value.route!!
        ui.handle("requestUI", """{"url":"intent://evil"}""")
        ui.handle("requestUI", """{"url":"mobilephone:+12345"}""")
        assertEquals(first, ui.state.value.route)
        ui.handle("requestUI", """{"url":"mobilephone:"}""")
        assertFalse(ui.consumeRoute(first))
        val phone = ui.state.value.route!!
        assertEquals(CarUiDestination.PHONE, phone.value)
        assertTrue(ui.consumeRoute(phone))
        assertEquals(CarUiDestination.PHONE, ui.state.value.destination)
        assertFalse(ui.consumeRoute(phone))
    }

    @Test fun losingScreenDiscardsPendingActionsButKeepsReceiverLimitsUntilSessionEnds() {
        val ui = ReceiverUiStore()
        ui.handle("setLimitedUI", """{"limitedUI":true,"limitedUIElements":["softKeyboard"]}""")
        ui.handle("requestUI", """{"url":"nowplaying:"}""")
        ui.handle("accessoryGiveFocus", focus)
        assertNotNull(ui.state.value.focus)
        ui.handle("screenOwned", """{"owned":false}""")
        assertNull(ui.state.value.focus)
        assertNull(ui.state.value.route)
        assertTrue(ui.state.value.limits.blockMapApp)
        ui.handle("requestUI", """{"url":"music:"}""")
        ui.handle("accessoryGiveFocus", focus)
        ui.handle("setLimitedUI", """{"limitedUI":true,"limitedUIElements":["musicLists"]}""")
        assertTrue(ui.state.value.limits.musicLists)
        assertNull(ui.state.value.route)
        assertNull(ui.state.value.focus)
        ui.handle("screenOwned", """{"owned":true}""")
        assertNull(ui.state.value.route)
        ui.handle("requestUI", """{"url":"maps:"}""")
        ui.handle("accessoryGiveFocus", focus)
        assertNotNull(ui.state.value.route)
        assertNotNull(ui.state.value.focus)
        ui.reset()
        assertEquals(UiLimits(), ui.state.value.limits)
    }

    @Test fun focusCoordinatesScaleIndependentlyOnBothAxesAndUnknownHeadingsAreIgnored() {
        val ui = ReceiverUiStore()
        ui.handle("accessoryGiveFocus", focus)
        val request = ui.state.value.focus!!.value
        assertEquals(DisplayRect(10, 50, 10, 25), request.onCanvas(1000, 250))
        val controls = mapOf("left" to DisplayRect(0, 50, 8, 20), "top" to DisplayRect(50, 0, 20, 20),
            "right" to DisplayRect(60, 60, 20, 20))
        assertEquals("right", request.target(controls, 1000, 250))
        assertEquals("left", request.copy(direction = android.view.View.FOCUS_LEFT).target(controls, 1000, 250))
        ui.handle("accessoryGiveFocus", focus.replace("\"focusHeading\":8", "\"focusHeading\":99"))
        assertEquals(request, ui.state.value.focus!!.value)
    }

    @Test fun changingToAnAreaWithoutFocusTransferInvalidatesQueuedAndSubsequentFocus() {
        val ui = ReceiverUiStore()
        ui.handle("accessoryGiveFocus", focus)
        val queued = ui.state.value.focus!!
        ui.setFocusTransferAllowed(false)
        assertFalse(ui.consumeFocus(queued))
        ui.handle("accessoryGiveFocus", focus)
        assertNull(ui.state.value.focus)
        ui.setFocusTransferAllowed(true)
        ui.handle("accessoryGiveFocus", focus)
        assertTrue(ui.consumeFocus(ui.state.value.focus!!))
    }

    private val focus = """{"originXPixels":20,"originYPixels":100,"widthPixels":20,"heightPixels":50,"focusHeading":8,"displayWidth":2000,"displayHeight":500}"""
}
