package io.github.hypercar.settings

import kotlin.math.roundToInt

/** Encode the receiver viewport at native size, aligned for H.264 4:2:0. */
data class VideoEncodingPlan(val width: Int, val height: Int, val fps: Int, val densityDpi: Int) {
    val bitrate: Int get() = (width.toLong() * height * fps / 5).coerceIn(2_000_000L, 8_000_000L).toInt()

    fun withReceiverMaxFps(limit: Int?): VideoEncodingPlan =
        if (limit != null && limit in 1 until fps) copy(fps = limit) else this

    /** Keep native resolution first, then try aligned canvases with the same orientation and aspect. */
    fun encoderCandidates(): List<VideoEncodingPlan> = buildList {
        fun addRates(mode: VideoEncodingPlan) {
            add(mode)
            if (fps > 30) add(mode.copy(fps = 30))
        }
        addRates(this@VideoEncodingPlan)
        val longest = maxOf(width, height)
        for (edge in listOf(longest, 2560, 1920, 1280, 960, 640).distinct()) {
            if (edge > longest) continue
            val scale = edge.toDouble() / longest
            fun aligned(pixels: Int) = (pixels * scale / 16).roundToInt().coerceIn(1, pixels / 16) * 16
            val w = aligned(width)
            val h = aligned(height)
            if (w == width && h == height) continue
            addRates(copy(width = w, height = h, densityDpi = (densityDpi * w.toDouble() / width).roundToInt().coerceAtLeast(120)))
        }
    }.distinct()

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
