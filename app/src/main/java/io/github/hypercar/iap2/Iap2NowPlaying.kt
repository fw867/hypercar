package io.github.hypercar.iap2

/** Immutable snapshot; providers must return cached data without Binder or bitmap work. */
data class Iap2NowPlaying(
    val title: String = "", val artist: String = "", val album: String = "",
    val appName: String = "", val durationMs: Long = 0, val elapsedMs: Long = 0,
    val status: Int = 0, val artwork: ByteArray? = null,
) {
    fun body(artworkId: Int?): ByteArray {
        // Bound UTF-8 fields as well as numeric values; a bad player must not exhaust CSM u16.
        fun text(id: Int, value: String) = Iap2Csm.string(id, value.replace("\u0000", "").take(192))
        fun millis(value: Long) = value.coerceIn(0, 0xffff_ffffL)
        val item = text(1, title) + Iap2Csm.u32(4, millis(durationMs)) +
            text(6, album) + text(12, artist) +
            (artworkId?.takeIf { it in 1..255 }?.let { Iap2Csm.u8(26, it) } ?: byteArrayOf())
        val playback = Iap2Csm.u8(0, status.coerceIn(0, 2)) + Iap2Csm.u32(1, millis(elapsedMs)) +
            text(7, appName)
        return Iap2Csm.param(0, item) + Iap2Csm.param(1, playback)
    }
}

/** One subscription and artwork transaction per authenticated transport. No extra worker threads. */
internal class Iap2NowPlayingSession(
    private val snapshot: () -> Iap2NowPlaying = { Iap2NowPlaying() },
    private val log: (String) -> Unit = {},
) {
    sealed class Output {
        class Control(val body: ByteArray) : Output()
        class File(val bytes: ByteArray) : Output()
    }
    private var authenticated = false
    private var subscribed = false
    private var force = false
    private var nextPoll = 0L
    private var lastBody: ByteArray? = null
    private var lastProgressAt = 0L
    private var nextArtworkId = 1
    private var artwork: ByteArray? = null
    private var artworkId: Int? = null
    private var pending: Transfer? = null
    private var cancelId: Int? = null
    private var diagnostics = 0
    private class Transfer(val id: Int, val bytes: ByteArray, var deadline: Long) {
        var started = false
        var offset = 0
    }
    val hasFileWork: Boolean get() = pending != null || cancelId != null
    fun authenticated() { authenticated = true }
    fun restartLink() { val wasAuthenticated = authenticated; reset(); authenticated = wasAuthenticated }
    fun reset() {
        authenticated = false; subscribed = false; force = false; nextPoll = 0
        lastBody = null; pending = null; cancelId = null; artwork = null; artworkId = null
        // Do not recycle transfer IDs across link resynchronization on the same transport.
    }
    fun handle(frame: Iap2Csm.Frame): Boolean = when (frame.messageId) {
        Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
            subscribed = true; force = true; nextPoll = 0; true
        }
        Iap2MessageIds.STOP_NOW_PLAYING_UPDATES -> {
            subscribed = false; lastBody = null; cancelPending(); artwork = null; artworkId = null; true
        }
        else -> false
    }
    private fun cancelPending() { pending?.let { cancelId = it.id }; pending = null }
    fun fileResponse(bytes: ByteArray, now: Long) {
        if (!authenticated || bytes.size != 2) return
        val transfer = pending ?: return
        if ((bytes[0].toInt() and 255) != transfer.id) return
        when (bytes[1].toInt() and 255) {
            1 -> if (!transfer.started) { transfer.started = true; transfer.deadline = now + 5_000 }
            5 -> { note("artwork accepted id=${transfer.id}"); pending = null }
            2, 3, 6 -> { note("artwork declined/cancelled id=${transfer.id}"); pending = null; artworkId = null; force = subscribed }
        }
    }
    fun poll(now: Long, maxPayload: Int, filesSupported: Boolean): List<Output> {
        if (!authenticated) return emptyList()
        val out = mutableListOf<Output>()
        if (pending?.let { now >= it.deadline } == true) {
            note("artwork response timeout; retain media metadata")
            cancelPending(); artworkId = null; force = subscribed
        }
        if (subscribed && (force || now >= nextPoll)) {
            nextPoll = now + 1_500
            val np = try { snapshot() } catch (error: Exception) {
                note("snapshot unavailable: ${error.javaClass.simpleName}"); return out
            }
            val nextArt = np.artwork?.takeIf { filesSupported && maxPayload >= 12 && it.isNotEmpty() && it.size <= MAX_ARTWORK }
            var setup: ByteArray? = null
            if (nextArt !== artwork) {
                cancelPending()
                artwork = nextArt
                artworkId = null
                if (nextArt != null && nextArtworkId <= 255) {
                    val id = nextArtworkId++
                    artworkId = id
                    pending = Transfer(id, nextArt, now + 5_000)
                    // SETUP: transfer ID, command, u64 size, u16 artwork type (big endian).
                    setup = byteArrayOf(id.toByte(), 4) + ByteArray(8) { i ->
                        (nextArt.size.toLong() ushr (56 - 8 * i)).toByte()
                    } + byteArrayOf(0, 2)
                }
            }
            val body = np.body(artworkId)
            // Elapsed changes are useful but do not flood the link every polling interval.
            val content = np.copy(elapsedMs = 0).body(artworkId)
            if (force || !content.contentEquals(lastBody) || (np.status == 1 && now - lastProgressAt >= 5_000)) {
                out += Output.Control(body)
                lastBody = content
                lastProgressAt = now
                force = false
                note("update status=${np.status} artwork=${artworkId != null}; text omitted")
            }
            cancelId?.let { out += Output.File(byteArrayOf(it.toByte(), 2)); cancelId = null }
            setup?.let { out += Output.File(it) }
        }
        cancelId?.let { out += Output.File(byteArrayOf(it.toByte(), 2)); cancelId = null }
        val transfer = pending
        if (transfer != null && transfer.started && transfer.offset < transfer.bytes.size) {
            val chunkSize = minOf(2_000, maxPayload - 2)
            if (chunkSize <= 0) { cancelPending(); return out }
            // Bound each turn so large artwork never monopolises auth/HID/HUD/ACK handling.
            repeat(2) {
                val start = transfer.offset
                if (start >= transfer.bytes.size) return@repeat
                val end = minOf(start + chunkSize, transfer.bytes.size)
                val command = (if (start == 0) 0x80 else 0) or (if (end == transfer.bytes.size) 0x40 else 0)
                out += Output.File(byteArrayOf(transfer.id.toByte(), command.toByte()) + transfer.bytes.copyOfRange(start, end))
                transfer.offset = end
                transfer.deadline = now + 5_000
            }
        }
        return out
    }
    private fun note(message: String) { if (diagnostics++ < 64) log("IAP2-NOWPLAYING: $message") }
    companion object { const val MAX_ARTWORK = 256 * 1024 }
}
