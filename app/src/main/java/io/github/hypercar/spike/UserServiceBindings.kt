package io.github.hypercar.spike

import java.util.WeakHashMap
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class UserServiceBinding {
    val finished = CompletableDeferred<Boolean>()
    val closed = CompletableDeferred<Unit>()

    fun close() {
        closed.complete(Unit)
        finished.complete(false)
    }
}

internal class UserServiceBindings(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val brokers = WeakHashMap<Any, Semaphore>()

    fun start(broker: Any, binding: UserServiceBinding, bind: () -> Boolean, unbind: () -> Unit) {
        // Keep room for one retry behind a stuck transaction. Reject excess work before dispatch.
        val permits = synchronized(brokers) { brokers.getOrPut(broker) { Semaphore(2) } }
        if (!permits.tryAcquire()) {
            binding.finished.complete(false)
            return
        }
        scope.launch {
            try {
                if (binding.closed.isCompleted) return@launch
                binding.finished.complete(bind())
                binding.closed.await()
                unbind()
            } finally {
                permits.release()
            }
        }
    }
}
