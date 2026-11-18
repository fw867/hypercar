package io.github.hypercar.carui

import android.media.MediaMetadata.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTrackTextTest {
    @Test fun displayOnlySessionSuppliesCardText() {
        val fields = mapOf(
            METADATA_KEY_TITLE to " ", METADATA_KEY_ARTIST to "",
            METADATA_KEY_DISPLAY_TITLE to "晴天", METADATA_KEY_DISPLAY_SUBTITLE to "周杰伦",
        )
        assertEquals(MusicTrackText("晴天", "周杰伦"), musicTrackText(fields::get))
    }

    @Test fun trackTagsTakePrecedenceOverDisplayText() {
        val fields = mapOf(
            METADATA_KEY_TITLE to "曲名", METADATA_KEY_ARTIST to "歌手",
            METADATA_KEY_DISPLAY_TITLE to "显示标题", METADATA_KEY_DISPLAY_SUBTITLE to "显示副标题",
            METADATA_KEY_ALBUM_ARTIST to "专辑艺人",
        )
        assertEquals(MusicTrackText("曲名", "歌手"), musicTrackText(fields::get))
        assertEquals("专辑艺人", musicTrackText((fields - METADATA_KEY_ARTIST)::get).artist)
    }
}
