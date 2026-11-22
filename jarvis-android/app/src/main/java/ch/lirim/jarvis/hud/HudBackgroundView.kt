package ch.lirim.jarvis.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** Lebendiger Hintergrund: Raster, schwebende Partikel, laufende Scanlinie. */
class HudBackgroundView(c: Context) : View(c) {
    private class P(var x: Float, var y: Float, val vx: Float, val vy: Float, val r: Float, val phase: Float)

    private val particles = mutableListOf<P>()
    private val bg = Paint()
    private val grid = Hud.stroke(Hud.CYAN_FAINT, 1f)
    private val dot = Hud.fill(Hud.CYAN)
    private val line = Hud.stroke(Hud.CYAN, 1f)
    private val scan = Paint()
    private var t = 0f
    private val dp = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Hud.BG_TOP, Hud.BG_BOTTOM, Shader.TileMode.CLAMP)
        particles.clear()
        repeat(24) {
            particles.add(
                P(Random.nextFloat() * w, Random.nextFloat() * h,
                    (Random.nextFloat() - 0.5f) * 0.4f * dp, (Random.nextFloat() - 0.5f) * 0.4f * dp,
                    (0.8f + Random.nextFloat() * 1.8f) * dp, Random.nextFloat() * 6.28f)
            )
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        t += 0.033f
        c.drawRect(0f, 0f, w, h, bg)

        // Raster mit leichter Bewegung
        val step = 36 * dp
        val off = (t * 8 * dp) % step
        var x = -step + off
        while (x < w) { c.drawLine(x, 0f, x, h, grid); x += step }
        var y = -step + off
        while (y < h) { c.drawLine(0f, y, w, y, grid); y += step }

        // Partikel + Verbindungslinien
        for (p in particles) {
            p.x += p.vx; p.y += p.vy
            if (p.x < 0) p.x += w; if (p.x > w) p.x -= w
            if (p.y < 0) p.y += h; if (p.y > h) p.y -= h
        }
        val maxD = 90 * dp
        for (i in particles.indices) {
            val a = particles[i]
            for (j in i + 1 until particles.size) {
                val b = particles[j]
                val dx = a.x - b.x; val dy = a.y - b.y
                val d2 = dx * dx + dy * dy
                if (d2 < maxD * maxD) {
                    line.alpha = (60 * (1 - d2 / (maxD * maxD))).toInt()
                    c.drawLine(a.x, a.y, b.x, b.y, line)
                }
            }
            dot.alpha = (120 + 100 * sin(t * 2 + a.phase)).toInt().coerceIn(20, 255)
            c.drawCircle(a.x, a.y, a.r, dot)
        }

        // Scanlinie
        val sy = (t * 120 * dp) % (h + 200 * dp) - 100 * dp
        scan.shader = LinearGradient(0f, sy - 60 * dp, 0f, sy, 0x0000D4FF, 0x3000D4FF, Shader.TileMode.CLAMP)
        c.drawRect(0f, sy - 60 * dp, w, sy, scan)
        line.alpha = 140
        c.drawLine(0f, sy, w, sy, line)

        postInvalidateDelayed(66)   // 15 Bilder/s genügen für den ruhigen Hintergrund
    }
}
