package io.github.hypercar.carui

import io.github.hypercar.shizuku.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaTaskWindowTest {
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
