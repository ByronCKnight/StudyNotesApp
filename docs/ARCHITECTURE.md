# StudyNotes — Architecture & Behaviour Spec

StudyNotes is an Android app for reading PDF and Word (.docx) files and marking them up with a
stylus: highlighter, pen, typed sticky notes. Primary device: **Lenovo Idea Tab / Idea Tab Pro**
(Android 14–16, 11–12.7" 2.5K–3K screens, Lenovo Tab Pen / Tab Pen Plus), but it must work on any
Android 8.0+ (API 26+) phone or tablet, with or without a stylus.

## 1. Requirements → design

| Requirement | Design |
|---|---|
| Easy install | Signed release APK (`dist/StudyNotes-<version>.apk`), sideload. |
| Dedicated, easy-to-access folder | `Documents/StudyNotes` on shared storage (all-files access on Android 11+; legacy permission on 8–10). Visible in the Files app and over USB from a PC. Private fallback if the user declines. |
| Highlight text with a stylus | Freehand translucent highlighter (MULTIPLY blend, text stays crisp). Pen draws, fingers scroll/zoom (palm rejection, auto-enabled the first time a stylus is seen). Hold-to-straighten. Optional "Snap to text" (platform text selection, API 35+/ext 13). |
| Add notes | Typed sticky notes placed on the page (Note tool → tap) + handwritten pen ink. |
| Save button + autosave ~5 min | Explicit **Save** in the editor top bar; autosave every N minutes (default 5) when there are unsaved changes; also saves when the app goes to the background; prompt on exit with unsaved changes. |
| Google Drive / OneDrive | Import through the system document picker, where the Drive and OneDrive apps appear as sources (no API keys / OAuth app registration needed). Also "Open with" / "Share to StudyNotes" from those apps. Export/share the annotated PDF back to them. |
| Sleek, uncluttered, efficient | One home screen (library grid) and one editor. Editor chrome = slim top bar + one floating tool pill. Options for a tool appear only when its selected button is tapped again. |

## 2. Stack & build

- Kotlin, Jetpack Compose (Material 3), single Activity, no navigation library (MainActivity swaps
  `LibraryScreen` / `EditorScreen` and scopes a ViewModelStore per screen).
- AGP 9.2.1 (built-in Kotlin — do **not** add the kotlin-android plugin), Gradle 9.4.1, JDK 21 (Android Studio JBR),
  compileSdk/targetSdk 36, minSdk 26.
- Libraries: AndroidX core/activity/lifecycle, Compose BOM 2026.02.01 + material3 + material-icons-extended,
  kotlinx-coroutines, PdfBox-Android 2.0.27.0 (export only). JSON via `org.json` (platform). No other deps
  without a strong reason.
- PDF viewing: platform `android.graphics.pdf.PdfRenderer`. DOCX: own converter → PDF via `android.graphics.pdf.PdfDocument`.
- Build: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease`.

## 3. Package map

```
com.studynotes.app
  StudyNotesApp, AppContainer, MainActivity          core (done)
  model/        Annotations, AnnotationJson, LibraryModels, Tools   core (done)
  data/         AppSettings, AnnotationStore, LibraryPaths           core (done)
  ui/theme/     Theme (colours, type, shapes)                       core (done)
  pdf/          PdfDocumentHandle (+ private helpers)                 PDF engine
  editor/canvas/ DocumentCanvasView, CanvasListener, AnnotationPainter, InkGeometry, … Canvas
  docx/         DocxRenditionManager, DocxConverter, parser/layout     DOCX
  export/       AnnotatedPdfExporter (+ helpers)                     Export
  library/      LibraryStorage, LibraryRepository, LibraryScreen, LibraryViewModel,
                ThumbnailLoader, import sheet, onboarding, settings sheet   Library
  editor/       EditorScreen, EditorViewModel, UndoStack, tool palette, note editor,
                ui/icons/ToolIcons                                   Editor
```

Files containing `CONTRACT (frozen)` define public APIs other modules call. Implementers may add
private/internal members and new files in their own packages, but must not change or remove the
frozen signatures.

## 4. Data model

See `model/Annotations.kt`. **Page space**: PDF points of the page as displayed by PdfRenderer
(crop box ∩ media box, /Rotate applied), origin top-left, y down. Every annotation coordinate and
width is in page space, so annotations are resolution- and zoom-independent.

- `Stroke(id, tool HIGHLIGHTER|PEN, color (opaque ARGB), width pt, points FloatArray, flatCaps, groupId)`
- `Note(id, x, y (card top-left), text, color, createdAt, modifiedAt)`
- `DocumentAnnotations(pages: Map<Int, PageAnnotations>, viewState)` — immutable; edits create new snapshots.

Sidecar: `<library>/.studynotes/annotations/<fileName>.json` (format in `AnnotationJson.kt`), written
atomically by `AnnotationStore` with a `.bak` of the previous version. Original documents are never
modified. DOCX annotations are in the page space of the DOCX's PDF rendition.

**Converter changes move DOCX marks.** A rendition is kept while its source is unchanged, even
across `DocxConverter.VERSION` bumps, so existing marks stay put. But a rendition that has to be
rebuilt (cache deleted, or a sidecar restored without its rendition) is laid out by the current
converter, and marks made on an older layout land where that layout had the text. Version 1.0.0
ships converter version 3. Before changing the layout after release, record the converter version
in the sidecar and warn ("marks may be misaligned") when it differs from the rendition's.

Library folder layout: see `data/LibraryPaths.kt`.

## 5. PDF engine (`pdf/PdfDocumentHandle`)

Contract in the file. Verified platform facts (see research notes):
- Backends: **MODERN** `android.graphics.pdf.PdfRendererPreV` when `SDK_INT >= 35 ||
  (SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)` — annotate with
  `@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)`; **LEGACY**
  `android.graphics.pdf.PdfRenderer` otherwise. (The new members on `PdfRenderer.Page` are API 35
  only; PreV covers 31–34 with ext 13 *and* 35+.) The Lenovo Idea Tab Pro launched on Android 14, so
  both paths matter.
- One dedicated thread per handle (single-thread executor dispatcher). PDFium uses a process-wide lock.
- Keep the most recently used page open; close it before opening another (LEGACY throws
  `IllegalStateException("Current page not closed")` otherwise).
- `render(bitmap, destClip = null, transform, ...)`: transform maps page points -> bitmap px
  (`Matrix().apply { setScale(s, s); postTranslate(-region.left*s, -region.top*s) }`). Bitmap must be a
  mutable ARGB_8888; PdfRenderer paints NO background -> `bitmap.eraseColor(WHITE)` before every render.
  MODERN: `RenderParams.Builder(RENDER_MODE_FOR_DISPLAY).setRenderFlags(FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or FLAG_RENDER_TEXT_ANNOTATIONS).build()`.
- Open: `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`. Close the PFD yourself if the LEGACY
  constructor throws. Map exceptions: `SecurityException` -> PASSWORD_PROTECTED; missing/unreadable
  file -> NOT_FOUND / IO; `IOException`, `IllegalArgumentException`, `IllegalStateException`,
  `RuntimeException` and checked `java.lang.Exception` ("unknown error") -> CORRUPT. Catch `Exception`.
- Page sizes need `openPage` on every page (parses content: ~1-10 ms/page). Cache the list as JSON in
  `sizeCacheDir/<hash of path>.json` with file length + lastModified; reuse when they match.
  Page sizes are ints (truncated points) — use them as-is.
- Text selection (MODERN only): `page.selectContent(SelectionBoundary(Point(x, y)), SelectionBoundary(Point(x2, y2)))`
  with integer **page points** (top-left origin). Returns `PageSelection?`; take
  `selectedTextContents.flatMap { it.bounds }` — RectF per **line**, in page points. Boundaries snap
  to the nearest character measured to its **baseline**, even far from text; identical start/stop =
  word-at-point (10 pt tolerance) or null. Wrap in try/catch, return null on failure/empty.
  `getTextContents()` has no bounds, but its text is what character indices refer to. The returned
  selection's boundaries carry no index (-1), while `SelectionBoundary(index)` selects by index
  (stop exclusive). The backend uses this to grow a selection to whole words (`pdf/WordSelection.kt`,
  `PageText`): find the selected text in the page text (whitespace ignored), confirm the occurrence
  by re-selecting it by index and comparing bounds (±1 pt; at most 24 candidates), extend both ends
  over letters/digits/apostrophes, and select again by index. Any mismatch keeps the original.

## 6. Canvas (`editor/canvas/DocumentCanvasView`)

### Layout
- Pages stacked vertically, centred horizontally, 12 dp gap, plus content padding top/bottom from
  `setContentPadding` (toolbars overlay the canvas).
- Zoom 1.0 = **fit width**: page width = view width − 2×12 dp side margin (no cap; users zoom out on
  wide landscape screens). Each page is scaled by its own width (mixed page sizes all fit width).
- Zoom range 0.5 … 6.0. Below 1.0 pages shrink and stay centred.
- `pxPerPt(page) = (fitWidthPx / pageWidthPt) × zoom`.

### Rendering
- **Base bitmaps** per page: rendered at fit-width resolution (zoom 1), capped at 4 MP per bitmap
  (scale down proportionally). LruCache sized in bytes (~1/5 of `Runtime.maxMemory()`, min 32 MB,
  max 256 MB). Render the visible pages first, then +/-2 neighbours. While missing, draw a white page
  (soft outline) — never a blank gap.
- **Detail tiles** when zoomed in: when the on-screen scale exceeds the base bitmap's scale by > 15 %,
  overlay 512x512 px tiles rendered with `renderRegion` at a *quantized* scale (powers of sqrt(2) of the
  fit-width scale, at or just above the current scale). Request only tiles intersecting the viewport
  (+ half a tile margin), nearest-to-centre first, ~120 ms after scrolling/zooming settles. Tiles live
  in their own byte-budgeted LruCache (~1/6 of max memory) keyed by (page, scaleStep, col, row);
  recycle via a small bitmap pool. During a pinch, keep drawing existing tiles/base scaled (no
  re-render until the gesture ends). Cancel stale tile jobs (generation counter / Job cancellation
  between tiles — a render itself cannot be interrupted).
- Never draw a bitmap > 100 MB on the canvas (platform limit); the caps above guarantee this.
- Page draw order: soft shadow/outline, white fill, base bitmap, detail tiles, annotations via
  `AnnotationPainter`, then the in-progress stroke and pending/erasing state.
- Default hardware-accelerated drawing, no extra layers: highlighter MULTIPLY must blend with the
  page bitmap drawn earlier on the same canvas.

### Input
Determine tool type from `MotionEvent.getToolType(pointerIndex)`.

| Input | stylusOnly = true (pen detected) | stylusOnly = false (no pen) |
|---|---|---|
| Stylus, tool HIGHLIGHTER/PEN | draw | draw |
| Stylus, tool ERASER, **or** stylus with `BUTTON_STYLUS_PRIMARY/SECONDARY` pressed, **or** `TOOL_TYPE_ERASER` | erase | erase |
| Stylus, tool NOTE | tap empty spot → place note; tap note → open; drag note → move | same |
| Stylus, tool VIEW | pan like a finger | pan |
| 1 finger | pan / fling; tap note → open; NOTE tool tap empty → place note | ink tools: draw/erase with finger; VIEW: pan; NOTE: tap place / drag pan |
| 2 fingers | pinch-zoom + pan | pinch-zoom + pan (cancels an in-progress finger stroke) |
| Double-tap (finger) | zoom toggle: < 1.8 → 2.5 at tap point, else → 1.0 (animated) | same (in VIEW/NOTE tools; in ink tools double-tap is ignored when finger draws) |

- First `TOOL_TYPE_STYLUS`/`TOOL_TYPE_ERASER` event → `listener.onStylusDetected()` (once per document).
- While a stylus pointer is down, ignore all finger pointers (palm). `ACTION_CANCEL` (incl. API 33
  `FLAG_CANCELED`) discards the in-progress stroke.
- `requestUnbufferedDispatch(event)` on stylus ACTION_DOWN (API 30+). Consume historical points.
- A stylus tap (moved < 8 px, < 300 ms) on a note in an ink tool opens the note instead of leaving a dot.
- Strokes start only on a page; points are clamped to that page's bounds (stroke stays on one page).
- **Hold to straighten** (ink tools): if the pen stays within 3 dp for 550 ms after the stroke is
  > 24 dp long, convert it to a straight segment start→current point, haptic tick
  (`performHapticFeedback(CLOCK_TICK)`), then until lift only the end point follows the pen.
- `ToolState.straightHighlighter`: highlighter strokes are always straight segments (start → current).
- **Snap to text** (`ToolState.snapToText` && `handle.supportsTextSelection`), highlighter only: on lift,
  call `selectTextRects(page, startPoint, endPoint)` with both points moved DOWN by 0.25 x highlighter
  width (selection snaps to glyph baselines; a stroke is drawn through the middle of the x-height). Accept if non-empty and plausible: each rect
  height ≤ 3× highlighter width, total vertical span ≤ stroke's vertical span + 2× max rect height, and
  ≥ 60 % of the stroke's points lie inside the rects inflated by half their height. Accepted → one
  stroke per rect: `points = [left, centerY, right, centerY]`, `width = rect.height() × 1.15`,
  `flatCaps = true`, shared `groupId`, the highlighter colour. Otherwise keep the freehand stroke.
  Vertical text (a /Rotate 90 or 270 page: every rect taller than wide and the stroke more vertical
  than horizontal) goes through the same rules with x and y swapped, so the band runs along the line.
  Keep showing the freehand stroke while waiting.
- Point simplification on lift: Ramer–Douglas–Peucker with tolerance 0.15 pt (pen) / 0.35 pt
  (highlighter), always keeping endpoints. A tap with no movement makes a dot (single point) for the
  pen; the highlighter ignores taps shorter than 2 pt total length.
- **Eraser** (stroke eraser): radius 10 dp on screen (converted to page points). Any stroke whose
  polyline comes within (radius + width/2) is removed — with its whole `groupId` group. Removed strokes
  hide immediately (local pending set); on lift fire one `onStrokesErased`. Notes are not erased.
- Pending strokes: after `onStrokesAdded`, keep drawing them until an annotations snapshot containing
  their ids arrives (no flicker).
- Notes: NOTE tool drag on a note moves it (live preview, clamped to page) then `onNoteMoved`.

### Stylus specifics (Lenovo Tab Pen / Tab Pen Plus research)
- The pen reports `TOOL_TYPE_STYLUS` with pressure/tilt; it has **no eraser end** and its side button is
  captured by Lenovo (not delivered to apps). So the Eraser *tool* must always be available; still
  honour `TOOL_TYPE_ERASER` and `BUTTON_STYLUS_PRIMARY/SECONDARY` (other brands deliver them).
- Constant stroke width (ignore pressure and tilt — Lenovo tilt is over-sensitive, pressure skews thick).
  Light smoothing (quadratic midpoint curves; optionally a 3-point moving average) hides diagonal jitter.
- `requestUnbufferedDispatch(event)` ONLY for stylus gestures (it makes finger scrolling jittery).
- `FLAG_CANCELED` (API 33+) appears on `ACTION_POINTER_UP` and `ACTION_CANCEL` (not ACTION_UP):
  discard the affected stroke.
- Palm guard (stylusOnly): ignore finger pointers while a stylus pointer is down AND for 400 ms after
  the last stylus up or hover event.
- Finger drawing (stylusOnly = false): start the stroke after touch slop; if a second finger lands
  within 150 ms of the first (or before the stroke exceeds ~3× touch slop), discard the pending stroke
  (no undo entry) and switch to two-finger pan/zoom.
- Hover (`ACTION_HOVER_ENTER/MOVE/EXIT` from a stylus): draw a subtle ring showing the highlighter
  width / eraser radius at the hover point; hide on exit. Degrade gracefully when no hover arrives.
- Prediction (display only): on API 34+ use the platform `android.view.MotionPredictor`
  (`record(event)`, `predict(System.nanoTime() + ~16 ms)`, `isPredictionAvailable(deviceId, source)`)
  to draw a short predicted tail for the pen; never store predicted points.
- Edge-to-edge: while an ink tool is active and a stylus is near/at a screen edge, set
  `systemGestureExclusionRects` to a 200 dp band (left and right edges) centred on the pen's y so a
  stroke near the edge doesn't trigger the back gesture.
- `scrollByScreens(±0.9)` for PAGE_UP/PAGE_DOWN/DPAD keys (the editor routes key events).

### Gestures & scrolling
- `ScaleGestureDetector` + `GestureDetector` (or equivalent) with `OverScroller` fling. Clamp
  scroll so content edges can't go past the view (centre when smaller).
- Animated `scrollToPage` and double-tap zoom (`ValueAnimator`, ~250 ms, decelerate).
- Keep the viewport anchored (same page + fraction + zoom) across size changes (rotation, split-screen).
- `onCurrentPageChanged` from the page under the viewport centre.
- A pen navigating (View tool, or Note tool off a note) is handled without `GestureDetector`: a pen
  lift within touch slop (no scale, not catching a fling) is a tap that opens the note under it or,
  with the Note tool, places one. Pen + palm is never a pinch. A touch that stops a fling or animation
  is never a tap.
- One eraser gesture erases on the first page it touches only (one `onStrokesErased`, one undo step).
- A second finger during an established finger stroke (after 150 ms and ~3x touch slop) is ignored.
- Internal additions to the frozen API: `commitPendingInk()` emits highlights still waiting for their
  snap-to-text result (the editor calls it before undo, redo, save, export and leaving), and
  `setSideInsets(leftPx, rightPx)` lays fit-width pages out between a side navigation bar / cutout
  while the view itself stays full-screen.
- Memory: the view registers `ComponentCallbacks2` while attached; UI hidden drops tiles and trims
  base bitmaps, TRIM_MEMORY_BACKGROUND drops every bitmap; rendering resumes when visible again. When
  the visible pages' base bitmaps don't fit the cache together they are rendered at a reduced scale.

## 7. AnnotationPainter

Contract in the file. Highlighter paint: `Paint.Style.STROKE`, the stroke colour fully opaque,
`xfermode = PorterDuffXfermode(MULTIPLY)` (works on all API levels, hardware and software canvases),
round joins; caps BUTT if `flatCaps` else ROUND. Pen: SRC_OVER, round caps/joins. Smooth paths with quadratic Béziers through
midpoints. Cache `Path` per stroke id (cleared when the stroke disappears). Single-point strokes draw a
dot (circle of width/2). Note card: `color` fill, 1 px darker outline, 4 pt radius, 6 pt padding,
`StaticLayout` text in near-black (#1F1F24) at `NOTE_TEXT_SIZE_PT`, max width `NOTE_MAX_WIDTH_PT`,
min width 36 pt, ellipsize after `NOTE_MAX_LINES`; an empty note shows a small "Note" placeholder in grey.

## 8. DOCX → PDF (`docx/`)

Own converter, no external libraries: `ZipFile` + `XmlPullParser` (`XmlPullParserFactory` with namespaces
on) → document model → paginated layout with `StaticLayout` → `android.graphics.pdf.PdfDocument` (page
units = points). Text stays real text in the PDF (selectable; text snapping works).

Support (in priority order): paragraphs and runs (bold, italic, underline, strike, size, colour,
highlight, super/subscript, caps, font family → serif/sans/mono heuristic); paragraph styles from
styles.xml with `basedOn` chains and docDefaults; headings; alignment incl. justify; spacing
before/after/line (auto/exact/atLeast); indents (left/right/firstLine/hanging); numbered & bulleted
lists from numbering.xml (decimal, letters, roman, bullets; per-level counters); tabs; line and page
breaks; `pageBreakBefore`; hyperlinks (styled text); tables (grid widths, gridSpan, vMerge, cell
shading, borders when defined, nested paragraphs/tables, row splitting across pages by moving whole
rows, very tall rows clipped); inline images (`a:blip r:embed` via document.xml.rels, extent in EMU,
scaled to fit width/page, downsampled when huge); floating images rendered inline as a fallback;
`mc:AlternateContent` (use Fallback/Choice sensibly); `w:sdt`, `w:ins`, `w:smartTag`, `w:fldSimple`
descend; `w:del` skipped; footnote references as superscript numbers, with footnote text appended
at the end under a "Notes" heading; page size and margins from `w:sectPr` (default A4/Letter
1-inch margins). Headers/footers: ignored. Robustness: any unsupported element is skipped; a
malformed document raises `DocxConversionException` with a user-presentable message; never crash.

## 9. Export (`export/AnnotatedPdfExporter`, `export/ExportActions`, `export/PdfUnlocker`)

Contracts in the files. PdfBox-Android 2.0.27.0 (PDFBox 2.0 API — no `Loader.loadPDF`; packages
`com.tom_roush.*`). `PDFBoxResourceLoader.init` is already called in `StudyNotesApp.onCreate`.
- Load with `PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(cacheDir))`;
  never save onto the source; save to `<out>.part` then rename. `if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)`.
- Per annotated page: `PDPageContentStream(doc, page, AppendMode.APPEND, true, true)` (resetContext
  wraps existing content in q/Q), then `cs.transform(displayToPdf(page))` and draw in page-space coords.
  Page-space -> PDF user space, crop box C=(llx,lly,urx,ury) (`page.cropBox` is already clipped to the
  media box), rotation r = the raw inherited /Rotate normalised the way PDFium does (truncated
  quarter turns, e.g. 100 -> 90, -100 -> 270), not `page.rotation` (which is 0 for non-multiples of 90).
  Unclosed `q`s in the page's existing content are closed with extra `Q`s before the transform, so the
  marks always start from the page's initial CTM and clip:

| /Rotate | user (x',y') from page (x,y) | `Matrix(a, b, c, d, e, f)` |
|---|---|---|
| 0 | (llx + x, ury - y) | 1 0 0 -1 llx ury |
| 90 | (llx + y, lly + x) | 0 1 1 0 llx lly |
| 180 | (urx - x, lly + y) | -1 0 0 1 urx lly |
| 270 | (urx - y, ury - x) | 0 -1 -1 0 urx ury |

  Verify with the fixtures `testdata/Biology Lecture Notes.pdf` (page 5 /Rotate 90, page 6 crop box
  offset, page 7 landscape) by rendering the export with PyMuPDF.
- Highlighters: one shared `PDExtendedGraphicsState` per document: `blendMode = BlendMode.MULTIPLY`,
  stroking alpha **0.55**, and a *compensated* colour `c' = clamp((c - 0.45) / 0.55)` per channel, so
  that in viewers that support blend modes the result on white matches the on-screen colour, while
  viewers that ignore /BM still show the text through a 55 % tint. Pen: normal, opaque. Round
  caps/joins (butt caps for `flatCaps`). Smooth with `curveTo` like the painter. Colours as 0..1 floats.
