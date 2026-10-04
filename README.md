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
  Settings) and it snaps to a clean line / circle / ellipse / rectangle /
  square / triangle / polygon. Release early and your original ink is kept.
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

To point a build at a real backend:

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

No keys configured ⇒ the backend runs in mock mode automatically. Secrets are
read only from the environment; the Android client never contains them.

### Tests

```bash
./gradlew :app:testDebugUnitTest   # Android unit + Robolectric tests
cd backend && npm test             # backend tests
```

Covered: stroke codec roundtrips, shape detection (line/circle/ellipse/
rectangle/square/triangle, squiggle rejection), undo/redo, line segmentation,
strict JSON parsing (including malformed AI output), mock scenarios,
notebook/page persistence, page independence, question isolation (page A vs
page B, Q1/Q2/Q3), cache invalidation on solution edits, empty-input error
states, backend schema validation, rate limiting and dedup cache.

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
- Arrow shape snapping, text boxes and image objects on the canvas are
  modelled in the database but not yet fully editable in the UI.
- PDF export flattens ink into the PDF (annotations are not embedded as PDF
  ink objects).
- Question regions are vertical bands; free-positioned question frames are a
  future refinement.
- No accounts, sync, collaboration or subscriptions — by design (v1 scope).

## License

Personal/portfolio project.
