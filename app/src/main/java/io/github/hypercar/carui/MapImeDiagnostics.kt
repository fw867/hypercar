package io.github.hypercar.carui

import android.os.SystemClock
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One read-only observer per fullscreen visit. The input queue only posts a timestamp. */
internal class MapImeDiagnostics(private val mapDisplay: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taps = Channel<Long>(Channel.CONFLATED)

    init {
        scope.launch {
            for (started in taps) {
                if (!HyperCarRepo.logRecording) continue
                val policy = SpikeShell.readDisplayImePolicy(mapDisplay)
                var lastTap = started
                var sample = 0
                do {
                    val dump = ShizukuTools.exec(DUMP, timeoutMs = 7_000)
                    if (!isActive || !HyperCarRepo.logRecording) break
                    val snapshot = MapImeSnapshot.parse(mapDisplay, dump.out)
                    val now = SystemClock.elapsedRealtime()
                    HyperCarRepo.log("MAP-IME: tap=$started sample=$sample elapsedMs=${now - started} " +
                        "mapDisplay=$mapDisplay imePolicy=$policy ${snapshot.summary}")
                    lastTap = taps.tryReceive().getOrNull() ?: lastTap
                    sample++
                    // IMMS can mark input shown before WM actually displays the keyboard.
                    val idleMs = now - lastTap
                    if (idleMs >= 5_000 || (sample >= 2 && snapshot.visible && idleMs >= 1_000)) break
                    delay(1_000)
                } while (isActive && HyperCarRepo.logRecording)
            }
        }
    }

    fun tap() {
        if (HyperCarRepo.logRecording) taps.trySend(SystemClock.elapsedRealtime())
    }

    fun close() {
        scope.cancel()
        taps.close()
    }

    private companion object {
        // Do not export the raw dump, editor contents, window titles or input history.
        val DUMP = """
            dumpsys -t 2 window displays | grep -E 'Display: mDisplayId=|mCurrentFocus=|mImeInputTarget=|mInputMethodTarget='
            echo '#IMMS'
            dumpsys -t 2 input_method | grep -E '^[[:space:]]*(mCurClient|mCurId|mCurImeId|mCurMethodId|mSelectedImeId|mShowRequested|mInputShown)='
            echo '#WINDOWS'
            dumpsys -t 2 window windows | grep -E 'Window #[0-9]+ Window\{|mDisplayId=|ty=(INPUT_METHOD|2011)|isVisible='
        """.trimIndent()
    }
}

internal data class MapImeSnapshot(val summary: String, val visible: Boolean) {
    companion object {
        private val component = Regex("""[A-Za-z][\w]*(?:\.[\w]+)+/[\w.$]+""")
        private val displayHeader = Regex("""Display: mDisplayId=(\d+)""")
        private val clientDisplay = Regex("""\b(?:mSelfReportedDisplayId|selfReportedDisplayId|displayId)=(\d+)""")
        private val shown = Regex("""\bmInputShown=(true|false)\b""")
        private val windowHeader = Regex("""(?m)^[ \t]*Window #\d+ Window\{""")
        private val imeType = Regex("""\bty=(INPUT_METHOD|2011)\b""")
        private val windowDisplay = Regex("""\bmDisplayId=(\d+)\b""")
        private val visibility = Regex("""\bisVisible=(true|false)\b""")

        fun parse(mapDisplay: Int, dump: String): MapImeSnapshot {
            val displays = dump.substringBefore("#IMMS")
            val imms = dump.substringAfter("#IMMS", "").substringBefore("#WINDOWS")
            val windows = dump.substringAfter("#WINDOWS", "")
            fun imeValue(vararg keys: String): String {
                val value = imms.lineSequence().map { it.trim() }
                    .firstOrNull { line -> keys.any { line.startsWith("$it=") } }
                    ?.substringAfter('=') ?: return "unknown"
                return component.matchAt(value, 0)?.value ?: if (value.startsWith("null")) "none" else "unknown"
            }
            var id: Int? = null
            val displayFocus = mutableMapOf<Int, String>()
            var target = "unknown"
            for (line in displays.lineSequence()) {
                displayHeader.find(line)?.let { id = it.groupValues[1].toIntOrNull() }
                val focusDisplay = id ?: continue
                val field = line.trim().substringBefore('=')
                val value = line.substringAfter('=')
                val label = if (value.startsWith("null")) "none" else component.find(value)?.value ?: "unknown"
                when (field) {
                    "mCurrentFocus" -> displayFocus[focusDisplay] = label
                    "mImeInputTarget", "mInputMethodTarget" -> if (focusDisplay == mapDisplay) target = label
                }
            }
            val visibleIds = sortedSetOf<Int>()
            var hidden = false
            var incomplete = false
            for (window in windowHeader.split(windows).drop(1)) {
                if (!imeType.containsMatchIn(window) && !window.lineSequence().first().endsWith(" InputMethod}:")) continue
                val display = windowDisplay.find(window)?.groupValues?.get(1)?.toIntOrNull()
                when (visibility.find(window)?.groupValues?.get(1)) {
                    "true" -> if (display != null) visibleIds.add(display) else incomplete = true
                    "false" -> hidden = true
                    else -> incomplete = true
                }
            }
            val actual = when {
                visibleIds.isNotEmpty() -> visibleIds.joinToString(",")
                hidden && !incomplete -> "hidden"
                else -> "unknown"
            }
            val client = imms.lineSequence().firstOrNull { it.trimStart().startsWith("mCurClient=") }.orEmpty()
            val inputDisplay = clientDisplay.find(client)?.groupValues?.get(1) ?: "unknown"
            val inputShown = shown.find(imms)?.groupValues?.get(1) ?: "unknown"
            val clientFocus = displayFocus[inputDisplay.toIntOrNull()] ?: "unknown"
            val mapFocus = displayFocus[mapDisplay] ?: "unknown"
            return MapImeSnapshot("mapFocus=$mapFocus imeTarget=$target inputClientDisplay=$inputDisplay clientFocus=$clientFocus " +
                "currentIme=${imeValue("mCurId", "mCurImeId")} selectedIme=${imeValue("mCurMethodId", "mSelectedImeId")} " +
                "inputShown=$inputShown keyboardDisplay=$actual", visibleIds.isNotEmpty())
        }
    }
}
