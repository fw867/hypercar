package io.github.hypercar.carui

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.roundToInt

internal data class PhoneProjectionSize(val width: Int, val height: Int, val dpi: Int) {
    fun fit(availableWidth: Int, availableHeight: Int): Pair<Int, Int> {
        val scale = minOf(availableWidth.toDouble() / width, availableHeight.toDouble() / height)
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    fun expanded(width: Int, height: Int): PhoneProjectionSize {
        // Offer a tablet-sized window using the actual CarPlay canvas, without stretching a phone buffer.
        val tabletDpi = (minOf(width, height) * 160 / 600).coerceAtLeast(120)
        return PhoneProjectionSize(width, height, minOf(dpi, tabletDpi))
    }

    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): PhoneProjectionSize {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            display?.getRealMetrics(metrics)
            if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0 || metrics.densityDpi <= 0) {
                return PhoneProjectionSize(1080, 1920, 480)
            }
            return PhoneProjectionSize(minOf(metrics.widthPixels, metrics.heightPixels),
                maxOf(metrics.widthPixels, metrics.heightPixels), metrics.densityDpi)
        }
    }
}
