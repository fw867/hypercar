package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapScaleTest {
    private val canvas = DisplayRect(0, 0, 2736, 800)
    private val inset = DisplayRect(4, 4, 2732, 794)

    @Test fun hiddenGroupMovesItsWakeSlotsAndRestoresTheRememberedEdge() {
        val owner = Any()
        val placement = MapButtonPlacement(canvas, true, MapButtonPosition(1f, 1f))
        placement.resize(inset)
        val hidden = MapOverlayInput(owner, placement.layout)
        assertEquals(null, hidden.slot(owner, 2670, 799))
        assertEquals(MapControl.HOME, hidden.slot(owner, 2670, 797))
        assertEquals(null, hidden.visible(owner, 2670, 797))
        val shown = hidden.copy(drawn = hidden.layout.buttons.keys)
        assertEquals(MapControl.HOME, shown.visible(owner, 2670, 797))
        assertEquals(MapControl.RESET, shown.visible(owner, 2670, 530))
        placement.resize(DisplayRect(80, 90, 40, 70))
        placement.resize(canvas)
        assertEquals(MapControl.HOME, placement.layout.hit(2735, 799))
    }

    @Test fun shrinkDuringDragRebasesAtLastPointerAndCancelRestoresSavedPosition() {
        val placement = MapButtonPlacement(canvas, true, MapButtonPosition(1f, 1f))
        placement.dragBy(-200, -50)
        placement.resize(DisplayRect(100, 50, 600, 420))
        val rebased = placement.layout
        placement.dragBy(-200, -50)
        assertEquals(rebased.buttons, placement.layout.buttons)
        placement.dragBy(-215, -65)
        assertEquals(rebased.left - 15, placement.layout.left)
        assertEquals(rebased.top - 15, placement.layout.top)
        assertEquals(null, placement.endDrag(save = false))
        assertEquals(MapControl.HOME, placement.layout.hit(699, 469))
        placement.resize(canvas)
        assertEquals(MapControl.HOME, placement.layout.hit(2735, 799))
    }

    @Test fun liftingAfterPassiveResizeKeepsLastDraggedPositionAcrossRestart() {
        val saved = MapButtonPosition(.75f, .25f)
        val placement = MapButtonPlacement(canvas, true, saved)
        placement.dragBy(-150, 50)
        val intended = placement.layout
        placement.resize(DisplayRect(40, 30, 1, 2))
        placement.dragBy(-150, 50)
        val committed = placement.endDrag(save = true)
        assertEquals(intended.position(), committed)
        assertEquals(intended.buttons, MapButtonPlacement(canvas, true, committed).layout.buttons)
    }

    @Test fun draggingEitherButtonClampsTheEntireGroupAndPreservesItsGap() {
        val safe = DisplayRect(127, 61, 2200, 460)
        val initial = MapControlBar.inside(safe, true)
        val reset = initial.buttons.getValue(MapControl.RESET)
        val home = initial.buttons.getValue(MapControl.HOME)
        for (x in listOf(-2000, 700, 4000)) for (y in listOf(-2000, 132, 2200)) {
            val moved = initial.at(x, y)
            for (r in moved.buttons.values) assertEquals(r, safe.intersect(r))
            assertEquals(home.y - reset.y, moved.buttons.getValue(MapControl.HOME).y - moved.top)
            assertEquals(null, moved.hit(moved.left, moved.top + reset.height))
            assertEquals(moved.buttons, initial.restore(moved.position()).buttons)
        }
        val bottomRight = initial.at(4000, 2200)
        assertEquals(safe.right, bottomRight.buttons.getValue(MapControl.HOME).right)
        assertEquals(safe.bottom, bottomRight.buttons.getValue(MapControl.HOME).bottom)
    }

    @Test fun restoringOnAnotherCanvasAccountsForBothButtonsAndSafeInsets() {
        val original = MapControlBar.inside(DisplayRect(0, 0, 2736, 800), true)
        val saved = original.at(4000, 2000).position()
        for (safe in listOf(DisplayRect(50, 20, 500, 200), DisplayRect(25, 19, 80, 4))) {
            val bar = MapControlBar.inside(safe, true).restore(saved)
            for (r in bar.buttons.values) assertEquals(r, safe.intersect(r))
            val home = bar.buttons.getValue(MapControl.HOME)
            assertEquals(safe.right, home.right)
            assertEquals(safe.bottom, home.bottom)
        }
    }

    @Test fun hiddenOrUnpaintedButtonCanOnlyWakeItsOwnSession() {
        val owner = Any()
        val hidden = MapOverlayInput(owner, MapControlBar.inside(DisplayRect(0, 0, 2736, 800), true))
        val home = hidden.layout.buttons.getValue(MapControl.HOME)
        assertEquals(MapControl.HOME, hidden.slot(owner, home.x, home.y))
        assertEquals(null, hidden.visible(owner, home.x, home.y))
        val resetOnly = hidden.copy(drawn = setOf(MapControl.RESET))
        assertEquals(null, resetOnly.visible(owner, home.x, home.y))
        val visible = hidden.copy(drawn = setOf(MapControl.HOME, MapControl.RESET))
        assertEquals(MapControl.HOME, visible.visible(owner, home.x, home.y))
        assertEquals(null, visible.slot(Any(), home.x, home.y))
        assertEquals(null, hidden.slot(owner, home.x, home.y - 1))
    }

    @Test fun resetOnlyTargetsTheResumedUcarWindowOnTheRequestedDisplay() {
        val snapshot = """
            Display #0 (activities from top to bottom):
                topResumedActivity=ActivityRecord{a u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t23}
            Display #4 (activities from top to bottom):
                topResumedActivity=ActivityRecord{b u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t24}
            Display #45 (activities from top to bottom):
                topResumedActivity=ActivityRecord{c u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity t25}
        """.trimIndent()
        assertEquals(false, hasResumedUcarActivity(snapshot, 4))
        assertEquals(false, hasResumedUcarActivity(snapshot, 5))
        assertEquals(true, hasResumedUcarActivity(snapshot, 45))
    }

    @Test fun onlyAShortSingleFingerTapCanOpenTheCard() {
        val tap = MapCardTap(8, 500)
        tap.down(10f, 10f)
        tap.observe(12f, 10f, 1)
        assertEquals(true, tap.isTap(499))
        assertEquals(false, tap.isTap(500))
        tap.observe(20f, 10f, 1)
        tap.observe(10f, 10f, 1)
        assertEquals(false, tap.isTap(100))
        tap.down(10f, 10f)
        tap.observe(10f, 10f, 2)
        tap.observe(10f, 10f, 1)
        assertEquals(false, tap.isTap(100))
    }

    @Test fun eachButtonIsReachableInsideAnOffsetUltrawideSafeArea() {
        for (safe in listOf(DisplayRect(200, 35, 2200, 460), DisplayRect(15, 24, 77, 93))) {
            val bar = MapControlBar.inside(safe, withReset = true)
            val reset = bar.buttons.getValue(MapControl.RESET)
            val home = bar.buttons.getValue(MapControl.HOME)
            for ((control, bounds) in bar.buttons) {
                assertEquals(bounds, safe.intersect(bounds))
                assertEquals(control, bar.hit(bounds.x, bounds.y))
                assertEquals(control, bar.hit(bounds.right - 1, bounds.bottom - 1))
            }
            for (y in reset.bottom until home.y) assertEquals(null, bar.hit(home.x, y))
            assertEquals(true, home.y > reset.bottom)
            assertEquals(null, bar.hit(home.right, home.y))
            assertEquals(setOf(MapControl.HOME), MapControlBar.inside(safe, withReset = false).buttons.keys)
        }
        val normal = MapControlBar.inside(DisplayRect(0, 0, 2736, 800), withReset = true)
        assertEquals(124, normal.buttons.getValue(MapControl.HOME).width)
        assertEquals(20, normal.buttons.getValue(MapControl.HOME).y - normal.buttons.getValue(MapControl.RESET).bottom)
    }
}
