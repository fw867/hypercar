package io.github.hypercar.carui

import android.media.session.PlaybackState

/** A pause survives BUFFERING; an ongoing stream's rebuffer is not a new play action. */
internal class PlaybackAudioResume(
    initial: Int?,
    private val onPlaying: () -> Unit = {},
    private val onPause: () -> Unit = {},
) {
    private var armed = initial == PlaybackState.STATE_PAUSED || initial == PlaybackState.STATE_STOPPED
    private var playing = initial == PlaybackState.STATE_PLAYING

    init {
        if (playing) onPlaying()
    }

    fun onState(state: Int?): Boolean {
        if (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED) {
            val changed = !armed
            armed = true
            playing = false
            if (changed) onPause()
        }
        if (state == null || state == PlaybackState.STATE_ERROR) {
            armed = false
            playing = false
        }
        if (state != PlaybackState.STATE_PLAYING) return false
        if (!playing) {
            playing = true
            onPlaying()
        }
        val resumed = armed
        armed = false
        return resumed
    }
}
