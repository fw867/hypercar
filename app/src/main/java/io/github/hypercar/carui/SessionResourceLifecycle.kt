package io.github.hypercar.carui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes creation/destruction while invalidating queued work immediately on stop. */
internal class SessionResourceLifecycle {
    private val generation = AtomicLong()
    private val changes = Mutex()
    private var owner = -1L

    fun ticket(): Long = generation.get()
    fun retire(): Long = generation.incrementAndGet()
    fun isCurrent(ticket: Long) = ticket == generation.get()

    suspend fun <T> start(ticket: Long, current: () -> Boolean, cleanup: () -> Unit, create: suspend () -> T): T? =
        changes.withLock {
            if (!isCurrent(ticket) || !current()) return@withLock null
            if (owner != ticket) cleanup()
            owner = ticket
            try {
                val result = create()
                if (isCurrent(ticket) && current()) result else {
                    cleanup(); owner = -1L; null
                }
            } catch (error: Throwable) {
                cleanup(); owner = -1L
                throw error
            }
        }

    suspend fun stop(retired: Long, cleanup: () -> Unit) = changes.withLock {
        // A newer start can acquire the mutex before a queued stop. Never tear it down.
        if (owner < retired) { cleanup(); owner = -1L }
    }
}
