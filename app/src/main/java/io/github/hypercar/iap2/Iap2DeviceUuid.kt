package io.github.hypercar.iap2

import java.util.Locale
import java.util.UUID

/** Box-style random device UUID, shared by Bluetooth/Wi-Fi for this app process. */
internal object Iap2DeviceUuid {
    val value: String = UUID.randomUUID().toString().uppercase(Locale.ROOT)
}
