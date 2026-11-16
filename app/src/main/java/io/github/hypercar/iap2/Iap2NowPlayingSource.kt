package io.github.hypercar.iap2

import android.graphics.Bitmap
import io.github.hypercar.carui.MusicBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Bitmap conversion stays off both iAP2 receive loops and the main thread. */
internal object Iap2NowPlayingSource {
    private data class Track(val app: String, val item: Long?, val title: String, val artist: String, val album: String)
    private val lock = Any()
    private var track: Track? = null
    private var bitmap = java.lang.ref.WeakReference<Bitmap>(null)
    private var bitmapGeneration = 0
    private var generation = 0L
    private var encoded: ByteArray? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { task ->
        Thread(task, "iap2-artwork").apply { isDaemon = true }
    } }

    fun snapshot(): Iap2NowPlaying = synchronized(lock) {
        val state = MusicBridge.state.value
        val image = state.albumArt?.takeIf { state.available && !it.isRecycled }
        val nextTrack = if (state.available) Track(state.packageName, state.mediaDetails.itemId,
            state.title, state.artist, state.album) else null
        val imageGeneration = runCatching { image?.generationId ?: 0 }.getOrDefault(0)
        if (track != nextTrack || bitmap.get() !== image || bitmapGeneration != imageGeneration) {
            // QQ republishes identical artwork in fresh Bitmap instances. Keep the last
            // verified JPEG for this track while checking the replacement off-thread.
            if (track != nextTrack || image == null) encoded = null
            track = nextTrack
            bitmap = java.lang.ref.WeakReference(image)
            bitmapGeneration = imageGeneration
            val ticket = ++generation
            if (image != null) worker.execute {
                if (synchronized(lock) { generation != ticket }) return@execute
                val result = runCatching { encode(image) }.getOrNull()
                synchronized(lock) {
                    if (generation == ticket && !encoded.contentEquals(result)) encoded = result
                }
            }
        }
        if (!state.available) return@synchronized Iap2NowPlaying()
        Iap2NowPlaying(state.title, state.artist, state.album, state.appLabel,
            state.durationMs, state.positionMs, state.playbackStatus, encoded, appBundleId = state.packageName,
            details = state.mediaDetails)
    }
    private fun encode(image: Bitmap): ByteArray? {
        val scale = minOf(1.0, 320.0 / maxOf(image.width, image.height))
        val resized = if (scale < 1) Bitmap.createScaledBitmap(image,
            (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true) else image
        return try {
            val bytes = ByteArrayOutputStream()
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 80, bytes)) null
            else bytes.toByteArray().takeIf { it.isNotEmpty() && it.size <= Iap2NowPlayingSession.MAX_ARTWORK }
        } finally { if (resized !== image) resized.recycle() }
    }
}
