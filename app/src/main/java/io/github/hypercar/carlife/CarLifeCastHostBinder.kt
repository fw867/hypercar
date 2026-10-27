package io.github.hypercar.carlife

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import android.view.Surface

/**
 * CarLife 的「投屏宿主」Binder 实现（**不依赖 CarWith**）。
 *
 * 背景（详见 docs/NEGOTIATION-GAPS.md §25）：
 * CarLife 的 `CarlifeConnectService` 收到带 `bindPackageName` + `usb_type` 的 Intent 后，
 * 会 `bindService()` 到调用方指定的 Service，并把它当作
 * `com.baidu.carlife.mixing.aidl.ICarLifeCastManager` 使用。
 * 有了这个 binder，CarLife 才会：
 *   1) 认为"会话已开始"（否则它接受 TCP 后 15ms 就关掉媒体通道）；
 *   2) 通过 `prepareCast(CastConfig)` 把编码画布交给我们；
 *   3) 通过 `invokeMethod(Intent)` 询问宿主各种动作 —— **包括 `use_carlife_opengl`**，
 *      而 GL 是否开启正是 CarLife 肯不肯渲染自己界面的开关。
 *
 * 本实现按对方 `ICarLifeCastManager.Stub.onTransact` 的**字节级布局**复刻，
 * 因为 Binder 只认 DESCRIPTOR 与事务号，不需要引入对方的 AIDL 类。
 */
class CarLifeCastHostBinder : android.os.Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_PREPARE_CAST -> {
                data.enforceInterface(DESCRIPTOR)
                // 对方是 `writeInt(1)` + `castConfig.writeToParcel()`：先读存在标记，再读四个字段。
                // （2026-10-27 首版漏了这个标记，读成 1x1920@720 → 必须按此顺序）
                val present = data.readInt() != 0
                var width = 0
                var height = 0
                var dpi = 0
                var surface: Surface? = null
                if (present) {
                    width = data.readInt()
                    height = data.readInt()
                    dpi = data.readInt()
                    surface = data.readParcelable<Surface>(Surface::class.java.classLoader)
                }
                log("CASTHOST: ← prepareCast ${width}x${height}@${dpi}dpi surface=${surface != null}")
                onPrepareCast?.invoke(width, height, dpi, surface)
                reply?.writeNoException()
                return true
            }
            TRANSACTION_STOP_CAST -> {
                data.enforceInterface(DESCRIPTOR)
                log("CASTHOST: ← stopCast")
                reply?.writeNoException()
                return true
            }
            TRANSACTION_SET_SYSTEM_AUDIO_CALLBACK -> {
                data.enforceInterface(DESCRIPTOR)
                val cb = data.readStrongBinder()
                log("CASTHOST: ← setSystemAudioCallback(${cb != null})")
                reply?.writeNoException()
                return true
            }
            TRANSACTION_SET_CAST_CALLBACK -> {
                data.enforceInterface(DESCRIPTOR)
                val cb = data.readStrongBinder()
                log("CASTHOST: ← setCastCallback(${cb != null})")
                reply?.writeNoException()
                return true
            }
            TRANSACTION_INVOKE_METHOD -> {
                data.enforceInterface(DESCRIPTOR)
                val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                val action = intent?.action
                val extrasDump = intent?.extras?.let { b -> b.keySet().joinToString(",") { "$it=${b.get(it)}" } }
                val bundle = answerAction(action, intent?.extras)
                log("CASTHOST: ← invokeMethod action=$action extras=[$extrasDump] → ${bundle?.keySet()?.joinToString(",") ?: "null"}")
                reply?.writeNoException()
                if (bundle != null) {
                    reply?.writeInt(1)
                    bundle.writeToParcel(reply!!, 1)
                } else {
                    reply?.writeInt(0)
                }
                return true
            }
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * 回答 CarLife 的"宿主动作询问"。
     *
     * 关键一条：`use_carlife_opengl`（`Actions.CarLife.CARLIFE_USE_OPENGL`）。
     * CarLife 的 `CarlifeSurfaceWrapper.openglRenderType()` 会读 `use_opengl_type`（int）
     * 或 `use_opengl`（boolean）；返回 1 = OPENGL_OPEN → GL 渲染线程启动 →
     * 它自己的车机界面才会被渲染进编码器。
     *
     * 其余动作先只记录、返回 null（= 宿主不支持），后续按需补。
     */
    private fun answerAction(action: String?, extras: Bundle?): Bundle? {
        return when (action) {
            ACTION_USE_OPENGL -> Bundle().apply {
                // 实验（0.1.0.232）：回答 0 = OPENGL_CLOSE。
                // 这样 CarLife 走 ScreenRealImpl.getVirtualSurface() → 直接把【编码器输入面】
                // 交给宿主，我们的 VirtualDisplay 就能绕过它的 GL 合成线程直接写像素。
                putInt("use_opengl_type", 0)
                putBoolean("use_opengl", false)
            }
            else -> null
        }
    }

    companion object {
        private const val TAG = "HyperCar"

        /** 对方 `ICarLifeCastManager.Stub` 的 DESCRIPTOR（字节级固定）。 */
        const val DESCRIPTOR = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager"

        private const val TRANSACTION_PREPARE_CAST = 1
        private const val TRANSACTION_STOP_CAST = 2
        private const val TRANSACTION_SET_SYSTEM_AUDIO_CALLBACK = 3
        private const val TRANSACTION_SET_CAST_CALLBACK = 4
        private const val TRANSACTION_INVOKE_METHOD = 5

        /** `Actions.CarLife.CARLIFE_USE_OPENGL` */
        const val ACTION_USE_OPENGL = "use_carlife_opengl"

        /** CarLife 事件回调（由 [CarLifeCastHost] 注入，避免这里直接依赖 Repo）。 */
        @Volatile
        var onPrepareCast: ((Int, Int, Int, Surface?) -> Unit)? = null

        @Volatile
        var logger: ((String) -> Unit)? = null

        private fun log(line: String) {
            Log.i(TAG, line)
            logger?.invoke(line)
        }
    }
}

/** 供 Service 使用：把 binder 做成单例，重复 onBind 拿到的是同一个实例。 */
object CarLifeCastHost {
    private var binder: IBinder? = null

    fun binder(): IBinder = binder ?: synchronized(this) {
        binder ?: CarLifeCastHostBinder().also { binder = it }
    }
}
