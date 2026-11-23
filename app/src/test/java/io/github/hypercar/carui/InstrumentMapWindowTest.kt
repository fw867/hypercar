package io.github.hypercar.carui

import io.github.hypercar.spike.ShellInstrumentMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentMapWindowTest {
    private fun instrumentMapFocused(dump: String, id: Int) = instrumentMapWindow(dump.lineSequence(), id) != null
    private val map = "mCurrentFocus=Window{abc23 u0 com.autonavi.minimap/com.autonavi.map.activity.UcarMapActivity}"
    private val phone = "Display: mDisplayId=0 rootTasks=2\n$map"
    private val card = "mImeWindow=null Display: mDisplayId=12 (organized)"

    @Test fun onlyFocusedNativeCardWithConfirmedHiddenImeCanSupplyClusterPixels() {
        val ime = "InsetsSource id=3 type=ime frame=[0,0][0,0] visible="
        assertTrue(instrumentMapFocused("$phone\n${ime}true\n$card\n$map\n${ime}false", 12))
        assertTrue(instrumentMapFocused("$card\n$map\nmImeWindow=null", 12))
        assertFalse(instrumentMapFocused("$phone\n$card\nmCurrentFocus=null\n${ime}false", 12))
        assertFalse(instrumentMapFocused("$card\n$map\nmImeWindow=null\n${ime}true", 12))
        assertFalse(instrumentMapFocused("$card\n$map", 12))
        assertFalse(instrumentMapFocused("$card\nmCurrentFocus=Window{abc01 u0 Permission Dialog}\n${ime}false", 12))
        assertFalse(instrumentMapFocused("$card\n$map\n${ime}false", 2))
    }

    data class Layer(val uid: Int, val secure: Boolean, val label: String)
    class LayerArgsBuilder {
        private var filter = -1L
        private var secure = true
        fun setUid(uid: Long) { filter = uid }
        fun setCaptureSecureLayers(include: Boolean) { secure = include }
        fun build(): (Layer) -> Boolean = { layer ->
            (filter == -1L || layer.uid.toLong() == filter) && (secure || !layer.secure)
        }
    }

    class PolicyArgsBuilder {
        var owner = -1L
        var secure = -1
        var protected = -1
        fun setUid(value: Long) { owner = value }
        fun setSecureContentPolicy(value: Int) { secure = value }
        fun setProtectedContentPolicy(value: Int) { protected = value }
        fun build() = this
    }
    class PolicyValues {
        companion object {
            const val SECURE_CONTENT_POLICY_REDACT = 5
            const val PROTECTED_CONTENT_POLICY_REDACT = 9
        }
    }

    @Test fun newerLayerCaptureUsesExplicitRedactionForSecureAndProtectedContent() {
        val builder = PolicyArgsBuilder()
        ShellInstrumentMap.filteredArgs(builder, 10291) { PolicyValues::class.java }
        assertEquals(10291L, builder.owner)
        assertEquals(5, builder.secure)
        assertEquals(9, builder.protected)
    }

    @Test fun mixedOwnersOnlyContributeMapUidLayers() {
        val layers = listOf(Layer(10291, false, "map"), Layer(10291, true, "secure"),
            Layer(1000, false, "overlay"), Layer(10031, false, "keyboard"))
        val result = readInstrumentMap(10291, 0, { InstrumentMapWindow("abc23", 0) },
            { uid ->
                @Suppress("UNCHECKED_CAST")
                val predicate = ShellInstrumentMap.filteredArgs(LayerArgsBuilder(), uid) as (Layer) -> Boolean
                layers.filter(predicate).map { it.label }
            }, { it }, {})
        assertEquals(listOf("map"), result)
    }

    @Test fun emptyCompositorResultCannotQualifyAsMapPixels() {
        fun detail(vararg pixels: Int) = ShellInstrumentMap.hasMapPixels(java.nio.IntBuffer.wrap(pixels))
        assertFalse(detail())
        assertFalse(detail(0, 0, 0))
        assertFalse(detail(-16777216, -16777216, -16777216))
        assertTrue(detail(-16777216, -16777216, -1))
    }

    @Test fun rejectsImeFocusAndWindowReplacementDuringReadAndDisposesUnpublishedFrame() {
        val initial = "$card\n$map\nmImeWindow=null"
        for (after in listOf(initial.replace("abc23", "abc77"), initial.replace("mImeWindow=null", "type=ime visible=true"),
            initial.replace("u0 ", "u10 "), initial.replace("UcarMapActivity", "SearchActivity"))) {
            val observations = listOf(initial, after).iterator()
            var released = false
            val result = readInstrumentMap(10291, 0, { instrumentMapWindow(observations.next().lineSequence(), 12) },
                { "captured" }, { it }, { released = true })
            assertNull(result)
            assertTrue(released)
        }
    }
}
