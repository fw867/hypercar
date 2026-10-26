package io.github.hypercar.spike

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.View
import io.github.hypercar.core.RustCoreBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S3：自绘画面 → MediaCodec(avc) → `nativePushVideoFrame`（SPIKE-P0）。
 *
 * 结构就是 P1 `CarUiCaster` 的雏形：一块 1920x720 的 VirtualDisplay 输出到编码器
 * InputSurface，`Presentation` 往这块屏上装我们的 View，编码器输出 Annex B 直接喂引擎。
 *
 * 关键细节（踩了会表现为「编码器在跑、车机还是待机卡片」）：
 *  - 首帧必须带 SPS/PPS——`screen.rs` 只有看到 SPS/PPS 才发 VideoConfig，否则丢帧；
 *    这里用 `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` + 自己把 csd 拼到关键帧前面，双保险。
 *  - `nativeSetVideoSize` 只改画布宽高，不触发 VideoConfig；先调它，帧里再带 SPS。
 */
class SpikeCaster(
    private val context: Context,
    private val width: Int = 1920,
    private val height: Int = 720,
    private val fps: Int = 60,
    private val bitrate: Int = 8_000_000,
    private val tag: String = "S3",
) {

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var thread: HandlerThread? = null

    @Volatile
    private var running = false

    private val csdFromConfig = java.io.ByteArrayOutputStream()
    private val csdFromFormat = java.io.ByteArrayOutputStream()

    private var frames = 0L
    private var bytesTotal = 0L
    private var bytesAtStats = 0L
    private var framesAtStats = 0L
    private var statsAtNs = System.nanoTime()
    private var startedAtNs = 0L
    private var lastPtsUs = 0L
    private var ptsUnitWarned = false

    private val latencies = LongArray(2048)
    private var latIdx = 0
    private val gaps = LongArray(2048)
    private var gapIdx = 0

    val isRunning: Boolean get() = running

    /** 编码器 InputSurface：可以由 shell UserService 拿去建屏（S3 推荐路径）。 */
    val encoderSurface: Surface? get() = inputSurface

    /** 只启编码器，不建屏（建屏交给 shell UserService / 调用方）。 */
    fun startEncoder(): String {
        if (running) return "已经在跑"
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            SpikeReport.add(tag, "configure 带 profile/level/bitrateMode 失败（${t.message}），去掉后重试")
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            format.removeKey(MediaFormat.KEY_BITRATE_MODE)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = enc.createInputSurface()
        val ht = HandlerThread("hypercar-spike-enc")
        ht.start()
        val handler = Handler(ht.looper)
        enc.setCallback(callback, handler)
        enc.start()

        codec = enc
        inputSurface = surface
        thread = ht
        startedAtNs = System.nanoTime()
        statsAtNs = startedAtNs
        running = true

        // 先对齐画布，再出帧（send_config 由帧里的 SPS/PPS 触发）
        RustCoreBridge.nativeSetVideoSize(width, height)
        val msg = "编码器启动：${width}x${height}@${fps} ${bitrate / 1000}kbps " +
            "surface=${surface.isValid} encoder=${enc.name}"
        SpikeReport.add(tag, msg)
        return msg
    }

    /** 用 App 自己的 VD + Presentation 出画面（无 Shizuku 时的兜底；锁屏时不合成）。 */
    fun attachOwnDisplay(): String {
        val surface = inputSurface ?: return "ERR 编码器未启动"
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return "ERR DisplayManager 不可用"
        val vd = dm.createVirtualDisplay(
            "hypercar-cast",
            width,
            height,
            240,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val displayId = vd.display.displayId
        val stage = Presentation(context, vd.display).apply {
            setContentView(SpikeStageView(context, width, height, displayId, fps))
            show()
        }
        virtualDisplay = vd
        presentation = stage
        val msg = "app 建屏 displayId=$displayId（Presentation）"
        SpikeReport.add(tag, msg)
        return msg
    }

    fun start(): String = startEncoder() + " / " + attachOwnDisplay()

    fun stop() {
        if (!running) return
        running = false
        runCatching { presentation?.dismiss() }
        runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { thread?.quitSafely() }
        presentation = null
        virtualDisplay = null
        codec = null
        inputSurface = null
        thread = null

        val secs = (System.nanoTime() - startedAtNs) / 1e9
        val avgFps = if (secs > 0) frames / secs else 0.0
        SpikeReport.add(
            tag,
            "停止：frames=$frames 时长=${"%.1f".format(secs)}s 平均fps=${"%.1f".format(avgFps)} " +
                "总字节=$bytesTotal 延迟=${SpikeReport.stats(ring(latencies, latIdx))}µs " +
                "帧间隔=${SpikeReport.stats(ring(gaps, gapIdx))}µs",
        )
    }

    private fun ring(src: LongArray, idx: Int): LongArray {
        val n = minOf(idx, src.size)
        if (n <= 0) return LongArray(0)
        val out = LongArray(n)
        for (i in 0 until n) out[i] = src[(idx - n + i).mod(src.size)]
        return out
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface 输入模式下不应出现；出现就还回去，别让编码器卡住输入队列
            runCatching { codec.getInputBuffer(index)?.clear() }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            synchronized(lock) { csdFromConfig.write(data) }
                            SpikeReport.add(tag, "codec-config ${data.size}B")
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val prefix = if (key) synchronized(lock) {
                                when {
                                    csdFromConfig.size() > 0 -> csdFromConfig.toByteArray()
                                    csdFromFormat.size() > 0 -> csdFromFormat.toByteArray()
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val payload = if (prefix != null) prefix + data else data
                            val now = System.nanoTime()
                            RustCoreBridge.nativePushVideoFrame(payload, info.presentationTimeUs)
                            onFrame(info.presentationTimeUs, payload.size, now)
                        }
                    }
                }
            } catch (t: Throwable) {
                SpikeReport.add(tag, "输出帧处理异常 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            SpikeReport.add(tag, "outputFormat: $format")
            for (name in listOf("csd-0", "csd-1")) {
                val b = format.getByteBuffer(name) ?: continue
                val dup = b.duplicate()
                dup.position(0)
                val arr = ByteArray(dup.remaining())
                dup.get(arr)
                synchronized(lock) { csdFromFormat.write(arr) }
                SpikeReport.add(tag, "format $name=${arr.size}B")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            SpikeReport.add(tag, "编码器错误: ${e.diagnosticInfo}（recoverable=${e.isRecoverable}）")
        }
    }

    private fun onFrame(ptsUs: Long, size: Int, nowNs: Long) {
        var statsLine: String? = null
        synchronized(lock) {
            frames++
            bytesTotal += size
            val latUs = nowNs / 1000 - ptsUs
            latencies[latIdx++ % latencies.size] = latUs
            if (!ptsUnitWarned && latUs < -1_000_000) {
                ptsUnitWarned = true
                SpikeReport.add(tag, "⚠ 延迟为负（${latUs}µs）——PTS 单位可能不是 µs，请检查")
            }
            if (lastPtsUs > 0 && ptsUs > lastPtsUs) {
                gaps[gapIdx++ % gaps.size] = ptsUs - lastPtsUs
            }
            lastPtsUs = ptsUs
            val now = System.nanoTime()
            if (now - statsAtNs >= 1_000_000_000L) {
                val secs = (now - statsAtNs) / 1e9
                val fpsNow = (frames - framesAtStats) / secs
                val kbps = (bytesTotal - bytesAtStats) * 8.0 / secs / 1000.0
                statsLine = "fps=${"%.1f".format(fpsNow)} frames=$frames kbps=${kbps.toInt()} " +
                    "lat=${SpikeReport.stats(ring(latencies, latIdx))}µs " +
                    "gap=${SpikeReport.stats(ring(gaps, gapIdx))}µs"
                statsAtNs = now
                framesAtStats = frames
                bytesAtStats = bytesTotal
            }
        }
        statsLine?.let { SpikeReport.add(tag, it) }
    }
}

/** 编码用的测试画面：大时钟 + 帧号 + 移动色块（持续 invalidate 保证 60fps 有内容变化）。 */
private class SpikeStageView(
    context: Context,
    private val targetW: Int,
    private val targetH: Int,
    private val displayId: Int,
    private val targetFps: Int,
) : View(context) {

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 96f
        isFakeBoldText = true
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 220, 255) }
    private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 230, 140) }
    private val grid = Paint().apply { color = Color.rgb(28, 40, 60); strokeWidth = 2f }

    private var frame = 0L
    private val startNs = System.nanoTime()

    override fun onDraw(canvas: Canvas) {
        frame++
        val tMs = (System.nanoTime() - startNs) / 1_000_000
        canvas.drawColor(Color.rgb(6, 10, 18))
        var x = 0f
        while (x < targetW) {
            canvas.drawLine(x, 0f, x, targetH.toFloat(), grid)
            x += 120f
        }
        var y = 0f
        while (y < targetH) {
            canvas.drawLine(0f, y, targetW.toFloat(), y, grid)
            y += 120f
        }

        textPaint.textSize = 150f
        canvas.drawText(clock.format(Date()), 80f, 200f, textPaint)
        textPaint.textSize = 64f
        canvas.drawText(
            "SPIKE CAST  display=$displayId  ${targetW}x${targetH}@${targetFps}",
            80f,
            320f,
            accent,
        )
        canvas.drawText("frame=$frame   uptime=${tMs}ms", 80f, 410f, textPaint)

        val speed = 0.6f
        val barX = (tMs * speed) % targetW.toFloat()
        canvas.drawRect(barX, 500f, barX + 220f, 700f, green)
        val bar2X = (targetW - ((tMs * 0.35f) % targetW.toFloat()))
        canvas.drawRect(bar2X - 160f, 500f, bar2X, 700f, accent)

        postInvalidateOnAnimation()
    }
}
