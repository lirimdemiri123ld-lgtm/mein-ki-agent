"""Agent: ruft die neuesten Reddit-Posts live ab, prueft jeden auf eine
Kaufempfehlungs-Anfrage und schlaegt bei Treffer passende Produkte vor."""

import os
import sys

import anthropic
import requests

MODEL = "claude-sonnet-4-6"

# Reddit blockiert Anfragen ohne User-Agent, daher wird hier ein eigener gesetzt
REDDIT_URL = "https://www.reddit.com/r/ProductRecommendations/new.json?limit=5"
REDDIT_HEADERS = {"User-Agent": "mein-ki-agent/1.0 (Reddit-Kaufempfehlungs-Agent)"}


def lade_reddit_posts() -> list[dict]:
    """Ruft die 5 neuesten Posts aus r/ProductRecommendations ab und
    extrahiert Titel, Text (selftext) und Link zu jedem Post."""
    response = requests.get(REDDIT_URL, headers=REDDIT_HEADERS, timeout=10)
    response.raise_for_status()
    daten = response.json()

    posts = []
    for eintrag in daten["data"]["children"]:
        post = eintrag["data"]
        posts.append(
            {
                "titel": post.get("title", ""),
                "text": post.get("selftext", ""),
                "link": "https://www.reddit.com" + post.get("permalink", ""),
            }
        )
    return posts


def pruefe_und_empfehle(client: anthropic.Anthropic, post: dict) -> str | None:
    """Fuehrt den GATE- und Empfehlungs-Ablauf fuer einen einzelnen Post aus.
    Gibt None zurueck, wenn keine Kaufempfehlungs-Anfrage erkannt wurde,
    sonst den Empfehlungstext."""
    post_inhalt = f"Titel: {post['titel']}\n\n{post['text']}"

    # Schritt 1: GATE - pruefen, ob der Post ueberhaupt eine konkrete
    # Kaufempfehlungs-Anfrage enthaelt
    gate_response = client.messages.create(
        model=MODEL,
        max_tokens=10,
        messages=[
            {
                "role": "user",
                "content": (
                    f"Reddit-Post:\n{post_inhalt}\n\n"
                    "Ist das eine konkrete Kaufempfehlungs-Anfrage? "
                    "Antworte nur mit Ja oder Nein."
                ),
            }
        ],
    )
    gate_answer = gate_response.content[0].text.strip()

    # Schritt 2: Bei "Nein" wird der Post verworfen
    if "nein" in gate_answer.lower():
        return None

    # Schritt 3: Bei "Ja" - Zusammenfassung und Produktvorschlaege einholen
    empfehlung_response = client.messages.create(
        model=MODEL,
        max_tokens=1024,
        messages=[
            {
                "role": "user",
                "content": (
                    f"Reddit-Post:\n{post_inhalt}\n\n"
                    "Fasse in einem Satz zusammen, was gesucht wird. "
                    "Schlage danach drei passende Produkte vor, jeweils mit "
                    "einer kurzen Begruendung."
                ),
            }
        ],
    )
    return empfehlung_response.content[0].text.strip()


def main() -> None:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        print("Fehler: Umgebungsvariable ANTHROPIC_API_KEY ist nicht gesetzt.")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)

    # Live-Posts von Reddit abrufen statt eines festen Beispieltexts
    posts = lade_reddit_posts()

    # Schleife ueber alle abgerufenen Posts, jeder durchlaeuft den
    # bestehenden Gate- und Empfehlungs-Ablauf einzeln
    for post in posts:
        empfehlung_text = pruefe_und_empfehle(client, post)

        print("=" * 50)
        print(f"POST: {post['titel']}")
        print(f"LINK: {post['link']}")
        print("-" * 50)
        if empfehlung_text is None:
            print("Kein Treffer")
        else:
            print(empfehlung_text)
        print("=" * 50)


if __name__ == "__main__":
    main()
