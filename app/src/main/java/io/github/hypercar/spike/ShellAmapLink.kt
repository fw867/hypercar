package io.github.hypercar.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在 **shell 进程**里绑定高德 LinkSDK，再把回调转回 App。
 *
 * 为什么不能由 App 直接 bind：MIUI/HyperOS 的 `WakePathChecker` 会把普通 App 发起的
 * `bindService`（action=com.autonavi.minimap.service.NAVIGATION_SERVICE）当成"关联启动"直接拒绝
 * （`WakePathChecker: ... Reject ... wakeType=8`），且不会弹窗让用户补授权。
 * shell(uid 2000) 不受该策略约束；自绘 UI 本来就要求 Shizuku，所以复用同一条 UserService。
 *
 * 协议（反编译 `com.amap.linksdk.aidldefine.*` 得到）：
 * - 控制端 `ILinkSdkControl`：1=authenticate(String 入参/JSON 出参)、3=registerListener(callback)、4=unregisterListener(callback)
 * - 回调端 `ILinkSdkCallback`：1=onAmapCallback(String)、2=onAmapCallbackWithFd(ParcelFileDescriptor)
 * 业务数据（bizType 等）仍是 AES-GCM 信封，解密留在 App 侧做，shell 只转发原文。
 */
internal class ShellAmapLink {

    private val tag = "HyperCar-Shell"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var sink: IAmapLinkSink? = null

    @Volatile
    private var remote: IBinder? = null

    @Volatile
    private var connected = false
    private var amapUid = -1
    private val attempts = AtomicInteger(0)
    private var retryScheduled = false