- Notes: render the card with `AnnotationPainter` into a software ARGB_8888 bitmap at ~3x and draw
  with `LosslessFactory.createFromImage` + `cs.drawImage(img, Matrix(w, 0f, 0f, -h, x, y + h))` inside
  the display CTM (un-mirrors the image). Also add a `PDAnnotationText` (NAME_COMMENT, contents =
  text, colour, `isPrinted = true`, `constructAppearances(doc)`) at the card's top-right corner in
  unrotated user space.
- Raster fallback (PdfBox failure): `PdfDocumentHandle` at 2x (150 dpi) + painter -> a new PdfBox
  `PDDocument` (temp-file `MemoryUsageSetting` in cacheDir), one JPEG image page (quality 0.9, page
  size = page points) per source page, one page bitmap in memory at a time.
- `PdfUnlocker`: `PDDocument.load(file, password, mem)`; `InvalidPasswordException` -> return false.

## 10. Library (`library/`)

### Storage & onboarding
- First run (`!settings.onboardingDone`): a calm full-screen welcome: app icon, "Your study library",
  one sentence: "StudyNotes keeps your PDFs and Word files in **Documents/StudyNotes** so you can add
  files from your computer or file manager." Primary button **Allow folder access** (launches the
  Settings intent / runtime permission), secondary **Not now** (uses private storage until access is
  granted). Either marks onboarding done.
