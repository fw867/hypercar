package io.github.hypercar.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import io.github.hypercar.carui.MapCardInputState
import io.github.hypercar.carui.MapPreview
import io.github.hypercar.carui.CarReceiverUi
import io.github.hypercar.navigation.AmapLinkNavigation
import io.github.hypercar.navigation.AmapNavigation
import io.github.hypercar.navigation.ClusterNavigationRoute
import io.github.hypercar.navigation.NavigationCue
import io.github.hypercar.navigation.isUsableInstrumentGuidance
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.SpikeCaster
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Dedicated type-111 AVC producer. Only the AMap card supplies map pixels. */
class ClusterMapEncoder private constructor(
    private val width: Int, private val height: Int, private val safe: RectF, fps: Int, private val round: Long,
    private val card: MapCardInputState.Binding,
) : AutoCloseable {
    companion object {
        private val navigationRoute = ClusterNavigationRoute()

        private fun renderable(cue: NavigationCue): Boolean =
            cue.isUsableInstrumentGuidance() && !CarReceiverUi.state.value.limits.blockMapApp

        private fun routeStatus(): ClusterNavigationRoute.Status = synchronized(navigationRoute) {
            val now = SystemClock.elapsedRealtime()
            navigationRoute.update(now, AmapLinkNavigation.clusterRoute(now), AmapNavigation.clusterRoute(now))
        }

        private fun mapStatus(route: ClusterNavigationRoute.Status, card: MapCardInputState.Binding?): Long {
            val ready = HyperCarRepo.mapNavigationAllowed && route.cue?.let(::renderable) == true && card != null
            return (route.round shl 2) or (if (route.active) 2L else 0L) or (if (ready) 1L else 0L)
        }

        internal fun mapStatus(): Long = mapStatus(routeStatus(), MapPreview.currentInstrumentCard())

        internal fun mapObservation(): LongArray {
            val route = routeStatus()
            val card = MapPreview.currentInstrumentCard()
            return longArrayOf(mapStatus(route, card), card?.generation ?: 0L)
        }

        internal fun capable(): Boolean = HyperCarRepo.mapNavigationAllowed && runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
            }
        }.getOrDefault(false)

        internal fun available(width: Int, height: Int): Boolean {
            if (!HyperCarRepo.mapNavigationAllowed) return false
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null }.getOrDefault(false)
        }

        internal fun open(w: Int, h: Int, x: Int, y: Int, sw: Int, sh: Int, fps: Int, round: Long, cardGeneration: Long): ClusterMapEncoder? {
            if (fps !in 1..2 || !available(w, h) || sw < 64 || sh < 48 || x < 0 || y < 0 || x + sw > w || y + sh > h) return null
            val card = MapPreview.currentInstrumentCard()?.takeIf { it.generation == cardGeneration } ?: return null
            val safe = RectF(x.toFloat(), y.toFloat(), (x + sw).toFloat(), (y + sh).toFloat())
            val encoder = ClusterMapEncoder(w, h, safe, fps, round, card)
            return try {
                val initial = encoder.snapshot() ?: return null
                try {
                    encoder.caster.startEncoder()
                    encoder.bridge = CodecSurfaceBridge(checkNotNull(encoder.caster.encoderSurface), w, h, w, h)
                    encoder.paintFrame(initial)
                } finally { initial.map.recycle() }
                encoder
            } catch (error: Exception) { encoder.close(); throw error }
        }
    }

    private data class Frame(val cue: NavigationCue, val map: Bitmap)
    private data class Sample(val bytes: ByteArray, val pts: Long)
    private val samples = ArrayBlockingQueue<Sample>(2)
    private var submittedPts = 0L
    private var codecClockOffset: Long? = null
    @Volatile private var overflow = false
    private val caster = SpikeCaster(width, height, fps = fps, bitrate = 1_200_000, tag = "CLUSTER", receiverRateLimited = fps == 1,
        independentOutput = { bytes, pts -> if (!samples.offer(Sample(bytes, pts))) overflow = true })
    private var bridge: CodecSurfaceBridge? = null
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Every access unit from this encoder belongs to the card that supplied its first snapshot.
    fun isCardCurrent(): Boolean = MapPreview.currentInstrumentCard() === card

    fun acquireCardWrite(): Long = MapPreview.acquireInstrumentCardWrite(card)

    fun releaseCardWrite(stamp: Long) = MapPreview.releaseInstrumentCardWrite(stamp)

    private fun navigationCue(): NavigationCue? {
        if (!HyperCarRepo.mapNavigationAllowed || !isCardCurrent()) return null
        return routeStatus().takeIf { it.round == round && it.active }?.cue?.takeIf(::renderable)
    }

    private fun snapshot(): Frame? {
        if (navigationCue() == null) return null
        val map = MapPreview.clusterMap(card, safe.width().toInt(), safe.height().toInt()) ?: return null
        navigationCue()?.let { return Frame(it, map) }
        map.recycle()
        return null
    }

    private fun paintFrame(frame: Frame?) {
        val compositor = checkNotNull(bridge)
        val surface = compositor.input
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            if (frame != null) drawGuidance(canvas, frame)
        } finally { surface.unlockCanvasAndPost(canvas) }
        submittedPts = SystemClock.elapsedRealtimeNanos() / 1000
        compositor.render(submittedPts)
    }

    private fun drawGuidance(canvas: android.graphics.Canvas, frame: Frame) {
        canvas.clipRect(safe)
        val map = frame.map
        val left = safe.centerX() - map.width / 2f
        val top = safe.centerY() - map.height / 2f
        canvas.drawBitmap(map, null, RectF(left, top, left + map.width, top + map.height), paint)
        paint.color = 0xEA10232B.toInt()
        val banner = minOf(safe.height() * 0.36f, safe.width() * 0.22f)
        canvas.drawRect(safe.left, safe.bottom - banner, safe.right, safe.bottom, paint)
        paint.color = Color.WHITE
        paint.textSize = banner * 0.47f
        val text = listOfNotNull(frame.cue.distanceMeters?.let { "${it}米" }, frame.cue.instruction).joinToString("  ")
        val label = TextUtils.ellipsize(text, paint, safe.width() - banner * 0.32f, TextUtils.TruncateAt.END)
        canvas.drawText(label.toString(), safe.left + banner * 0.16f, safe.bottom - banner * 0.5f - (paint.ascent() + paint.descent()) / 2, paint)
    }

    // Only return the sample submitted by this draw. Delayed codec callbacks may arrive after a timeout.
    private fun currentSample(timeout: Long): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!overflow) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val sample = samples.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            val offset = codecClockOffset ?: (sample.pts - submittedPts).also { codecClockOffset = it }
            if (kotlin.math.abs(sample.pts - offset - submittedPts) <= 1000L) return sample.bytes
        }
        return null
    }

    fun firstSample(): ByteArray? = guidanceSample(1000)

    fun clearSample(): ByteArray? {
        if (!HyperCarRepo.mapNavigationAllowed || !isCardCurrent() || routeStatus().round != round) return null
        caster.requestSyncFrame()
        paintFrame(null)
        return currentSample(700)?.takeIf { HyperCarRepo.mapNavigationAllowed && isCardCurrent() && routeStatus().round == round }
    }

    fun nextSample(sync: Boolean): ByteArray? {
        val next = snapshot() ?: return null
        try {
            if (sync) caster.requestSyncFrame()
            paintFrame(next)
        } finally { next.map.recycle() }
        return guidanceSample(700)
    }

    private fun guidanceSample(timeout: Long): ByteArray? = currentSample(timeout)?.takeIf { navigationCue() != null }

    override fun close() {
        try { bridge?.close() } finally { caster.stop() }
        samples.clear()
    }
}
