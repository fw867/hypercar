package io.github.hypercar.carui

import io.github.hypercar.shizuku.ExecResult
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.delay

internal object MediaTaskWindow {
    private val rootRow = Regex("""RootTask id=(\d+).*\bdisplayId=(\d+)\b""")
    private val taskRow = Regex("""taskId=(\d+):\s+(\S+)\s+bounds=(\S+).*\bvisible=(true|false)\b""")
    data class Task(
        val id: Int, val bounds: String, val packageName: String, val displayId: Int,
        val rootId: Int, val visible: Boolean,
    )

    fun tasks(stacks: String): List<Task> = buildList {
        var root = -1
        var display = -1
        for (line in stacks.lineSequence()) {
            val row = line.trimStart()
            if (row.startsWith("RootTask ")) {
                val match = rootRow.find(row)
                root = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
                display = match?.groupValues?.get(2)?.toIntOrNull() ?: -1
            } else if (root >= 0 && display >= 0) {
                val match = taskRow.find(row) ?: continue
                val id = match.groupValues[1].toIntOrNull() ?: continue
                val packageName = match.groupValues[2].substringBefore('/', missingDelimiterValue = "")
                add(Task(id, match.groupValues[3], packageName, display, root, match.groupValues[4] == "true"))
            }
        }
    }

    fun visibleTask(stacks: String, displayId: Int, packageName: String): Task? =
        tasks(stacks).firstOrNull { it.displayId == displayId && it.packageName == packageName && it.visible }

    suspend fun confirmLaunch(
        displayId: Int,
        packageName: String,
        stillOpen: () -> Boolean,
        readStacks: suspend () -> String? = { readStackList() },
        prepareWindow: suspend () -> Unit,
    ): Boolean {
        suspend fun taskVisible() = readStacks()?.let { visibleTask(it, displayId, packageName) } != null
        repeat(4) { attempt ->
            if (!stillOpen()) return false
            if (taskVisible()) {
                if (!stillOpen()) return false
                prepareWindow()
                // A launch result can precede a process exit or a splash activity handoff.
                delay(300)
                return stillOpen() && taskVisible() && stillOpen()
            }
            if (attempt < 3) delay(150)
        }
        return false
    }

    fun releaseCommands(
        previous: List<Task>?, current: List<Task>, displayId: Int, packageName: String,
        closeProjectedTasks: Boolean = false,
    ): List<String> {
        if (displayId <= 0 || previous == null && !closeProjectedTasks) return emptyList()
        val origins = previous.orEmpty().associateBy { it.id }
        return current.filter { it.displayId == displayId }.groupBy { it.rootId }.mapNotNull { (root, tasks) ->
            if (tasks.any { it.packageName != packageName }) return@mapNotNull null
            if (closeProjectedTasks) return@mapNotNull "am stack remove $root"
            val destinations = tasks.map { origins[it.id]?.displayId }.distinct()
            if (destinations.size != 1) return@mapNotNull null
            val destination = destinations.single()
            if (destination == null) "am stack remove $root"
            else if (destination != displayId) "am display move-stack $root $destination"
            else null
        }
    }

    suspend fun snapshot(): List<Task>? = readStackList()?.let(::tasks)?.takeIf { it.isNotEmpty() }

    suspend fun releaseTasks(
        displayId: Int, packageName: String, previous: List<Task>?, closeProjectedTasks: Boolean = false,
    ) {
        val current = snapshot() ?: return
        val commands = releaseCommands(previous, current, displayId, packageName, closeProjectedTasks)
        for (command in commands) {
            val result = ShizukuTools.exec(command, 2_000)
            if (!result.ok) {
                io.github.hypercar.session.HyperCarRepo.log("VIDEO-CARD: cleanup display=$displayId command=$command exit=${result.code}")
            }
        }
        if (closeProjectedTasks && commands.isNotEmpty()) {
            val removed = awaitProjectionTaskRemoval(displayId, packageName)
            io.github.hypercar.session.HyperCarRepo.log("VIDEO-CARD: package=$packageName display=$displayId projectionTasksClosed=$removed")
        }
    }

