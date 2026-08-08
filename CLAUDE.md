# sprout-canvas — Claude Code Project Intelligence

An Android **library** giving host apps a stylus drawing canvas that captures the full stylus signal
a device can produce, renders it, and returns it through one standardized API — on BOOX (Onyx SDK),
Supernote (Ratta firmware ink), and ordinary Android stylus tablets alike.

- **License:** MIT · **Namespace:** `com.symmetricalpalmtree.sprout.canvas`
- **Coordinates:** `com.symmetricalpalmtree.sprout:canvas` (+ `canvas-onyx`, `canvas-supernote`)

---

## ⚠️ Read PLAN.md first

**[`PLAN.md`](PLAN.md) is the source of truth for this project.** It carries the architecture, the
settled decisions, the device-quirk lessons, and every phase's deliverables and acceptance criteria.
This file holds only the always-relevant guardrails; it deliberately does **not** duplicate the plan,
because two copies of an architecture drift apart.

Before doing any work, read:

| Section | Why |
|---|---|
| **§2 Decisions** | Settled choices (D1–D15). Do not re-litigate one without saying so. |
| **§3 Architecture** | Modules, engine SPI, data model, the standardized tool vocabulary. |
| **§5 Hard-Won Lessons** | Device quirks that cost real debugging time elsewhere. **Mandatory before touching any engine.** |
| **§7** — the phase you are on | Goal, deliverables, acceptance criteria, device protocol. |
| **§8 Status** | Where the project actually is, and notes carried from the last phase. |
| **§10.2** | Questions still open, and which phase answers each. |
| **§11 Session checklist** | The start-of-phase and end-of-phase ritual. Follow it. |

---

## Working rhythm — one phase per session

The user runs this project **one phase at a time, with a cleared session between phases.** That is
why the plan is written the way it is.

1. **Start:** read the sections above. Ask any clarifying questions **before building** — the user
   has asked for this explicitly and repeatedly.
2. **Build** the phase's deliverables.
3. **Test:** `./gradlew build test`, plus the phase's device protocol.
4. **Record:** update `PLAN.md` §8 (status, commit, date, notes). Add anything learned to §5 or §10.
5. **Commit and push.**
6. **Stop.** Do not begin the next phase without being asked.

---

## Standard constraints

These apply everywhere. They are not repeated in the plan's phase sections.

- **Kotlin**, Java 17 target. The toolchain comes from `jvmToolchain(17)` in each module —
  **never** pin `org.gradle.java.home` in the repo's `gradle.properties`. An absolute JDK path in a
  committed file works on exactly one machine.
  ⚠ **Gradle itself must also run on JDK 17.** This machine's default `java` is JDK 26, which
  Gradle 8.14 cannot run on; it fails before compiling anything with the message `* What went
  wrong: 26`. `jvmToolchain` does not help — it picks the JDK that compiles the code, not the one
  that runs Gradle. Prefix commands with
  `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`, or set
  `org.gradle.java.home` in `~/.gradle/gradle.properties` (machine-local, uncommitted). See
  PLAN.md §5.12.
- **`android.enableJetifier=true` is required by `:canvas-onyx`**, and by any app that depends on
  it. `onyxsdk-base` pulls `pub.devrel:easypermissions:0.2.1`, which drags in the whole
  `com.android.support:appcompat-v7:24.2.1` tree; without jetifier, packaging fails with a wall of
  `Duplicate class android.support.v4.…` naming AndroidX and a support library nothing here asked
  for. It costs a transform pass over every dependency — that is the price of the BOOX ink path.
  See PLAN.md §5.7 and the note in `gradle.properties`.
- **`:canvas` has exactly one dependency: `androidx.annotation`**, on the `api` configuration
  because its annotations appear on the public surface. No coroutines, no serialization, no
  Material, no Compose, **no `androidx.lifecycle`**. A drawing library must not dictate a host app's
  stack. Adding anything here needs explicit discussion.
- **No vendor types in `:canvas`.** Onyx and Supernote SDK types live in their adapter modules and
  never appear in the public API. A phone-only app must never pull the BOOX SDK.
