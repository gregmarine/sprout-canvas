# sprout-canvas — Implementation Plan

> **What this document is.** The single source of truth for building sprout-canvas, written so that
> any session can pick up any phase without re-deriving context. Every phase carries its own goal,
> file-level deliverables, acceptance criteria, test protocol, and status. Read the phase you are
> working, plus **§2 Decisions**, **§3 Architecture**, and **§5 Hard-Won Lessons** — those three
> sections are the shared context every phase assumes.
>
> **Working rhythm.** One phase per session. At the end of each phase: run the tests → test on the
> devices that phase names → update that phase's **Status** in §8 → commit → push. Do not start the
> next phase in the same session unless explicitly asked.

---

## 1. Overview

### 1.1 What sprout-canvas is

An Android **library** that gives a host app a drawing surface for stylus input. It captures the
complete stylus signal the hardware offers, renders it, and hands the data to the app through one
standardized API — regardless of whether the device is a BOOX (Onyx SDK), a Supernote (Ratta
firmware ink), or an ordinary Android tablet with a stylus.

The library is a **component**, not a screen. It embeds in any window, any `ViewGroup`, at any size.

### 1.2 Goals

| # | Goal |
|---|---|
| G1 | **One entry point.** The app writes the same code on every device. `SproutCanvasView` picks the right input-capture and render path at runtime. |
| G2 | **Leverage the hardware.** On e-ink, the panel's own ink overlay is the primary live-stroke display; the app Canvas is the committed/authoritative layer behind it. Never make an e-ink device draw live ink through the Android view system. |
| G3 | **Lose nothing.** Everything the device reports — position, pressure, tilt, orientation, contact size, timing, tool kind, width, colour — reaches the app. |
| G4 | **Render anything given.** Strokes handed to the canvas by the app render identically to strokes drawn on it. |
| G5 | **Standardized vocabulary.** One set of tool names across Onyx, Supernote and generic. The app never learns a vendor's constant. |
| G6 | **Respect its own bounds.** Capture and rendering happen only inside the canvas's own rectangle. |
| G7 | **Respect overlays.** Toolbars, popups and panels drawn over the canvas are never written under, on any platform. |
| G8 | **Dynamic.** Resizes, re-layouts, and rotates without losing content or breaking hardware capture. |

### 1.3 Non-goals (explicitly out of scope — the host app owns these)

- Persistence, storage, file formats, databases
- Copy / paste / clipboard
- Undo / redo *(the app owns history; the library emits the events it needs)*
- Pages, layers, templates, surface/background art *(planned for later — see §9)*
- Lasso, selection, transform, shape recognition, handwriting recognition
- Toolbars and any UI chrome *(the library only guarantees it will not write under them)*
- Export (PNG/PDF)

### 1.4 Reference project

`~/git/Notesprout` — a shipping handwriting app with a mature two-engine drawing stack. It is the
source of nearly every hard-won lesson in §5. Key files to consult (paths relative to
`~/git/Notesprout`):

| Topic | Path |
|---|---|
| Engine interface (both engines implement it) | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/notebook/NotebookView.kt` |
| Onyx engine | `.../notebook/OnyxNotebookView.kt` |
| Generic engine | `.../notebook/GenericNotebookView.kt` |
| Exclusion-rect computation | `.../NotebookActivity.kt` → `computeToolbarExclusionRect()` (~line 2679) |
| Stroke model | `.../data/LiveStroke.kt`, `.../data/StrokePoint.kt` |
| Ink colour chokepoint | `.../core/InkColor.kt` |
| **EPD rules, render model, pen gate** | `docs/drawing-engine.md` |
| **Onyx pen-tool survey (5 devices, all styles + native pens)** | `docs/onyx-pen-tools.md` |
| **Supernote binder design** | `SUPERNOTE_SUPPORT_PLAN.md` |
| Supernote Lua reference client | `~/Downloads/koreader-supernote-eink-v1/plugins/pencil.koplugin/lib/supernote_ink.lua` |
| Upstream Kotlin original (EMR size map lives here) | https://github.com/plateaukao/supernote_draw → `app/src/main/java/com/example/supernotedraw/SupernoteInk.kt` |

---

## 2. Decisions (settled — do not re-litigate without saying so)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Multi-module with optional vendor adapters.** `:canvas` core carries zero vendor dependencies. `:canvas-onyx` and `:canvas-supernote` are opt-in. | A phone-only app must not inherit the BOOX SDK's native libs or be forced to add the insecure `http://repo.boox.com` maven repo. |
| D2 | **Namespace `com.symmetricalpalmtree.sprout.canvas`**, with `.onyx` / `.supernote` sub-namespaces. Maven group `com.symmetricalpalmtree.sprout`, artifacts `canvas`, `canvas-onyx`, `canvas-supernote`. | User-specified. |
| D3 | **View-based API.** `SproutCanvasView : android.view.View`. Compose interop via `AndroidView`, documented with a working sample, **no Compose dependency in the library**. | Works in any layout system; avoids Compose recomposition/refresh pitfalls on e-ink. |
| D4 | **minSdk 29 / compileSdk 35 / JDK 17 / Kotlin 2.2.x / AGP 8.11.x.** | Matches Notesprout. API 29 is the floor for the hardware `RenderNode` committed-content model, which avoids a second software render path. |
| D5 | **Initial tool scope: pen + eraser.** All standardized pen kinds, widths, colours, full pressure/tilt capture, and erase (including the stylus barrel button). No lasso, no selection, no undo. | Barrel button reports as an eraser on every platform whether we ask or not; shipping without erase would feel broken. |
| D6 | **Supernote comes early — immediately after Onyx** (Phase 5, right after Phase 4). Until then Supernote devices run the generic engine (functional, just e-ink-laggy). | User-specified. Keeps a working baseline at every commit while front-loading the platform work. |
| D7 | **Distribution: local / private, indefinitely.** `mavenLocal()` + a documented `includeBuild` composite recipe. No public registry. | sprout-canvas is infrastructure for your own apps first. Publishing remains possible at any later date and nothing in the build depends on the choice. |
| D8 | **License: MIT.** | Matches Notesprout; permissive and frictionless. Apache-2.0's patent grant was weighed and judged unnecessary here. |
| D9 | **Test devices available:** BOOX fleet (multiple panels), Supernote Nomad/Manta, generic Android stylus tablet. | All three platforms can be validated in their own phase. |
| D10 | **Adapter discovery by reflection on a fixed factory FQCN list**, plus a public manual-registration escape hatch. Each adapter ships its own `consumer-rules.pro` keep rule. | Simplest and most debuggable; AGP applies an AAR's consumer rules automatically, so R8 cannot strip the factory. |
| D11 | **The host must call `SproutCanvas.initialize(application)`** in `Application.onCreate`. If it is missing, the library logs a clear error and falls back to the generic engine — it never crashes, and never silently loses the hardware path. | The vendor SDKs need a process-wide hidden-API exemption (Onyx) and hidden `ServiceManager` access (Supernote). A library must not install that behind the host's back. |
| D12 | **Nine standardized pens** — `MARKER` and `HIGHLIGHTER` are separate tools. | BOOX's MARKER is a thin even-width pen; Supernote's MARK is a true highlighter. One name meaning two different things depending on the device is exactly the vendor confusion standardization exists to remove. |
| D13 | **Render regression: geometry always, goldens as a deliberate suite.** Geometry assertions run on every build; golden-image comparison is a separate suite run on demand and before every release. | Real pixel protection without wedging routine builds on emulator/Robolectric rendering variance. |
| D14 | **The harness is a real installable — "Sprout Canvas Lab"** (`:lab`, applicationId `com.symmetricalpalmtree.sprout.canvas.lab`, `.dev` debug suffix). | It is the durable regression instrument, will live on the device fleet for years, and must be findable in a launcher next to Notesprout rather than being one more unlabelled test APK. |
| D15 | **Supernote EMR mapping: upstream first, then device tuning.** Read the table out of `plateaukao/supernote_draw`, then validate and adjust it on the Nomad. | Cites a real source instead of guessing, and still verifies on the hardware that matters. |

---

## 3. Architecture

### 3.1 Repository & module layout

```
sprout-canvas/                        ← git root, Gradle root
├── PLAN.md                           ← this file
├── README.md
├── LICENSE
├── .gitignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml            ← version catalog; single source of dependency versions
│   └── wrapper/
├── canvas/                           ← :canvas          com.symmetricalpalmtree.sprout.canvas
├── canvas-onyx/                      ← :canvas-onyx     …sprout.canvas.onyx
├── canvas-supernote/                 ← :canvas-supernote …sprout.canvas.supernote
├── lab/                              ← :lab             …sprout.canvas.lab      ("Sprout Canvas Lab" — conformance harness)
└── docs/                             ← long-form docs split out of README as they grow
```

**Why `:canvas` and not `:sprout-canvas`** — the Maven coordinate is
`com.symmetricalpalmtree.sprout:canvas`, which already reads as "sprout canvas". Gradle project names
mirror the artifact names.

### 3.2 Consumer integration

```kotlin
// settings.gradle.kts — ONLY needed if using the Onyx adapter
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("http://repo.boox.com/repository/maven-public/"); isAllowInsecureProtocol = true }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.symmetricalpalmtree.sprout:canvas:1.0.0")            // always
    implementation("com.symmetricalpalmtree.sprout:canvas-onyx:1.0.0")       // BOOX support
    implementation("com.symmetricalpalmtree.sprout:canvas-supernote:1.0.0")  // Supernote support
}
```

```xml
<com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
    android:id="@+id/canvas"
    android:layout_width="match_parent"
    android:layout_height="400dp" />
```

```kotlin
// Application.onCreate — REQUIRED for the Onyx and Supernote hardware paths (D11).
// Omit it and the library logs an error and runs the generic engine; it never crashes.
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SproutCanvas.initialize(this)
    }
}

// Anywhere — identical on every device.
canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 2f, color = Color.BLACK)
canvas.addExclusionZone(binding.floatingToolbar)   // never written under, on any platform
canvas.listener = object : SproutCanvasListener {
    override fun onStrokeCompleted(stroke: InkStroke) { myRepo.save(stroke) }
}
canvas.setStrokes(myRepo.load())                   // anything given is rendered
```

The canvas code above is **identical on every device**. That is G1. `initialize` is the one piece of
host cooperation the library asks for, and it asks explicitly rather than installing a process-wide
hidden-API exemption silently.

### 3.3 The layering

