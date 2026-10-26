package io.github.hypercar.spike

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.DeviceInfo
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.shizuku.ShizukuTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * P0 三个 spike 的编排（SPIKE-P0）。
 *
 * 约定：每个入口自己 reset 报告、把原始证据写进 `Download/HyperCar/spike/`，
 * 返回一句人话结论；UI 只负责显示。任何一步失败都必须记录**原始异常文本**，
 * 因为 P0 的价值就在「失败时到底是哪一层拦的」。
 */
object SpikeRunner {

    private val ioDispatcher = Dispatchers.IO
    private var caster: SpikeCaster? = null

    // ---------------------------------------------------------------- S0 自检

    /**
     * 最小自检：只验证「Shizuku UserService 能在 shell 身份下建出带焦点 flag 的副屏」。
     * 不依赖主屏、不依赖 AirPlay——锁屏/后台也能跑（画面可能不合成，但建屏结果可验证）。
     */
    suspend fun shellTest(context: Context): String = withContext(ioDispatcher) {
        SpikeReport.reset("S0 shell 建屏自检")
        spikeEnv(context)
        SpikeReport.add("S0", "bind: ${SpikeShell.connect(context)}")
        val reader = SpikeDisplay.newReader()
        val screen = runCatching { SpikeDisplay.createShell(reader) }.getOrElse { t ->
            SpikeReport.add("S0", "失败: ${t.javaClass.simpleName}: ${t.message}")
            val path = SpikeReport.write(context, "s0-failed")
            return@withContext "shell 建屏失败：${t.message}（报告 $path）"
        }
        SpikeSession.screen = screen
        SpikeReport.add(
            "S0",
            "OK id=${screen.id} flags=0x${screen.flags.toString(16)} shellOwned=${screen.shellOwned} " +
                "status=${SpikeShell.status()}",
        )
        SpikeDisplay.dumpState("S0")
        delay(1_000)
        val bmp = SpikeDisplay.snapshot(screen)
        if (bmp != null) {
            SpikeReport.add("S0", "首帧 非黑=${"%.3f".format(SpikeDisplay.nonBlackRatio(bmp))}")
            SpikeReport.add("S0", "PNG=${SpikeReport.writePng(context, "s0", bmp)}")
        } else {
            SpikeReport.add("S0", "首帧=空（锁屏/刚建屏时正常）")
        }
        val path = SpikeReport.write(context, "s0")
        "S0 完成：id=${screen.id} flags=0x${screen.flags.toString(16)} 报告 $path"
    }

    // ---------------------------------------------------------------- S1

