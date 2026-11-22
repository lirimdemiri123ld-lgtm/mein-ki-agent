package ch.lirim.jarvis2

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Protokoll der letzten Aktionen – damit Lirim in der App sieht, was Claude auf dem Handy gemacht hat. */
object ActionLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null

    fun add(line: String) {
        synchronized(lines) {
            lines.addFirst(fmt.format(Date()) + "  " + line)
            while (lines.size > 80) lines.removeLast()
        }
        main.post { listener?.invoke() }
    }

    fun text(): String = synchronized(lines) {
        if (lines.isEmpty()) "Noch keine Aktionen." else lines.joinToString("\n")
    }
}
