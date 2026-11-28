package io.github.hypercar.carui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test fun instrumentSamplesCannotRetargetToAnIdenticalReboundCard() {
        val state = InstrumentMapCard()
        val texture = Any()
        val first = MapCardInputState.Binding(texture, 44, 1782, 701)
        val second = MapCardInputState.Binding(texture, 44, 1782, 701)
        assertTrue(first.generation > 0 && second.generation > first.generation)
        state.update { first }
        val captured = checkNotNull(state.current)
        state.update { null }
        assertEquals(0L, state.tryAcquire(captured))
        state.update { second }
        assertEquals(0L, state.tryAcquire(captured))
        val permit = state.tryAcquire(second)
        assertNotEquals(0L, permit)
        state.release(permit)
    }

    @Test fun publishingAReplacementWaitsOnlyForTheFirstWritePermit() {
        val state = InstrumentMapCard()
        val first = MapCardInputState.Binding(Any(), 44, 1782, 701)
        val replacement = MapCardInputState.Binding(Any(), 44, 1782, 701)
        state.update { first }
        val permit = state.tryAcquire(first)
        assertNotEquals(0L, permit)
        val entering = CountDownLatch(1)
        val published = CountDownLatch(1)
        val publisher = Thread {
            entering.countDown()
            state.update { replacement }
            published.countDown()
        }
        try {
            publisher.start()
            assertTrue(entering.await(2, TimeUnit.SECONDS))
            assertFalse(published.await(100, TimeUnit.MILLISECONDS))
            assertSame(first, state.current)
        } finally {
            state.release(permit)
            publisher.join(2_000)
        }
        assertTrue(published.await(2, TimeUnit.SECONDS))
        assertSame(replacement, state.current)
        assertEquals(0L, state.tryAcquire(first))
    }

    @Test fun coordinateScaleUsesCardPixelsIndependentlyOfHomeDensityOrPosition() {
        val binding = MapCardInputState.Binding(Any(), 44, 1782, 701)
        assertEquals(1f to 1f, binding.scaleFrom(1782, 701))
        val (sx, sy) = binding.scaleFrom(891, 351)
        assertEquals(891f, 445.5f * sx, 0.001f)
        assertEquals(350.5f, 175.5f * sy, 0.001f)
    }
}
