package io.github.hypercar.spike

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import io.github.hypercar.session.HyperCarRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P0 spike 取证（SPIKE-P0）。
 *
 * 每个 spike 的结论都要能被复现：报告文本 + 截图一起写到
 * `Download/HyperCar/spike/`，同时在应用日志里留一份（`adb logcat -s HyperCar` 可看）。
 *
 * 为什么不只依赖 logcat：HyperCarRepo 的日志折叠器会压掉同一条重复行，
 * 而 spike 自己需要完整的原始序列（多少次 tap、每次延迟多少）。
 */
object SpikeReport {

    private const val MAX_LINES = 4000
    private const val SUB_DIR = "HyperCar/spike"
    private val lines = ArrayList<String>(512)
    private val lock = Any()

    /** 开一轮新实验（清空上一轮）。 */
    fun reset(title: String) {
        synchronized(lock) {
            lines.clear()
            lines.add("===== $title @ ${dateStamp()} =====")
        }
        HyperCarRepo.log("SPIKE: ===== $title =====")
    }

    fun add(msg: String) {
        val line = "${timeStamp()}  $msg"
        synchronized(lock) {
            lines.add(line)
            if (lines.size > MAX_LINES) lines.removeAt(0)
        }
        HyperCarRepo.log("SPIKE: $msg")
    }

    fun add(tag: String, msg: String) = add("[$tag] $msg")

    fun size(): Int = synchronized(lock) { lines.size }

    /** 最近 [n] 行（控制台上直接显示，不用先导出）。 */
    fun tail(n: Int = 30): String = synchronized(lock) {
        lines.takeLast(n).joinToString("\n")
    }

    /** 把当前缓冲整段写成 `Download/HyperCar/spike/<name>-<时间戳>.txt`。 */
    fun write(context: Context, name: String): String? {
        val text = synchronized(lock) { lines.joinToString("\n") }
        return writeText(context, name, text)
    }

    fun writeText(context: Context, name: String, text: String): String? = runCatching {
        val file = "$name-${fileStamp()}.txt"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("输出流打开失败")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        "/storage/emulated/0/Download/$SUB_DIR/$file"
    }.onFailure { HyperCarRepo.log("SPIKE: 写报告失败 ${it.message}") }.getOrNull()

    /** 把截图写成 `Download/HyperCar/spike/<name>-<时间戳>.png`。 */
    fun writePng(context: Context, name: String, bitmap: Bitmap): String? = runCatching {
        val file = "$name-${fileStamp()}.png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("PNG 压缩失败")
        } ?: error("输出流打开失败")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        "/storage/emulated/0/Download/$SUB_DIR/$file"
    }.onFailure { HyperCarRepo.log("SPIKE: 写截图失败 ${it.message}") }.getOrNull()

    /** 延迟分位数：`n=30 min=… p50=… p90=… max=…`（单位由调用方决定，通常是 µs/ms）。 */
    fun stats(values: LongArray): String {
        if (values.isEmpty()) return "n=0"
        val sorted = values.sortedArray()
        fun p(q: Double): Long =
            sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
        return "n=${sorted.size} min=${sorted.first()} p50=${p(0.5)} p90=${p(0.9)} max=${sorted.last()}"
    }

    private fun timeStamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun dateStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
