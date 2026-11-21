package ch.lirim.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Hört dauerhaft (offline, mit Vosk) auf «Hallo Jarvis» und startet dann die Spracheingabe.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 7
        private const val ACTION_STOP = "ch.lirim.jarvis.WAKE_STOP"
        private const val ACTION_RESUME = "ch.lirim.jarvis.WAKE_RESUME"
        private const val GRAMMAR = "[\"hello jarvis\", \"jarvis\", \"[unk]\"]"

        @Volatile var running = false
            private set

        fun start(c: Context) {
            try {
                c.startForegroundService(Intent(c, WakeWordService::class.java))
            } catch (e: Exception) {
                Agent.log("Aktivierungswort konnte nicht starten: ${e.message}")
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WakeWordService::class.java))
        }

        /** Nach der Spracheingabe wieder zuhören. */
        fun resume(c: Context) {
            if (!running) return
            try {
                c.startService(Intent(c, WakeWordService::class.java).setAction(ACTION_RESUME))
            } catch (_: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var paused = false
    private val autoResume = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground("Lade Spracherkennung …")
        StorageService.unpack(this, "model-en", "model",
            { m ->
                model = m
                startListening()
            },
            { e ->
                Agent.log("Aktivierungswort-Modell fehlt: ${e.message}")
                stopSelf()
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setWakeWord(this, false)
                stopSelf()
            }
            ACTION_RESUME -> {
                main.removeCallbacks(autoResume)
                main.postDelayed({ startListening() }, 600)
            }
        }
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis Aktivierungswort", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ausschalten", stop).build())
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun updateText(text: String) {
        try { startInForeground(text) } catch (_: Exception) {}
    }

    private fun startListening() {
        val m = model ?: return
        stopListening()
        try {
            val rec = Recognizer(m, 16000f, GRAMMAR)
            recognizer = rec
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            paused = false
            updateText("Hört auf «Hallo Jarvis»")
        } catch (e: Exception) {
            Agent.log("Mikrofon nicht verfügbar: ${e.message}")
            main.postDelayed(autoResume, 10_000)
        }
    }

    private fun stopListening() {
        speech?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        speech = null
        recognizer?.let { try { it.close() } catch (_: Exception) {} }
        recognizer = null
    }

    private fun check(json: String?, key: String) {
        if (paused || json == null) return
        val text = try { JSONObject(json).optString(key) } catch (e: Exception) { "" }
        if (text.contains("jarvis")) triggered()
    }

    private fun triggered() {
        paused = true
        main.post {
            // Mikrofon freigeben, damit die Spracheingabe es nutzen kann
            stopListening()
            updateText("Ich höre zu …")
            if (Agent.running) {
                Agent.stop()
            }
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_LISTEN, true)
            try { startActivity(i) } catch (e: Exception) { Agent.log("Konnte Jarvis nicht öffnen: ${e.message}") }
            // Falls nichts zurückkommt: nach 30 s wieder zuhören
            main.postDelayed(autoResume, 30_000)
        }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onError(exception: Exception?) {
        main.postDelayed(autoResume, 3000)
    }
    override fun onTimeout() {
        main.post { startListening() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopListening()
        model?.close()
        model = null
        super.onDestroy()
    }
}