```
                ┌──────────────────────────────────────────────────┐
   Host app ──▶ │  SproutCanvasView            (public API, G1)    │
                │   • public surface: tool, colour, strokes,        │
                │     exclusion zones, listener, capabilities       │
                │   • owns COMMITTED content + its render pipeline  │
                │   • owns exclusion-zone tracking & bounds         │
                │   • owns lifecycle + the pen-activity gate        │
                └───────────────┬──────────────────────────────────┘
                                │  InkEngineHost  (engine → view callbacks)
                                ▼
                ┌──────────────────────────────────────────────────┐
                │  InkEngine       (SPI — one per platform)        │
                │   • input capture      • LIVE-ink display        │
                │   • bounds + exclusion enforcement at the source │
                └───┬──────────────────┬───────────────────┬───────┘
                    │                  │                   │
        GenericInkEngine       OnyxInkEngine      SupernoteInkEngine
        (MotionEvent,          (TouchHelper +     (MotionEvent +
         software live ink)     EPD overlay)       firmware overlay)
             :canvas            :canvas-onyx      :canvas-supernote
```

**The split that matters (G2).** The *view* always owns committed content and always renders it. The
*engine* owns live ink. On e-ink the engine hands live ink to the hardware and the view's committed
layer catches up on pen-up; on a generic tablet the engine draws live ink through the same Canvas.
This is exactly Notesprout's model, generalized behind an interface.

### 3.4 Engine SPI

```kotlin
package com.symmetricalpalmtree.sprout.canvas.engine

interface InkEngineFactory {
    val info: EngineInfo                      // id, display name, priority
    fun isSupported(context: Context): Boolean
    fun create(host: InkEngineHost): InkEngine
}

interface InkEngine {
    val info: EngineInfo
    val capabilities: CanvasCapabilities

    fun attach(view: View)
    fun detach()

    fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point)
    fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>)

    fun setTool(tool: ToolSpec)
    fun setEraser(eraser: EraserSpec?)          // null = pen mode

    /** True while the stylus is on the glass, plus a tail. See §5.3. */
    val isPenActive: Boolean

    // Lifecycle — hardware pipelines are process-global on e-ink. See §5.2.
    fun resume()
    fun pause()
    fun releaseForHandoff()
    fun releaseLiveInk()                        // "another surface needs the panel now"
    fun onCommittedContentChanged(reason: RepaintReason)

    /** Host-supplied MotionEvents, for engines that capture that way. */
    fun onTouchEvent(event: MotionEvent): Boolean
}

interface InkEngineHost {
    val context: Context
    fun onStrokeBegan(seed: StrokeSeed)
    fun onStrokeSamples(strokeId: String, samples: StrokeSamples)  // may arrive batched or streamed
    fun onStrokeEnded(strokeId: String)
    fun onEraseAt(path: List<PointF>, radiusPx: Float)
    fun onPenActiveChanged(active: Boolean)
    fun requestInvalidate()
    fun requestCommittedRepaint(region: Rect?)
}
```

**Engine selection order** (`EngineRegistry`):

1. An explicit override, if set — `SproutCanvasView.enginePreference` or the `app:sproutEngine` XML
   attribute. **Required for the harness**, so a BOOX can be forced onto the generic engine for
   side-by-side comparison.
2. Registered factories by descending `priority`, first whose `isSupported(context)` is true.
3. `GenericInkEngine` — always last, always supported.

`isSupported` must **probe, not guess**:

| Engine | Probe |
|---|---|
| Onyx | `Build.MANUFACTURER` contains `"onyx"` **and** the SDK classes resolve |
| Supernote | brand/manufacturer ≈ `ratta` as a cheap pre-filter, **then** `ServiceManager.getService("service_myservice" \| "service.myservice") != null` |
| Generic | always `true` |

The Supernote binder probe is the authority, matching the reference Lua's self-detection philosophy:
a Supernote whose firmware lacks the service safely lands on generic.

### 3.5 Data model (G3)

Located in `com.symmetricalpalmtree.sprout.canvas.model`.

**Samples are columnar, not per-point objects.** A stroke can carry thousands of samples across ten
channels; an array-of-structs would allocate one object per point per channel. Struct-of-arrays keeps
capture allocation-light and makes it trivial for the app to bulk-copy or serialize.

```kotlin
/** Bitmask of which channels this stroke actually carries. Absent ⇒ the device did not report it. */
object InkChannel {
    const val PRESSURE      = 1 shl 0
    const val TILT          = 1 shl 1   // tiltX/tiltY, device-native units
    const val ORIENTATION   = 1 shl 2   // radians, Android AXIS_ORIENTATION semantics
    const val ALTITUDE      = 1 shl 3   // radians from the surface, AXIS_TILT semantics
    const val SIZE          = 1 shl 4   // contact size
    const val TIMESTAMP     = 1 shl 5
}

class StrokeSamples(
    val count: Int,
    val x: FloatArray,                 // canvas coordinates, px, origin = canvas top-left
    val y: FloatArray,
    val channels: Int,
    val pressure: FloatArray?  = null, // NORMALIZED 0..1 against the device max
    val tiltX: FloatArray?     = null, // RAW device units — see the tilt warning below
    val tiltY: FloatArray?     = null,
    val orientation: FloatArray? = null,
    val altitude: FloatArray?  = null,
    val size: FloatArray?      = null,
    val timestampMs: LongArray? = null,
) {
    operator fun get(index: Int): InkPoint     // convenience cursor; allocates only on demand
}

data class InkStroke(
    val id: String,
    val samples: StrokeSamples,
    val tool: ToolSpec,                // which pen, what width, what colour
    val capture: CaptureInfo,          // engine id, device calibration, start/end wall clock
    val bounds: RectF,                 // derived at construction; O(1) hit-test pre-filter
)

data class DeviceCalibration(
    val maxPressure: Float,            // read at runtime — 4095 vs 4096 differ across BOOX models
    val pressureIsNormalized: Boolean,
    val tiltUnitsKnown: Boolean,       // false on every Onyx device today — see below
    val digitizerWidth: Int, val digitizerHeight: Int,
    val densityDpi: Int,
)
```

> **⚠ Tilt is not normalized, and must not pretend to be.** The Notesprout five-device survey found
> `tiltX` on the BOOX G6 reported in the **thousands** (a 2625-unit span *within a single stroke*)
> while NA5C, G102, MAX and P2P all reported roughly ±60. There is no `getMaxTilt()` anywhere in the
> Onyx SDK to normalize against. So sprout-canvas reports vendor tilt **raw**, sets
> `tiltUnitsKnown = false`, and — where Android itself provides the properly-defined
> `AXIS_TILT`/`AXIS_ORIENTATION` (radians) — reports those separately as `altitude`/`orientation`.
> Inventing a normalization here would be a lie that silently corrupts every app that trusts it.

### 3.6 Standardized tool vocabulary (G5)

The names below mean **what they say**, which is deliberately *not* what the vendors' constants say.
The single biggest naming trap: **Onyx's `STROKE_STYLE_PENCIL = 0` is a plain even line** — BOOX's own
UI calls it "Pen" — and BOOX's grainy "Pencil" is internally *charcoal*. BOOX's "Ballpoint" is
internally *oily pen*. sprout-canvas fixes this at the boundary.

#### Pens (`SproutPen`)

| `SproutPen` | What the user sees | Onyx overlay (live, path A) | Onyx software (committed, path B) | Supernote firmware | Generic software |
|---|---|---|---|---|---|
| `BALLPOINT` | Even-width opaque line — **the default** | `STROKE_STYLE_PENCIL` = 0 | `NEOPEN_PEN_TYPE_BALLPOINT` = 8 | `NEEDLE` = 10 | Polyline, round cap |
| `FOUNTAIN` | Pressure-responsive, thin→thick | `STROKE_STYLE_FOUNTAIN` = 1 | `NEOPEN_PEN_TYPE_FOUNTAIN_V2` = 6 | `INK` = 16 | Pressure-width ribbon |
| `BRUSH` | Heavier pressure-responsive | `STROKE_STYLE_NEO_BRUSH` = 3 | `NEOPEN_PEN_TYPE_BRUSH` = 1 | `INK` = 16 | Pressure-width, ×2 scale |
| `MARKER` | Flat even-width **opaque** pen | `STROKE_STYLE_MARKER` = 2 | `NEOPEN_PEN_TYPE_MARKER` = 3 | `NEEDLE` = 10 | Flat, opaque |
| `HIGHLIGHTER` | **Wide translucent wash** | `STROKE_STYLE_MARKER` = 2 *(wide + alpha)* | `NEOPEN_PEN_TYPE_MARKER` = 3 *(wide + alpha)* | `MARK` = 11 | Flat, wide, alpha |
| `PENCIL` | Grainy graphite | `STROKE_STYLE_CHARCOAL` = 4 | `NEOPEN_PEN_TYPE_PENCIL` = 7 | `NEEDLE` = 10 *(no grain)* | Stamped grain |
| `CHARCOAL` | Heavy grain | `STROKE_STYLE_CHARCOAL_V2` = 6 | `NEOPEN_PEN_TYPE_CHARCOAL_V2` = 5 | `NEEDLE` = 10 *(no grain)* | Stamped grain, heavy |
| `CALLIGRAPHY` | 45° chisel nib | `STROKE_STYLE_SQUARE_PEN` = 7 | `NEOPEN_PEN_TYPE_SQUARE` = 9 | `CALLIGRAPHY` = 15 | Chisel nib |
| `DASHED` | Dashed line | `STROKE_STYLE_DASH` = 5 | — *(software dash)* | — *(software dash)* | `DashPathEffect` |

**Every pen renders something on every engine.** Where a platform has no native equivalent the
software renderer covers it, and `CanvasCapabilities.fidelity(pen)` reports honestly
(`NATIVE` / `EMULATED` / `APPROXIMATE`) so an app can grey out or annotate a picker. What must never
happen is a silent no-op — see §5.5.

`MARKER` and `HIGHLIGHTER` are the clearest illustration of why the standardization exists. On
Supernote they are genuinely different firmware pens (`NEEDLE` vs `MARK`); on Onyx they are the same
firmware style differentiated by width and alpha, so `HIGHLIGHTER` reports `EMULATED` there and
`NATIVE` on Supernote. **Open device question for Phase 4:** whether the Onyx firmware overlay
honours alpha at all in live preview. If it does not, the highlighter's live stroke will read as
opaque and only become translucent on commit — the same shape of problem as the Kaleido colour floor
below, and it must be surfaced through `capabilities`, not hidden.

#### Eraser (`EraserMode`)

| `EraserMode` | Meaning | v1 | Onyx | Supernote | Generic |
|---|---|---|---|---|---|
| `STROKE` | Contact removes whole strokes it touches | ✅ | software hit-test off the raw-erase callbacks | software hit-test off MotionEvent | software hit-test |
| `AREA` | A rectangle/region removes strokes | ✖ declared, unsupported | later | `setEraser(rectangular = true)` | later |
| `PIXEL` | Partial / segment erase | ✖ declared, unsupported | later | later | later |

