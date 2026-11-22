package ch.lirim.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import ch.lirim.jarvis.core.YesNo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hört nach einer Ja/Nein-Frage zu – du musst nichts antippen.
 * Die Knöpfe bleiben zusätzlich da.
 */
class VoiceAnswer(private val ctx: Context, private val onAnswer: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var rec: SpeechRecognizer? = null

    fun cancel() {
        cancelled = true
        main.post { try { rec?.destroy() } catch (_: Exception) {}; rec = null }
    }

    /** Blockiert im Hintergrund, bis eine Antwort erkannt wurde oder die Frage beantwortet ist. */
    fun run(done: CountDownLatch) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return
        // Warten, bis Jarvis die Frage fertig vorgelesen hat (sonst hört er sich selbst)
        Thread.sleep(400)
        var waited = 0
        while (Speaker.speaking && waited < 20_000 && !cancelled) { Thread.sleep(150); waited += 150 }
        Thread.sleep(250)
        WakeWordService.pause(ctx)
        try {
            for (attempt in 1..4) {
                if (cancelled || done.count == 0L) return
                val result = listenOnce() ?: continue
                val yes = YesNo.parse(result)
                Agent.log("🎤 «$result»" + when (yes) { true -> " → Ja"; false -> " → Nein"; null -> " (nicht verstanden – sag Ja oder Nein)" })
                if (yes != null) { onAnswer(yes); return }
            }
        } finally {
            if (!Agent.running) WakeWordService.resume(ctx)
        }
    }

    private fun listenOnce(): String? {
        val latch = CountDownLatch(1)
        var text: String? = null
        main.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(ctx)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle?) {
                        text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        latch.countDown()
                    }
                    override fun onPartialResults(b: Bundle?) {
                        val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        if (YesNo.parse(p) != null) { text = p; latch.countDown() }
                    }
                    override fun onError(error: Int) { latch.countDown() }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-CH")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L))
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(12, TimeUnit.SECONDS)
        main.post { try { rec?.destroy() } catch (_: Exception) {}; rec = null }
        Thread.sleep(300)
        return text?.takeIf { it.isNotBlank() }
    }
}
