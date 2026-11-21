package ch.lirim.jarvis

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Schwebender Mikrofon-Knopf und Statusleiste über allen Apps. */
class Overlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var yesBtn: Button? = null
    private var noBtn: Button? = null

    @Volatile private var confirmLatch: CountDownLatch? = null
    @Volatile private var confirmAnswer = false

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()

    private fun params(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity }

    private fun orangeBg(oval: Boolean) = GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!oval) cornerRadius = dp(16).toFloat()
        setColor(0xEEFF6600.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() = main.post {
        if (bubble != null) return@post
        val tv = TextView(svc).apply {
            text = "🎙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = orangeBg(true)
            elevation = dp(6).toFloat()
        }
        val size = dp(58)
        val lp = params(size, size, Gravity.TOP or Gravity.END)
        lp.y = dp(320)
        var startY = 0f
        var startLpY = 0
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; startLpY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.y = (startLpY + dy).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onBubbleTap()
            }
            true
        }
        try {
            wm.addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {}
    }

    private fun onBubbleTap() {
        if (Agent.running) {
            Agent.stop()
            setStatus("Gestoppt.")
            return
        }
        val i = Intent(svc, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
        svc.startActivity(i)
    }

    fun hideBubble() = main.post {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun ensurePanel() {
        if (panel != null) return
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = orangeBg(false)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(8).toFloat()
        }
        val status = TextView(svc).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
        }
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val yes = Button(svc).apply { text = "Ja"; visibility = View.GONE; setOnClickListener { answer(true) } }
        val no = Button(svc).apply { text = "Nein"; visibility = View.GONE; setOnClickListener { answer(false) } }
        val stop = Button(svc).apply {
            text = "Stopp"
            setOnClickListener { Agent.stop(); answer(false); setStatus("Gestoppt.") }
        }
        row.addView(yes); row.addView(no); row.addView(stop)
        box.addView(status); box.addView(row)
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.y = dp(90)
        lp.horizontalMargin = 0.03f
        try {
            wm.addView(box, lp)
            panel = box; statusView = status; yesBtn = yes; noBtn = no
        } catch (_: Exception) {}
    }

    fun setStatus(text: String) = main.post {
        ensurePanel()
        statusView?.text = "Jarvis: $text"
    }

    fun hideStatus() = main.post {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; statusView = null; yesBtn = null; noBtn = null
    }

    private fun answer(v: Boolean) {
        confirmAnswer = v
        yesBtn?.visibility = View.GONE
        noBtn?.visibility = View.GONE
        confirmLatch?.countDown()
    }

    /** Blockiert (Hintergrund-Thread!) bis der Nutzer Ja/Nein tippt. */
    fun confirm(question: String): Boolean {
        val latch = CountDownLatch(1)
        confirmLatch = latch
        confirmAnswer = false
        main.post {
            ensurePanel()
            statusView?.text = "❓ $question"
            yesBtn?.visibility = View.VISIBLE
            noBtn?.visibility = View.VISIBLE
        }
        latch.await(120, TimeUnit.SECONDS)
        confirmLatch = null
        main.post { yesBtn?.visibility = View.GONE; noBtn?.visibility = View.GONE }
        return confirmAnswer
    }

    fun removeAll() {
        hideBubble()
        hideStatus()
    }
}
