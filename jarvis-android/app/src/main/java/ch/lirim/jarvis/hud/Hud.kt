package ch.lirim.jarvis.hud

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Farben und Pinsel im JARVIS-Stil. */
object Hud {
    val CYAN = Color.rgb(0, 212, 255)
    val CYAN_SOFT = Color.argb(90, 0, 212, 255)
    val CYAN_FAINT = Color.argb(35, 0, 212, 255)
    val ICE = Color.rgb(190, 245, 255)
    val ORANGE = Color.rgb(255, 140, 40)
    val RED = Color.rgb(255, 70, 70)
    val GREEN = Color.rgb(60, 255, 170)
    val BG_TOP = Color.rgb(2, 10, 22)
    val BG_BOTTOM = Color.rgb(0, 3, 8)
    val PANEL_BG = Color.argb(150, 4, 22, 40)

    val MONO: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    val MONO_BOLD: Typeface = Typeface.create("monospace", Typeface.BOLD)
    val HEAD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun text(color: Int, size: Float, tf: Typeface = MONO) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = tf
    }

    /** Weiches Annähern eines Wertes (für flüssige Animationen). */
    fun ease(current: Float, target: Float, k: Float = 0.12f) = current + (target - current) * k
}
