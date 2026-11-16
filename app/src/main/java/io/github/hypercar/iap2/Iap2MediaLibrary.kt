package io.github.hypercar.iap2

import android.content.Context
import java.util.UUID

/** The bridge exports an empty local catalogue. Its identifier survives reconnects and APK updates. */
internal object Iap2MediaLibrary {
    var identifier: String = UUID.randomUUID().toString()
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("iap2_media_library", Context.MODE_PRIVATE)
        identifier = prefs.getString("identifier", null) ?: identifier.also {
            prefs.edit().putString("identifier", it).apply()
        }
    }

    fun information(deviceName: String): Iap2Csm.Frame {
        val name = deviceName.substringBefore('\u0000').take(128).ifBlank { "HyperCar" }
        return Iap2Csm.Frame(0x4c01, Iap2Csm.group(0,
            Iap2Csm.string(0, name), Iap2Csm.string(1, identifier), Iap2Csm.u8(2, 0)))
    }

    fun matches(fields: List<Iap2Csm.Param>): Boolean =
        fields.singleOrNull { it.id == 0 }?.value?.contentEquals(identifier.encodeToByteArray() + 0.toByte()) == true

    fun updates(fields: List<Iap2Csm.Param>): List<Iap2Csm.Frame> {
        val id = Iap2Csm.string(0, identifier)
        val flags = fields.filter { it.value.isEmpty() }.map { it.id }.toSet()
        return buildList {
            if (5 in flags) add(Iap2Csm.Frame(0x4c04, id + Iap2Csm.u8(8, 0)))
            if (6 in flags) add(Iap2Csm.Frame(0x4c04, id + Iap2Csm.u8(9, 0)))
            if (4 in flags) add(Iap2Csm.Frame(0x4c04, id + Iap2Csm.param(6, byteArrayOf()) +
                Iap2Csm.string(1, "1") + Iap2Csm.u8(7, 100)))
        }
    }
}
