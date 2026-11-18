package io.github.hypercar.carui

import android.media.MediaMetadata

internal data class MusicTrackText(val title: String, val artist: String)

internal fun musicTrackText(read: (String) -> String?): MusicTrackText {
    fun first(vararg keys: String) = keys.firstNotNullOfOrNull { read(it)?.takeIf(String::isNotBlank) }.orEmpty()
    return MusicTrackText(
        title = first(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        artist = first(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
    )
}
