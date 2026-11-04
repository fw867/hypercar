package io.github.hypercar.spike

import android.media.AudioAttributes
import android.media.AudioManager

/** Shell-only media routing. Restore the previous preference only while we still own it. */
internal class PlaybackRouteLease(private val audio: AudioManager) {
    private val strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
    private val deviceClass = Class.forName("android.media.AudioDeviceAttributes")
    private val get = AudioManager::class.java.getMethod("getPreferredDevicesForStrategy", strategyClass)
    private val set = AudioManager::class.java.getMethod("setPreferredDevicesForStrategy", strategyClass, List::class.java)
    private val remove = AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", strategyClass)
    private var strategy: Any? = null
    private var previous = emptyList<Any>()
    private var installed = emptyList<Any>()
    private var attempted = false

    fun acquire(): String {
        if (attempted) return "media route recovery already attempted"
        attempted = true
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val strategies = AudioManager::class.java.getMethod("getAudioProductStrategies").invoke(null) as List<*>
        val supports = strategyClass.getMethod("supportsAudioAttributes", AudioAttributes::class.java)
        val selected = strategies.filterNotNull().firstOrNull { supports.invoke(it, media) == true }
            ?: return "media route recovery: no media strategy"
        // AudioDeviceAttributes' internal-device constructor: REMOTE_SUBMIX, legacy address 0.
        val target = deviceClass.getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(0x8000, "0")
        previous = read(selected)
        installed = listOf(target)
        strategy = selected // retain rollback information even if the setter throws after mutation
        val accepted = set.invoke(audio, selected, installed) == true
        return "media route recovery remote_submix accepted=$accepted previous=$previous"
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(selected: Any): List<Any> =
        (get.invoke(audio, selected) as List<Any>).toList()

    fun release(): String {
        val selected = strategy ?: return "media route unchanged"
        strategy = null
        if (read(selected) != installed) return "media route changed externally; preserve current preference"
        val restored = if (previous.isEmpty()) remove.invoke(audio, selected)
            else set.invoke(audio, selected, previous)
        return "media route restored=$restored"
    }
}
