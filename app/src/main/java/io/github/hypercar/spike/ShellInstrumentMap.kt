package io.github.hypercar.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.os.SharedMemory
import android.os.UserHandle
import android.view.SurfaceControl
import io.github.hypercar.carui.MapProvider
import io.github.hypercar.carui.instrumentMapWindow
import io.github.hypercar.carui.readInstrumentMap

/** No composed-display readback: SurfaceFlinger filters by UID during composition. */
internal object ShellInstrumentMap {
    fun capture(
        context: Context, id: Int, width: Int, height: Int, maxWidth: Int, maxHeight: Int,
        pixels: SharedMemory, bound: () -> Boolean,
    ): IntArray? {
        if (!bound() || maxWidth !in 1..960 || maxHeight !in 1..540 ||
            width !in 1..16384 || height !in 1..16384 || pixels.size != maxWidth * maxHeight * 4) return null
        val pkg = MapProvider.AMAP.packageName
        val pm = context.packageManager
        val uid = pm.getApplicationInfo(pkg, 0).uid
        // Shared UIDs cannot distinguish other packages' layers.
        if (uid < android.os.Process.FIRST_APPLICATION_UID || pm.getPackagesForUid(uid)?.singleOrNull() != pkg) return null
        val user = UserHandle::class.java.getMethod("getUserId", Int::class.javaPrimitiveType).invoke(null, uid) as Int
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        return readInstrumentMap(uid, user,
            window = { focusedWindow(id)?.takeIf { bound() } },
            captureUid = { ownerUid -> captureLayers(id, width, height, maxWidth, maxHeight, scale, ownerUid) },
            publish = publish@{ image ->
                if (image.rowBytes != image.width * 4) return@publish null
                val buffer = pixels.mapReadWrite()
                try {
                    image.copyPixelsToBuffer(buffer)
                    buffer.flip()
                    if (!hasMapPixels(buffer.asIntBuffer())) return@publish null
                } finally { SharedMemory.unmap(buffer) }
                intArrayOf(image.width, image.height)
            }, release = Bitmap::recycle)
    }

    private fun focusedWindow(id: Int): io.github.hypercar.carui.InstrumentMapWindow? {
        val process = ProcessBuilder("/system/bin/timeout", "2", "/system/bin/dumpsys", "-t", "1", "window", "displays")
            .redirectErrorStream(true).start()
        return try {
            val focus = process.inputStream.bufferedReader().useLines { instrumentMapWindow(it, id) }
            focus.takeIf { process.waitFor() == 0 }
        } finally { process.destroy() }
    }

    private fun captureLayers(id: Int, w: Int, h: Int, maxW: Int, maxH: Int, scale: Float, uid: Int): Bitmap? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "window") as IBinder
        val windowApi = Class.forName("android.view.IWindowManager")
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val surfaceType = SurfaceControl::class.java
        val mirror = surfaceType.getConstructor().newInstance()
        try {
            val created = windowApi.getMethod("mirrorDisplay", Int::class.javaPrimitiveType, surfaceType)
                .invoke(wm, id, mirror)
            if (created != true || !mirror.isValid) return null
            val argsType = if (Build.VERSION.SDK_INT < 34) Class.forName("android.view.SurfaceControl\$LayerCaptureArgs")
                else try { Class.forName("android.window.ScreenCaptureInternal\$LayerCaptureArgs") }
                catch (_: ClassNotFoundException) { Class.forName("android.window.ScreenCapture\$LayerCaptureArgs") }
            val api = argsType.declaringClass ?: return null
            val builderType = Class.forName(argsType.name + "\$Builder")
            val builder = builderType.getConstructor(surfaceType).newInstance(mirror)
            builderType.getMethod("setSourceCrop", Rect::class.java).invoke(builder, Rect(0, 0, w, h))
            builderType.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, scale)
            val args = filteredArgs(builder, uid)
            val shot = api.getMethod("captureLayers", argsType).invoke(null, args) ?: return null
            val shotType = shot.javaClass
            val buffer = shotType.getMethod("getHardwareBuffer").invoke(shot) as? HardwareBuffer ?: return null
            buffer.use {
                if (buffer.width !in 1..maxW || buffer.height !in 1..maxH ||
                    kotlin.math.abs(buffer.width - w * scale) > 1f || kotlin.math.abs(buffer.height - h * scale) > 1f ||
                    shotType.getMethod("containsSecureLayers").invoke(shot) != false) return null
                val image = shotType.getMethod("asBitmap").invoke(shot) as? Bitmap ?: return null
                try { return image.copy(Bitmap.Config.ARGB_8888, false) } finally { image.recycle() }
            }
        } finally { mirror.release() }
    }

    internal fun filteredArgs(builder: Any, uid: Int, policyValues: () -> Class<*> = {
        Class.forName("android.window.ScreenCapture\$ScreenCaptureParams")
    }): Any {
        require(uid >= android.os.Process.FIRST_APPLICATION_UID)
        val type = builder.javaClass
        type.getMethod("setUid", Long::class.javaPrimitiveType).invoke(builder, uid.toLong())
        val legacy = try { type.getMethod("setCaptureSecureLayers", Boolean::class.javaPrimitiveType) }
            catch (_: NoSuchMethodException) { null }
        if (legacy != null) legacy.invoke(builder, false)
        else {
            val policy = policyValues()
            type.getMethod("setSecureContentPolicy", Int::class.javaPrimitiveType).invoke(builder,
                policy.getField("SECURE_CONTENT_POLICY_REDACT").getInt(null))
            type.getMethod("setProtectedContentPolicy", Int::class.javaPrimitiveType).invoke(builder,
                policy.getField("PROTECTED_CONTENT_POLICY_REDACT").getInt(null))
        }
        return type.getMethod("build").invoke(builder)
    }

    internal fun hasMapPixels(pixels: java.nio.IntBuffer): Boolean {
        if (!pixels.hasRemaining()) return false
        val first = pixels.get()
        while (pixels.hasRemaining()) if (pixels.get() != first) return true
        return false
    }
}
