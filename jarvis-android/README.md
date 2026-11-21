# Jarvis – Sprachassistent für Android

Jarvis hört dir zu, versteht deinen Auftrag mit Claude und bedient dein Handy wie ein Mensch: Apps öffnen, Bildschirm lesen, tippen, schreiben, scrollen.

## Download
Neueste Version: https://github.com/lirimdemiri123ld-lgtm/mein-ki-agent/releases/download/jarvis-latest/Jarvis.apk

## Einrichtung (Samsung Galaxy S25)
1. APK herunterladen und öffnen → Installation aus dieser Quelle erlauben → Installieren.
2. Jarvis öffnen → «Einstellungen» → Claude-API-Schlüssel eintragen (console.anthropic.com).
3. «Bedienungshilfe» → Installierte Apps → Jarvis → einschalten.
   Grau? Einstellungen → Apps → Jarvis → ⋮ → «Eingeschränkte Einstellungen zulassen».
4. Optional: Einstellungen → Erweiterte Funktionen → Seitentaste → Zweimal drücken → App öffnen → Jarvis.

## Bedienung
- Seitentaste 2× oder orangen 🎙-Knopf tippen → sprechen.
- Während Jarvis arbeitet: 🎙-Knopf oder «Stopp» beendet sofort.
- Heikle Aktionen (senden, kaufen, löschen, anrufen) fragt Jarvis immer mit Ja/Nein.

## Aufbau
- `JarvisService.kt` – Bedienungshilfe: Bildschirm lesen, tippen, wischen, Screenshots
- `Agent.kt` – Schleife mit Claude (Werkzeuge in `Tools.kt`)
- `Overlay.kt` – schwebender Knopf, Status, Ja/Nein
- `MainActivity.kt` – Oberfläche, Spracheingabe, Einstellungen

Gebaut wird automatisch über GitHub Actions (`.github/workflows/jarvis-android.yml`).
