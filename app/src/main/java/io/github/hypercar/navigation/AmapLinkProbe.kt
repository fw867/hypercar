package io.github.hypercar.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.spike.IAmapLinkSink
import io.github.hypercar.spike.SpikeShell
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Passive compatibility check, under HyperCar's own UID. Never alters AMap's authorization policy. */
internal object AmapLinkProbe {
    private const val CONTROL = "com.amap.linksdk.aidldefine.ILinkSdkControl"
    private const val CALLBACK = "com.amap.linksdk.aidldefine.ILinkSdkCallback"
    private val main = Handler(Looper.getMainLooper())
    private var active: Reader? = null
    @Synchronized fun start(context: Context) {
        if (!HyperCarRepo.hudNavigationAllowed || HyperCarRepo.carWithHudNavigationAllowed || active != null) return
        active = Reader(context.applicationContext).also { it.bind() }
    }
    @Synchronized fun stop() { active?.close(); active = null }

    /** 仅供 DUMP 保护的调试广播使用：跳过会话/HUD 开关，直接验证 shell 代绑通道。 */
    @Synchronized fun startForDebug(context: Context) {
        if (active != null) {
            HyperCarRepo.log("HUD-DEBUG: 已在运行")
            return
        }
        val shellReady = SpikeShell.isConnected()
        HyperCarRepo.log("HUD-DEBUG: shellReady=$shellReady")
        val reader = Reader(context.applicationContext)
        active = reader
        val sink = reader.debugSink()
        if (shellReady) {
            val res = runCatching { SpikeShell.connectAmapLink(sink) }.getOrNull()
            HyperCarRepo.log("HUD-DEBUG: connectAmapLink=$res")
        }
        // 新路径：以 shell 身份直接调 IActivityManager.bindService
        val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(
            ComponentName(
                "com.autonavi.minimap",
                "com.amap.bundle.wearable.connect.glass.AmapLinkService",
            ),
        )
        val r = runCatching {
            io.github.hypercar.spike.ShellActivityBind(sink).bind(context.applicationContext, intent)
        }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
        HyperCarRepo.log("HUD-DEBUG: amBind=$r")
    }
    @Synchronized fun stopForDebug() { active?.close(); active = null }

