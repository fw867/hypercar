package io.github.hypercar.carui

import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class UiLimits(val enabled: Boolean = false, val elements: Set<String> = emptySet()) {
    val musicLists get() = "musicLists" in elements
    val nonMusicLists get() = "nonMusicLists" in elements
    // External app surfaces can already contain a keyboard/list when restrictions arrive.
    val blockMapApp get() = "softKeyboard" in elements || nonMusicLists || "japanMaps" in elements
    val blockMusicApp get() = "softKeyboard" in elements || musicLists || nonMusicLists
    val blockPhoneApp get() = "softPhoneKeypad" in elements || "softKeyboard" in elements || nonMusicLists

    fun allowsProjection(packageName: String, dialer: String?): Boolean = when {
        packageName == dialer -> !blockPhoneApp
        MusicApps.isKnownPlayer(packageName) -> !blockMusicApp
        ShortVideoApps.isSupported(packageName) -> !nonMusicLists && "softKeyboard" !in elements
        // A user-selected launcher entry can expose any of the restricted controls.
        else -> elements.isEmpty()
    }
}

internal enum class CarUiDestination { MAPS, MUSIC, NOW_PLAYING, PHONE }

internal data class ReceiverFocus(val rect: DisplayRect, val direction: Int, val width: Int, val height: Int) {
    fun onCanvas(w: Int, h: Int): DisplayRect {
        fun x(pixel: Int) = (pixel.toDouble() * w / width).roundToInt()
        fun y(pixel: Int) = (pixel.toDouble() * h / height).roundToInt()
        return DisplayRect(x(rect.x), y(rect.y), x(rect.right) - x(rect.x), y(rect.bottom) - y(rect.y))
    }

    fun <T> target(buttons: Map<T, DisplayRect>, w: Int, h: Int): T? {
        val origin = onCanvas(w, h)
        val x = origin.x + origin.width / 2.0
        val y = origin.y + origin.height / 2.0
        return when (direction) {
            View.FOCUS_FORWARD -> buttons.keys.firstOrNull()
            View.FOCUS_BACKWARD -> buttons.keys.lastOrNull()
            else -> buttons.entries.filter { (_, b) ->
                when (direction) {
                    View.FOCUS_UP -> b.y < origin.bottom
                    View.FOCUS_DOWN -> b.bottom > origin.y
                    View.FOCUS_LEFT -> b.x < origin.right
                    View.FOCUS_RIGHT -> b.right > origin.x
                    else -> false
                }
            }.minByOrNull { (_, b) -> kotlin.math.hypot(b.x + b.width / 2.0 - x, b.y + b.height / 2.0 - y) }?.key
        }
    }

    companion object {
        fun from(p: JSONObject): ReceiverFocus? {
            fun number(key: String) = (p.opt(key) as? Number)?.toLong()?.takeIf { it in -65535L..65535L }?.toInt()
            val direction = when (number("focusHeading")) {
                1 -> View.FOCUS_UP
                2 -> View.FOCUS_DOWN
                4 -> View.FOCUS_LEFT
                8 -> View.FOCUS_RIGHT
                16 -> View.FOCUS_FORWARD
                32 -> View.FOCUS_BACKWARD
                else -> return null
            }
            val rect = DisplayRect(number("originXPixels") ?: return null, number("originYPixels") ?: return null,
                number("widthPixels")?.takeIf { it >= 0 } ?: return null,
                number("heightPixels")?.takeIf { it >= 0 } ?: return null)
            return ReceiverFocus(rect, direction, number("displayWidth")?.takeIf { it > 0 } ?: return null,
                number("displayHeight")?.takeIf { it > 0 } ?: return null)
        }
    }
}

internal data class UiRequest<T>(val sequence: Long, val value: T)
internal data class ReceiverUiState(
    val session: Long = 0,
    val screenOwned: Boolean? = null,
    val allowsFocusTransfer: Boolean = true,
    val limits: UiLimits = UiLimits(),
    val destination: CarUiDestination? = null,
    val route: UiRequest<CarUiDestination>? = null,
    val focus: UiRequest<ReceiverFocus>? = null,
)

internal open class ReceiverUiStore {
    private val current = MutableStateFlow(ReceiverUiState())
    val state = current.asStateFlow()
    private var sequence = 0L

    @Synchronized fun handle(kind: String, payload: String) {
        val p = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val old = current.value
        current.value = when (kind) {
            "setLimitedUI" -> {
                val enabled = p.opt("limitedUI") as? Boolean ?: return
                val array = p.optJSONArray("limitedUIElements")
                val elements = if (enabled && array != null) buildSet {
                    for (index in 0 until array.length()) {
                        val name = array.optString(index)
                        if (name in limitedElements) add(name)
                    }
                } else emptySet()
                old.copy(limits = UiLimits(enabled, elements))
            }
            "requestUI" -> {
                if (old.screenOwned == false) return
                val destination = when (p.optString("url")) {
                    "maps:" -> CarUiDestination.MAPS
                    "music:" -> CarUiDestination.MUSIC
                    "nowplaying:" -> CarUiDestination.NOW_PLAYING
                    "mobilephone:" -> CarUiDestination.PHONE
                    else -> return
                }
                old.copy(route = UiRequest(++sequence, destination), focus = null)
            }
            "accessoryGiveFocus" -> {
                if (old.screenOwned == false || !old.allowsFocusTransfer) return
                old.copy(focus = UiRequest(++sequence, ReceiverFocus.from(p) ?: return))
            }
            "screenOwned" -> when (p.opt("owned") as? Boolean) {
                true -> old.copy(screenOwned = true)
                false -> old.copy(screenOwned = false, route = null, focus = null)
                null -> return
            }
            else -> return
        }
    }

    @Synchronized fun consumeRoute(request: UiRequest<CarUiDestination>): Boolean {
        if (current.value.route != request) return false
        current.value = current.value.copy(route = null, destination = request.value)
        return true
    }

    @Synchronized fun showDestination(destination: CarUiDestination?) {
        current.value = current.value.copy(destination = destination)
    }

    @Synchronized fun consumeFocus(request: UiRequest<ReceiverFocus>): Boolean {
        if (current.value.focus != request) return false
        current.value = current.value.copy(focus = null)
        return true
    }

    @Synchronized fun setFocusTransferAllowed(allowed: Boolean) {
        val old = current.value
        current.value = old.copy(allowsFocusTransfer = allowed, focus = old.focus.takeIf { allowed })
    }

    @Synchronized fun reset() { current.value = ReceiverUiState(session = current.value.session + 1) }

    private val limitedElements = setOf("softKeyboard", "softPhoneKeypad", "musicLists", "nonMusicLists", "japanMaps")
}

internal object CarReceiverUi : ReceiverUiStore()
