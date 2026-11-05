package io.github.hypercar.spike

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.view.Surface
import io.github.hypercar.core.RustCoreBridge

/**
 * Production D1/D2 encoder: Surface → MediaCodec AVC → nativePushVideoFrame.
 *
 * Virtual displays are owned by the shell service; this class owns only encoding resources.
 *
 * 关键细节（踩了会表现为「编码器在跑、车机还是待机卡片」）：
 *  - 首帧必须带 SPS/PPS——`screen.rs` 只有看到 SPS/PPS 才发 VideoConfig，否则丢帧；
 *    这里用 `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` + 自己把 csd 拼到关键帧前面，双保险。
 *  - `nativeSetVideoSize` 只改画布宽高，不触发 VideoConfig；先调它，帧里再带 SPS。
 */
class SpikeCaster(
    private val width: Int = 1920,
    private val height: Int = 720,
    private val fps: Int = 60,
    private val bitrate: Int = 8_000_000,
    private val tag: String = "S3",
) {

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: HandlerThread? = null

    @Volatile
    private var running = false

    /** P2：双屏切源时暂停/恢复本编码器向 Rust 推帧（编码器本身继续跑，切换时只需 requestSyncFrame）。 */
    @Volatile
    private var pushing = true

    private val csdFromConfig = java.io.ByteArrayOutputStream()
    private val csdFromFormat = java.io.ByteArrayOutputStream()

    private var frames = 0L
    private var bytesTotal = 0L
    private var bytesAtStats = 0L
    private var framesAtStats = 0L
    private var statsAtNs = System.nanoTime()
    private var startedAtNs = 0L
    private var awaitingKeyFrame = true
    private var sourceFrames = 0L
    private var sourceFramesAtStats = 0L
    private var sourceFps = 60.0
    private var lastPtsUs = 0L
    private var ptsUnitWarned = false

    private val latencies = LongArray(2048)
    private var latIdx = 0
    private val gaps = LongArray(2048)
    private var gapIdx = 0

    val isRunning: Boolean get() = running

    /** 编码器 InputSurface：可以由 shell UserService 拿去建屏（S3 推荐路径）。 */
    val encoderSurface: Surface? get() = inputSurface

    /** P2：切源时暂停/恢复推帧；暂停期间仍排空编码器，恢复时先请求关键帧。 */
    fun setPushing(enabled: Boolean) {
        synchronized(lock) {
            if (enabled && !pushing) awaitingKeyFrame = true
            pushing = enabled
        }
    }

    /** P2：暂停/恢复编码器（MediaCodec suspend 丢帧，不释放 InputSurface/显示）。 */
    fun setSuspended(suspended: Boolean) {
        synchronized(lock) {
            runCatching {
                codec?.setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0)
                    },
                )
            }
        }
    }

    /** 让编码器立即产出关键帧（切源后解码器需要 IDR + SPS/PPS 重新同步）。 */
    fun requestSyncFrame() {
        synchronized(lock) {
            runCatching {
                codec?.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
                )
            }
        }
    }

    /** 只启编码器，不建屏（建屏交给 shell UserService / 调用方）。 */
    fun startEncoder(): String {
        if (running) return "已经在跑"
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // Let MediaCodec choose the level required by the native size and frame rate.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            // 低延迟：实时优先级、无 B 帧、0 帧输入缓冲；静止 100ms 补发上一帧，
            // 避免点击后的尾帧卡在编码器 pipeline 里。
            runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
            runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
            runCatching { setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching { setInteger(MediaFormat.KEY_LATENCY, 0) }
            }
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = enc
        try {
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
            inputSurface = surface
            // 把编码器输入面的帧率钉到配置值：设备/虚拟屏是 90Hz 时 Compose 会按 90 出帧，
            // 不钉要么被发送端丢帧成 45fps 抖动，要么 90~110fps 压垮 AirPlay 接收端。
            runCatching {
                surface.setFrameRate(
                    fps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            }
            val ht = HandlerThread("hypercar-spike-enc")
            thread = ht
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
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        val wasRunning = running
        running = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { thread?.quitSafely() }
        codec = null
        inputSurface = null
        thread = null

        if (!wasRunning) return
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
                        } else if (pushing) {
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
                            sourceFrames++
                            // Never discard encoded reference frames. Rate limiting belongs before encoding.
                            synchronized(lock) {
                                if (pushing && (!awaitingKeyFrame || key)) {
                                    awaitingKeyFrame = false
                                    RustCoreBridge.nativePushVideoFrame(payload, info.presentationTimeUs)
                                    onFrame(info.presentationTimeUs, payload.size, now)
                                }
                            }
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
            synchronized(lock) { csdFromFormat.reset(); csdFromConfig.reset() }
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
                sourceFps = (sourceFrames - sourceFramesAtStats) / secs
                sourceFramesAtStats = sourceFrames
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