`EraserSpec(mode, widthDp)`. The **stylus barrel button** always engages `STROKE` erase regardless of
the armed tool, on every engine — see §5.4.

#### Width and colour

- **Width is `dp`.** Always. The library converts to each platform's units (Onyx takes a raw float;
  Supernote takes an EMR size int). An app never learns device units. A preset ladder is offered
  (`SproutWidth.HAIRLINE … SproutWidth.XXL`) but arbitrary values are accepted.
- **Colour is `@ColorInt Int` (ARGB).** Alpha honoured where the engine supports it;
  `capabilities.supportsAlpha` reports. **A stroke's stored colour is never rewritten to suit a
  device** — a red stroke on a greyscale panel stays red in the data and merely renders grey.
  (Notesprout's `InkColor` chokepoint principle: data in, device-appropriate pixels out.)
- **Kaleido live-preview floor:** the Onyx overlay paints a colour as black once its dominant RGB
  channel drops below ~180. This is a *live-preview* limitation only — the stroke is captured, stored
  and committed in its true colour. Surfaced as `capabilities.livePreviewColorFloor`, never as a
  reason to refuse a colour.

### 3.7 Bounds and exclusion zones (G6, G7)

**Bounds.** The engine is armed with a limit rect = the view's own bounds ∩ the visible display
frame, expressed in view coordinates and recomputed on every layout/size/scroll change. Nothing is
captured outside it. On Onyx this is `TouchHelper.setLimitRect`; on Supernote it is the inverse of
the disable-area list; on generic it is the view's natural bounds plus a clip.

**Exclusion zones.** Two registration forms:

```kotlin
fun addExclusionZone(view: View, id: String = view.toString())   // auto-tracked
fun addExclusionZone(rect: Rect, id: String)                     // manual
fun removeExclusionZone(id: String)
fun clearExclusionZones()
```

The `View` form is the ergonomic win over Notesprout, which hand-computes a union rect for every
piece of chrome. sprout-canvas attaches an `OnLayoutChangeListener` + visibility observer to each
registered view, maps its bounds into canvas coordinates via `getLocationOnScreen` deltas, coalesces
changes to one push per layout pass, and re-arms the engine.

**Semantics are uniform across engines: no capture inside an exclusion zone, period.** A stroke may
not begin there, and a stroke that wanders in stops. This matches Onyx's hardware limit-rect
behaviour, so the generic and Supernote engines are written to match it rather than the reverse.

Two rules carried over from Notesprout, both of which cost real debugging time there:

- **Never pass an empty exclusion list to Onyx.** The SDK treats an empty list as a no-op and keeps
  whatever zone was previously active (including a zone restored from its own persisted state). Pass
  a single off-screen dummy rect (`Rect(-1,-1,0,0)`) to genuinely clear.
- **Re-arm between strokes, never mid-contact.** Changing the limit rect while the stylus is down
  drops the stroke being written.

**Supernote coordinate note:** the firmware paints in **screen** coordinates while `MotionEvent`
arrives in **view** coordinates. Every disable-area rect must be offset by `getLocationOnScreen`.
A mismatch shows up as a baked stroke visibly *jumping* on pen-lift — that is the tell.

### 3.8 Render model

Committed content is recorded into a hardware `RenderNode` (API 29+, hence D4) and blitted in
`onDraw`. During active writing only `invalidate()` fires; the node is re-recorded **only** when
committed content actually changes (stroke commit, erase, ingest, clear).

```kotlin
override fun onDraw(canvas: Canvas) {
    if (canvas.isHardwareAccelerated && committedNode.hasDisplayList()) canvas.drawRenderNode(committedNode)
    else drawCommittedContent(canvas)          // software fallback — REQUIRED, see below
    engine.drawLiveInk(canvas)                 // no-op on hardware-overlay engines
}
```

**The software branch is not optional.** A `RenderNode` can only be drawn onto a hardware canvas, and
Onyx's `EpdController.handwritingRepaint` re-draws the view *through a software canvas* to capture
the panel. Without the fallback branch, every EPD repaint would come back blank.

Per-pen rendering goes through a `StrokeRenderer` registry, one implementation per `SproutPen`. The
Onyx adapter may substitute SDK `NeoPen*` renderers for closer path-A/path-B agreement.

> **The two-path agreement problem.** On e-ink, the user sees the *firmware's* stroke while writing
> and *our* stroke forever after. Notesprout's two paths already disagree slightly (firmware style
> vs. a flat polyline). Keeping them in agreement is a standing requirement of every rendering change
> here, and is an explicit acceptance criterion in Phases 4 and 5.

### 3.9 Lifecycle and multi-canvas safety

The library owns its own lifecycle wherever it can: `onAttachedToWindow` / `onDetachedFromWindow` /
`onWindowFocusChanged`. Explicit `resume()` / `pause()` / `releaseForHandoff()` remain as escape
hatches for hosts whose navigation does not line up with those.

> **Corrected in Phase 1:** this section originally also named `findViewTreeLifecycleOwner()`. That
> lives in `androidx.lifecycle:lifecycle-runtime`, which `:canvas` does not depend on and must not —
> the zero-dependency rule is a decision, not an oversight. The three `View` callbacks cover every
> case the library can observe on its own, and the explicit hooks cover the rest.

**The process-global hazard.** On BOOX the raw-drawing pipeline is a single process-global hardware
resource, and Android runs an incoming screen's open *before* the outgoing screen's close — so a late
teardown silently kills the live canvas. sprout-canvas carries the ownership guard **inside the
adapter**, where Notesprout had to hand-thread it through five Activities. This is one of the
strongest reasons this library should exist at all.

---

## 4. Testing strategy

Three tiers. Everything that can be tested without a device, is.

### 4.1 JVM tests — `src/test/`, no device, run on every build

JUnit 4 + Robolectric.

| Area | What is asserted |
|---|---|
| Model | `StrokeSamples` channel packing/round-trip, `count`/array-length invariants, bounds derivation, `InkPoint` cursor correctness |
| Normalization | pressure normalization against a fake `DeviceCalibration`; **tilt is passed through untouched** |
| Tool mapping | Table-driven, **exhaustive over the `SproutPen` enum** — every pen maps to every platform, no gaps. These tables rot silently; this is the test that catches it. |
| Engine selection | `EngineRegistry` order, priority, explicit override, generic-always-last, with fake probes |
| Exclusion geometry | view→canvas coordinate mapping, union/coalescing, screen-offset math, empty-list handling |
| Bounds | limit-rect intersection with visible frame, clamping, zero-size and rotated cases |
| View behaviour (Robolectric) | layout, exclusion auto-tracking on a fake view tree, ingest→render-invalidation, listener dispatch ordering |
| Renderer **geometry** | Per `StrokeRenderer`: solved path/point geometry, per-sample widths, taper curves, nib angle, dash cadence, decimation. Fast, deterministic, **runs on every build**. |

#### 4.1.1 The golden-image suite (D13)

Pixel comparison is real protection — a renderer can regress visually (wrong paint style, wrong
alpha, wrong cap) while every geometry assertion still passes. But cross-environment rendering
variance makes goldens a poor fit for a suite that must be green on every build.

So goldens live in their own **tagged, on-demand suite** (`./gradlew goldenTest`), run before every
release and whenever a renderer changes — never as a gate on routine builds. Each golden carries the
environment it was generated in, and regeneration is a single deliberate command.

**Decided empirically in Phase 2: Robolectric, in `NATIVE` graphics mode** (R1, §10.4). Both tiers
render the same scenes from one shared definition, and on a Wacom Movink Pad 11 sixteen of the
eighteen scenes that existed then came back byte-identical to the JVM — the other two differing by
one unit on one channel. The instrumented tier renders the identical scenes and remains as an
on-demand cross-check, because either environment can move underneath the other. Written up in
`docs/golden-tier.md`.

Phase 3 grew the suite to **33 scenes**. Two of the additions are worth knowing about when adding
more:

- **A scene holds a *list* of strokes.** The interesting failures in a drawing library are
  compositing failures, and one stroke on white cannot express one. The suite shipped for two phases
  with a scene called `highlighter-over-ink` that drew a highlighter onto a blank bitmap.
- **Width ladders, off the real `SproutWidth` rungs.** A texture pen's appearance is a function of
  its width in *both* directions (§5.13), so a suite rendering every pen at exactly one width is
  blind to the ends of the range where the interesting things happen.

### 4.2 Instrumented tests — `src/androidTest/`, real device or emulator

AndroidX Test + Espresso. Stylus input is **synthesized** via `MotionEvent.obtain` with
`PointerProperties.toolType = TOOL_TYPE_STYLUS` and populated pressure / tilt / orientation axes.

| Area | What is asserted |
|---|---|
| Generic engine end-to-end | down/move/up → one stroke, correct sample count including historical points |
| Channel capture | injected pressure/tilt/orientation/size survive into `InkStroke.samples` |
| Eraser | `TOOL_TYPE_ERASER` and `BUTTON_STYLUS_PRIMARY` both erase; barrel button erases regardless of armed tool |
| Exclusion | a stylus-down inside a zone produces no stroke; a stroke crossing in stops at the boundary |
| Bounds | input outside the view's rect never reaches the canvas |
| Ingest | `setStrokes(captured)` re-renders pixel-identically to the original (G4) |
| Resize | content survives size change, rotation, and re-layout (G8) |
| Multi-canvas | two `SproutCanvasView`s in one Activity behave; ownership handoff is clean |

These run on the emulator too, so the generic path has genuine CI-able coverage.

### 4.3 On-device conformance harness — `:lab` ("Sprout Canvas Lab")

**Sprout Canvas Lab** is a **conformance harness that happens to be a demo**, not the reverse. It is
how every phase gets device-tested and how future features get regression-tested. It ships as a real
installable (`com.symmetricalpalmtree.sprout.canvas.lab`, `.dev` debug suffix, proper label and icon)
because it will live on the device fleet for years alongside Notesprout, and an anonymous "sample"
APK is not findable on a shared BOOX six months from now.

| Screen | Purpose |
|---|---|
| **Canvas** | The plain drawing surface. Resizable live (preset sizes + drag handle) to prove G8, and embeddable in a scrolling parent to prove it is a component. |
| **Tools** | Every standardized pen, width and colour, switchable mid-session. Shows the reported `fidelity` per pen for the active engine. |
| **Overlays** | Floating toolbars, popups, side panels and a bottom bar, each independently toggleable — the direct visual proof of G7 on each platform. |
| **Data** | Dump captured strokes as JSON to a file/logcat; re-ingest them into a cleared canvas. Round-trip fidelity (G3 + G4) becomes a two-tap check. |
| **Device report** | Selected engine, capabilities, `maxPressure`, observed tilt range, digitizer size, density. This is the diagnostic the Onyx survey needed and did not have. |
| **Conformance run** | A scripted ordered checklist. Each item is `PASS` / `FAIL` (auto-asserted) or `CONFIRM` (tester answers a yes/no about something only eyes can judge). Exports a timestamped report file per device. |

