package io.github.hypercar.spike

import android.os.Binder
import android.os.IBinder

/** Runs inside the shell UserService; ordinary app identity cannot change display IME policy. */
internal object ShellDisplayIme {
    private const val LOCAL = 0

    fun configure(displayId: Int, trusted: Boolean): String {
        if (!trusted) return "unavailable(untrusted display)"
        val identity = Binder.clearCallingIdentity()
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val api = Class.forName("android.view.IWindowManager")
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            api.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, displayId, LOCAL)
            val actual = api.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
                .invoke(wm, displayId) as Int
            if (actual == LOCAL) "local" else "unavailable(policy=$actual)"
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            // Keep the display usable on ROMs which deny this operation, but expose the failure.
            "unavailable(${cause.javaClass.simpleName}: ${cause.message})"
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
