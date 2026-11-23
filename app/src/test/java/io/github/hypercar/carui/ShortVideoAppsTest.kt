package io.github.hypercar.carui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortVideoAppsTest {
    @Test fun detectsSupportedInstalledAppsWithoutUsingTheMusicPlayerList() {
        val douyin = "com.ss.android.ugc.aweme"
        val hongguo = "com.phoenix.read"
        val kuaishou = "com.smile.gifmaker"
        val qqMusic = "com.tencent.qqmusic"
        val installedWithLauncher = setOf(douyin, hongguo, kuaishou, qqMusic, "com.example.video")
        assertEquals(listOf(douyin, hongguo, kuaishou),
            ShortVideoApps.packages.filter(installedWithLauncher::contains))
        assertFalse(ShortVideoApps.isSupported(qqMusic))
        assertFalse(MusicApps.isKnownPlayer(douyin))
        assertTrue(ShortVideoApps.isSupported("com.kuaishou.nebula"))
    }
}
