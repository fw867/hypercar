package io.github.hypercar.carui

import io.github.hypercar.session.EngineCallbackGate
import org.junit.Assert.*
import org.junit.Test

class ReceiverAudioTakeTest {
    private val engines = EngineCallbackGate()
    private val owner = engines.begin()
    private var attempt = 1
    private var player: String? = "A"
    private var selectionRevision = 0L
    private var sessionGeneration = 1L
    private val paused = mutableListOf<String>()

    @Test fun samePlayerTokenOnReinviteRejectsOldPlayingAndAcceptsNewCardPlayOnce() {
        val requests = mutableListOf<Long>()
        fun callback(engine: Long): (Int) -> Unit {
            val samePlayerGeneration = sessionGeneration
            val resume = PlaybackAudioResume(android.media.session.PlaybackState.STATE_PAUSED)
            return { state ->
                if (samePlayerGeneration == sessionGeneration && resume.onState(state)) {
                    engines.requestPlayback(engine) { requests.add(engine) }
                }
            }
        }
        val late = callback(owner)
        engines.retire()
        val newOwner = engines.begin()
        val fresh = callback(newOwner)
        val ticket = engines.receiverAudioTake(newOwner, true)!!
        late(android.media.session.PlaybackState.STATE_PLAYING)
        assertTrue(requests.isEmpty())
        assertTrue(ticket.dispatch({ true }) { paused += "A" })
        repeat(2) { fresh(android.media.session.PlaybackState.STATE_PLAYING) }
        assertEquals(listOf(newOwner), requests)
    }

    @Test fun unchangedPlayingLeavesTheTargetedPausePendingAndDuplicatesDoNotRearmIt() {
        val pending = take()
        val playback = PlaybackAudioResume(android.media.session.PlaybackState.STATE_PLAYING)
        assertFalse(playback.onState(android.media.session.PlaybackState.STATE_PLAYING))
        assertNull(engines.receiverAudioTake(owner, true))
        assertTrue(pending())
        assertFalse(pending())
        assertEquals(listOf("A"), paused)
    }

    @Test fun cardResumeSupersedesOnlyItsOutstandingPauseAndClaimsOnce() {
        val pending = take()
        val playback = PlaybackAudioResume(android.media.session.PlaybackState.STATE_PAUSED)
        assertTrue(playback.onState(android.media.session.PlaybackState.STATE_PLAYING))
        assertTrue(engines.requestPlayback(owner) { true })
        assertFalse(pending())
        assertFalse(playback.onState(android.media.session.PlaybackState.STATE_PLAYING))
        engines.receiverAudioTake(owner, false)
        assertTrue(take()())
    }

    // The slow Android lookup can finish after either the player or the car has changed.
    private fun take(): () -> Boolean {
        val ticket = engines.receiverAudioTake(owner, true)!!
        val locked = player?.let { ReceiverPauseTarget(it, it, sessionGeneration, selectionRevision) }
        val connection = attempt
        return { ticket.dispatch(
            valid = { attempt == connection && locked?.matches(player, sessionGeneration, selectionRevision) == true },
            pause = { paused += requireNotNull(locked).player },
        ) }
    }

    @Test fun switchingFromAToBDoesNotPauseEitherPlayer() {
        val locked = ReceiverPauseTarget("A", "A", sessionGeneration, selectionRevision)
        assertNull(locked.activeIn(listOf("B")) { it })
        val pending = take()
        player = "B"
        assertFalse(pending())
        assertTrue(paused.isEmpty())
    }

    @Test fun playerSwitchStartedButControllerNotReplacedYet() {
        val pending = take()
        selectionRevision++
        assertFalse(pending())
        engines.receiverAudioTake(owner, false)
        val next = take()
        sessionGeneration++
        assertFalse(next())
        assertTrue(paused.isEmpty())
    }

    @Test fun unknownAtTakeCannotAdoptALaterPlayer() {
        player = null
        val pending = take()
        player = "B"
        assertFalse(pending())
        assertTrue(paused.isEmpty())
    }

    @Test fun returnAndSecondTakeInvalidateTheFirstPause() {
        val old = take()
        engines.receiverAudioTake(owner, false)
        assertFalse(old())
        val next = take()
        assertFalse(old())
        assertTrue(next())
        assertFalse(next())
        assertEquals(listOf("A"), paused)
    }

    @Test fun sameAddressReinviteAndEngineOnlyRestartRejectOldPause() {
        val old = take()
        assertTrue(engines.endConnection { true })
        assertFalse(old())
        attempt++
        assertFalse(old())
        val restarting = take()
        val nextOwner = engines.begin()
        assertFalse(restarting())
        val next = engines.receiverAudioTake(nextOwner, true)!!
        engines.receiverAudioTake(owner, false)
        assertTrue(next.dispatch({ true }) { paused += "B" })
        assertEquals(listOf("B"), paused)
    }
}
