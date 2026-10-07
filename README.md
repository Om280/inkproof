# InkProof

**Prove your work.**

*You solve it. InkProof checks it.*

InkProof is a premium handwritten mathematics notebook for Android tablets.
You write mathematics by hand with an active stylus; InkProof checks *your*
work — finds the first mistake, explains it, and coaches you with progressive
hints. It is deliberately **not** an AI homework-answer app: the student does
the solving, InkProof does the verifying.

Primary hardware target: **OnePlus Pad + OnePlus Stylo 2**.

---

## Features

- **Low-latency handwriting** — ink appears under the stylus as you write.
  Pressure-tapered strokes, hover cursor, stylus-button eraser, palm rejection
  based on real Android tool types (not timing hacks).
- **Real vector stroke model** — every stroke has a persistent ID, points with
  pressure + timestamps, color, width and tool. Fully editable: undo, redo,
  erase, lasso, move, copy/paste, recolor. Sharp at any zoom, never flattened
  to a bitmap.
- **Notebook library** — folders, notebooks, pages, favorites, search, rename,
  duplicate, delete; cards show real page thumbnails.
- **Templates** — blank, ruled, grid, dot grid, engineering grid, math
  worksheet. Switching templates never touches your ink.
- **Hold-to-shape** — draw a rough shape, keep the pen down ~400 ms (tunable in
  Settings) and it snaps to a clean line / arrow / circle / ellipse /
  rectangle / square / triangle / polygon. Release early and your original ink
  is kept.
- **Text boxes** — tap with the Text tool to place typed text anywhere on the
  page; tap an existing box to edit or delete it.
- **Import image** — pick a photo from the gallery and it becomes a page you
  can annotate (great for textbook problems).
- **Math question pages** — a **Question is a first-class object** with its own
  content (typed, handwritten, pasted, imported), its own solution region,
  version counters and check history. Multiple questions per page are fully
  isolated.
- **CHECK MY WORK** — compact side panel (your handwriting stays visible):
  per-step verdicts (✓ / ✗ first mistake / ↳ depends on earlier error),
  progressive hints, optional full solution. **SOLVE** is a separate, visually
  distinct action.
- **Honest states** — correct, incorrect, incomplete, unclear, unsupported,
  error. If handwriting can't be read confidently, InkProof says so.
  **It never guesses.**
- **Lasso + check selection** — on freeform pages, lasso any work and check
  just that selection. Selections inside a question route through the Question
  object (its typed statement is the source of truth).
- **PDF** — import a PDF (each page becomes an annotatable page), write on it,
  export the annotated notebook back to PDF.
- **Local-first** — notebooks, pages, handwriting, lasso, shapes and storage
  all work offline. Only CHECK/SOLVE needs the network.
- **Pen stabilization** — Off / Low / Medium / High adaptive jitter smoothing
  that preserves corners and adds zero latency (position-only filtering).
- **Continuous scrolling** — finger-scroll naturally from page to page;
  neighbor pages render in place and the switch is a seamless camera handoff.
  Pages stay fully independent objects underneath.
- **Paper colors** — per-page paper from white to black (warm white, grey,
  slate, dark grey, near black); independent of both template and app theme;
  template lines adapt to dark paper; ink is never auto-inverted.
- **Recognize math** — lasso a region → Recognize math → Accept / Edit;
  local on-device recognition, never automatic, never replaces handwriting.
- **Dark mode** — System / Light / Dark in Settings → Appearance; one theme
  system drives every screen, dialog and panel. Pages keep their own paper
  color — a dark app never forces dark pages.
- **Universal import** — one "Import file…" picker: PDF (multi-page,
  annotatable), images (PNG/JPG/WEBP/BMP), and text formats (TXT/MD/HTML/CSV →
  editable text pages). Unsupported formats (DOCX/XLSX/PPTX/SVG…) are rejected
  gracefully with a clear reason, never a crash.
- **Mock mode** — the full UX works with zero credentials; deterministic mock
  results (`mock:correct`, `mock:incorrect`, `mock:incomplete`, `mock:unclear`
  keywords force scenarios). Mock data can never leak into real checks: the
  provider is chosen per call and only ever processes the submitted request.

