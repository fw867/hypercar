package io.github.hypercar.carlife

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 实现 `ICarLifeCastManager` 的宿主 Service（见 [CarLifeCastHostBinder]）。
 *
 * CarLife 会 `bindService(new Intent().setClassName(bindPackageName, bindClassName))`，
 * 所以本 Service **必须 exported**，否则对方 bind 不到。
 */
class CastHostService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "CASTHOST: onBind extras=${intent?.extras}")
        boundAt = System.currentTimeMillis()
        bound = true
        return CarLifeCastHost.binder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CASTHOST: onStartCommand extras=${intent?.extras}")
        return START_STICKY
    }

    companion object {
        private const val TAG = "HyperCar"

        const val ACTION_START = "io.github.hypercar.action.START_CAST_HOST"

        /** 本次会话绑定的是哪个 CarLife 包（决定 GL 回答策略）。 */
        @Volatile
        var targetPackage: String = ""
            internal set

        /** 是否已经被 CarLife 绑定过（runConnect 会等它，保证"先绑宿主再握手"）。 */
        @Volatile
        var bound: Boolean = false
            private set

        @Volatile
        var boundAt: Long = 0L
            private set

        /** 等 CarLife 调 onBind（最多 [timeoutMs]）。 */
        fun awaitBound(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (bound) return true
                Thread.sleep(100)
            }
            return bound
        }

        /** CarLife 的会话入口 Service（exported=true，不需要权限）。 */
        const val CARLIFE_CONNECT_SERVICE = "com.baidu.carlife.service.CarlifeConnectService"

        /** 连接类型：`MixConstants.CONNECT_KEY_USB_TYPE` */
        const val KEY_USB_TYPE = "usb_type"
        const val TYPE_WIFI_HOTSPOT = 0
        const val TYPE_WIFI_P2P = 1
        const val TYPE_WIFI_BOX = 2
        const val TYPE_NONE = 13

        /**
         * 让 CarLife 进入"会话已开始"状态，并把本 App 绑成它的投屏宿主。
         *
         * 这一步等价于 CarWith 做的事（`CarlifeConnectService.connectStart` 要求
         * `bindPackageName` + `usb_type` 两个 extra，少一个它就直接 return）。
         *
         * @return true = Intent 已发出（不代表对方接受，结果看日志 `CASTHOST:`）
         */
        fun startCarLifeSession(
            context: Context,
            carLifePackage: String,
            usbType: Int = TYPE_WIFI_HOTSPOT,
        ): Boolean {
            val pkg = context.packageName
            val cls = CastHostService::class.java.name
            // 先确保自己的宿主 Service 处于"可被 bind"的活状态（部分 ROM 要求先 start）
            runCatching {
                context.startService(Intent(context, CastHostService::class.java))
            }.onFailure { Log.w(TAG, "CASTHOST: 自身 Service 预启动失败 $it") }

            targetPackage = carLifePackage
            val intent = Intent().apply {
                setClassName(carLifePackage, CARLIFE_CONNECT_SERVICE)
                putExtra("bindPackageName", pkg)
                putExtra("bindClassName", cls)
                putExtra(KEY_USB_TYPE, usbType)
                // 这两个是 CarLife 里用于日志/协议选择的辅助字段，缺省也能走，但带上更接近真实宿主
                putExtra("bindVersion", "1.0")
                putExtra("protocolType", 0)
            }
            return runCatching {
                context.startService(intent)
                Log.i(TAG, "CASTHOST: → startService $carLifePackage/$CARLIFE_CONNECT_SERVICE usb_type=$usbType host=$pkg/$cls")
                true
            }.getOrElse {
                Log.w(TAG, "CASTHOST: startService 失败 $it")
                false
            }
        }

        /** 断开：让 CarLife 解绑宿主（它内部会 unBindService + 结束会话）。 */
        fun stopCarLifeSession(context: Context, carLifePackage: String): Boolean = runCatching {
            targetPackage = carLifePackage
            val intent = Intent().apply {
                setClassName(carLifePackage, CARLIFE_CONNECT_SERVICE)
                action = "com.baidu.carlife.action.STOP_CAST"
            }
            context.stopService(intent)
            true
        }.getOrElse { false }
    }
}