> **⚠ The screenshot trap.** `adb shell screencap` **cannot capture the e-ink firmware ink overlay** —
> it is painted by firmware straight to the panel and is not in the Android framebuffer. Every
> live-ink check is therefore a `CONFIRM` item answered by the tester. Committed content *is*
> capturable, so everything after pen-up can be automated. Design every check with this line in mind.

### 4.4 Regression discipline

- The conformance report from each device is committed under `docs/conformance/<device>-<date>.md`,
  so a future change has a baseline to diff against.
- Every bug fixed on hardware gets a JVM or instrumented test if one is possible, and a new
  conformance item if it is not.
- Tool-mapping tables and capability tables are exhaustively tested — adding a `SproutPen` without
  mapping it on every platform must fail the build.

---

## 5. Hard-won lessons (from Notesprout — do not rediscover these)

Everything in this section cost real debugging time in the reference project. Each is a design
constraint here, not a tip.

### 5.1 E-ink rules (Onyx)

- **First-stroke lag.** The first stroke after opening a surface used to lag 1–2 s. The *sole* fix,
  proven by a device sweep of every EPD mode, is
  `EpdController.applyAppScopeUpdate(scope, true, false, UpdateMode.HAND_WRITING_REPAINT_MODE, 0)`
  applied when the pen pipeline opens and cleared when it is relinquished. Scribble mode, view mode
  and system-fast all still lagged.
- **`setRawDrawingRenderEnabled(false)` does not clear the hardware buffer.** It is a lightweight
  toggle. Any handoff that changes what is on screen (clear, ingest, template change, erase-complete)
  must follow with `EpdController.handwritingRepaint(view, rect)` or the user sees grey residue and a
  black flash.
- **Never call `handwritingRepaint` during move events** — one full-panel flash per event. On gesture
  end only.
- **`EpdController.setUpdListSize(512)`** at pipeline open suppresses mid-session GC16 refresh.
- **Eraser must release the overlay first.** On eraser start, `setRawDrawingRenderEnabled(false)` +
  `invalidate()` *before* any erase logic, or the overlay hides the result and phantom strokes remain
  visible.
- **Tool-state invariants** — get these wrong and phantom strokes appear that look real and vanish on
  the next refresh:

  | Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
  |---|---|---|
  | Pen | `true` | `true` (SDK manages) |
  | Eraser | `true` | `false` |
  | Any non-drawing mode | `false` | n/a |

- **`setStrokeColor` must be set explicitly at init and re-asserted after any restart** — some panels
  do not default to black.
- **`setStrokeStyle` needs no session restart** and survives the pinned fast-mode waveform; it takes
  effect on the very next stroke. Verified on five devices.
- **A single pen-down→pen-up can produce more than one point-list callback.** One callback per stroke
  is not guaranteed; the engine must accumulate.

### 5.2 The process-global pipeline

Only one surface in the process can own the BOOX raw-drawing pipeline. Android opens the incoming
surface before closing the outgoing one, so a naive close in teardown kills the live canvas —
intermittently, because the open is async and the close is synchronous. **Guard:** a volatile
process-global owner reference; every close is a *close-if-owner*. This lives inside the adapter and
must never be pushed onto the host app.

### 5.3 The pen-activity gate

On e-ink, stylus ink bypasses `MotionEvent` entirely — but a **palm resting on the glass still
produces MotionEvents**. Without a gate, a palm roll mid-word registers as a tap/swipe/double-tap in
the host app, whose handler then reaches into the live pen session and drops the stroke. One cause,
two symptoms: strokes intermittently not registering, and phantom double-taps.

`isPenActive` = stylus down, **plus a ~350 ms tail** after lift. The tail is deliberately longer than
the platform double-tap window (~300 ms) so the second half of a palm-induced "double tap" cannot
land just after the pen leaves the glass. This is exposed **publicly** — host apps building toolbars
need it, and it is exactly the sort of thing a library should provide rather than make every app
rediscover. Tracked from both directions: the SDK's begin/end callbacks *and* stylus MotionEvents,
because in modes where raw drawing is disabled the SDK is silent and the stylus arrives as an
ordinary event.

### 5.4 The stylus barrel button

The BOOX barrel button is reported as `TOOL_TYPE_ERASER`, **not** `BUTTON_STYLUS_PRIMARY`. When the
SDK's raw pipeline is active it intercepts the button at the hardware level and fires the erase
callbacks; when raw drawing is disabled the event arrives only through `onTouchEvent`. Both paths
must be handled, and both `TOOL_TYPE_ERASER` and `BUTTON_STYLUS_PRIMARY` checked, so erase-on-button
is consistent regardless of the armed tool.

### 5.5 Vendor SDKs fail silently

`TouchHelper.setStrokeStyle(int)` is a pass-through to a reflected hidden framework method, and the
reflection helper **swallows failures**: no exception, no return value, no log. Nothing in the SDK
validates the value, and there is no `isStrokeStyleSupported()` anywhere. `BaseDevice`'s
implementation is an empty method, so on a non-Onyx device the whole call silently disappears.

**Consequence for us:** capability must be established empirically and recorded, never assumed. The
good news, from the five-device survey: **all 9 overlay styles render on all 5 BOOX devices tested,
and 13 of 13 software renderers work — zero silent failures observed anywhere.** Styles 9–15 are
ignored; the style space really is closed at nine.

### 5.6 Read these at runtime, never hardcode

| Value | Variation observed | Why it matters |
|---|---|---|
| `EpdController.getMaxTouchPressure()` | **4095 or 4096** across BOOX models | It is the divisor for every pressure normalization and every `NeoPen` config |
| Tilt scale | G6 reports ~100× the other four devices | No `getMaxTilt()` exists; see the warning in §3.5 |
| `colorType` | `0`, `1`, and **`1017`** | **Not a boolean.** Anything gating on `== 1` misreads the Palma2 Pro. Opaque identifier only. |
| Digitizer resolution | 7239×5359 → 27040×20280 | Differs from screen resolution; sample density varies ~26× with stroke speed |

### 5.7 Onyx SDK build facts

- `android.enableJetifier=true` — the Onyx SDK bundles old support classes.
- `jniLibs.pickFirsts` for `libc++_shared.so` — the SDK conflicts with other deps.
- `abiFilters += "arm64-v8a"` — every target device is 64-bit ARM, and this drops the one
  4 KB-aligned native lib that fails Play's 16 KB page-size check.
- Do **not** exclude `com.tencent:mmkv` — `onyxsdk-base` references it; removing it risks
  `NoClassDefFoundError`.
- `HiddenApiBypass.addHiddenApiExemptions("")` must run before any SDK init. **In a library this is a
  problem**, which is why the host is required to call `SproutCanvas.initialize(application)` — D11.
- `ResManager.init(applicationContext)` is mandatory before any bitmap-backed pen (pencil, charcoal).
  Nothing in `TouchHelper` or `NeoPenNative` does it. The failure mode is nasty: the pencil first
  renders **solid and grainless with no error at all**, and only throws later.
- Texture pens need **large widths** or their grain has no room to exist (solid at width 8, proper
  grain at 32). BOOX multiplies nominal width per pen kind — `CHARCOAL × 5.0`, `BRUSH × 2.0`. Those
  multipliers are not cosmetic.
- The one-call `*Wrapper.drawStroke()` helpers **build and destroy a native pen on every call**. Use
  them for one-off rendering; hold a `NeoPen` in a `NeoPenRender` for repainting, remembering that
  width is baked in at create time so the cache invalidates on width change.

### 5.8 Supernote specifics

- The firmware paints stroke pixels to the EPDC overlay at sub-frame latency but **gives back no
  point data.** Points come from the ordinary Android input stream. So the Supernote engine is the
  *generic* engine's capture model with the *Onyx* engine's live-ink handoff.
- Binder: service `service_myservice` (legacy alias `service.myservice`), interface token
  `android.demo.IMyService`. Every transaction writes `writeInterfaceToken(token)` +
  `writeString(appName)` then a small int payload. `tx=0` claim ownership, `tx=1` disable areas,
  `tx=2` pen/eraser, `tx=6` clear the ink overlay.
- `enableFullUiAuto(true)` via reflection on `getSystemService("eink")` is **required** for a
  third-party app to get ink painted everywhere rather than only in whitelisted firmware apps. Some
  firmwares do not expose the method — guard and degrade to generic.
- Handle `DeadObjectException`: the firmware service can restart, invalidating a cached binder proxy.
  Re-look-up once, then mark unavailable.
- Pen codes are confirmed for **Nomad (deviceType 3 / A5X2)**. **Manta shares the firmware and base
  chipset — only the screen size differs**, so Nomad results are expected to carry over unchanged.
- The **EMR size → stroke-width mapping is missing from the Lua reference** and must come from the
  upstream `supernote_draw` repo or on-device tuning.

### 5.9 Deploying to BOOX devices

**A freshly sideloaded APK can land *disabled* on BOOX.** Observed on the G10 (Go 10.3, Android 12)
during Phase 0: `adb install` reported `Success`, the package appeared in `pm list packages`, and the
manifest's `MAIN`/`LAUNCHER` filter was correctly registered in the Activity Resolver Table — yet
`am start` failed with:

```
Error type 3
Error: Activity class {…/….LabActivity} does not exist.
```

`dumpsys package <id>` gives the real story:

```
User 0: … stopped=true notLaunched=true enabled=3 …
lastDisabledCaller: com.onyx
```

`enabled=3` is `COMPONENT_ENABLED_STATE_DISABLED_USER`, and the caller is Onyx's own system
software — the BOOX app-management layer disabled the package on install. The fix is one command:

```sh
adb -s <serial> shell pm enable <applicationId>
```

**⚠ `pm enable` can lose a race with Onyx, and does.** Observed on the **NA5C**, Phase 1: `pm enable`
reported `new state: enabled`, the first `am start` succeeded — and a later `am start` failed with
`Error type 3` again, with `dumpsys` showing `enabled=3` and `lastDisabledCaller: com.onyx` a second
time. Onyx re-disabled the package *after* it had been enabled. Running the two as separate `adb`
invocations leaves a window for that; issuing them in **one device-side shell** closes it:

```sh
adb -s <serial> shell 'pm enable <applicationId> && am start -n <applicationId>/<activity>'
```

