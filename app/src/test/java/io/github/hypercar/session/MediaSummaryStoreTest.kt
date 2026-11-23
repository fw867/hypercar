package io.github.hypercar.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaSummaryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun keepsAnchorsAndCriticalEventsAcrossProcessRestartWithoutDuplicatingLiveRows() {
        val file = File(folder.root, "media-summary")
        val first = ConnectionDiagnostics { 1_000L }
        val attempt = first.begin("manual", "bmw_id9")
        first.engineStarted(attempt, 3, null, 200, 4_000)
        first.mediaEvent(attempt, 3, "MEDIA-TIMELINE: 201 first_video_written socket_write")
        first.mediaEvent(attempt, 3, "MEDIA-TIMELINE: 202 feedback_timeout RTSP#1 CSeq=14")
        first.mediaEvent(attempt, 3, "MEDIA-TIMELINE: 203 feedback_queued RTSP#1 CSeq=15 afterTimeout=true")
        first.eventChannel(attempt, 3, 2, 204, "local_drop", "")
        first.finish(attempt, "STOPPED", "控制保活失败")
        val live = first.mediaSummaries()
        MediaSummaryStore(file).save(live)

        val reopened = MediaSummaryStore(file)
        assertTrue(reopened.previous(live).isEmpty())
        val second = ConnectionDiagnostics { 1_000L }
        val next = second.begin("manual", "bmw_id85")
        second.engineStarted(next, 3, null, 200, 4_000)
        second.mediaEvent(next, 3, "MEDIA-TIMELINE: 205 hid_touch_in RTSP#1 bytes=6 mapped=true")
        reopened.save(second.mediaSummaries())
        val previous = reopened.previous(second.mediaSummaries()).single()
        for (evidence in listOf("anchor_uptime_ms=200", "anchor_unix_ms=4000", "CSeq=14", "CSeq=15", "local_drop", "控制保活失败")) {
            assertTrue(previous.contains(evidence))
        }
    }

    @Test fun retainsOnlyLatestEightDistinctSessions() {
        val store = MediaSummaryStore(File(folder.root, "media-summary"))
        repeat(12) { store.save(mapOf("key$it" to "row$it")) }
        assertEquals((4..11).map { "row$it" }, store.previous(emptyMap()))
    }
}
