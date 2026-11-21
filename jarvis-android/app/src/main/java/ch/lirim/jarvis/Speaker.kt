package ch.lirim.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Sprachausgabe mit wählbarer Stimme, Tonhöhe und Tempo. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var appCtx: Context? = null
    @Volatile private var ready = false
    private val pending = mutableListOf<String>()

    /** Stil-Voreinstellungen: Name, Tonhöhe, Tempo, bevorzugte Sprachen der Stimme. */
    data class Style(val label: String, val pitch: Float, val rate: Float, val prefer: List<String>)

    val styles = listOf(
        Style("Standard", 1.0f, 1.0f, listOf("de-CH", "de-DE")),
        Style("Butler – ruhig & höflich (Jarvis-Stil)", 0.9f, 0.92f, listOf("de-DE", "de-AT")),
        Style("Auto-Computer – tief & sachlich (KITT-Stil)", 0.72f, 1.02f, listOf("de-DE")),
        Style("Roboter – sehr tief & langsam", 0.55f, 0.85f, listOf("de-DE")),
        Style("Butler auf Englisch (britisch)", 0.9f, 0.92f, listOf("en-GB")),
        Style("Eigene Einstellung", -1f, -1f, emptyList())
    )

    fun init(c: Context) {
        appCtx = c.applicationContext
        if (tts == null) tts = TextToSpeech(c.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale("de", "CH"))
            if (r < 0) t.setLanguage(Locale.GERMAN)
            ready = true
            appCtx?.let { applySettings(it) }
            synchronized(pending) {
                pending.forEach { say(it) }
                pending.clear()
            }
        }
    }

    fun isReady() = ready

    /** Alle offline nutzbaren deutschen und englischen Stimmen. */
    fun voices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            (t.voices ?: emptySet())
                .filter { it.locale.language == "de" || it.locale.language == "en" }
                .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy({ it.locale.language != "de" }, { it.locale.toLanguageTag() }, { it.name }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun label(v: Voice): String {
        val net = if (v.isNetworkConnectionRequired) " · online" else ""
        val q = if (v.quality >= Voice.QUALITY_HIGH) " · HD" else ""
        return "${v.locale.displayName} – ${v.name}$q$net"
    }

    /** Wählt passend zum Stil eine Stimme (bevorzugt männlich klingende Namen, falls erkennbar). */
    fun suggestVoice(style: Style): Voice? {
        val list = voices()
        for (tag in style.prefer) {
            val cands = list.filter { it.locale.toLanguageTag().equals(tag, true) }
            if (cands.isEmpty()) continue
            return cands.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
                ?: cands.firstOrNull { it.name.contains("-x-") && it.name.matches(Regex(".*-(deb|deg|nfh|rfj|gbd|gbb|rjs).*")) }
                ?: cands.maxByOrNull { it.quality }
        }
        return null
    }

    fun applySettings(c: Context) {
        val t = tts ?: return
        if (!ready) return
        val name = Prefs.voiceName(c)
        if (name.isNotBlank()) {
            voices().firstOrNull { it.name == name }?.let { t.voice = it }
        }
        t.setPitch(Prefs.pitch(c))
        t.setSpeechRate(Prefs.rate(c))
    }

    /** Probe mit bestimmten Werten, ohne zu speichern. */
    fun preview(voice: Voice?, pitch: Float, rate: Float, text: String) {
        val t = tts ?: return
        if (!ready) return
        t.stop()
        if (voice != null) t.voice = voice
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun say(text: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null || !ready) {
            synchronized(pending) { pending.add(text) }
            return
        }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis-" + System.nanoTime())
    }

    fun stop() {
        tts?.stop()
    }
}
