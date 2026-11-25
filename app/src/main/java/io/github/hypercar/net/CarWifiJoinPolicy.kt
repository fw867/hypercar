package io.github.hypercar.net

/** iAP2 SecurityType values. Unknown declarations must not silently become open networks. */
internal object CarWifiJoinPolicy {
    enum class Security { OPEN, WPA2, WPA3 }

    fun canRetryHint(failureStage: String?, timedOut: Boolean): Boolean = when (failureStage) {
        "association", "ap_not_found", "ap_no_response" -> true
        null, "unknown" -> timedOut
        else -> false
    }

    fun security(declared: Int?, password: String): Security? = when {
        declared == 1 || declared != null && declared !in 0..4 -> null // WEP/unknown
        password.isEmpty() -> if (declared == null || declared == 0) Security.OPEN else null
        password.length !in 8..63 -> null
        declared == 4 -> Security.WPA3
        else -> Security.WPA2 // legacy 0-with-password and WPA2/WPA3 transition keep WPA2
    }

    // The unqualified legacy channel number is not enough to infer a 6 GHz band.
    fun frequency(channel: Int?): Int? = when {
        channel == null -> null
        channel in 1..13 -> 2407 + 5 * channel
        channel == 14 -> 2484
        channel in 36..64 && channel % 4 == 0 || channel in 100..144 && channel % 4 == 0 ||
            channel in 149..177 && (channel - 149) % 4 == 0 -> 5000 + 5 * channel
        else -> null
    }
}
