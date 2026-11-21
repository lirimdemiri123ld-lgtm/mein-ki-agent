"""Prüft, ob das Vosk-Modell «Hallo Jarvis» erkennt (synthetische Sprache)."""
import json, sys, wave
from vosk import Model, KaldiRecognizer as Recognizer, SetLogLevel

SetLogLevel(0)
model = Model(sys.argv[1])
grammars = {
    "jarvis": '["hello jarvis", "jarvis", "[unk]"]',
    "alt": '["hello jarvis", "jarvis", "jar vis", "jarvis is", "harvest", "service", "[unk]"]',
}
for wav in sys.argv[2:]:
    for gname, g in list(grammars.items()) + [("frei", None)]:
        wf = wave.open(wav, "rb")
        rec = Recognizer(model, wf.getframerate(), g) if g else Recognizer(model, wf.getframerate())
        while True:
            data = wf.readframes(4000)
            if not data:
                break
            rec.AcceptWaveform(data)
        print(f"{wav} [{gname}] -> {json.loads(rec.FinalResult()).get('text')!r}", flush=True)
