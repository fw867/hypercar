package io.github.hypercar.iap2

/** Session identities survive retries; a reply cannot confirm another protocol's session. */
internal class Iap2EaSessions {
    private data class Session(val id: Int, var confirmed: Boolean = false)
    private val sessions = linkedMapOf<Pair<Int,String>,Session>()
    val allConfirmed: Boolean get() = sessions.isNotEmpty() && sessions.values.all { it.confirmed }
    fun open(protocolId: Int, name: String): ByteArray {
        val session = sessions.getOrPut(protocolId to name) { Session(sessions.size + 1) }
        return Iap2Messages.eaOpenSession(protocolId,session.id)
    }
    fun status(body: ByteArray): Boolean {
        val fields = Iap2Csm.parseParams(body)
        if (fields.sumOf { it.value.size + 4 } != body.size) return false
        val ids = fields.filter { it.id == 0 }; val statuses = fields.filter { it.id == 1 }
        if (ids.size != 1 || statuses.size != 1 || ids.single().value.size != 2 || statuses.single().value.size != 1) return false
        val id = ids.single().value.let { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
        val status = statuses.single().value[0].toInt() and 255
        if (status !in 0..1) return false
        val session = sessions.values.firstOrNull { it.id == id } ?: return false
        session.confirmed = status == 0
        return true
    }
}
