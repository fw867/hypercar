package io.github.hypercar.carui

import android.media.session.PlaybackState

/** A pause survives BUFFERING; an ongoing stream's rebuffer is not a new play action. */
internal class PlaybackAudioResume(initial: Int?) {
    private var armed = initial == PlaybackState.STATE_PAUSED || initial == PlaybackState.STATE_STOPPED
    fun onState(state: Int?): Boolean {
        if (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED) armed = true
        if (state == null || state == PlaybackState.STATE_ERROR) armed = false
        if (state != PlaybackState.STATE_PLAYING) return false
        val resumed = armed
        armed = false
        return resumed
    }
}
