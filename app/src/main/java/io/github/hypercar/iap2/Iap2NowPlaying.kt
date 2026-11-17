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
            artworkId?.takeIf { it in 0x80..0xff }?.let { write(Iap2Csm.u8(26, it)) }
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
    private val initializeArtworkWithEmptyFile: Boolean = false,
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
    // One initial snapshot per authenticated link, not per repeated Start/STOP.
    private var firstUpdateSent = false
    private var lastProgressAt = 0L
    // Device -> accessory uses the high half; xiap2 starts at 0x80 and wraps after 0xff.
    private var nextArtworkId = 0x80
    private var artwork: ByteArray? = null
    private var artworkId: Int? = null
    private var pending: Transfer? = null
    private var cancelId: Int? = null
    private var diagnostics = 0
    private var artworkInitializationAttempted = false
    private class Transfer(val id: Int, val bytes: ByteArray, var deadline: Long) {
        var started = false
        var offset = 0
        var endSent = false
    }
    val hasFileWork: Boolean get() = pending != null || cancelId != null
    fun authenticated() { authenticated = true }
    fun restartLink() { val wasAuthenticated = authenticated; reset(); authenticated = wasAuthenticated }
    fun reset() {
        authenticated = false; subscribed = false; requested = null; force = false; nextPoll = 0
        lastBody = null; lastSample = null; pending = null; cancelId = null; artwork = null; artworkId = null
        firstUpdateSent = false
        artworkInitializationAttempted = false
        // Preserve the allocation cursor across link resynchronization on the same transport.
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
                    ids - supportedFields[group].orEmpty() - initialDefaults[group].orEmpty().map { it.id }.toSet()
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
    private fun expirePending(now: Long): Boolean {
        if (pending?.let { now >= it.deadline } != true) return false
        note("artwork response timeout; retain media metadata")
        cancelPending(); artworkId = null; force = subscribed
        return true
    }
    fun fileResponse(bytes: ByteArray, now: Long) {
        if (!authenticated || bytes.size != 2) return
        val transfer = pending ?: return
        if ((bytes[0].toInt() and 255) != transfer.id) return
        // Inbound events precede poll(): a late START/complete cannot revive or accept
        // an expired transfer, even when the periodic timeout check has not run yet.
        if (expirePending(now)) return
        when (bytes[1].toInt() and 255) {
            1 -> if (!transfer.started) {
                transfer.started = true; transfer.deadline = now + 5_000
                note("artwork START id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
            }
            5 -> { note("artwork accepted id=${transfer.id}"); pending = null }
            2, 3, 6 -> { note("artwork declined/cancelled id=${transfer.id}"); pending = null; artworkId = null; force = subscribed }
        }
    }
    fun poll(now: Long, maxPayload: Int, filesSupported: Boolean): List<Output> {
        if (!authenticated) return emptyList()
        val out = mutableListOf<Output>()
        expirePending(now)
        if (subscribed && (force || now >= nextPoll)) {
            // Observe track changes and completed JPEG work promptly. Elapsed-only
            // updates retain their independent five-second wire cadence below.
            nextPoll = now + 100
            val np = try { snapshot() } catch (error: Exception) {
                note("snapshot unavailable: ${error.javaClass.simpleName}"); return out
            }
            val canTransfer = wants(0,26) && filesSupported && maxPayload >= 12
            val realArt = np.artwork?.takeIf { canTransfer && it.isNotEmpty() && it.size <= MAX_ARTWORK }
            val initialize = initializeArtworkWithEmptyFile && !artworkInitializationAttempted && canTransfer
            // Finish the one-time empty handshake before publishing the latest JPEG. Metadata
            // remains live; STOP, timeout and rejection still release the pending transfer.
            val keepEmpty = canTransfer && (pending?.bytes === EMPTY_ARTWORK ||
                (artwork === EMPTY_ARTWORK && realArt == null))
            val nextArt = if (initialize || keepEmpty) EMPTY_ARTWORK else realArt
            var setup: ByteArray? = null
            val artworkChanged = nextArt !== artwork
            if (artworkChanged) {
                cancelPending()
                artwork = nextArt
                artworkId = null
                if (nextArt != null) {
                    if (initialize) artworkInitializationAttempted = true
                    val id = nextArtworkId
                    nextArtworkId = if (id == 0xff) 0x80 else id + 1
                    // Only one transfer is active. Its CANCEL is emitted before the new SETUP;
                    // advancing the cursor never reuses that active/cancelling transfer's ID.
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
                    val body = select(np.body(artworkId, explicitPlayback = requested != null),
                        includeInitialDefaults = !firstUpdateSent)
                    if (body.isNotEmpty()) {
                        out += Output.Control(body)
                        firstUpdateSent = true
                    }
                    // Cache normal content: dropping first-only defaults must not cause an extra update.
                    lastBody = content
                    lastProgressAt = now
                    force = false
                    note("update status=${np.status} artwork=${artworkId != null}; text omitted")
                }
            }
            cancelId?.let { out += Output.File(byteArrayOf(it.toByte(), 2)); cancelId = null }
            setup?.let {
                out += Output.File(it)
                note("artwork SETUP id=0x${artworkId!!.toString(16)} bytes=${nextArt!!.size} type=0x0002; awaiting START")
            }
        }
        cancelId?.let { out += Output.File(byteArrayOf(it.toByte(), 2)); cancelId = null }
        val transfer = pending
        if (transfer != null && transfer.started && !transfer.endSent) {
            if (transfer.bytes.isEmpty()) {
                out += Output.File(byteArrayOf(transfer.id.toByte(), 0xc0.toByte()))
                transfer.endSent = true
                transfer.deadline = now + 5_000
                note("artwork empty END id=0x${transfer.id.toString(16)}; awaiting completion")
                return out
            }
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
                transfer.endSent = end == transfer.bytes.size
                transfer.deadline = now + 5_000
            }
        }
        return out
    }
    private fun wants(group: Int, field: Int): Boolean = requested?.let { field in it[group].orEmpty() } ?: true
    private fun select(body: ByteArray, includeInitialDefaults: Boolean = false): ByteArray {
        if (requested == null) return body
        return Iap2Csm.parseParams(body).fold(byteArrayOf()) { selected, group ->
            val actual = Iap2Csm.parseParams(group.value)
            val present = actual.map { it.id }.toSet()
            val defaults = if (includeInitialDefaults) initialDefaults[group.id].orEmpty()
                .filter { it.id !in present } else emptyList()
            val fields = (actual + defaults).filter { wants(group.id, it.id) }
            if (fields.isEmpty()) selected else selected + Iap2Csm.param(group.id,
                fields.fold(byteArrayOf()) { bytes, field -> bytes + Iap2Csm.param(field.id, field.value) })
        }
    }
    private fun note(message: String) { if (diagnostics++ < 64) log("IAP2-NOWPLAYING: $message") }
    companion object {
        private val EMPTY_ARTWORK = byteArrayOf()
        const val MAX_ARTWORK = 256 * 1024
        // iPhone first 5001 and xiap2 0x62924..0x62ff8 explicitly initialize these fields.
        // Sizes include only the value: empty strings are one NUL byte. Never replace real data,
        // invent an artwork/queue transfer ID, or apply defaults to an implicit legacy subscription.
        private val initialDefaults = mapOf(
            0 to mapOf(0 to 8, 7 to 2, 8 to 2, 9 to 2, 10 to 2, 16 to 1, 18 to 1,
                23 to 1, 24 to 1, 27 to 2),
            1 to mapOf(2 to 4, 4 to 4, 5 to 1, 6 to 1, 8 to 1, 9 to 1, 10 to 1, 11 to 8),
        ).mapValues { (_, sizes) -> sizes.map { (id, size) -> Iap2Csm.Param(id, ByteArray(size)) } }
        private val supportedFields = mapOf(0 to setOf(0,1,4,6,7,8,9,12,16,18,21,22,23,26),
            1 to setOf(0,1,2,3,7,12,13,14,16))
    }
}
