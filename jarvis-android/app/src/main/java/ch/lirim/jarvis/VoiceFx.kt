package ch.lirim.jarvis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Klangfilter für eigene Stil-Pakete (keine Nachahmung realer Stimmen):
 *  - "jarvis": ruhiger Raumhall + feiner digitaler Glanz («KI im Anzug»)
 *  - "kitt":   metallischer Auto-Computer-Klang (Ringmodulation + kurzer Slapback)
 */
class VoiceFx(private val type: String, private val rate: Int) {
    private val delays = when (type) {
        "jarvis" -> intArrayOf((0.029 * rate).toInt(), (0.037 * rate).toInt(), (0.043 * rate).toInt())
        "kitt" -> intArrayOf((0.011 * rate).toInt())
        else -> IntArray(0)
    }
    private val buf = FloatArray((0.06 * rate).toInt().coerceAtLeast(1))
    private var pos = 0
    private var phase = 0.0
    private var lp = 0f

    fun process(x: FloatArray, off: Int, n: Int) {
        if (type == "none" || type.isBlank()) return
        for (i in off until off + n) {
            var s = x[i]
            when (type) {
                "jarvis" -> {
                    var wet = 0f
                    for (d in delays) wet += buf[(pos - d + buf.size) % buf.size]
                    val out = s + wet * 0.12f
                    buf[pos] = s + wet * 0.25f
                    // leichter Glanz: sanfte Sättigung
                    s = (tanh((out * 1.15f).toDouble()) * 0.95).toFloat()
                }
                "kitt" -> {
                    phase += 2 * PI * 38.0 / rate
                    if (phase > 2 * PI) phase -= 2 * PI
                    val ring = s * (0.7f + 0.3f * sin(phase).toFloat())
                    val slap = buf[(pos - delays[0] + buf.size) % buf.size]
                    buf[pos] = ring
                    lp += 0.55f * (ring - lp)          // etwas dumpfer, «aus dem Armaturenbrett»
                    s = (tanh(((lp + slap * 0.35f) * 1.4f).toDouble()) * 0.9).toFloat()
                }
            }
            x[i] = s
            pos = (pos + 1) % buf.size
        }
    }
}
