package io.github.hypercar.carui

import android.content.Context

internal object ShortVideoApps {
    val packages = listOf(
        "com.ss.android.ugc.aweme",
        "com.phoenix.read",
        "com.smile.gifmaker",
        "com.ss.android.ugc.aweme.lite",
        "com.kuaishou.nebula",
    )

    fun isSupported(packageName: String) = packageName in packages

    fun installed(context: Context) = MusicApps.installedEntries(context, packages)
}
