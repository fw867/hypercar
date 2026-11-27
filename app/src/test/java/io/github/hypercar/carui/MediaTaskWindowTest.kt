package io.github.hypercar.carui

import io.github.hypercar.shizuku.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaTaskWindowTest {
    private val original = listOf(MediaTaskWindow.Task(31, "", "player", 2, 20, true))
    private fun stacks(display: Int) =
        "RootTask id=42 displayId=$display\n  taskId=31: player/.UI bounds=[0,0][1,1] visible=true"

    @Test fun requiredStackInspectionRejectsFailedOrUnrecognizedResponses() = runBlocking {
        for (reply in listOf(ExecResult(1, "", "failed"), ExecResult(0, "", ""),
            ExecResult(0, "unsupported", ""), ExecResult(0, "RootTask id=42 displayId=8\n taskId=31: incomplete", ""))) {
            assertFalse(MediaTaskWindow.restoreBeforeRelease(8, "player", original, false, null, false) { reply })
        }
    }

    @Test fun requiredMoveAndRemovalWaitForVisibleDeliveryEvenAfterSuccessfulExit() = runBlocking {
        for (close in listOf(false, true)) {
            for (exit in listOf(0, 1)) {
                assertFalse(MediaTaskWindow.restoreBeforeRelease(8, "player", original, false, null, close) {
                    if (it == "am stack list") ExecResult(0, stacks(8), "") else ExecResult(exit, "", "")
                })
            }
        }
    }

    @Test fun retriesKeepTheExpectedTaskDestinationAcrossAReadbackFailure() = runBlocking {
        val expected = mutableMapOf<Int, Int?>()
        var current = stacks(8)
        suspend fun attempt() = MediaTaskWindow.restoreBeforeRelease(8, "player", original, false, null, false, expected) {
            when (it) {
                "am stack list" -> ExecResult(0, current, "")
                "am display move-stack 42 2" -> { current = ""; ExecResult(0, "", "") }
                else -> error(it)
            }
        }
        assertFalse(attempt())
        current = stacks(8).replace("player/.UI", "unknown")
        assertFalse(attempt())
        // The user or system subsequently moved the task elsewhere. Cleanup may retire the
        // old display after consecutive observations there, without touching the other display.
        current = stacks(0)
        assertTrue(attempt())
    }

    @Test fun mixedRootsUseAndroidDisplayRemovalWithoutClosingAnotherApplicationsTask() = runBlocking {
        val mixed = stacks(8) + "\n taskId=44: other/.UI bounds=[0,0][1,1] visible=true"
        for (close in listOf(false, true)) {
            assertTrue(MediaTaskWindow.restoreBeforeRelease(8, "player", original, false, null, close) {
                assertEquals("am stack list", it)
                ExecResult(0, mixed, "")
            })
        }
    }

    @Test fun anOriginallyUnnamedTaskIsStillRestoredById() {
        val previous = original.map { it.copy(packageName = "") }
        assertEquals(listOf("am display move-stack 42 2"),
            MediaTaskWindow.releaseCommands(previous, MediaTaskWindow.tasks(stacks(8)), 8, "player"))
    }

    @Test fun unsupportedWindowCommandsAllowConfirmedTaskHandoff() = runBlocking {
        var moved = false
        assertTrue(MediaTaskWindow.restoreBeforeRelease(8, "player", original, true, false, false) {
            when (it) {
                "am stack list" -> ExecResult(0, stacks(if (moved) 2 else 8), "")
                "am display move-stack 42 2" -> { moved = true; ExecResult(0, "", "") }
                else -> ExecResult(1, "", "unknown wm command")
            }
        })
        assertTrue(moved)
    }

    @Test fun samePackageMustFinishItsHandoffBeforeAnotherSnapshot() {
        val handoffs = MediaCardHandoffs()
        var alive = true
        val first = handoffs.tryAcquire("player")!!
        first.ownerAlive = { alive }
        repeat(100) { assertNull(handoffs.tryAcquire("player")) }
        assertNotNull(handoffs.tryAcquire("dialer"))
        alive = false
        val second = handoffs.tryAcquire("player")!!
        handoffs.finish("player", first)
        assertNull(handoffs.tryAcquire("player"))
        handoffs.finish("player", second)
        assertNotNull(handoffs.tryAcquire("player"))
    }

    @Test fun aSecondVisibleCardCanRequestHandoffFromAHiddenOwner() {
        val handoffs = MediaCardHandoffs()
        val first = handoffs.tryAcquire("dialer")!!
        var hidden = false
        first.retireIfHidden = { if (hidden) handoffs.finish("dialer", first) }
        assertNull(handoffs.tryAcquire("dialer"))
        hidden = true
        assertNull(handoffs.tryAcquire("dialer"))
        assertNotNull(handoffs.tryAcquire("dialer"))
    }

    @Test fun failedCollapseRetainsOnlyOutstandingWindowStateForDisplayCleanup() = runBlocking {
        for (failFullscreen in listOf(true, false)) {
            val window = MediaCardWindowState()
            window.needsFullscreenReset = true
            window.shortVideo = MediaTaskWindow.ExpandedShortVideo(true, false)
            assertFalse(window.collapse(8) { command ->
                if (!failFullscreen && command.contains("windowing-mode")) ExecResult(0, "windowing mode=fullscreen", "")
                else ExecResult(-1, "", "disconnected")
            })
            assertEquals(failFullscreen, window.needsFullscreenReset)
            assertEquals(false, window.shortVideo?.restoreIgnoreOrientation)
        }
    }

    @Test fun disconnectedWindowCleanupWaitsToMoveTheOriginalTaskBeforeReleasingTheDisplay() = runBlocking {
        val previous = listOf(MediaTaskWindow.Task(31, "", "player", 2, 20, true))
        var online = false
        var moved = false
        val commands = mutableListOf<String>()
        val cleanup = MediaCardRelease({ true }, {
            MediaTaskWindow.restoreBeforeRelease(8, "player", previous, true, false, false) { command ->
                commands += command
                if (!online) ExecResult(-1, "", "disconnected") else ExecResult(0, when (command) {
                    "wm get-display-windowing-mode -d 8" -> "display windowing mode=fullscreen"
                    "wm get-ignore-orientation-request -d 8" -> "ignoreOrientationRequest false for displayId=8"
                    "am stack list" -> stacks(if (moved) 2 else 8)
                    "am display move-stack 42 2" -> { moved = true; "" }
                    else -> ""
                }, "")
            }
        }, { commands += "release"; true })
        assertFalse(cleanup.attempt())
        assertEquals(listOf("wm set-display-windowing-mode -d 8 1"), commands)
        online = true
        commands.clear()
        assertTrue(cleanup.attempt())
        assertEquals(listOf(
            "wm set-display-windowing-mode -d 8 1", "wm get-display-windowing-mode -d 8",
            "wm set-ignore-orientation-request -d 8 false", "wm get-ignore-orientation-request -d 8",
            "am stack list", "am display move-stack 42 2", "am stack list", "release",
        ), commands)
    }

    @Test fun mediaDisplayCleanupSurvivesBrokerLossAndRetriesOnlyOutstandingWork() = runBlocking {
        var connected = false
        var released = false
        val events = mutableListOf<String>()
        val cleanup = MediaCardRelease(
            ownerAlive = { true },
            restore = { events += "restore"; connected },
            release = { events += "release original"; released },
        )
        assertFalse(cleanup.attempt())
        connected = true
        assertFalse(cleanup.attempt())
        released = true
        assertTrue(cleanup.attempt())
        assertEquals(listOf("restore", "restore", "release original", "release original"), events)
    }

    @Test fun deadDisplayOwnerRetiresCleanupWithoutTouchingAnotherService() = runBlocking {
        val cleanup = MediaCardRelease({ false }, { error("restored dead display") }, { error("released another display") })
        assertTrue(cleanup.attempt())
    }

    @Test fun expandedShortVideoReadsOrientationBeforeSettingItOnItsVirtualDisplay() = runBlocking {
        val commands = mutableListOf<String>()
        var ignored = false
        val prepared = MediaTaskWindow.prepareExpandedShortVideo(8) { command ->
            commands += command
            if (command == "wm set-ignore-orientation-request -d 8 true") ignored = true
            val output = when (command) {
                "wm get-ignore-orientation-request -d 8" -> "ignoreOrientationRequest $ignored for displayId=8"
                "wm get-display-windowing-mode -d 8" -> "display windowing mode=fullscreen for displayId=8"
                else -> ""
            }
            ExecResult(0, output, "")
        }
        assertTrue(prepared.ready)
        assertEquals(false, prepared.restoreIgnoreOrientation)
        assertEquals(listOf(
            "wm get-ignore-orientation-request -d 8",
            "wm set-ignore-orientation-request -d 8 true", "wm get-ignore-orientation-request -d 8",
            "wm set-display-windowing-mode -d 8 1", "wm get-display-windowing-mode -d 8",
        ), commands)
    }

    @Test fun expandedShortVideoLeavesAnExistingOrUnknownOrientationSettingAlone() = runBlocking {
        for ((readback, ready) in listOf("ignoreOrientationRequest true for displayId=8" to true, "" to false)) {
            val commands = mutableListOf<String>()
            val prepared = MediaTaskWindow.prepareExpandedShortVideo(8) {
                commands += it
                val output = if (it.startsWith("wm get-ignore")) readback else "display windowing mode=fullscreen for displayId=8"
                ExecResult(0, output, "")
            }
            assertEquals(ready, prepared.ready)
            assertNull(prepared.restoreIgnoreOrientation)
            assertFalse(commands.any { it.startsWith("wm set-ignore") })
        }
    }

    @Test fun orientationRestoreRequiresReadbackAndNeverChangesThePhoneDisplay() = runBlocking {
        assertFalse(MediaTaskWindow.setIgnoreOrientationRequest(0, false) { error("physical display") })
        assertFalse(MediaTaskWindow.setIgnoreOrientationRequest(8, false) {
            ExecResult(0, "ignoreOrientationRequest true for displayId=8", "")
        })
        assertTrue(MediaTaskWindow.setIgnoreOrientationRequest(8, false) {
            ExecResult(0, "ignoreOrientationRequest false for displayId=8", "")
        })
    }

    @Test fun launchConfirmationRejectsTaskLostDuringWindowSetup() = runBlocking {
        val taskOnProjection = """
            RootTask id=42 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=42: com.tencent.mm/.ui.LauncherUI bounds=[0,0][2523,800] userId=0 visible=true
        """.trimIndent()
        val taskOnPhone = taskOnProjection.replace("displayId=8", "displayId=0")
        var stacks = taskOnProjection
        var prepared = false
        assertFalse(MediaTaskWindow.confirmLaunch(8, "com.tencent.mm", { true }, readStacks = { stacks }) {
            prepared = true
            stacks = taskOnPhone
        })
        assertTrue(prepared)
    }

    @Test fun launchConfirmationAcceptsReplacementActivityOnTheSameDisplay() = runBlocking {
        var stacks = """
            RootTask id=42 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=42: com.tencent.mm/.Splash bounds=[0,0][2523,800] userId=0 visible=true
        """.trimIndent()
        assertTrue(MediaTaskWindow.confirmLaunch(8, "com.tencent.mm", { true }, readStacks = { stacks }) {
            stacks = stacks.replace(".Splash", ".ui.LauncherUI")
        })
    }

    @Test fun restoresReusedTaskToItsOriginalDisplayAndRemovesOnlyNewTasks() {
        val previous = MediaTaskWindow.tasks("""
            RootTask id=30 bounds=[0,0][1080,1920] displayId=6 userId=0
              taskId=31: com.luna.music/.Main bounds=[0,0][1080,1920] userId=0 visible=false
        """.trimIndent())
        val current = MediaTaskWindow.tasks("""
            RootTask id=40 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=31: com.luna.music/.Main bounds=[0,0][2523,800] userId=0 visible=true
            RootTask id=41 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=42: com.luna.music/.Other bounds=[0,0][2523,800] userId=0 visible=false
            RootTask id=43 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=44: com.luna.music/.Other bounds=[0,0][2523,800] userId=0 visible=false
              taskId=45: unknown bounds=[0,0][2523,800] userId=0 visible=false
        """.trimIndent())
        assertEquals(listOf("am display move-stack 40 6", "am stack remove 41"),
            MediaTaskWindow.releaseCommands(previous, current, 8, "com.luna.music"))
        assertTrue(MediaTaskWindow.releaseCommands(null, current, 8, "com.luna.music").isEmpty())
    }

    @Test fun closingShortVideoRemovesItsProjectedTaskEvenIfItStartedOnThePhone() {
        val previous = listOf(MediaTaskWindow.Task(42, "", "com.ss.android.ugc.aweme", 0, 42, true))
        val current = listOf(
            MediaTaskWindow.Task(42, "", "com.ss.android.ugc.aweme", 8, 42, true),
            MediaTaskWindow.Task(43, "", "com.ss.android.ugc.aweme", 0, 43, true),
            MediaTaskWindow.Task(44, "", "com.ss.android.ugc.aweme", 8, 44, true),
            MediaTaskWindow.Task(45, "", "com.example.other", 8, 44, false),
        )
        assertEquals(listOf("am stack remove 42"),
            MediaTaskWindow.releaseCommands(previous, current, 8, "com.ss.android.ugc.aweme", closeProjectedTasks = true))
        assertEquals(listOf("am display move-stack 42 0"),
            MediaTaskWindow.releaseCommands(previous, current, 8, "com.ss.android.ugc.aweme"))
    }

    @Test fun fullscreenRequiresReadbackAndRetriesAnUnappliedChange() = runBlocking {
        val commands = mutableListOf<String>()
        var changes = 0
        assertTrue(MediaTaskWindow.fullscreen(8) { command ->
            commands += command
            if (command.startsWith("wm set-")) {
                changes++
                ExecResult(0, "", "")
            } else {
                val mode = if (changes == 1) "freeform" else "fullscreen"
                ExecResult(0, "display windowing mode=$mode for displayId=8", "")
            }
        })
        assertEquals(4, commands.size)
        assertFalse(MediaTaskWindow.fullscreen(8) { ExecResult(1, "", "timeout") })
    }

    @Test fun findsVisibleAppTaskOnlyOnTheRequestedDisplay() {
        val stacks = """
            RootTask id=12 bounds=[0,0][2523,800] displayId=7 userId=0
              taskId=13: com.ss.android.ugc.aweme/.Splash bounds=[0,0][2523,800] userId=0 visible=true
            RootTask id=14 bounds=[0,0][2523,800] displayId=8 userId=0
              taskId=15: com.ss.android.ugc.aweme.lite/.Splash bounds=[0,0][2523,800] userId=0 visible=true
              taskId=16: com.ss.android.ugc.aweme/.Splash bounds=[0,0][2523,800] userId=0 visible=false
              taskId=17: com.ss.android.ugc.aweme/.Splash bounds=[0,0][2523,800] userId=0 visible=true
        """.trimIndent()
        assertEquals(17, MediaTaskWindow.visibleTask(stacks, 8, "com.ss.android.ugc.aweme")?.id)
        assertEquals("[0,0][2523,800]", MediaTaskWindow.visibleTask(stacks, 8, "com.ss.android.ugc.aweme")?.bounds)
        assertNull(MediaTaskWindow.visibleTask(stacks, 9, "com.ss.android.ugc.aweme"))
    }
}
