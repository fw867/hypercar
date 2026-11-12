package io.github.hypercar.navigation

import org.json.JSONObject

/** Numeric business state only: no route IDs, road names, coordinates or opaque payloads. */
internal object MapNavigationDiagnostic {
    fun event(type: Int, data: JSONObject?): String? {
        val fields = when (type) {
            3 -> listOf("eventType", "errorCode", "routeType", "status", "operationType")
            7 -> listOf("naviMode", "naviType")
            18 -> listOf("modeType")
            else -> return null
        }
        val values = fields.mapNotNull { field ->
            val value = data?.opt(field) as? Number ?: return@mapNotNull null
            val number = value.toDouble()
            if (!number.isFinite() || number != value.toLong().toDouble()) null else "$field=${value.toLong()}"
        }
        return if (values.isEmpty()) null else "type=$type ${values.joinToString(" ")}; business state, rendering unknown"
    }
}
