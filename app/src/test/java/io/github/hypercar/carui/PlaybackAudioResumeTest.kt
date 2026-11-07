package io.github.hypercar.carui

import android.media.session.PlaybackState.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioResumeTest {
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
