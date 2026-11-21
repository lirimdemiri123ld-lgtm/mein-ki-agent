package ch.lirim.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Langzeitgedächtnis von Jarvis (lokal auf dem Handy, Datei memory.json):
 *  - Fakten über Lirim (Personen, Vorlieben, Routinen …)
 *  - Erfahrungen pro App (wie man dort etwas erledigt)
 *  - Verlauf der letzten Aufträge
 */
object Memory {
    private const val MAX_FACTS = 150
    private const val MAX_LESSONS_PER_APP = 10
    private const val MAX_HISTORY = 30

    data class Fact(val text: String, val cat: String, val ts: Long)
    data class Entry(val kind: String, val key: String, val text: String)

    private var loaded = false
    private val facts = mutableListOf<Fact>()
    private val lessons = linkedMapOf<String, MutableList<String>>()
    private val history = mutableListOf<Pair<String, String>>()
    private var file: File? = null

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        file = File(c.applicationContext.filesDir, "memory.json")
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.optJSONArray("facts")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    facts.add(Fact(x.getString("text"), x.optString("cat", "fakt"), x.optLong("ts")))
                }
            }
            o.optJSONObject("lessons")?.let { l ->
                for (k in l.keys()) {
                    val a = l.getJSONArray(k)
                    lessons[k] = MutableList(a.length()) { a.getString(it) }
                }
            }
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    history.add(x.getString("when") + " – " + x.getString("cmd") to x.optString("result"))
                }
            }
        } catch (e: Exception) {
            Agent.log("Gedächtnis konnte nicht gelesen werden: ${e.message}")
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("facts", JSONArray().apply {
            facts.forEach { put(JSONObject().put("text", it.text).put("cat", it.cat).put("ts", it.ts)) }
        })
        o.put("lessons", JSONObject().apply { lessons.forEach { (k, v) -> put(k, JSONArray(v)) } })
        o.put("history", JSONArray().apply {
            history.forEach { (wc, r) ->
                val parts = wc.split(" – ", limit = 2)
                put(JSONObject().put("when", parts[0]).put("cmd", parts.getOrElse(1) { "" }).put("result", r))
            }
        })
        val tmp = File(f.parentFile, "memory.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun norm(s: String) = s.lowercase().trim()

    @Synchronized
    fun remember(c: Context, text: String, cat: String): String {
        load(c)
        val t = text.trim()
        if (t.isEmpty()) return "Nichts zu merken."
        if (facts.any { norm(it.text) == norm(t) }) return "War schon gespeichert."
        facts.add(Fact(t, cat.ifBlank { "fakt" }, System.currentTimeMillis()))
        while (facts.size > MAX_FACTS) facts.removeAt(0)
        save()
        Agent.log("🧠 Gemerkt: $t")
        return "Gespeichert."
    }

    @Synchronized
    fun forget(c: Context, query: String): String {
        load(c)
        val q = norm(query)
        if (q.isEmpty()) return "Was soll ich vergessen?"
        val before = facts.size
        facts.removeAll { norm(it.text).contains(q) }
        var removed = before - facts.size
        for ((_, list) in lessons) {
            val b = list.size
            list.removeAll { norm(it).contains(q) }
            removed += b - list.size
        }
        lessons.entries.removeAll { it.value.isEmpty() }
        save()
        Agent.log("🧠 Vergessen ($removed): $query")
        return if (removed > 0) "$removed Einträge gelöscht." else "Nichts Passendes gefunden."
    }

    @Synchronized
    fun learn(c: Context, app: String, tip: String): String {
        load(c)
        val key = norm(app)
        val t = tip.trim()
        if (key.isEmpty() || t.isEmpty()) return "App und Tipp nötig."
        val list = lessons.getOrPut(key) { mutableListOf() }
        if (list.any { norm(it) == norm(t) }) return "Schon bekannt."
        list.add(t)
        while (list.size > MAX_LESSONS_PER_APP) list.removeAt(0)
        save()
        Agent.log("📚 Erfahrung ($app): $t")
        return "Erfahrung gespeichert."
    }

    @Synchronized
    fun lessonsFor(c: Context, app: String): String {
        load(c)
        val q = norm(app)
        if (q.isEmpty()) return ""
        val hits = lessons.filterKeys { it.contains(q) || q.contains(it) }
        if (hits.isEmpty()) return ""
        return hits.entries.joinToString("\n") { (k, v) -> v.joinToString("\n") { "- [$k] $it" } }
    }

    @Synchronized
    fun addHistory(c: Context, cmd: String, result: String) {
        load(c)
        val now = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())
        history.add("$now – ${cmd.take(200)}" to result.take(200))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        save()
    }

    /** Text für den System-Prompt. */
    @Synchronized
    fun promptBlock(c: Context): String {
        load(c)
        val sb = StringBuilder()
        sb.append("\n\n## Dein Gedächtnis über Lirim\n")
        if (facts.isEmpty()) sb.append("(noch leer)\n")
        else facts.groupBy { it.cat }.forEach { (cat, list) ->
            sb.append("$cat:\n")
            list.forEach { sb.append("- ").append(it.text).append('\n') }
        }
        if (lessons.isNotEmpty()) {
            sb.append("\n## Apps, zu denen du Erfahrungen hast\n")
            sb.append(lessons.keys.joinToString(", ")).append(" (Details kommen beim Öffnen der App)\n")
        }
        if (history.isNotEmpty()) {
            sb.append("\n## Letzte Aufträge\n")
            history.takeLast(8).forEach { (wc, r) -> sb.append("- ").append(wc).append(" → ").append(r).append('\n') }
        }
        return sb.toString()
    }

    /** Für die Gedächtnis-Ansicht. */
    @Synchronized
    fun entries(c: Context): List<Entry> {
        load(c)
        val out = mutableListOf<Entry>()
        facts.forEach { out.add(Entry("fakt", it.cat, it.text)) }
        lessons.forEach { (k, v) -> v.forEach { out.add(Entry("app", k, it)) } }
        history.asReversed().forEach { (wc, r) -> out.add(Entry("verlauf", wc, r)) }
        return out
    }

    @Synchronized
    fun delete(c: Context, e: Entry) {
        load(c)
        when (e.kind) {
            "fakt" -> facts.removeAll { it.text == e.text }
            "app" -> { lessons[e.key]?.remove(e.text); lessons.entries.removeAll { it.value.isEmpty() } }
            "verlauf" -> history.removeAll { it.first == e.key && it.second == e.text }
        }
        save()
    }

    @Synchronized
    fun clearAll(c: Context) {
        load(c)
        facts.clear(); lessons.clear(); history.clear()
        save()
    }
}
