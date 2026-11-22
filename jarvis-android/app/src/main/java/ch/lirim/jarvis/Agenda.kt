package ch.lirim.jarvis

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nächste Termine aus dem Handy-Kalender (nur lesen). */
object Agenda {
    fun next(c: Context, max: Int = 3): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val end = now + 48 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, end)
        }.build()
        val out = mutableListOf<Pair<String, String>>()
        val day = SimpleDateFormat("EEE", Locale.GERMAN)
        val hm = SimpleDateFormat("HH:mm", Locale.GERMAN)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        try {
            c.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cur ->
                while (cur.moveToNext() && out.size < max) {
                    val title = cur.getString(0)?.trim().orEmpty().ifEmpty { "(ohne Titel)" }
                    val begin = cur.getLong(1)
                    val allDay = cur.getInt(2) == 1
                    val d = Date(begin)
                    val isToday = SimpleDateFormat("yyyyMMdd", Locale.US).format(d) == today
                    val time = (if (isToday) "Heute" else day.format(d)) + if (allDay) " ganztags" else " " + hm.format(d)
                    out.add(time to title.take(40))
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
