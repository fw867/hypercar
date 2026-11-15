package io.github.hypercar.session

internal object AirplayFailurePolicy {
    fun waitsForNewInvitation(profileId: String, detail: String): Boolean {
        if (profileId != "bmw_id9") return false
        if (listOf("SETUP", "RESOURCES", "SCREEN_CLAIM", "RECORD").none { it in detail }) return false
        return Regex("(?:RTSP/1\\.0\\s+|RTSP status=)(400|422)(?=\\D|$)").containsMatchIn(detail)
    }
}
