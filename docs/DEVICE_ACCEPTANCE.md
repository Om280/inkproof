# Device acceptance

Target: OnePlus Pad 2 with OnePlus Stylo 2.

Nothing in this file has been run on that hardware. Every item is **NOT YET VERIFIED**. A green unit test or a debug APK does not check any box below.

## Ink

- [ ] NOT YET VERIFIED — Stylus stroke appears immediately and is not delayed by autosave, thumbnail write, OCR, or a network call.
- [ ] NOT YET VERIFIED — Pressure changes stroke width. Hover shows a cursor and does not lay ink.
- [ ] NOT YET VERIFIED — Stylus eraser end and side button erase. Eraser sizes small, medium, and large remove objects they hit.
- [ ] NOT YET VERIFIED — Finger never lays ink. Two-finger pan, pinch zoom, and a vertical swipe scroll the pages.
- [ ] NOT YET VERIFIED — Palm resting on the glass does not create a stroke. This is tool-type and size based. Do not treat a pass as perfect palm rejection.
- [ ] NOT YET VERIFIED — Stabilizer Off, Low, Medium, and High. Default is Medium. The tip is raw immediately; the tail catches up. Corners are not rounded away.
- [ ] NOT YET VERIFIED — Undo and redo survive a page change and a reopen.

## Tools

- [ ] NOT YET VERIFIED — Marker is wider and translucent, with its own color and opacity. It is not just a thick pen.
- [ ] NOT YET VERIFIED — Palette colors, including white, light grey, yellow, cyan, light blue, pink, and green, stay readable on dark paper. Changing paper or template does not recolor or delete ink.
- [ ] NOT YET VERIFIED — Line, arrow, rectangle, square, circle, ellipse, and triangle are shape objects. Move, resize, recolor, delete, lasso, undo, and they survive zoom, scroll, page change, and reopen.
- [ ] NOT YET VERIFIED — Hold-to-shape (about 450 ms, configurable) snaps only when confidence is high. Normal writing does not snap. Holding does not delay the ink that is already on screen.
- [ ] NOT YET VERIFIED — Lasso selects ink, shapes, text, and images. Move, copy, cut, paste, duplicate, delete, recolor, Check, and Solve work on that selection. Paste gets new IDs. Copy, paste, move, save, reopen, cut, duplicate, and undo/redo stay independent.

## Pages and questions

- [ ] NOT YET VERIFIED — Write ONE, TWO, and THREE on three pages. They survive scroll, leaving the notebook, and process death. Deleting page 2 leaves the others. A new page is blank.
- [ ] NOT YET VERIFIED — + Question adds a question on the current page, not a new page. Zero, one, and many questions can share a page with freeform ink.
- [ ] NOT YET VERIFIED — Question A `2x + 6 = 14` and Question B `x² + 4x + 1 = 0` on the same page. Checking B does not send A's text or strokes. Solve on B does not send A's solution strokes. If the target is ambiguous, the app asks instead of guessing.

## Check, solve, recognition

- [ ] NOT YET VERIFIED — Check shows correct, incorrect, incomplete, unclear, unsupported, or error. The first wrong step is identified. Later steps that depend on it are not marked independently correct.
- [ ] NOT YET VERIFIED — Hints advance one at a time. The full answer appears only after Reveal.
- [ ] NOT YET VERIFIED — The review panel does not cover the working.
- [ ] NOT YET VERIFIED — A reading under about 0.55 confidence asks for confirmation and does not replace ink.
- [ ] NOT YET VERIFIED — With the backend stopped, Check does not claim a Gemini success. Mock mode is labeled MOCK.
- [ ] NOT YET VERIFIED — No network call is made while drawing.

## Files and theme

- [ ] NOT YET VERIFIED — Import a PDF, a PNG, a TXT, a DOCX, and a corrupt file. The corrupt file fails with a message and does not wipe the notebook.
- [ ] NOT YET VERIFIED — Annotate an imported PDF and export an annotated PDF.
- [ ] NOT YET VERIFIED — System, Light, and Dark theme do not change page paper or ink color.

## How to record a result

Replace `NOT YET VERIFIED` with the date, the device, the APK version (`1.0.0-beta.1` unless the build changed), and pass or fail. Do not check an item from a unit test.
