package ch.lirim.jarvis2

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ergebnis eines Werkzeugs: Text und optional ein Bild (JPEG, Base64). */
class ToolResult(val text: String, val imageB64: String? = null, val isError: Boolean = false)

/** Das, was der MCP-Server von der App braucht. Ohne Android-Abhängigkeit, damit es auf dem PC testbar ist. */
interface ToolHost {
    fun tools(): List<JMap>
    fun call(name: String, args: JMap): ToolResult
    fun log(line: String) {}
}

/**
 * Kleiner MCP-Server (Model Context Protocol, «Streamable HTTP» mit JSON-Antworten).
 *
 * Claude (claude.ai bzw. Claude-App) schickt JSON-RPC per POST an  https://<tunnel>/mcp/<geheimer-schlüssel>.
 * Der Server lauscht nur auf 127.0.0.1 – von aussen erreichbar ist er ausschliesslich über den Tunnel.
 */
class McpServer(
    private val port: Int,
    private val secretPath: () -> String,
    private val host: ToolHost,
    private val version: String = "2.0",
) {
    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_BODY = 5 * 1024 * 1024
        const val INSTRUCTIONS =
            "Jarvis2 ist Lirims eigenes Handy (Samsung Galaxy S25), live verbunden. Mit diesen Werkzeugen kannst du auf dem Handy " +
            "WIRKLICH Dinge tun: WhatsApp-Nachrichten senden, Apps öffnen und bedienen, anrufen, Benachrichtigungen lesen, Wecker und Timer stellen. " +
            "Wenn Lirim sagt «schick», «schreib», «öffne», «ruf an», «stell» oder etwas auf seinem Handy will, benutze IMMER diese Werkzeuge – " +
            "zeige keinen Nachrichtenentwurf und sag nicht, dass du das nicht kannst. " +
            "Für WhatsApp: whatsapp_senden. Sonst: app_oeffnen → bildschirm_lesen → tippen/text_eingeben → Ergebnis prüfen. " +
            "Nach jeder Aktion kommt der neue Bildschirm zurück; Elemente haben Nummern [id], die nur bis zum nächsten Lesen gelten. " +
            "Banking- und Bezahl-Apps sind gesperrt; versuche nicht, die Sperre zu umgehen."
    }

    @Volatile private var server: ServerSocket? = null
    private val connPool = Executors.newCachedThreadPool()
    /** Ein Werkzeug nach dem anderen: Bildschirm-Aktionen dürfen sich nicht überschneiden. */
    private val toolPool = Executors.newSingleThreadExecutor()

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        val s = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        s.reuseAddress = true
        server = s
        Thread({
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                connPool.execute { handle(sock) }
            }
        }, "jarvis2-mcp-accept").apply { isDaemon = true }.start()
        host.log("MCP-Server gestartet auf 127.0.0.1:$port")
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        host.log("MCP-Server gestoppt")
    }

    // ------------------------------------------------------------------ HTTP

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = readRequest(input) ?: return
                route(req, out)
            } catch (e: Exception) {
                // Verbindung einfach schliessen
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 16_384) throw IllegalStateException("Zeile zu lang")
        }
        return b.toString("ISO-8859-1")
    }

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 100) return null
        }
        val body = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
            else -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > MAX_BODY) throw IllegalStateException("Anfrage zu gross")
                readExact(input, len)
            }
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun readExact(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break }
                break
            }
            if (out.size() + size > MAX_BODY) throw IllegalStateException("Anfrage zu gross")
            out.write(readExact(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun send(out: OutputStream, code: Int, body: String = "", type: String = "application/json", extra: Map<String, String> = emptyMap()) {
        val reason = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        if (bytes.isNotEmpty()) sb.append("Content-Type: ").append(type).append("; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun sameSecret(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun route(req: Request, out: OutputStream) {
        val path = req.path.substringBefore('?').trimEnd('/')
        if (path == "/health") {
            send(out, 200, "Jarvis 2.0 läuft", "text/plain")
            return
        }
        val secret = secretPath().trimEnd('/')
        if (secret.isEmpty() || !sameSecret(path, secret)) {
            send(out, 404, "{\"error\":\"not found\"}")
            return
        }
        when (req.method) {
            "POST" -> {
                val text = String(req.body, Charsets.UTF_8)
                val reply = handleRpc(text)
                if (reply == null) send(out, 202) else send(out, 200, reply)
            }
            "GET" -> send(out, 405, "{\"error\":\"Kein SSE-Stream – bitte POST verwenden\"}", extra = mapOf("Allow" to "POST, DELETE"))
            "DELETE" -> send(out, 200, "{}")
            "OPTIONS" -> send(out, 204, extra = mapOf("Allow" to "POST, DELETE"))
            else -> send(out, 405, "{\"error\":\"method not allowed\"}", extra = mapOf("Allow" to "POST, DELETE"))
        }
    }

    // ------------------------------------------------------------------ JSON-RPC / MCP

    /** Verarbeitet eine JSON-RPC-Nachricht (einzeln oder Liste). null = nur Benachrichtigungen, Antwort 202. */
    fun handleRpc(text: String): String? {
        val parsed = try { Json.parse(text) } catch (e: Exception) {
            return Json.write(error(null, -32700, "Ungültiges JSON"))
        }
        if (parsed is List<*>) {
            val replies = parsed.mapNotNull { m ->
                @Suppress("UNCHECKED_CAST")
                (m as? Map<String, Any?>)?.let { one(it) } ?: if (m !is Map<*, *>) error(null, -32600, "Ungültige Anfrage") else null
            }
            return if (replies.isEmpty()) null else Json.write(replies)
        }
        @Suppress("UNCHECKED_CAST")
        val msg = parsed as? Map<String, Any?> ?: return Json.write(error(null, -32600, "Ungültige Anfrage"))
        return one(msg)?.let { Json.write(it) }
    }

    private fun result(id: Any?, res: Any?) = jobj("jsonrpc" to "2.0", "id" to id, "result" to res)
    private fun error(id: Any?, code: Int, msg: String) =
        jobj("jsonrpc" to "2.0", "id" to id, "error" to jobj("code" to code, "message" to msg))

    private fun one(msg: JMap): MutableMap<String, Any?>? {
        val method = msg.str("method")
        val hasId = msg.containsKey("id") && msg["id"] != null
        val id = msg["id"]
        if (method.isEmpty()) return null // Antwort des Clients auf eine Server-Anfrage: ignorieren
        if (!hasId) return null // Benachrichtigung (z. B. notifications/initialized)
        val params = msg.obj("params") ?: emptyMap()
        return when (method) {
            "initialize" -> {
                val asked = params.str("protocolVersion")
                val ver = if (asked in SUPPORTED_VERSIONS) asked else SUPPORTED_VERSIONS.first()
                host.log("Claude hat sich verbunden (${params.obj("clientInfo")?.str("name") ?: "?"})")
                result(id, jobj(
                    "protocolVersion" to ver,
                    "capabilities" to jobj("tools" to jobj("listChanged" to false)),
                    "serverInfo" to jobj("name" to "jarvis2", "title" to "Jarvis2 – Lirims Handy (WhatsApp, Apps, Anrufe)", "version" to version),
                    "instructions" to INSTRUCTIONS,
                ))
            }
            "ping" -> result(id, jobj())
            "tools/list" -> result(id, jobj("tools" to host.tools()))
            "tools/call" -> {
                val name = params.str("name")
                val args = params.obj("arguments") ?: emptyMap()
                if (host.tools().none { it.str("name") == name }) {
                    return result(id, jobj("content" to jarr(jobj("type" to "text", "text" to "Unbekanntes Werkzeug: $name")), "isError" to true))
                }
                result(id, callTool(name, args))
            }
            "resources/list" -> result(id, jobj("resources" to jarr()))
            "prompts/list" -> result(id, jobj("prompts" to jarr()))
            else -> error(id, -32601, "Methode nicht unterstützt: $method")
        }
    }

    private fun callTool(name: String, args: JMap): MutableMap<String, Any?> {
        host.log("→ $name ${shortArgs(args)}")
        val r: ToolResult = try {
            toolPool.submit<ToolResult> { host.call(name, args) }.get(90, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            ToolResult("Zeitüberschreitung: '$name' hat zu lange gedauert.", isError = true)
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            ToolResult("Fehler bei '$name': ${cause.message ?: cause.javaClass.simpleName}", isError = true)
        }
        host.log((if (r.isError) "✗ " else "✓ ") + r.text.lineSequence().firstOrNull().orEmpty().take(90))
        val content = mutableListOf<Any?>(jobj("type" to "text", "text" to r.text))
        if (r.imageB64 != null) content.add(jobj("type" to "image", "data" to r.imageB64, "mimeType" to "image/jpeg"))
        return jobj("content" to content, "isError" to r.isError)
    }

    private fun shortArgs(a: JMap): String {
        if (a.isEmpty()) return ""
        return a.entries.joinToString(", ", "(", ")") { (k, v) ->
            val s = v?.toString().orEmpty()
            "$k=" + if (s.length > 40) s.take(40) + "…" else s
        }
    }
}
