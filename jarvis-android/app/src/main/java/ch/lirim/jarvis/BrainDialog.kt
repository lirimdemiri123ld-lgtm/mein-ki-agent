package ch.lirim.jarvis

import android.app.Activity
import android.app.AlertDialog

/** Spar-Übersicht: Kosten, gratis erledigte Aufträge und gelernte Abläufe. */
object BrainDialog {
    fun show(a: Activity) {
        val all = RecipeStore.all(a).sortedByDescending { it.successes }
        val info = "Modell: ${Prefs.model(a)}\n(Sonnet nur, wenn du «gründlich …» sagst)\n\n" +
            "Claude-Kosten diesen Monat: ≈ ${Agent.rp(Prefs.monthCost(a))}\n" +
            "Gratis erledigt (ohne Claude): ${Prefs.savedTasks(a)}\n" +
            "Gelernte Abläufe: ${all.size}"
        val b = AlertDialog.Builder(a).setTitle("Gehirn · Spar-Übersicht").setPositiveButton("Fertig", null)
        if (all.isEmpty()) {
            b.setMessage("$info\n\nNoch nichts gelernt. Was Claude einmal erledigt hat, spielt Jarvis danach selbst ab – gratis.")
        } else {
            val items = listOf(info) + all.map { "«${it.template}» · ${it.successes}× geklappt (tippen = löschen)" }
            b.setItems(items.toTypedArray()) { _, i ->
                if (i > 0) { RecipeStore.delete(a, all[i - 1].template); show(a) }
            }
        }
        b.show()
    }
}
