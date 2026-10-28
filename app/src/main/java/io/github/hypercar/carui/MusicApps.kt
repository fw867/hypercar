package io.github.hypercar.carui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * 已安装的音乐 App 列表（只列装了的，按常用顺序）。
 *
 * 不遍历所有 App：音乐卡片左上角只放用户真正会用来放歌的几个，
 * 避免把系统播放器/视频 App 混进来。
 */
object MusicApps {

    data class Entry(
        val packageName: String,
        val label: String,
        val icon: Bitmap?,
    )

    private val CANDIDATES = listOf(
        "com.tencent.qqmusic",      // QQ 音乐
        "com.luna.music",           // 汽水音乐
        "com.kugou.android",        // 酷狗音乐
        "com.netease.cloudmusic",   // 网易云音乐
        "cn.kuwo.player",           // 酷我音乐
        "com.spotify.music",        // Spotify
        "com.apple.android.music",  // Apple Music
        "cmccwm.mobilemusic",       // 咪咕音乐
    )

    fun installed(context: Context): List<Entry> {
        val pm = context.packageManager
        return CANDIDATES.mapNotNull { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                // 没有 Launcher 入口的（组件被禁用/仅服务）不算可切换
                if (pm.getLaunchIntentForPackage(pkg) == null) return@runCatching null
                Entry(
                    packageName = pkg,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = drawableToBitmap(pm.getApplicationIcon(ai), 96),
                )
            }.getOrNull()
        }
    }

    fun iconFor(context: Context, packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        return runCatching {
            drawableToBitmap(pm.getApplicationIcon(packageName), 96)
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width == size && src.height == size) return src
            return Bitmap.createScaledBitmap(src, size, size, true)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }
}
