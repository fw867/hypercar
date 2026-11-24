package io.github.hypercar.iap2

private const val CERTIFICATE_PARAM_THRESHOLD_BYTES = 644

/** xiap2 selects the challenge length from the AA01 certificate parameter, including its TLV header. */
internal fun mfiChallengeLengthForCertificateParam(totalBytes: Int): Int =
    if (totalBytes > CERTIFICATE_PARAM_THRESHOLD_BYTES) 20 else 32