    /** 只在 shell 进程里用主线程 Looper 做 bind/unbind（bindService 要求）。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun status(): String =
        when {
            sink == null -> "idle"
            remote == null && !connected -> "binding(attempt=${attempts.get()})"
            remote == null -> "bound-no-binder"
            else -> "connected"
        }

    fun connect(ctx: Context?, sink: IAmapLinkSink?): String {
        if (ctx == null) return "ERR no-context"
        if (sink == null) return "ERR no-sink"
        // UserService 的 Context 是宿主 App 的（package=io.github.hypercar），但 uid 是 shell(2000)，
        // 直接 bindService 会被 AMS 拒绝：SecurityException: io.github.hypercar does not belong to uid 2000。
        // 与建屏同款处理：把包名覆盖成 com.android.shell。
        val base = ctx.applicationContext ?: ctx
        // 优先用真正的 shell 包 Context：bindService 由 base 的 ContextImpl 执行，
        // 仅覆盖 getPackageName 的 wrapper 不生效（AMS 仍看到宿主包 → "does not belong to uid 2000"）。
        var ctxErr = ""
        context = runCatching { base.createPackageContext("com.android.shell", 0) }
            .onFailure { ctxErr = it.javaClass.simpleName + ":" + it.message }
            .getOrNull()
            ?: PackageOverrideContext(base, "com.android.shell")
        runCatching {
            sink?.onState(
                "ctx=" + (this.context?.javaClass?.simpleName) +
                    " pkg=" + this.context?.packageName + " err=" + ctxErr,
            )
        }
        this.sink = sink
        amapUid = runCatching {
            this.context!!.packageManager.getApplicationInfo("com.autonavi.minimap", 0).uid
        }.getOrDefault(-1)
        attempts.set(0)
        bind()
        return "OK binding"
    }

    fun disconnect() {
        sink = null
        connected = false
        remote = null
        val ctx = context
        if (ctx != null) {
            main.post {
                runCatching {
                    remote?.let { r -> transact(r, 4, { it.writeStrongBinder(callbackBinder()) })?.recycle() }
                }
                runCatching { ctx.unbindService(connection) }
            }
        }
    }

    /**
     * 以 shell 身份直接调 `IActivityManager.bindService`。
     *
     * 为什么需要它：UserService 里没有可用的 `IApplicationThread`，`Context.bindService`
     * 在本地就返回 false（实测 resolve 成功、AMS 无日志）。shell(uid 2000) 不受 hidden-API
     * 限制，所以这里用反射取 `ServiceManager.getService("activity")` 与 `TRANSACTION_bindService`，
     * 显式传 `callingPackage=com.android.shell`、`caller=null`，自己实现 IServiceConnection。
     */
    private fun bindViaActivityManager(): String {
        val am = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, "activity") as? IBinder
        }.getOrNull() ?: return "ERR no-activity-service"
        val code = runCatching {
            val stub = Class.forName("android.app.IActivityManager" + "$" + "Stub")
            stub.getDeclaredField("TRANSACTION_bindService").getInt(null)
        }.getOrNull() ?: return "ERR no-transaction-code"
        val ctx = context ?: return "ERR no-context"
        val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(
            ComponentName("com.autonavi.minimap", "com.amap.bundle.wearable.connect.glass.AmapLinkService"),
        )
        val resolved = runCatching { intent.resolveTypeIfNeeded(ctx.contentResolver) }.getOrNull()
        var last = "no-variant"
        for (flagsAreLong in listOf(true, false)) {
            for (withFeatureId in listOf(true, false)) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.app.IActivityManager")
                    data.writeStrongBinder(null)              // caller
                    data.writeStrongBinder(null)              // token
                    intent.writeToParcel(data, 0)
                    data.writeString(resolved)
                    data.writeStrongBinder(connectionBinder())
                    if (flagsAreLong) data.writeLong(1L) else data.writeInt(1)
                    data.writeString("com.android.shell")
                    if (withFeatureId) data.writeString(null)
                    data.writeInt(0)
                    val ok = runCatching { am.transact(code, data, reply, 0) }.getOrElse {
                        last = "transact " + it.javaClass.simpleName + ": " + it.message
                        false
                    }
                    if (ok) {
                        runCatching { reply.readException() }
                        val result = runCatching { reply.readInt() }.getOrDefault(Int.MIN_VALUE)
                        if (result >= 0) {
                            val v = "flagsLong=" + flagsAreLong + " featureId=" + withFeatureId
                            runCatching { sink?.onState("am-bind-ok " + v + " result=" + result) }
                            return "OK " + v
                        }
                        last = "variant " + flagsAreLong + "/" + withFeatureId + " result=" + result
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
        return "ERR " + last
    }

    /** IServiceConnection：AMS 连上后回调 connected(name, service, conn)。 */
    private val amConnection = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString("android.app.IServiceConnection")
                return true
            }
            if (code != 1) return false
            data.enforceInterface("android.app.IServiceConnection")
            val name = if (data.readInt() != 0) ComponentName.CREATOR.createFromParcel(data) else null
            val service = data.readStrongBinder()
            if (data.readInt() != 0) data.readStrongBinder()
            runCatching { sink?.onState("am-connected " + (name?.flattenToShortString() ?: "?") + " binder=" + (service != null)) }
            if (service != null) {
                // 服务端是 async binder，需在主线程/后台处理；这里直接走现有注册流程
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { registerOnRemote(service) }
                }
            }
            return true
        }
    }

    private fun connectionBinder(): IBinder = amConnection

    /** 拿到 AmapLink 服务 binder 后：authenticate(1) → registerListener(3)。 */
    private fun registerOnRemote(service: IBinder) {
        transact(service, 1)?.let { reply ->
            runCatching {
                val body = reply.readString().orEmpty()
                val status = runCatching { org.json.JSONObject(body).optString("status") }.getOrDefault("")
                sink?.onState("am-auth=" + status.ifEmpty { "unknown" })
            }
            reply.recycle()
        }
        transact(service, 3) { it.writeStrongBinder(callbackBinder()) }?.let { reply ->
            runCatching {
                val registered = runCatching { reply.readInt() != 0 }.getOrDefault(false)
                sink?.onState("am-registered=" + registered)
            }
            reply.recycle()
        }
    }

    private fun bind() {
        val ctx = context ?: return
        val s = sink ?: return
        if (attempts.get() >= 40) {
            runCatching { s.onState("error:give-up") }
            return
        }
        attempts.incrementAndGet()
        main.post {
            if (sink == null) return@post
            // 先试 shell 身份直连 IActivityManager（UserService 的 Context.bindService 会返回 false）
            val amResult = runCatching { bindViaActivityManager() }.getOrElse { "ERR " + it.javaClass.simpleName + ": " + it.message }
            runCatching { sink?.onState("am-bind=" + amResult) }
            if (amResult.startsWith("OK")) return@post
            val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(
                ComponentName(
                    "com.autonavi.minimap",
                    "com.amap.bundle.wearable.connect.glass.AmapLinkService",
                ),
            )
            val resolved = runCatching { ctx.packageManager.resolveService(intent, 0) }.getOrNull()
            runCatching { sink?.onState("resolve=" + (resolved?.serviceInfo?.let { it.packageName + "/" + it.name } ?: "null")) }
            var err = ""
            val ok = runCatching { ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                .onFailure { err = " : " }
                .getOrDefault(false)
            runCatching { sink?.onState("binding attempt=${attempts.get()} ok=$ok") }
            if (!ok && !retryScheduled) {
                retryScheduled = true
                val delay = (3_000L * attempts.get()).coerceAtMost(30_000L)
                main.postDelayed({ retryScheduled = false; if (remote == null && sink != null) bind() }, delay)
            }
        }
    }

    private fun transact(target: IBinder, code: Int, write: (Parcel) -> Unit = {}): Parcel? =
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.amap.linksdk.aidldefine.ILinkSdkControl")
                write(data)
                target.transact(code, data, reply, 0)
                reply.readException()
                reply
            } finally {
                data.recycle()
            }
        }.getOrNull()

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString("com.amap.linksdk.aidldefine.ILinkSdkCallback")
                return true
            }
            if (code !in 1..2) return false
            val caller = Binder.getCallingUid()
            if (amapUid >= 0 && caller != amapUid) {
                Log.w(tag, "AMAP-LINK: drop callback from uid=$caller (want $amapUid)")
                return false
            }
            data.enforceInterface("com.amap.linksdk.aidldefine.ILinkSdkCallback")
            when (code) {
                1 -> {
                    val text = data.readString()
                    if (text != null && text.length <= 131_072) {
                        runCatching { sink?.onCallback(1, text) }
                    }
                }
                else -> {
                    val fd = if (data.readInt() != 0) {
                        ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    } else {
                        null
                    }
                    if (fd != null) {
                        runCatching {
                            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                                val bytes = ByteArray(65_537)
                                var size = 0
                                while (size < bytes.size) {
                                    val n = input.read(bytes, size, bytes.size - size)
                                    if (n <= 0) break
                                    size += n
                                }
                                if (size <= 65_536) {
                                    runCatching { sink?.onCallback(2, bytes.copyOf(size).toString(Charsets.UTF_8)) }
                                }
                            }
                        }
                    }
                }
            }
            runCatching { reply?.writeNoException() }
            return true
        }
    }

    private fun callbackBinder(): IBinder = callback

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (sink == null || service == null) return
            remote = service
            connected = true
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    sink?.onState("connected descriptor=${safeDescriptor(service)}")
                    // 1 = authenticate
                    transact(service, 1)?.let { reply ->
                        runCatching {
                            val body = reply.readString().orEmpty()
                            val status = runCatching {
                                org.json.JSONObject(body).optString("status")
                            }.getOrDefault("")
                            sink?.onState("auth=${status.ifEmpty { "unknown" }} token-not-logged")
                        }
                        reply.recycle()
                    }
                    // 3 = registerListener(callback)
                    transact(service, 3) { it.writeStrongBinder(callbackBinder()) }?.let { reply ->
                        runCatching {
                            val registered = runCatching { reply.readInt() != 0 }.getOrDefault(false)
                            sink?.onState("registered=$registered")
                        }
                        reply.recycle()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            connected = false
            runCatching { sink?.onState("disconnected") }
            if (!retryScheduled) {
                retryScheduled = true
                main.postDelayed({ retryScheduled = false; if (remote == null && sink != null) bind() }, 2_000)
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            connected = false
            runCatching { sink?.onState("binding-died") }
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            connected = false
            runCatching { sink?.onState("null-binding") }
        }
    }

    private fun safeDescriptor(binder: IBinder): String =
        runCatching { binder.interfaceDescriptor ?: "?" }.getOrDefault("?")
}
