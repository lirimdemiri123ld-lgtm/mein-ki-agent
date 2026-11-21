package ch.lirim.jarvis

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sprachausgabe: neuronale Offline-Stimme (falls gewählt) oder System-Stimme.
 * Liefert «speaking» und «level» für den animierten Reaktor.
 */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var appCtx: Context? = null
    @Volatile private var ready = false
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var stopFlag = false
    @Volatile private var systemSpeaking = false
    @Volatile var speaking = false
        private set
    @Volatile var level = 0f
        private set
    private var worker: Thread? = null

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
        if (tts == null) {
            tts = TextToSpeech(c.applicationContext, this)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { systemSpeaking = true }
                override fun onDone(id: String?) { systemSpeaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { systemSpeaking = false }
                override fun onStop(id: String?, interrupted: Boolean) { systemSpeaking = false }
            })
        }
        if (worker == null) {
            worker = Thread({ loop() }, "jarvis-speaker").apply { isDaemon = true; start() }
        }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale("de", "CH"))
            if (r < 0) t.setLanguage(Locale.GERMAN)
            ready = true
            appCtx?.let { applySettings(it) }
        }
    }

    fun isReady() = ready

    private fun loop() {
        while (true) {
            val text = try { queue.take() } catch (e: InterruptedException) { return }
            stopFlag = false
            val c = appCtx ?: continue
            speaking = true
            try {
                val neural = Prefs.neuralVoice(c)
                var done = false
                if (neural.isNotBlank() && NeuralVoices.isInstalled(c, neural)) {
                    done = try {
                        NeuralVoices.speak(c, neural, text, Prefs.rate(c), { level = it }, { stopFlag })
                    } catch (e: Throwable) {
                        Agent.log("Neuronale Stimme Fehler: ${e.message}")
                        false
                    }
                }
                if (!done) speakSystem(text)
            } finally {
                level = 0f
                speaking = !queue.isEmpty()
            }
        }
    }

    private fun speakSystem(text: String) {
        val t = tts ?: return
        var waited = 0
        while (!ready && waited < 50) { Thread.sleep(100); waited++ }
        systemSpeaking = true
        t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "jarvis-" + System.nanoTime())
        var tt = 0f
        var guard = 0
        while (systemSpeaking && !stopFlag && guard < 1200) {
            // Simulierte Lautstärke (System-TTS liefert keine Pegel)
            tt += 0.25f
            level = (0.35f + 0.35f * abs(sin(tt)) + 0.2f * abs(sin(tt * 2.7f))).coerceIn(0f, 1f)
            Thread.sleep(50); guard++
        }
        if (stopFlag) t.stop()
    }

    // ---------------------------------------------------------------- System-Stimmen

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

    fun suggestVoice(style: Style): Voice? {
        val list = voices()
        for (tag in style.prefer) {
            val cands = list.filter { it.locale.toLanguageTag().equals(tag, true) }
            if (cands.isEmpty()) continue
            return cands.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
                ?: cands.maxByOrNull { it.quality }
        }
        return null
    }

    fun applySettings(c: Context) {
        val t = tts ?: return
        if (!ready) return
        val name = Prefs.voiceName(c)
        if (name.isNotBlank()) voices().firstOrNull { it.name == name }?.let { t.voice = it }
        t.setPitch(Prefs.pitch(c))
        t.setSpeechRate(Prefs.rate(c))
    }

    fun preview(voice: Voice?, pitch: Float, rate: Float, text: String) {
        val t = tts ?: return
        if (!ready) return
        stop()
        if (voice != null) t.voice = voice
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    // ---------------------------------------------------------------- Öffentlich

    fun say(text: String) {
        if (text.isBlank()) return
        speaking = true
        queue.offer(text)
    }

    fun stop() {
        queue.clear()
        stopFlag = true
        tts?.stop()
    }
}
