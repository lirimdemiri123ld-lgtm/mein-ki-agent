"""Prüft eine Piper-Stimme mit sherpa-onnx (gleiche Konfiguration wie in der App)."""
import glob, os, sys, sherpa_onnx
d = sys.argv[1]
model = [f for f in glob.glob(os.path.join(d, "*.onnx"))][0]
cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=model, tokens=os.path.join(d, "tokens.txt"),
        data_dir=os.path.join(d, "espeak-ng-data")), num_threads=2))
tts = sherpa_onnx.OfflineTts(cfg)
a = tts.generate("Guten Abend, Lirim. Alle Systeme sind bereit.", sid=0, speed=1.0)
print("Dateien:", sorted(os.listdir(d))[:8])
print("TTS OK: %d Samples, %d Hz, %.1f s" % (len(a.samples), a.sample_rate, len(a.samples) / a.sample_rate))
