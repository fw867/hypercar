package io.github.hypercar.iap2

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

internal data class Iap2Communications(
    val signal: Int? = null,
    val registration: Int? = null,
    val airplane: Boolean? = null,
    val carrier: String? = null,
)

internal object Iap2CommunicationsSource {
    @Volatile private var settings: ContentResolver? = null
    @Volatile private var telephonyManager: TelephonyManager? = null

    fun init(context: Context) {
        val app = context.applicationContext
        settings = app.contentResolver
        telephonyManager = app.getSystemService(TelephonyManager::class.java)
    }

    fun snapshot(requested: Set<Int>): Iap2Communications {
        val airplane = if (2 in requested) runCatching {
            settings?.let { Settings.Global.getInt(it, Settings.Global.AIRPLANE_MODE_ON) != 0 }
        }.getOrNull() else null
        val wantsNetworkState = 0 in requested || 1 in requested
        if (!wantsNetworkState && 4 !in requested) return Iap2Communications(airplane = airplane)

        val telephony = telephonyManager ?: return Iap2Communications(airplane = airplane)
        return runCatching {
            val id = SubscriptionManager.getDefaultVoiceSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(id)) {
                val slots = if (wantsNetworkState) telephony.activeModemCount else 0
                val noSim = slots > 0 && (0 until slots).all { telephony.getSimState(it) == TelephonyManager.SIM_STATE_ABSENT }
                return@runCatching if (noSim && SubscriptionManager.getDefaultVoiceSubscriptionId() == id) {
                    Iap2Communications(signal = 0, registration = 1, airplane = airplane)
                } else Iap2Communications(airplane = airplane)
            }
            val voice = telephony.createForSubscriptionId(id)
            val operator = voice.networkOperator
            val registered = operator.length in 5..6 && operator.all { it in '0'..'9' }
            if (!registered && wantsNetworkState) {
                val slot = SubscriptionManager.getSlotIndex(id)
                if (slot >= 0 && telephony.getSimState(slot) == TelephonyManager.SIM_STATE_ABSENT &&
                    SubscriptionManager.getDefaultVoiceSubscriptionId() == id && voice.networkOperator == operator) {
                    return@runCatching Iap2Communications(signal = 0, registration = 1, airplane = airplane)
                }
            }
            val signal = if (0 in requested && registered) runCatching { voice.signalStrength?.level }.getOrNull() else null
            // MGU uses 1 for unregistered, 4 for registered and 5 for roaming.
            // An empty operator alone cannot distinguish lost service from unavailable information.
            val registration = if (1 in requested && registered) runCatching {
                if (voice.isNetworkRoaming) 5 else 4
            }.getOrNull() else null
            val carrier = if (4 in requested && registered) runCatching { voice.networkOperatorName }.getOrNull() else null
            if (SubscriptionManager.getDefaultVoiceSubscriptionId() != id || voice.networkOperator != operator) {
                return@runCatching Iap2Communications(airplane = airplane)
            }
            Iap2Communications(signal, registration, airplane, carrier)
        }.getOrElse { Iap2Communications(airplane = airplane) }
    }
}
