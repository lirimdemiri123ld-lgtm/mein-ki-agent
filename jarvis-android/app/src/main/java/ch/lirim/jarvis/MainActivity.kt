package ch.lirim.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ch.lirim.jarvis.hud.ArcReactorView
import ch.lirim.jarvis.hud.CountingText
import ch.lirim.jarvis.hud.Hud
import ch.lirim.jarvis.hud.HudBackgroundView
import ch.lirim.jarvis.hud.HudPanel
import ch.lirim.jarvis.hud.Meter
import ch.lirim.jarvis.hud.Sparkline
import ch.lirim.jarvis.hud.TypewriterLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val REQ_SPEECH = 42
        private const val REQ_PERMS = 43
        private const val REQ_MIC_LISTEN = 44
        private const val REQ_CONTACTS = 45
        private const val REQ_CAL = 46
    }

    private val main = Handler(Looper.getMainLooper())
    private var askedPerms = false
    private var visible = false

    // HUD-Elemente
    private lateinit var reactor: ArcReactorView
    private lateinit var kitt: ch.lirim.jarvis.hud.KittScanner
    private lateinit var transcript: TextView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var statusLine: TextView
    private lateinit var input: EditText
    private lateinit var logView: TextView
    private lateinit var typer: TypewriterLog

    private lateinit var cpuMeter: Meter
    private lateinit var cpuSpark: Sparkline
    private lateinit var cpuVal: TextView
    private lateinit var ramMeter: Meter
    private lateinit var ramVal: TextView
    private lateinit var batVal: TextView
    private lateinit var tempVal: TextView
    private lateinit var diskVal: TextView

    private lateinit var wxTemp: CountingText
    private lateinit var wxDesc: TextView
    private lateinit var wxRange: TextView
    private lateinit var wxWind: TextView
    private lateinit var wxSpark: Sparkline
    private lateinit var wxPlace: TextView

    private lateinit var marketBody: LinearLayout
    private lateinit var agendaBody: LinearLayout
    private lateinit var creditVal: TextView
    private lateinit var creditSub: TextView
    private lateinit var creditHint: TextView
    private lateinit var projectBody: LinearLayout
    private lateinit var commsBody: LinearLayout
    private lateinit var insightBody: LinearLayout
    private val panels = mutableListOf<HudPanel>()

    // Spracherkennung im eigenen Interface
    private var recognizer: SpeechRecognizer? = null
    private var preferGoogle = true
    private var usingGoogle = false
    @Volatile private var listening = false
    private var micLevel = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = JarvisApp.crashFile(this)
        if (crash.exists()) {
            val text = try { crash.readText() } catch (e: Exception) { "?" }
            crash.delete()
            showCrash(text)
            return
        }
        Speaker.init(this)
        buildUi()
        if (savedInstanceState == null) handleIntent(intent)
        // alte Hintergrund-Analyse (kostete Geld/Akku) endgültig abschalten
        try { getSystemService(android.app.job.JobScheduler::class.java).cancel(4711) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (crashMode) return
        val explicit = i?.getBooleanExtra(EXTRA_LISTEN, false) == true
        val assist = i?.action == Intent.ACTION_ASSIST
        val launcher = i?.action == Intent.ACTION_MAIN && Prefs.autoListen(this)
        if ((explicit || assist || launcher) && !Agent.running) {
            main.postDelayed({ listen() }, 450)
        }
    }

    private var crashMode = false

    /** Einfache Fehleranzeige ohne HUD (falls der letzte Start abgestürzt ist). */
    private fun showCrash(text: String) {
        crashMode = true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
            setBackgroundColor(Color.BLACK)
        }
        box.addView(TextView(this).apply {
            this.text = "Jarvis ist beim letzten Start abgestürzt.\nBitte Screenshot an Claude schicken:"
            setTextColor(Hud.ORANGE); textSize = 16f
        })
        val err = TextView(this).apply {
            this.text = text.take(3500); setTextColor(Color.WHITE); textSize = 11f; typeface = Hud.MONO
            setTextIsSelectable(true)
        }
        box.addView(ScrollView(this).apply { addView(err) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("KOPIEREN") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis-Fehler", text))
            Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
        })
        row.addView(chip("NEU STARTEN") { recreate() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        box.addView(row)
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        if (crashMode) return
        visible = true
        if (Prefs.wakeWord(this) && !WakeWordService.running && !askedPerms) applyWakeWord()
        else if (!askedPerms && !Contacts.canRead(this) && !Prefs.askedContacts(this)) {
            askedPerms = true
            Prefs.setAskedContacts(this)
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQ_CONTACTS)
        }
        typer.setAll(lastLines(Agent.fullLog(), 14))
        Agent.listener = { s -> typer.append(s) }
        NotificationStore.listener = { main.post { refreshComms() } }
        updateStatus()
        refreshProjects(); refreshComms(); refreshInsights(); refreshCredit(); refreshAgenda()
        main.removeCallbacks(frameTick); main.post(frameTick)
        main.removeCallbacks(secondTick); main.post(secondTick)
        refreshNet(force = false)
        // Spar-Modus: Analyse nur noch auf Knopfdruck (ANALYSE), nie automatisch beim Öffnen
        panels.forEachIndexed { i, p -> if (p.alpha < 1f) p.reveal(150L + i * 120L) }
    }

    override fun onPause() {
        if (crashMode) { super.onPause(); return }
        visible = false
        Agent.listener = null
        NotificationStore.listener = null
        main.removeCallbacks(frameTick)
        main.removeCallbacks(secondTick)
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Aufbau

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hudText(size: Float, color: Int = Hud.ICE, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = if (bold) Hud.MONO_BOLD else Hud.MONO
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Hud.CYAN)
        textSize = 12f
        typeface = Hud.MONO_BOLD
        letterSpacing = 0.12f
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.argb(110, 0, 60, 90))
            setStroke(dp(1), Hud.CYAN_SOFT)
            cornerRadius = dp(3).toFloat()
        }
        setOnClickListener {
            animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            onClick()
        }
    }

    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun panel(title: String): HudPanel = HudPanel(this, title).also { panels.add(it) }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(HudBackgroundView(this), FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(24)) }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            col.setPadding(dp(14), bars.top + dp(8), dp(14), bars.bottom + dp(16))
            insets
        }

        // Kopfzeile
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            setTextColor(Hud.CYAN)
            textSize = 22f
            typeface = Hud.HEAD
            letterSpacing = 0.25f
            setShadowLayer(18f, 0f, 0f, Hud.CYAN)
        }
        val timeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        clock = hudText(20f, Hud.ICE, true).apply { gravity = Gravity.END; setShadowLayer(12f, 0f, 0f, Hud.CYAN) }
        dateView = hudText(10f, Hud.CYAN_SOFT).apply { gravity = Gravity.END }
        timeBox.addView(clock); timeBox.addView(dateView)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(timeBox)
        col.addView(head)

        statusLine = hudText(10f, Hud.CYAN).apply { letterSpacing = 0.08f }
        col.addView(statusLine, lp(4))

        // ── Oberste Reihe: Wetter · Jarvis (klein, rund, Mitte) · Guthaben
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pWx = panel("Wetter")
        wxPlace = pWx.text(9f, Hud.CYAN_SOFT).apply { maxLines = 1 }
        val wxT = hudText(24f, Hud.ICE, true).apply { setShadowLayer(14f, 0f, 0f, Hud.CYAN) }
        pWx.body.addView(wxT)
        wxTemp = CountingText(wxT) { "%.1f°".format(it) }
        wxDesc = pWx.text(10f, Hud.CYAN).apply { maxLines = 1 }
        wxRange = pWx.text(9f).apply { maxLines = 1 }
        wxWind = pWx.text(9f).apply { maxLines = 1 }
        wxSpark = Sparkline(this, Hud.ORANGE)
        pWx.body.addView(wxSpark, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(2) })

        reactor = ArcReactorView(this)
        reactor.setOnClickListener { if (listening) stopListening() else listen() }
        reactor.setOnLongClickListener { Agent.stop(); Speaker.stop(); stopListening(); true }

        val pCr = panel("Guthaben")
        creditVal = hudText(20f, Hud.ICE, true).apply { setShadowLayer(12f, 0f, 0f, Hud.CYAN) }
        pCr.body.addView(creditVal)
        creditSub = pCr.text(9f, Hud.CYAN_SOFT)
        creditHint = pCr.text(9f, Hud.ORANGE)
        pCr.setOnClickListener { creditDialog() }

        top.addView(pWx, LinearLayout.LayoutParams(0, dp(150), 1f))
        top.addView(reactor, LinearLayout.LayoutParams(dp(138), dp(138)).apply { marginStart = dp(4); marginEnd = dp(4) })
        top.addView(pCr, LinearLayout.LayoutParams(0, dp(150), 1f))
        col.addView(top, lp(6))

        kitt = ch.lirim.jarvis.hud.KittScanner(this).apply {
            visibility = if (Prefs.voiceFx(this@MainActivity) == "kitt") android.view.View.VISIBLE else android.view.View.GONE
        }
        col.addView(kitt, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(4); leftMargin = dp(60); rightMargin = dp(60) })

        transcript = hudText(13f, Hud.ICE).apply {
            gravity = Gravity.CENTER
            text = "Tippe auf den Kreis oder sag «Hallo Jarvis»"
            setShadowLayer(10f, 0f, 0f, Hud.CYAN)
            minLines = 1
            maxLines = 3
        }
        col.addView(transcript, lp(4))

        // ── Zweite Reihe: System · Heute (Termine, Wecker, Nachrichten)
        val twin = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pSys = panel("System")
        cpuVal = pSys.row("CPU")
        cpuMeter = Meter(this); pSys.body.addView(cpuMeter, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(2) })
        cpuSpark = Sparkline(this).apply { fixedMax = 1f }
        pSys.body.addView(cpuSpark, LinearLayout.LayoutParams(-1, dp(20)).apply { topMargin = dp(3) })
        ramVal = pSys.row("RAM")
        ramMeter = Meter(this); pSys.body.addView(ramMeter, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(2) })
        batVal = pSys.row("Akku")
        tempVal = pSys.row("Temp")
        diskVal = pSys.row("Disk")

        val pDay = panel("Heute")
        agendaBody = pDay.body
        pDay.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL)
            else refreshAgenda()
        }

        twin.addView(pSys, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(5) })
        twin.addView(pDay, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(5) })
        col.addView(twin, lp(8))

        val pMk = panel("Märkte · Live")
        marketBody = pMk.body
        pMk.setOnClickListener { refreshNet(force = true) }
        col.addView(pMk, lp(8))

        // Eingabe
        val inRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "> Befehl eingeben …"
            setHintTextColor(Hud.CYAN_SOFT)
            setTextColor(Hud.ICE)
            typeface = Hud.MONO
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 0, 25, 45)); setStroke(dp(1), Hud.CYAN_SOFT); cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        inRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inRow.addView(chip("▶") { sendTyped() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(inRow, lp(10))

        // Befehlsleiste
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipLp = { LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) } }
        chips.addView(chip("■ STOPP") { Agent.stop(); Speaker.stop(); stopListening() }, chipLp())
        chips.addView(chip("↻ NEU") { Agent.reset() }, chipLp())
        chips.addView(chip("⬢ GEHIRN") { BrainDialog.show(this) }, chipLp())
        chips.addView(chip("◎ STIMME") { voiceMenu() }, chipLp())
        chips.addView(chip("◈ GEDÄCHTNIS") { MemoryDialog.show(this) }, chipLp())
        chips.addView(chip("✦ BRIEFING") { runAnalysis() }, chipLp())
        chips.addView(chip("⚿ ZUGRIFF") { accessMenu() }, chipLp())
        chips.addView(chip("⚙ SYSTEM") { showSettings() }, chipLp())
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(10))

        // Panel: Analyse / Tipps
        val pIns = panel("Lagebericht · gratis")
        insightBody = pIns.body
        pIns.setOnClickListener { runAnalysis() }
        col.addView(pIns, lp(14))

        val pPr = panel("Projekte")
        projectBody = pPr.body
        col.addView(pPr, lp(12))

        val pCm = panel("Kommunikation")
        commsBody = pCm.body
        col.addView(pCm, lp(12))

        val pLog = panel("Protokoll")
        logView = pLog.text(11f, Hud.ICE).apply { setLineSpacing(0f, 1.1f); minLines = 8 }
        typer = TypewriterLog(logView) {
            val lines = logView.text.split('\n')
            if (lines.size > 16) logView.text = lines.takeLast(16).joinToString("\n")
        }
        col.addView(pLog, lp(12))

        setContentView(root)
    }

    private fun lastLines(s: String, n: Int) = s.trimEnd().split('\n').takeLast(n).joinToString("\n") + "\n"

    // ------------------------------------------------------------------ Animation & Live-Daten

    private val frameTick = object : Runnable {
        override fun run() {
            reactor.state = when {
                listening -> ArcReactorView.State.LISTENING
                Speaker.speaking -> ArcReactorView.State.SPEAKING
                Agent.running -> ArcReactorView.State.THINKING
                else -> ArcReactorView.State.IDLE
            }
            reactor.level = if (listening) micLevel else Speaker.level
            kitt.level = if (Speaker.speaking) Speaker.level else 0f
            reactor.subLabel = when (reactor.state) {
                ArcReactorView.State.LISTENING -> "Ich höre"
                ArcReactorView.State.SPEAKING -> "Spreche"
                ArcReactorView.State.THINKING -> "Verarbeite"
                ArcReactorView.State.IDLE -> "Online"
            }
            // Hat Jarvis eine Rückfrage gestellt, hört er danach von selbst zu (Antwort gehört zum selben Auftrag)
            if (Agent.awaitingAnswer && !Agent.running && !Speaker.speaking && !listening && askedFor != Agent.questionId) {
                askedFor = Agent.questionId
                main.postDelayed({ if (visible && !listening && !Agent.running) listen() }, 400)
            }
            if (visible) main.postDelayed(this, 50)
        }
    }

    private var askedFor = 0
    private var tickCount = 0
    private val secondTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm:ss", Locale.GERMAN).format(now)
            dateView.text = SimpleDateFormat("EEE d. MMM yyyy", Locale.GERMAN).format(now).uppercase()
            if (tickCount % 2 == 0) refreshSystem()
            if (tickCount % 10 == 0) { updateStatus(); refreshCredit() }
            if (tickCount % 60 == 0) refreshAgenda()
            if (tickCount % 300 == 299) refreshNet(force = false)
            tickCount++
            if (visible) main.postDelayed(this, 1000)
        }
    }

    // ── Guthaben (Schätzung: dein eingetragener Betrag minus Jarvis-Verbrauch)
    private fun refreshCredit() {
        val bal = Prefs.creditBalance(this)
        val blocked = System.currentTimeMillis() < Prefs.cloudBlockedUntil(this)
        if (bal == null) {
            creditVal.text = "– \$"
            creditVal.setTextColor(Hud.CYAN_SOFT)
            creditSub.text = "Monat ≈ ${Agent.rp(Prefs.monthCost(this))}"
            creditHint.text = "Antippen: Betrag eintragen"
            return
        }
        creditVal.text = "%.2f \$".format(Locale.US, bal)
        val color = when { blocked || bal <= 0.2 -> Hud.RED; bal < 1.5 -> Hud.ORANGE; else -> Hud.GREEN }
        creditVal.setTextColor(color)
        creditSub.text = "≈ %.2f Fr.\nMonat ≈ %s\nGratis: %d · Gelernt: %d".format(Locale.US, bal * 0.8,
            Agent.rp(Prefs.monthCost(this)), Prefs.savedTasks(this), RecipeStore.count(this))
        creditHint.text = when {
            blocked || bal <= 0.2 -> "⚠ AUFLADEN NÖTIG"
            bal < 1.5 -> "Bald aufladen"
            else -> ""
        }
    }

    private fun creditDialog() {
        val et = EditText(this).apply {
            hint = "z. B. 4.83"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            Prefs.creditBalance(this@MainActivity)?.let { setText("%.2f".format(Locale.US, it)) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = "Anthropic zeigt dein Guthaben nur in der Console an (Billing). Trag den Betrag in Dollar hier ein – Jarvis zieht danach jede Aufgabe selbst ab."
        })
        box.addView(et)
        android.app.AlertDialog.Builder(this).setTitle("Claude-Guthaben")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                et.text.toString().replace(',', '.').toDoubleOrNull()?.let {
                    Prefs.setCreditBalance(this, it)
                    if (it > 0.2) Prefs.setCloudBlockedUntil(this, 0L)
                    refreshCredit()
                }
            }
            .setNeutralButton("Console öffnen") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://platform.claude.com/settings/billing")))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── Heute: nächster Wecker, Termine, ungelesene Nachrichten
    private fun refreshAgenda() {
        agendaBody.removeAllViews()
        fun line(t: String, color: Int = Hud.ICE, size: Float = 10f) =
            agendaBody.addView(hudText(size, color).apply { text = t; maxLines = 2 })
        try {
            val am = getSystemService(android.app.AlarmManager::class.java)
            am.nextAlarmClock?.let {
                line("⏰ " + SimpleDateFormat("EEE HH:mm", Locale.GERMAN).format(Date(it.triggerTime)), Hud.ORANGE, 11f)
            }
        } catch (_: Exception) {}
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            val ev = Agenda.next(this, 3)
            if (ev.isEmpty()) line("Keine Termine in den nächsten 2 Tagen.", Hud.CYAN_SOFT)
            ev.forEach { (time, title) -> line("◆ $time", Hud.CYAN, 10f); line(title, Hud.ICE, 10f) }
        } else line("Antippen, um Termine anzuzeigen.", Hud.CYAN_SOFT)
        val n = NotificationStore.recent(40).count { System.currentTimeMillis() - it.time < 12 * 3600_000L }
        if (n > 0) line("✉ $n neue Meldungen", Hud.CYAN_SOFT)
    }

    private fun refreshSystem() {
        val s = try { SystemStats.snapshot(this) } catch (e: Exception) { return }
        val load = s.cpuLoad
        if (load != null) {
            cpuMeter.target = load
            cpuSpark.push(load)
            cpuVal.text = "%.0f%% %.1fGHz".format(load * 100, s.cpuGhz ?: 0f)
        } else {
            cpuVal.text = "${s.cores} KERNE"
            val pseudo = (0.15f + Math.random().toFloat() * 0.1f)
            cpuMeter.target = pseudo; cpuSpark.push(pseudo)
        }
        ramMeter.target = s.ramUsed
        ramVal.text = s.ramGb
        batVal.text = "${s.battery}%" + if (s.charging) " ⚡" else ""
        tempVal.text = "%.1f°C".format(s.batteryTemp)
        diskVal.text = s.storageText
    }

    private fun refreshNet(force: Boolean) {
        Thread {
            val wx = try { Weather.fetch(applicationContext, force) } catch (e: Exception) { null }
            val q = try { Stocks.fetch(applicationContext, force) } catch (e: Exception) { emptyList() }
            main.post {
                if (wx != null) {
                    wxPlace.text = wx.place.uppercase()
                    wxTemp.to(wx.temp.toFloat())
                    wxDesc.text = Weather.describe(wx.code).uppercase()
                    wxRange.text = "▼%.0f° ▲%.0f° ☂%d%%".format(wx.min, wx.max, wx.rainProb)
                    wxWind.text = "WIND %.0f km/h".format(wx.wind)
                    wxSpark.set(wx.hourly)
                } else if (wxPlace.text.isNullOrEmpty()) {
                    wxPlace.text = "KEINE VERBINDUNG"
                }
                renderMarkets(q)
            }
        }.start()
    }

    private fun renderMarkets(q: List<Stocks.Quote>) {
        marketBody.removeAllViews()
        if (q.isEmpty()) {
            marketBody.addView(hudText(11f, Hud.CYAN_SOFT).apply { text = "Lade Kurse … (antippen zum Aktualisieren)" })
            return
        }
        q.forEachIndexed { i, it ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val up = it.change >= 0
            val color = if (up) Hud.GREEN else Hud.RED
            row.addView(hudText(11f, Hud.CYAN, true).apply { text = it.name; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1.1f))
            row.addView(Sparkline(this, color).apply { set(it.series) }, LinearLayout.LayoutParams(0, dp(22), 1f))
            row.addView(hudText(11f, Hud.ICE).apply { text = "%,.2f".format(it.price); gravity = Gravity.END },
                LinearLayout.LayoutParams(0, -2, 1.1f))
            row.addView(hudText(11f, color, true).apply {
                text = (if (up) "▲" else "▼") + "%.2f%%".format(kotlin.math.abs(it.change)); gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, -2, 0.9f))
            row.alpha = 0f; row.translationX = dp(40).toFloat()
            row.animate().alpha(1f).translationX(0f).setStartDelay(i * 90L).setDuration(400).start()
            marketBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun refreshProjects() {
        projectBody.removeAllViews()
        val list = Memory.projectList(this)
        if (list.isEmpty()) {
            projectBody.addView(hudText(11f, Hud.CYAN_SOFT).apply {
                text = "Noch keine Projekte. Sag z. B.: «Projekt Weihnachtsfeier ist zu 40 Prozent fertig.»"
            })
            return
        }
        list.take(6).forEach { p ->
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(hudText(12f, Hud.ICE, true).apply { text = p.name.uppercase() }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(hudText(12f, Hud.CYAN, true).apply { text = "${p.progress}%" })
            projectBody.addView(head, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            projectBody.addView(Meter(this).apply { target = p.progress / 100f }, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(3) })
            if (p.status.isNotBlank()) projectBody.addView(hudText(10f, Hud.CYAN_SOFT).apply { text = p.status })
        }
    }

    private fun refreshComms() {
        commsBody.removeAllViews()
        if (!NotificationStore.hasAccess(this)) {
            commsBody.addView(hudText(11f, Hud.ORANGE).apply {
                text = "Kein Zugriff auf Nachrichten. Antippen zum Freischalten."
                setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
            return
        }
        val items = NotificationStore.recent(7)
        if (items.isEmpty()) {
            commsBody.addView(hudText(11f, Hud.CYAN_SOFT).apply { text = "Kanal ruhig. Keine neuen Nachrichten." })
            return
        }
        val f = SimpleDateFormat("HH:mm", Locale.GERMAN)
        items.forEach { n ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            box.addView(hudText(10f, Hud.CYAN, true).apply {
                text = "${f.format(Date(n.time))} ▸ ${n.app.uppercase()} ▸ ${n.title}"
            })
            box.addView(hudText(11f, Hud.ICE).apply { text = n.text; maxLines = 2 })
            commsBody.addView(box)
        }
    }

    private fun refreshInsights() {
        insightBody.removeAllViews()
        insightBody.addView(hudText(11f, Hud.ICE).apply { text = Direct.briefing(this@MainActivity, fetch = false) })
    }

    /** Lagebericht: regelbasiert aus Wetter, Wecker, Terminen und Meldungen – ohne KI, gratis. */
    private fun runAnalysis() {
        if (!Agent.running) Agent.start(this, "Briefing")
        main.postDelayed({ refreshInsights() }, 2500)
    }

    private fun updateStatus() {
        fun dot(ok: Boolean) = if (ok) "●" else "○"
        val acc = JarvisService.instance != null
        val key = Prefs.apiKey(this).isNotBlank()
        val wake = Prefs.wakeWord(this) && WakeWordService.running
        val msg = NotificationStore.hasAccess(this)
        val neuro = Prefs.neuralVoice(this).isNotBlank()
        statusLine.text = "${dot(acc)} ZUGRIFF  ${dot(key)} CLAUDE  ${dot(wake)} WACHWORT  ${dot(msg)} KOMM  ${dot(neuro)} NEURO"
        statusLine.setTextColor(if (acc && key) Hud.CYAN else Hud.ORANGE)
    }

    // ------------------------------------------------------------------ Spracheingabe (eigenes Interface)

    private fun listen() {
        if (Agent.running || Contacts.inCall(this)) return
        Speaker.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_LISTEN)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { listenFallback(); return }
        if (recognizer == null) {
            // Googles Erkennung versteht Deutsch (Schweiz) am besten – wenn vorhanden, gezielt wählen
            val google = android.content.ComponentName("com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
            usingGoogle = preferGoogle && try { packageManager.getServiceInfo(google, 0); true } catch (e: Exception) { false }
            recognizer = (if (usingGoogle) SpeechRecognizer.createSpeechRecognizer(this, google)
                          else SpeechRecognizer.createSpeechRecognizer(this)).apply { setRecognitionListener(recListener) }
        }
        val lang = Prefs.sttLang(this)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        WakeWordService.pause(this)
        listening = true
        transcript.text = "…"
        try { recognizer?.startListening(i) } catch (e: Exception) { listening = false; listenFallback() }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { transcript.text = "Ich höre …" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { micLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { micLevel = 0f }
        override fun onError(error: Int) {
            listening = false; micLevel = 0f
            transcript.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts verstanden. Tippe nochmals auf den Reaktor."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt."
                else -> "Spracherkennung nicht bereit (Fehler $error). Tippe nochmals."
            }
            // Klappt die gezielt gewählte Google-Erkennung nicht, beim nächsten Mal die Standard-Erkennung nehmen
            if (usingGoogle && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                preferGoogle = false
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
            }
            WakeWordService.resume(this@MainActivity)
        }
        override fun onResults(results: Bundle?) {
            listening = false; micLevel = 0f
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            // Von mehreren Hör-Varianten die nehmen, die Jarvis als klaren Befehl versteht
            val text = list.take(4).firstOrNull { try { ch.lirim.jarvis.core.Intents.parse(it) != null } catch (e: Exception) { false } }
                ?: list.firstOrNull()
            if (!text.isNullOrBlank()) {
                transcript.text = "«$text»"
                run(text)
            } else WakeWordService.resume(this@MainActivity)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { transcript.text = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Falls keine eingebettete Erkennung verfügbar ist: Google-Dialog. */
    private fun listenFallback() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.sttLang(this))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Keine Spracherkennung gefunden. Bitte die Google-App installieren.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH) {
            val text = if (resultCode == RESULT_OK)
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
            if (!text.isNullOrBlank()) { transcript.text = "«$text»"; run(text) } else WakeWordService.resume(this)
        }
    }

    // ------------------------------------------------------------------ Aktionen

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        transcript.text = "«$t»"
        run(t)
    }

    private fun run(cmd: String) {
        // Direktbefehle (Wecker, Anruf, Nachricht …) laufen auch ohne API-Schlüssel
        Agent.start(this, cmd)
    }

    private fun voiceMenu() {
        AlertDialog.Builder(this)
            .setTitle("Stimme")
            .setItems(arrayOf(
                "★ Jarvis-Stil-Paket (ruhig, Raumhall, «KI im Anzug»)",
                "★ KITT-Stil-Paket (tief, metallisch, Auto-Computer)",
                "🧠 Neuronale Stimme selbst wählen",
                "🔈 Systemstimme: Stil, Tonhöhe, Tempo",
                "✕ Klangfilter aus"
            )) { _, i ->
                when (i) {
                    0 -> NeuralVoiceDialog.applyPack(this, "jarvis")
                    1 -> NeuralVoiceDialog.applyPack(this, "kitt")
                    2 -> NeuralVoiceDialog.show(this)
                    3 -> VoiceDialog.show(this)
                    4 -> { Prefs.setVoiceFx(this, "none"); Speaker.say("Klangfilter aus.") }
                }
                main.postDelayed({ kitt.visibility = if (Prefs.voiceFx(this) == "kitt") android.view.View.VISIBLE else android.view.View.GONE }, 300)
            }
            .show()
    }

    private fun accessMenu() {
        AlertDialog.Builder(this)
            .setTitle("Zugriffe")
            .setItems(arrayOf(
                (if (JarvisService.instance != null) "✓ " else "✗ ") + "Bedienungshilfe (Apps bedienen)",
                (if (NotificationStore.hasAccess(this)) "✓ " else "✗ ") + "Benachrichtigungen (WhatsApp, Mail, SMS lesen)",
                "Akku: nicht einschränken (für Hintergrund)",
                (if (Contacts.canRead(this) && Contacts.canCall(this)) "✓ " else "✗ ") + "Kontakte & Anrufe"
            )) { _, i ->
                when (i) {
                    3 -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQ_CONTACTS)
                    0 -> openAccessibilitySettings()
                    1 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName")))
                }
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Bedienungshilfe einschalten")
            .setMessage(
                "1. Auf «Weiter» tippen.\n" +
                    "2. «Installierte Apps» → «Jarvis» → einschalten.\n\n" +
                    "Ist der Schalter grau? Einstellungen → Apps → Jarvis → ⋮ → «Eingeschränkte Einstellungen zulassen»."
            )
            .setPositiveButton("Weiter") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applyWakeWord() {
        if (!Prefs.wakeWord(this)) {
            WakeWordService.stop(this)
            return
        }
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            askedPerms = true
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return
        }
        if (!WakeWordService.running) WakeWordService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_PERMS -> if (mic) WakeWordService.start(this)
                else Toast.makeText(this, "Ohne Mikrofon kann Jarvis nicht auf «Hallo Jarvis» hören.", Toast.LENGTH_LONG).show()
            REQ_MIC_LISTEN -> if (mic) listen() else listenFallback()
            REQ_CAL -> refreshAgenda()
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(8), 0, 0) }
        val key = EditText(this).apply {
            setText(Prefs.apiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-…"
        }
        val model = EditText(this).apply { setText(Prefs.model(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT }
        val city = EditText(this).apply { setText(Prefs.city(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT }
        val watch = EditText(this).apply {
            setText(Prefs.watchlist(this@MainActivity).joinToString(","))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        fun cb(t: String, v: Boolean) = CheckBox(this).apply { text = t; isChecked = v }
        val auto = cb("Beim Öffnen sofort zuhören", Prefs.autoListen(this))
        val bubble = cb("Schwebenden Knopf anzeigen", Prefs.bubble(this))
        val wake = cb("Auf «Hallo Jarvis» hören (immer an)", Prefs.wakeWord(this))
        val web = cb("Websuche erlauben (≈ 1 Rp. pro Suche; nur wenn du «im Internet» sagst)", Prefs.webSearch(this))
        val hd = cb("Spracherkennung auf Hochdeutsch (de-DE) statt Schweiz (de-CH)", Prefs.sttLang(this) == "de-DE")

        box.addView(label("Claude-API-Schlüssel")); box.addView(key)
        box.addView(label("Modell")); box.addView(model)
        box.addView(label("Ort fürs Wetter")); box.addView(city)
        box.addView(label("Börsen-Watchlist (Yahoo-Symbole, Komma)")); box.addView(watch)
        listOf(auto, bubble, wake, web, hd).forEach { box.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("System")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Speichern") { _, _ ->
                Prefs.setApiKey(this, key.text.toString())
                Prefs.setModel(this, model.text.toString())
                Prefs.setCity(this, city.text.toString())
                Prefs.setWatchlist(this, watch.text.toString())
                Prefs.setAutoListen(this, auto.isChecked)
                Prefs.setBubble(this, bubble.isChecked)
                Prefs.setWebSearch(this, web.isChecked)
                Prefs.setWakeWord(this, wake.isChecked)
                Prefs.setSttLang(this, if (hd.isChecked) "de-DE" else "de-CH")
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
                applyWakeWord()
                JarvisService.instance?.overlay?.let { if (bubble.isChecked) it.showBubble() else it.hideBubble() }
                updateStatus()
                refreshNet(force = true)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