So the rule is not "enable once after install" but "enable immediately before every launch". Treat a
BOOX launch failure as a package-state question even when you already enabled the package.

**Why this matters beyond the annoyance:** the error message points at the *activity class*, so the
natural reading is a manifest, namespace or `applicationIdSuffix` mistake — and the natural response
is to go rewrite build config that was never wrong. Two checks settle it in seconds:
`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <id>`
(returns `No activity found` when the package is disabled, the resolved component when it is not),
and the `enabled=` field in `dumpsys package`.

Fold `pm enable` into every BOOX install step, and treat "activity does not exist" on a BOOX as a
package-state question before it is a code question.

### 5.10 The Android view system, when a canvas lives inside it

Learned in Phase 2, all four on hardware, all four now covered by a test. Each one presents as a
canvas that is subtly wrong rather than as anything that looks like a bug.

- **A `GONE` view is never laid out, so its layout listener never fires.** Watching each registered
  overlay's own layout therefore misses the moment it is dismissed, and the canvas keeps refusing to
  capture in a region where there is now nothing at all. Watch the **view tree**
  (`ViewTreeObserver.OnGlobalLayoutListener`), not the view.
- **`INVISIBLE` does not reach a global layout listener at all.** The documentation for
  `OnGlobalLayoutListener` mentions visibility, which reads as a promise; measured on a Wacom Movink
  Pad (API 34), `GONE` fires it and `INVISIBLE` does not, because an invisible view keeps its space
  and no layout pass is scheduled. Catching it needs a second signal — a pre-draw listener comparing
  each tracked view's `isShown` against the last value seen, which is an int and a short parent walk
  per registered view.
- **Refusing to capture must not mean refusing to receive.** Android stops delivering the rest of a
  gesture to a view that returned false from its `ACTION_DOWN`. Declining a stylus-down — outside
  the bounds, under a toolbar, on a paused canvas — therefore means the `ACTION_UP` never arrives,
  and the pen-activity gate latches open and silently suppresses the host's chrome for the rest of
  the session. The engine consumes every stylus event and decides separately whether to capture it.
- **A canvas inside a scrolling parent loses its strokes unless it claims the gesture.** A
  `ScrollView` takes a gesture over once it has moved far enough and sends the child an
  `ACTION_CANCEL`, which is most strokes. `requestDisallowInterceptTouchEvent(true)` for the
  duration — **only** for gestures the engine consumed, so a finger still scrolls the page while the
  pen draws on it.

### 5.11 Capability probing

- **Never let one stroke retract a capability.** Synthesized input (`adb shell input stylus`) comes
  from a *virtual* device that declares no motion axes, and so does a knuckle on the glass or a
  capacitive stylus on a tablet that also has an EMR pen. Adopting whatever last wrote means a
  pressure-sensitive tablet permanently reporting `TIMESTAMP`-only, with every pressure pen greyed
  out in the host's picker and no way back. Capabilities describe *the device* and only ever grow; a
  stroke carries its own channels and its own calibration, so nothing is misrepresented.
- **`adb shell input stylus` works, and is worth knowing about.** It produces genuine
  `TOOL_TYPE_STYLUS` events, so most of a device protocol can be scripted rather than tapped out by
  hand — nine pens, exclusion zones and resize were all verified that way. Just remember the events
  carry no pressure, so pressure-driven behaviour still needs the actual pen.

### 5.12 Build and test environment

Not device quirks, but they cost time in the same way — each one presents as a mysterious failure in
code that is actually fine.

- **Gradle 8.14 cannot run on JDK 26.** Observed in Phase 1 when the machine's default `java` moved
  to Temurin 26: every task fails before compiling anything, with the uniquely unhelpful message
  `* What went wrong: 26` — an `IllegalArgumentException` from `JavaVersion.parse` inside the Kotlin
  DSL script compiler. It looks like a corrupt build script; it is a JVM the toolchain has never
  heard of. `jvmToolchain(17)` does **not** help: it selects the JDK that compiles the *code*, not
  the one that runs Gradle itself.
  **Fix:** run Gradle on JDK 17 — `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew …`,
  or set `org.gradle.java.home` in **`~/.gradle/gradle.properties`** (machine-local, not committed).
  Never in the repo's own `gradle.properties`: an absolute JDK path there works on exactly one
  machine.
- **Robolectric runs a real layout pass, and it wins.** A hand-rolled `view.measure(...)` +
  `view.layout(...)` on a view inside a parent is silently overwritten when the framework lays the
  hierarchy out on the next idle — the view ends up at the parent's size, not the requested one.
  Tests that assert on `width`/`height`, bounds or exclusion geometry must set real `LayoutParams`
  and let the pass apply them. The symptom is an assertion failure quoting the *screen* size, which
  reads like a bug in the geometry code rather than in the fixture.
- **Robolectric's default screen is small (320×414).** A canvas larger than it is genuinely only
  partly visible, and the limit rect correctly clips to the visible frame. Test fixtures should sit
  inside the screen unless the clipping itself is what is under test.

### 5.13 A texture pen's appearance is a function of its width, at both ends

Found in Phase 3 by adding width ladders to the golden suite, then confirmed on the Movink Pad with
a real pen (`docs/conformance/mip11-2026-08-08-phase3.md`). Neither end is visible if every pen is
only ever rendered at one width, which is how the suite was built in Phase 2.

The nominal-width multipliers (§5.7 — `CHARCOAL ×5`, `BRUSH ×2`) exist because a texture pen drawn
at its nominal width has no room for its grain and comes out solid. That is true, and the multiplier
fixes it in the middle of the ladder. It also creates the opposite problem at the top and does not
reach far enough at the bottom:

- **At `XXL` (12 dp) the grain becomes discrete circles.** Stamp size scales linearly with drawn
  width and nothing caps it, so charcoal at 12 dp × 5 draws 120 px-wide ink whose stamps are large
  enough to count individually. It reads as speckle, not as charcoal. `PenTuning.grainScale` was
  added in Phase 2 for exactly this failure mode and solves it at 3 dp only.
- **At `HAIRLINE` (0.5 dp) pencil and charcoal swap character.** The pencil's ×2 leaves ~2 px of
  drawn width and produces a clean grainless line; charcoal's ×5 leaves ~5 px and still shows fine
  texture. So the coarser pen is the *only* one with grain at the narrow end — backwards, and purely
  arithmetic.

Both are pinned by goldens (`width-pencil-*`, `width-charcoal-*`), so any re-tuning shows up in a
diff. **Not fixed in Phase 3 on purpose** — the software renderer is the only reference that exists
until the vendor paths land, and §7's Phase 3 note is explicit that tuning it against preference
alone means tuning twice. This is the material the Phase 4/5 pass should start from.

Separately, and not reproducible offline: **texture pens lag the pen for the first moment of a
stroke** and then keep up. A golden renders a finished stroke and says nothing about the rate at
which it appeared, so this needs the deferred performance harness (§9). The texture renderer places
far more primitives per unit length than any other, so a first-stroke cost there is plausible.

---

## 6. Cross-cutting conventions

- **Language:** Kotlin, Java 17 target, explicit API mode on for the library modules (`explicitApi()`)
  so nothing leaks into the public surface by accident.
- **Public API is main-thread only**, documented, and asserted in debug builds.
- **No logging via `Log.d` directly** — a `SproutLog` inline wrapper gated on a build flag, so release
  consumers pay nothing. Vendor-facing diagnostics carry a stable tag so device sessions are greppable.
- **Zero required dependencies in `:canvas`** beyond `androidx.annotation`. No coroutines, no
  serialization, no Material — a library should not dictate a host app's stack.
- **Every public type carries KDoc**, including the *why* for anything driven by a device quirk. The
  quirks in §5 belong in the code that implements them, not only here.
- **Commit style:** one phase per commit series, conventional-ish subject lines, phase named in the
  body. Push at the end of each phase after tests pass.

---

## 7. Phases

Each phase: **Goal → Deliverables → Acceptance criteria → Tests → Device protocol → Status**.
Statuses live in the table in §8 and are updated at the end of each phase.

---

### Phase 0 — Foundation & build scaffolding

**Goal.** A green, committed, installable skeleton. No drawing yet.

**Deliverables**
- `.gitignore` — Android/Gradle/IDEA/macOS, keystores, `local.properties`, keeps `gradle-wrapper.jar`
- `LICENSE` — MIT
- `README.md` — first pass: what it is, supported devices, install snippet, minimal usage, status table
- `PLAN.md` — this file, committed
- Gradle root: `settings.gradle.kts` (modules `:canvas`, `:lab`), `build.gradle.kts`,
  `gradle.properties` (JDK 17 pin, AndroidX, jvmargs), `gradle/libs.versions.toml`, wrapper 8.14
- `:canvas` — library module, namespace `com.symmetricalpalmtree.sprout.canvas`, minSdk 29,
  compileSdk 35, `explicitApi()`, `consumer-rules.pro`
- `:lab` — **Sprout Canvas Lab**: application module, applicationId
  `com.symmetricalpalmtree.sprout.canvas.lab`, `.dev` debug suffix, app label and launcher icon,
  launches an empty Activity
- `maven-publish` wired for `publishToMavenLocal`, group `com.symmetricalpalmtree.sprout`, artifact
  `canvas`; plus a documented `includeBuild` composite recipe (D7)
- Test infra: JUnit 4 + Robolectric wired, one trivial passing test per module; the `goldenTest`
  task registered (empty for now) so the split in D13 exists from day one

**Acceptance**
- `./gradlew build test` green from a clean checkout
- **Sprout Canvas Lab** installs, launches, and is findable by name in the launcher on at least one device
- `./gradlew publishToMavenLocal` produces a resolvable `com.symmetricalpalmtree.sprout:canvas`
- Repo has its initial commit and is pushed

**Tests.** JVM smoke tests only.
**Device protocol.** Install + launch on any one device.

---

### Phase 1 — Core model & public API contract

**Goal.** The complete app-facing surface, fully documented and unit-tested, with a stub engine. This
is the contract every later phase implements against — get it right before writing engines.

**Deliverables** (all in `:canvas`)
- `model/` — `InkChannel`, `StrokeSamples`, `InkPoint`, `InkStroke`, `StrokeSeed`, `ToolSpec`,
  `SproutPen` (**nine pens** — §3.6), `SproutWidth`, `EraserSpec`, `EraserMode`, `DeviceCalibration`,
  `CaptureInfo`
- `SproutCanvas` — the library entry object carrying `initialize(application)` (D11), engine
  registration, and the global capability query. Core declares it; adapters consume it in Phases 4–5.