    suspend fun s1(context: Context): String = withContext(ioDispatcher) {
        SpikeReport.reset("S1 副屏跑高德")
        spikeEnv(context)
        SpikeSession.release()

        // Android 14+：非 OWN_CONTENT_ONLY 的虚拟屏被系统当成「屏幕共享」，
        // 普通 App 会吃 SecurityException（需 CAPTURE_VIDEO_OUTPUT / MediaProjection token）。
        // 先把组合矩阵跑一遍留证据（只建/释放，不托管 App）。
        val combos = linkedMapOf(
            "PUBLIC|OWN_CONTENT_ONLY" to
                (SpikeDisplay.FLAG_PUBLIC or SpikeDisplay.FLAG_OWN_CONTENT_ONLY),
            "PUBLIC" to SpikeDisplay.FLAG_PUBLIC,
            "PUBLIC|AUTO_MIRROR" to
                (SpikeDisplay.FLAG_PUBLIC or SpikeDisplay.FLAG_AUTO_MIRROR),
            "PUBLIC|PRESENTATION" to
                (SpikeDisplay.FLAG_PUBLIC or SpikeDisplay.FLAG_PRESENTATION),
        )
        for ((label, flags) in combos) {
            try {
                val s = SpikeDisplay.create(context, flags = flags)
                SpikeReport.add("S1", "app 建屏 $label → id=${s.id} flags=0x${flags.toString(16)}")
                s.release()
            } catch (t: Throwable) {
                SpikeReport.add("S1", "app 建屏 $label 失败: ${t.javaClass.name}: ${t.message}")
            }
        }

        // D2 必须由 shell 身份建：带 SUPPORTS_TOUCH|OWN_FOCUS|TRUSTED 才有焦点/触摸。
        SpikeReport.add("S1", "绑定 Shizuku user service: ${SpikeShell.connect(context)}")
        val reader = SpikeDisplay.newReader()
        val screen = runCatching { SpikeDisplay.createShell(reader) }.getOrElse { t ->
            SpikeReport.add("S1", "shell 建屏失败（${t.message}）——回退 app 建屏（无焦点）")
            SpikeDisplay.create(context)
        }
        SpikeSession.screen = screen
        SpikeReport.add(
            "S1",
            "D2 id=${screen.id} flags=0x${screen.flags.toString(16)} shellOwned=${screen.shellOwned} " +
                "status=${SpikeShell.status()}",
        )
        SpikeReport.add("S1", "显示列表=${SpikeDisplay.displayList(context)}")
        SpikeReport.add("S1", "包信息 高德=${SpikeDisplay.packageInfo(context, "com.autonavi.minimap")}")
        SpikeReport.add("S1", "包信息 百度=${SpikeDisplay.packageInfo(context, "com.baidu.BaiduMap")}")

        // 1) 先证明「副屏能托管我们自己的 Activity」——probe 必须 exported 才能由 shell 拉起
        val probeComponent = "io.github.hypercar/" + SpikeProbeActivity::class.java.name
        SpikeReport.add("S1", "probe: ${SpikeDisplay.amStartComponentOnDisplay(screen.id, probeComponent, clearTask = true)}")
        delay(2_000)
        val probeShot = grab(screen)
        if (probeShot != null) {
            SpikeReport.add("S1", "probe 截图 非黑占比=${"%.3f".format(SpikeDisplay.nonBlackRatio(probeShot))}")
            SpikeReport.add("S1", "probe PNG=${SpikeReport.writePng(context, "s1-probe", probeShot)}")
        } else {
            SpikeReport.add("S1", "probe 截图=空（ImageReader 没拿到帧）")
        }
        SpikeDisplay.dumpState("S1-probe")

        // 2) 高德：先开「强制横屏」compat（cardeck 方案），再 force-stop 后由 shell 拉到 D2
        val target = "com.autonavi.minimap"
        SpikeReport.add("S1", "高德横屏(am compat): ${MapCompat.enable(target)}")
        delay(500)
        SpikeReport.add("S1", "force-stop: ${SpikeDisplay.forceStop(target)}")
        delay(400)
        SpikeReport.add("S1", "高德上屏: ${SpikeDisplay.amStartOnDisplay(screen.id, target)}")
        delay(4_000)
        val shot = grab(screen)
        val ratio = shot?.let { SpikeDisplay.nonBlackRatio(it) } ?: -1.0
        SpikeReport.add("S1", "高德截图 非黑占比=$ratio")
        shot?.let { SpikeReport.add("S1", "高德 PNG=${SpikeReport.writePng(context, "s1-amap-shell", it)}") }
        SpikeDisplay.dumpState("S1-高德")

        // 3) 连续 3 张（间隔 2s）：有变化 = 画面在动（静态界面差异为 0 也正常）
        var prev: Bitmap? = null
        var diffCount = 0
        for (i in 1..3) {
            delay(2_000)
            val bmp = grab(screen) ?: continue
            val r = SpikeDisplay.nonBlackRatio(bmp)
            val diff = prev?.let { pixelDiffRatio(it, bmp) } ?: -1.0
            SpikeReport.add("S1", "序列 $i: 非黑占比=${"%.3f".format(r)} 与上一张差异=${"%.3f".format(diff)}")
            SpikeReport.add("S1", "序列 $i PNG=${SpikeReport.writePng(context, "s1-seq$i", bmp)}")
            if (diff > 0.002) diffCount++
            prev = bmp
        }
        SpikeReport.add("S1", "连续 3 张中变化张数=$diffCount")

        // 复原 am compat 覆盖：它是持久系统设置，不关掉会影响用户手机上高德的正常竖屏
        SpikeReport.add("S1", "复原高德横屏覆盖: ${MapCompat.disable(target)}")

        // 4) 百度对照（装了才跑）
        if (runCatching { context.packageManager.getPackageInfo("com.baidu.BaiduMap", 0) }.isSuccess) {
            SpikeReport.add("S1", "百度对照：force-stop ${SpikeDisplay.forceStop("com.baidu.BaiduMap")}")
            delay(400)
            SpikeReport.add("S1", "百度上屏: ${SpikeDisplay.amStartOnDisplay(screen.id, "com.baidu.BaiduMap")}")
            delay(4_000)
            val b = grab(screen)
            if (b != null) {
                SpikeReport.add("S1", "百度截图 非黑占比=${"%.3f".format(SpikeDisplay.nonBlackRatio(b))}")
                SpikeReport.add("S1", "百度 PNG=${SpikeReport.writePng(context, "s1-baidu", b)}")
            } else {
                SpikeReport.add("S1", "百度截图=空")
            }
        }

        val path = SpikeReport.write(context, "s1")
        "S1 完成：D2 id=${screen.id}（flags=0x${screen.flags.toString(16)}），报告 $path"
    }

