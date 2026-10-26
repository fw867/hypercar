package io.github.hypercar.spike

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.view.Display
import io.github.hypercar.shizuku.ShizukuTools

/**
 * S1 的建屏 / 截图 / 启动目标 App / dumpsys 取证（SPIKE-P0）。
 *
 * 两种建屏方式：
 *  - **app 进程**：`PUBLIC|OWN_CONTENT_ONLY` 能建（普通 App 唯一合法组合），但
 *    Activity 的 `launchDisplayId` 会被拒，且窗口 `NOT_FOCUSABLE`（触摸进不去）；
 *  - **Shizuku shell 进程**（[createShell]）：加 `SUPPORTS_TOUCH|OWN_FOCUS|TRUSTED`，
 *    可托管别的 App 并有焦点——D2 方案必须走这条。
 *
 * 截图用 app 侧创建的 [ImageReader]：它的 Surface 交给 shell 建屏，画面直接回读，
 * 不依赖 AirPlay/编码器。
 */
object SpikeDisplay {

    const val FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    const val FLAG_OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    const val FLAG_AUTO_MIRROR = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
    const val FLAG_PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

    const val WIDTH = 1920
    const val HEIGHT = 720
    const val DPI = 240

    class Screen(
        val virtualDisplay: VirtualDisplay?,
        val reader: ImageReader?,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int,
        val shellDisplayId: Int = -1,
        val shellOwned: Boolean = false,
    ) {
        val id: Int
            get() = if (shellOwned) shellDisplayId else requireNotNull(virtualDisplay).display.displayId

        val display: Display?
            get() = virtualDisplay?.display

        /** 最近一帧（ImageReader 只在画面变化时出新帧，静态界面要能重复截图）。 */
        var lastBitmap: Bitmap? = null

        fun release() {
            if (shellOwned) SpikeShell.releaseDisplay()
            runCatching { virtualDisplay?.release() }
            runCatching { reader?.close() }
            lastBitmap = null
        }
    }

