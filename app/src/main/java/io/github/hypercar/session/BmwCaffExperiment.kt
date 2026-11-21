package io.github.hypercar.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local, target-specific opt-in. Automatic wake/reconnect never consumes the request. */
internal class BmwCaffExperiment {
    private val target = MutableStateFlow<String?>(null)
    val armedTarget = target.asStateFlow()

    fun arm(address: String) { target.value = address }
    fun clear() { target.value = null }

    fun takeForRound(manual: Boolean, eligibleTarget: String?): String? {
        if (!manual) return null
        while (true) {
            val armed = target.value ?: return null
            if (target.compareAndSet(armed, null)) return eligibleTarget?.takeIf { armed.equals(it, ignoreCase = true) }
        }
    }
}
