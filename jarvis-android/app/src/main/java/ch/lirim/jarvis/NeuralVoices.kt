package ch.lirim.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Neuronale Offline-Stimmen (Piper-Modelle über sherpa-onnx).
 * Katalog liegt in assets/voices.json, Stimmen werden bei Bedarf heruntergeladen.
 */
object NeuralVoices {
    data class VoiceInfo(val id: String, val title: String, val lang: String, val url: String, val sizeMb: Int)

    private var catalog: List<VoiceInfo>? = null
    private var tts: OfflineTts? = null
    private var loadedId = ""

    fun catalog(c: Context): List<VoiceInfo> {
        catalog?.let { return it }
        val list = try {
            val a = JSONArray(c.assets.open("voices.json").bufferedReader().use { it.readText() })
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                VoiceInfo(o.getString("id"), o.getString("title"), o.getString("lang"), o.getString("url"), o.optInt("mb"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        catalog = list
        return list
    }

    private fun dir(c: Context, id: String) = File(c.filesDir, "voices/$id")

    fun isInstalled(c: Context, id: String) = File(dir(c, id), ".ok").exists()

    fun delete(c: Context, id: String) {
        if (loadedId == id) { tts?.release(); tts = null; loadedId = "" }
        dir(c, id).deleteRecursively()
    }

    /** Lädt und entpackt eine Stimme. progress(0..100). Läuft im Hintergrund-Thread. */
    fun download(c: Context, v: VoiceInfo, progress: (Int) -> Unit) {
        val target = dir(c, v.id)
        target.deleteRecursively()
        target.mkdirs()
        var url = URL(v.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue
            }
            if (code !in 200..299) throw RuntimeException("Download fehlgeschlagen (HTTP $code)")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (v.sizeMb * 1_048_576L)
        var read = 0L
        val counting = object : java.io.FilterInputStream(BufferedInputStream(conn.inputStream, 65536)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) { read += n; progress(((read * 100) / total).toInt().coerceIn(0, 99)) }
                return n
            }
        }
        TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                // obersten Ordner entfernen
                val rel = e.name.substringAfter('/', e.name)
                if (rel.isEmpty() || rel.contains("..")) continue
                val out = File(target, rel)
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it) }
            }
        }
        conn.disconnect()
        File(target, ".ok").writeText("ok")
        progress(100)
    }

    private fun ensureLoaded(c: Context, id: String): OfflineTts? {
        if (loadedId == id && tts != null) return tts
        tts?.release(); tts = null; loadedId = ""
        val d = dir(c, id)
        val model = d.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: return null
        val tokens = File(d, "tokens.txt")
        val data = File(d, "espeak-ng-data")
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = if (data.exists()) data.absolutePath else ""
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        tts = OfflineTts(null, cfg)
        loadedId = id
        return tts
    }

    /**
     * Spricht den Text mit der neuronalen Stimme (blockierend).
     * level(0..1) liefert die Lautstärke für die Animation, shouldStop() bricht ab.
     */
    fun speak(c: Context, id: String, text: String, speed: Float, level: (Float) -> Unit, shouldStop: () -> Boolean): Boolean {
        val engine = ensureLoaded(c, id) ?: return false
        val rate = engine.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        try {
            engine.generateWithCallback(text, 0, speed.coerceIn(0.5f, 2f)) { samples ->
                if (shouldStop()) return@generateWithCallback 0
                var off = 0
                val chunk = rate / 20
                while (off < samples.size) {
                    if (shouldStop()) return@generateWithCallback 0
                    val n = minOf(chunk, samples.size - off)
                    var sum = 0f
                    for (i in off until off + n) sum += samples[i] * samples[i]
                    level((sqrt(sum / n) * 4f).coerceIn(0f, 1f))
                    track.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                    off += n
                }
                1
            }
            if (!shouldStop()) {
                Thread.sleep(250)
            }
        } finally {
            level(0f)
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
        return true
    }
}
