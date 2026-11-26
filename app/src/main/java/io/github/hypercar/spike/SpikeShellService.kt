package io.github.hypercar.spike

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * SPIKE-P0：跑在 **Shizuku UserService** 进程里的最小 shell 端。
 *
 * 为什么需要它：Android 14+ 起，普通 App 建「非 OWN_CONTENT_ONLY」虚拟屏会被当成屏幕共享，
 * 直接 `SecurityException: Requires CAPTURE_VIDEO_OUTPUT`；而要让副屏能接受触摸/拥有焦点，
 * 还需要 `SUPPORTS_TOUCH | OWN_FOCUS | TRUSTED` 这些隐藏 flag。shell UID 具备
 * `CAPTURE_VIDEO_OUTPUT` / `ADD_MIRROR_DISPLAY`，且非 App UID 不受 hidden-API 限制——
 * 所以把「建屏」这一步搬到 shell 进程（与 headless-android / ShadowAuto 同款做法）。
 *
 * 进程模型：Shizuku 用 app_process 以 shell 身份加载本类并实例化；它不是 Android Service，
 * 通过客户端 Binder 的死亡通知释放虚拟屏，即使 App 被更新或强制退出。
 */
class SpikeShellService : ISpikeShell.Stub {

    private val playbackAudio = ShellPlaybackAudio()
    private val splitPlaybackAudio = SplitPlaybackAudio()
    private val callIdentity: ShellCallIdentity by lazy {
        ShellCallIdentity(onRevoked = { id ->
            if (callAudio.isInitialized()) callAudio.value.revoke(id)
        })
    }
    private val callStateMonitor by lazy { ShellCallStateMonitor(callIdentity) }
    private val callAudio: Lazy<ShellCallAudio> = lazy { ShellCallAudio(callIdentity) }

