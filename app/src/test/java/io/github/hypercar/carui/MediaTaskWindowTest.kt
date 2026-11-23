package io.github.hypercar.carui

import io.github.hypercar.shizuku.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaTaskWindowTest {
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
