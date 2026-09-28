# mein-ki-agent

Einfacher Agent, der einen Reddit-Post daraufhin prüft, ob er eine konkrete
Kaufempfehlungs-Anfrage enthält, und bei Treffer passende Produktvorschläge
liefert.

## Installation

```bash
pip install -r requirements.txt
```

## API-Key setzen

```bash
export ANTHROPIC_API_KEY="dein-api-key"
```

## Ausführen

```bash
python3 agent.py
```

## Ablauf

1. Der Reddit-Post-Text steht als Platzhalter in der Variable `REDDIT_POST`
   am Anfang von `agent.py` — dort kannst du einen anderen Text einsetzen.
2. **GATE-Schritt:** Ein erster Aufruf an die Anthropic API prüft, ob der
   Post eine konkrete Kaufempfehlungs-Anfrage ist (Ja/Nein).
3. Bei "Nein" gibt das Skript `Kein Treffer - wird verworfen` aus und
   beendet sich.
4. Bei "Ja" folgt ein zweiter Aufruf, der in einem Satz zusammenfasst, was
   gesucht wird, und drei passende Produkte mit kurzer Begründung vorschlägt.
5. Das Ergebnis wird übersichtlich in der Konsole ausgegeben.

## Website mit 3D-Animationen

`website-3d.html` ist eine eigenständige moderne Landingpage mit Three.js:
Torusknoten-Szene, die auf Maus und Scrollen reagiert, 3D-Tilt-Karten,
Laufband, CSS-Scroll-Reveals und Kontaktformular (Demo, ohne Backend).

Einfach die Datei im Browser öffnen, es wird nichts installiert. Three.js und
die Schriften werden per CDN geladen. Texte, Farben und Schriften lassen sich
oben in der Datei im `:root`-Block und im HTML anpassen.

## Uhren-Shop (uhren-shop/)

Eigenständige Shop-Website für den Uhrenverkäufer, als Ersatz für den
Ricardo-Shop. `uhren-shop/index.html` öffnen, kein Build nötig.

- `produkte.js`: die 31 Uhren mit Titel, Marke, Kategorie, Werk, Zustand,
  Versandart und -kosten, Standort, Preis (CHF) und Bildliste. Neue Uhr =
  neuer Eintrag in dieser Liste.
- `bilder/`: Produktfotos, benannt nach der Ricardo-Angebotsnummer. Weitere
  Fotos einer Uhr als `bilder/<id>-2.jpg`, `bilder/<id>-3.jpg` usw. ablegen,
  die Galerie im Detail-Dialog erkennt sie automatisch.
- Seitenaufbau: Vorhang beim Laden, 3D-Uhr in Three.js (echte Uhrzeit, dreht
  sich beim Scrollen um), Fotoband, horizontal scrollende Auswahl, gefilterte
  Kollektion, drehbare 3D-Vitrine aus allen Fotos (CSS 3D), Konditionen,
  Anfrageformular (Demo, ohne Backend).