    override fun activeCallAudioIdentity(): String? {
        if (android.os.Binder.getCallingUid() != appContext?.applicationInfo?.uid) return null
        val identity = android.os.Binder.clearCallingIdentity()
        return try { callStateMonitor.run { callIdentity.current() } }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    override fun callAudioStatus(): String? {
        if (android.os.Binder.getCallingUid() != appContext?.applicationInfo?.uid) return null
        val endpoint = if (callAudio.isInitialized()) callAudio.value.diagnosticStatus() else "idle"
        return "identity=${callIdentity.diagnosticStatus()} endpoint=$endpoint"
    }

    override fun openCallAudio(owner: IBinder, token: Long, callId: String, rate: Int): android.os.ParcelFileDescriptor? {
        val context = appContext ?: return null
        if (android.os.Binder.getCallingUid() != context.applicationInfo.uid) return null
        val identity = android.os.Binder.clearCallingIdentity()
        return try { callAudio.value.open(context, owner, token, callId, rate) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    override fun closeCallAudio(token: Long) {
        if (android.os.Binder.getCallingUid() != appContext?.applicationInfo?.uid) return
        val identity = android.os.Binder.clearCallingIdentity()
        try { if (callAudio.isInitialized()) callAudio.value.close(token) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }
    @Volatile private var splitCaptureRequested = false
    private val bluetoothDiscovery = ShellBluetoothDiscovery()
    private val wifiSelection = ShellWifiSelection()
    override fun startWifiSelection(owner: IBinder, token: String, ssid: String, bssid: String, uid: Int, protocolFrequency: Int): String {
        if (android.os.Binder.getCallingUid() != uid || appContext?.applicationInfo?.uid != uid)
            return "ERR Wi-Fi request owner mismatch"
        val identity = android.os.Binder.clearCallingIdentity()
        return try { wifiSelection.start(owner, token, ssid, bssid, uid, protocolFrequency) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }
    override fun stopWifiSelection(token: String): String {
        val identity = android.os.Binder.clearCallingIdentity()
        return try { wifiSelection.stop(token) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }
    override fun startBluetoothDiscoverable(owner: IBinder, token: String, seconds: Int): String {
        val identity = android.os.Binder.clearCallingIdentity()
        return try { bluetoothDiscovery.start(appContext, owner, token, seconds) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    override fun stopBluetoothDiscoverable(token: String): String {
        val identity = android.os.Binder.clearCallingIdentity()
        return try { bluetoothDiscovery.stop(token) }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun startAudioCapture(owner: IBinder): android.os.ParcelFileDescriptor? {
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            splitPlaybackAudio.stop()
            splitCaptureRequested = false
            appContext?.let { playbackAudio.start(it, owner) }
        }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun startSplitAudioCapture(owner: IBinder, navigationUid: Int): Array<android.os.ParcelFileDescriptor>? {
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            playbackAudio.stop()
            splitPlaybackAudio.stop()
            splitCaptureRequested = true
            appContext?.let { splitPlaybackAudio.start(it, owner, navigationUid) }
        } finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    @Synchronized override fun stopAudioCapture() {
        val identity = android.os.Binder.clearCallingIdentity()
        try {
            splitPlaybackAudio.stop()
            playbackAudio.stop()
            splitCaptureRequested = false
        }
        finally { android.os.Binder.restoreCallingIdentity(identity) }
    }

    override fun audioCaptureStatus(): String = if (splitCaptureRequested) splitPlaybackAudio.status else playbackAudio.status

    constructor() : super() {
        bindContext(null)
    }

    constructor(context: Context) : super() {
        bindContext(context)
    }

    private fun bindContext(context: Context?) {
        appContext = context ?: runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentApplication").invoke(null)
            current as? Context
        }.getOrNull()
        Log.i(TAG, "service init context=${appContext != null}")
    }

    override fun createDisplay(
        owner: IBinder,
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int,
        name: String,
    ): String {
        val ctx = appContext ?: return "ERR 没有 Context"
        if (surface == null) return "ERR surface=null"
        // 关键：Android 17 的 DisplayManagerService 会校验 packageName 与调用 uid 的包列表。
        // UserService 的 uid 是 shell(2000)，Context 却是宿主 App 的（io.github.hypercar）→
        //   SecurityException: packageName must match the owner uid
        // 传 null 也会被拒（已实测）。这里把包名覆盖成 shell uid 自己登记的包名再试。
        synchronized(lock) {
            if (!displayLease.bind(owner) { callback ->
                    val death = IBinder.DeathRecipient { callback() }
                    runCatching {
                        owner.linkToDeath(death, 0)
                        val unlink: () -> Unit = { runCatching { owner.unlinkToDeath(death, 0) } }
                        unlink
                    }.getOrNull()
                }) return "ERR display owner disconnected"
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // 隐藏 flag（AOSP DisplayManager.java）：SUPPORTS_TOUCH=1<<6、
            // TRUSTED=1<<10、OWN_FOCUS=1<<14。OWN_FOCUS 要求 display 同时 TRUSTED。
            val touch = 1 shl 6
            val trusted = 1 shl 10
            val ownFocus = 1 shl 14
            // cardeck 2026-10-23 实测：虚拟屏必须进自己的 DisplayGroup，否则物理主屏
            // 断电/锁屏时整组跟着 OFF（车机侧表现为黑屏/冻结）。OWN_DISPLAY_GROUP 要求
            // TRUSTED（shell 已具备）；ALWAYS_UNLOCKED 另有权限，部分 ROM 不给，故三级降级。
            val ownGroup = 1 shl 11
            val alwaysUnlocked = 1 shl 12
            val candidates = intArrayOf(
                base or flags or touch or ownFocus or trusted or ownGroup or alwaysUnlocked,
                base or flags or touch or ownFocus or trusted or ownGroup,
                base or flags or touch or ownFocus or trusted,
                base or flags or touch or ownFocus,
                base or flags or touch,
                base or flags,
            )

            var lastError: Throwable? = null
            var lastLabel = ""
            for (pkg in listOf("com.android.shell", "android")) {
                val dm = runCatching {
                    val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                    ctor.isAccessible = true
                    ctor.newInstance(PackageOverrideContext(ctx, pkg)) as DisplayManager
                }.getOrElse {
                    SpikeReport.add("SHELL", "构造 DisplayManager(pkg=$pkg) 失败: ${it.message}")
                    null
                } ?: continue
                SpikeReport.add("SHELL", "尝试 package=$pkg（真实 uid=${android.os.Process.myUid()}）")
                for (candidate in candidates) {
                    try {
                        val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, candidate)
                        if (vd != null) {
                            display = vd
                            displays[vd.display.displayId] = vd
                            displaySizes[vd.display.displayId] = Triple(width, height, dpi)
                            flagsUsed = candidate
                            // Trusted secondary displays still default to FALLBACK_DISPLAY on some ROMs.
                            // Set this before launching any activity so search keyboards join the encoded display.
                            val ime = ShellDisplayIme.configure(vd.display.displayId, candidate and trusted != 0)
                            val msg = "OK id=${vd.display.displayId} flags=0x${candidate.toString(16)} package=$pkg ime=$ime"
                            Log.i(TAG, "createDisplay $msg")
                            SpikeReport.add("SHELL", "createDisplay $msg")
                            return msg
                        }
                        Log.w(TAG, "createDisplay(pkg=$pkg) 返回 null，flags=0x${candidate.toString(16)}")
                    } catch (t: Throwable) {
                        lastError = t
                        lastLabel = "pkg=$pkg flags=0x${candidate.toString(16)}"
                        Log.w(TAG, "createDisplay $lastLabel 失败: $t")
                    }
                }
            }
            val stack = lastError?.stackTraceToString()?.take(1200).orEmpty()
            return "ERR 全部失败（$lastLabel）: ${lastError?.javaClass?.name}: ${lastError?.message}\n$stack"
        }
    }

    override fun releaseDisplay(): String = synchronized(lock) {
        val had = displays.size
        releaseDisplaysLocked()
        if (physicalScreenOff) {
            runCatching { setPhysicalScreenPower(true) }
        }
        "OK released=$had"
    }

    override fun updateDisplaySurface(displayId: Int, surface: Surface?, width: Int, height: Int, dpi: Int): String = synchronized(lock) {
        val vd = displays[displayId] ?: return@synchronized "ERR display=$displayId missing"
        runCatching {
            val size = Triple(width, height, dpi)
            if (width > 0 && height > 0 && dpi > 0 && displaySizes[displayId] != size) {
                vd.resize(width, height, dpi)
                displaySizes[displayId] = size
            }
            vd.surface = surface
            "OK display=$displayId ${width}x$height surface=${surface != null}"
        }.getOrElse { "ERR ${it.message}" }
    }

    override fun releaseDisplayById(displayId: Int): String = synchronized(lock) {
        val vd = displays.remove(displayId)
        displaySizes.remove(displayId)
        runCatching { vd?.release() }
        if (display === vd) display = displays.values.lastOrNull()
        if (displays.isEmpty()) flagsUsed = 0
        "OK released=${vd != null} remaining=${displays.size}"
    }

    override fun prepareProjection(retainedMapName: String): String = synchronized(lock) {
        val retained = displays.entries.lastOrNull {
            retainedMapName.isNotEmpty() && it.value.display.isValid && it.value.display.name == retainedMapName
        }?.key
        displays.keys.toList().filter { it != retained }.forEach { releaseDisplayById(it) }
        if (retained == null) return@synchronized "OK id=-1"
        val vd = displays.getValue(retained)
        // No resize or activity relaunch: navigation and the manually selected camera survive.
        vd.surface = null
        val size = displaySizes.getValue(retained)
        "OK id=$retained width=${size.first} height=${size.second} dpi=${size.third}"
    }

    // ---- 物理主屏电源（真息屏；系统保持唤醒，虚拟屏继续渲染） ----

    /**
     * 对物理主屏做硬件级断电/点亮（scrcpy `-S/--turn-screen-off` 同款）：
     * 不按电源键（那会锁屏 + Doze），只切断面板电源。框架仍认为亮屏，
     * 独立显示组的虚拟屏继续 60fps，Keyguard 也不会画到它上面。
     */
    override fun setPhysicalScreenPower(on: Boolean): Boolean {
        val token = internalDisplayToken()
        if (token == null) {
            SpikeReport.add("SHELL", "setPhysicalScreenPower($on) 失败：拿不到物理屏 token")
            Log.w(TAG, "setPhysicalScreenPower($on): no physical display token")
            return false
        }
        val ok = setDisplayPowerMode(token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
        if (ok) physicalScreenOff = !on
        SpikeReport.add("SHELL", "setPhysicalScreenPower($on) ok=$ok")
        Log.i(TAG, "setPhysicalScreenPower($on) ok=$ok")
        return ok
    }

    private fun setDisplayPowerMode(token: IBinder, mode: Int): Boolean = try {
        Class.forName("android.view.SurfaceControl")
            .getMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
            .invoke(null, token, mode)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "SurfaceControl.setDisplayPowerMode($mode) 失败: $t")
        false
    }

    /**
     * 物理主屏 token。Android 14（SDK 34）删除了 SurfaceControl.getInternalDisplayToken /
     * getPhysicalDisplayToken，只剩 services.jar 里的 DisplayControl——必须用
     * ClassLoaderFactory + SYSTEMSERVERCLASSPATH 造类加载器并注册 libandroid_servers.so
     * （scrcpy / cardeck 同款）。
     */
    private fun internalDisplayToken(): IBinder? {
        if (Build.VERSION.SDK_INT >= 34) {
            displayControlClass?.let { cls ->
                try {
                    val ids = cls.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                    if (ids.isNotEmpty()) {
                        return cls
                            .getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
                            .invoke(null, ids[0]) as? IBinder
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "DisplayControl 取 token 失败: $t")
                }
            }
        }
        try {
            val sc = Class.forName("android.view.SurfaceControl")
            val token = runCatching { sc.getMethod("getInternalDisplayToken").invoke(null) }
                .getOrElse {
                    val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                    sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
                        .invoke(null, ids[0])
                }
            return token as? IBinder
        } catch (t: Throwable) {
            Log.w(TAG, "SurfaceControl 取 token 失败: $t")
        }
        return null
    }

    private val displayControlClass: Class<*>? by lazy {
        try {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = factory.getDeclaredMethod(
                "createClassLoader",
                String::class.java,
                String::class.java,
                String::class.java,
                ClassLoader::class.java,
                Integer.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java,
            )
            val classpath = System.getenv("SYSTEMSERVERCLASSPATH")
            if (classpath.isNullOrEmpty()) {
                Log.w(TAG, "SYSTEMSERVERCLASSPATH 为空——无法加载 DisplayControl")
                return@lazy null
            }
            val loader = create.invoke(
                null,
                classpath,
                null,
                null,
                ClassLoader.getSystemClassLoader(),
                0,
                true,
                null,
            ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            registerAndroidServersLibrary(cls)
            Log.i(TAG, "DisplayControl 已就绪")
            cls
        } catch (t: Throwable) {
            Log.w(TAG, "DisplayControl 加载失败（SDK ${Build.VERSION.SDK_INT}）: $t")
            null
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun registerAndroidServersLibrary(cls: Class<*>) {
        val loadLibrary0 =
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
    }

    override fun status(): String = synchronized(lock) {
        val vd = display ?: return "OK no display"
        val displayId = vd.display.displayId
        val state = runCatching { vd.display.state }.getOrDefault(-1)
        "OK id=$displayId state=$state flags=0x${flagsUsed.toString(16)} count=${displays.size}"
    }

    // ------------------------------------------------------------ 触摸注入（shell 身份）

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var inputError: String = "未初始化"

    override fun readDisplayImePolicy(displayId: Int): String = ShellDisplayIme.read(displayId)

    override fun captureInstrumentMap(owner: IBinder, displayId: Int, width: Int, height: Int,
                                      maxWidth: Int, maxHeight: Int, pixels: android.os.SharedMemory?): IntArray? {
        if (pixels == null) return null
        return pixels.use { memory ->
            val ctx = appContext ?: return null
            val serviceUid = android.os.Process.myUid()
            if ((serviceUid != 2000 && serviceUid != 0) || ctx.packageName != io.github.hypercar.BuildConfig.APPLICATION_ID ||
                android.os.Binder.getCallingUid() != ctx.applicationInfo.uid || displayId <= 0) return null
            val identity = android.os.Binder.clearCallingIdentity()
            try {
                val (vd, surface) = synchronized(lock) {
                    val vd = displays[displayId] ?: return null
                    vd to (vd.surface ?: return null)
                }
                ShellInstrumentMap.capture(ctx, displayId, width, height, maxWidth, maxHeight, memory) {
                    synchronized(lock) {
                        val size = displaySizes[displayId]
                        val actual = android.graphics.Point()
                        vd.display.getRealSize(actual)
                        owner.isBinderAlive && displayLease.owns(owner) && displays[displayId] === vd &&
                            vd.display.isValid && vd.display.name == "hypercar-map-amap" && vd.surface === surface &&
                            surface.isValid && size?.first == width && size.second == height &&
                            actual.x == width && actual.y == height
                    }
                }
            } catch (_: Exception) { null }
            catch (_: LinkageError) { null }
            finally { android.os.Binder.restoreCallingIdentity(identity) }
        }
    }

    override fun inputState(): String {
        val ok = ensureInput()
        return if (ok) "OK inject=${injectMethod != null} displayId=${setDisplayIdMethod != null}"
        else "ERR $inputError"
    }

    override fun motion(
        displayId: Int,
        action: Int,
        x: Int,
        y: Int,
        downTimeMs: Long,
    ): String {
        if (!ensureInput()) return "ERR $inputError"
        val a = when (action) {
            0 -> MotionEvent.ACTION_DOWN
            1 -> MotionEvent.ACTION_UP
            2 -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_CANCEL
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (downTimeMs > 0L) downTimeMs else now
        val ev = MotionEvent.obtain(
            dt,
            now,
            a,
            x.toFloat(),
            y.toFloat(),
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = sendAsync(displayId, ev)
        return if (ok) "OK" else "ERR $inputError"
    }

    override fun touchEvent(displayId: Int, event: MotionEvent): String = synchronized(lock) {
        if (!displays.containsKey(displayId)) {
            event.recycle()
            return@synchronized "ERR display=$displayId missing"
        }
        if (!ensureInput()) {
            event.recycle()
            return@synchronized "ERR $inputError"
        }
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // This is the service's unmarshalled copy; sendAsync recycles it after injection.
        if (sendAsync(displayId, event)) "OK" else "ERR $inputError"
    }

    override fun consoleKey(displayId: Int, keyCode: Int, metaState: Int): String = synchronized(lock) {
        if (displayId <= 0 || !displays.containsKey(displayId)) return@synchronized "ERR display missing"
        if (!ensureInput()) return@synchronized "ERR $inputError"
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int): Boolean {
            val event = android.view.KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0,
                metaState, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            setDisplayIdMethod!!.invoke(event, displayId)
            return injectMethod!!.invoke(inputManager, event, 0) != false
        }
        runCatching {
            if (inject(android.view.KeyEvent.ACTION_DOWN) && inject(android.view.KeyEvent.ACTION_UP)) "OK"
            else "ERR key injection"
        }.getOrElse { "ERR ${it.javaClass.simpleName}" }
    }

    private fun sendAsync(displayId: Int, event: MotionEvent): Boolean {
        if (!ensureInput()) return false
        return try {
            setDisplayIdMethod!!.invoke(event, displayId)
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC：立即返回，不阻塞触摸线程
            val result = injectMethod!!.invoke(inputManager, event, 0)
            result !is Boolean || result
        } catch (t: Throwable) {
            inputError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "inject(async) 失败: $t")
            false
        } finally {
            event.recycle()
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int): String {        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val ok = send(displayId, single(displayId, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), downTime)) &&
            run { SystemClock.sleep(40); true } &&
            send(displayId, single(displayId, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), downTime))
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    override fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val duration = durationMs.coerceAtLeast(120)
        val steps = (duration / 20).coerceAtLeast(6)
        var ok = send(displayId, single(displayId, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), downTime))
        for (i in 1 until steps) {
            val f = i.toFloat() / steps
            ok = send(
                displayId,
                single(
                    displayId,
                    MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f,
                    y1 + (y2 - y1) * f,
                    downTime,
                ),
            ) && ok
            SystemClock.sleep(8)
        }
        ok = send(displayId, single(displayId, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), downTime)) && ok
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    override fun pinch(
        displayId: Int,
        cx: Int,
        cy: Int,
        fromRadius: Int,
        toRadius: Int,
        steps: Int,
    ): String {
        if (!ensureInput()) return "ERR $inputError"
        val t0 = SystemClock.elapsedRealtimeNanos()
        val downTime = SystemClock.uptimeMillis()
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        var ok = send(displayId, multi(displayId, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(cx - fromRadius.toFloat()), floatArrayOf(cy.toFloat())))
        ok = send(
            displayId,
            multi(
                displayId,
                downTime,
                pointerDown,
                floatArrayOf((cx - fromRadius).toFloat(), (cx + fromRadius).toFloat()),
                floatArrayOf(cy.toFloat(), cy.toFloat()),
            ),
        ) && ok
        val n = steps.coerceAtLeast(4)
        for (i in 1..n) {
            val f = i.toFloat() / n
            val half = fromRadius + (toRadius - fromRadius) * f
            ok = send(
                displayId,
                multi(
                    displayId,
                    downTime,
                    MotionEvent.ACTION_MOVE,
                    floatArrayOf(cx - half, cx + half),
                    floatArrayOf(cy.toFloat(), cy.toFloat()),
                ),
            ) && ok
            SystemClock.sleep(10)
        }
        ok = send(
            displayId,
            multi(
                displayId,
                downTime,
                pointerUp,
                floatArrayOf((cx - toRadius).toFloat(), (cx + toRadius).toFloat()),
                floatArrayOf(cy.toFloat(), cy.toFloat()),
            ),
        ) && ok
        ok = send(
            displayId,
            multi(displayId, downTime, MotionEvent.ACTION_UP, floatArrayOf((cx - toRadius).toFloat()), floatArrayOf(cy.toFloat())),
        ) && ok
        val us = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        return if (ok) "OK $us" else "ERR inject 返回 false（$inputError）"
    }

    private fun ensureInput(): Boolean {
        if (inputManager != null && injectMethod != null && setDisplayIdMethod != null) return true
        val ctx = appContext ?: run { inputError = "没有 Context"; return false }
        val im = ctx.getSystemService(Context.INPUT_SERVICE) ?: run {
            inputError = "INPUT_SERVICE 不可用"
            return false
        }
        inputManager = im
        injectMethod = runCatching {
            im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrElse {
            inputError = "injectInputEvent 反射失败: ${it.message}"
            null
        }
        setDisplayIdMethod = runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrElse {
            inputError = "setDisplayId 反射失败: ${it.message}"
            null
        }
        return injectMethod != null && setDisplayIdMethod != null
    }

    private fun single(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            if (action == MotionEvent.ACTION_DOWN) now else downTime,
            now,
            action,
            x,
            y,
            1f,
            1f,
            0,
            1f,
            1f,
            0,
            0,
        )
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return ev
    }

    private fun multi(
        displayId: Int,
        downTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
    ): MotionEvent {
        val props = Array(xs.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            xs.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun send(displayId: Int, event: MotionEvent): Boolean {
        if (!ensureInput()) return false
        return try {
            setDisplayIdMethod!!.invoke(event, displayId)
            val result = injectMethod!!.invoke(inputManager, event, 2)
            result !is Boolean || result
        } catch (t: Throwable) {
            inputError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "inject 失败: $t")
            false
        } finally {
            event.recycle()
        }
    }

    private companion object {
        private const val TAG = "HyperCar-SpikeShell"
        private val lock = Any()
        private var display: VirtualDisplay? = null
        private val displays = LinkedHashMap<Int, VirtualDisplay>()
        private val displaySizes = LinkedHashMap<Int, Triple<Int, Int, Int>>()
        private val displayLease = DisplayOwnerLease<IBinder>(lock) {
            val count = displays.size
            releaseDisplaysLocked()
            Log.i(TAG, "display owner retired released=$count")
        }
        private var flagsUsed: Int = 0
        private var appContext: Context? = null

        private fun releaseDisplaysLocked() {
            displays.values.forEach { runCatching { it.release() } }
            displays.clear()
            displaySizes.clear()
            display = null
            flagsUsed = 0
        }

        /** SurfaceControl 显示电源模式：OFF=硬件断电，NORMAL=正常点亮。 */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        private var physicalScreenOff = false
    }
}
