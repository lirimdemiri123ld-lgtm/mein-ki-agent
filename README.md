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
