package io.github.hypercar.iap2

import io.github.hypercar.carui.MediaPlaybackDetails
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Immutable snapshot; providers must return cached data without Binder or bitmap work. */
data class Iap2NowPlaying(
    val title: String = "", val artist: String = "", val album: String = "",
    val appName: String = "", val durationMs: Long = 0, val elapsedMs: Long = 0,
    val status: Int = 0, val artwork: ByteArray? = null,
    val appBundleId: String = "",
    val details: MediaPlaybackDetails = MediaPlaybackDetails(),
) {
    fun body(artworkId: Int?, explicitPlayback: Boolean = false): ByteArray {
        // Bound UTF-8 fields as well as numeric values; a bad player must not exhaust CSM u16.
        fun text(id: Int, value: String) = Iap2Csm.string(id, value.replace("\u0000", "").take(192))
        fun millis(value: Long) = value.coerceIn(0, 0xffff_ffffL)
        val item = ByteArrayOutputStream().apply {
            if (explicitPlayback) details.itemId?.let { write(Iap2Csm.u64(0, it)) }
            write(text(1, title))
            write(Iap2Csm.u32(4, millis(durationMs)))
            write(text(6, album))
            if (explicitPlayback) {
                details.trackNumber?.let { write(Iap2Csm.u16(7, it)) }
                details.trackCount?.let { write(Iap2Csm.u16(8, it)) }
                details.discNumber?.let { write(Iap2Csm.u16(9, it)) }
            }
            write(text(12, artist))
            if (explicitPlayback) {
                if (details.genre.isNotBlank()) write(text(16, details.genre))
                if (details.composer.isNotBlank()) write(text(18, details.composer))
                // No remote like/ban command handler. The player may still publish its current rating.
                write(Iap2Csm.u8(21, 0))
                write(Iap2Csm.u8(22, 0))
                details.liked?.let { write(Iap2Csm.u8(23, if (it) 1 else 0)) }
            }
            artworkId?.takeIf { it in 1..255 }?.let { write(Iap2Csm.u8(26, it)) }
        }
        val playback = ByteArrayOutputStream().apply {
            write(Iap2Csm.u8(0, status.coerceIn(0, 2)))
            write(Iap2Csm.u32(1, millis(elapsedMs)))
            if (explicitPlayback) {
                details.queueIndex?.let { write(Iap2Csm.u32(2, it.toLong())) }
                write(Iap2Csm.u32(3, details.queueCount?.toLong() ?: 0))
            }
            write(text(7, appName))
            if (explicitPlayback) {
                val speed = details.speed?.takeIf { it.isFinite() && it in 0f..655.35f }
                write(Iap2Csm.u16(12, speed?.let { (it * 100).roundToInt() } ?: 0))
                write(Iap2Csm.u8(13, 0))
                write(Iap2Csm.u8(14, 0))
                write(text(16, appBundleId))
            }
        }
        return Iap2Csm.param(0, item.toByteArray()) + Iap2Csm.param(1, playback.toByteArray())
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
    // An empty legacy Start requests the compatibility snapshot; explicit groups select fields.
    private var requested: Map<Int, Set<Int>>? = null
    private var force = false
    private var nextPoll = 0L
    private var lastSample: Iap2NowPlaying? = null
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
        authenticated = false; subscribed = false; requested = null; force = false; nextPoll = 0
        lastBody = null; lastSample = null; pending = null; cancelId = null; artwork = null; artworkId = null
        // Do not recycle transfer IDs across link resynchronization on the same transport.
    }
    fun handle(frame: Iap2Csm.Frame): Boolean = when (frame.messageId) {
        Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
            val groups = Iap2Csm.parseParams(frame.body)
            val fields = groups.associate { it.id to Iap2Csm.parseParams(it.value) }
            if (fields.size != groups.size || groups.sumOf { it.value.size + 4 } != frame.body.size ||
                groups.any { group -> fields.getValue(group.id).sumOf { it.value.size + 4 } != group.value.size }) {
                note("malformed Start ignored body_bytes=${frame.body.size}")
            } else {
                requested = if (frame.body.isEmpty()) null else fields.mapValues { (_, values) -> values.map { it.id }.toSet() }
                note("Start body_bytes=${frame.body.size} fields=${requested?.entries?.take(8)?.associate { it.key to it.value.sorted().take(32) } ?: "legacy-default"}; values omitted")
                val unsupported = requested?.mapValues { (group, ids) ->
                    ids - supportedFields[group].orEmpty()
                }?.filterValues { it.isNotEmpty() }
                if (!unsupported.isNullOrEmpty()) note("requested unsupported fields=${unsupported.entries.take(8).associate { it.key to it.value.sorted().take(32) }}; values omitted")
                subscribed = true; force = true; nextPoll = 0
            }
            true
        }
        Iap2MessageIds.STOP_NOW_PLAYING_UPDATES -> {
            subscribed = false; lastBody = null; lastSample = null; cancelPending(); artwork = null; artworkId = null; true
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
            // Observe track changes and completed JPEG work promptly. Elapsed-only
            // updates retain their independent five-second wire cadence below.
            nextPoll = now + 100
            val np = try { snapshot() } catch (error: Exception) {
                note("snapshot unavailable: ${error.javaClass.simpleName}"); return out
            }
            val nextArt = np.artwork?.takeIf { wants(0,26) && filesSupported && maxPayload >= 12 && it.isNotEmpty() && it.size <= MAX_ARTWORK }
            var setup: ByteArray? = null
            val artworkChanged = nextArt !== artwork
            if (artworkChanged) {
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
            val progressDue = wants(1,1) && np.status == 1 && now - lastProgressAt >= 5_000
            if (force || progressDue || artworkChanged || np != lastSample) {
                lastSample = np
                val content = select(np.copy(elapsedMs = 0).body(artworkId, explicitPlayback = requested != null))
                if (force || progressDue || !content.contentEquals(lastBody)) {
                    val body = select(np.body(artworkId, explicitPlayback = requested != null))
                    if (body.isNotEmpty()) out += Output.Control(body)
                    lastBody = content
                    lastProgressAt = now
                    force = false
                    note("update status=${np.status} artwork=${artworkId != null}; text omitted")
                }
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
    private fun wants(group: Int, field: Int): Boolean = requested?.let { field in it[group].orEmpty() } ?: true
    private fun select(body: ByteArray): ByteArray {
        if (requested == null) return body
        return Iap2Csm.parseParams(body).fold(byteArrayOf()) { selected, group ->
            val fields = Iap2Csm.parseParams(group.value).filter { wants(group.id, it.id) }
            if (fields.isEmpty()) selected else selected + Iap2Csm.param(group.id,
                fields.fold(byteArrayOf()) { bytes, field -> bytes + Iap2Csm.param(field.id, field.value) })
        }
    }
    private fun note(message: String) { if (diagnostics++ < 64) log("IAP2-NOWPLAYING: $message") }
    companion object {
        const val MAX_ARTWORK = 256 * 1024
        private val supportedFields = mapOf(0 to setOf(0,1,4,6,7,8,9,12,16,18,21,22,23,26),
            1 to setOf(0,1,2,3,7,12,13,14,16))
    }
}