- If running without folder access, the library shows a slim dismissible banner offering access.
- Re-check access in `ON_RESUME` (user returning from Settings) → `refreshAccess()` → refresh list.

### Library screen
- Top app bar (large title "Library" collapsing, or a medium top bar): search icon (expands an inline
  search field filtering by title), sort menu (Recent / Name / Date added), Settings gear icon (no
  single-item overflow menu).
- Content: adaptive grid of document cards (`GridCells.Adaptive(168.dp)`), 16 dp spacing. Card:
  first-page thumbnail (A4-ish 3:4 area, rounded 12 dp, subtle outline), title (2 lines max), meta
  line ("PDF · 2.4 MB" / "Word · 18 KB"), and when annotated a small highlighter-dot badge with
  count. Tap → open. Long-press (and a ⋮ on the card) → menu: Rename, Share original, Export
  annotated PDF, Delete (confirm dialog, mentions notes are deleted too).
- Pull-to-refresh is unnecessary; refresh on resume and after imports.
- **Import**: an extended FAB "Import" -> modal bottom sheet with three large rows: *This device*,
  *Google Drive*, *OneDrive*. All three launch the same system picker
  (`ActivityResultContracts.OpenMultipleDocuments()` with `arrayOf(PDF, DOCX, "application/vnd.google-apps.document")`
  — never CATEGORY_OPENABLE or EXTRA_LOCAL_ONLY, which hide Google Docs / cloud roots). Deep-linking
  into Drive/OneDrive is impossible (root ids are undiscoverable); DocumentsUI already reopens at the
  last location used by this app. So the cloud rows show a one-line hint "In the picker, tap ☰ and
  choose Drive" (resp. OneDrive) until a file from that provider has been imported once (track via
  `settings.setLastPickerUri(authority, uri)` / `lastPickerUri(authority) != null`; authorities
  `com.google.android.apps.docs.storage`, `com.microsoft.skydrive.content.StorageAccessProvider`).
  If the provider app is not installed (`<queries>` already declared), the row reads "Not installed
  — get it from Play Store" and opens `market://details?id=<pkg>` (fallback https URL).
