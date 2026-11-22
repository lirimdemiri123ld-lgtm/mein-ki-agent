# Jarvis 2.0 – Claude steuert dein Handy (MCP)

Jarvis 2.0 denkt nicht selbst. Du sprichst oder schreibst mit Claude in der Claude-App,
und Claude schickt die Aufträge über einen eigenen MCP-Connector an Jarvis 2.0 auf dem Handy.
Das Denken läuft über dein Claude-Abo, es braucht kein API-Guthaben.

Das alte Jarvis (`jarvis-android`, App-ID `ch.lirim.jarvis`) bleibt unverändert.
Jarvis 2.0 hat die App-ID `ch.lirim.jarvis2` und wird daneben installiert.

## Download
https://github.com/lirimdemiri123ld-lgtm/mein-ki-agent/releases/download/jarvis2-latest/Jarvis2.apk

## So funktioniert es
```
Du ──▶ Claude-App ──▶ Anthropic-Cloud ──▶ Cloudflare-Tunnel ──▶ Termux (cloudflared)
                                                               └─▶ Jarvis 2.0 MCP-Server 127.0.0.1:8765
                                                                   └─▶ Bedienungshilfe: Apps öffnen, lesen, tippen
```

## Einrichtung (Samsung Galaxy S25)
1. **Jarvis2.apk** installieren.
2. Jarvis 2.0 öffnen → «Bedienungshilfe einschalten» → Installierte Apps → Jarvis 2.0 → ein.
   Grau? Einstellungen → Apps → Jarvis 2.0 → ⋮ → «Eingeschränkte Einstellungen zulassen».
3. Optional: «Kontakte & Anrufe erlauben», «Benachrichtigungen lesen erlauben».
4. **Termux** installieren (F-Droid oder github.com/termux/termux-app/releases, nicht Play Store).
5. In Jarvis 2.0 «Befehl kopieren und Termux öffnen» → in Termux einfügen → Enter.
   Termux zeigt `https://….trycloudflare.com`. Diese Adresse in Jarvis 2.0 einfügen und speichern.
6. «Connector-Link kopieren». Auf claude.ai → Einstellungen → Connectors → Eigenen Connector hinzufügen,
   Name «Jarvis 2.0», URL einfügen.
7. In der Claude-App im Chat über «+» → Connectors → Jarvis 2.0 einschalten. Dann zum Beispiel:
   «Schick Gruja auf WhatsApp, dass ich in 20 Minuten da bin.»

**Damit es zuverlässig läuft:** Für Termux und Jarvis 2.0 die Akku-Optimierung ausschalten
(Einstellungen → Apps → … → Akku → «Nicht eingeschränkt»). In Termux die Benachrichtigung
herunterziehen → «Acquire wakelock».

**Hinweis Quick-Tunnel:** Die trycloudflare-Adresse ändert sich bei jedem Neustart von Termux.
Dann neue Adresse in Jarvis 2.0 speichern und den Connector-Link in Claude aktualisieren.
Für eine feste Adresse braucht es einen Cloudflare-Account mit eigener Domain (benannter Tunnel).

## Werkzeuge für Claude
apps_auflisten, app_oeffnen, bildschirm_lesen, screenshot, tippen, tippen_xy, lange_druecken,
text_eingeben, scrollen, wischen, taste, warten, url_oeffnen, kontakt_suchen, whatsapp_senden,
anrufen, nachrichten_lesen, wecker_stellen, timer_stellen

## Sicherheit
- Der Server lauscht nur auf 127.0.0.1; erreichbar nur über den Tunnel und nur mit dem geheimen Schlüssel im Pfad.
- Gesperrt: Banking-, Bezahl-, Wallet- und Passwort-Apps, Android-Einstellungen, Jarvis 2.0 selbst (`Safety.kt`).
- Pausieren-Schalter in der App, Protokoll aller Aktionen, neuer Schlüssel per Knopfdruck.
- Sofort alles stoppen: Bedienungshilfe ausschalten.

## Aufbau
- `McpServer.kt` – HTTP + MCP (JSON-RPC), ohne Abhängigkeiten, mit Unit-Tests
- `PhoneTools.kt` – Werkzeuge für Claude
- `JarvisService.kt` – Bedienungshilfe: Bildschirm lesen, tippen, wischen, Screenshots
- `Safety.kt` – Sperrliste
- `MainActivity.kt` – Einrichtung, Status, Protokoll