- **Explicit API mode is on for `:canvas`** — every public declaration states its visibility and
  return type. It is enforced as an **error**, verified by probe (see §8's Phase 0 notes).
- **minSdk 29.** Required by the committed-content `RenderNode` render model (PLAN.md D4, §3.8).
  Raising or lowering it is an architecture change, not a config tweak.
- **The public API is main-thread only** — documented, and asserted in debug builds.
- **No `Log.d` directly** — use the `SproutLog` inline wrapper so release consumers pay nothing.
  `d { }` takes a lambda and is gated on `SproutCanvas.debugLogging`; `w`/`e` always log, because a
  host that lost the hardware ink path must be told.
- **Every public type carries KDoc**, including the *why* for anything driven by a device quirk. The
  quirks belong in the code that implements them, not only in the plan.
- **The library stores nothing.** No persistence, no file formats, no clipboard, no undo/redo, no
  export, no UI chrome. Those belong to the host app (PLAN.md §1.3).

---

## Repository layout

```
canvas/            :canvas             the library — zero vendor dependencies
canvas-onyx/       :canvas-onyx        BOOX adapter          (Phase 4 — in progress)
canvas-supernote/  :canvas-supernote   Supernote adapter     (Phase 5 — does not exist yet)
lab/               :lab                Sprout Canvas Lab — the conformance harness
gradle/libs.versions.toml              single source of dependency versions
```

**Sprout Canvas Lab is the instrument, not a demo.** Device validation runs through it, and it grows
one screen per phase. Do not create ad-hoc test apps; add a screen to the Lab instead.

---

## Build & test

Prefix every command with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
unless the shell's default `java` is already 17 — see the JDK note above.

```sh
./gradlew build test                         # compile + JVM tests (JUnit 4 + Robolectric)
./gradlew goldenTest                         # golden-image regression — on demand, NOT part of `check`
./gradlew goldenTest -Psprout.golden.regenerate=true   # accept the current rendering, then review the diff
./gradlew :canvas:connectedDebugAndroidTest  # stylus-injection suite, needs a device
./gradlew publishToMavenLocal                # publish :canvas locally (no public registry — D7)
./gradlew :lab:installDebug                  # install Sprout Canvas Lab
```

**Why `goldenTest` is separate:** geometry assertions run on every build; pixel comparison is run on
demand and before releases, so routine builds are never gated on rendering variance (PLAN.md D13,
§4.1.1). It runs on **Robolectric with `NATIVE` graphics** — settled by measurement in Phase 2, see
`docs/golden-tier.md`. Any pixel test needs `@GraphicsMode(NATIVE)`; Robolectric's default mode
records draw calls without executing them, so the test would pass forever while asserting nothing.

**Scripting a device session:** `adb shell input stylus swipe x1 y1 x2 y2 ms` produces genuine
`TOOL_TYPE_STYLUS` events, so most of a device protocol can be automated. Those events come from a
virtual device that declares no motion axes, so anything pressure-driven still needs the real pen.

Device builds, install, serials, and the BOOX enable-after-sideload step: see the
`device-build-install` skill (`.claude/skills/device-build-install/SKILL.md`) — invoked
automatically for build/install/device work.

---

## Reference project — Notesprout

`~/git/Notesprout` is a shipping handwriting app whose two-engine drawing stack is the source of most
of what this library knows. **Consult it rather than re-deriving.** Full path table in PLAN.md §1.4;
the three that matter most:

| Topic | Path (under `~/git/Notesprout`) |
|---|---|
| EPD rules, render model, pen-activity gate | `docs/drawing-engine.md` |
| Onyx pen-tool survey — 5 devices, 9 overlay styles, 10 native pens | `docs/onyx-pen-tools.md` |
| Supernote binder design | `SUPERNOTE_SUPPORT_PLAN.md` |

Notesprout is a **reference, not a dependency.** sprout-canvas shares no code with it, and improves
on it in two known places: columnar stroke samples, and exclusion zones that track a `View`
automatically.

---

## Things that are easy to get wrong

Short list; the full reasoning is in PLAN.md §5.

- **On e-ink, the hardware paints live ink — not us.** The view owns committed content; the engine
  owns the live stroke. Drawing live ink through an Android `Canvas` on e-ink feels broken.
- **A palm on the glass produces MotionEvents even when stylus ink does not.** That is what the
  pen-activity gate exists for, and why it is public API.
- **Vendor SDKs fail silently.** `setStrokeStyle` swallows failures with no exception, no return
  value and no log. Capability is established empirically and recorded — never assumed.
- **Read `maxTouchPressure` at runtime** (4095 on some BOOX models, 4096 on others). Never hardcode.
- **Tilt has no common scale** — one BOOX model reports ~100× the others. Report it raw; never
  invent a normalization.
- **`screencap` cannot capture the e-ink firmware ink overlay.** Live-ink checks are tester-confirmed;
  only committed content can be automated.
