package io.github.hypercar.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapNavigationDiagnosticTest {
    @Test fun routeErrorsAndSceneModesAreLoggedWithoutRouteIdentity() {
        val result=MapNavigationDiagnostic.event(3,JSONObject("""{"errorCode":12,"status":0,"pathID":"private-route","latitude":31.123,"curRouteName":"private-road"}"""))!!
        assertTrue(result.contains("errorCode=12 status=0"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("31.123"))
        assertEquals("type=18 modeType=2; business state, rendering unknown",MapNavigationDiagnostic.event(18,JSONObject("""{"modeType":2}""")))
    }
    @Test fun opaqueOrMalformedValuesCannotLeakIntoLogs() {
        assertNull(MapNavigationDiagnostic.event(3,JSONObject("""{"errorCode":"secret","status":1.5}""")))
        assertNull(MapNavigationDiagnostic.event(27,JSONObject("""{"status":1}""")))
        assertNull(MapNavigationDiagnostic.event(7,null))
    }
}