- Import copying (`LibraryRepository.importUris`): Dispatchers.IO; providers download inside
  `openInputStream` (can be slow/offline-fail). Virtual docs (`FLAG_VIRTUAL_DOCUMENT`) ->
  `getStreamTypes(uri, "*/*")`, prefer PDF then DOCX, `openTypedAssetFileDescriptor(...).createInputStream()`,
  append the extension to the display name. Validate by magic bytes (`%PDF-`; `PK\x03\x04` +
  `word/document.xml` entry) not MIME/name. After writing, `MediaScannerConnection.scanFile`.
- Rescan the folder on every ON_RESUME (no FileObserver: PC/MTP writes may bypass inotify).
- Empty state: centred illustration-like icon, "No documents yet", "Import from your device, Google
  Drive or OneDrive — or copy files into Documents/StudyNotes.", primary button "Import".
- Thumbnails: `ThumbnailLoader` renders page 1 at ~360 px wide (PDF directly, DOCX via cached rendition
  if available, else converts in background one at a time) and caches PNGs in `cacheDir/thumbs`
  keyed by fileName+lastModified+size. Placeholder: tinted page with a PDF/DOC glyph.
  Only page 1 is opened (`PdfDocumentHandle.renderFirstPage`, no page measuring), capped at 360x720 px.
  The memory cache empties itself on TRIM_MEMORY_UI_HIDDEN. A DOCX whose background conversion twice
  died with the process gets no automatic thumbnail conversion until it changes.
