package com.icode.asmviewer.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val launchable: Boolean,
)

object InstalledApps {
    /**
     * Visible packages only. Launchable apps come from MAIN/LAUNCHER queries;
     * QUERY_ALL_PACKAGES (sideload / non-store) may add more. Never log the list.
     */
    fun loadVisibleApps(pm: PackageManager, selfPackage: String): List<InstalledAppEntry> {
        val byPackage = linkedMapOf<String, InstalledAppEntry>()

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        for (ri in pm.queryIntentActivities(launcher, flags)) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            byPackage[ai.packageName] = toEntry(pm, ai, launchable = true)
        }

        try {
            val apps = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (ai in apps) {
                if (ai.packageName in byPackage) continue
                if (ai.sourceDir.isNullOrBlank()) continue
                byPackage[ai.packageName] = toEntry(pm, ai, launchable = false)
            }
        } catch (_: Exception) {
            // Visibility limited; keep launchable entries only.
        }

        if (selfPackage !in byPackage) {
            try {
                val ai = pm.getApplicationInfo(selfPackage, 0)
                byPackage[selfPackage] = toEntry(pm, ai, launchable = true)
            } catch (_: Exception) {
            }
        }
        return byPackage.values.sortedWith(
            compareBy<InstalledAppEntry> { it.packageName != selfPackage }
                .thenBy { it.label.lowercase() },
        )
    }

    fun apkContainersFor(entry: InstalledAppEntry): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        val base = File(entry.sourceDir)
        out += base to (base.name.ifBlank { "base.apk" })
        for (split in entry.splitSourceDirs) {
            val f = File(split)
            if (f.isFile) out += f to (f.name.ifBlank { "split.apk" })
        }
        return out
    }

    private fun toEntry(pm: PackageManager, ai: ApplicationInfo, launchable: Boolean): InstalledAppEntry {
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
        val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(ai)) }.getOrNull()
        val splits = ai.splitSourceDirs?.filter { it.isNotBlank() } ?: emptyList()
        return InstalledAppEntry(
            packageName = ai.packageName,
            label = label,
            icon = icon,
            sourceDir = ai.sourceDir.orEmpty(),
            splitSourceDirs = splits,
            launchable = launchable,
        )
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }
}
