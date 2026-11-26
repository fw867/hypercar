package io.github.hypercar.carui

import android.media.session.PlaybackState.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioResumeTest {
    @Test fun attachingAPlayingPlayerReportsStateWithoutAUserPlayAction() {
        val observed = mutableListOf<Boolean>()
        val intent = PlaybackAudioResume(STATE_PLAYING, onPlaying = { observed += true })
        assertEquals(listOf(true), observed)
        assertFalse(intent.onState(STATE_PLAYING))
        assertEquals(listOf(true), observed)
    }
    @Test fun stopPublishesOnceAcrossPauseUpdatesAndRearmsAfterPlaying() {
        val events = mutableListOf<String>()
        val intent = PlaybackAudioResume(STATE_PLAYING) { events.add("pause") }
        for (state in listOf(STATE_PAUSED, STATE_STOPPED, STATE_BUFFERING, STATE_PAUSED,
            STATE_PLAYING, STATE_PLAYING, STATE_STOPPED)) {
            if (intent.onState(state)) events.add("play")
        }
        assertEquals(listOf("pause", "play", "pause"), events)
    }
    @Test fun attachingAnAlreadyPlayingSessionOrRebufferingDoesNotRequestAudio() {
        val intent = PlaybackAudioResume(null)
        assertFalse(intent.onState(STATE_PLAYING))
        assertFalse(intent.onState(STATE_BUFFERING))
        assertFalse(intent.onState(STATE_PLAYING))
    }
    @Test fun pauseThenBufferThenPlayRequestsOnce() {
        val intent = PlaybackAudioResume(STATE_PLAYING)
        assertFalse(intent.onState(STATE_PAUSED))
        assertFalse(intent.onState(STATE_BUFFERING))
        assertTrue(intent.onState(STATE_PLAYING))
        assertFalse(intent.onState(STATE_PLAYING))
    }
    @Test fun sessionLossOrErrorCancelsTheResumeIntent() {
        for (state in listOf(null, STATE_ERROR)) {
            val intent = PlaybackAudioResume(STATE_PAUSED)
            assertFalse(intent.onState(state))
            assertFalse(intent.onState(STATE_PLAYING))
        }
    }
}
