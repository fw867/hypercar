package io.github.hypercar.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeDisplayTest {
    @Test fun projectionLaunchUsesTheDesktopActionAndCategory() {
        assertEquals(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n 'com.example.reader/.LaunchAlias' --display 8",
            SpikeDisplay.launcherCommand(8, "com.example.reader/.LaunchAlias"),
        )
    }
}
