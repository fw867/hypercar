package io.github.hypercar.spike

import org.junit.Assert.*
import org.junit.Test

class PlaybackCaptureLifecycleTest {
    @Test fun navigation_and_media_rules_partition_the_target_uid_without_overlap() {
        val rules = playbackUidRules(10314)
        assertEquals(2, rules.size)
        for ((source, expected) in listOf(10314 to listOf(1), 10315 to listOf(0), 2000 to listOf(0))) {
            val routed = rules.indices.filter { lane ->
                val rule = rules[lane]
                if (rule.exclude) source != rule.uid else source == rule.uid
            }
            assertEquals("source UID=$source must occur in exactly one capture lane", expected, routed)
        }
    }
    @Test fun rollback_closes_every_resource_in_reverse_order_even_if_one_close_fails() {
        val cleanup = PlaybackCaptureCleanup(); val closed = mutableListOf<String>()
        cleanup.own { closed += "policy" }
        cleanup.own { closed += "media-record"; error("release failure") }
        cleanup.own { closed += "navigation-record" }
        cleanup.own { closed += "pipes" }
        cleanup.close(); cleanup.close()
        assertEquals(listOf("pipes", "navigation-record", "media-record", "policy"), closed)
    }
    @Test fun ownership_after_rollback_immediately_closes_the_resource_and_aborts_startup() {
        val cleanup = PlaybackCaptureCleanup(); var closes = 0
        cleanup.close()
        assertThrows(IllegalStateException::class.java) { cleanup.own { closes++ } }
        assertEquals(1, closes)
    }
    @Test fun invalid_uid_cannot_accidentally_capture_all_applications() {
        for (uid in listOf(-1, 0)) assertThrows(IllegalArgumentException::class.java) { playbackUidRules(uid) }
    }
}
