package io.github.hypercar.net

import io.github.hypercar.settings.SavedPhoneMac
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One resolved phone identity shared by the transports; invalidation cannot publish a late result. */
internal class PhoneIdentitySnapshot(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val rememberHardwareMac: (Long, String) -> Unit = { _, _ -> },
    private val onBudgetExpired: (Long) -> Unit = {},
) {
    data class Identity(val address: String, val settingsRevision: Long)

    private class Round {
        val gate = Mutex()
        var deadline: Long? = null
        var selection: SavedPhoneMac.Selection? = null
        var identity: Identity? = null
        var pinned = false
    }

    @Volatile private var round = Round()
    @Synchronized fun invalidate() { round = Round() }

    suspend fun prefetch(selection: () -> SavedPhoneMac.Selection, provider: suspend () -> String?): String? =
        select(selection, provider, fallback = null)?.address

    suspend fun pin(selection: () -> SavedPhoneMac.Selection, provider: suspend () -> String?, stable: () -> String): Identity =
        checkNotNull(select(selection, provider, stable)) { "No valid stable phone identity" }

    private suspend fun select(readSelection: () -> SavedPhoneMac.Selection, provider: suspend () -> String?, fallback: (() -> String)?): Identity? {
        val current = round
        return current.gate.withLock {
            currentCoroutineContext().ensureActive()
            checkCurrent(current)
            if (current.pinned) return@withLock current.identity
            val selection = readSelection()
            val cached = current.identity?.address.takeIf { current.selection == selection }
            val known = normalize(selection.address) ?: cached
            val queried = if (known == null) query(current, provider) else null

            currentCoroutineContext().ensureActive()
            synchronized(this) {
                checkCurrent(current)
                val deadline = current.deadline
                val expired = deadline != null && nowMillis() >= deadline
                val accepted = queried.takeUnless { expired }
                if (accepted != null) rememberHardwareMac(selection.revision, accepted)
                val latest = readSelection()
                val candidate = (known ?: accepted).takeIf { selection.revision == latest.revision }
                val address = latest.address ?: candidate ?: normalize(fallback?.invoke())
                // A clear during lookup also revokes this attempt's first-frame auto-save.
                val revision = if (latest.address != null) latest.revision else selection.revision
                current.selection = latest
                current.identity = address?.let { Identity(it, revision) }
                current.pinned = fallback != null && current.identity != null
                if (current.pinned && expired && latest.address == null && candidate == null)
                    onBudgetExpired(MAC_QUERY_BUDGET_MS)
                current.identity
            }
        }
    }

    private suspend fun query(current: Round, provider: suspend () -> String?): String? {
        val now = nowMillis()
        val deadline = current.deadline ?: (now + MAC_QUERY_BUDGET_MS).also { current.deadline = it }
        return try {
            withTimeoutOrNull(deadline - now) { normalize(provider()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun checkCurrent(expected: Round) {
        if (round !== expected) throw CancellationException("Phone identity snapshot invalidated")
    }

    companion object {
        private const val MAC_QUERY_BUDGET_MS = 2_000L

        fun normalize(value: String?): String? {
            val address = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            if (!Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(address)) return null
            if (address in setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")) return null
            if (address.take(2).toInt(16) and 1 != 0) return null
            return address
        }
    }
}
