package io.github.hypercar.autowake

import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE as DEVICE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK as PLAYBACK
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE as MIC
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionForegroundPolicyTest {
    @Test fun cancelledReturnFromForegroundTeardownStillReleasesTrack() = runTest {
        val events = mutableListOf<String>()
        val keeper = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                ProjectionPlayback.releaseAfterForeground(
                    restoreForeground = {
                        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.Default) { events += "foreground" }
                    },
                    releaseTrack = { events += "track" },
                )
            }
        }
        keeper.cancel()
        keeper.join()
        assertEquals(listOf("foreground", "track"), events)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun retiringTracksKeepTheirProcessSlotsUntilNativeReleaseFinishes() = runTest {
        val release = CompletableDeferred<Unit>()
        var started = 0
        val retiring = List(2) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                ProjectionPlayback.withTrackSlot {
                    started++
                    try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
                }
            }
        }
        retiring.forEach { it.cancel() }
        runCurrent()
        val acquired = CompletableDeferred<Unit>()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            ProjectionPlayback.withTrackSlot { acquired.complete(Unit) }
        }
        try {
            assertEquals(2, started)
            assertFalse(acquired.isCompleted)
        } finally {
            release.complete(Unit)
        }
        acquired.await()
        replacement.join()
    }

    @Test fun playbackSurvivesARejectedBackgroundMicrophoneType() {
        assertEquals(listOf(DEVICE or MIC or PLAYBACK, DEVICE or PLAYBACK, DEVICE or MIC, DEVICE),
            ProjectionForegroundPolicy.types(microphone = true, playback = true))
        assertEquals(listOf(DEVICE), ProjectionForegroundPolicy.types(microphone = false, playback = false))
    }

    @Test fun heartbeatSeparatesSchedulingGapsFromSuspend() {
        val before = ProjectionHeartbeat.Clock(elapsedMs = 10_000, uptimeMs = 9_000)
        assertNull(ProjectionHeartbeat.gap(before, ProjectionHeartbeat.Clock(10_510, 9_510)))
        assertEquals(2_100L, ProjectionHeartbeat.gap(before, ProjectionHeartbeat.Clock(12_600, 9_500))?.suspendMs)
        assertEquals(2_600L, ProjectionHeartbeat.gap(before, ProjectionHeartbeat.Clock(12_600, 11_600))?.uptimeMs)
    }
}
