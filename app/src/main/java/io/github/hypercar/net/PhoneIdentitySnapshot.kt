package io.github.hypercar.net

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One resolved phone identity shared by the transports; invalidation cannot publish a late result. */
internal class PhoneIdentitySnapshot {
    private data class State(val generation: Long, val selection: String? = null, val address: String? = null)
    private val state = AtomicReference(State(0))
    private val gate = Mutex()
    fun peek(): String? = state.get().address
    fun invalidate() { state.updateAndGet { State(it.generation + 1) } }

    suspend fun resolve(selection: String?, provider: suspend () -> String?, stable: () -> String): String = gate.withLock {
        currentCoroutineContext().ensureActive()
        val before = state.get()
        if (before.address != null && before.selection == selection) return@withLock before.address
        val supplied = try { provider() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val address = normalize(supplied) ?: normalize(stable())
            ?: error("No valid stable phone identity")
        currentCoroutineContext().ensureActive()
        if (!state.compareAndSet(before, State(before.generation, selection, address)))
            throw CancellationException("Phone identity snapshot invalidated")
        address
    }

    companion object {
        fun normalize(value: String?): String? {
            val address = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            if (!Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(address)) return null
            if (address in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) return null
            if (address.take(2).toInt(16) and 1 != 0) return null
            return address
        }
    }
}