    private class Reader(private val context: Context) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "amap-hud-link").apply { isDaemon = true } }
        @Volatile private var closed = false
        @Volatile private var remote: IBinder? = null
        private var bound = false // main thread
        @Volatile private var viaShell = false
        private val shellSink = object : IAmapLinkSink.Stub() {
            override fun onCallback(code: Int, payload: String?) {
                if (payload != null) queue { receive(payload) }
            }
            override fun onState(state: String?) {
                log("shell:${state ?: ""}")
            }
        }
        /** 调试入口用：把同一个 sink 交给 shell 通道。 */
        fun debugSink(): IAmapLinkSink = shellSink
        /** 调试入口用：跳过 shell，直接走 App 自身 bind。 */
        fun bindForDebug() = bind()
        private var count = 0
        private var retries = 0
        private val capture = org.json.JSONArray()
        private val captureFile = java.io.File(context.getExternalFilesDir("diagnostics"), "hud-link-capture.json")
        private val sourceUid = context.packageManager.getApplicationInfo("com.autonavi.minimap", 0).uid
        private fun queue(task: () -> Unit) {
            if (closed) return
            try { worker.execute { if (!closed) runCatching(task).onFailure { log("error=${it.javaClass.simpleName}") } } }
            catch (_: RejectedExecutionException) { }
        }
        private fun log(message: String) = HyperCarRepo.log("HUD-LINK: $message")
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                if (code !in 1..2 || getCallingUid() != sourceUid) return false
                data.enforceInterface(CALLBACK)
                val text = data.readString()
                if (code == 1) {
                    if (text != null && text.length <= 131072) queue { receive(text) }
                } else {
                    val fd = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                    if (fd != null) {
                        if (closed) fd.close()
                        else try { worker.execute {
                            runCatching { ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                                val bytes = ByteArray(65537)
                                var size = 0
                                while (size < bytes.size) {
                                    val n = input.read(bytes, size, bytes.size - size)
                                    if (n <= 0) break
                                    size += n
                                }
                                if (!closed && size <= 65536) runCatching { receive(bytes.copyOf(size).toString(Charsets.UTF_8)) }
                            } }.onFailure { if (!closed) log("FD read=${it.javaClass.simpleName}") }
                        } } catch (_: RejectedExecutionException) { fd.close() }
                    }
                }
                reply?.writeNoException()
                return true
            }
        }
        private fun call(code: Int, write: (Parcel) -> Unit = {}): Parcel {
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(CONTROL); write(request)
                check(remote?.transact(code, request, reply, 0) == true)
                reply.readException()
                return reply
            } catch (error: Exception) { reply.recycle(); throw error }
            finally { request.recycle() }
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed) return
                remote = service
                queue {
                    check(service.interfaceDescriptor == CONTROL)
                    call(1).let { response -> try {
                        val status = JSONObject(response.readString().orEmpty()).optString("status")
                        log("authenticate=${if (status == "success") "success" else "not-success"}; token not logged")
                    } finally { response.recycle() } }
                    call(3) { it.writeStrongBinder(callback) }.let { response -> try {
                        log("registered=${response.readInt() != 0}; awaiting actual guidance, registration is not navigation success")
                    } finally { response.recycle() } }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                remote = null; log("disconnected")
                retryBinding()
            }
            override fun onBindingDied(name: ComponentName) { remote = null; retryBinding() }
            override fun onNullBinding(name: ComponentName) { log("null binding"); close() }
        }
        private fun retryBinding() {
            if (closed || retries >= 3) return
            retries++
            main.postDelayed({
                if (!closed && HyperCarRepo.hudNavigationAllowed && !HyperCarRepo.carWithHudNavigationAllowed && remote == null) {
                    if (bound) { runCatching { context.unbindService(connection) }; bound = false }
                    log("rebind attempt=$retries after map process restart")
                    bind()
                }
            }, 2000L * retries)
        }
        private var bindAttempts = 0

        /**
         * 绑定高德 LinkSDK 服务。
         *
         * 高德进程通常比我们晚起（车机连上后地图卡才拉起高德），而 MIUI 的
         * `WakePathChecker` 会拒绝"从后台把别人叫醒"的 bindService（日志
         * `WakePathChecker: ... Reject ... AmapLinkService`）——所以 bind 失败**不能**
         * 当成终态：按 3s→30s 退避重试，直到高德进程起来为止（最多 40 次 ≈ 15 分钟）。
         */
        fun bind() { main.post {
            if (closed) return@post
            if (bindAttempts >= 40) { log("give up bind after $bindAttempts attempts"); return@post }
            bindAttempts++
            // 顺序：先 App 自身 bind（0.1.0.577 实测可用，需 MIUI「关联启动」授权），
            // 失败再让 shell 进程代绑（当前 MIUI/shell Context 两条都还没打通，保留做后续）。
            if (appBind()) {
                log("bind via app attempt=$bindAttempts")
                return@post
            }
            if (SpikeShell.isConnected()) {
                val r = runCatching { SpikeShell.connectAmapLink(shellSink) }.getOrNull()
                if (r != null && r.startsWith("OK")) {
                    viaShell = true
                    log("bind via shell attempt=$bindAttempts")
                    return@post
                }
                log("shell bind rejected=$r")
            }
            val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(ComponentName(
                "com.autonavi.minimap", "com.amap.bundle.wearable.connect.glass.AmapLinkService"))
            if (bindAttempts == 1) runCatching { captureFile.delete() }
            bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            log("bind=$bound attempt=$bindAttempts ownUid=${android.os.Process.myUid()} mode=selfUI")
            if (!bound) {
                val delay = (3000L * bindAttempts).coerceAtMost(30_000L)
                main.postDelayed({ if (!closed && remote == null) bind() }, delay)
            }
        } }

        /** App 自身 bindService；返回是否发起成功（真正连上要看 onServiceConnected）。 */
        private fun appBind(): Boolean {
            val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(
                ComponentName(
                    "com.autonavi.minimap",
                    "com.amap.bundle.wearable.connect.glass.AmapLinkService",
                ),
            )
            if (bindAttempts == 1) runCatching { captureFile.delete() }
            bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            return bound
        }
        private fun receive(raw: String) {
            val text = LinkEnvelope.decode(raw) ?: run { log("callback envelope invalid bytes=${raw.length}"); return }
            val json = JSONObject(text)
            if (json.optInt("bizType") == 113 && capture.length() < 8) {
                // Temporary bounded local sample of business data only. Never save authentication replies.
                // Sampling is not gated on the debug-log switch so a field sample can be taken on demand;
                // the file is deleted on every (re)bind and capped at 8 entries.
                capture.put(JSONObject().put("bizType", 113).put("datas", json.opt("datas")))
                runCatching { captureFile.parentFile?.mkdirs(); captureFile.writeText(capture.toString()) }
                if (capture.length() == 1) log("sample#1 fields=${fieldShape(json.opt("datas"))}")
            }
            count++
            if (count <= 10 || count and (count - 1) == 0) log("callback=$count ${shape(json, 0)}")
        }
        /** 字段名 + 类型 + 数值，字符串只留长度（不把路名写进日志）。 */
        private fun fieldShape(datas: Any?): String {
            val text = datas as? String ?: return "datas:${datas?.javaClass?.simpleName ?: "null"}"
            val first = runCatching {
                val array = org.json.JSONArray(text)
                if (array.length() == 0) null else array.opt(0)
            }.getOrNull() ?: return "datas:array length=${text.length}"
            val obj = first as? JSONObject ?: return "datas[0]:${first.javaClass.simpleName}"
            return obj.keys().asSequence().take(40).map { key ->
                when (val v = obj.opt(key)) {
                    is Number -> "$key=$v"
                    is Boolean -> "$key=$v"
                    is JSONObject -> "$key:{${v.keys().asSequence().take(12).joinToString(",")}}"
                    is org.json.JSONArray -> "$key:[len=${v.length()}]"
                    is String -> "$key:str(${v.length})"
                    else -> "$key:${v?.javaClass?.simpleName ?: "null"}"
                }
            }.sorted().joinToString(prefix = "datas[0]{", postfix = "}")
        }

        private fun shape(json: JSONObject, depth: Int): String = json.keys().asSequence().take(24).map { key ->
            val value = json.opt(key)
            val type = when {
                value is JSONObject && depth < 5 -> shape(value, depth + 1)
                value is String && key in setOf("datas", "data", "message") && depth < 5 ->
                    LinkJson.objectValue(value)?.let { shape(it, depth + 1) }
                        ?: "String(length=${value.length},encoding=${LinkJson.encoding(value)})"
                value is String && key in setOf("type", "status", "event", "cmd", "command") && value.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,40}")) -> value
                value is Number && key in setOf("bizType", "code", "errorCode", "action", "type", "status", "icon") -> value.toString()
                value is Boolean -> value.toString()
                else -> value?.javaClass?.simpleName ?: "null"
            }
            "$key:$type"
        }.sorted().joinToString(prefix = "{", postfix = "}")
        fun close() {
            if (closed) return
            closed = true
            if (viaShell) {
                viaShell = false
                runCatching { SpikeShell.disconnectAmapLink() }
            }
            main.post { if (bound) { runCatching { context.unbindService(connection) }; bound = false } }
            // Bounded queue: no periodic task or reconnection loop.
            runCatching { worker.execute {
                runCatching { if (remote != null) call(4) { it.writeStrongBinder(callback) }.recycle() }
                remote = null
            } }
            worker.shutdown()
        }
    }
}
