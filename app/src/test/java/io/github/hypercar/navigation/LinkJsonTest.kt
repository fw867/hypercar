package io.github.hypercar.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LinkJsonTest {
    @Test fun acceptsBoundedQuotedAndPercentEncodedJson() {
        val json = "{\"type\":\"navigation\",\"distance\":36}"
        assertEquals(36, LinkJson.objectValue(json)!!.getInt("distance"))
        assertEquals(36, LinkJson.objectValue(JSONObject.quote(json))!!.getInt("distance"))
        assertEquals(36, LinkJson.objectValue(java.net.URLEncoder.encode(json, "UTF-8"))!!.getInt("distance"))
    }
    @Test fun invalidPayloadDoesNotBecomeNavigation() {
        assertNull(LinkJson.objectValue("not_json"))
        assertNull(LinkJson.objectValue("%7Bbad"))
        assertNull(LinkJson.objectValue("x".repeat(131073)))
        assertEquals("percent", LinkJson.encoding("%7B%22type"))
    }
}
