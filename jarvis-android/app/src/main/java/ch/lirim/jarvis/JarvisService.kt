package ch.lirim.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Die "Hände und Augen" von Jarvis: liest den Bildschirm und bedient Apps
 * (Bedienungshilfe / AccessibilityService).
 */
class JarvisService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisService? = null
        private const val MAX_NODES = 300
    }

    var overlay: Overlay? = null
        private set

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()
    var lastShotInfo: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Speaker.init(this)
        overlay = Overlay(this).also { if (Prefs.bubble(this)) it.showBubble() }
        if (Prefs.wakeWord(this) && !WakeWordService.running &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        overlay?.removeAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        overlay?.removeAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Bildschirm lesen

    fun readScreen(): String {
        val sb = StringBuilder()
        synchronized(nodes) {
            nodes.clear()
            val roots = mutableListOf<AccessibilityNodeInfo>()
            try {
                for (w in windows) {
                    val keep = when (w.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION,
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> true
                        AccessibilityWindowInfo.TYPE_SYSTEM -> w.isActive || w.isFocused
                        else -> false
                    }
                    if (!keep) continue
                    w.root?.let { roots.add(it) }
                }
            } catch (e: Exception) {
                // ignorieren, Fallback unten
            }
            if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

            val pkg = rootInActiveWindow?.packageName?.toString() ?: "?"
            val dm = resources.displayMetrics
            sb.append("App im Vordergrund: ").append(Apps.labelFor(this, pkg))
                .append(" (").append(pkg).append(")")
                .append(", Bildschirm ").append(dm.widthPixels).append("x").append(dm.heightPixels).append(" px\n")

            for (r in roots) {
                val rp = r.packageName?.toString() ?: ""
                if (rp != pkg) sb.append("--- Fenster: ").append(Apps.labelFor(this, rp)).append(" ---\n")
                walk(r, sb)
            }
            if (nodes.isEmpty()) {
                sb.append("(Keine lesbaren Elemente. Nutze screenshot und tippen_xy.)\n")
            } else if (nodes.size >= MAX_NODES) {
                sb.append("(Liste gekürzt – evtl. scrollen.)\n")
            }
        }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder) {
        if (nodes.size >= MAX_NODES) return
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.trim()?.replace("\n", " ").orEmpty()
        val desc = n.contentDescription?.toString()?.trim()?.replace("\n", " ").orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable ||
            n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable

        if (interesting) {
            val id = nodes.size + 1
            nodes[id] = n
            val r = Rect()
            n.getBoundsInScreen(r)
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            sb.append('[').append(id).append("] ").append(cls)
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" (").append(desc.take(100)).append(')')
            if (hint.isNotEmpty() && hint != text) sb.append(" {Hinweis: ").append(hint.take(60)).append('}')
            val flags = mutableListOf<String>()
            if (n.isClickable) flags.add("klickbar")
            if (n.isEditable) flags.add("Eingabefeld")
            if (n.isScrollable) flags.add("scrollbar")
            if (n.isCheckable) flags.add(if (n.isChecked) "AN" else "AUS")
            if (n.isSelected) flags.add("ausgewählt")
            if (n.isFocused) flags.add("fokussiert")
            if (!n.isEnabled) flags.add("deaktiviert")
            if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(", ")).append(']')
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb)
        }
    }

    private fun node(id: Int): AccessibilityNodeInfo? = synchronized(nodes) { nodes[id] }

    // ---------------------------------------------------------------- Aktionen

    fun tap(id: Int): String {
        val n = node(id) ?: return "Fehler: Element $id nicht gefunden – Bildschirm neu lesen."
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Auf [$id] getippt."
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (tapXY(r.centerX().toFloat(), r.centerY().toFloat())) "Auf [$id] getippt (per Koordinaten)."
        else "Tippen auf [$id] fehlgeschlagen."
    }

    /** Tippt auf das Element mit diesem sichtbaren Text (oder Beschreibung). */
    fun tapText(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return "Fehler: Kein Text angegeben."
        if (nodes.isEmpty()) readScreen()
        fun label(n: AccessibilityNodeInfo) =
            ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
        val all = synchronized(nodes) { nodes.entries.toList() }
        val hit = all.map { it to ch.lirim.jarvis.core.Fuzzy.score(q, label(it.value)) }
            .filter { it.second >= 55 }.maxByOrNull { it.second }?.first
        if (hit != null) return tap(hit.key).replace("[${hit.key}]", "«${query.trim()}» [${hit.key}]")
        val found = rootInActiveWindow?.findAccessibilityNodeInfosByText(query.trim())?.firstOrNull()
            ?: return "Kein Element mit «${query.trim()}» auf dem Bildschirm. Evtl. scrollen oder suchen."
        var c: AccessibilityNodeInfo? = found
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Auf «${query.trim()}» getippt."
        val r = Rect(); found.getBoundsInScreen(r)
        return if (tapXY(r.centerX().toFloat(), r.centerY().toFloat())) "Auf «${query.trim()}» getippt." else "Tippen auf «${query.trim()}» fehlgeschlagen."
    }

    fun longPress(id: Int): String {
        val n = node(id) ?: return "Fehler: Element $id nicht gefunden."
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isLongClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Lange auf [$id] gedrückt."
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (gesture(linePath(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat()), 900))
            "Lange auf [$id] gedrückt (per Koordinaten)." else "Langes Drücken fehlgeschlagen."
    }

    fun tapXY(x: Float, y: Float): Boolean = gesture(linePath(x, y, x, y), 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(linePath(x1, y1, x2, y2), ms.coerceIn(50, 3000))

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float): Path = Path().apply {
        moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) lineTo(x2, y2)
    }

    private fun gesture(path: Path, duration: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!dispatched) return false
        latch.await(duration + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    fun typeText(id: Int?, text: String, submit: Boolean): String {
        val first: AccessibilityNodeInfo = (if (id != null) node(id) else null)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Fehler: Kein Eingabefeld gefunden. Tippe zuerst auf das Feld."

        var t: AccessibilityNodeInfo = first
        if (!first.isEditable) {
            first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(500)
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) t = focused
        }
        t.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "Text konnte nicht eingegeben werden."
        if (submit) {
            Thread.sleep(300)
            t.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "Text eingegeben" + if (submit) " und bestätigt." else "."
    }

    fun scroll(direction: String, id: Int?): String {
        if (id != null) {
            var c: AccessibilityNodeInfo? = node(id)
            while (c != null && !c.isScrollable) c = c.parent
            if (c != null) {
                val action = when (direction) {
                    "hoch", "links" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (c.performAction(action)) return "Gescrollt ($direction)."
            }
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val ok = when (direction) {
            "hoch" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.75f, 400)
            "links" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2, 350)
            "rechts" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, 350)
            else -> swipe(w / 2, h * 0.75f, w / 2, h * 0.30f, 400)
        }
        return if (ok) "Gescrollt ($direction)." else "Scrollen fehlgeschlagen."
    }

    fun press(key: String): String {
        val ok = when (key) {
            "zurueck" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "letzte_apps" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "benachrichtigungen" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "schnelleinstellungen" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "enter" -> findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            else -> return "Unbekannte Taste '$key'."
        }
        return if (ok) "Taste '$key' gedrückt." else "Taste '$key' fehlgeschlagen."
    }

    // ---------------------------------------------------------------- Screenshot

    fun screenshot(): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    try {
                        val hb = shot.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                        if (bmp != null) {
                            val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            val targetW = 720
                            val factor = soft.width.toFloat() / targetW
                            val targetH = (soft.height / factor).toInt()
                            val scaled = Bitmap.createScaledBitmap(soft, targetW, targetH, true)
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                            result = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            lastShotInfo = "Screenshot ${targetW}x$targetH px. Echter Bildschirm ${soft.width}x${soft.height} px. " +
                                String.format(java.util.Locale.US, "Für tippen_xy die Bild-Koordinaten mit %.2f multiplizieren.", factor)
                        }
                        hb.close()
                    } catch (e: Exception) {
                        result = null
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            return null
        }
        latch.await(6, TimeUnit.SECONDS)
        return result
    }
}
