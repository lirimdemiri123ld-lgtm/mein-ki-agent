package ch.lirim.jarvis

import android.content.Context
import android.content.Intent

/** Installierte Apps finden und starten. */
object Apps {
    data class AppInfo(val label: String, val pkg: String)

    fun launchables(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun list(ctx: Context): String =
        "Installierte Apps:\n" + launchables(ctx).joinToString("\n") { "${it.label} (${it.pkg})" }

    fun labelFor(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun open(ctx: Context, name: String): String {
        val q = name.trim().lowercase()
        val apps = launchables(ctx)
        val m = apps.firstOrNull { it.pkg.equals(name.trim(), ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(" ", "")) }
            ?: return "App '$name' nicht gefunden. Nutze apps_auflisten, um den genauen Namen zu sehen."
        val intent = ctx.packageManager.getLaunchIntentForPackage(m.pkg)
            ?: return "App ${m.label} kann nicht gestartet werden."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(intent)
        return "App ${m.label} geöffnet."
    }
}
