package io.github.hypercar.carui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** CarLife 内置的三套壁纸；每套都有白天/暗夜两张。 */
enum class CarWallpaper(
    val id: String,
    val label: String,
    val dayAsset: String,
    val nightAsset: String,
) {
    GUANGYING(
        "guangying",
        "光影空间",
        "wallpapers/guangying_day.webp",
        "wallpapers/guangying_night.webp",
    ),
    LIUSHA(
        "liusha",
        "流沙星影",
        "wallpapers/liusha_day.webp",
        "wallpapers/liusha_night.webp",
    ),
    DEFAULT(
        "default",
        "默认",
        "wallpapers/default_day.webp",
        "wallpapers/default_night.webp",
    ),
}

object CarWallpapers {
    fun load(context: Context, wallpaper: CarWallpaper, dark: Boolean): Bitmap? = runCatching {
        context.assets.open(if (dark) wallpaper.nightAsset else wallpaper.dayAsset).use {
            BitmapFactory.decodeStream(it)
        }
    }.getOrNull()

    fun fromId(id: String?): CarWallpaper =
        CarWallpaper.entries.firstOrNull { it.id == id } ?: CarWallpaper.GUANGYING
}
