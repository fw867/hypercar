package io.github.hypercar.carui

import io.github.hypercar.shizuku.ExecResult
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.delay

private val shellExec: suspend (String) -> ExecResult = { ShizukuTools.exec(it, 2_000) }

internal class MediaCardWindowState {
    var needsFullscreenReset = false
    var shortVideo: MediaTaskWindow.ExpandedShortVideo? = null

    suspend fun resetFullscreen(displayId: Int, exec: suspend (String) -> ExecResult = shellExec): Boolean {
        val restored = MediaTaskWindow.fullscreen(displayId, exec)
        if (restored) needsFullscreenReset = false
        return restored
    }

    suspend fun collapse(displayId: Int, exec: suspend (String) -> ExecResult = shellExec): Boolean {
        if (needsFullscreenReset && !resetFullscreen(displayId, exec)) return false
        val original = shortVideo?.restoreIgnoreOrientation
        if (original != null && !MediaTaskWindow.setIgnoreOrientationRequest(displayId, original, exec)) return false
        shortVideo = null
        return true
    }
}

internal object MediaTaskWindow {
    private val rootRow = Regex("""RootTask id=(\d+).*\bdisplayId=(\d+)\b""")
    private val taskRow = Regex("""taskId=(\d+):\s+(\S+)\s+bounds=(\S+).*\bvisible=(true|false)\b""")
    data class Task(
        val id: Int, val bounds: String, val packageName: String, val displayId: Int,
        val rootId: Int, val visible: Boolean,
    )

    fun tasks(stacks: String): List<Task> = parseStacks(stacks).orEmpty()

    private fun parseStacks(stacks: String): List<Task>? {
        val result = mutableListOf<Task>()
        var root = -1
        var display = -1
        for (line in stacks.lineSequence()) {
            val row = line.trimStart()
            if (row.startsWith("RootTask")) {
                val match = rootRow.find(row) ?: return null
                root = match.groupValues[1].toIntOrNull() ?: return null
                display = match.groupValues[2].toIntOrNull() ?: return null
            } else if (row.startsWith("taskId")) {
                if (root < 0) return null
                val match = taskRow.find(row) ?: return null
                val id = match.groupValues[1].toIntOrNull() ?: return null
                val packageName = match.groupValues[2].substringBefore('/', missingDelimiterValue = "")
                result.add(Task(id, match.groupValues[3], packageName, display, root, match.groupValues[4] == "true"))
            }
        }
        return result.takeIf { root >= 0 }
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

    suspend fun snapshot(): List<Task>? = readStackList()?.let(::parseStacks)

    suspend fun restoreBeforeRelease(
        displayId: Int, packageName: String, previous: List<Task>?, restoreFullscreen: Boolean,
        originalOrientation: Boolean?, closeProjectedTasks: Boolean,
        expectedDestinations: MutableMap<Int, Int?> = mutableMapOf(), exec: suspend (String) -> ExecResult,
    ): Boolean {
        // Unsupported wm commands are best effort. A lost broker prevents even attempting the handoff.
        var lostTransport: ExecResult? = null
        val shell: suspend (String) -> ExecResult = { command ->
            lostTransport ?: exec(command).also { if (it.code < 0) lostTransport = it }
        }
        if (restoreFullscreen) fullscreen(displayId, shell)
        if (originalOrientation != null) setIgnoreOrientationRequest(displayId, originalOrientation, shell)
        return lostTransport == null &&
            releaseTasks(displayId, packageName, previous, closeProjectedTasks, expectedDestinations, shell)
    }

    private suspend fun releaseTasks(
        displayId: Int, packageName: String, previous: List<Task>?, closeProjectedTasks: Boolean,
        expectedDestinations: MutableMap<Int, Int?>, exec: suspend (String) -> ExecResult,
    ): Boolean {
        if (previous == null && !closeProjectedTasks) return false
        suspend fun currentTasks() = exec("am stack list").takeIf { it.ok }?.let { parseStacks(it.out) }
        val current = currentTasks() ?: return false
        expectedDestinations.keys.retainAll(current.map { it.id }.toSet())
        val origins = previous.orEmpty().associateBy { it.id }
        for (task in current.filter { it.displayId == displayId && it.packageName == packageName }) {
            expectedDestinations[task.id] = if (closeProjectedTasks) null else origins[task.id]?.displayId
        }
        val commands = releaseCommands(previous, current, displayId, packageName, closeProjectedTasks)
        for (command in commands) {
            if (!exec(command).ok) return false
        }
        var lastExternal: Map<Int, Int>? = null
        repeat(4) { attempt ->
            val observed = if (attempt == 0 && commands.isEmpty()) current else currentTasks() ?: return false
            // A shared root cannot be moved or removed without affecting another task. Leave
            // such roots to Android's normal migration on removal of this public display.
            val actionable = releaseCommands(previous, observed, displayId, packageName, closeProjectedTasks)
            val unknownOnCard = observed.any { it.displayId == displayId && it.packageName.isEmpty() &&
                it.id in expectedDestinations }
            if (actionable.isEmpty() && !unknownOnCard) {
                val external = observed.filter { it.displayId != displayId && it.id in expectedDestinations &&
                    it.displayId != expectedDestinations[it.id] }.associate { it.id to it.displayId }
                // An independent move can supersede our command. Confirm the task stays off
                // this card before retiring it, without issuing a command on another display.
                if (external.isEmpty() || external == lastExternal) return true
                lastExternal = external
            } else {
                lastExternal = null
            }
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
        exec: suspend (String) -> ExecResult = shellExec,
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
        exec: suspend (String) -> ExecResult = shellExec,
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
        exec: suspend (String) -> ExecResult = shellExec,
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
