"""Einfacher Agent: prueft einen Reddit-Post auf eine Kaufempfehlungs-Anfrage
und schlaegt bei Treffer passende Produkte vor."""

import os
import sys

import anthropic

MODEL = "claude-sonnet-4-6"

# Platzhalter-Beispieltext fuer den Reddit-Post
REDDIT_POST = """
Hey Leute, ich suche seit Wochen nach kabellosen Kopfhoerern fuer's Gym.
Sollten schweissresistent sein und mindestens 6 Stunden Akkulaufzeit haben.
Budget liegt bei max. 100 Euro. Hat jemand Empfehlungen?
"""


def main() -> None:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        print("Fehler: Umgebungsvariable ANTHROPIC_API_KEY ist nicht gesetzt.")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)

    # Schritt 1: GATE - pruefen, ob der Post ueberhaupt eine konkrete
    # Kaufempfehlungs-Anfrage enthaelt
    gate_response = client.messages.create(
        model=MODEL,
        max_tokens=10,
        messages=[
            {
                "role": "user",
                "content": (
                    f"Reddit-Post:\n{REDDIT_POST}\n\n"
                    "Ist das eine konkrete Kaufempfehlungs-Anfrage? "
                    "Antworte nur mit Ja oder Nein."
                ),
            }
        ],
    )
    gate_answer = gate_response.content[0].text.strip()

    # Schritt 2: Bei "Nein" wird der Post verworfen
    if "nein" in gate_answer.lower():
        print("Kein Treffer - wird verworfen")
        return

    # Schritt 3: Bei "Ja" - Zusammenfassung und Produktvorschlaege einholen
    empfehlung_response = client.messages.create(
        model=MODEL,
        max_tokens=1024,
        messages=[
            {
                "role": "user",
                "content": (
                    f"Reddit-Post:\n{REDDIT_POST}\n\n"
                    "Fasse in einem Satz zusammen, was gesucht wird. "
                    "Schlage danach drei passende Produkte vor, jeweils mit "
                    "einer kurzen Begruendung."
                ),
            }
        ],
    )
    empfehlung_text = empfehlung_response.content[0].text.strip()

    # Schritt 4: Ergebnis uebersichtlich ausgeben
    print("=" * 50)
    print("TREFFER: Kaufempfehlungs-Anfrage erkannt")
    print("=" * 50)
    print(empfehlung_text)
    print("=" * 50)


if __name__ == "__main__":
    main()
