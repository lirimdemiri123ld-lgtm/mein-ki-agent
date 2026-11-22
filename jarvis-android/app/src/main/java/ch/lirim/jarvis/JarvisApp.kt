package ch.lirim.jarvis

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fängt Abstürze ab und speichert sie, damit sie beim nächsten Start angezeigt werden. */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Prefs.migrate(this) } catch (_: Exception) {}
        // Die lokalen KI-Modelle (mehrere GB) werden nicht mehr gebraucht → Speicher freigeben
        Thread {
            try {
                val dir = File(getExternalFilesDir(null) ?: filesDir, "models")
                if (dir.exists()) {
                    val mb = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576
                    dir.deleteRecursively()
                    if (mb > 0) Agent.log("🧹 Alte lokale KI-Modelle entfernt: $mb MB frei.")
                }
            } catch (_: Throwable) {}
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val now = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())
                File(filesDir, "crash.txt").writeText(
                    "Jarvis ${BuildConfigLite.version(this)} – $now – Thread ${t.name}\n\n$sw"
                )
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "crash.txt")
    }
}

object BuildConfigLite {
    fun version(c: android.content.Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
