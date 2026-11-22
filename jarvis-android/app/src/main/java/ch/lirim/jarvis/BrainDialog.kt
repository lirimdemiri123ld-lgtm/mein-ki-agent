package ch.lirim.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Gehirn wählen: Hybrid / nur lokal / nur Claude, und lokale Modelle verwalten. */
object BrainDialog {

    fun show(a: Activity) {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val h = Handler(Looper.getMainLooper())
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), px(8)) }

        box.addView(TextView(a).apply { text = "Denkmodus"; textSize = 15f })
        val group = RadioGroup(a)
        val modes = listOf(
            "hybrid" to "Spar-Hybrid – Gelerntes & Plaudern gratis, neue App-Aufgaben mit Claude Haiku (empfohlen)",
            "local" to "Nur lokal – nie Guthaben, offline (langsam)",
            "cloud" to "Nur Claude – auch Plaudern über Claude"
        )
        modes.forEachIndexed { i, (key, label) ->
            group.addView(RadioButton(a).apply {
                id = 1000 + i; text = label; isChecked = Prefs.brain(a) == key
            })
        }
        group.setOnCheckedChangeListener { _, id -> Prefs.setBrain(a, modes[id - 1000].first) }
        box.addView(group)

        box.addView(android.widget.CheckBox(a).apply {
            text = "Jarvis wählt das Modell selbst (einfach → schnell, komplex → schlau)"
            isChecked = Prefs.autoModel(a)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoModel(a, v) }
        })
        // Spar-Übersicht
        val recipes = RecipeStore.count(a)
        box.addView(TextView(a).apply {
            text = "\nSpar-Übersicht diesen Monat\n" +
                "Claude-Kosten: ≈ ${Agent.rp(Prefs.monthCost(a))}\n" +
                "Gratis erledigt (ohne Claude): ${Prefs.savedTasks(a)}\n" +
                "Gelernte Abläufe: $recipes – die spielt Jarvis selbst ab"
            textSize = 14f
        })
        if (recipes > 0) box.addView(Button(a).apply {
            isAllCaps = false; text = "Gelernte Abläufe ansehen"
            setOnClickListener {
                val all = RecipeStore.all(a).sortedByDescending { it.successes }
                val labels = all.map { "«${it.template}» · ${it.successes}× ok" }.toTypedArray()
                AlertDialog.Builder(a).setTitle("Gelernte Abläufe (tippen = löschen)")
                    .setItems(labels) { _, i -> RecipeStore.delete(a, all[i].template) }
                    .setNegativeButton("Schliessen", null).show()
            }
        })
        box.addView(TextView(a).apply {
            text = "\nLokale Gehirne (werden in den Handyspeicher geladen)\nGelernte Fähigkeiten: ${Memory.skillCount(a)}"; textSize = 15f
        })
        val free = (a.getExternalFilesDir(null)?.usableSpace ?: 0L) / 1e9
        box.addView(TextView(a).apply { text = "Frei: %.0f GB".format(free); textSize = 12f })

        val updaters = mutableListOf<() -> Unit>()
        for (m in LocalBrain.models) {
            val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(12), 0, 0) }
            val title = TextView(a).apply { textSize = 14f }
            val desc = TextView(a).apply { text = "${m.desc} · ${m.gb} · ab ${m.minRamGb} GB RAM"; textSize = 12f }
            val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val btn = Button(a).apply { isAllCaps = false }
            val del = Button(a).apply { isAllCaps = false; text = "Löschen" }
            row.addView(btn); row.addView(del)
            card.addView(title); card.addView(desc); card.addView(bar); card.addView(row)
            box.addView(card)

            val update = {
                val installed = LocalBrain.isInstalled(a, m)
                val p = LocalBrain.progress(a, m)
                val active = LocalBrain.active(a)?.id == m.id
                title.text = (if (active) "✓ " else "") + m.title
                bar.visibility = if (!installed && p in 0..99) android.view.View.VISIBLE else android.view.View.GONE
                if (p in 0..99) bar.progress = p
                del.visibility = if (installed || p in 0..99) android.view.View.VISIBLE else android.view.View.GONE
                btn.text = when {
                    installed && active -> "Aktiv"
                    installed -> "Verwenden"
                    p in 0..99 -> "Lädt … $p %"
                    p == -2 -> "Fehler – erneut laden"
                    else -> "Herunterladen (${m.gb})"
                }
                btn.isEnabled = !(installed && active) && p !in 0..99
            }
            btn.setOnClickListener {
                if (LocalBrain.isInstalled(a, m)) {
                    Prefs.setLocalModel(a, m.id)
                    Speaker.say("Lokales Gehirn ${m.title.substringBefore(" ·")} ist aktiv.")
                } else {
                    LocalBrain.startDownload(a, m)
                    Prefs.setLocalModel(a, m.id)
                }
                update()
            }
            del.setOnClickListener {
                AlertDialog.Builder(a).setMessage("${m.title} löschen und ${m.gb} freigeben?")
                    .setPositiveButton("Löschen") { _, _ -> LocalBrain.delete(a, m); update() }
                    .setNegativeButton("Abbrechen", null).show()
            }
            update()
            updaters.add(update)
        }
        box.addView(TextView(a).apply {
            text = "\nTipp: Sag «mit Claude …», um einen Auftrag gezielt an Claude zu geben. Downloads laufen im Hintergrund weiter (Benachrichtigung)."
            textSize = 12f
        })

        val dlg = AlertDialog.Builder(a)
            .setTitle("Gehirn")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Fertig", null)
            .show()
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                updaters.forEach { it() }
                h.postDelayed(this, 1000)
            }
        }
        h.post(tick)
    }
}
