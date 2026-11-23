package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortVideoMusicPauseTest {
    private data class Session(val token: Int, val activeLocalMusic: Boolean)

    @Test fun pausesOtherPlayingSessionsWhenTheSelectedPlayerIsPaused() {
        val selected = Session(1, false)
        val playing = Session(2, true)
        val video = Session(3, false)
        assertEquals(listOf(playing), shortVideoPauseTargets(
            listOf(selected, playing, video, playing.copy()), selected, Session::token, Session::activeLocalMusic,
        ))
    }

    @Test fun retainsSelectedPlayerAsFallbackWhenSessionLookupFails() {
        val selected = Session(1, true)
        assertEquals(listOf(selected), shortVideoPauseTargets(
            emptyList(), selected, Session::token, Session::activeLocalMusic,
        ))
    }
}