    // ---------------------------------------------------------------- S2

    suspend fun s2(context: Context): String = withContext(ioDispatcher) {
        SpikeReport.reset("S2 Shizuku 注入到 D2")
        spikeEnv(context)
        SpikeReport.add("S2", "input usage: ${SpikeInput.inputUsage()}")
        SpikeReport.add("S2", "bind shell service: ${SpikeShell.connect(context)}")
        SpikeReport.add("S2", "input prepare: ${SpikeInput.prepare()}")

        SpikeReport.add("S2", "bind shell service: ${SpikeShell.connect(context)}")
        val screen = SpikeSession.screen ?: runCatching {
            SpikeDisplay.createShell(SpikeDisplay.newReader())
        }.getOrElse {
            SpikeReport.add("S2", "shell 建屏失败（${it.message}），回退 app 建屏（无焦点）")
            SpikeDisplay.create(context)
        }.also { SpikeSession.screen = it }
        SpikeReport.add("S2", "D2 id=${screen.id} flags=0x${screen.flags.toString(16)} " +
            "（${if (SpikeInput.isBinderReady()) "binder 可用" else "binder 不可用"}）")

        // 受控目标：自己的全屏 probe（exported，由 shell 拉起）
        val probeComponent = "io.github.hypercar/" + SpikeProbeActivity::class.java.name
        SpikeReport.add("S2", "probe: ${SpikeDisplay.amStartComponentOnDisplay(screen.id, probeComponent, clearTask = true)}")
        delay(2_000)
        grab(screen)?.let { SpikeReport.add("S2", "probe PNG=${SpikeReport.writePng(context, "s2-probe", it)}") }
        SpikeDisplay.dumpState("S2-probe")
        SpikeProbe.clear()

        // 2B：binder 单击
        val cx = screen.width / 2f
        val cy = screen.height / 2f
        val t0 = SystemClock.elapsedRealtimeNanos()
        val tap = SpikeInput.tap(screen.id, cx, cy)
        delay(150)
        val ev = SpikeProbe.firstSince(t0)
        SpikeReport.add(
            "S2",
            "tap ok=${tap.ok} 耗时=${tap.elapsedUs}µs probeCount=${SpikeProbe.count()} " +
                "first=${ev?.let { "a=${it.action} (${it.x.toInt()},${it.y.toInt()}) d=${it.displayId}" }} " +
                "注入→收到(DOWN)=${ev?.let { (it.atNs - t0) / 1000 }}µs detail=${tap.detail}",
        )

        // 30 次 tap：成功率 + 延迟分布（按第一条 DOWN 事件算，不含按压保持时间）
        val tapLats = ArrayList<Long>(32)
        var tapOk = 0
        repeat(30) { i ->
            val x = 200f + (i % 10) * 140f
            val y = 200f + (i / 10) * 150f
            val s = SystemClock.elapsedRealtimeNanos()
            val r = SpikeInput.tap(screen.id, x, y)
            delay(120)
            val first = SpikeProbe.firstSince(s)
            if (r.ok && first != null) {
                tapOk++
                tapLats.add((first.atNs - s) / 1000)
            }
        }
        SpikeReport.add(
            "S2",
            "30 次 tap：命令成功=$tapOk/30 注入→收到(DOWN)=" +
                SpikeReport.stats(tapLats.toLongArray()) + "µs",
        )

        // 拖动
        val beforeDrag = SpikeProbe.count()
        val drag = SpikeInput.swipe(screen.id, 300f, 500f, 1_600f, 500f, steps = 12, stepMs = 8)
        delay(200)
        SpikeReport.add(
            "S2",
            "binder swipe ok=${drag.ok} 总耗时=${drag.elapsedUs}µs 新增事件=${SpikeProbe.count() - beforeDrag} " +
                "MOVE=${SpikeProbe.moveCount()}",
        )
        grab(screen)?.let { SpikeReport.add("S2", "拖动后 PNG=${SpikeReport.writePng(context, "s2-after-swipe", it)}") }

        // 双指
        val beforePinch = SpikeProbe.count()
        val pinch = SpikeInput.pinch(screen.id, cx, cy, from = 80f, to = 240f, steps = 10)
        delay(200)
        SpikeReport.add("S2", "binder pinch ok=${pinch.ok} 事件=${SpikeProbe.count() - beforePinch} 耗时=${pinch.elapsedUs}µs")

        // 2A：shell baseline
        if (shizukuReady()) {
            SpikeProbe.clear()
            val shellLats = ArrayList<Long>(12)
            var shellOk = 0
            repeat(10) { i ->
                val x = 300f + (i % 5) * 250f
                val y = 300f
                val s = SystemClock.elapsedRealtimeNanos()
                val r = SpikeInput.shellTap(screen.id, x, y)
                delay(150)
                if (r.ok) shellOk++
                SpikeProbe.last()?.let { shellLats.add((it.atNs - s) / 1000) }
            }
            SpikeReport.add(
                "S2",
                "10 次 shell tap：命令成功=$shellOk/10 端到端=" +
                    SpikeReport.stats(shellLats.toLongArray()) + "µs（含进程启动）",
            )
            val sw = SpikeInput.shellSwipe(screen.id, 300, 500, 1600, 500, 260)
            delay(200)
            SpikeReport.add("S2", "shell swipe ok=${sw.ok} 耗时=${sw.elapsedUs}µs")
        } else {
            SpikeReport.add("S2", "Shizuku 未就绪——2A 基线跳过（这也会让 shell 输入路径不可用）")
        }

        // 真实目标：高德（截图前后差异作为“收到了”的证据）
        val target = "com.autonavi.minimap"
        SpikeReport.add("S2", "force-stop: ${SpikeDisplay.forceStop(target)}")
        delay(400)
        SpikeReport.add("S2", "高德上屏: ${SpikeDisplay.amStartOnDisplay(screen.id, target)}")
        delay(4_000)
        val beforeApp = grab(screen)
        if (beforeApp != null) {
            SpikeReport.add("S2", "高德点击前 PNG=${SpikeReport.writePng(context, "s2-amap-before", beforeApp)}")
            SpikeProbe.clear()
            val beforeCount = SpikeProbe.count()
            val r1 = SpikeInput.tap(screen.id, 960f, 110f) // 顶部搜索栏附近
            delay(1_500)
            val afterApp = grab(screen)
            val diff = afterApp?.let { pixelDiffRatio(beforeApp, it) } ?: -1.0
            SpikeReport.add(
                "S2",
                "高德 tap(${960},${110}) ok=${r1.ok} 截图差异=${"%.4f".format(diff)} " +
                    "probeCount=${SpikeProbe.count()}（probe 已不在前台，>0 说明事件被我们进程收到）",
            )
            afterApp?.let { SpikeReport.add("S2", "高德点击后 PNG=${SpikeReport.writePng(context, "s2-amap-after", it)}") }
        } else {
            SpikeReport.add("S2", "高德截图=空（可能没上屏，先看 S1）")
        }

        val path = SpikeReport.write(context, "s2")
        "S2 完成：报告 $path。重点看『binder tap 成功率/延迟』与『高德截图差异』"
    }

