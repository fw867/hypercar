package io.github.hypercar.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 以 **shell 身份**调 `IActivityManager.bindService` 绑定高德 LinkSDK。
 *
 * 为什么不直接 `Context.bindService`：
 * 1) 普通 App 会被 MIUI `WakePathChecker` 当"关联启动"拒绝（不弹窗）；
 * 2) Shizuku UserService 里的 Context 没有可用的 `IApplicationThread`，
 *    `bindService` 在本地就返回 false（实测：resolve 成功、无 AMS 日志、返回 false）。
 *
 * 这里用 `ShizukuBinderWrapper` 把 IActivityManager 的调用身份换成 shell(2000)，
 * 显式传 `callingPackage = "com.android.shell"`，并自己实现 `IServiceConnection` 收服务 binder。
 *
 * 注意：IActivityManager 的 bindService 参数布局随版本变化（API 34 起 flags 为 long，
 * 之后又加了 callingFeatureId），这里按变体逐个尝试，成功的那个记下来复用。
 */
internal class ShellActivityBind(private val sink: IAmapLinkSink, private val tag: String = "HUD-AMBIND") {

    private val connectionBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR_CONN)
                return true
            }
            if (code != 1) return false
            data.enforceInterface(DESCRIPTOR_CONN)
            val name = data.readInt().let {
                if (it != 0) ComponentName.CREATOR.createFromParcel(data) else null
            }
            val service = data.readStrongBinder()
            // 第三个参数是 IServiceConnection 的 binder（AMS 侧的代理），我们不用
            if (data.readInt() != 0) data.readStrongBinder()
            runCatching { sink.onState("am-connected ${name?.flattenToShortString() ?: "?"} binder=${service != null}") }
            if (service != null) {
                runCatching { pump(service) }
            }
            return true
        }
    }

    /** 拿到服务 binder 后：authenticate(1) → registerListener(3)，之后回调走 [callback]。 */
    private fun pump(raw: IBinder) {
        val svc = ShizukuBinderWrapper(raw)
        // 1 = authenticate
        transact(svc, 1)?.let { reply ->
            val body = runCatching { reply.readString().orEmpty() }.getOrDefault("")
            val status = runCatching { org.json.JSONObject(body).optString("status") }.getOrDefault("")
            runCatching { sink.onState("am-auth=${status.ifEmpty { "unknown" }}") }
            reply.recycle()
        }
        // 3 = registerListener(callback)
        transact(svc, 3) { it.writeStrongBinder(callback) }?.let { reply ->
            val registered = runCatching { reply.readInt() != 0 }.getOrDefault(false)
            runCatching { sink.onState("am-registered=$registered") }
            reply.recycle()
        }
    }

    private fun transact(target: IBinder, code: Int, write: (Parcel) -> Unit = {}): Parcel? =
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CONTROL)
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
                reply?.writeString(CALLBACK)
                return true
            }
            if (code !in 1..2) return false
            data.enforceInterface(CALLBACK)
            if (code == 1) {
                val text = data.readString()
                if (!text.isNullOrEmpty() && text.length <= 131_072) {
                    runCatching { sink.onCallback(1, text) }
                }
            } else {
                val fd = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
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
                                runCatching { sink.onCallback(2, bytes.copyOf(size).toString(Charsets.UTF_8)) }
                            }
                        }
                    }
                }
            }
            runCatching { reply?.writeNoException() }
            return true
        }
    }

    /** 尝试各种参数布局调用 bindService。返回 null 表示成功（已发起），否则返回最后一次错误描述。 */
    fun bind(context: Context, intent: Intent): String {
        val binder = runCatching { SystemServiceHelper.getSystemService("activity") }.getOrNull()
            ?: return "ERR no-activity-binder"
        val am = ShizukuBinderWrapper(binder)
        // SystemServiceHelper 的第一个参数是 **Stub 类名**（不是服务名），第二个是方法名。
        val stubClass = "android.app.IActivityManager" + "$" + "Stub"
        val code = runCatching {
            SystemServiceHelper.getTransactionCode(stubClass, "bindService")
        }.getOrNull()
            ?: return "ERR no-transaction-code"
        val pkg = "com.android.shell"
        val resolved = runCatching { intent.resolveTypeIfNeeded(context.contentResolver) }.getOrNull()

        // 变体：(flags 宽度, 是否带 callingFeatureId)
        val variants = listOf(false to true, true to true, false to false, true to false)
        var last = "no-variant-tried"
        for ((flagsAreLong, withFeatureId) in variants) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.app.IActivityManager")
                data.writeStrongBinder(null)          // IApplicationThread caller
                data.writeStrongBinder(null)          // IBinder token
                intent.writeToParcel(data, 0)         // Intent service
                data.writeString(resolved)            // String resolvedType
                data.writeStrongBinder(connectionBinder) // IServiceConnection
                if (flagsAreLong) data.writeLong(FLAGS.toLong()) else data.writeInt(FLAGS)
                data.writeString(pkg)                 // String callingPackage
                if (withFeatureId) data.writeString(null) // String callingFeatureId
                data.writeInt(android.os.Process.myUid().let { 0 })  // int userId（当前用户）
                val ok = runCatching { am.transact(code, data, reply, 0) }.getOrElse {
                    last = "transact ${it.javaClass.simpleName}: ${it.message}"
                    false
                }
                if (ok) {
                    runCatching { reply.readException() }
                    val result = runCatching { reply.readInt() }.getOrDefault(Int.MIN_VALUE)
                    if (result >= 0) {
                        lastUsed = "flagsLong=$flagsAreLong featureId=$withFeatureId"
                        Log.i(tag, "AM bindService ok variant=$lastUsed result=$result")
                        return "OK $lastUsed"
                    }
                    last = "variant flagsLong=$flagsAreLong featureId=$withFeatureId result=$result"
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        return "ERR $last"
    }

    companion object {
        private const val CONTROL = "com.amap.linksdk.aidldefine.ILinkSdkControl"
        private const val CALLBACK = "com.amap.linksdk.aidldefine.ILinkSdkCallback"
        private const val DESCRIPTOR_CONN = "android.app.IServiceConnection"
        private const val FLAGS = 1 // BIND_AUTO_CREATE

        @Volatile
        var lastUsed: String = ""
            private set
    }
}
