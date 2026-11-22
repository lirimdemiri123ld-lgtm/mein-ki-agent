# Jarvis – Sprachassistent für Android

Jarvis hört dir zu und bedient dein Handy. Einfache Dinge erledigt er **ohne KI und gratis**,
nur für Neues oder Schwieriges fragt er Claude (Haiku 5.5, sehr günstig).

## Download
Neueste Version: https://github.com/lirimdemiri123ld-lgtm/mein-ki-agent/releases/download/jarvis-latest/Jarvis.apk

Einfach **über die alte Version installieren** – Gedächtnis, gelernte Abläufe und Einstellungen bleiben erhalten.

## Was diese Version neu kann
- **Schneller und kühler:** Das lokale Sprachmodell (Gemma) ist entfernt – es brauchte Minuten, heizte das Handy
  und erfand Erfolge. Die alten Modelldateien (mehrere GB) werden beim ersten Start automatisch gelöscht.
  Das HUD zeichnet nur noch, was sich bewegt (vorher 30× pro Sekunde jedes Panel).
- **Direktbefehle ohne KI (0 Rp.):** Wecker («halb 7», «viertel vor 6», «in 20 Minuten»), Timer, Anrufen,
  **WhatsApp/SMS schreiben** (Chat öffnet mit fertigem Text, Jarvis liest vor, fragt «Senden?» und prüft danach),
  Apps öffnen, Lautstärke, Taschenlampe, Musik (Pause/Weiter/Nächstes, «Spiel … auf Spotify»), Navigation,
  Websuche im Browser, Kalendereintrag, Einstellungen öffnen, Uhrzeit/Datum/Akku/Wetter/Termine/neue Nachrichten,
  «Merk dir …», «Gruja ist meine Frau», «Vergiss …», «Briefing».
- **Rückfragen bleiben im selben Auftrag:** Fragt Jarvis «Was soll in der Nachricht stehen?», hört er danach
  von selbst zu und verwendet deine Antwort.
- **Versteht besser:** Jarvis nimmt von mehreren Hör-Varianten die, die einen klaren Befehl ergibt, wählt wenn
  möglich Googles Erkennung und kann unter SYSTEM auf Hochdeutsch (de-DE) umgestellt werden.
- **Weniger Tokens:** Tastatur wird nicht mehr mitgeschickt, Haiku arbeitet ohne Nachdenk-Phase, Websuche nur noch
  auf ausdrücklichen Wunsch (≈ 1 Rp. pro Suche), keine Hintergrund-Analyse mehr. Sonnet nur mit «gründlich …».
- **Guthaben leer:** klare Ansage, Sperre nur 90 Sekunden statt 6 Stunden; Direktbefehle laufen weiter.
- **Stimmen:** neue Stimmen im Katalog (★ = empfohlen, z. B. Miro, Dii, Thorsten high) und **Vorhören** vor dem Download.

## Einrichtung (Samsung Galaxy S25)
1. APK herunterladen und öffnen → Installation aus dieser Quelle erlauben → Installieren.
   (Play Protect warnt bei selbst gebauten Apps – «Trotzdem installieren».)
2. Jarvis öffnen → **SYSTEM** → Claude-API-Schlüssel eintragen (platform.claude.com). Direktbefehle gehen auch ohne.
3. **ZUGRIFF** → Bedienungshilfe → «Installierte Apps» → Jarvis → einschalten.
   Schalter grau? Einstellungen → Apps → Jarvis → ⋮ → «Eingeschränkte Einstellungen zulassen».
4. **ZUGRIFF** → Kontakte & Anrufe erlauben; optional Benachrichtigungen.
5. Im HUD auf **Guthaben** tippen und den Betrag aus der Console eintragen.
6. Optional: SYSTEM → «Auf Hallo Jarvis hören»; STIMME → Stimme wählen (★) und vorhören.

## Bedienung
- Reaktor antippen oder «Hallo Jarvis» → sprechen. Lang drücken = Stopp.
- Heikle Aktionen (senden, anrufen, kaufen, löschen) fragt Jarvis immer mit Ja/Nein – per Stimme oder Knopf.
- «Gründlich: …» oder «Mit Claude Sonnet …» nutzt das stärkere (teurere) Modell.

## Noch offen (ehrlich)
- HUD-Layout: Aufbau wie bisher (Wetter · Reaktor · Guthaben oben), nur flüssiger – keine neue Optik.
- WLAN/Bluetooth: Jarvis öffnet die Einstellung, umschalten musst du selbst (Android-Sperre).
- Dual-SIM-Auswahl per Stimme, Startbildschirm-Widget, SBB-Widget, englische Antworten: noch nicht gebaut.
- Diese Version wurde am PC geprüft (Übersetzung + 36 automatische Tests der Sprachlogik), aber noch nicht
  auf einem echten Handy getestet.

## Aufbau
- `core/` – reine Logik mit Tests: `Intents.kt` (Befehle erkennen), `Lang.kt` (Uhrzeiten, Zahlen, Daten),
  `MessageText.kt` (Nachrichtentext ableiten), `People.kt`, `Recipes.kt`, `Fuzzy.kt`
- `Direct.kt` – führt Direktbefehle aus · `Agent.kt` – Weiche und Claude-Schleife · `JarvisService.kt` – Bedienungshilfe
- `MainActivity.kt`, `hud/` – Oberfläche · `Speaker.kt`, `NeuralVoices.kt` – Stimmen

Gebaut wird automatisch über GitHub Actions (`.github/workflows/jarvis-android.yml`).
