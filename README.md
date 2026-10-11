# InkProof

Prove your work.

InkProof is a native Android tablet notebook for handwritten math. You write the question, solve it yourself, then explicitly check. It is not a chatbot and it does not draw while it waits on a network call.

The defining action is **Check my work**: recognize the working for one question, find the first incorrect step, explain that step, and offer progressive hints. The full answer appears only after you tap Reveal. **Solve** is a separate action and does not read another question's solution strokes.

## Status

Labels mean what they say. Source existing is not the same as a passing build, and a passing build is not stylus validation.

| Area | Status |
| --- | --- |
| Notebook model, ink engine, shapes, local math checker, import text extractors | Implemented |
| Question A / Question B request isolation in the core module | Implemented. Automated test passed in GitHub Actions run [38119244590](https://github.com/Om280/inkproof/actions/runs/38119244590) (`:core:test` exit 0 on commit `86a885f`) |
| Backend (`node server.js`) health, missing-key failure, labeled mock | Implemented. Automated test passed locally with `node --test backend/test/server.test.js` (6 passed, 0 failed) on 2026-10-11, and the same command passed in that Actions run (Backend tests job, 5s) |
| Android library, editor, custom ink canvas, settings, file import, annotated PDF export | Implemented. `:app:testDebugUnitTest` and `:app:assembleDebug` exited 0 in that same Actions run |
| Debug APK | Built by GitHub Actions run [38119244590](https://github.com/Om280/inkproof/actions/runs/38119244590) on commit `86a885f`. Artifact `inkproof-debug-apk` (about 24 MB, not expired). Job path: `app/build/outputs/apk/debug/app-debug.apk`. A later docs-only commit does not rebuild the APK until its own run finishes |
| Live Gemini check or solve | Blocked: no `GEMINI_API_KEY` is configured in this environment. A missing key returns a non-200 `MISSING_API_KEY` error. It is not reported as correct |
| OnePlus Pad 2 + OnePlus Stylo 2 | Requires physical-device validation. See `docs/DEVICE_ACCEPTANCE.md`. Every stylus item there is **NOT YET VERIFIED** |

This session cannot install a JDK or Android SDK, so the APK is produced by GitHub Actions, not by a local Gradle invocation.

## What the app does

- Native Kotlin. Compose is used for chrome only. Ink is a custom `View`. There is no WebView.
- Library: notebooks, folders, recent, favorites, search, sort, create, rename, delete, duplicate, move, and page thumbnails. New notebooks are blank. Sample problems are not inserted into documents or AI requests.
- Stylus writes. Finger taps UI, two-finger pans, pinch-zooms, and vertical-swipe scrolls. Finger does not lay ink. Palm rejection uses tool type and touch size. It is not claimed to be perfect.
- Vector strokes with stable IDs, pressure, undo/redo, object eraser (small / medium / large), lasso (move, copy, cut, paste, duplicate, delete, recolor, check, solve).
- Marker is a separate tool: wider, translucent, its own color and opacity. It is not a thicker pen.
- Palette: black, white, grey, dark grey, red, orange, yellow, green, cyan, light blue, blue, purple, pink, plus a custom RRGGBB color. Ink is never recolored because the paper changed.
- Shape tools: line, arrow, rectangle, square, circle, ellipse, triangle. A dragged rectangle is stored as a shape, not a freehand path.
- Hold-to-shape is about 450 ms by default (configurable, 200–1000 ms) and snaps only above 0.82 confidence. It does not delay the raw stroke. A clean rectangle that is not held stays a stroke.
- Pages are separate objects in one vertical scroll. `+ Question` adds a question object on the current page. A page can have zero, one, or many questions mixed with freeform ink.
- Check sends that question and that question's solution strokes only. If more than one question could match, the app asks. It does not guess.
- Ambiguous handwriting asks you to confirm or edit the reading. Ink is not silently replaced. Confidence under about 0.55 is not treated as a silent success.
- ML Kit Digital Ink is optional and fail-closed. If the model is missing or the call fails, the app uses the on-device unistroke reader. Neither reader is a reliable recognizer for arbitrary integrals, matrices, or multivariable calculus.
- Cloud calls happen only for explicit Check, Solve, or a cloud read. There is no request per stroke. Results are cached by question version, solution version, and action. Autosave does not run on the ink thread.
- App theme (System / Light / Dark) is independent of per-page paper and template.

## Getting CHECK MY WORK and SOLVE Working

The Android app never contains the Gemini key. A small local backend holds the key and speaks HTTP on your LAN. The commands below are the ones this repository actually uses. No `npm install` is required to start or test the backend.

### Windows

1. Install Node.js 20 or newer. Check with:

   ```bat
   node -v
   ```

2. Open Command Prompt in the InkProof folder (the folder that contains `settings.gradle.kts` and the `backend` folder).

3. Create the env file and set your own key. Do not commit it.

   ```bat
   copy .env.example .env
   notepad .env
   ```

   Set `GEMINI_API_KEY` to a key from [Google AI Studio](https://aistudio.google.com/apikey). Leave `GEMINI_MODEL=gemini-3.8-flash` unless you have a reason to change it. The backend also reads `backend\.env` if you put the file there instead. An already-set environment variable wins over the file.

4. Start the backend:

   ```bat
   cd backend
   node server.js
   ```

   You should see `InkProof backend listening on 0.0.0.0:8787` and whether a key is configured. Leave this window open.

5. In another Command Prompt, confirm health does not print the key:

   ```bat
   curl http://127.0.0.1:8787/health
   ```

   Expect `configured` true or false, a `model` name, and no API key. `GET /health/gemini` checks the provider without returning the key. If the key is missing, `POST /v1/check` returns a non-200 body with `"status":"error"`, `"mock":false`, and `"errorCode":"MISSING_API_KEY"`. That is a failure, not a successful grade.

6. Optional backend tests, from the `backend` folder:

   ```bat
   node --test
   ```

   From the repository root the same tests are:

   ```bat
   node --test backend\test\server.test.js
   ```

7. Install the debug APK from the GitHub Actions artifact `inkproof-debug-apk`, or build it in Android Studio (see below).

8. Point the app at this PC.

   - Android emulator: Settings → backend URL `http://10.0.2.2:8787` (the app default).
   - OnePlus Pad 2 on the same Wi-Fi: run `ipconfig`, copy the IPv4 address of the Wi-Fi adapter, and set `http://THAT-ADDRESS:8787`.
   - If the tablet cannot connect, allow `node.exe` through Windows Defender Firewall for private networks, and confirm the tablet is not on a guest network.

9. In the app, set AI mode to **Real** and tap **Check backend**. The status line must not be treated as a math grade. Mock mode calls `/v1/mock/check` and `/v1/mock/solve` and is labeled `MOCK`. It is not Gemini. Off uses only the on-device checker.

10. Write one question, solve it, then tap **Check**. Solve is the other button. Reveal is explicit. Editing the working invalidates the cached check.

### What the backend calls

`POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with header `x-goog-api-key`. The model defaults to `gemini-3.8-flash`. Responses are JSON (`responseMimeType=application/json` plus a response schema). Tools and code execution are not combined with that schema. The app, not the model, decides how the panel looks. The server forces `provider`, `mock`, and `model`, and strips those fields if the model supplies them. The key is removed from error text.

On-device checks cover linear equations, quadratics, simple derivatives, simple integrals, and simple limits. Matrices, arbitrary integrals, and multivariable calculus are unsupported and must not be reported as correct.

## Build the Android app

Open the repository folder in Android Studio (the folder that contains `settings.gradle.kts`). Use JDK 17. Compile SDK 35, minimum SDK 26, application id `app.inkproof`, version `1.0.0-beta.1`.

This repo pins Gradle 8.11.1 in `gradle/wrapper/gradle-wrapper.properties`. The wrapper jar is not committed. GitHub Actions installs Gradle 8.11.1 with `gradle/actions/setup-gradle` and runs:

```bat
gradle :core:test --no-daemon
gradle :app:testDebugUnitTest --no-daemon
gradle :app:assembleDebug --no-daemon
```

Android Studio can create the wrapper when it syncs. Accept Gradle 8.11.1 if it asks.

Continuous integration is `.github/workflows/android.yml`. It runs the backend tests and the Android jobs above, then uploads `inkproof-debug-apk` only if `:core:test`, `:app:testDebugUnitTest`, and `:app:assembleDebug` all exit 0.

The green run for the app sources is https://github.com/Om280/inkproof/actions/runs/38119244590 (commit `86a885fe9fddc38fafca44ad39b0c17d72ea4159`). That run is not a substitute for the device checklist.

## Layout

- `core/` — document model, ink, shapes, local math, persistence, analysis isolation. No Android imports.
- `app/` — canvas, editor, library, settings, ML Kit fail-closed reader, file import, PDF export.
- `backend/server.js` — Gemini proxy. Start with `node server.js`.
- `schema/analysis-v1.json` — analysis response shape. `ANALYSIS_SCHEMA_VERSION` is 1.
- `docs/DEVICE_ACCEPTANCE.md` — hardware checklist. Not a substitute for running it.

## Limitations

- Binary `.doc`, `.xls`, and `.ppt` are refused. OOXML (`.docx`, `.xlsx`, `.pptx`) is imported as text, not as a live office document.
- PDF import rasterizes up to 40 pages into page backgrounds. Annotation is InkProof ink on top of that image. Export writes a new annotated PDF. It does not round-trip arbitrary PDF structure.
- Unistroke recognition is a small symbol matcher, not a math OCR engine.
- ML Kit Digital Ink recognizes text in a downloaded language model. It is not reliable for integrals, matrices, or multivariable calculus, and it fails closed when Play services or the model is unavailable.
- Palm rejection is heuristic. Do not claim it is perfect.
- No live Gemini call has been made from this environment, because no API key is present.
- Stylus pressure, hover, side button, eraser end, and palm behavior on a OnePlus Pad 2 with a OnePlus Stylo 2 are **NOT YET VERIFIED**.
