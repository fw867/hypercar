package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MusicSnapshotEpochTest {
    @Test fun sameSessionRefreshFillsMissingCallbacks() {
        val epochs = MusicSnapshotEpoch()
        val request = epochs.capture()
        assertTrue(epochs.acceptsMetadata(request))
        assertTrue(epochs.acceptsPlayback(request))
    }
    @Test fun newMetadataDoesNotBlockPlaybackRefreshOrRollBackTheCover() {
        val epochs = MusicSnapshotEpoch()
        val request = epochs.capture()
        epochs.metadataChanged()
        assertFalse(epochs.acceptsMetadata(request))
        assertTrue(epochs.acceptsPlayback(request))
    }
    @Test fun playbackCallbackWinsWithoutLosingTheMetadataRefresh() {
        val epochs = MusicSnapshotEpoch()
        val request = epochs.capture()
        epochs.playbackChanged()
        assertTrue(epochs.acceptsMetadata(request))
        assertFalse(epochs.acceptsPlayback(request))
    }
    @Test fun switchingOrDetachingRejectsBothOldSnapshotFields() {
        val epochs = MusicSnapshotEpoch()
        val request = epochs.capture()
        epochs.sessionChanged()
        assertFalse(epochs.acceptsMetadata(request))
        assertFalse(epochs.acceptsPlayback(request))
    }
}
