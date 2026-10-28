package io.github.hypercar.settings

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Keep the receiver's viewport ratio; spend pixels on the stream, not on invisible oversampling. */
data class VideoEncodingPlan(val width: Int, val height: Int, val fps: Int, val densityDpi: Int) {
    val bitrate: Int get() = (width.toLong() * height * fps / 5).coerceIn(2_000_000L, 8_000_000L).toInt()

    companion object {
        fun fit(viewportWidth: Int, viewportHeight: Int, requestedFps: Int = 30): VideoEncodingPlan {
            val w = viewportWidth.coerceIn(320, 16384)
            val h = viewportHeight.coerceIn(240, 16384)
            val scale = min(1.0, min(1920.0 / w, min(1080.0 / h, sqrt(1_100_000.0 / (w.toDouble() * h)))))
            fun even(value: Double) = ((value / 2).toInt() * 2).coerceAtLeast(2)
            val outW = even(w * scale)
            val outH = even(h * scale)
            // Preserve the existing ultrawide layout in dp while reducing encoded pixels.
            val dpi = if (w.toDouble() / h > 2.5) (outW * 240.0 / 2560).roundToInt() else 160
            return VideoEncodingPlan(outW, outH, requestedFps.coerceIn(20, 60), dpi.coerceIn(120, 240))
        }
    }
}
