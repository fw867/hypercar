package io.github.hypercar.yadea

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

internal class YadeaStopTicket {
    val id: String = UUID.randomUUID().toString()

    fun accepts(received: String?): Boolean = received == id
}

internal class YadeaCleanupDeadline(private val timeoutMillis: Long, private val now: () -> Long) {
    private val deadline = AtomicLong(-1L)
    val started: Boolean get() = deadline.get() >= 0L

    fun start(): Boolean = deadline.compareAndSet(-1L, now() + timeoutMillis)

    fun remainingMillis(): Long = (deadline.get() - now()).coerceAtLeast(0L)
}

internal class YadeaPendingConnectCleanup {
    enum class Action { Cancel, ObserveIdle, Wait, Done }
    enum class Cancellation { None, Pending, Accepted, Rejected }
    enum class Group { Absent, Unknown, Foreign }

    private var requestsAfterAcceptance = 0
    private var matchingGroupReleased = false

    fun recordMatchingGroupGone() {
        matchingGroupReleased = true
    }

    fun afterGroupCheck(accepted: Boolean?, cancellation: Cancellation, group: Group = Group.Absent): Action {
        if (cancellation == Cancellation.Pending) return Action.Wait
        if (accepted == false) return Action.Done
        if (accepted != true || group != Group.Absent) return Action.Wait
        return when {
            matchingGroupReleased -> Action.ObserveIdle
            requestsAfterAcceptance == 0 || cancellation == Cancellation.Rejected && requestsAfterAcceptance < 2 -> {
                requestsAfterAcceptance++
                Action.Cancel
            }
            cancellation == Cancellation.Accepted -> Action.ObserveIdle
            else -> Action.Wait
        }
    }
}

internal class YadeaP2pActionResult(private val changes: Channel<Unit>) {
    val result = CompletableDeferred<Int>()

    fun complete(code: Int) {
        if (result.complete(code)) changes.trySend(Unit)
    }
}

/** The caller serializes scanner registration and the projection target change. */
internal class YadeaScanHandoff<Scanner : Any>(private val closeAndAwait: suspend (Scanner) -> Boolean) {
    private var scanner: Scanner? = null

    suspend fun track(next: Scanner) {
        if (scanner === next) return
        closeCurrent()
        scanner = next
    }

    suspend fun closeCurrent(): Boolean {
        val previous = scanner ?: return true
        return closeAndAwait(previous).also { scanner = null }
    }
}
