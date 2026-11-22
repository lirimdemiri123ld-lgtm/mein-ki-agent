package ch.lirim.jarvis

import android.content.Context
import ch.lirim.jarvis.core.Recipes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Speichert gelernte Abläufe im Handyspeicher (recipes.json). */
object RecipeStore {
    private const val MAX = 400
    private val list = mutableListOf<Recipes.Recipe>()
    private var loaded = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "recipes.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(c)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val st = o.getJSONArray("steps")
                val steps = (0 until st.length()).map { j ->
                    val s = st.getJSONObject(j)
                    val args = s.getJSONObject("args")
                    Recipes.Step(s.getString("tool"), args.keys().asSequence().associateWith { args.getString(it) })
                }
                list.add(Recipes.Recipe(o.getString("template"), steps, o.optString("answer", "Erledigt."),
                    o.optInt("ok"), o.optInt("fail"), o.optLong("ts")))
            }
        } catch (e: Exception) {
            Agent.log("Rezepte nicht lesbar: ${e.message}")
        }
    }

    @Synchronized
    private fun save(c: Context) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("template", r.template).put("answer", r.answer).put("ok", r.successes)
                .put("fail", r.failures).put("ts", r.ts)
                .put("steps", JSONArray().apply {
                    r.steps.forEach { s -> put(JSONObject().put("tool", s.tool).put("args", JSONObject(s.args))) }
                }))
        }
        val f = file(c)
        val tmp = File(f.parentFile, "recipes.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun find(c: Context, command: String): Pair<Recipes.Recipe, Map<String, String>>? {
        load(c)
        return Recipes.find(list, command)
    }

    @Synchronized
    fun add(c: Context, r: Recipes.Recipe): Boolean {
        load(c)
        val old = list.indexOfFirst { it.template == r.template }
        if (old >= 0) list[old] = r.copy(successes = list[old].successes) else list.add(r)
        while (list.size > MAX) list.removeAt(list.indices.minByOrNull { list[it].ts } ?: 0)
        save(c)
        return old < 0
    }

    @Synchronized
    fun result(c: Context, template: String, ok: Boolean) {
        load(c)
        val i = list.indexOfFirst { it.template == template }
        if (i < 0) return
        val r = list[i]
        val n = if (ok) r.copy(successes = r.successes + 1, ts = System.currentTimeMillis()) else r.copy(failures = r.failures + 1)
        // Zweimal hintereinander gescheitert und nie geklappt → vergessen
        if (!ok && n.failures >= 2 && n.successes < n.failures) list.removeAt(i) else list[i] = n
        save(c)
    }

    @Synchronized
    fun count(c: Context): Int { load(c); return list.size }

    @Synchronized
    fun all(c: Context): List<Recipes.Recipe> { load(c); return list.toList() }

    @Synchronized
    fun delete(c: Context, template: String) { load(c); list.removeAll { it.template == template }; save(c) }

    @Synchronized
    fun clear(c: Context) { load(c); list.clear(); save(c) }
}
