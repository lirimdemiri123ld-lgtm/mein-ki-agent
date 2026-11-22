package ch.lirim.jarvis

import android.app.Activity
import android.app.AlertDialog

/** Zeigt, was Jarvis gelernt hat – antippen zum Löschen. */
object MemoryDialog {

    fun show(a: Activity) {
        val entries = Memory.entries(a)
        val labels = entries.map {
            when (it.kind) {
                "fakt" -> "🧠 [${it.key}] ${it.text}"
                "app" -> "📚 [${it.key}] ${it.text}"
                "skill" -> "⚙ ${it.key} → ${it.text}"
                else -> "🕘 ${it.key} → ${it.text}"
            }
        }.toTypedArray()

        val b = AlertDialog.Builder(a)
            .setTitle("Gedächtnis (${entries.size})")
            .setNegativeButton("Schliessen", null)
            .setNeutralButton("Alles löschen") { _, _ ->
                AlertDialog.Builder(a)
                    .setMessage("Wirklich das ganze Gedächtnis löschen?")
                    .setPositiveButton("Löschen") { _, _ -> Memory.clearAll(a) }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        if (entries.isEmpty()) {
            b.setMessage("Noch leer. Erzähl Jarvis etwas, z. B. «Merk dir: Baba ist mein Vater, Kontakt Baba Swisscom.»")
        } else {
            b.setItems(labels) { _, i ->
                val e = entries[i]
                AlertDialog.Builder(a)
                    .setMessage("Diesen Eintrag löschen?\n\n${labels[i]}")
                    .setPositiveButton("Löschen") { _, _ -> Memory.delete(a, e); show(a) }
                    .setNegativeButton("Abbrechen") { _, _ -> show(a) }
                    .show()
            }
        }
        b.show()
    }
}
