package io.github.hypercar.carui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.graphics.drawable.toBitmap
import io.github.hypercar.session.HyperCarRepo
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*

/** Bounded, asynchronous fallback for players that publish artwork as a URI or notification icon. */
internal object MusicArtwork {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var key = ""
    private var generation = 0L
    private var cached: Bitmap? = null
    private var job: Job? = null
    private var attemptedAt = -10_000L
    private var refreshRequested = false
    private var reportedSource = ""

    fun invalidate() { refreshRequested = true; attemptedAt = -10_000L }
    fun clear() { generation++; key=""; cached=null; reportedSource=""; job?.cancel(); job=null; attemptedAt=-10_000L }

    fun resolve(service: NotificationListenerService?, pkg: String, metadata: MediaMetadata?, changed: () -> Unit): Bitmap? {
        val uris = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).mapNotNull { runCatching { metadata?.getString(it) }.getOrNull() }
            .plus(listOfNotNull(runCatching { metadata?.description?.iconUri?.toString() }.getOrNull())).distinct()
        val nextKey = listOf(pkg, metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM), uris.joinToString()).joinToString("\u0000")
        if (key != nextKey) { clear(); key = nextKey }
        val embedded = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON).firstNotNullOfOrNull { runCatching { metadata?.getBitmap(it) }.getOrNull() }
            ?: runCatching { metadata?.description?.iconBitmap }.getOrNull()
        if (embedded != null) {
            cached=embedded
            if (reportedSource != "metadata" && CarUiCaster.running) HyperCarRepo.log("MUSIC-ART: app=$pkg source=metadata bitmap=${embedded.width}x${embedded.height}")
            reportedSource="metadata"
            return embedded
        }
        if (service == null || job?.isActive == true) return cached
        if (cached != null && !refreshRequested) return cached
        if (SystemClock.elapsedRealtime()-attemptedAt < 5_000) return cached
        attemptedAt = SystemClock.elapsedRealtime(); refreshRequested=false
        val requestGeneration = generation
        val requestKey = key
        job = scope.launch {
            var source = "none"
            val image = uris.firstNotNullOfOrNull { text ->
                runCatching { loadUri(service,Uri.parse(text)) }.getOrNull()?.also { source="uri" }
            } ?: runCatching {
                val title=metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                service.activeNotifications.orEmpty().filter { it.packageName == pkg }
                    .sortedByDescending { it.postTime }.asSequence().filter {
                        val notificationTitle=it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                        title.isBlank() || notificationTitle.isBlank() || title == notificationTitle
                    }.firstNotNullOfOrNull { notification ->
                        notification.notification.getLargeIcon()?.loadDrawable(service)?.let { drawable ->
                            val w=drawable.intrinsicWidth.coerceAtLeast(1);val h=drawable.intrinsicHeight.coerceAtLeast(1)
                            val scale=minOf(1.0,1024.0/maxOf(w,h))
                            drawable.toBitmap((w*scale).toInt().coerceAtLeast(1),(h*scale).toInt().coerceAtLeast(1))
                        }
                    }?.also { source="notification" }
            }.getOrNull()
            main.post {
                if (generation != requestGeneration || key != requestKey) return@post
                job=null
                if (image != null) { cached=image; reportedSource=source }
                // 车机 UI 不在跑时静默：断连后系统仍会反复改 session，会把日志刷满（2026-10-31）
                if (CarUiCaster.running) HyperCarRepo.log("MUSIC-ART: app=$pkg source=$source bitmap=${image?.width ?: 0}x${image?.height ?: 0}")
                if (image != null) changed()
            }
        }
        return cached
    }

    private fun loadUri(service: NotificationListenerService, uri: Uri): Bitmap? {
        val connection = if (uri.scheme in listOf("https","http")) URL(uri.toString()).openConnection() as HttpURLConnection else null
        connection?.apply { connectTimeout=1500;readTimeout=2000;instanceFollowRedirects=true }
        return try {
            val input = if (connection != null) connection.inputStream
                else if (uri.scheme in listOf("content","android.resource","file")) service.contentResolver.openInputStream(uri) else null
            val bytes=input?.use { stream ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while (true) { val n=stream.read(buffer);if(n<0)break;check(out.size()+n<=4*1024*1024){"artwork too large"};out.write(buffer,0,n) }
                out.toByteArray()
            } ?: return null
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            if(options.outWidth<=0 || options.outHeight<=0)return null
            var sample=1
            while(maxOf(options.outWidth,options.outHeight)/sample>1024)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
        } finally { connection?.disconnect() }
    }
}