    // ---------------------------------------------------------------- S3

    suspend fun s3(context: Context, seconds: Int = 120): String = withContext(ioDispatcher) {
        SpikeReport.reset("S3 编码 → AirPlay")
        spikeEnv(context)
        val rustRunning = RustCoreBridge.nativeIsRunning()
        SpikeReport.add("S3", "link=${HyperCarRepo.snapshots.value.link} " +
            "rustRunning=$rustRunning " +
            "设置分辨率=${SettingsStore.videoWidth}x${SettingsStore.videoHeight}@${SettingsStore.videoFps}")
        if (!rustRunning) {
            SpikeReport.add("S3", "无 AirPlay 会话——只验证编码链路（帧不会到车机；先连 DiPlay/车机再跑全链路）")
        }

        // 真 HID（DiPlay 若转发鼠标/触摸）也要能看到：装一个临时监听器
        var touchCount = 0
        val touchListener = object : RustCoreBridge.TouchListener {
            override fun onTouch(action: Int, x: Int, y: Int) {
                touchCount++
                if (touchCount <= 20 || touchCount % 50 == 0) {
                    SpikeReport.add("TOUCH", "HID action=$action ($x,$y) 第 $touchCount 条")
                }
            }
        }
        RustCoreBridge.setTouchListener(touchListener)

        val caster = SpikeCaster(context)
        this@SpikeRunner.caster = caster
        SpikeReport.add("S3", caster.startEncoder())

        // 画面源优先用 shell 建屏 + 我们的全屏 stage Activity：
        // App 自建屏的 Presentation 在锁屏/息屏时不一定被合成（实测 10s 只出 2 帧）。
        var castDisplayId = -1
        val shellState = SpikeShell.connect(context)
        SpikeReport.add("S3", "shell=$shellState")
        if (shellState == "connected" || shellState == "已连接") {
            val surface = caster.encoderSurface
            if (surface != null) {
                val r = SpikeShell.createDisplay(
                    surface,
                    SpikeDisplay.WIDTH,
                    SpikeDisplay.HEIGHT,
                    SpikeDisplay.DPI,
                    0,
                    "hypercar-cast",
                )
                castDisplayId = Regex("id=(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                SpikeReport.add("S3", "shell 建屏: $r")
                if (castDisplayId > 0) {
                    val comp = "io.github.hypercar/" + SpikeProbeActivity::class.java.name
                    SpikeReport.add("S3", "stage: ${SpikeDisplay.amStartComponentOnDisplay(castDisplayId, comp, clearTask = true)}")
                    // 第二次 start 把 stage 提到该 display 的前台：第一次启动时窗口常常还没 layout
                    // （view=0x0，编码器一帧都拿不到）；复刻 S2 里“第二次 am start 才变成 1920x720”的经验。
                    delay(1_200)
                    SpikeReport.add("S3", "stage 2: ${SpikeDisplay.amStartComponentOnDisplay(castDisplayId, comp)}")
                    delay(800)
                }
            }
        }
        if (castDisplayId <= 0) {
            SpikeReport.add("S3", "shell 建屏不可用，回退 app 建屏: ${caster.attachOwnDisplay()}")
        }

        val endAt = SystemClock.elapsedRealtime() + seconds * 1000L
        var lastReport = 0L
        while (SystemClock.elapsedRealtime() < endAt && caster.isRunning) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= 5_000) {
                val v = HyperCarRepo.snapshots.value.video
                SpikeReport.add("S3", "Rust 统计 fps=${v.fps} frames=${v.frames} kbps=${v.kbps} res=${v.resolution}")
                lastReport = now
            }
        }
        // 先拆显示（它引用着编码器 Surface），再停编码器
        if (castDisplayId > 0) SpikeShell.releaseDisplay()
        caster.stop()
        this@SpikeRunner.caster = null
        RustCoreBridge.setTouchListener(null)
        SpikeReport.add("S3", "HID 触摸回调共 $touchCount 条（0 = DiPlay 没发 HID，或它不支持鼠标转发）")

