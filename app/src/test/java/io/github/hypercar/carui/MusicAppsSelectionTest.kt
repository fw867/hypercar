package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicAppsSelectionTest {
    private val huawei = "com.huawei.music"
    private val qq = "com.tencent.qqmusic"

    @Test fun soleInstalledPlayerIsShownBeforeMediaSessionStarts() {
        assertEquals(huawei, MusicApps.idlePlayer(listOf(huawei), null, "uninstalled.player"))
    }

    @Test fun selectedPlayerWinsOverDefaultAndUninstalledSelectionFallsBack() {
        assertEquals(huawei, MusicApps.idlePlayer(listOf(huawei, qq), huawei, qq))
        assertEquals(qq, MusicApps.idlePlayer(listOf(huawei, qq), "uninstalled.player", qq))
    }

    @Test fun unspecifiedMultiplePlayersRequireAChoice() {
        assertNull(MusicApps.idlePlayer(listOf(huawei, qq), null, ""))
        assertNull(MusicApps.idlePlayer(emptyList(), huawei, huawei))
    }
}
