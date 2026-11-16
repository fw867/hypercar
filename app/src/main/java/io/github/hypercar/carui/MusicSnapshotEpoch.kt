package io.github.hypercar.carui

/** Independent callback revisions let an async refresh fill gaps without rolling back live data. */
internal class MusicSnapshotEpoch {
    data class Token(val session: Long, val metadata: Long, val playback: Long, val queue: Long)
    private var session = 0L
    private var metadata = 0L
    private var playback = 0L
    private var queue = 0L
    fun capture() = Token(session, metadata, playback, queue)
    fun queueChanged() { queue++ }
    fun acceptsQueue(token: Token) = session == token.session && queue == token.queue
    fun sessionChanged() { session++; metadata++; playback++ }
    fun metadataChanged() { metadata++ }
    fun playbackChanged() { playback++ }
    fun acceptsMetadata(token: Token) = session == token.session && metadata == token.metadata
    fun acceptsPlayback(token: Token) = session == token.session && playback == token.playback
}
