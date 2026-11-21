package ch.lirim.jarvis

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File

/**
 * Lokales KI-Gehirn: Sprachmodelle laufen direkt auf dem Handy (LiteRT-LM),
 * ohne Internet und ohne Kosten. Modelle werden einmalig in den Handyspeicher geladen.
 */
object LocalBrain {
    data class Model(
        val id: String, val title: String, val desc: String,
        val repo: String, val file: String, val commit: String, val bytes: Long, val minRamGb: Int
    ) {
        val url get() = "https://huggingface.co/$repo/resolve/$commit/$file?download=true"
        val gb get() = "%.1f GB".format(bytes / 1e9)
    }

    val models = listOf(
        Model("gemma4-e2b", "Gemma 4 E2B · Standard", "Schnell, gut für Alltag und Gespräche",
            "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
            "6e5c4f1e395deb959c494953478fa5cec4b8008f", 2_588_147_712L, 8),
        Model("gemma4-e4b", "Gemma 4 E4B · Schlauer", "Klüger und besser beim Bedienen von Apps, etwas langsamer",
            "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
            "28299f30ee4d43294517a4ac93abd6163412f07f", 3_659_530_240L, 12),
        Model("qwen25-1.5b", "Qwen 2.5 1.5B · Klein & flink", "Sehr schnell, aber einfacher",
            "litert-community/Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "5e6e7a58d03777898b31330a15d2e0a5a9107de3", 1_597_922_856L, 6),
    )

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedId = ""
    @Volatile var backendName = ""
        private set
    private val lock = Any()

    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").also { it.mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.file)
    fun isInstalled(c: Context, m: Model) = file(c, m).let { it.exists() && it.length() >= m.bytes * 0.98 }

    fun active(c: Context): Model? {
        val id = Prefs.localModel(c)
        return models.firstOrNull { it.id == id && isInstalled(c, it) } ?: models.firstOrNull { isInstalled(c, it) }
    }

    fun available(c: Context) = active(c) != null

    // ---------------------------------------------------------------- Download

    fun startDownload(c: Context, m: Model): Long {
        val f = file(c, m)
        if (f.exists()) f.delete()
        val dm = c.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("Jarvis Gehirn: ${m.title}")
            .setDescription("${m.gb} – wird in den Handyspeicher geladen")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(c, "models", m.file)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        Prefs.setDownload(c, m.id, id)
        return id
    }

    /** Fortschritt 0..100, -1 = kein Download, -2 = Fehler. */
    fun progress(c: Context, m: Model): Int {
        val id = Prefs.download(c, m.id)
        if (id < 0) return -1
        val dm = c.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
            if (!cur.moveToFirst()) return -1
            val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> 100
                DownloadManager.STATUS_FAILED -> -2
                else -> ((done * 100) / m.bytes).toInt().coerceIn(0, 99)
            }
        }
        return -1
    }

    fun cancelDownload(c: Context, m: Model) {
        val id = Prefs.download(c, m.id)
        if (id >= 0) c.getSystemService(DownloadManager::class.java).remove(id)
        Prefs.setDownload(c, m.id, -1)
    }

    fun delete(c: Context, m: Model) {
        synchronized(lock) {
            if (loadedId == m.id) { try { engine?.close() } catch (_: Exception) {}; engine = null; loadedId = "" }
        }
        cancelDownload(c, m)
        file(c, m).delete()
    }

    // ---------------------------------------------------------------- Engine

    private fun ensureEngine(c: Context): Engine {
        val m = active(c) ?: throw IllegalStateException("Kein lokales Modell installiert.")
        synchronized(lock) {
            engine?.let { if (loadedId == m.id) return it }
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            Agent.log("🧠 Lade lokales Gehirn ${m.title} …")
            val path = file(c, m).absolutePath
            val e = try {
                Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 8192, cacheDir = c.cacheDir.path))
                    .also { it.initialize(); backendName = "GPU" }
            } catch (t: Throwable) {
                Agent.log("GPU nicht verfügbar, nutze CPU (${t.message?.take(80)})")
                Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 8192, cacheDir = c.cacheDir.path))
                    .also { it.initialize(); backendName = "CPU" }
            }
            engine = e
            loadedId = m.id
            Agent.log("🧠 Lokales Gehirn bereit ($backendName)")
            return e
        }
    }

    /** Einfache Textantwort (ohne Werkzeuge). */
    fun chat(c: Context, system: String, prompt: String): String {
        val e = ensureEngine(c)
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        )
        e.createConversation(cfg).use { conv ->
            return conv.sendMessage(prompt).toString().trim()
        }
    }

    data class Step(val text: String, val toolName: String?, val args: Map<String, Any?>)

    /** Ein Agenten-Schritt: das Modell wählt höchstens ein Werkzeug. */
    fun step(c: Context, system: String, prompt: String, toolsJson: List<String>): Step {
        val e = ensureEngine(c)
        val providers = toolsJson.map { j ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString() = j
                override fun execute(paramsJsonString: String) = "{}"
            })
        }
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(system),
            tools = providers,
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3)
        )
        e.createConversation(cfg).use { conv ->
            val msg: Message = conv.sendMessage(prompt)
            val call = msg.toolCalls.firstOrNull()
            return Step(msg.toString().trim(), call?.name, call?.arguments ?: emptyMap())
        }
    }
}
