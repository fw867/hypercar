package io.github.hypercar.session

internal data class OldDeviceReadiness(
    val attempt: Long,
    val network: String?,
    val srv: Srv?,
    val discoverable: Boolean?,
) {
    data class Srv(val host: String, val port: Int)

    val ready: Boolean get() = network != null && srv != null && discoverable == true

    fun diagnosticFields(): String = "attempt=$attempt ready=$ready wifiBound=${network != null} " +
        "network=${network ?: "none"} srvSent=${srv != null} srvHost=${srv?.host ?: "none"} " +
        "srvPort=${srv?.port ?: "none"} btDiscoverable=${discoverable ?: "unknown"}"
}
