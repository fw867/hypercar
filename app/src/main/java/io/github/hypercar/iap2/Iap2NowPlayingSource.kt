package io.github.hypercar.iap2

import android.graphics.Bitmap
import io.github.hypercar.carui.MusicBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Bitmap conversion stays off both iAP2 receive loops and the main thread. */
internal object Iap2NowPlayingSource {
    private val lock = Any()
    private var bitmap = java.lang.ref.WeakReference<Bitmap>(null)
    private var bitmapGeneration = 0
    private var generation = 0L
    private var encoded: ByteArray? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { task ->
        Thread(task, "iap2-artwork").apply { isDaemon = true }
    } }

    fun snapshot(): Iap2NowPlaying {
        val state = MusicBridge.state.value
        val image = state.albumArt.takeIf { state.available }
        val bytes = synchronized(lock) {
            val imageGeneration = runCatching { image?.generationId ?: 0 }.getOrDefault(0)
            if (bitmap.get() !== image || bitmapGeneration != imageGeneration) {
                bitmap = java.lang.ref.WeakReference(image); bitmapGeneration = imageGeneration; encoded = null
                val ticket = ++generation
                if (image != null) worker.execute {
                    // Superseded work must not retain or publish another player's cover.
                    if (synchronized(lock) { generation != ticket }) return@execute
                    val result = runCatching { encode(image) }.getOrNull()
                    synchronized(lock) { if (generation == ticket) encoded = result }
                }
            }
            encoded
        }
        if (!state.available) return Iap2NowPlaying()
        return Iap2NowPlaying(state.title, state.artist, state.album, state.appLabel,
            state.durationMs, state.positionMs, state.playbackStatus, bytes)
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
