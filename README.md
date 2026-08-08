# sprout-canvas

> A stylus drawing surface for Android that treats every device's hardware as a first-class citizen.

sprout-canvas is an Android **library**. It gives a host app a drawing canvas that captures the full
stylus signal a device can produce, renders it, and hands the data back through one standardized API
— whether the device is a BOOX e-ink tablet driving the Onyx SDK, a Supernote painting ink from
firmware, or an ordinary Android tablet with a stylus.

It is a **component**, not a screen. Embed it in any window, any `ViewGroup`, at any size.

```kotlin
// The same code on every device.
canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 2f, color = Color.BLACK)
canvas.addExclusionZone(binding.floatingToolbar)   // never written under, on any platform
canvas.listener = object : SproutCanvasListener {
    override fun onStrokeCompleted(stroke: InkStroke) { myRepo.save(stroke) }
}
```

---

## Status

🚧 **Early development.** The API is not stable and nothing is published to a public registry.

| Phase | | Status |
|---:|---|---|
| 0 | Foundation & build scaffolding | ✅ Complete |
| 1 | Core model & public API contract | ⬜ Not started |
| 2 | Generic engine: capture and render | ⬜ Not started |
| 3 | Tooling & rendering fidelity | ⬜ Not started |
| 4 | Onyx adapter (BOOX) | ⬜ Not started |
| 5 | Supernote adapter (Ratta) | ⬜ Not started |
| 6 | Conformance harness & regression | ⬜ Not started |
| 7 | Packaging, docs, release prep | ⬜ Not started |

Full detail, phase by phase, in [`PLAN.md`](PLAN.md).

---

## Why it exists

Writing on an e-ink device is not the same problem as writing on an LCD. The panel has its own ink
pipeline that paints strokes at sub-frame latency, bypassing Android's view system entirely — and if
you draw live ink through a `Canvas` instead, it lags badly enough to feel broken. Every vendor
solves this differently, names their pens differently, and fails differently when you get it wrong.

sprout-canvas absorbs that. The hardware paints live ink; the library owns the committed content
behind it; your app sees one API and one vocabulary.

**What it deliberately does not do:** storage, file formats, copy/paste, undo/redo, layers, export,
or any UI chrome. Those belong to the app. The library captures stylus input, renders it, and gets
out of the way.

---

## Device support

| Platform | Engine | Live ink | Status |
|---|---|---|---|
| **BOOX** (Onyx SDK) | `:canvas-onyx` | Firmware EPD overlay | Phase 4 |
| **Supernote** (Ratta) | `:canvas-supernote` | Firmware ink daemon (binder) | Phase 5 |
| **Standard Android** stylus tablets | built in | App `Canvas` | Phase 2 |

Vendor support is **opt-in**. The core library carries no vendor dependencies, so a phone-only app
never pulls the BOOX SDK's native libraries. A device with no matching adapter — or a Supernote
whose firmware lacks the ink service — falls back to the generic engine automatically and keeps
working.

---

## Installation

Not yet published to a public registry. Consume it locally:

```kotlin
// settings.gradle.kts
includeBuild("../sprout-canvas")
```

or `./gradlew publishToMavenLocal` and then:

```kotlin
dependencies {
    implementation("com.symmetricalpalmtree.sprout:canvas:0.1.0-SNAPSHOT")            // always
    implementation("com.symmetricalpalmtree.sprout:canvas-onyx:0.1.0-SNAPSHOT")       // BOOX
    implementation("com.symmetricalpalmtree.sprout:canvas-supernote:0.1.0-SNAPSHOT")  // Supernote
}
```

The Onyx adapter additionally needs the BOOX repository, which serves over plain HTTP:

```kotlin
// settings.gradle.kts — only if using :canvas-onyx
maven {
    url = uri("http://repo.boox.com/repository/maven-public/")
    isAllowInsecureProtocol = true
}
```

**Requirements:** minSdk 29 · compileSdk 35 · JDK 17 · Kotlin 2.2 · AGP 8.11

---

## Usage

```xml
<com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
    android:id="@+id/canvas"
    android:layout_width="match_parent"
    android:layout_height="400dp" />
```

If you use the Onyx or Supernote adapters, opt in once at startup. Both vendor SDKs need process-wide
hidden-API access, and the library asks for it explicitly rather than installing it behind your back:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SproutCanvas.initialize(this)
    }
}
```

Skip it and the canvas still works — it runs the generic engine and logs why.

Using Compose? Wrap it. The library has no Compose dependency:

```kotlin
AndroidView(factory = { SproutCanvasView(it) }, modifier = Modifier.fillMaxSize())
```

---

## Building

```sh
./gradlew build test          # compile + JVM tests (JUnit 4 + Robolectric)
./gradlew goldenTest          # golden-image render regression, on demand
./gradlew publishToMavenLocal # publish :canvas locally
./gradlew :lab:installDebug   # install Sprout Canvas Lab on a connected device
```

**Sprout Canvas Lab** (`:lab`) is the conformance harness — a real installable app that exercises
every capability on real hardware and exports a per-device report. It is how each phase is validated
and how future changes are regression-tested. Device sessions run through the Lab, not through
ad-hoc test apps.

---

## Acknowledgements

The architecture is drawn from [Notesprout](https://github.com/gregmarine/Notesprout), a
handwriting-first notes app whose two-engine drawing stack, EPD handling rules, and five-device Onyx
pen survey are the source of most of what this library knows. The Supernote ink path builds on the
reverse engineering in
[`supernote_draw`](https://github.com/plateaukao/supernote_draw) and the KOReader Supernote e-ink
plugin.

---

## License

MIT © Greg Marine — see [LICENSE](LICENSE).
