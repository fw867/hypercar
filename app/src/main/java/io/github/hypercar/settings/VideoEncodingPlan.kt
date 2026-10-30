package io.github.hypercar.settings

import kotlin.math.roundToInt

/** Encode the receiver viewport at native size, aligned for H.264 4:2:0. */
data class VideoEncodingPlan(val width: Int, val height: Int, val fps: Int, val densityDpi: Int) {
    val bitrate: Int get() = (width.toLong() * height * fps / 5).coerceIn(2_000_000L, 8_000_000L).toInt()

    companion object {
        fun fit(viewportWidth: Int, viewportHeight: Int, requestedFps: Int = 60): VideoEncodingPlan {
            // Reject pathological dimensions; do not apply a software encoding pixel budget.
            val w = viewportWidth.coerceIn(320, 16384)
            val h = viewportHeight.coerceIn(240, 16384)
            val outW = w / 2 * 2
            val outH = h / 2 * 2
            val dpi = if (w.toDouble() / h > 2.5) (outW * 240.0 / 2560).roundToInt() else 160
            return VideoEncodingPlan(outW, outH, requestedFps.coerceIn(20, 60), dpi.coerceIn(120, 240))
        }
    }
}
