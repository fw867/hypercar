package io.github.hypercar.carui

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One running Binder query and one latest replacement. Binder calls cannot reliably
 * be interrupted: never create timeout/retry threads to work around a stuck player.
 * Results are checked again on the owner thread, including after detach/switch.
 */
internal class LatestMusicQuery<T>(
    name: String,
    private val deliver: ((() -> Unit) -> Unit),
    private val failed: (Throwable) -> Unit,
    private val worker: Executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { action -> Thread(action, name).apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    ),
) {
    private val revision = AtomicLong()

    fun invalidate() { revision.incrementAndGet() }

    fun submit(read: () -> T, apply: (T) -> Unit) {
        val ticket = revision.incrementAndGet()
        worker.execute {
            if (ticket != revision.get()) return@execute
            val result = try { read() } catch (error: Exception) {
                if (ticket == revision.get()) failed(error)
                return@execute
            }
            if (ticket != revision.get()) return@execute
            deliver {
                if (ticket == revision.get()) apply(result)
            }
        }
    }
}
