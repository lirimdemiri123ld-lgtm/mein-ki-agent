"""Agent: ruft die neuesten Reddit-Posts live ab, prueft jeden auf eine
Kaufempfehlungs-Anfrage, schlaegt bei Treffer passende Produkte vor und
schreibt jeden Treffer zusaetzlich als Zeile in eine Google-Tabelle."""

import datetime
import os
import sys

import anthropic
import gspread
import requests
from google.oauth2.service_account import Credentials

MODEL = "claude-sonnet-4-6"

# Reddit blockiert Anfragen ohne User-Agent, daher wird hier ein eigener gesetzt
REDDIT_URL = "https://www.reddit.com/r/ProductRecommendations/new.json?limit=5"
REDDIT_HEADERS = {"User-Agent": "mein-ki-agent/1.0 (Reddit-Kaufempfehlungs-Agent)"}

# Google Sheets: Pfad zur Dienstkonto-Schluesseldatei und Tabellen-ID
# kommen aus Umgebungsvariablen statt hart codiert zu sein
GOOGLE_SERVICE_ACCOUNT_FILE = os.environ.get("GOOGLE_SERVICE_ACCOUNT_FILE")
GOOGLE_SHEET_ID = os.environ.get("GOOGLE_SHEET_ID")
GOOGLE_SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive",
]


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


def oeffne_google_sheet() -> gspread.Worksheet:
    """Authentifiziert sich per Dienstkonto bei Google Sheets und gibt das
    erste Arbeitsblatt der konfigurierten Tabelle zurueck."""
    if not GOOGLE_SERVICE_ACCOUNT_FILE or not GOOGLE_SHEET_ID:
        print(
            "Fehler: Umgebungsvariablen GOOGLE_SERVICE_ACCOUNT_FILE und "
            "GOOGLE_SHEET_ID muessen gesetzt sein."
        )
        sys.exit(1)

    credentials = Credentials.from_service_account_file(
        GOOGLE_SERVICE_ACCOUNT_FILE, scopes=GOOGLE_SCOPES
    )
    client = gspread.authorize(credentials)
    # Tabelle wird ueber ihre ID geoeffnet (aus der Sheet-URL extrahierbar)
    return client.open_by_key(GOOGLE_SHEET_ID).sheet1


def parse_empfehlung(rohtext: str) -> tuple[str, str]:
    """Trennt die strukturierte Modellantwort in Zusammenfassung und
    Produktvorschlaege auf, damit beide getrennt in der Tabelle landen."""
    if "ZUSAMMENFASSUNG:" in rohtext and "PRODUKTE:" in rohtext:
        vor_produkte, produkte_teil = rohtext.split("PRODUKTE:", 1)
        zusammenfassung = vor_produkte.replace("ZUSAMMENFASSUNG:", "").strip()
        produktvorschlaege = produkte_teil.strip()
        return zusammenfassung, produktvorschlaege

    # Fallback, falls das Modell das Format nicht exakt eingehalten hat
    return "", rohtext


def pruefe_und_empfehle(
    client: anthropic.Anthropic, post: dict
) -> tuple[str, str] | None:
    """Fuehrt den GATE- und Empfehlungs-Ablauf fuer einen einzelnen Post aus.
    Gibt None zurueck, wenn keine Kaufempfehlungs-Anfrage erkannt wurde,
    sonst ein Tupel (Zusammenfassung, Produktvorschlaege)."""
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

    # Schritt 3: Bei "Ja" - Zusammenfassung und Produktvorschlaege einholen.
    # Ein festes Antwortformat wird verlangt, damit die Antwort zuverlaessig
    # in Zusammenfassung und Produktvorschlaege aufgeteilt werden kann.
    empfehlung_response = client.messages.create(
        model=MODEL,
        max_tokens=1024,
        messages=[
            {
                "role": "user",
                "content": (
                    f"Reddit-Post:\n{post_inhalt}\n\n"
                    "Antworte in genau diesem Format, ohne zusaetzlichen Text:\n"
                    "ZUSAMMENFASSUNG: <ein Satz, was gesucht wird>\n"
                    "PRODUKTE:\n"
                    "1. <Produkt> - <kurze Begruendung>\n"
                    "2. <Produkt> - <kurze Begruendung>\n"
                    "3. <Produkt> - <kurze Begruendung>"
                ),
            }
        ],
    )
    rohtext = empfehlung_response.content[0].text.strip()
    return parse_empfehlung(rohtext)


def schreibe_zeile_in_sheet(
    worksheet: gspread.Worksheet, post: dict, zusammenfassung: str, produktvorschlaege: str
) -> None:
    """Haengt eine neue Zeile mit Datum, Post-Titel, Link, Zusammenfassung
    und den drei Produktvorschlaegen an die Google-Tabelle an."""
    datum = datetime.date.today().isoformat()
    worksheet.append_row(
        [datum, post["titel"], post["link"], zusammenfassung, produktvorschlaege]
    )


def main() -> None:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        print("Fehler: Umgebungsvariable ANTHROPIC_API_KEY ist nicht gesetzt.")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)
    worksheet = oeffne_google_sheet()

    # Live-Posts von Reddit abrufen statt eines festen Beispieltexts
    posts = lade_reddit_posts()

    # Schleife ueber alle abgerufenen Posts, jeder durchlaeuft den
    # bestehenden Gate- und Empfehlungs-Ablauf einzeln
    for post in posts:
        ergebnis = pruefe_und_empfehle(client, post)

        print("=" * 50)
        print(f"POST: {post['titel']}")
        print(f"LINK: {post['link']}")
        print("-" * 50)
        if ergebnis is None:
            print("Kein Treffer")
        else:
            zusammenfassung, produktvorschlaege = ergebnis
            print(f"Zusammenfassung: {zusammenfassung}")
            print(produktvorschlaege)
            # Treffer zusaetzlich in die Google-Tabelle schreiben
            schreibe_zeile_in_sheet(worksheet, post, zusammenfassung, produktvorschlaege)
        print("=" * 50)


if __name__ == "__main__":
    main()
