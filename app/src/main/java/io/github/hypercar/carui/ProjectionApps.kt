package io.github.hypercar.carui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import java.text.Collator

internal object ProjectionApps {
    data class Candidate(val packageName: String, val label: String)

    fun eligiblePackages(packages: Iterable<String>, ownPackage: String): List<String> =
        packages.filter { pkg ->
            pkg.isNotBlank() && pkg != ownPackage && pkg != "com.miui.carlink" &&
                MapProvider.entries.none { it.packageName == pkg } &&
                !MusicApps.isKnownPlayer(pkg) && !ShortVideoApps.isSupported(pkg)
        }.distinct()

    private fun launcherActivities(context: Context, packageName: String? = null): List<ActivityInfo> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        return pm.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo }
            .filter { it.exported }
    }

    fun launcherComponent(context: Context, packageName: String): ComponentName? =
        launcherActivities(context, packageName).firstOrNull()?.let { ComponentName(it.packageName, it.name) }

    fun candidates(context: Context): List<Candidate> = candidates(context, selected = null)

    private fun candidates(context: Context, selected: Set<String>?): List<Candidate> {
        val pm = context.packageManager
        val activities = launcherActivities(context).distinctBy { it.packageName }.associateBy { it.packageName }
        val packages = if (selected == null) activities.keys else activities.keys.intersect(selected)
        val collator = Collator.getInstance()
        return eligiblePackages(packages, context.packageName).mapNotNull { pkg ->
            runCatching {
                val label = pm.getApplicationLabel(activities.getValue(pkg).applicationInfo).toString()
                Candidate(pkg, label.ifBlank { pkg })
            }.getOrNull()
        }.sortedWith(compareBy(collator) { it.label })
    }

    fun installed(context: Context, selected: Set<String>): List<MusicApps.Entry> =
        if (selected.isEmpty()) emptyList() else candidates(context, selected).map { app ->
            MusicApps.Entry(app.packageName, app.label, MusicApps.iconFor(context, app.packageName))
        }
}
