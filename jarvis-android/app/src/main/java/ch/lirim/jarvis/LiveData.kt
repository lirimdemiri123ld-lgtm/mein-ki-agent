package ch.lirim.jarvis

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Live-Systemwerte. */
object SystemStats {
    data class Snapshot(
        val cpuLoad: Float?,     // 0..1, aus aktuellem/maximalem Takt
        val cpuGhz: Float?,
        val cores: Int,
        val ramUsed: Float,      // 0..1
        val ramGb: String,
        val battery: Int,
        val charging: Boolean,
        val batteryTemp: Float,
        val storageUsed: Float,
        val storageText: String
    )

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLong()
    } catch (e: Exception) {
        null
    }

    fun snapshot(c: Context): Snapshot {
        val cores = Runtime.getRuntime().availableProcessors()
        var cur = 0L; var max = 0L; var n = 0
        for (i in 0 until cores) {
            val cf = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val mf = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (cf != null && mf != null && mf > 0) { cur += cf; max += mf; n++ }
        }
        val load = if (n > 0) cur.toFloat() / max else null
        val ghz = if (n > 0) cur.toFloat() / n / 1_000_000f else null

        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = 1f - mi.availMem.toFloat() / mi.totalMem
        val ramGb = "%.1f/%.1f GB".format((mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9)

        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes.toFloat(); val free = st.availableBytes.toFloat()
        return Snapshot(
            load, ghz, cores, used, ramGb,
            (lvl * 100 / scale.coerceAtLeast(1)),
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            temp, 1f - free / total,
            "%.0f GB frei".format(free / 1e9)
        )
    }
}

object Http {
    fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Jarvis/1.0")
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Wetter über Open-Meteo (kostenlos, ohne Schlüssel). */
object Weather {
    data class Now(
        val place: String, val temp: Double, val feels: Double, val code: Int, val wind: Double,
        val humidity: Int, val min: Double, val max: Double, val rainProb: Int,
        val hourly: List<Float>, val sunset: String
    )

    @Volatile var last: Now? = null
    private var lastFetch = 0L
    private var cachedPlace = ""
    private var lat = 47.52; private var lon = 7.69

    fun describe(code: Int): String = when (code) {
        0 -> "Klar"
        1, 2 -> "Leicht bewölkt"
        3 -> "Bedeckt"
        45, 48 -> "Nebel"
        in 51..57 -> "Nieselregen"
        in 61..67 -> "Regen"
        in 71..77 -> "Schnee"
        in 80..82 -> "Regenschauer"
        in 85..86 -> "Schneeschauer"
        in 95..99 -> "Gewitter"
        else -> "–"
    }

    fun fetch(c: Context, force: Boolean = false): Now? {
        val place = Prefs.city(c)
        if (!force && last != null && place == cachedPlace && System.currentTimeMillis() - lastFetch < 15 * 60_000) return last
        if (place != cachedPlace) {
            val g = JSONObject(Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name=" + Http.enc(place)))
            val r = g.optJSONArray("results")?.optJSONObject(0)
            if (r != null) { lat = r.getDouble("latitude"); lon = r.getDouble("longitude") }
            cachedPlace = place
        }
        val j = JSONObject(Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                "&hourly=temperature_2m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunset" +
                "&forecast_days=2&timezone=auto"
        ))
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly").getJSONArray("temperature_2m")
        val hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hours = (hourNow until (hourNow + 24).coerceAtMost(hourly.length())).map { hourly.getDouble(it).toFloat() }
        last = Now(
            place, cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"),
            cur.getInt("weather_code"), cur.getDouble("wind_speed_10m"), cur.getInt("relative_humidity_2m"),
            d.getJSONArray("temperature_2m_min").getDouble(0), d.getJSONArray("temperature_2m_max").getDouble(0),
            d.getJSONArray("precipitation_probability_max").optInt(0), hours,
            d.getJSONArray("sunset").getString(0).substringAfter('T')
        )
        lastFetch = System.currentTimeMillis()
        return last
    }

    fun summary(): String = last?.let {
        "${it.place}: ${"%.0f".format(it.temp)}°C, ${describe(it.code)}, heute ${"%.0f".format(it.min)}–${"%.0f".format(it.max)}°C, Regen ${it.rainProb}%"
    } ?: "Wetter unbekannt"
}

/** Börsenkurse (Yahoo Finance, öffentliche Kursdaten). */
object Stocks {
    data class Quote(val symbol: String, val name: String, val price: Double, val change: Double, val series: List<Float>, val currency: String)

    @Volatile var last: List<Quote> = emptyList()
    private var lastFetch = 0L

    private val names = mapOf(
        "^SSMI" to "SMI", "^GSPC" to "S&P 500", "^IXIC" to "NASDAQ", "^GDAXI" to "DAX",
        "BTC-USD" to "BITCOIN", "NESN.SW" to "NESTLÉ", "NOVN.SW" to "NOVARTIS", "UBSG.SW" to "UBS"
    )

    fun fetch(c: Context, force: Boolean = false): List<Quote> {
        if (!force && last.isNotEmpty() && System.currentTimeMillis() - lastFetch < 5 * 60_000) return last
        val out = mutableListOf<Quote>()
        for (sym in Prefs.watchlist(c)) {
            try {
                val j = JSONObject(Http.get("https://query1.finance.yahoo.com/v8/finance/chart/" + Http.enc(sym) + "?range=1d&interval=15m"))
                val r = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = r.getJSONObject("meta")
                val price = meta.getDouble("regularMarketPrice")
                val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
                val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
                val series = mutableListOf<Float>()
                if (closes != null) for (i in 0 until closes.length()) if (!closes.isNull(i)) series.add(closes.getDouble(i).toFloat())
                out.add(Quote(sym, names[sym] ?: sym, price, (price - prev) / prev * 100, series, meta.optString("currency")))
            } catch (_: Exception) {}
        }
        if (out.isNotEmpty()) { last = out; lastFetch = System.currentTimeMillis() }
        return last
    }

    fun summary(): String = last.joinToString("; ") { "${it.name} %.2f (%+.2f%%)".format(it.price, it.change) }
}
