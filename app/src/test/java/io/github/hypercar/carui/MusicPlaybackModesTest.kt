package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MusicPlaybackModesTest {
    @Test fun absentPlayerCapabilitiesDoNotInventModeControls() {
        assertNull(MusicPlaybackModes.current(-1, -1))
        assertTrue(MusicPlaybackModes.available(false, false, -1, -1).isEmpty())
    }
    @Test fun repeatOnlySessionCannotDisableActiveShuffle() {
        assertTrue(MusicPlaybackModes.available(true, false, 2, 1).isEmpty())
        assertEquals(3, MusicPlaybackModes.available(true, false, 2, 0).size)
    }
    @Test fun shuffleOnlySessionPreservesExistingRepeatInsteadOfClaimingSequential() {
        assertEquals(listOf(MusicPlaybackMode.ONE, MusicPlaybackMode.SHUFFLE),
            MusicPlaybackModes.available(false, true, 1, 1))
        assertEquals(MusicPlaybackMode.SHUFFLE, MusicPlaybackModes.current(1, 1))
        assertEquals(MusicPlaybackMode.ONE, MusicPlaybackModes.current(1, 0))
    }
}
