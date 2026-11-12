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
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** App-owned binding: Android needs a registered application thread, which shell lacks. */
internal object AmapLinkProbe {
    private const val CONTROL = "com.amap.linksdk.aidldefine.ILinkSdkControl"
    private const val CALLBACK = "com.amap.linksdk.aidldefine.ILinkSdkCallback"
    private val main = Handler(Looper.getMainLooper())
    private var active: Reader? = null
    @Synchronized fun start(context: Context, mapStarted: Boolean = false) {
        if (!HyperCarRepo.hudNavigationAllowed || HyperCarRepo.carWithHudNavigationAllowed) return
        if (active?.diagnosticOnly == true) { active?.close(); active = null }
        val reader = active ?: Reader(context.applicationContext, diagnosticOnly = false).also { active = it }
        reader.bind(mapStarted) // A real map launch renews an exhausted startup retry budget.
    }
    @Synchronized fun stop() { active?.close(); active = null }
    /** DUMP-protected diagnostics exercise exactly the production binding implementation. */
    @Synchronized fun startForDebug(context: Context) {
        if (active != null) { HyperCarRepo.log("HUD-DEBUG: 已在运行"); return }
        active = Reader(context.applicationContext, diagnosticOnly = true).also { it.bind() }
    }
    @Synchronized fun stopForDebug() {
        // A navigation session may have replaced the temporary diagnostic reader.
        if (active?.diagnosticOnly == true) stop()
    }