---

## Architecture

```
android/ (this repo, :app)
├── ink/        Custom handwriting engine (no Compose in the hot path)
│   ├── InkCanvasView      low-latency input + rendering
│   ├── StrokeRenderer     pressure-tapered vector ink
│   ├── ShapeDetector      pure-geometry hold-to-shape
│   ├── CanvasCamera       page-space ↔ screen-space transform
│   └── UndoRedoStack      bounded command history
├── model/      Stroke / Question / CheckResponse (strict JSON schema)
├── data/
│   ├── db/     Room: folders, notebooks, pages, questions, strokes,
│   │           text/image objects, cached check results
│   └── repo/   Library / Page / Check repositories
├── check/      CheckWorkEngine + replaceable abstractions:
│   ├── HandwritingRecognizer  (LocalDigitalInkRecognizer | MockRecognizer)
│   ├── CheckProvider          (BackendCheckProvider | MockCheckProvider)
│   └── MathVerificationProvider (backend-side Wolfram)
├── pdf/        PdfImporter / PdfExporter
└── ui/         Jetpack Compose: library, editor, check panel, settings

backend/ (zero-dependency Node 18+)
└── /api/check, /api/solve, /api/health
    rate limiting · request validation · AI call · Wolfram cross-check ·
    strict response sanitization · response cache
```

### Handwriting latency architecture

The previous prototype rendered ink only after state updates. InkProof fixes
this at the architecture level:

```
Stylus MotionEvent (+ batched historical samples, unbuffered dispatch)
  → InkCanvasView input layer (page-space points, pressure, timestamps)
  → active stroke drawn IMMEDIATELY in onDraw
  → committed strokes live in a Picture display list,
    rebuilt only when the stroke set changes — never per point
  → on pen-up, the stroke is persisted to Room asynchronously
```

Nothing on the stylus path waits for Compose state, the database, JSON,
recognition or the network.

### Input policy (OnePlus Stylo 2 first)

- `TOOL_TYPE_STYLUS` writes; `TOOL_TYPE_ERASER` and the primary stylus button
  erase; hover shows a tool cursor.
- Fingers navigate: one-finger pan, two-finger pan, pinch-zoom.
- Palm rejection: finger touches are ignored while the stylus is in contact or
  hovered recently; extra pointers during a stroke are discarded. Optional
  "finger writing" setting for tablets without a stylus.

### Question model

```
Page (NOTE | MATH_QUESTION | PDF)
└── Question (first-class object)
    ├── content: typed | handwritten | image | pdf | pasted
    │     typed text is NEVER OCR'd — it is already the truth
    ├── question region (y-band)   → ink here = question content
    ├── solution region (y-band)   → ink here = the student's solution,
    │     tagged with the question ID at write time
    ├── contentVersion / solutionVersion  → cache invalidation
    └── check history (CheckResult rows)
```

The question **object** is the source of truth; the on-page divider is only
its visual representation. Checking Q2 can structurally never see Q1/Q3 — the
engine loads strokes by `questionId`, not by scanning the page.

### CHECK MY WORK pipeline

```
Question object + ONLY that question's solution strokes
  → HandwritingRecognizer (stroke data: order, coordinates, timestamps —
    never screenshots; low confidence ⇒ UNCLEAR, never a guess)
  → structured CheckRequest JSON
  → backend: validate → AI step analysis → Wolfram cross-check →
    sanitize to strict schema
  → CheckResponse JSON → compact side panel
  → cached by (question, contentVersion, solutionVersion, action);
    editing the solution automatically invalidates the cache
```

"What did the student write?", "Is it mathematically correct?" and "How should
we explain it?" are three separate, replaceable layers.

---

## Setup

### Requirements

- Android Studio (Koala or newer) with Android SDK 34
- JDK 17
- Node 18+ for the backend

### Android app

```bash
git clone <this repo>
# open in Android Studio, or:
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Debug builds default to **mock mode** — the entire app, including CHECK MY
WORK, works with no backend and no credentials.

To point the app at a real backend, either set it **at runtime** in
Settings → AI → Backend URL (e.g. `http://192.168.1.50:8787` while the
backend runs on a computer on the same Wi-Fi — debug builds allow plain
HTTP for this), or bake a default into the build:

