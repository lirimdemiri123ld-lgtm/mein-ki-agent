package ch.lirim.jarvis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Dialog: Stil, Stimme, Tonhöhe und Tempo wählen. */
object VoiceDialog {

    fun show(a: Activity) {
        if (!Speaker.isReady()) {
            Toast.makeText(a, "Sprachausgabe startet noch – gleich nochmals versuchen.", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val voices: List<Voice> = Speaker.voices()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }

        val styleSpin = Spinner(a)
        styleSpin.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, Speaker.styles.map { it.label })

        val voiceSpin = Spinner(a)
        val voiceLabels = listOf("(Standardstimme)") + voices.map { Speaker.label(it) }
        voiceSpin.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, voiceLabels)

        val pitchLabel = TextView(a)
        val pitch = SeekBar(a).apply { max = 150 }   // 0.50 … 2.00
        val rateLabel = TextView(a)
        val rate = SeekBar(a).apply { max = 150 }

        fun pitchVal() = 0.5f + pitch.progress / 100f
        fun rateVal() = 0.5f + rate.progress / 100f
        fun refreshLabels() {
            pitchLabel.text = "Tonhöhe: %.2f (tiefer ← → höher)".format(pitchVal())
            rateLabel.text = "Tempo: %.2f (langsamer ← → schneller)".format(rateVal())
        }
        fun selectedVoice(): Voice? = voiceSpin.selectedItemPosition.let { if (it <= 0) null else voices[it - 1] }

        // Startwerte
        pitch.progress = ((Prefs.pitch(a) - 0.5f) * 100).toInt().coerceIn(0, 150)
        rate.progress = ((Prefs.rate(a) - 0.5f) * 100).toInt().coerceIn(0, 150)
        val cur = voices.indexOfFirst { it.name == Prefs.voiceName(a) }
        voiceSpin.setSelection(if (cur >= 0) cur + 1 else 0)
        styleSpin.setSelection(Prefs.voiceStyle(a).coerceIn(0, Speaker.styles.size - 1))
        refreshLabels()

        var initializing = true
        styleSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (initializing) { initializing = false; return }
                val s = Speaker.styles[pos]
                if (s.pitch < 0) return
                pitch.progress = ((s.pitch - 0.5f) * 100).toInt()
                rate.progress = ((s.rate - 0.5f) * 100).toInt()
                Speaker.suggestVoice(s)?.let { sv ->
                    val idx = voices.indexOfFirst { it.name == sv.name }
                    if (idx >= 0) voiceSpin.setSelection(idx + 1)
                }
                refreshLabels()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                refreshLabels()
                if (fromUser) styleSpin.setSelection(Speaker.styles.size - 1)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        pitch.setOnSeekBarChangeListener(seekListener)
        rate.setOnSeekBarChangeListener(seekListener)

        val test = Button(a).apply {
            text = "▶ Probe hören"
            isAllCaps = false
            setOnClickListener {
                Speaker.preview(selectedVoice(), pitchVal(), rateVal(),
                    "Guten Abend, Lirim. Alle Systeme sind bereit. Was kann ich für dich tun?")
            }
        }
        val more = Button(a).apply {
            text = "Weitere Stimmen herunterladen"
            isAllCaps = false
            setOnClickListener {
                try {
                    a.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                } catch (e: Exception) {
                    Toast.makeText(a, "Einstellungen → Allgemeine Verwaltung → Text-zu-Sprache", Toast.LENGTH_LONG).show()
                }
            }
        }

        box.addView(TextView(a).apply { text = "Stil" })
        box.addView(styleSpin)
        box.addView(TextView(a).apply { text = "Stimme"; setPadding(0, px(10), 0, 0) })
        box.addView(voiceSpin)
        box.addView(pitchLabel.apply { setPadding(0, px(10), 0, 0) })
        box.addView(pitch)
        box.addView(rateLabel)
        box.addView(rate)
        box.addView(test)
        box.addView(more)
        if (voices.isEmpty()) box.addView(TextView(a).apply { text = "Keine Stimmen gefunden – bitte «Weitere Stimmen herunterladen»." })

        AlertDialog.Builder(a)
            .setTitle("Stimme")
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                Prefs.setVoice(a, selectedVoice()?.name ?: "", pitchVal(), rateVal(), styleSpin.selectedItemPosition)
                Speaker.applySettings(a)
                Speaker.say("Stimme gespeichert.")
            }
            .setNegativeButton("Abbrechen") { _, _ -> Speaker.stop(); Speaker.applySettings(a) }
            .show()
    }
}