    private class Reader(private val context: Context, val diagnosticOnly: Boolean) {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "amap-hud-link").apply { isDaemon = true } }
        @Volatile private var closed = false
        private val state = LinkBindingState()
        private var current: Attempt? = null // main thread
        private val pendingFds = java.util.concurrent.ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
        private val retry = Runnable { bind() }
        private var count = 0
        private var fdCount = 0
        private var nextGuidanceLogAt = 0L
        private val mapDiagnosticValues = mutableMapOf<Int, String>()
        private var mapDiagnosticLines = 0
        private val capture = org.json.JSONArray()
        private val sampledTypes = mutableSetOf<Int>()
        private val captureFile = java.io.File(context.getExternalFilesDir("diagnostics"), "hud-link-capture.json")
        private val sourceUid = runCatching { context.packageManager.getApplicationInfo("com.autonavi.minimap", 0).uid }.getOrDefault(-1)
        private fun log(message: String) = HyperCarRepo.log("HUD-LINK: $message")
        private fun queue(task: () -> Unit) {
            if (closed) return
            try { worker.execute { if (!closed) runCatching(task).onFailure { log("error=${it.javaClass.simpleName}") } } }
            catch (_: RejectedExecutionException) { }
        }
        fun bind(mapStarted: Boolean = false) { main.post {
            if (closed || current != null) return@post
            main.removeCallbacks(retry)
            if (mapStarted) state.mapStarted()
            val token = state.begin() ?: run { log("binding stopped after 8 failures; check map startup/关联启动 permission"); return@post }
            if (sourceUid < 0) { log("map not installed"); return@post }
            if (count == 0) runCatching { captureFile.delete() }
            val attempt = Attempt(token)
            current = attempt
            val intent = Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE").setComponent(
                ComponentName("com.autonavi.minimap", "com.amap.bundle.wearable.connect.glass.AmapLinkService"))
            attempt.bound = runCatching { context.bindService(intent, attempt.connection, Context.BIND_AUTO_CREATE) }
                .onFailure { log("bind error=${it.javaClass.simpleName}") }.getOrDefault(false)
            log("bind=${attempt.bound} attempt=${state.attempts} ownUid=${android.os.Process.myUid()}")
            if (attempt.bound) main.postDelayed(attempt.watchdog, 10_000)
            else retry(attempt, "not accepted; waiting for map/关联启动 permission")
        } }
        private fun retry(attempt: Attempt, reason: String) {
            if (closed || current !== attempt) return
            log("$reason; rebind in ${state.retryDelayMillis}ms")
            attempt.retire(); current = null
            main.removeCallbacks(retry)
            main.postDelayed(retry, state.retryDelayMillis)
        }
        private inner class Attempt(private val token: Long) {
            private val payload = LinkPayload()
            private val navigationToken = if (diagnosticOnly) 0L else AmapLinkNavigation.begin()
            private fun deliver(text: String) = receive(text, navigationToken)
            @Volatile private var retired = false
            @Volatile private var authenticated = false
            @Volatile private var registered = false
            @Volatile private var remote: IBinder? = null
            var bound = false // main thread
            private fun alive() = !closed && !retired && state.accepts(token)
            val watchdog = Runnable { if (alive() && !registered) retry(this, "bind/auth timeout") }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!alive()) return
                    remote = service
                    queue { register(service) }
                }
                override fun onServiceDisconnected(name: ComponentName) { remote = null; retry(this@Attempt, "service disconnected") }
                override fun onBindingDied(name: ComponentName) { remote = null; retry(this@Attempt, "binding died") }
                override fun onNullBinding(name: ComponentName) { retry(this@Attempt, "null binding") }
            }
            private val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                    if (code !in 1..2 || Binder.getCallingUid() != sourceUid) return false
                    data.enforceInterface(CALLBACK)
                    if (code == 1) {
                        val text = data.readString()
                        // A synchronous initial callback can arrive before registerListener returns.
                        if (alive() && authenticated && text != null && text.length <= 131072)
                            queue { if (alive() && registered) payload.accept(text, android.os.SystemClock.elapsedRealtime())?.let(::deliver) }
                    } else {
                        // Actual SDK AIDL is (String metadata, ParcelFileDescriptor fd).
                        val metadata = data.readString()
                        val fd = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                        if (fd != null) {
                            if (!alive() || !authenticated) fd.close()
                            else {
                                pendingFds.add(fd)
                                try { worker.execute {
                                    try {
                                        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                                            val descriptor = JSONObject(metadata ?: "{}")
                                            val length = descriptor.optLong("dataLength", -1)
                                            check(length in 1..LinkPayload.MAX_BYTES.toLong())
                                            val text = LinkPayload.read(input)
                                            check(text.length.toLong() == length)
                                            if (alive() && registered) {
                                                fdCount++
                                                if (fdCount <= 4 || fdCount and (fdCount - 1) == 0) log("FD payload chars=${text.length} count=$fdCount; content not logged")
                                                payload.accept(text, android.os.SystemClock.elapsedRealtime())?.let(::deliver)
                                            }
                                        }
                                    } catch (error: Exception) { if (alive()) log("FD payload error=${error.javaClass.simpleName}") }
                                    finally { pendingFds.remove(fd); runCatching { fd.close() } }
                                } } catch (_: RejectedExecutionException) { pendingFds.remove(fd); fd.close() }
                            }
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
            }
            private fun register(service: IBinder) {
                if (!alive()) return
                try {
                    check(service.interfaceDescriptor == CONTROL)
                    val authResult = call(service, 1).let { response -> try {
                        JSONObject(response.readString().orEmpty())
                    } finally { response.recycle() } }
                    val auth = authResult.optString("status") == "success"
                    log("authenticate=${if (auth) "success" else "not-success"}; token not logged")
                    if (!alive()) return
                    if (!auth) { main.post { retry(this, "authentication not accepted") }; return }
                    authenticated = true
                    val accepted = call(service, 3) { it.writeStrongBinder(callback) }.let { response -> try {
                        response.readInt() != 0
                    } finally { response.recycle() } }
                    if (!alive()) {
                        if (accepted) runCatching { call(service, 4) { it.writeStrongBinder(callback) }.recycle() }
                        return
                    }
                    registered = accepted
                    log("registered=$accepted; awaiting actual guidance, registration is not HUD output")
                    main.post {
                        if (!alive()) return@post
                        if (accepted) { state.registered(token); main.removeCallbacks(watchdog) }
                        else retry(this, "registration not accepted")
                    }
                    // Documented read-only commands: refresh key guidance and query structure.
                    val authToken = authResult.optJSONObject("data")?.optString("token").orEmpty()
                    if (accepted && authToken.isNotEmpty()) for (cmd in listOf(7, 5)) {
                        if (!alive()) break
                        val request = JSONObject().put("cmd", cmd).put("requestId", (System.nanoTime() % 900000 + 100000).toInt())
                            .put("token", authToken).put("sessionId", "hypercar-$token")
                        runCatching { call(service, 2) { it.writeString(request.toString()) }.let { response -> try {
                            val result = JSONObject(response.readString().orEmpty())
                            log("guidance query cmd=$cmd status=${result.optString("status")} error=${result.optInt("errorCode", 0)}; token not logged")
                        } finally { response.recycle() } } }
                    }
                } catch (error: Exception) {
                    log("registration error=${error.javaClass.simpleName}")
                    main.post { retry(this, "registration failed") }
                }
            }
            fun retire() {
                retired = true; authenticated = false
                AmapLinkNavigation.clear(navigationToken)
                state.invalidate(token)
                main.removeCallbacks(watchdog)
                pendingFds.forEach { runCatching { it.close() } }; pendingFds.clear()
                val service = remote; val wasRegistered = registered
                registered = false; remote = null
                if (service != null && wasRegistered) runCatching { worker.execute {
                    runCatching { call(service, 4) { it.writeStrongBinder(callback) }.recycle() }
                } }
                if (bound) { bound = false; runCatching { context.unbindService(connection) } }
            }
        }
        private fun call(target: IBinder, code: Int, write: (Parcel) -> Unit = {}): Parcel {
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(CONTROL); write(request)
                check(target.transact(code, request, reply, 0)); reply.readException()
                return reply
            } catch (error: Exception) { reply.recycle(); throw error }
            finally { request.recycle() }
        }
        private fun receive(raw: String, navigationToken: Long) {
            val text = LinkEnvelope.decode(raw) ?: run { log("callback envelope invalid bytes=${raw.length}"); return }
            val json = JSONObject(text)
            if (json.optInt("bizType") == 113 && io.github.hypercar.settings.SettingsStore.debugLogEnabled && mapDiagnosticLines < 64) {
                val items = runCatching { org.json.JSONArray(json.optString("datas")) }.getOrNull()
                if (items != null) for (i in 0 until minOf(items.length(), 64)) {
                    val item = items.optJSONObject(i) ?: continue
                    val type = item.optInt("type", -1)
                    val value = item.opt("data")
                    val data = value as? JSONObject ?: (value as? String)?.let { LinkJson.objectValue(it) }
                    val diagnostic = MapNavigationDiagnostic.event(type, data) ?: continue
                    if (mapDiagnosticValues[type] != diagnostic && mapDiagnosticLines < 64) {
                        mapDiagnosticValues[type] = diagnostic
                        mapDiagnosticLines++
                        HyperCarRepo.log("MAP-NAV-EVENT: $diagnostic")
                    }
                }
            }
            if (json.optInt("bizType") == 113 && navigationToken != 0L &&
                HyperCarRepo.hudNavigationAllowed && !HyperCarRepo.carWithHudNavigationAllowed) {
                val items = runCatching { org.json.JSONArray(json.optString("datas")) }.getOrNull()
                val now = android.os.SystemClock.elapsedRealtime()
                var changed = false
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val value = item.opt("data")
                    val data = value as? JSONObject ?: (value as? String)?.let { LinkJson.objectValue(it) }
                    changed = AmapLinkNavigation.accept(navigationToken, item.optInt("type", -1), data, now) || changed
                }
                if (changed && now >= nextGuidanceLogAt) {
                    nextGuidanceLogAt = now + 5000
                    val cue = AmapLinkNavigation.snapshot()
                    log("guidance state=${cue?.state} maneuver=${cue?.maneuver} distance=${cue?.distanceMeters}m; road text omitted")
                }
            }
            if (json.optInt("bizType") == 113 && capture.length() < 16) {
                // Do not fill the navigation sample with repeated location/GPS/zoom events.
                // Capture one bounded business sample per type, excluding auth and known GPS events.
                val items = runCatching { org.json.JSONArray(json.optString("datas")) }.getOrNull()
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val type = item.optInt("type", -1)
                    if (type < 0 || type in setOf(13, 16, 22, 23) || type in sampledTypes ||
                        capture.length() >= 16 || item.toString().length > 8192) continue
                    sampledTypes.add(type)
                    val sample = if (type == 4) {
                        val value = item.opt("data")
                        val info = value as? JSONObject ?: (value as? String)?.let { LinkJson.objectValue(it) }
                        val fields = JSONObject()
                        listOf("eventType", "curManeuverID", "segmentRemainDist", "routeRemainDist", "nextRouteName").forEach { key ->
                            if (info?.has(key) == true) fields.put(key, info.opt(key))
                        }
                        JSONObject().put("type", type).put("data", fields)
                    } else item
                    capture.put(sample)
                    runCatching { captureFile.parentFile?.mkdirs(); captureFile.writeText(capture.toString()) }
                    log("navigation sample type=$type fields=${fieldShape(org.json.JSONArray().put(item).toString())}")
                }
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
            state.close()
            pendingFds.forEach { runCatching { it.close() } }; pendingFds.clear()
            main.post {
                main.removeCallbacks(retry)
                current?.retire(); current = null
                worker.shutdown()
            }
        }
    }
}
