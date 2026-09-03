# گویا (Goya) — Zero-Click Persian OCR Reader

Point the phone at Persian text. It reads it out loud. No buttons, no menus, no internet.

Built for an elderly user who cannot read and cannot navigate a UI: the camera opens on launch
and the app never asks for anything again.

---

## Two independent layers — do not confuse them

| Layer | Job | Implementation | Bundled asset |
| --- | --- | --- | --- |
| **OCR** | image → Persian **text** | Tesseract 4 (`fas`) | `assets/tessdata/fas.traineddata` (~1–15 MB) |
| **TTS** | text → Persian **speech** | Sherpa-ONNX VITS/Piper | `assets/tts/fa/*.onnx` (~30–60 MB) |

Both are required. `fas.traineddata` cannot speak, and the `.onnx` voice cannot read an image.

**Why not ML Kit for OCR?** ML Kit Text Recognition v2 only ships Latin, Chinese, Devanagari,
Japanese and Korean models. There is no Arabic/Persian script model — a build using
`ChineseTextRecognizerOptions` bundles only `Latn_ctc` and `Hani_ctc`, detects no Persian, and
stays permanently silent. `MlKitOcrEngine` is kept only as a Latin-script reference backend.

**Why not the device TTS?** Android does not guarantee a Persian voice, and installing one means
walking through Settings → Language → Speech output — exactly what this user cannot do. Bundling
the voice means: install the APK, open it, it talks.

---

## Designed for someone who cannot read the screen

Everything below exists because the usual visual affordances are unavailable to this user.

| Situation | What a sighted user does | What this app does |
| --- | --- | --- |
| App is loading | reads the spinner | speaks nothing yet, camera is already live |
| Nothing is recognised | moves the phone | after 5 s speaks escalating hints: aim → move closer → hold steady → find more light |
| Room is dark | taps the torch button | detects average frame brightness and switches the torch on automatically (with hysteresis so it cannot flicker) |
| Missed the sentence | taps “repeat” | **touch anywhere on the screen** repeats the last sentence — no target to find, no button to see |
| Text is found | sees the highlight | short double vibration, so it is noticeable despite hearing loss |
| Media volume is at 10% | notices the icon | raises the stream to 80% on launch, but never lowers a volume the user chose |
| Music is playing | pauses it | requests transient audio focus so the other app ducks |
| Print is close to the lens | taps to focus | centre autofocus retriggers every 2.5 s, hands-free |
| Permission dialog appears | reads it | the prompt is spoken aloud, and a refusal is spoken too instead of leaving a black screen |
| App goes to background | — | speech stops immediately in `onPause`; torch is released |

The system bars are hidden and the orientation is locked, so there is nothing to accidentally
tap and no way to wander into another app.

---

## Setup (three steps)

```bash
# 1. Download both models into assets/
bash tools/fetch-tts-model.sh

# 2. Put the Sherpa-ONNX Android AAR in app/libs/   (see app/libs/README.txt)
#    https://github.com/k2-fsa/sherpa-onnx/releases

# 3. Build
./gradlew :app:installDebug
```

No `gradle-wrapper.jar` is included (binary). Open the folder in Android Studio, or run
`gradle wrapper --gradle-version 8.9` once. Requires JDK 17.

With no models present the app still builds and runs — it just cannot read or speak. That is
intentional: a missing model degrades gracefully instead of crashing.

---

## Project structure

```
app/src/main/java/com/example/goya/
├── MainActivity.kt              zero-click launch, immersive fullscreen, spoken permission flow
├── ui/CameraReaderScreen.kt     CameraX preview + analysis, autofocus, torch, tap-to-repeat
├── ocr/
│   ├── OcrEngine.kt             pluggable backend interface
│   ├── TesseractOcrEngine.kt    Persian 'fas', confidence floor 60
│   ├── MlKitOcrEngine.kt        Latin-only reference backend
│   └── OcrAnalyzer.kt           throttling, frame gating, brightness sampling
├── speech/
│   ├── TtsEngine.kt             speech backend interface
│   ├── SherpaTtsEngine.kt       offline bundled voice, streaming AudioTrack
│   ├── SystemTtsEngine.kt       device TTS fallback (fa_IR → fa → ar)
│   ├── Speaker.kt               facade: prefers offline, falls back
│   └── SpeechGate.kt            debouncing, repeat suppression, repeatLast()
├── guidance/Coach.kt            spoken hints + automatic torch
├── feedback/Cues.kt             vibration, volume, audio focus
├── text/TextNormalizer.kt       ي→ی, ك→ک, diacritics, similarity ratio
└── util/AssetInstaller.kt       unpacks assets → filesDir once (native needs real paths)
```

---

## How the speech gating works

Naive `if (text == lastSpoken) return` does not work with live OCR: every frame differs by a
character, so the same sentence is spoken over and over. `SpeechGate` instead:

1. normalises the text (Arabic→Persian letterforms, diacritics, whitespace, digits)
2. requires the reading to be **stable across 2 consecutive frames** before speaking
3. compares against the last utterance with a **similarity ratio**, not equality
4. suppresses a repeat of the same text for a **10 s cooldown**
5. cancels speech only after **3 consecutive empty frames**, so one blurry frame does not cut
   the sentence mid-word
6. lets genuinely new text **interrupt** the current utterance immediately

### Tuning knobs

| Constant | Where | Default | Effect |
| --- | --- | --- | --- |
| `intervalMs` | OcrAnalyzer | 450 ms | OCR rate. Lower = snappier, hotter, more battery |
| `stableFrames` | SpeechGate | 2 | Frames a reading must persist before it is spoken |
| `sameThreshold` | SpeechGate | 0.85 | Above this similarity, treated as the same sentence |
| `changeThreshold` | SpeechGate | 0.55 | Below this, treated as a new target → interrupt |
| `repeatCooldownMs` | SpeechGate | 10 000 | Silence window before repeating identical text |
| `emptyFramesToCancel` | SpeechGate | 3 | Blank frames before speech is cancelled |
| `minPersianRatio` | SpeechGate | 0.5 | Fraction of Persian characters required |
| `quietBeforeHintMs` | Coach | 5 000 | Silence before the first spoken hint |
| `betweenHintsMs` | Coach | 18 000 | Gap between hints, so it never nags |
| `darkLuma` / `brightLuma` | Coach | 45 / 85 | Torch on/off brightness thresholds |
| `MIN_CONFIDENCE` | TesseractOcrEngine | 60 | Mean confidence floor |
| `speed` | SherpaTtsEngine | 0.95 | Speech rate of the offline voice |

For a hard-of-hearing listener, drop `speed` to about `0.85`.

---

## Notes

- `abiFilters` is limited to `arm64-v8a` and `armeabi-v7a`. Add `x86_64` for emulator runs.
- `.onnx` and `.traineddata` are in `androidResources.noCompress` — they are read directly and
  must not be deflated in the APK.
- With the voice bundled the APK lands around 80–110 MB. Use `./gradlew bundleRelease` for Play.
- `AssetInstaller` copies the model to the private files dir on first launch (a few seconds,
  off the main thread) because the native phonemiser needs real filesystem paths. Bump
  `SherpaTtsEngine.MODEL_VERSION` whenever you swap the voice.
- Not yet handled: multi-column layouts and RTL reading order across columns; Tesseract returns
  lines in raster order, which is fine for signs, labels and letters but not for newspapers.
