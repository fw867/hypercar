package io.github.hypercar.spike

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tracks all active subscriptions only when PSTN audio is requested in the shell service. */
internal class ShellCallStateMonitor(
    private val identity: ShellCallIdentity,
    private val trace: (String) -> Unit,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hypercar-precise-call-state").apply { isDaemon = true }
    }
    private lateinit var registry: ShellTelephonyRegistry
    private var subscriptionsListener: Any? = null
    private val callListeners = mutableMapOf<Int, AutoCloseable>()

    init {
        worker.execute {
            var stage = "registry_lookup"
            runCatching {
                registry = ShellTelephonyRegistry()
                stage = "registry_death_watch"
                registry.linkToDeath {
                    identity.beginTracking()
                    trace("precise registry=lost")
                    Log.w("HyperCar", "CALL-AUDIO: precise call state service lost")
                }
                stage = "watch_subscriptions"
                subscriptionsListener = registry.watchSubscriptions(::subscriptionsChanged)
                trace("precise registry=ready subscription_listener=registered")
                // Some devices defer the first subscription notification until a SIM update.
                subscriptionsChanged()
            }.onFailure { logUnavailable(stage, it) }
        }
    }

    private fun subscriptionsChanged() {
        // The Binder callback revokes immediately; reconciling registrations can require IPC.
        val generation = identity.beginTracking()
        worker.execute { refreshSubscriptions(generation) }
    }

    private fun refreshSubscriptions(generation: Long) {
        var stage = "query_active_subscriptions"
        runCatching {
            val subscriptions = identity.queryTrackedSubscriptions(generation, registry::activeSubscriptions) ?: return
            // This worker owns the listener table. An older queued refresh must leave it alone.
            stage = "unregister_old"
            unregisterCallListeners()
            trace("precise generation=$generation active_subscriptions=$subscriptions")
            for (subscription in subscriptions) {
                stage = "register_sub_$subscription"
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
                    { reason -> trace("precise generation=$generation sub=$subscription state_unreadable=$reason") },
                )
                trace("precise generation=$generation sub=$subscription state_listener=registered disconnect_listener=registered")
            }
            if (identity.trackingReady(generation)) {
                Log.i("HyperCar", "CALL-AUDIO: precise call state listeners registered count=${subscriptions.size}")
            }
        }.onFailure { failure ->
            runCatching { unregisterCallListeners() }
            logUnavailable("generation=$generation stage=$stage", failure)
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

    private fun logUnavailable(stage: String, failure: Throwable) {
        val reason = (failure.cause ?: failure).javaClass.simpleName
        trace("precise registration_failed $stage error=$reason")
        Log.w("HyperCar", "CALL-AUDIO: precise call state unavailable $stage $reason")
    }
}