```bash
./gradlew :app:assembleDebug -PinkproofBackendUrl=https://your-backend.example.com
```

then turn off "Mock mode" in Settings → AI.

### Backend

```bash
cd backend
cp ../.env.example .env     # fill in keys; see .env.example
npm start                   # zero dependencies, plain Node
npm test
```

**Google Gemini is the primary AI provider** — set `GEMINI_API_KEY`
(free key from https://aistudio.google.com/apikey). The default model is the
rolling alias `gemini-flash-latest`, so the backend never pins an obsolete
model name; override with `GEMINI_MODEL`, and if a pinned name ever
disappears the backend auto-discovers a current Flash model via the
ListModels API. Anthropic and OpenAI keys work as drop-in alternatives
behind the same provider interface.

No keys configured ⇒ the backend runs in mock mode automatically. Secrets are
read only from the environment; the Android client never contains them.

## Getting CHECK MY WORK Working

The real CHECK/SOLVE flow needs a Gemini API key on the **backend** (never in
the Android app). Follow these steps end-to-end:

1. **Open Google AI Studio** → https://aistudio.google.com/apikey
   (sign in with any Google account).
2. **Create an API key** ("Get API key" → "Create API key"). Free-tier keys
   work; Gemini Flash models have a free quota (subject to Google's current
   limits — not unlimited).
3. **Configure the backend**: copy the template and edit it.
   ```bash
   cd backend
   cp ../.env.example .env
   ```
4. **Set `GEMINI_API_KEY`** in `backend/.env`:
   ```
   GEMINI_API_KEY=AIza...your-key...
   ```
5. **(Optional) set `GEMINI_MODEL`**. The default `gemini-flash-latest` is a
   rolling alias that always points at the current stable Flash model, so you
   normally don't need this. To pin a version, check the current model list at
   https://ai.google.dev/gemini-api/docs/models and set e.g.
   `GEMINI_MODEL=<model-name-from-docs>`. If a pinned name ever 404s, the
   backend auto-discovers a current Flash model and retries.
6. **Start the backend**:
   ```bash
   node --env-file=.env server.js        # needs Node 18+ (22+ recommended)
   ```
   You should see `InkProof backend listening on :8787 (mock_mode=false)`.
   `mock_mode=false` confirms the key was picked up.
7. **Start the Android app** on the tablet (install the debug APK below).
8. **Check AI status**: Settings → AI → **Test AI connection**. You should see
   `AI CONNECTED — provider: gemini`. If you see `BACKEND UNAVAILABLE`, set
   Settings → AI → Backend URL to `http://<your-computer-ip>:8787` (both
   devices on the same Wi-Fi; debug builds allow plain HTTP).
9. **Run a test request** from the computer:
   ```bash
   curl http://localhost:8787/api/health
   curl -X POST http://localhost:8787/api/check -H "content-type: application/json" \
     -d '{"request_id":"t1","action":"check","question_id":"q1","question_text":"Solve 2x + 6 = 14","question_source":"typed","question_confidence":1,"solution_lines":[{"line_index":0,"text":"2x = 8","confidence":0.95},{"line_index":1,"text":"x = 4","confidence":0.95}],"content_version":1,"solution_version":1}'
   ```
   A JSON result with `"status":"correct"` means Gemini is answering.
10. **Test CHECK MY WORK**: turn OFF Settings → AI → Mock mode, open a
    question, write a solution, tap **Check my work**.
11. **Test SOLVE**: same question → **Solve** (uses only the question text).
12. **Common API errors**
    | Symptom | Cause / fix |
    |---|---|
    | `gemini 400` | Malformed key — re-copy it without spaces |
    | `gemini 403` | Key disabled/restricted — create a fresh key in AI Studio |
    | `gemini 404` | Model name gone — backend auto-falls back; or fix `GEMINI_MODEL` |
    | `gemini 429` | Free-tier quota hit — wait a minute, or lower usage |
    | `BACKEND UNAVAILABLE` | Backend not running / wrong IP / firewall blocks :8787 |
    | Result is always mock | Mock mode still ON in app settings, or `MOCK_MODE=true` in `.env` |

**Where secrets live**: only in `backend/.env` (gitignored) or your host's
environment variables. The Android client and this repo contain **no keys**.
**Restart/redeploy**: edit `.env`, Ctrl-C the Node process, start it again —
provider/model changes take effect immediately (the app also re-reads the
backend URL per request, no reinstall needed).

### Windows setup

Everything works on Windows with Node 18+ and Android Studio:

```powershell
# Backend (PowerShell)
cd backend
Copy-Item ..\.env.example .env
notepad .env                       # paste GEMINI_API_KEY
node --env-file=.env server.js     # http://localhost:8787

# Test (PowerShell)
Invoke-RestMethod http://localhost:8787/api/health

# Android: open the repo in Android Studio and Run, or build an APK:
.\gradlew.bat :app:assembleDebug   # output: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:testDebugUnitTest
```

Alternative to a local `.env`: set a user environment variable
(`setx GEMINI_API_KEY "AIza..."`, then restart the terminal) and run
`node server.js`. Find your PC's IP for the tablet with `ipconfig`
(IPv4 address), and allow Node through Windows Defender Firewall when
prompted. Never commit a real key.

### Tests

```bash
./gradlew :app:testDebugUnitTest   # Android unit + Robolectric tests
cd backend && npm test             # backend tests
```

Covered: stroke codec roundtrips, shape detection (line/arrow/circle/ellipse/
rectangle/square/triangle, squiggle rejection), eraser hit-testing (segment
distance, radius sizes, pass-through misses), shape factory geometry + scaling,
clipboard paste independence (new IDs, no shared references), undo/redo, line
segmentation, strict JSON parsing (including malformed AI output), mock
scenarios, recognition-confidence thresholding (UNCLEAR, provider never
called), SOLVE isolation (student ink never recognized, provider gets the
question only — linear/quadratic/derivative/integral/limit), import
classification (PDF/image/text/HTML/CSV routed; DOCX/XLSX/PPTX/SVG rejected
with reasons), text import (HTML stripping, CSV layout, line wrapping),
folders, text-object persistence, notebook/page persistence, page reordering,
page independence, question isolation (page A vs page B, Q1/Q2/Q3), cache
invalidation on solution edits, empty-input error states, backend schema
validation, provider priority (Gemini first), rate limiting and dedup cache,
stroke stabilization (jitter reduction, corner preservation, endpoint
fidelity, OFF pass-through), paper-color persistence + page independence +
dark-paper detection + adaptive template lines + ink never recolored.

CI (GitHub Actions) runs both suites and uploads a debug APK artifact on every
push.

---

## OnePlus Pad testing

The architecture targets the OnePlus Pad + Stylo 2 (tool-type routing,
pressure, hover, unbuffered dispatch, stylus-button eraser). **Physical
validation on the device has NOT been performed from this environment** — the
following must be verified on hardware: live ink latency, palm rejection feel,
pressure curve, hover cursor, hold-to-shape timing, long-session performance
with thousands of strokes, pinch-zoom smoothness.

## Known limitations

- On-device ML Kit digital ink uses the `en-US` text model (Google ships no
  public math model); complex notation is better served by a cloud math
  recognizer — the `HandwritingRecognizer` interface is designed for exactly
  that swap. When recognition confidence is low, InkProof reports UNCLEAR
  instead of guessing.
- Image objects as movable canvas elements are modelled in the database but
  not yet editable in the UI — "Import image" brings a picture in as a page
  you can write on instead.
- Typed text boxes are placed/edited/deleted via tap dialogs; drag-to-move and
  resize for text boxes are future refinements.
- Physical validation on a OnePlus Pad + Stylo 2 has not been performed from
  this environment and remains required.
- PDF export flattens ink into the PDF (annotations are not embedded as PDF
  ink objects).
- Question regions are vertical bands; free-positioned question frames are a
  future refinement.
- No accounts, sync, collaboration or subscriptions — by design (v1 scope).

## License

Personal/portfolio project.
