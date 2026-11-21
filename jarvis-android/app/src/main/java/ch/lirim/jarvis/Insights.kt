package ch.lirim.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Eigenständiges Denken: Jarvis analysiert regelmässig Gewohnheiten, Nachrichten,
 * Wetter, Börse, Projekte und installierte Apps – leitet Tipps und eigene Erkenntnisse ab.
 */
object Insights {
    private const val JOB_ID = 4711
    @Volatile var running = false
        private set
    var listener: (() -> Unit)? = null

    fun schedule(c: Context) {
        try { scheduleInner(c) } catch (e: Exception) { Agent.log("Hintergrund-Analyse nicht planbar: ${e.message}") }
    }

    private fun scheduleInner(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (!Prefs.proactive(c)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, InsightJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(3 * 60 * 60 * 1000L)
                .setPersisted(false)
                .build()
        )
    }

    fun isStale(c: Context): Boolean {
        val now = System.currentTimeMillis()
        return now - Memory.insightTs > 2 * 3600_000L && now - Prefs.lastInsightTry(c) > 2 * 3600_000L
    }

    private fun useLocal(c: Context): Boolean {
        if (!LocalBrain.available(c)) return false
        val mode = Prefs.brain(c)
        return mode != "cloud" || Prefs.apiKey(c).isBlank() || System.currentTimeMillis() < Prefs.cloudBlockedUntil(c)
    }

    /** Blockierend – im Hintergrund aufrufen. */
    fun run(c: Context, notify: Boolean): Boolean {
        val key = Prefs.apiKey(c)
        val local = useLocal(c)
        if (running) return false
        if (local && (Agent.running || LocalBrain.isBusy())) return false
        if (!local && (key.isBlank() || System.currentTimeMillis() < Prefs.cloudBlockedUntil(c))) return false
        running = true
        Prefs.setLastInsightTry(c, System.currentTimeMillis())
        try {
            try { Weather.fetch(c) } catch (_: Exception) {}
            try { Stocks.fetch(c) } catch (_: Exception) {}
            val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
            val apps = try { Apps.launchables(c).joinToString(", ") { it.label } } catch (e: Exception) { "" }
            val system = """
Du bist Jarvis, eine hochentwickelte persönliche KI von Lirim. Du arbeitest gerade im Hintergrund – ohne dass er dich gefragt hat.
Deine Aufgabe: Denke selbstständig nach. Erkenne Muster in seinen Gewohnheiten, Nachrichten und Projekten, entdecke neue Möglichkeiten (z. B. was du mit seinen installierten Apps für ihn automatisieren könntest, nützliche Funktionen, die er vermutlich nicht nutzt, Dinge aus aktuellen Nachrichten/Web, die zu seinen Interessen passen) und gib ihm konkrete, wertvolle Tipps.
Sei spezifisch, nicht generisch. Keine Wiederholungen von früheren Tipps. Schweizer Rechtschreibung (ss statt ß).
Antworte NUR mit JSON in genau diesem Format:
{"briefing":"2–3 Sätze Lagebericht für jetzt","tipps":[{"titel":"kurz","text":"1–2 Sätze, konkret umsetzbar"}],"erkenntnisse":["neue dauerhafte Erkenntnis über Lirim oder seine Muster, nur wenn wirklich belegt"],"meldung":"optional: eine einzelne Push-Nachricht, nur wenn etwas wirklich wichtig/zeitkritisch ist, sonst leer"}
Höchstens 4 Tipps, höchstens 2 Erkenntnisse. Nie Passwörter oder Kontodaten.
""".trimIndent()
            val (oldBrief, oldTips) = Memory.insights(c)
            val user = buildString {
                append("Jetzt: $now\n\n")
                append("Wetter: ${Weather.summary()}\n")
                append("Börse: ${Stocks.summary()}\n")
                val s = SystemStats.snapshot(c)
                append("Handy: Akku ${s.battery}%${if (s.charging) " (lädt)" else ""}, Speicher ${s.storageText}\n\n")
                append("Neueste Benachrichtigungen:\n${NotificationStore.asText(15)}\n\n")
                append("Installierte Apps: $apps\n\n")
                append("Frühere Tipps (nicht wiederholen): ${oldTips.joinToString("; ") { it.title }}\n")
                append("Früherer Lagebericht: $oldBrief\n")
                append(Memory.promptBlock(c))
            }
            val text: String
            if (local) {
                text = LocalBrain.chat(c, system, user.take(6000))
            } else {
            val tools = JSONArray()
            if (Prefs.webSearch(c)) tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2))
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", user))
            val resp = try {
                ClaudeClient.send(key, Prefs.model(c), system, tools, msgs)
            } catch (e: ApiException) {
                if (tools.length() > 0) ClaudeClient.send(key, Prefs.model(c), system, JSONArray(), msgs) else throw e
            }
            val content = resp.getJSONArray("content")
            var t = ""
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") t = b.optString("text")
            }
            text = t
            }
            val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            val tips = mutableListOf<Memory.Tip>()
            json.optJSONArray("tipps")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    tips.add(Memory.Tip(o.optString("titel"), o.optString("text")))
                }
            }
            Memory.setInsights(c, json.optString("briefing"), tips)
            json.optJSONArray("erkenntnisse")?.let { a ->
                for (i in 0 until a.length()) Memory.remember(c, a.getString(i), "erkenntnis")
            }
            Agent.log("💡 Neue Analyse: ${tips.size} Tipps")
            val msg = json.optString("meldung")
            if (notify && Prefs.proactive(c)) {
                val first = if (msg.isNotBlank()) msg else tips.firstOrNull()?.let { "${it.title}: ${it.text}" }
                if (first != null) postNotification(c, first)
            }
            listener?.invoke()
            return true
        } catch (e: Exception) {
            if (e.message?.contains("credit", ignoreCase = true) == true) {
                Prefs.setCloudBlockedUntil(c, System.currentTimeMillis() + 6 * 3600_000L)
                Agent.log("Analyse: Claude-Guthaben leer – nächster Versuch später oder lokal.")
            } else Agent.log("Analyse fehlgeschlagen: ${e.message?.take(160)}")
            return false
        } finally {
            running = false
        }
    }

    private fun postNotification(c: Context, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis_tips", "Jarvis Tipps", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "jarvis_tips")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Jarvis hat etwas für dich")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(99, n) } catch (_: SecurityException) {}
    }
}

class InsightJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            Insights.run(applicationContext, notify = true)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
