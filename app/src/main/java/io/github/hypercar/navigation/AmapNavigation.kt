package io.github.hypercar.navigation

import android.app.Notification
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import io.github.hypercar.session.HyperCarRepo

/** Reuses the existing notification permission; never binds a private AMap partner service. */
internal object AmapNavigation {
    private val store = NavigationCueStore()
    private var reportedUnsupported = false
    private var nextLogAt = 0L

    fun posted(notification: StatusBarNotification) = update(notification, store.generation(), fromActiveSnapshot = false)

    fun activeSnapshot(notifications: Array<StatusBarNotification>) {
        val generation = store.generation()
        notifications.asSequence().filter { it.packageName == "com.autonavi.minimap" }
            .sortedByDescending { it.postTime }
            .forEach { update(it, generation, fromActiveSnapshot = true) }
        HudSourceWrites.publish { store.finishActiveSnapshot(generation) }
    }

    private fun update(notification: StatusBarNotification, generation: Long, fromActiveSnapshot: Boolean) {
        if (notification.packageName != "com.autonavi.minimap") return
        if (!HyperCarRepo.hudNavigationAllowed || HyperCarRepo.carWithHudNavigationAllowed) { clear(); return }
        val n = notification.notification
        val extras = n.extras
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString() } +
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        val cue = AmapNotificationParser.parse(texts, notification.isOngoing)
        if (cue == null) {
            HudSourceWrites.publish { store.hide(generation, notification.key, notification.postTime) }
            if (!reportedUnsupported) {
                reportedUnsupported = true
                HyperCarRepo.log("HUD: 高德通知没有可识别的导航提示；不推断转向/距离（需通知使用权与导航通知）")
            }
            return
        }
        // Replaying an old notification after a reconnect must not make it fresh again.
        val age = (System.currentTimeMillis() - notification.postTime).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        val updated = now - age
        val accepted = HudSourceWrites.publish {
            (fromActiveSnapshot && store.restoreActiveNotification(generation, notification.key, cue, notification.postTime, now) ||
                store.put(generation, notification.key, cue, updated, notification.postTime)).also {
                if (it) AmapLinkNavigation.observeNotification(now, store.clusterRoute(now))
            }
        }
        if (!accepted) return
        reportedUnsupported = false
        if (now >= nextLogAt) {
            nextLogAt = now + 10_000
            HyperCarRepo.log("HUD: 高德通知更新 state=${cue.state} maneuver=${cue.maneuver} distance=${cue.distanceMeters}m（不记录道路文本）")
        }
    }

    fun removed(notification: StatusBarNotification) {
        if (notification.packageName != "com.autonavi.minimap") return
        HudSourceWrites.publish { store.remove(notification.key, notification.postTime, SystemClock.elapsedRealtime()) }
    }

    fun listenerDisconnected() { HudSourceWrites.publish { store.disconnectListener() } }
    fun clear() { HudSourceWrites.publish { store.clear() } }
    fun clusterRoute(now: Long): ClusterRouteObservation {
        val observation = store.clusterRoute(now)
        return if (HyperCarRepo.hudNavigationAllowed) observation else observation.copy(cue = null)
    }

    fun snapshot(now: Long = SystemClock.elapsedRealtime()): NavigationCue? = timedSnapshot(now)?.cue

    fun timedSnapshot(now: Long): TimedNavigationCue? {
        if (!HyperCarRepo.hudNavigationAllowed) { clear(); return null }
        return store.timedSnapshot(now)
    }
}
