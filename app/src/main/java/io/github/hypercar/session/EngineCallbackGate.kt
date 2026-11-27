package io.github.hypercar.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Engine starts within one connection attempt need distinct callback owners. */
internal class EngineCallbackGate {
    private var serial = 0L
    @Volatile private var owner = 0L
    // Separate from deliver(): some general callbacks wait for work on the music thread.
    private val audioLock = Any()
    private val activeProjection = MutableStateFlow<Long?>(null)
    val projectionOwner = activeProjection.asStateFlow()
    private var projectionPermitted = false
    @Volatile private var take: AudioTake? = null
    @Synchronized fun begin(): Long = synchronized(audioLock) {
        take = null
        activeProjection.value = null
        projectionPermitted = true
        (++serial).also { owner = it }
    }
    @Synchronized fun current(): Long = owner
    @Synchronized fun retire() = synchronized(audioLock) {
        take = null
        projectionPermitted = false
        activeProjection.value = null
        owner = 0
    }
    // A posted music-thread action can check its generation while deliver() is awaiting that thread.
    fun isCurrent(expected: Long): Boolean = expected != 0L && owner == expected

    fun receiverAudioTake(engine: Long, taken: Boolean): AudioTake? = synchronized(audioLock) {
        if (!isCurrent(engine) || (taken && take != null)) return null
        (if (taken) AudioTake() else null).also { take = it }
    }

    /** Ordered with Take receipt, even before its music-thread callback runs. */
    fun requestPlayback(engine: Long, enqueue: () -> Boolean): Boolean = synchronized(audioLock) {
        if (!isCurrent(engine) || !enqueue()) return false
        take?.supersedePause()
        true
    }

    fun notifyMediaPlayback(engine: Long, enqueue: () -> Boolean): Boolean = synchronized(audioLock) {
        isCurrent(engine) && enqueue()
    }

    fun endConnection(markEnded: () -> Boolean): Boolean = synchronized(audioLock) {
        markEnded().also { ended ->
            if (ended) {
                take = null
                projectionPermitted = false
                activeProjection.value = null
            }
        }
    }

    fun setProjection(expected: Long, active: Boolean) = synchronized(audioLock) {
        if (projectionPermitted && isCurrent(expected)) activeProjection.value = expected.takeIf { active }
    }

    inner class AudioTake internal constructor() {
        private var pausePending = true
        fun isCurrent() = take === this

        internal fun supersedePause() { pausePending = false }

        /** Release, engine replacement and the final targeted Binder dispatch are ordered here. */
        fun dispatch(valid: () -> Boolean, pause: () -> Unit): Boolean = synchronized(audioLock) {
            if (!isCurrent() || !pausePending || !valid()) return false
            pausePending = false
            pause()
            true
        }
    }
    @Synchronized fun deliver(expected: Long, action: () -> Unit): Boolean {
        if (!isCurrent(expected)) return false
        action()
        return true
    }
}
