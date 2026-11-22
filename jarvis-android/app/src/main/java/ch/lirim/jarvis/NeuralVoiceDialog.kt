package ch.lirim.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Auswahl und Download der neuronalen Offline-Stimmen. */
object NeuralVoiceDialog {

    fun show(a: Activity) {
        val cat = NeuralVoices.catalog(a)
        if (cat.isEmpty()) {
            Toast.makeText(a, "Kein Stimmenkatalog in dieser Version.", Toast.LENGTH_LONG).show()
            return
        }
        val current = Prefs.neuralVoice(a)
        val labels = mutableListOf("🔈 Systemstimme verwenden" + if (current.isBlank()) "  ✓" else "")
        cat.forEach { v ->
            val inst = NeuralVoices.isInstalled(a, v.id)
            val mark = when {
                v.id == current -> "  ✓ AKTIV"
                inst -> "  · installiert"
                else -> "  · ⬇ ${v.mb()} MB"
            }
            labels.add("${v.lang}  ${v.title}$mark")
        }
        AlertDialog.Builder(a)
            .setTitle("Neuronale Stimmen (offline)")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) {
                    Prefs.setNeuralVoice(a, "")
                    Speaker.say("Ich verwende wieder die Systemstimme.")
                    return@setItems
                }
                val v = cat[i - 1]
                if (NeuralVoices.isInstalled(a, v.id)) choose(a, v) else askDownload(a, v)
            }
            .setNegativeButton("Schliessen", null)
            .show()
    }

    /** Fertige Stil-Pakete: Stimme + Klangfilter + Tempo in einem Schritt. */
    fun applyPack(a: Activity, pack: String) {
        val cat = NeuralVoices.catalog(a)
        val prefs = when (pack) {
            "jarvis" -> listOf("de_DE-thorsten-high", "de_DE-thorsten-medium", "de_DE-karlsson")
            "kitt" -> listOf("de_DE-karlsson", "de_DE-thorsten-medium", "de_DE-thorsten-high")
            else -> emptyList()
        }
        val v = prefs.firstNotNullOfOrNull { p -> cat.firstOrNull { it.id.contains(p) } }
        if (v == null) { Toast.makeText(a, "Passende Stimme nicht im Katalog.", Toast.LENGTH_LONG).show(); return }
        Prefs.setVoiceFx(a, pack)
        val (pitch, rate) = if (pack == "kitt") 1.0f to 0.95f else 1.0f to 0.97f
        Prefs.setVoice(a, Prefs.voiceName(a), pitch, rate, Prefs.voiceStyle(a))
        if (NeuralVoices.isInstalled(a, v.id)) {
            Prefs.setNeuralVoice(a, v.id)
            Speaker.say(packSample(pack))
        } else {
            AlertDialog.Builder(a)
                .setTitle(if (pack == "kitt") "KITT-Stil-Paket" else "Jarvis-Stil-Paket")
                .setMessage("Dafür lade ich die Stimme «${v.title.substringBefore(" –")}» (ca. ${v.mb()} MB). Am besten im WLAN.")
                .setPositiveButton("Herunterladen") { _, _ -> download(a, v, packSample(pack)) }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun packSample(pack: String) = if (pack == "kitt")
        "Hallo Lirim. Alle Systeme laufen innerhalb der Toleranz. Wohin fahren wir heute?"
    else "Guten Tag, Lirim. Alle Systeme sind online. Womit darf ich behilflich sein?"

    private fun NeuralVoices.VoiceInfo.mb() = if (sizeMb > 0) sizeMb.toString() else "?"

    private fun choose(a: Activity, v: NeuralVoices.VoiceInfo) {
        AlertDialog.Builder(a)
            .setTitle(v.title)
            .setMessage("Diese Stimme verwenden?")
            .setPositiveButton("Verwenden") { _, _ ->
                Prefs.setNeuralVoice(a, v.id)
                Speaker.say(sample(v))
            }
            .setNeutralButton("Löschen") { _, _ ->
                if (Prefs.neuralVoice(a) == v.id) Prefs.setNeuralVoice(a, "")
                NeuralVoices.delete(a, v.id)
                Toast.makeText(a, "Gelöscht", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun sample(v: NeuralVoices.VoiceInfo) =
        if (v.lang.startsWith("EN")) "Good evening, sir. All systems are online. How may I assist you?"
        else "Guten Abend, Lirim. Alle Systeme sind bereit. Wie kann ich helfen?"

    private fun askDownload(a: Activity, v: NeuralVoices.VoiceInfo) {
        AlertDialog.Builder(a)
            .setTitle(v.title)
            .setMessage("Diese Stimme herunterladen (ca. ${v.mb()} MB)? Am besten im WLAN.")
            .setPositiveButton("Herunterladen") { _, _ -> download(a, v, sample(v)) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun download(a: Activity, v: NeuralVoices.VoiceInfo, sampleText: String) {
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val txt = TextView(a).apply { text = "Starte …" }
        box.addView(bar); box.addView(txt)
        val dlg = AlertDialog.Builder(a).setTitle("Lade ${v.title}").setView(box).setCancelable(false).show()
        Thread {
            try {
                var last = -1
                NeuralVoices.download(a, v) { p ->
                    if (p != last) {
                        last = p
                        a.runOnUiThread { bar.progress = p; txt.text = if (p < 100) "$p %" else "Entpacke …" }
                    }
                }
                a.runOnUiThread {
                    dlg.dismiss()
                    Prefs.setNeuralVoice(a, v.id)
                    Speaker.say(sampleText)
                    Toast.makeText(a, "Stimme installiert und aktiv", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                NeuralVoices.delete(a, v.id)
                a.runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(a).setTitle("Fehler").setMessage(e.message ?: "Download fehlgeschlagen")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
}