    /** app 侧建一个读帧用的 ImageReader（shell 建屏时把它的 Surface 交给 UserService）。 */
    fun newReader(width: Int = WIDTH, height: Int = HEIGHT): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    /** app 进程建屏（只用于对照 / 不需要其它 App 的场景）。 */
    fun create(
        context: Context,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        dpi: Int = DPI,
        flags: Int = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY,
        name: String = "hypercar-spike",
    ): Screen {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: error("DisplayManager 不可用")
        val vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, flags)
        return Screen(vd, reader, width, height, dpi, flags)
    }

    /**
     * shell 进程建屏（D2 唯一可行路径）：App 侧建 ImageReader（读帧用），
     * 把 Surface 交给 UserService，由它带隐藏 flag 建公共屏。
     */
    fun createShell(
        reader: ImageReader,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        dpi: Int = DPI,
        name: String = "hypercar-d2",
    ): Screen {
        val result = SpikeShell.createDisplay(reader.surface, width, height, dpi, 0, name)
        SpikeReport.add("SHELL", "createDisplay: $result")
        val id = Regex("id=(\\d+)").find(result)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        check(id > 0) { "shell 建屏失败: $result" }
        val flags = Regex("flags=0x([0-9a-fA-F]+)").find(result)
            ?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
        return Screen(
            virtualDisplay = null,
            reader = reader,
            width = width,
            height = height,
            dpi = dpi,
            flags = flags,
            shellDisplayId = id,
            shellOwned = true,
        )
    }

    /** 取一帧（没有新帧时返回缓存；调用方在 IO 线程）。 */
    fun snapshot(screen: Screen): Bitmap? {
        val reader = screen.reader ?: return screen.lastBitmap
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return screen.lastBitmap
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screen.width
            val raw = Bitmap.createBitmap(
                screen.width + rowPadding / pixelStride,
                screen.height,
                Bitmap.Config.ARGB_8888,
            )
            raw.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPadding == 0) raw
            else Bitmap.createBitmap(raw, 0, 0, screen.width, screen.height)
            screen.lastBitmap = bmp
            bmp
        } catch (t: Throwable) {
            SpikeReport.add("S1", "截图失败: ${t.javaClass.simpleName}: ${t.message}")
            screen.lastBitmap
        } finally {
            image.close()
        }
    }

    /** 非黑像素占比；全黑 / 纯色背景判失败，正常界面一般 > 0.05。 */
    fun nonBlackRatio(bitmap: Bitmap): Double {
        var total = 0
        var lit = 0
        val step = 16
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                total++
                if (r + g + b > 24) lit++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else lit.toDouble() / total
    }

    /** 应用进程直接启动（多数情况下会被 "not allowed on display" 拒，仅作对照）。 */
    fun launchAppOnDisplay(context: Context, displayId: Int, pkg: String): String {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "launchIntent=null（包未安装、没有 LAUNCHER，或未在 <queries> 里声明）"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            context.startActivity(launch, options)
            "startActivity 已提交 pkg=$pkg component=${launch.component} display=$displayId"
        } catch (t: Throwable) {
            "startActivity 失败 pkg=$pkg: ${t.javaClass.name}: ${t.message}"
        }
    }

    fun launchActivityOnDisplay(context: Context, displayId: Int, clazz: Class<out Activity>): String =
        try {
            val intent = Intent(context, clazz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            context.startActivity(intent, options)
            "startActivity(${clazz.simpleName}) display=$displayId"
        } catch (t: Throwable) {
            "startActivity(${clazz.simpleName}) 失败: ${t.javaClass.name}: ${t.message}"
        }

    /** 已安装包的版本与入口（报告用；需要 <queries> 才能看到）。 */
    fun packageInfo(context: Context, pkg: String): String = runCatching {
        val pi = context.packageManager.getPackageInfo(pkg, 0)
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.component
        "$pkg ver=${pi.versionName}（${pi.longVersionCode}）launcher=$launch"
    }.getOrElse { "$pkg 查询失败（${it.javaClass.simpleName}）" }

    suspend fun shizukuExec(command: String, timeoutMs: Long = 8_000): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪"
        val r = runCatching { ShizukuTools.exec(command, timeoutMs) }.getOrElse {
            return "exec 异常: ${it.message}"
        }
        return "exit=${r.code} out=${r.out.take(1500)} err=${r.err.take(300)}"
    }

    suspend fun forceStop(pkg: String): String = shizukuExec("am force-stop $pkg")

    /** 用 shell 启动包入口到指定 display（D2 上起 App 的唯一可靠路径）。 */
    suspend fun amStartOnDisplay(displayId: Int, pkg: String): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪——跳过 shell 启动"
        val resolved = runCatching { ShizukuTools.exec("cmd package resolve-activity --brief $pkg", 6_000) }
            .getOrElse { return "resolve 异常: ${it.message}" }
        val component = resolved.out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && !it.startsWith("priority=") }
            ?: return "resolve 无组件（exit=${resolved.code} out=${resolved.out.take(300)}）"
        return amStartComponentOnDisplay(displayId, component)
    }

    suspend fun amStartComponentOnDisplay(
        displayId: Int,
        component: String,
        clearTask: Boolean = false,
    ): String {
        if (!ShizukuTools.isReady) return "Shizuku 未就绪——跳过 shell 启动"
        val flag = if (clearTask) "--activity-clear-task " else ""
        var last = ""
        // HyperOS 上第一条 shell 命令偶尔被 Shizuku 以 "s0 was cancelled" 取消（服务刚唤醒），
        // 重试一次基本必成；不要因为一次抖动就判 D2 启动失败。
        repeat(2) { attempt ->
            val r = runCatching {
                ShizukuTools.exec("am start $flag-n $component --display $displayId", 12_000)
            }
            if (r.isSuccess) {
                val out = r.getOrThrow()
                last = "am start $flag-n $component --display $displayId → exit=${out.code} " +
                    "out=${out.out.take(700)} err=${out.err.take(300)}"
                if (out.code == 0) return last
            } else {
                last = "am start 异常（第 ${attempt + 1} 次）: ${r.exceptionOrNull()?.message}"
            }
            kotlinx.coroutines.delay(400)
        }
        return last
    }

    suspend fun dumpState(label: String) {
        if (!ShizukuTools.isReady) {
            SpikeReport.add("DUMP", "[$label] Shizuku 未就绪，跳过 dumpsys")
            return
        }
        val commands = listOf(
            "dumpsys display | grep -E 'mDisplayId=|DisplayDeviceInfo' | head -40",
            "dumpsys window displays | grep -E 'Display: mDisplayId|mCurrentFocus|mFocusedApp|mTouchableInsets' | head -40",
            "dumpsys input | grep -E 'FocusedWindows|displayId=|Display: [0-9]+|Display not interactive' | head -50",
            "dumpsys activity activities | grep -E 'displayId|mResumedActivity' | head -25",
        )
        for (cmd in commands) {
            SpikeReport.add("DUMP", "[$label] $cmd")
            SpikeReport.add("DUMP", shizukuExec(cmd, 12_000))
        }
    }

    /** 当前显示列表（判断自建屏 id / 状态最直接的一行）。 */
    fun displayList(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return "DisplayManager 不可用"
        return dm.displays.joinToString(" | ") { d ->
            val mode = d.mode
            "id=${d.displayId} name='${d.name}' ${mode?.physicalWidth}x${mode?.physicalHeight} " +
                "flags=0x${d.flags.toString(16)} state=${d.state}"
        }
    }

    fun displayName(context: Context, displayId: Int): String =
        runCatching {
            val d = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            "${d?.name} ${d?.mode?.physicalWidth}x${d?.mode?.physicalHeight} state=${d?.state}"
        }.getOrElse { "unknown" }
}

/** S1/S2 共用的“当前 D2”，让 S2 直接复用 S1 建好的屏。 */
object SpikeSession {
    var screen: SpikeDisplay.Screen? = null
    var targetPackage: String? = null

    fun release() {
        runCatching { screen?.release() }
        screen = null
        targetPackage = null
    }
}
