# InkProof — Prove your work.

Early native Android tablet prototype. **Not a complete release.** This repository originally contained only this README; there was no existing implementation to preserve. The current scope is a locally persisted, stylus-first vector notebook with vertically scrolling pages, question blocks and an explicit Gemini proxy. It must not be presented as fully verified handwritten mathematics checking.

## Requirements checklist (verified status)

| Requirement | Status |
|---|---|
| Native Android canvas, immediate active stroke and historical pointer samples | Implemented in source; device latency NOT YET VERIFIED |
| Stylus-only ink / finger scroll, eraser, undo/redo, marker alpha, color, width, stabilization | Implemented in source; device behavior NOT YET VERIFIED |
| Independent pages, page colors, editable typed questions and separate solution ink | Implemented in source; persistence NOT YET VERIFIED on Android |
| Local atomic JSON save, debounced off UI thread | Implemented in source; Android runtime NOT YET VERIFIED |
| Explicit mock mode and backend health, missing-key and ambiguous-ink responses | Backend tests passed |
| Real SOLVE on typed question | Implemented proxy; live Gemini request NOT YET VERIFIED |
| Real CHECK MY WORK on handwritten solutions | **Not implemented:** no trustworthy mathematical ink recognition; returns `unclear` rather than guessing |
| Shapes, hold-to-snap, lasso, clipboard, PDF/import/export, zoom, multiple notebooks, search, themes | **Not implemented** |
| OnePlus Pad 2 / OnePlus Stylo 2 hardware acceptance | **NOT YET VERIFIED** |

## Build and run Android

Requires JDK 17, Android SDK Platform 35 and Android Gradle Plugin 8.7.3. This minimal repository has no Gradle wrapper; install Gradle 8.9 and run `gradle :app:assembleDebug` from the repository root. Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The development sandbox does not have Java, Gradle or an Android SDK; no APK was generated here.

The app starts with a blank page. Stylus draws in the freeform area or a question's solution area; finger scrolls the vertical page list. Tap `+ PAGE` or `+ QUESTION`; question creation does not add a page. Paper color is per page; tool settings apply to the selected drawing surface. The emulator backend URL defaults to `http://10.0.2.2:8080`; on a physical tablet tap SERVER and enter `http://YOUR_PC_LAN_IP:8080`. Phone/tablet and PC must share a network. HTTP cleartext is enabled **for local development only**; use authenticated HTTPS before exposing a backend publicly.

## Getting CHECK MY WORK and SOLVE Working

1. Obtain a Gemini API key in [Google AI Studio](https://aistudio.google.com/app/apikey). Do not put it in the Android project or commit it. The backend reads `GEMINI_API_KEY`; `GEMINI_MODEL` defaults to `gemini-2.5-flash`. Check [Google's current model documentation](https://ai.google.dev/gemini-api/docs/models) before selecting another model. The REST request uses `generateContent` with the `x-goog-api-key` header.
2. Copy `.env.example` to `.env` **on the backend machine** and fill values, but note the stdlib server reads process environment variables, not `.env` automatically. On Windows PowerShell, from repository root run `$env:GEMINI_API_KEY="YOUR_KEY"; $env:GEMINI_MODEL="gemini-2.5-flash"; python backend/server.py`. In Command Prompt: `set GEMINI_API_KEY=YOUR_KEY` then `python backend/server.py`. For safe mock-only operation set `INKPROOF_MOCK=1`; unset it for real requests.
3. Run `curl http://localhost:8080/health` on the PC. `configured` means a key is set, **not** that the provider is reachable. The Android AI STATUS control reports configuration, mock mode or backend unavailability. On an emulator run the Android app and use default `10.0.2.2`; on OnePlus Pad 2 set SERVER to your computer's LAN IP. Allow inbound port 8080 in the Windows firewall for local-network tests.
4. Create a typed question such as `2x + 6 = 14` and tap SOLVE. Or call `curl -X POST http://localhost:8080/solve -H "Content-Type: application/json" -d '{"question":"2x + 6 = 14"}'`. A live Gemini response requires working network, valid key, model access and quota. Inspect backend console for request HTTP status; the server intentionally does not log secrets. After changing environment variables restart the Python server (and redeploy it if hosted).
5. CHECK MY WORK currently **cannot validate handwritten ink**: the client sends only the selected question ID, page ID, typed prompt and its own solution vector strokes, and the backend returns `unclear` with HTTP 422 in real mode. Mock mode returns a clearly labeled placeholder. Never interpret either as a correct/incorrect verdict. Local math recognition, step extraction and symbolic verification must be built and tested before enabling real assessment.
6. A missing key returns `AI NOT CONFIGURED`; unreachable PC returns `BACKEND UNAVAILABLE`; Gemini HTTP failures report a provider error (check model, rate limits and quota). A configured health status does not guarantee request success. Never publicly deploy this unauthenticated development server.

## Tests and device acceptance

Run backend tests: `python -m unittest discover -s tests -v`. They check missing-key behavior, empty solution, ambiguous ink and explicit mock labeling. They are not Android canvas tests.

**OnePlus Pad 2 + OnePlus Stylo 2: NOT YET VERIFIED.** Manual acceptance still required: writing latency, small math symbols at four stabilization levels, palm rejection, pressure/hover/buttons, finger scrolling, three-page isolation after restart/deletion, eraser visibility and undo, translucent marker, question separation, dark page contrast, real SOLVE, missing-key and offline errors. Shapes, snap, clipboard, recognition and real CHECK cannot pass until implemented.
