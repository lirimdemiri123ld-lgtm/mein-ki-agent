package ch.lirim.jarvis.hud

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animierter Arc-Reaktor – das «Gesicht» von Jarvis.
 * Reagiert auf Zustand (bereit/zuhören/denken/sprechen) und Lautstärke.
 */
class ArcReactorView(c: Context) : View(c) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    var state = State.IDLE
    /** Lautstärke 0..1 (Mikrofon oder Stimme). */
    var level = 0f
    var label = "J.A.R.V.I.S."
    var subLabel = "BEREIT"

    private val dp = resources.displayMetrics.density
    private var t = 0f
    private var smooth = 0f
    private var spin = 0f
    private val wave = FloatArray(72)

    private val ring = Hud.stroke(Hud.CYAN, 2f)
    private val thin = Hud.stroke(Hud.CYAN_SOFT, 1f)
    private val glow = Hud.stroke(Hud.CYAN, 6f).apply { maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Hud.fill(Hud.CYAN)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Hud.text(Hud.ICE, 15f, Hud.HEAD).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.3f }
    private val sub = Hud.text(Hud.CYAN, 10f).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.25f }
    private val small = Hud.text(Hud.CYAN_SOFT, 8f).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun accent(): Int = when (state) {
        State.THINKING -> Hud.ORANGE
        State.LISTENING -> Hud.GREEN
        else -> Hud.CYAN
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8 * dp
        t += 0.016f

        val targetLevel = when (state) {
            State.IDLE -> 0.08f + 0.05f * sin(t * 1.6f)
            State.THINKING -> 0.35f + 0.15f * sin(t * 6f)
            else -> level.coerceIn(0f, 1f)
        }
        smooth = Hud.ease(smooth, targetLevel, 0.2f)
        val speed = when (state) {
            State.IDLE -> 0.4f; State.LISTENING -> 1.2f; State.THINKING -> 3.5f; State.SPEAKING -> 1.6f
        }
        spin += speed
        val col = accent()
        ring.color = col; glow.color = col; fill.color = col; sub.color = col

        // Äusserer Tick-Ring
        for (i in 0 until 120) {
            val a = Math.toRadians((i * 3 + spin * 0.2).toDouble())
            val long = i % 10 == 0
            val r1 = r * (if (long) 0.90f else 0.94f)
            thin.alpha = if (long) 200 else 90
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), thin)
        }

        // Audio-Wellenring
        for (i in wave.indices) {
            val target = if (state == State.IDLE) 0.05f else smooth * (0.4f + Random.nextFloat() * 0.9f)
            wave[i] = Hud.ease(wave[i], target, 0.3f)
        }
        for (i in wave.indices) {
            val a = Math.toRadians((i * 5 - spin * 0.5).toDouble())
            val r1 = r * 0.72f
            val r2 = r1 + wave[i] * r * 0.16f + 2 * dp
            ring.strokeWidth = 2.5f * dp
            ring.alpha = 220
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), ring)
        }

        // Rotierende Segmentringe
        ring.strokeWidth = 3 * dp
        arcSegments(c, cx, cy, r * 0.84f, 6, 38f, spin * 0.6f, ring)
        ring.strokeWidth = 1.5f * dp
        arcSegments(c, cx, cy, r * 0.66f, 3, 80f, -spin * 1.1f, ring)
        thin.alpha = 160
        arcSegments(c, cx, cy, r * 0.60f, 12, 18f, spin * 1.6f, thin)
        glow.alpha = (90 + 120 * smooth).toInt().coerceAtMost(255)
        rect.set(cx - r * 0.84f, cy - r * 0.84f, cx + r * 0.84f, cy + r * 0.84f)
        c.drawArc(rect, spin * 0.6f, 50f, false, glow)

        // Kern mit Leuchten
        val coreR = r * (0.40f + smooth * 0.08f)
        core.shader = RadialGradient(cx, cy, coreR,
            intArrayOf(Color.WHITE, col, Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        core.alpha = (150 + 100 * smooth).toInt().coerceAtMost(255)
        c.drawCircle(cx, cy, coreR, core)

        // Dreieck wie beim Mark-Reaktor
        val tr = r * 0.30f
        path.reset()
        for (k in 0..2) {
            val a = Math.toRadians((-90 + k * 120 + sin(t) * 3).toDouble())
            val px = cx + (tr * cos(a)).toFloat()
            val py = cy + (tr * sin(a)).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        ring.strokeWidth = 3 * dp
        ring.alpha = 255
        c.drawPath(path, ring)
        ring.strokeWidth = 1 * dp
        c.drawCircle(cx, cy, r * 0.50f, ring)

        // Umlaufender Punkt
        val oa = Math.toRadians((spin * 2).toDouble())
        c.drawCircle(cx + (r * 0.84f * cos(oa)).toFloat(), cy + (r * 0.84f * sin(oa)).toFloat(), 3.5f * dp, fill)

        // Text
        // Schrift wächst mit dem Kreis (kleiner runder Reaktor oben)
        txt.textSize = (r * 0.14f).coerceIn(8 * dp, 16 * dp)
        sub.textSize = (r * 0.09f).coerceIn(6 * dp, 10 * dp)
        small.textSize = (r * 0.07f).coerceIn(5 * dp, 8 * dp)
        c.drawText(label, cx, cy + r * 0.62f + 6 * dp, txt)
        c.drawText(subLabel.uppercase(), cx, cy - r * 0.55f, sub)
        c.drawText("MK-" + String.format("%02d", (t * 10).toInt() % 100), cx, cy + r * 0.75f + 4 * dp, small)

        postInvalidateDelayed(33)
    }

    private fun arcSegments(c: Canvas, cx: Float, cy: Float, r: Float, n: Int, len: Float, start: Float, p: Paint) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val step = 360f / n
        for (i in 0 until n) c.drawArc(rect, start + i * step, len, false, p)
    }
}
