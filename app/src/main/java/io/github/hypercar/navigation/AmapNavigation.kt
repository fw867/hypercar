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

    fun posted(notification: StatusBarNotification) {
        if (notification.packageName != "com.autonavi.minimap") return
        if (!HyperCarRepo.hudNavigationAllowed || HyperCarRepo.carWithHudNavigationAllowed) { clear(); return }
        val generation = store.generation()
        val n = notification.notification
        val extras = n.extras
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString() } +
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
        val cue = AmapNotificationParser.parse(texts, notification.isOngoing)
        if (cue == null) {
            store.remove(notification.key) // do not retain a superseded instruction
            if (!reportedUnsupported) {
                reportedUnsupported = true
                HyperCarRepo.log("HUD: 高德通知没有可识别的导航提示；不推断转向/距离（需通知使用权与导航通知）")
            }
            return
        }
        // Replaying an old notification after a reconnect must not make it fresh again.
        val age = (System.currentTimeMillis() - notification.postTime).coerceAtLeast(0)
        store.put(generation, notification.key, cue, SystemClock.elapsedRealtime() - age)
        reportedUnsupported = false
        val now = SystemClock.elapsedRealtime()
        if (now >= nextLogAt) {
            nextLogAt = now + 10_000
            HyperCarRepo.log("HUD: 高德通知更新 state=${cue.state} maneuver=${cue.maneuver} distance=${cue.distanceMeters}m（不记录道路文本）")
        }
    }

    fun removed(notification: StatusBarNotification) {
        store.remove(notification.key)
    }

    fun clear() { store.clear() }

    fun snapshot(now: Long = SystemClock.elapsedRealtime()): NavigationCue? {
        if (!HyperCarRepo.hudNavigationAllowed) { clear(); return null }
        return store.snapshot(now)
    }
}
