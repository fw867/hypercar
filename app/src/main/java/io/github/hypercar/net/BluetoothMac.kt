package io.github.hypercar.net

import io.github.hypercar.shizuku.ShizukuTools
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Binder and stdout reads may ignore interruption. Only the snapshot may publish a result. */
internal class BluetoothMacLookup(private val read: () -> String?) {
    suspend fun resolve(): String? = suspendCancellableCoroutine { caller ->
        val task = FutureTask<Unit> {
            val result = try { read() } catch (_: Exception) { null }
            caller.resume(result)
        }
        try {
            worker.execute(task)
            caller.invokeOnCancellation {
                task.cancel(true)
                worker.remove(task)
            }
        } catch (_: RejectedExecutionException) {
            caller.resume(null)
        }
    }

    companion object {
        // Keep a stuck Binder call from accumulating threads or queued work on later invitations.
        private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
            { work -> Thread(work, "bluetooth-mac-query").apply { isDaemon = true } })
    }
}

internal object BluetoothMac {
    private val macPattern = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val lookup = BluetoothMacLookup {
        PhoneIdentitySnapshot.normalize(localAddress()) ?: ShizukuTools.readBluetoothAddress().let {
            if (it.ok) macPattern.find(it.out)?.value else null
        }
    }

    suspend fun resolve(): String? = lookup.resolve()

    @Suppress("DEPRECATION")
    private fun localAddress(): String? = runCatching {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address
    }.getOrNull()
}
