package ch.lirim.jarvis.hud

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.sin

private fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Panel mit Eckklammern, Titel, blinkender Status-LED und Glanzlinie. */
class HudPanel(c: Context, title: String) : LinearLayout(c) {
    private val dp = resources.displayMetrics.density
    private val border = Hud.stroke(Hud.CYAN_SOFT, 1f)
    private val corner = Hud.stroke(Hud.CYAN, 2.5f * dp)
    private val bg = Hud.fill(Hud.PANEL_BG)
    private val led = Hud.fill(Hud.CYAN)
    private val shine = Paint()
    private val rect = RectF()
    private var t = (Math.random() * 10).toFloat()
    val titleView: TextView
    val body: LinearLayout

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val p = (10 * dp).toInt()
        setPadding(p, p, p, p)
        titleView = TextView(c).apply {
            text = "▸ " + title.uppercase()
            setTextColor(Hud.CYAN)
            textSize = 11f
            typeface = Hud.MONO_BOLD
            letterSpacing = 0.18f
        }
        addView(titleView)
        body = LinearLayout(c).apply { orientation = VERTICAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
        addView(body)
        alpha = 0f
        translationY = 30 * dp
    }

    /** Einblenden mit Verzögerung (gestaffelt). */
    fun reveal(delay: Long) {
        animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(650)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    override fun onDraw(c: Canvas) {
        t += 0.4f
        val w = width.toFloat(); val h = height.toFloat()
        val k = 12 * dp
        rect.set(0f, 0f, w, h)
        c.drawRect(rect, bg)
        c.drawRect(rect, border)
        // Ecken
        c.drawLine(0f, 0f, k, 0f, corner); c.drawLine(0f, 0f, 0f, k, corner)
        c.drawLine(w, 0f, w - k, 0f, corner); c.drawLine(w, 0f, w, k, corner)
        c.drawLine(0f, h, k, h, corner); c.drawLine(0f, h, 0f, h - k, corner)
        c.drawLine(w, h, w - k, h, corner); c.drawLine(w, h, w, h - k, corner)
        // Status-LED blinkt ruhig: 1× pro Sekunde neu zeichnen statt 30× (pro Panel!) – hält das Handy kühl
        led.alpha = (140 + 115 * sin(t * 4)).toInt().coerceIn(0, 255)
        c.drawCircle(w - 12 * dp, 14 * dp, 3.5f * dp, led)
        postInvalidateDelayed(1000)
    }

    /** Zeile «Bezeichnung …… Wert». */
    fun row(label: String): TextView {
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        val l = TextView(context).apply {
            text = label.uppercase(); setTextColor(Hud.CYAN_SOFT); textSize = 11f; typeface = Hud.MONO
        }
        val v = TextView(context).apply {
            setTextColor(Hud.ICE); textSize = 12f; typeface = Hud.MONO_BOLD; gravity = Gravity.END
        }
        line.addView(l, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        line.addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        body.addView(line)
        return v
    }

    fun text(size: Float = 12f, color: Int = Hud.ICE): TextView {
        val tv = TextView(context).apply { setTextColor(color); textSize = size; typeface = Hud.MONO }
        body.addView(tv)
        return tv
    }
}

/** Fliessender Live-Graph. */
class Sparkline(c: Context, private val color: Int = Hud.CYAN) : View(c) {
    private val values = ArrayDeque<Float>()
    private val maxPoints = 60
    private val line = Hud.stroke(color, c.dp(1.6f))
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var scroll = 0f
    var fixedMax: Float? = null

    fun push(v: Float) {
        values.addLast(v)
        while (values.size > maxPoints) values.removeFirst()
        scroll = 1f
        invalidate()
    }

    fun set(list: List<Float>) {
        values.clear(); list.takeLast(maxPoints).forEach { values.addLast(it) }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (values.size < 2) return
        scroll = Hud.ease(scroll, 0f, 0.08f)
        val w = width.toFloat(); val h = height.toFloat()
        val mx = fixedMax ?: (values.maxOrNull() ?: 1f)
        val mn = if (fixedMax != null) 0f else (values.minOrNull() ?: 0f)
        val span = (mx - mn).takeIf { it > 0.0001f } ?: 1f
        val step = w / (maxPoints - 1)
        val startX = w - (values.size - 1) * step + scroll * step
        path.reset()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = h - ((v - mn) / span) * (h * 0.85f) - h * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
        path.lineTo(startX + (values.size - 1) * step, h)
        path.lineTo(startX, h)
        path.close()
        area.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(path, area)
        if (scroll > 0.02f) postInvalidateDelayed(33)   // nur während der kurzen Einlauf-Animation
    }
}

/** Segmentierter Balken, der sanft auf den Zielwert gleitet. */
class Meter(c: Context) : View(c) {
    var target = 0f
    private var value = 0f
    private val on = Hud.fill(Hud.CYAN)
    private val off = Hud.fill(Hud.CYAN_FAINT)

    override fun onDraw(c: Canvas) {
        value = Hud.ease(value, target.coerceIn(0f, 1f), 0.08f)
        val n = 24
        val gap = width * 0.012f
        val seg = (width - gap * (n - 1)) / n
        val lit = (value * n)
        for (i in 0 until n) {
            val p = if (i < lit.toInt()) on else off
            p.color = when {
                i < lit.toInt() && i > n * 0.8f -> Hud.ORANGE
                i < lit.toInt() -> Hud.CYAN
                else -> Hud.CYAN_FAINT
            }
            val x = i * (seg + gap)
            c.drawRect(x, 0f, x + seg, height.toFloat(), p)
        }
        if (kotlin.math.abs(value - target) > 0.002f) postInvalidateDelayed(33) else postInvalidateDelayed(500)
    }
}

/** Zahl, die zum neuen Wert hochzählt. */
class CountingText(private val tv: TextView, private val format: (Float) -> String) {
    private var shown = 0f
    private var animator: ObjectAnimator? = null
    @Suppress("unused") fun setShown(v: Float) { shown = v; tv.text = format(v) }
    @Suppress("unused") fun getShown() = shown
    fun to(v: Float) {
        animator?.cancel()
        animator = ObjectAnimator.ofFloat(this, "shown", shown, v).setDuration(900).also { it.start() }
    }
}

/** Protokoll, das Zeichen für Zeichen «getippt» wird. */
class TypewriterLog(private val tv: TextView, private val onChange: () -> Unit) {
    private val queue = StringBuilder()
    private val h = Handler(Looper.getMainLooper())
    private var running = false

    fun setAll(text: String) {
        queue.clear(); tv.text = text; onChange()
    }

    fun append(line: String) {
        queue.append(line).append('\n')
        if (!running) tick()
    }

    private fun tick() {
        if (queue.isEmpty()) { running = false; return }
        running = true
        val n = if (queue.length > 200) 40 else 8
        val chunk = queue.substring(0, minOf(n, queue.length))
        queue.delete(0, chunk.length)
        tv.append(chunk)
        if (tv.text.length > 8000) tv.text = tv.text.subSequence(tv.text.length - 6000, tv.text.length)
        onChange()
        h.postDelayed({ tick() }, 30)
    }
}

/** Rotes Lauflicht (Hommage an Auto-Computer der 80er) – pulsiert beim Sprechen. */
class KittScanner(c: Context) : View(c) {
    var level = 0f
    private var t = 0f
    private var smooth = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        t += 0.016f
        smooth = Hud.ease(smooth, level, 0.3f)
        val n = 16
        val gap = width * 0.01f
        val w = (width - gap * (n - 1)) / n
        val pos = ((sin(t * 2.6f) + 1f) / 2f) * (n - 1)
        for (i in 0 until n) {
            val d = kotlin.math.abs(i - pos)
            var a = (1f - d / 4f).coerceIn(0f, 1f)
            if (smooth > 0.05f) {
                val centre = kotlin.math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f)
                a = maxOf(a, (smooth * 1.4f - centre).coerceIn(0f, 1f))
            }
            p.color = android.graphics.Color.argb((40 + 215 * a).toInt(), 255, (30 + 40 * a).toInt(), 20)
            val x = i * (w + gap)
            c.drawRect(x, 0f, x + w, height.toFloat(), p)
        }
        postInvalidateDelayed(33)
    }
}
