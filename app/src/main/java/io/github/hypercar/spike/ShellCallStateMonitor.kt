package io.github.hypercar.spike

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tracks all active subscriptions only when PSTN audio is requested in the shell service. */
internal class ShellCallStateMonitor(private val identity: ShellCallIdentity) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hypercar-precise-call-state").apply { isDaemon = true }
    }
    private lateinit var registry: ShellTelephonyRegistry
    private var subscriptionsListener: Any? = null
    private val callListeners = mutableMapOf<Int, AutoCloseable>()

    init {
        worker.execute {
            runCatching {
                registry = ShellTelephonyRegistry()
                registry.linkToDeath {
                    identity.beginTracking()
                    Log.w("HyperCar", "CALL-AUDIO: precise call state service lost")
                }
                subscriptionsListener = registry.watchSubscriptions(::subscriptionsChanged)
                // Some devices defer the first subscription notification until a SIM update.
                subscriptionsChanged()
            }.onFailure(::logUnavailable)
        }
    }

    private fun subscriptionsChanged() {
        // The Binder callback revokes immediately; reconciling registrations can require IPC.
        val generation = identity.beginTracking()
        worker.execute { refreshSubscriptions(generation) }
    }

    private fun refreshSubscriptions(generation: Long) {
        runCatching {
            unregisterCallListeners()
            val subscriptions = registry.activeSubscriptions()
            if (!identity.trackSubscriptions(generation, subscriptions)) return
            for (subscription in subscriptions) {
                callListeners[subscription] = registry.watchPreciseState(subscription,
                    { ringing, foreground, background ->
                        val roles = PreciseCallRoles(ringing, foreground, background)
                        if (identity.onPreciseCallState(generation, subscription, roles)) {
                            Log.i("HyperCar", "CALL-AUDIO: initial precise call states received count=${subscriptions.size}")
                        }
                    },
                    {
                        if (identity.onDisconnected(generation, subscription)) {
                            scheduleRevalidation(2)
                        }
                    },
                )
            }
            identity.trackingReady(generation)
            Log.i("HyperCar", "CALL-AUDIO: precise call state listeners registered count=${subscriptions.size}")
        }.onFailure { failure ->
            runCatching { unregisterCallListeners() }
            logUnavailable(failure)
        }
    }

    private fun scheduleRevalidation(attempts: Int) {
        val delay = identity.millisUntilRevalidation() ?: return
        worker.schedule({
            identity.current()
            if (attempts > 1) scheduleRevalidation(attempts - 1)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun unregisterCallListeners() {
        val listeners = callListeners.entries.iterator()
        while (listeners.hasNext()) {
            val (_, callback) = listeners.next()
            callback.close()
            listeners.remove()
        }
    }

    private fun logUnavailable(failure: Throwable) {
        Log.w("HyperCar", "CALL-AUDIO: precise call state unavailable ${(failure.cause ?: failure).javaClass.simpleName}")
    }
}