        val path = SpikeReport.write(context, "s3")
        "S3 结束（${seconds}s）：报告 $path。重点看『ENC fps / 延迟 / 帧间隔』与 Rust 侧 VIDEO 日志"
    }

    // ---------------------------------------------------------------- 辅助

    /** Rust 触摸回调自检：不依赖车机 HID，直接往引擎触摸通道塞三条。 */
    suspend fun touchSelfTest(): String = withContext(ioDispatcher) {
        SpikeReport.add("TOUCH", "自检开始 rustRunning=${RustCoreBridge.nativeIsRunning()}")
        if (!RustCoreBridge.nativeIsRunning()) return@withContext "触摸自检跳过：Rust 引擎没在跑"
        val got = ArrayList<String>()
        val listener = object : RustCoreBridge.TouchListener {
            override fun onTouch(action: Int, x: Int, y: Int) {
                synchronized(got) { got.add("a=$action($x,$y)") }
            }
        }
        RustCoreBridge.setTouchListener(listener)
        delay(200)
        RustCoreBridge.nativeDebugTouch(0, 100, 200)
        delay(120)
        RustCoreBridge.nativeDebugTouch(2, 300, 400)
        delay(120)
        RustCoreBridge.nativeDebugTouch(1, 300, 400)
        delay(400)
        RustCoreBridge.setTouchListener(null)
        SpikeReport.add("TOUCH", "自检收到 ${got.size} 条: $got")
        "触摸回调自检：收到 ${got.size}/3 条 ${got.joinToString(" ")}"
    }

    fun stopAll() {
        runCatching { caster?.stop() }
        caster = null
        SpikeSession.release()
        RustCoreBridge.setTouchListener(null)
        SpikeReport.add("SPIKE", "已停止全部 spike 资源")
    }

    fun snapshotNow(context: Context): String {
        val screen = SpikeSession.screen ?: return "没有活动的 D2 显示"
        val bmp = SpikeDisplay.snapshot(screen) ?: return "D2 没拿到帧"
        val ratio = SpikeDisplay.nonBlackRatio(bmp)
        val path = SpikeReport.writePng(context, "s1-now", bmp)
        SpikeReport.add("S1", "手动快照 非黑占比=$ratio path=$path")
        return "快照 非黑占比=${"%.3f".format(ratio)} → $path"
    }

    private fun spikeEnv(context: Context) {
        SpikeReport.add(
            "ENV",
            "${DeviceInfo.fullSummary(context)}｜fingerprint=${Build.FINGERPRINT}｜SDK_INT=${Build.VERSION.SDK_INT}",
        )
        SpikeReport.add("ENV", "显示列表=${SpikeDisplay.displayList(context)}")
        SpikeReport.add("ENV", "Shizuku=${ShizukuTools.isReady}")
    }

    private fun shizukuReady(): Boolean = ShizukuTools.isReady

    private suspend fun grab(screen: SpikeDisplay.Screen, timeoutMs: Long = 3_000): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            SpikeDisplay.snapshot(screen)?.let { return it }
            delay(80)
        }
        return null
    }

    private fun pixelDiffRatio(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var total = 0
        var diff = 0
        val step = 8
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                val dr = kotlin.math.abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF))
                val dg = kotlin.math.abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF))
                val db = kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
                total++
                if (dr + dg + db > 30) diff++
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else diff.toDouble() / total
    }
}
