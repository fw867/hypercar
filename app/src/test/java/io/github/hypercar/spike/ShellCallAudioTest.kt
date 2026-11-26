package io.github.hypercar.spike

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Test

class ShellCallAudioTest {
    @Test fun scopedDiagnosticsDoNotWaitForTheEndpointLifecycleMonitor() {
        val audio = ShellCallAudio(ShellCallIdentity(elapsed = { 0L }))
        val binderThread = Executors.newSingleThreadExecutor()
        try {
            synchronized(audio) {
                val status = binderThread.submit(Callable { audio.diagnosticStatus(1, 1) })
                assertNull(status.get(1, TimeUnit.SECONDS))
            }
        } finally {
            binderThread.shutdownNow()
        }
    }
}
