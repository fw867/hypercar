package io.github.hypercar.yadea

import android.content.SharedPreferences
import io.github.hypercar.settings.ProjectionTarget
import io.github.hypercar.settings.SettingsStore
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YadeaSettingsTest {
    @Test fun wifiDirectAdmissionUsesTheCurrentModeAndTarget() {
        val prefs = preferences(mutableMapOf())
        val settings = YadeaSettings { prefs }
        assertFalse(settings.allowsWifiDirect(ProjectionTarget.Yadea))
        settings.connectionMode = YadeaConnectionMode.P2p
        assertTrue(settings.allowsWifiDirect(ProjectionTarget.Yadea))
        assertFalse(settings.allowsWifiDirect(ProjectionTarget.CarPlay))
        settings.connectionMode = YadeaConnectionMode.Manual
        assertFalse(settings.allowsWifiDirect(ProjectionTarget.Yadea))
    }

    @Test fun switchingToYadeaAndBackPreservesCarPlaySettings() {
        val prefsField = SettingsStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val original = prefsField.get(SettingsStore)
        try {
            for (autoConnect in listOf(false, true)) {
                val values = mutableMapOf<String, Any>(
                    "car_source" to "car_ui",
                    "head_unit_profile" to "bmw_id85",
                    "auto_connect_on_bt" to autoConnect,
                    "target_car_mac" to "00:11:22:33:44:55",
                )
                prefsField.set(SettingsStore, preferences(values))

                assertEquals(ProjectionTarget.CarPlay, SettingsStore.projectionTarget)
                SettingsStore.projectionTarget = ProjectionTarget.Yadea
                assertEquals(ProjectionTarget.Yadea, SettingsStore.projectionTarget)
                SettingsStore.yadea.source = YadeaSource.SystemCarLife
                SettingsStore.projectionTarget = ProjectionTarget.CarPlay

                assertEquals(ProjectionTarget.CarPlay, SettingsStore.projectionTarget)
                assertEquals(YadeaSource.SystemCarLife, SettingsStore.yadea.source)
                assertEquals("car_ui", SettingsStore.carSource)
                assertEquals("bmw_id85", SettingsStore.headUnitProfileId)
                assertEquals(autoConnect, SettingsStore.autoConnect)
                assertEquals("00:11:22:33:44:55", SettingsStore.targetCarMac)
            }
        } finally {
            prefsField.set(SettingsStore, original)
        }
    }

    @Test fun freshYadeaSourceDoesNotInheritCarPlayAndExistingNetworkSettingsStillLoad() {
        val values = mutableMapOf<String, Any>(
            "car_source" to "carwith",
            "yadea_instrument_ip" to "192.168.49.1",
            "yadea_connection_mode" to "p2p",
        )
        val settings = YadeaSettings { preferences(values) }

        assertNull(settings.source)
        assertEquals("192.168.49.1", settings.instrumentIp)
        assertEquals(YadeaConnectionMode.P2p, settings.connectionMode)
    }

    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val editor = proxy(SharedPreferences.Editor::class.java) { self, name, args ->
            when (name) {
                "putString" -> self.also { values[args[0] as String] = args[1] as String }
                "apply" -> null
                else -> error("Unexpected editor call: $name")
            }
        }
        return proxy(SharedPreferences::class.java) { _, name, args ->
            when (name) {
                "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference call: $name")
            }
        }
    }

    private fun <T : Any> proxy(type: Class<T>, call: (Any, String, Array<out Any?>) -> Any?): T =
        checkNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            call(self, method.name, args ?: emptyArray())
        }))
}