- `engine/` — `InkEngine`, `InkEngineFactory`, `InkEngineHost`, `EngineInfo`, `EngineRegistry`,
  `CanvasCapabilities`, `PenFidelity`, `RepaintReason`
- `tools/` — the standardized ↔ vendor mapping tables from §3.6, declared in core so adapters consume
  rather than redefine them
- `SproutCanvasView` — public surface complete: `tool`, `eraser`, `listener`, `setStrokes`,
  `addStroke`, `removeStrokes`, `clear`, `getStrokes`, exclusion-zone API, `capabilities`,
  `engineInfo`, `isPenActive`, `enginePreference`, lifecycle hooks
- `SproutCanvasListener`
- `attrs.xml` — `app:sproutEngine`, `app:sproutPen`, `app:sproutWidthDp`, `app:sproutColor`
- `NoOpInkEngine` — a stub so the view is exercisable before real engines exist
- KDoc throughout

**Acceptance**
- The API compiles and reads well from `:lab` (write the intended call sites even though nothing
  draws yet)
- Explicit API mode passes with no warnings
- Exhaustive mapping tables — every `SproutPen` has an entry for every platform

**Tests (JVM).** Model invariants and round-trips; bounds derivation; exclusion geometry; engine
selection with fake probes; **exhaustive enum-coverage tests over the mapping and capability tables**.

**Device protocol.** None required. The Lab's device-report screen shows the selected (stub) engine
to prove the wiring, and can be checked on any device.

*Done anyway on **NA5C** (BOOX NoteAir5C, Kaleido colour):* `initialized: true`, no adapters found,
fallback engine selected and running, `app:sproutPen="fountain"` at 2 dp honoured from XML, the
tracked toolbar reported as `1 registered, 1 active`, and Ingest → Round-trip giving
`strokes: 2 · round trip: identical`. Two things surfaced that a JVM test could not have
(see §8 and §5.9).

---

### Phase 2 — Generic engine: capture and render

**Goal.** A fully working canvas on an ordinary Android stylus tablet. This is the baseline every
device falls back to, so it must be complete before any vendor adapter exists.

