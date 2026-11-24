package ch.lirim.jarvis2

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Einrichtung und Kontrolle: Status, Tunnel, Connector-Link, Pause, Protokoll. */
class MainActivity : Activity() {

    private val orange = Color.parseColor("#FF6600")
    private val peach = Color.parseColor("#FAE2D3")
    private val dark = Color.parseColor("#1C1C1E")

    private lateinit var status: TextView
    private lateinit var connector: TextView
    private lateinit var tunnel: EditText
    private lateinit var pause: Switch
    private lateinit var log: TextView

    companion object {
        const val TUNNEL_CMD = "pkg install -y cloudflared && cloudflared tunnel --url http://127.0.0.1:${Prefs.PORT}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply { addView(box); setBackgroundColor(Color.WHITE) }
        setContentView(scroll)

        box.addView(TextView(this).apply {
            text = "Jarvis 2.0"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(dark)
        })
        box.addView(TextView(this).apply {
            text = "Du sprichst mit Claude – Jarvis 2.0 führt auf dem Handy aus."
            textSize = 15f
            setTextColor(orange)
            setPadding(0, 0, 0, dp(14))
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(dark)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(peach)
        }
        box.addView(status)

        section(box, "1  Zugriff erlauben")
        box.addView(button("Bedienungshilfe einschalten") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Installierte Apps → Jarvis 2.0 → einschalten. Grau? Siehe Hinweis unten.")
        })
        box.addView(button("Kontakte & Anrufe erlauben") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_MEDIA_IMAGES), 1)
        })
        box.addView(button("Benachrichtigungen lesen erlauben") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        box.addView(hint("Ist der Schalter grau («Eingeschränkte Einstellung»)? Einstellungen → Apps → Jarvis 2.0 → ⋮ oben rechts → «Eingeschränkte Einstellungen zulassen». Dann nochmals versuchen."))

        section(box, "2  Tunnel starten (Termux)")
        box.addView(hint("Damit Claude dein Handy erreicht, braucht es einen sicheren Tunnel. Installiere Termux (von F-Droid oder GitHub, nicht Play Store), öffne es und füge diesen Befehl ein:"))
        box.addView(TextView(this).apply {
            text = TUNNEL_CMD
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(dark)
            setTextIsSelectable(true)
        })
        box.addView(button("Befehl kopieren und Termux öffnen") {
            copy("Tunnel-Befehl", TUNNEL_CMD)
            val i = packageManager.getLaunchIntentForPackage("com.termux")
            if (i != null) startActivity(i) else toast("Befehl kopiert. Termux ist noch nicht installiert.")
        })
        box.addView(hint("Termux zeigt eine Adresse wie https://xyz-abc.trycloudflare.com – kopiere sie und füge sie hier ein. Termux muss offen bleiben (im Hintergrund ist ok)."))
        tunnel = EditText(this).apply {
            hint = "https://….trycloudflare.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Prefs.tunnelUrl(this@MainActivity))
        }
        box.addView(tunnel)
        box.addView(button("Tunnel-Adresse speichern") {
            val v = tunnel.text.toString().trim()
            if (v.isNotEmpty() && !v.startsWith("https://")) {
                toast("Die Adresse muss mit https:// beginnen.")
            } else {
                Prefs.setTunnelUrl(this, v)
                refresh()
                toast("Gespeichert.")
            }
        })

        section(box, "3  Mit Claude verbinden")
        box.addView(hint("Auf claude.ai (am besten am PC): Einstellungen → Connectors → «Eigenen Connector hinzufügen». Name: Jarvis 2.0, URL: der Link unten. Danach ist er auch in der Claude-App auf dem Handy verfügbar."))
        connector = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(dark)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(peach)
            setTextIsSelectable(true)
        }
        box.addView(connector)
        box.addView(button("Connector-Link kopieren") {
            val url = Prefs.connectorUrl(this)
            if (url.isEmpty()) toast("Zuerst die Tunnel-Adresse speichern.") else { copy("Jarvis 2.0 Connector", url); toast("Link kopiert.") }
        })
        box.addView(hint("Der Link enthält einen geheimen Schlüssel. Teile ihn mit niemandem."))

        section(box, "Kontrolle")
        pause = Switch(this).apply {
            text = "Jarvis 2.0 pausieren (Claude kann nichts tun)"
            textSize = 15f
            isChecked = Prefs.paused(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setPaused(this@MainActivity, on); ActionLog.add(if (on) "Pausiert" else "Fortgesetzt"); refresh() }
        }
        box.addView(pause)
        box.addView(button("Neuen geheimen Schlüssel erzeugen", secondary = true) {
            AlertDialog.Builder(this)
                .setTitle("Neuen Schlüssel erzeugen?")
                .setMessage("Der alte Connector-Link funktioniert danach nicht mehr. Du musst den neuen Link in Claude eintragen.")
                .setPositiveButton("Erzeugen") { _, _ -> Prefs.newToken(this); ActionLog.add("Neuer Schlüssel erzeugt"); refresh() }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
        box.addView(hint("Gesperrt für Claude: Banking- und Bezahl-Apps (TWINT, PostFinance, UBS, Raiffeisen, BLKB, Revolut, Wallets …), Passwort-Apps, Android-Einstellungen und Jarvis 2.0 selbst. Sofort alles stoppen: Bedienungshilfe ausschalten."))

        section(box, "Protokoll")
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(dark)
            setTextIsSelectable(true)
        }
        box.addView(log)
    }

    override fun onResume() {
        super.onResume()
        ActionLog.listener = { refresh() }
        JarvisService.instance?.startMcp()
        refresh()
    }

    override fun onPause() {
        ActionLog.listener = null
        super.onPause()
    }

    private fun refresh() {
        val svc = JarvisService.instance
        val server = svc?.mcp?.running == true
        fun mark(ok: Boolean) = if (ok) "✅" else "⚪"
        val lines = listOf(
            "${mark(svc != null)} Bedienungshilfe ${if (svc != null) "eingeschaltet" else "aus – unter 1 einschalten"}",
            "${mark(server)} MCP-Server ${if (server) "läuft (Port ${Prefs.PORT})" else "läuft nicht"}",
            "${mark(Prefs.tunnelUrl(this).isNotEmpty())} Tunnel-Adresse ${if (Prefs.tunnelUrl(this).isNotEmpty()) "gespeichert" else "fehlt – unter 2"}",
            "${mark(Contacts.canRead(this))} Kontakte",
            "${mark(ContactPhoto.canWrite(this) && ContactPhoto.canReadImages(this))} Kontaktbilder ändern (Fotos + Kontakte)",
            "${mark(NotificationListener.hasAccess(this))} Benachrichtigungen",
            if (Prefs.paused(this)) "⏸ PAUSIERT – Claude kann nichts tun" else "▶ Bereit für Aufträge von Claude",
        )
        status.text = lines.joinToString("\n")
        val url = Prefs.connectorUrl(this)
        connector.text = if (url.isEmpty()) "(erscheint, sobald die Tunnel-Adresse gespeichert ist)" else url
        log.text = ActionLog.text()
    }

    // ------------------------------------------------------------------ Bausteine

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }

    private fun section(box: LinearLayout, title: String) {
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(dark)
            setPadding(0, dp(22), 0, dp(6))
        })
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#555555"))
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun button(label: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(if (secondary) orange else Color.WHITE)
        background = if (secondary) GradientDrawable().apply {
            setColor(Color.WHITE); setStroke(dp(2), orange); cornerRadius = dp(10).toFloat()
        } else rounded(orange)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) }
        setOnClickListener(View.OnClickListener { onClick() })
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