    private suspend fun awaitProjectionTaskRemoval(displayId: Int, packageName: String): Boolean {
        repeat(4) { attempt ->
            val remaining = readStackList()?.let(::tasks) ?: return false
            if (remaining.none { it.displayId == displayId && it.packageName == packageName }) return true
            if (attempt < 3) delay(100)
        }
        return false
    }

    suspend fun expand(displayId: Int, packageName: String, width: Int, height: Int): Boolean {
        if (displayId <= 0 || width <= height) return false
        if (findTask(displayId, packageName) == null) {
            delay(150)
            if (findTask(displayId, packageName) == null) return false
        }
        if (!ShizukuTools.exec("wm set-display-windowing-mode -d $displayId 5", 2_000).ok) return false
        val task = findTask(displayId, packageName) ?: return false
        if (!ShizukuTools.exec("am task resize ${task.id} 0 0 $width $height", 2_000).ok) return false
        val mode = ShizukuTools.exec("wm get-display-windowing-mode -d $displayId", 2_000)
        return mode.ok && mode.out.contains("windowing mode=freeform") &&
            findTask(displayId, packageName)?.bounds == "[0,0][$width,$height]"
    }

    suspend fun prepareExpandedShortVideo(
        displayId: Int,
        exec: suspend (String) -> ExecResult = { ShizukuTools.exec(it, 2_000) },
    ): ExpandedShortVideo {
        val original = readIgnoreOrientationRequest(displayId, exec)
        val orientation = when (original) {
            true -> true
            false -> setIgnoreOrientationRequest(displayId, true, exec)
            null -> false
        }
        val fullscreen = fullscreen(displayId, exec)
        // A setting command can succeed even when the following readback fails.
        return ExpandedShortVideo(orientation && fullscreen, original?.takeUnless { it })
    }

    data class ExpandedShortVideo(val ready: Boolean, val restoreIgnoreOrientation: Boolean?)

    suspend fun setIgnoreOrientationRequest(
        displayId: Int, ignore: Boolean,
        exec: suspend (String) -> ExecResult = { ShizukuTools.exec(it, 2_000) },
    ): Boolean {
        if (displayId <= 0) return false
        runCatching { exec("wm set-ignore-orientation-request -d $displayId $ignore") }
        return readIgnoreOrientationRequest(displayId, exec) == ignore
    }

    private suspend fun readIgnoreOrientationRequest(
        displayId: Int, exec: suspend (String) -> ExecResult,
    ): Boolean? {
        if (displayId <= 0) return null
        val result = runCatching { exec("wm get-ignore-orientation-request -d $displayId") }.getOrNull()
        if (result?.ok != true) return null
        return when {
            result.out.contains("ignoreOrientationRequest true for displayId=$displayId") -> true
            result.out.contains("ignoreOrientationRequest false for displayId=$displayId") -> false
            else -> null
        }
    }

    suspend fun fullscreen(
        displayId: Int,
        exec: suspend (String) -> ExecResult = { ShizukuTools.exec(it, 2_000) },
    ): Boolean {
        if (displayId <= 0) return false
        repeat(2) {
            runCatching { exec("wm set-display-windowing-mode -d $displayId 1") }
            val mode = runCatching { exec("wm get-display-windowing-mode -d $displayId") }.getOrNull()
            if (mode?.ok == true && mode.out.contains("windowing mode=fullscreen")) return true
        }
        return false
    }

    private suspend fun findTask(displayId: Int, packageName: String): Task? =
        readStackList()?.let { visibleTask(it, displayId, packageName) }

    private suspend fun readStackList(): String? =
        ShizukuTools.exec("am stack list", 2_000).let { if (it.ok) it.out else null }
}
