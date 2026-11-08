package io.github.hypercar.carui

/** Independent callback revisions let an async refresh fill gaps without rolling back live data. */
internal class MusicSnapshotEpoch {
    data class Token(val session: Long, val metadata: Long, val playback: Long)
    private var session = 0L
    private var metadata = 0L
    private var playback = 0L
    fun capture() = Token(session, metadata, playback)
    fun sessionChanged() { session++; metadata++; playback++ }
    fun metadataChanged() { metadata++ }
    fun playbackChanged() { playback++ }
    fun acceptsMetadata(token: Token) = session == token.session && metadata == token.metadata
    fun acceptsPlayback(token: Token) = session == token.session && playback == token.playback
}
