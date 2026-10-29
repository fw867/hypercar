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
            // 只做**偶数对齐**（H.264 4:2:0 的硬要求），并且**向下取整**：
            //  - 16 对齐是 CarLife 那条链路的经验要求（见 rust-core 的 safe_carlife_encoder_size），
            //    这里是我们自己车机 UI 的编码计划；实测接收端能吃 1280x372 / 2560x744
            //    这类非 16 对齐尺寸（待机母版正是这两套，桌面接收端与真车都正常出画）；
            //  - 向上取整会放大画面并**突破像素预算**：7680x4320 → 1408x800 = 1,126,400 > 1.1M，
            //    宽高比也偏出容差（1.76 vs 1.778），所以退回偶数 + floor。
            fun even(value: Double) = ((value / 2).toInt() * 2).coerceAtLeast(2)
            val outW = even(w * scale)
            val outH = even(h * scale)
            // Preserve the existing ultrawide layout in dp while reducing encoded pixels.
            val dpi = if (w.toDouble() / h > 2.5) (outW * 240.0 / 2560).roundToInt() else 160
            return VideoEncodingPlan(outW, outH, requestedFps.coerceIn(20, 60), dpi.coerceIn(120, 240))
        }
    }
}
