package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test

class MapCardInputStateTest {
    @Test fun inputIsBoundToTheActualCardTexture() {
        val state = MapCardInputState()
        val card = Any()
        state.bind(card, 44, 1782, 701)
        assertNotNull(state.acquire(card))
        assertNull(state.acquire(Any()))
    }

    @Test fun fullscreenOrDisconnectInvalidatesQueuedCardEvents() {
        val state = MapCardInputState()
        val card = Any()
        state.bind(card, 44, 1782, 701)
        val gesture = state.acquire(card)!!
        state.clear()
        assertFalse(state.accepts(gesture))
        assertNull(state.acquire(card))
    }

    @Test fun returningToSameDisplayAndTextureCannotReviveOldGesture() {
        val state = MapCardInputState()
        val card = Any()
        state.bind(card, 44, 1782, 701)
        val old = state.acquire(card)!!
        state.clear()
        state.bind(card, 44, 1782, 701)
        assertFalse(state.accepts(old))
        assertTrue(state.accepts(state.acquire(card)!!))
    }

    @Test fun changingSizeOrProviderInvalidatesPreviousInputBinding() {
        val state = MapCardInputState()
        val card = Any()
        state.bind(card, 44, 1782, 701)
        val old = state.acquire(card)!!
        state.bind(card, 45, 1920, 720)
        assertFalse(state.accepts(old))
        assertEquals(45, state.acquire(card)!!.displayId)
    }

    @Test fun coordinateScaleUsesCardPixelsIndependentlyOfHomeDensityOrPosition() {
        val binding = MapCardInputState.Binding(Any(), 44, 1782, 701)
        assertEquals(1f to 1f, binding.scaleFrom(1782, 701))
        val (sx, sy) = binding.scaleFrom(891, 351)
        assertEquals(891f, 445.5f * sx, 0.001f)
        assertEquals(350.5f, 175.5f * sy, 0.001f)
    }
}
