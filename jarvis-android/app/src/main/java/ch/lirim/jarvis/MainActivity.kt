package ch.lirim.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val REQ_SPEECH = 42
        private const val REQ_PERMS = 43
        private const val ORANGE = 0xFFFF6600.toInt()
        private const val PEACH = 0xFFFAE2D3.toInt()
    }

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var askedPerms = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.init(this)
        buildUi()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val explicit = i?.getBooleanExtra(EXTRA_LISTEN, false) == true
        val assist = i?.action == Intent.ACTION_ASSIST
        val launcher = i?.action == Intent.ACTION_MAIN && Prefs.autoListen(this)
        if ((explicit || assist || launcher) && Prefs.apiKey(this).isNotBlank() && !Agent.running) {
            logView.postDelayed({ listen() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.wakeWord(this) && !WakeWordService.running && !askedPerms) applyWakeWord()
        updateStatus()
        logView.text = Agent.fullLog()
        Agent.listener = { s ->
            logView.append(s + "\n")
            scrollDown()
        }
        scrollDown()
    }

    override fun onPause() {
        Agent.listener = null
        super.onPause()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun styled(b: Button, primary: Boolean): Button {
        b.isAllCaps = false
        b.textSize = if (primary) 20f else 15f
        b.setTextColor(if (primary) Color.WHITE else Color.BLACK)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) ORANGE else PEACH)
        }
        return b
    }

    private fun lp(topMargin: Int = 8, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { this.topMargin = dp(topMargin) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom + dp(12))
            insets
        }

        val title = TextView(this).apply {
            text = "Jarvis"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ORANGE)
        }
        statusView = TextView(this).apply { textSize = 14f; setTextColor(Color.DKGRAY) }

        val speak = styled(Button(this).apply { text = "🎙  Sprechen" }, true)
        speak.setOnClickListener { listen() }

        input = EditText(this).apply {
            hint = "Oder Auftrag eintippen …"
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val send = styled(Button(this).apply { text = "Senden" }, false)
        send.setOnClickListener { sendTyped() }
        val stop = styled(Button(this).apply { text = "Stopp" }, false)
        stop.setOnClickListener { Agent.stop() }
        val newChat = styled(Button(this).apply { text = "Neu" }, false)
        newChat.setOnClickListener { Agent.reset() }
        val w = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        row.addView(send, w)
        row.addView(stop, LinearLayout.LayoutParams(w))
        row.addView(newChat, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val access = styled(Button(this).apply { text = "Bedienungshilfe" }, false)
        access.setOnClickListener { openAccessibilitySettings() }
        val settings = styled(Button(this).apply { text = "Einstellungen" }, false)
        settings.setOnClickListener { showSettings() }
        row2.addView(access, LinearLayout.LayoutParams(w))
        row2.addView(settings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        logView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFFF6F6F6.toInt()) }
            addView(logView)
        }

        root.addView(title)
        root.addView(statusView, lp(2))
        root.addView(speak, lp(14, dp(72)))
        root.addView(input, lp(10))
        root.addView(row, lp(6))
        root.addView(row2, lp(6))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateStatus() {
        val acc = if (JarvisService.instance != null) "✅ Bedienungshilfe aktiv" else "❌ Bedienungshilfe aus – bitte einschalten"
        val key = if (Prefs.apiKey(this).isNotBlank()) "✅ API-Schlüssel gesetzt" else "❌ API-Schlüssel fehlt (Einstellungen)"
        val wake = if (Prefs.wakeWord(this)) "\n🎙 Hört auf «Hallo Jarvis»" else ""
        statusView.text = "$acc\n$key$wake"
    }

    // ------------------------------------------------------------------ Aktionen

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        run(t)
    }

    private fun run(cmd: String) {
        if (Prefs.apiKey(this).isBlank()) {
            Toast.makeText(this, "Bitte zuerst den API-Schlüssel eintragen.", Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        Agent.start(this, cmd)
    }

    private fun listen() {
        if (Agent.running) return
        Speaker.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-CH")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Was soll ich tun?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Keine Spracherkennung gefunden. Bitte die Google-App installieren/aktualisieren.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH) {
            val text = if (resultCode == RESULT_OK)
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
            if (!text.isNullOrBlank()) run(text) else WakeWordService.resume(this)
        }
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("Bedienungshilfe einschalten")
            .setMessage(
                "1. Auf «Weiter» tippen.\n" +
                    "2. «Installierte Apps» → «Jarvis» → einschalten.\n\n" +
                    "Ist der Schalter grau («Eingeschränkte Einstellung»)? Dann: Einstellungen → Apps → Jarvis → ⋮ oben rechts → «Eingeschränkte Einstellungen zulassen». Danach nochmals versuchen."
            )
            .setPositiveButton("Weiter") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App-Info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
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
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeWordService.start(this)
        } else {
            Toast.makeText(this, "Ohne Mikrofon-Berechtigung kann Jarvis nicht auf «Hallo Jarvis» hören.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val keyLabel = TextView(this).apply { text = "Claude-API-Schlüssel (sk-ant-…)" }
        val key = EditText(this).apply {
            setText(Prefs.apiKey(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-…"
        }
        val modelLabel = TextView(this).apply { text = "Modell" }
        val model = EditText(this).apply {
            setText(Prefs.model(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val auto = CheckBox(this).apply {
            text = "Beim Öffnen sofort zuhören"
            isChecked = Prefs.autoListen(this@MainActivity)
        }
        val bubble = CheckBox(this).apply {
            text = "Schwebenden 🎙-Knopf anzeigen"
            isChecked = Prefs.bubble(this@MainActivity)
        }
        val wake = CheckBox(this).apply {
            text = "Auf «Hallo Jarvis» hören (immer an)"
            isChecked = Prefs.wakeWord(this@MainActivity)
        }
        val web = CheckBox(this).apply {
            text = "Websuche erlauben"
            isChecked = Prefs.webSearch(this@MainActivity)
        }
        listOf(keyLabel, key, modelLabel, model, auto, bubble, wake, web).forEach { box.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                Prefs.setApiKey(this, key.text.toString())
                Prefs.setModel(this, model.text.toString())
                Prefs.setAutoListen(this, auto.isChecked)
                Prefs.setBubble(this, bubble.isChecked)
                Prefs.setWebSearch(this, web.isChecked)
                Prefs.setWakeWord(this, wake.isChecked)
                applyWakeWord()
                JarvisService.instance?.overlay?.let { if (bubble.isChecked) it.showBubble() else it.hideBubble() }
                updateStatus()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