**Deliverables** (all in `:canvas`)
- `engine/generic/GenericInkEngine` — stylus-only capture (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`),
  historical-point harvesting, full channel capture (pressure, tilt, orientation/`AXIS_TILT`, size,
  timestamp) with per-device ranges read from `InputDevice.getMotionRange`
- Pen-activity gate (§5.3), exposed through `isPenActive` and `onPenActiveChanged`
- Barrel-button erase (§5.4)
- `render/` — committed `RenderNode` model + the mandatory software fallback branch (§3.8), live
  active-stroke draw, `StrokeRenderer` registry
- Bounds + exclusion enforcement at capture time (§3.7)
- Ingest (`setStrokes` / `addStroke` / `removeStrokes` / `clear`) actually rendering
- Erase: `STROKE` mode with AABB pre-filter, throttled redraw, finalize-on-gesture-end
- Lab gains the **Canvas**, **Tools** (partial), **Overlays** and **Device report** screens
- **Golden-suite tier decision (D13):** measure Robolectric graphics fidelity against the same
  renderers run instrumented; record the result and wire `goldenTest` to whichever tier wins

**Acceptance**
- Draw, erase, switch colour/width, clear — all correct on a generic stylus tablet
- Content survives resize, rotation and re-layout (G8)
- Nothing is captured outside the view's bounds (G6) or inside an exclusion zone (G7)
- `setStrokes(getStrokes())` is a visual no-op (G4)
- Captured strokes carry every channel the device reports (G3), verified in the device report

**Tests.** Instrumented MotionEvent-injection suite (§4.2) — runs on emulator and device. JVM
renderer geometry tests on every build. The golden suite is stood up here in whichever tier the
fidelity measurement selects (§4.1.1).

**Device protocol.** Generic Android stylus tablet (S26 Ultra S-Pen and/or Wacom Movink Pad). Run the
conformance items that exist so far; export the first device report.

> **Done on the Wacom Movink Pad 11** — `docs/conformance/mip11-2026-08-08.md`. Also installed and
> launched on the **NA5C**, which correctly selects the generic engine (no vendor adapter exists
> until Phase 4). A tip worth reusing: **`adb shell input stylus` produces genuine
> `TOOL_TYPE_STYLUS` events**, so nine-pen coverage, exclusion zones and resize were all scripted
> rather than tapped out by hand — with the caveat that those events come from a virtual device
> declaring no axes, so anything pressure-driven still needs the real pen.

---

### Phase 3 — Tooling & rendering fidelity

**Goal.** Every standardized pen renders correctly through software, on the generic engine, so the
vendor adapters have a defined target to match rather than inventing one.

> **Delivered early, in Phase 2** — all nine renderers, the curves, the multipliers, the colour
> chokepoint, `PenFidelity` and the Lab's Tools screen. Verified by eye on the Movink Pad with a real
> pen: every pen reads as what its name claims.
>
> **Do not spend this phase perfecting the curves.** The numbers in `PenTuning` are taste, and today
> the software renderer is the only reference there is, so tuning it now means tuning against
> preference alone. The moment worth waiting for is **Phase 4/5**, where our fountain can sit beside
> the firmware's on the same panel — and where path-A/path-B agreement is an acceptance criterion
> anyway, so these numbers get revisited regardless. Tuning now means tuning twice, and worse, means
> tuning the vendor paths to agree with a reference nobody has checked against hardware ink.
>
> Deferring is cheap by construction: every constant lives in one `when` in `PenTuning.forPen`, the
> geometry tests assert *relationships* rather than absolute values so they survive re-tuning
> untouched, and only the goldens pin pixels — `./gradlew goldenTest -Psprout.golden.regenerate=true`
> and read the diff.

**Deliverables**
- Software `StrokeRenderer` for each of the nine `SproutPen`s: `BALLPOINT`, `FOUNTAIN`, `BRUSH`,
  `MARKER`, `HIGHLIGHTER`, `PENCIL`, `CHARCOAL`, `CALLIGRAPHY`, `DASHED`
- Pressure→width and velocity→width curves, tunable per pen, with sane defaults borrowed from the
  Onyx `PenUtils` constants (spacing 0.25, pressure sensitivity 0.375, smoothing 0.6) so our software
  ink already sits in the same family as the SDK's
- Per-pen nominal-width multipliers (§5.7) so pens *feel* like the same nominal width
- Alpha handling and default width/opacity for `HIGHLIGHTER`; `capabilities.supportsAlpha`
- `PenFidelity` reporting per engine
- Colour handling: the single `paintColor` chokepoint; stored colour never rewritten (§3.6)
- Lab **Tools** screen completed — every pen/width/colour, live switching, fidelity display

**Acceptance**
- All nine pens visually distinct and recognizable as what their name claims
- `MARKER` and `HIGHLIGHTER` are unmistakably different tools, not one tool with two names
- Pressure genuinely modulates `FOUNTAIN`/`BRUSH` width on a device that reports pressure
- `CALLIGRAPHY` produces a real chisel nib (one diagonal thick, the other thin)
- `PENCIL`/`CHARCOAL` show actual grain at reasonable widths (mind the width trap, §5.7)
- Ingest round-trip is pixel-identical for every pen

**Tests.** JVM geometry tests per renderer; golden-image comparisons; exhaustive per-pen ingest
round-trip in the instrumented suite.

**Device protocol.** Generic stylus tablet. Draw one stroke per pen, screenshot, attach to the
conformance report. (Committed content is capturable — no `CONFIRM` items needed yet.)

> **Done on the Wacom Movink Pad 11, with the real pen** —
> `docs/conformance/mip11-2026-08-08-phase3.md`. That mattered: `adb shell input stylus` carries no
> pressure, so the criterion *"pressure genuinely modulates `FOUNTAIN`/`BRUSH`"* is not automatable
> on any device and a human had to answer it. The golden suite grew 18 → 33 scenes; the curves were
> **not** tuned, per the note above. Two defects the new width ladders found are recorded in §5.13
> and left as the Phase 4/5 tuning pass's starting material.

---

### Phase 4 — Onyx adapter (BOOX)

**Goal.** Hardware-accelerated ink on BOOX, with the app-facing API unchanged.

**Deliverables**
- New module `:canvas-onyx`, namespace `com.symmetricalpalmtree.sprout.canvas.onyx`
- BOOX maven repo in `settings.gradle.kts`; `onyxsdk-pen:1.5.4` + `onyxsdk-device:1.3.3`;
  jetifier, `pickFirsts`, `abiFilters` (§5.7)
- `OnyxInkEngine` — `TouchHelper` + `RawInputCallback`; limit rect + exclusion zones; app-scope
  fast-mode pin; overlay lifetime discipline; eraser overlay ordering; `handwritingRepaint`
  discipline; barrel button; the full §5.1 rule set
- `OnyxPenOwner` — the process-global ownership guard (§5.2), entirely internal
- **Full channel capture from `TouchPoint`** — pressure, tiltX, tiltY, size, timestamp.
  *Notesprout reads only x/y and discards the rest; sprout-canvas will not.*
- `maxTouchPressure` read at runtime; tilt reported raw with `tiltUnitsKnown = false` (§3.5)
- Tool mapping: `SproutPen` → `setStrokeStyle` (live) **and** the matching committed renderer,
  tuned for path-A/path-B agreement (§3.8)
- `ResManager.init` handling for bitmap-backed pens (§5.7)
- `OnyxInkEngineFactory` + `consumer-rules.pro` keep rule
- `SproutCanvas.initialize` wiring (D11): the adapter performs `HiddenApiBypass` there, and
  `isSupported` returns false with a clear logged error when initialize was never called — so a host
  that forgets gets the generic engine and an explanation, not a crash and not a mystery

**Acceptance**
- Live ink is the firmware overlay; committed content matches it closely (G2, §3.8)
- No writing under any registered overlay, on any toolbar placement (G7)
- No writing outside the canvas rect, including when the canvas is smaller than the screen (G6)
- First stroke after open is instant — no 1–2 s lag (§5.1)
- Erase leaves no grey residue and no black flash
- Two canvases in one process hand off cleanly; neither goes dead (§5.2)
- A palm resting on the glass does not fire host gestures (§5.3)
- Captured strokes carry pressure and tilt (G3)
- Skipping `SproutCanvas.initialize` yields the generic engine plus a clear logged error — verified
- **Answer the open device question from §3.6:** does the firmware overlay honour alpha in live
  preview? Record the result and set `HIGHLIGHTER`'s Onyx fidelity accordingly

**Tests.** JVM tests for the mapping tables and the ownership guard's state machine (pure logic,
testable with fakes). Instrumented tests cannot inject into the raw pipeline — **the Onyx path is
validated through the conformance harness**, with `CONFIRM` items for everything the firmware paints.

**Device protocol.** Minimum **two panels**: the mono flagship (G102) and one colour panel (NA5C or
P2P). Full conformance run on each; reports committed under `docs/conformance/`.

---

### Phase 5 — Supernote adapter (Ratta)

**Goal.** Hardware-accelerated ink on Supernote. Highest-risk phase — reverse-engineered, firmware
specific. It ships behind a binder probe with a safe generic fallback, so a failure degrades rather
than breaks.

**Deliverables**
- New module `:canvas-supernote`, namespace `com.symmetricalpalmtree.sprout.canvas.supernote`
- `SupernoteInk` — Kotlin binder client ported from the reference Lua (§1.4): `ServiceManager`
  lookup by reflection with both service-name aliases, cached `IBinder`, `transact` helper writing
  the interface-token + app-name preamble, `DeadObjectException` recovery + re-lookup, parcel
  recycling. Public surface: `isAvailable`, `claimPen`, `setPen`, `setEraser`, `clearAll`,
  `setDisableAreas` / `clearDisableAreas`, `enableFullUiAuto`
- `SupernoteInkEngine` — generic-style `MotionEvent` capture + firmware overlay live ink. Suppress
  the software active-stroke draw; on pen-up commit the stroke then `clearAll()`, mirroring the Onyx
  bitmap-handoff ordering so there is no flash
- Exclusion zones → `TX_DISABLE_AREA` **in screen coordinates** (§3.7 — the offset is the #1 risk)
- Tool mapping: `SproutPen` → `NEEDLE` / `INK` / `MARK` / `CALLIGRAPHY` (note `HIGHLIGHTER` → `MARK`
  is one of the two `NATIVE`-fidelity wins on this platform), plus the **EMR size → width table**:
  read it out of `plateaukao/supernote_draw` first, record the source in `docs/`, then validate and
  tune it on the Nomad against a `BALLPOINT` reference (D15, §5.8)
- Mode transitions: eraser, released-overlay, focus loss/regain, detach — each with the right
  `clearAll()` / claim / disable-area sequencing
- `enableFullUiAuto` guarded; degrade to generic if absent (§5.8)
- `SupernoteInkEngineFactory` + `consumer-rules.pro`

**Acceptance**
- Live ink is visibly lower-latency than the generic engine on the same device
- **No coordinate jump on pen-lift** — the baked stroke lands exactly where the firmware painted it
- No writing under registered overlays (G7) or outside the canvas rect (G6)
- No stale firmware ink survives a clear, an ingest, or a canvas resize
- A Supernote without the binder falls back to generic with no crash and no visual difference from
  Phase 2/3 behaviour

**Tests.** JVM tests for the parcel-payload builders (pure byte-layout logic, fully testable without
a device) and for the availability state machine. Everything else is harness-driven.

**Device protocol.** Supernote **Nomad**, and **Manta** if present. Manta shares Nomad's firmware and
chipset, so treat them as one target but verify both. Coordinate alignment is the make-or-break check.

---

### Phase 6 — Conformance harness & cross-device regression

**Goal.** Turn Sprout Canvas Lab into the durable regression instrument, and establish the baseline.

**Deliverables**
- Lab completed: **Data** (JSON dump + re-ingest), **Conformance run** (scripted checklist with
  `PASS`/`FAIL`/`CONFIRM`, timestamped report export), **Device report** finalized
- Engine-override UI so a BOOX or Supernote can be forced onto the generic engine for side-by-side
  comparison — this is how path-A/path-B agreement gets judged
- Full conformance matrix run across all three platforms
- `docs/conformance/` baseline reports committed
- `docs/testing.md` — how to run each tier, how to add a check, what can and cannot be automated
  (including the screencap limitation, §4.3)

**Acceptance**
- One documented command/flow produces a complete report on any device
- All three platforms have a committed baseline report
- Every acceptance criterion from Phases 2–5 appears as a conformance item

**Device protocol.** All three platforms.

---

### Phase 7 — Packaging, documentation, release prep

**Goal.** Something another app — starting with Notesprout — can actually adopt, consumed locally.

**Deliverables**
- `README.md` finalized: what it is, device support matrix, install via `mavenLocal` **and** the
  `includeBuild` composite recipe (with the BOOX-repo caveat), the required `SproutCanvas.initialize`
  call, quick start, full API tour, standardized tool table, capability model, Compose interop
  sample, testing, license
- `docs/integration.md` — step-by-step host-app guide: lifecycle, exclusion zones, the pen-activity
  gate, threading rules, and what happens if `initialize` is skipped
- `docs/architecture.md` — the engine SPI, for anyone writing a third-party engine
- KDoc coverage audit; Dokka HTML if it earns its keep
- `maven-publish` finalized with full POM metadata + sources jar, targeting `mavenLocal` (D7). The
  configuration is registry-agnostic, so a future public publish is a credentials change, not a
  rewrite.
- API stability review — one deliberate pass over the public surface before it is frozen
- Version `1.0.0`, tagged

**Acceptance**
- A fresh app can consume the library locally and draw on a BOOX, a Supernote and a tablet using only
  the README
- Public API has no accidental leaks (explicit API mode clean, no vendor types in `:canvas`)

**Device protocol.** Adoption smoke test: a throwaway app consuming the locally-published artifacts,
on one device per platform.

---

## 8. Status

Update the row at the end of each phase, then commit and push.

| Phase | Name | Status | Commit | Date | Notes |
|---:|---|---|---|---|---|
| — | Planning | ✅ Complete | `2dc9949` | 2026-08-07 | This document |
| 0 | Foundation & build scaffolding | ✅ Complete | `2dc9949` | 2026-08-07 | Build + 4 JVM tests green from clean; `publishToMavenLocal` resolves `com.symmetricalpalmtree.sprout:canvas:0.1.0-SNAPSHOT` (AAR + sources + MIT POM); Lab installs, launches and is findable by name on **G10**. Three things worth carrying forward: (1) Java 17 comes from `jvmToolchain(17)`, **not** an `org.gradle.java.home` pin — an absolute JDK path in a committed file breaks every other machine; (2) explicit API mode was verified **by probe**, not assumed — it did not appear in the compiler args when grepped, and has historically been unreliable on Android variants; (3) BOOX disables freshly sideloaded APKs — see §5.9. |
| 1 | Core model & public API contract | ✅ Complete | `ac9ce38` | 2026-08-07 | 151 JVM tests green (144 `:canvas`, 7 `:lab`); `build test` and `publishToMavenLocal` clean; explicit API mode re-verified by probe (a bare `fun f() = 42` fails the build). Four contract decisions taken with the user, all recorded in §10.3. Five things worth carrying forward: (1) **`StrokeSamples.channels` is derived, not passed** — §3.5 sketched it as a constructor parameter, but a mask supplied separately from the data it describes is a mask that can disagree with it; deriving deletes the failure mode. (2) **The vendor tables are `@RestrictTo(LIBRARY_GROUP)`** — adapters are separate Gradle modules so `internal` cannot reach them, but the Onyx style ints and Supernote pen codes must not become frozen public API; `PenFidelity` and `capabilities.fidelity()` stay fully public because that is what a host actually needs. (3) **`androidx.annotation` moved to `api`** — `@ColorInt` / `@IntDef` / `@MainThread` / `@RestrictTo` appear on the public surface, and an annotation a consumer's compiler cannot resolve does nothing. Still exactly one dependency. (4) **`-Xannotation-default-target=param-property`** is on for `:canvas`; without it a constructor-property annotation lands on the value parameter only, so a consumer reading the getter sees nothing. Lint then immediately caught a real `@IntDef` gap (`InkChannel.NONE` had to be declared as a legal value) — the annotations are load-bearing, not decorative. (5) **`SproutLog` was created here, not in Phase 2** — D11's missing-`initialize` error needed it. §3.9 corrected: `findViewTreeLifecycleOwner()` cannot be used, it needs `androidx.lifecycle`. Build environment: this machine's default `java` is now JDK 26, which Gradle 8.14 cannot run on — see §5.12. **Device-verified on NA5C** (not required by this phase): the report reads `initialized: true`, no adapters found, fallback engine selected, `app:sproutPen="fountain"` honoured from XML, `1 registered, 1 active` exclusion zone, and Ingest → Round-trip gives `strokes: 2 · round trip: identical`. Two things only hardware could show: (a) **`pm enable` loses a race with Onyx** — the NA5C re-disabled the package after a successful enable, so enable must be issued in the *same device shell* as the launch, every launch (§5.9 updated, skill updated); (b) the Lab's device report refreshed from `onResume` alone showed `0 active` zones with the toolbar plainly on the canvas, because zone computation is coalesced to one posted pass per layout and `onResume` runs before the first layout — now also refreshed from `onWindowFocusChanged`. That was a bug in the diagnostic, not the tracker, which is exactly the sort of thing that sends a later session hunting a fault that is not there. |
| 2 | Generic engine: capture and render | ✅ Complete | `8c10fcb` | 2026-08-08 | **The canvas draws.** 255 JVM tests (241 `:canvas`, 14 `:lab`) + 20 instrumented tests on the MIP11 (0 skipped, so every channel assertion actually ran) + an 18-scene golden suite. Scope: at the user's direction Phase 2 delivered **all nine software renderers**, not the two the phase strictly needed — so most of Phase 3 landed here (see the Phase 3 note below). **R1 is resolved** — Robolectric with `NATIVE` graphics hosts the golden suite; 16 of 18 scenes are byte-identical to the Wacom tablet and the other two differ by 1 on one channel (`docs/golden-tier.md`). Seven things worth carrying forward: (1) **The `InkEngineHost` SPI gained `onEraseEnded()`** — one eraser swipe is one action to a user, so the strokes it removed are reported once at the gesture boundary rather than in batches, and a hardware engine gets exactly one panel repaint out of it instead of one per move event. (2) **Renderers are split into a pure-Kotlin solver and a thin `android.graphics` shell.** Taper curves, nib angle, dash cadence, decimation and grain determinism are all asserted in plain JUnit in milliseconds on every build; only *appearance* needs pixels. (3) **The renderer registry is per canvas, not global** — two canvases in one process can run different engines, which is exactly how the harness compares the hardware and software ink paths. (4) **Texture grain must be seeded from the stroke's own id**, or `setStrokes(getStrokes())` stops being a visual no-op for the pencil and charcoal alone — a G4 failure that would get blamed on the ingest path. (5) **Grain scale has to be decoupled from the width multiplier**: charcoal's ×5 scaled its stamps up with it until they read as a row of circles. (6) **A calligraphy nib modelled as a zero-thickness line draws nothing at all** when the stroke runs along its own angle — caught by a golden, not by geometry. (7) **Robolectric's default graphics mode records draw calls without executing them**, so any pixel test needs `@GraphicsMode(NATIVE)` or it passes forever while asserting nothing. Four bugs were found only by putting it on hardware — all fixed, all now covered by a test, all recorded in §5.10–§5.11 and `docs/conformance/mip11-2026-08-08.md`. |
| 3 | Tooling & rendering fidelity | ✅ Complete | `42fd0df` | 2026-08-08 | **The renderers were delivered in Phase 2; this phase established what they actually look like.** Deliverables had already landed — all nine renderers, the curves, the multipliers, highlighter alpha, the colour chokepoint, `PenFidelity`, the Lab's Tools screen — so the work here was the two named remnants plus the device protocol, and the pen curves were **deliberately not tuned** (see §7's Phase 3 note; reaffirmed by the user this session). 255 JVM tests green; golden suite **18 → 33 scenes**. Five things worth carrying forward: (1) **A golden scene now holds a *list* of strokes.** `highlighter-over-ink` had drawn a highlighter onto a *blank bitmap* for two phases — a scene named for a blend it never performed, passing the whole time. Compositing is where a drawing library actually fails, and one stroke on white cannot express it. (2) **Width ladders found a real defect the single-width scenes were blind to** — texture grain becomes countable circles at `XXL` and pencil/charcoal swap character at `HAIRLINE`, both confirmed on hardware with a real pen. Recorded in §5.13 with pictures, pinned by goldens, left for the Phase 4/5 tuning pass to start from rather than fixed against preference. (3) **`CommittedLayer`'s software branch had no pixel coverage at all**, and it is the branch an Onyx panel repaint and every host screenshot take (§3.8). Losing it produces no exception and no geometry change — just a blank panel, in Phase 4, with nothing to point at. Both tiers now assert the committed path and the direct path are byte-identical. (4) **`GenericPenTable` stays all-`NATIVE`, re-examined and left alone on purpose.** `PenFidelity` describes how faithfully an *engine* reproduces a pen, not how good the ink looks; on an ordinary tablet there is no other path for the software renderer to be worse than. A table where every row says the same thing invites the suspicion nobody decided it, so the reasoning and what would overturn it are now in its KDoc. (5) **The calligraphy nib is the strongest result on the device sheet** — the tester added a descending curl on the reasoning that a nib must be proven in both directions, and thick-across/thin-along is exactly what came out. Device protocol run on **MIP11 with the real pen in a real hand**, which is the only way the pressure criterion is answerable at all: `docs/conformance/mip11-2026-08-08-phase3.md`, with both stroke sheets committed under `docs/conformance/images/`. |
| 4 | Onyx adapter (BOOX) | ⬜ Not started | | | |
| 5 | Supernote adapter (Ratta) | ⬜ Not started | | | |
| 6 | Conformance harness & regression | ⬜ Not started | | | |
| 7 | Packaging, docs, release prep | ⬜ Not started | | | |

**Legend:** ⬜ Not started · 🟡 In progress · 🔵 Awaiting device test · ✅ Complete · ⛔ Blocked

---

## 9. Deferred — explicitly later, not forgotten

Recorded so a future session knows these were considered and deliberately postponed.

| Item | Note |
|---|---|
| Surface / template / background layer | The canvas is plain white in v1. A background layer is the natural next feature. |
| Multi-layer support | Content layers with z-order, visibility, per-layer locking. |
| `AREA` and `PIXEL` eraser modes | Enum entries exist from Phase 1, reported unsupported. |
| Highlighter drawn *behind* ink | `HIGHLIGHTER` exists from v1 (D12) but draws over with alpha. Drawing beneath ink needs the layering model. |
| Publishing to a public registry | Local/private by decision (D7). The `maven-publish` config is registry-agnostic, so this is a credentials change whenever it is wanted. |
| Onyx `NEOPEN_PEN_TYPE_BRUSH_SIGN` (type 10) | Constructs but throws on render with the brush result reader — likely the East-Asian brush with a different result shape. Untested. |
| `SOFT_ERASER` overlay style (Onyx style 8) | Marks rather than doing nothing; never tested against existing ink. |
| Compose-first module | View + `AndroidView` for now (D3). |
| Zoom / pan / scroll transforms | The canvas is 1:1 with its own bounds in v1. |
| Stroke serialization helpers | The app owns storage (§1.3), but an optional codec module may earn its place. |
| Wacom / other vendor adapters | The SPI is open; no adapter planned. |
| Performance benchmark suite | The Onyx survey's timings are single cold renders dominated by warmup and are **not** a benchmark. A real repeated-run harness is needed before any renderer is chosen on speed. |

---

## 10. Question log

### 10.1 Resolved (2026-08-07)

All eight questions raised during planning are answered. Recorded with their resolution so a later
session does not reopen a settled decision — or, if it needs to, knows what was traded away.

| # | Question | Resolution | Lands in |
|---|---|---|---|
| O1 | License | **MIT.** Apache-2.0's patent grant was considered and judged unnecessary. | D8, Phase 0 |
| O2 | Artifact names | **`canvas` / `canvas-onyx` / `canvas-supernote`** under group `com.symmetricalpalmtree.sprout` — the group already says "sprout", so no stutter. | D2, Phase 0 |
| O3 | Hidden-API bypass | **Explicit host `SproutCanvas.initialize(application)`.** Missing ⇒ logged error + generic engine, never a crash. | D11, §3.2, Phase 4 |
| O4 | Render-test rigour | **Geometry on every build; goldens in a separate on-demand suite.** Hosting tier for goldens measured in Phase 2. | D13, §4.1.1, Phase 2 |
| O5 | Supernote EMR mapping | **Upstream `supernote_draw` first, then tune on the Nomad.** Done in Phase 5, not front-loaded. | D15, Phase 5 |
| O6 | `MARKER` vs `HIGHLIGHTER` | **Split — nine pens.** They are genuinely different tools on Supernote and merging them would recreate the vendor ambiguity. | D12, §3.6, Phase 3 |
| O7 | Harness app identity | **"Sprout Canvas Lab"** — a real installable, `:lab`, `…sprout.canvas.lab`, `.dev` suffix. | D14, §4.3, Phase 0 |
| O8 | Publishing target | **Local / private indefinitely.** Publishing config stays registry-agnostic. | D7, §9, Phase 7 |

### 10.2 Still open

Only genuinely undecidable-from-a-desk items belong here. Each is answered by measurement in its
phase, not by preference.

| # | Question | Answered by | Fallback |
|---|---|---|---|
| R2 | Does the Onyx firmware overlay honour **alpha** in live preview? If not, `HIGHLIGHTER` reads opaque while writing and only becomes translucent on commit. | Phase 4 device check | Report the limitation through `capabilities`; never hide it |
| R3 | Does the Supernote firmware honour a per-stroke width fine enough to express our dp ladder, or is the EMR range coarse? | Phase 5 device tuning | Quantize the ladder to what the hardware actually resolves, and report it |

### 10.3 Resolved in Phase 1 (2026-08-07)

Four API-shape questions that could not be settled from the plan alone, and would have been
expensive to reverse once the adapters were written against them.

| # | Question | Resolution |
|---|---|---|
| P1 | Who owns the arrays handed to `StrokeSamples`? | **Copy on the public constructor, `Builder` on the capture path.** A host cannot corrupt a stored stroke by reusing its own working buffers; the engine path appends without allocating per point and the builder's buffers survive `build()` for reuse across strokes. `array.size == count` is an enforced invariant everywhere. |
| P2 | How broad is `SproutCanvasListener`? | **Full event set, every method defaulted.** Erase hands back the removed `InkStroke`s, not their ids — the library owns no history, so a host implementing undo would otherwise have to keep a shadow copy of the whole canvas. Installation (`setStrokes`/`addStroke`) fires nothing; removal fires whatever caused it. |
| P3 | How far does `NoOpInkEngine` go? | **Truly inert.** It reports capabilities and accepts every call, captures nothing and draws nothing. Ingest, removal and listener dispatch are real, so the model is exercisable on a device; capture is exercised by a recording fake in tests. No throwaway code for Phase 2 to unpick. |
| P4 | How visible are the vendor mapping tables? | **`@RestrictTo(LIBRARY_GROUP)` for the vendor constants, fully public for fidelity.** Adapters compile against `OnyxPenTable` / `SupernotePenTable`; a host app gets a lint error for touching an Onyx style int, and reads `capabilities.fidelity(pen)` instead. |

### 10.4 Resolved in Phase 2 (2026-08-08)

| # | Question | Resolution |
|---|---|---|
| R1 | Which tier hosts the golden-image suite — Robolectric or instrumented? | **Robolectric, in `NATIVE` graphics mode.** Measured rather than preferred: both tiers render the same 18 scenes, and on a Wacom Movink Pad 11 (API 34) **16 of 18 came back byte-identical** to the JVM, with the other two differing by **1 unit on one channel** across a few hundred anti-aliased pixels. Nothing about goldens needs hardware, so the instrumented tier buys only a cross-check — which it keeps providing, on demand, through `GoldenImageInstrumentedTest`. The measurement and what would overturn it are in `docs/golden-tier.md`. |
| P5 | Does the erase gesture need a boundary in the engine SPI? | **Yes — `InkEngineHost.onEraseEnded()` was added.** One swipe of an eraser produces a stream of `onEraseAt` calls; without a boundary a host implementing undo gets five or ten entries for one user action, and a hardware engine has no moment at which one panel repaint is correct rather than one per move event. |

---

## 11. Session checklist

**This project is run one phase per session, with the session cleared in between.** Two files exist
so that a cold session loses nothing:

- **`CLAUDE.md`** (repo root) — loaded automatically at session start. Thin by design: it points here
  and carries only the always-relevant guardrails. It does not duplicate this document.
- **`.claude/skills/device-build-install/SKILL.md`** — build/install commands, the device serial
  table, the BOOX enable-after-sideload step, and how to verify a launch actually worked.

Keep both in sync with this plan. If a phase changes a constraint, a device requirement, or a build
command, update the corresponding file in the same commit — a stale guardrail is worse than none.

At the **start** of a phase session:
1. Read §2 Decisions, §3 Architecture, §5 Hard-Won Lessons, and the phase's own section.
2. Check §8 for the previous phase's notes and any carry-over.
3. Check §10 for open questions that phase must resolve, and **ask them before building**.

At the **end** of a phase session:
1. Run `./gradlew build test` (and the instrumented suite where it applies) — green.
2. Run the phase's device protocol; export conformance reports where they exist.
3. Update §8: status, commit hash, date, notes. Record anything learned in §5 or §10.
4. Commit and push.
5. Stop. Do not begin the next phase without being asked.
