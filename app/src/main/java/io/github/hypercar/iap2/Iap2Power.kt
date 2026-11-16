package io.github.hypercar.iap2

/** Null fields are unavailable; connection transport cannot imply charging. */
internal data class Iap2Power(val externalCharger: Boolean? = null, val chargingState: Int? = null, val level: Int? = null) {
    fun fields(requested: Set<Int>): List<ByteArray> = buildList {
        if (4 in requested) externalCharger?.let { add(Iap2Csm.u8(4, if (it) 1 else 0)) }
        if (5 in requested) chargingState?.takeIf { it in 0..2 }?.let { add(Iap2Csm.u8(5, it)) }
        if (6 in requested) level?.takeIf { it in 0..100 }?.let { add(Iap2Csm.u16(6, it)) }
    }
}

/** Both transports read the Android sticky broadcast at request time. */
internal object Iap2PowerSource {
    private var context: android.content.Context? = null
    fun init(context: android.content.Context) { this.context = context.applicationContext }
    fun snapshot(): Iap2Power {
        val intent = context?.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return Iap2Power()
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,-1)
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,-1)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1)
        return Iap2Power(plugged.takeIf { it >= 0 }?.let { it != 0 }, when(status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> 1
            android.os.BatteryManager.BATTERY_STATUS_FULL -> 2
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING, android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> 0
            else -> null
        }, if(scale > 0 && level in 0..scale) (level.toLong()*100/scale).toInt() else null)
    }
}