- Refresh re-attaches orphaned sidecars (documents renamed outside the app) when type and size match
  one unannotated document unambiguously, but never onto (or from) a document open in the editor
  (`AnnotationStore.hasPendingSave`). An import never takes a name that still has another
  document's leftover data. Imports of the app's own private files / FileProvider URIs are refused.

### Settings (bottom sheet from the top-bar gear icon)
- Library folder: path text + "Allow folder access" when private.
- Pen & touch: "Draw with finger" switch (= `stylusOnly == false`); explanation "When off, only a
  stylus draws; fingers scroll and zoom." Help line: "Lenovo Tab Pen Plus: keep Bluetooth on so the
  pen works."
- Autosave: segmented 1 / 2 / 5 / 10 min.
- About: version.

## 11. Editor (`editor/`)

### Opening
`EditorViewModel(container, documentFile)`: state `Loading("Opening…" / "Preparing document…" for
DOCX conversion)` -> `Ready` | `NeedsPassword` | `Error(message)`. Open with
`PdfDocumentHandle.open(file, sizeCacheDir = cacheDir/pagesizes)`.
Password-protected PDF (PASSWORD_PROTECTED): if `paths.renditionFile(fileName)` exists and is newer
than the source, open that unlocked copy; otherwise show a password field ("This PDF is
password-protected") -> `PdfUnlocker.unlock(source, password, paths.renditionFile(fileName))` -> open
the copy; wrong password -> inline error. Export then uses the unlocked copy as source. For DOCX: `renditions.ensureRendition(paths, file)`;
if `isSourceChanged` and annotations exist → one-time snackbar "This document changed since you
annotated it; marks may be misaligned." Open `PdfDocumentHandle`, load sidecar via
`AnnotationStore.loadResult` (missing → empty; unreadable → open without marks and say a copy was
kept; loaded from the backup → say so; the damaged file is kept as `<name>.unreadable-<time>` in both
cases, and if it can't be kept the editor never saves over it), `settings.markOpened(fileName)`. The same message is shown for
a PDF whose sidecar records another type or size. The library location is pinned when the document
opens: every sidecar / rendition access uses it, and saves (under `withLibraryLock`, then the
AnnotationStore mutex — library code must never load a sidecar inside `withLibraryLock`) refuse to
write once the document has moved.

### State & editing
- `annotations: StateFlow<DocumentAnnotations>`; every edit = new snapshot pushed through an
  `UndoStack` of commands (AddStrokes, EraseStrokes, AddNote, EditNote, MoveNote, DeleteNote), max 200.
  Undo/redo apply inverse snapshots per page. `dirty` = current snapshot ≠ last saved snapshot
  (track a monotonically increasing edit counter vs saved counter).
- View state is saved too but does not by itself make the document dirty.
- Tool state persists in `AppSettings.toolState`; `stylusOnly` from settings (`null` -> treat as
  false until `onStylusDetected` sets it to true and shows a one-time snackbar "Pen detected — fingers
  now scroll. Change in Settings.").
- Initial tool when a document opens: if `stylusOnly == true`, the last used tool (default
  Highlighter); otherwise **View**, so the first finger touch on a phone scrolls instead of drawing.
  The first stylus touch (onStylusDetected) while in View switches to the Highlighter.
- Keyboard / pen-remote paging: PAGE_DOWN, PAGE_UP, DPAD_DOWN/UP (and DPAD_RIGHT/LEFT) ->
  `canvas.scrollByScreens(±0.9)` via `Modifier.onPreviewKeyEvent` on the editor root (not while a
  text field has focus).

### Saving
- `save(reason)`: snapshot annotations + `canvas.currentViewState()` → `AnnotationStore.save` with
  `SourceInfo` (fileName, type, size, lastModified of the *original* file). Serialised (one at a time);
  a save requested during a save runs after it. On success: saved counter = snapshot's edit counter,
  `lastSavedAt` updated. On failure: error snackbar with retry; stays dirty.
- **Autosave**: coroutine loop every `settings.autosaveMinutes` (re-read each tick) → save if dirty.
- **Background**: on the Activity's ON_PAUSE (and `ProcessLifecycleOwner` ON_STOP as a fallback) →
  commit a note being typed, then save if dirty. Nothing saves after *Discard*. The editor holds an
  `AnnotationStore.reserveSave` handle from opening (taken under `withLibraryLock`) until its final
  save has landed, so a reopen of the same document waits for it. `onCleared`: if dirty and not
  discarded → final save on `container.appScope`; close the PdfDocumentHandle after that save
  completes. A canvas recreated with the activity resumes at the last reported position.
- The reading position alone is saved quietly (no status change) by autosave, background and close
  saves when it moved by more than 0.02.
- **Save button** (top bar, filled tonal, label "Save"): saves now; while saving shows a small spinner;
  then briefly "Saved ✓". Disabled look (but still visible) when nothing to save.
- **Back** (system back or ←): if dirty → dialog "Save changes?" — *Save* (save then close),
  *Discard* (close without saving; reverts to last saved), *Cancel*. If clean → close immediately.
- Status text under the title: "Saved · 2 min ago" / "Unsaved changes" / "Saving…" (updates each minute).

### Layout (sleek, uncluttered)
- Canvas fills the screen edge-to-edge behind system bars; neutral backdrop
  (`EditorColors.CanvasLight/Dark`).
- **Top bar** (translucent surface, 56–64 dp): ← back, title (1 line, ellipsized) + status line,
  spacer, Undo, Redo, Save button, ⋮ overflow (Go to page…, Show/Hide notes, Export annotated PDF…,
  Share annotated PDF, "Draw with finger" checkbox item).
- **Tool pill**: floating, bottom-centre, 8–12 dp above the navigation bar, rounded 28 dp, tonal
  surface, elevation. Buttons (48 dp touch targets): View (hand), Highlighter, Pen, Note, Eraser.
  Selected tool has a filled tonal background; ink tools show a small colour dot of the current colour.
  Tapping the *already-selected* Highlighter/Pen/Note opens a compact options popover above the pill:
  colour swatches (Palette), 3 widths (Highlighter/Pen), and for the highlighter two switches:
  "Snap to text" (only if supported) and "Straight lines". Tapping outside closes it.
- **Page chip**: small rounded "12 / 48" at bottom-left above the pill area, fades in while scrolling
  and out ~1.5 s after; tap → Go to page dialog.
- **Note editor**: modal bottom sheet (or dialog on wide screens) with a multi-line text field
  (autofocus, keyboard up), 4 colour swatches, *Delete* (for existing notes), *Done*. Empty text on
  Done for a new note = cancel; for an existing note = ask to delete. Placing a note: new note at the
  tapped point (clamped so the card stays on the page).
- Export: `ExportActions.exportDocument(...)` to
  `cacheDir/exports/<16-hex SHA-1 of the document file name>/<title> (annotated).pdf` with a
  progress dialog that can be cancelled. **Share annotated PDF** (primary; Drive shows "Save to Drive", OneDrive accepts
  SEND) uses `ExportActions.shareIntent` (FileProvider `${applicationId}.fileprovider` with display
  name, `ACTION_SEND`, `ClipData.newRawUri`, `FLAG_GRANT_READ_URI_PERMISSION`, chooser).
  **Save a copy…** (secondary) uses `ActivityResultContracts.CreateDocument("application/pdf")` then
  `ExportActions.copyToUri` (OneDrive may not appear there — that's expected). Don't delete export
  files right after sharing (cloud apps upload later); `ExportActions` prunes exports older than a
  day. Uses the live annotations; unsaved edits are saved first anyway, so a long export the system
  kills can't lose them. A failed or cancelled "Save a copy…" removes the destination the picker
  created if nothing was written to it. The share chooser excludes StudyNotes itself.
- Show/Hide notes is per document and per session (not stored in settings); placing a note or picking
  the Note tool shows them again, and a "Notes hidden · Show" chip appears while they are hidden.
- Error state: friendly message + "Back to library" button (password-protected PDF, corrupt file, conversion failure).

## 12. Design language

- Material 3 with the custom indigo scheme in `ui/theme/Theme.kt`; light & dark. Generous spacing
  (16/24 dp), rounded shapes, few borders, tonal surfaces instead of heavy shadows. One accent colour.
- No more than ~6 controls visible on the editor at once. Labels only where icons are ambiguous;
  every icon button has a contentDescription and a tooltip/long-press label.
- Tool icons are custom `ImageVector`s in `ui/icons/ToolIcons.kt` (highlighter, pen, eraser, note,
  hand), 24 dp, 2 dp strokes, rounded — consistent with Material Symbols Rounded.
- Large screens: content max widths for dialogs/sheets (≤ 560 dp); grid adapts; editor pill centred.

## 13. Engineering rules

- Main thread never does file IO, PDF rendering, JSON or DOCX work.
- Every user-visible failure becomes a short, human message (no stack traces).
- Release build uses R8; keep reflection-free code; PdfBox rules in `proguard-rules.pro`.
- Unit tests (JVM) for pure logic: JSON, geometry (simplify, hit-test, straighten, snap plausibility),
  undo stack, export coordinate transforms, DOCX parsing (with kxml2 on the test classpath).
- Instrumented tests (`app/src/androidTest`, fixtures in its `assets/`): canvas gestures with real
  MotionEvents (stylus/finger routing, palm guard, cancel, eraser, notes, finger drawing, zoom, snap),
  `PdfDocumentHandle` on every fixture, DOCX conversion and the rendition manager, and export
  positions checked by rendering the exported PDF (vector and raster) on every page type.
