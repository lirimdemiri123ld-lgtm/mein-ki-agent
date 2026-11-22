"""Erzeugt assets/voices.json aus den Piper-Stimmen des sherpa-onnx-Releases «tts-models»."""
import json, re, sys
assets = json.load(open(sys.argv[1]))
out = []
langs = {"de_DE": "DE", "en_GB": "EN-GB", "en_US": "EN-US"}
hints = {
    "miro": "neu, sehr natürlich", "dii": "neu, sehr natürlich",
    "alan": "britisch, männlich – Butler-Stil", "northern_english_male": "britisch, männlich, tief",
    "thorsten": "männlich, klar", "thorsten_emotional": "männlich, ausdrucksstark", "karlsson": "männlich, tief",
    "pavoque": "männlich, ruhig", "ryan": "männlich, kräftig", "joe": "männlich", "john": "männlich", "kusal": "männlich",
    "jenny_dioco": "britisch, weiblich", "cori": "britisch, weiblich", "alba": "schottisch, weiblich",
    "kerstin": "weiblich", "eva_k": "weiblich", "ramona": "weiblich", "amy": "weiblich", "lessac": "neutral",
    "semaine": "britisch, mehrere Sprecher", "vctk": "britisch, viele Sprecher", "libritts": "viele Sprecher",
}
# Empfehlungen zuerst (beste Qualität für Deutsch)
best = ["de_DE-miro-high", "de_DE-thorsten-high", "de_DE-dii-high", "de_DE-thorsten-medium", "de_DE-thorsten_emotional-medium"]
for a in assets:
    m = re.match(r"vits-piper-(de_DE|en_GB|en_US)-(.+?)-(x_low|low|medium|high)\.tar\.bz2$", a["name"])
    if not m:
        continue
    lang, name, q = m.groups()
    if q == "x_low" or "glados" in name.lower():
        continue  # keine Nachahmungen bekannter Figuren
    vid = a["name"][:-8]
    pretty = name.replace("_", " ").title()
    hint = hints.get(name, "")
    star = "★ " if any(b in vid for b in best) else ""
    title = f"{star}{pretty} ({q})" + (f" – {hint}" if hint else "")
    out.append({"id": vid, "title": title, "lang": langs[lang],
                "url": a["browser_download_url"], "mb": round(a["size"] / 1048576),
                "sample": f"https://k2-fsa.github.io/sherpa/onnx/tts/all/piper/mp3/{lang}/{vid}/0.mp3"})
order = {"DE": 0, "EN-GB": 1, "EN-US": 2}
out.sort(key=lambda v: (order[v["lang"]], 0 if v["title"].startswith("★") else 1, v["title"]))
json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
print(f"{len(out)} Stimmen im Katalog")
for v in out: print(v["lang"], v["title"], v["mb"], "MB")
