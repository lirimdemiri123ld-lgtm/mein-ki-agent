package ch.lirim.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Sprachausgabe (Text-to-Speech) auf Deutsch. */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = mutableListOf<String>()

    fun init(c: Context) {
        if (tts == null) tts = TextToSpeech(c.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale("de", "CH"))
            if (r < 0) t.setLanguage(Locale.GERMAN)
            ready = true
            synchronized(pending) {
                pending.forEach { say(it) }
                pending.clear()
            }
        }
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
