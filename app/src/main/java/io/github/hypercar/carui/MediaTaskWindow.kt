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
    ): List<String> {
        if (previous == null) return emptyList()
        val origins = previous.associateBy { it.id }
        return current.filter { it.displayId == displayId }.groupBy { it.rootId }.mapNotNull { (root, tasks) ->
            if (tasks.any { it.packageName != packageName }) return@mapNotNull null
            val destinations = tasks.map { origins[it.id]?.displayId }.distinct()
            if (destinations.size != 1) return@mapNotNull null
            val destination = destinations.single()
            if (destination == null) "am stack remove $root"
            else if (destination != displayId) "am display move-stack $root $destination"
            else null
        }
    }

    suspend fun snapshot(): List<Task>? = readStackList()?.let(::tasks)?.takeIf { it.isNotEmpty() }

    suspend fun releaseTasks(displayId: Int, packageName: String, previous: List<Task>?) {
        val current = snapshot() ?: return
        for (command in releaseCommands(previous, current, displayId, packageName)) {
            val result = ShizukuTools.exec(command, 2_000)
            if (!result.ok) {
                io.github.hypercar.session.HyperCarRepo.log("VIDEO-CARD: cleanup display=$displayId command=$command exit=${result.code}")
            }
        }
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
