"""Erzeugt assets/voices.json aus den Piper-Stimmen des sherpa-onnx-Releases «tts-models»."""
import json, re, sys
assets = json.load(open(sys.argv[1]))
out = []
langs = {"de_DE": "DE", "en_GB": "EN-GB", "en_US": "EN-US"}
hints = {
    "alan": "britisch, männlich – Butler-Stil", "northern_english_male": "britisch, männlich, tief",
    "thorsten": "männlich, klar", "thorsten_emotional": "männlich, ausdrucksstark", "karlsson": "männlich, tief",
    "ryan": "männlich, kräftig", "joe": "männlich", "john": "männlich", "kusal": "männlich",
    "jenny_dioco": "britisch, weiblich", "cori": "britisch, weiblich", "alba": "schottisch, weiblich",
    "kerstin": "weiblich", "eva_k": "weiblich", "ramona": "weiblich", "amy": "weiblich", "lessac": "neutral",
    "semaine": "britisch, mehrere Sprecher", "vctk": "britisch, viele Sprecher", "libritts": "viele Sprecher",
}
for a in assets:
    m = re.match(r"vits-piper-(de_DE|en_GB|en_US)-(.+?)-(x_low|low|medium|high)\.tar\.bz2$", a["name"])
    if not m:
        continue
    lang, name, q = m.groups()
    if q == "x_low":
        continue
    pretty = name.replace("_", " ").title()
    hint = hints.get(name, "")
    title = f"{pretty} ({q})" + (f" – {hint}" if hint else "")
    out.append({"id": a["name"][:-8], "title": title, "lang": langs[lang],
                "url": a["browser_download_url"], "mb": round(a["size"] / 1048576)})
order = {"DE": 0, "EN-GB": 1, "EN-US": 2}
out.sort(key=lambda v: (order[v["lang"]], v["title"]))
json.dump(out, open(sys.argv[2], "w"), ensure_ascii=False, indent=1)
print(f"{len(out)} Stimmen im Katalog")
for v in out: print(v["lang"], v["title"], v["mb"], "MB")
