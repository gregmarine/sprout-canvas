---
name: device-build-install
description: Build and install Sprout Canvas Lab to physical BOOX / Supernote / stylus-tablet devices by nickname (G102, NA5C, Nomad, S26U, etc.); includes gradle + adb commands, the device serial table, the mandatory BOOX enable-after-sideload step, and how to verify a launch actually worked. Use whenever asked to build, install, sideload, launch, screenshot, or look up a device serial.
---

# Device build & install — Sprout Canvas Lab

`:lab` (**Sprout Canvas Lab**) is the only app this repo installs. It is the conformance harness for
every phase. Do not create ad-hoc test apps — add a screen to the Lab.

- **Debug** — `com.symmetricalpalmtree.sprout.canvas.lab.dev`. **The default. Always build/install
  debug unless told otherwise.** Installs alongside release.
- **Release** — `com.symmetricalpalmtree.sprout.canvas.lab`. Explicit requests only; unsigned, so it
  must be signed before sideloading.

---

## The one-liner

```sh
./gradlew :lab:installDebug          # builds and installs to the ONLY connected device
```

With several devices attached, `installDebug` is ambiguous — build once, then install per device:

```sh
./gradlew :lab:assembleDebug
adb -s <serial> install -r lab/build/outputs/apk/debug/lab-debug.apk
# BOOX: enable and launch in ONE device shell — Onyx re-disables between separate adb calls.
adb -s <serial> shell 'pm enable com.symmetricalpalmtree.sprout.canvas.lab.dev && am start -n com.symmetricalpalmtree.sprout.canvas.lab.dev/com.symmetricalpalmtree.sprout.canvas.lab.LabActivity'
```

Install all requested devices in a single shell block. If the user says devices are ready, **skip
`adb devices`** — go straight to build and install. Users refer to devices by nickname ("install on
G102 and the Nomad").

---

## ⚠️ BOOX disables freshly sideloaded apps

**Always run `pm enable` after installing to any BOOX device.** Onyx's app-management layer disables
newly sideloaded packages, and the failure is badly misleading:

```
$ adb install -r lab-debug.apk
Success
$ adb shell am start -n …/….LabActivity
Error type 3
Error: Activity class {…/….LabActivity} does not exist.
```

The activity exists and the manifest is fine. `dumpsys package <id>` shows the truth:

```
User 0: … stopped=true notLaunched=true enabled=3 …
lastDisabledCaller: com.onyx
```

`enabled=3` is `COMPONENT_ENABLED_STATE_DISABLED_USER`.

```sh
adb -s <serial> shell pm enable com.symmetricalpalmtree.sprout.canvas.lab.dev
```

**⚠ Enabling once is not enough — Onyx re-disables it.** Seen on the **NA5C**: `pm enable` reported
`new state: enabled` and the first launch worked, then a later `am start` failed with `Error type 3`
and `dumpsys` showed `enabled=3 · lastDisabledCaller: com.onyx` all over again. Separate `adb` calls
leave Onyx a window to undo the enable. Put both in **one device-side shell**:

```sh
adb -s <serial> shell 'pm enable com.symmetricalpalmtree.sprout.canvas.lab.dev && am start -n com.symmetricalpalmtree.sprout.canvas.lab.dev/com.symmetricalpalmtree.sprout.canvas.lab.LabActivity'
```

Enable immediately before **every** launch, not once after install.

**Do not go debugging the manifest, namespace, or `applicationIdSuffix` on this error.** Two commands
separate package state from a code bug in seconds:

```sh
# "No activity found" ⇒ the package is disabled, not broken
adb -s <serial> shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <applicationId>

adb -s <serial> shell dumpsys package <applicationId> | grep "User 0:"   # check enabled=
```

Recorded in full at PLAN.md §5.9. First hit on the G10, 2026-08-07.

---

## Verifying a launch actually worked

`am start` printing `Starting: Intent {…}` means the intent was dispatched — **not** that the app is
alive. Confirm:

```sh
adb -s <serial> shell dumpsys window | grep -i mCurrentFocus          # is it focused?
adb -s <serial> logcat -d -t 200 | grep -iE "AndroidRuntime|FATAL"    # did it crash?
adb -s <serial> exec-out screencap -p > /tmp/lab.png                  # what does it look like?
```

> **`screencap` cannot capture e-ink live ink.** The BOOX and Supernote firmware ink overlays are
> painted straight to the panel and never enter the Android framebuffer. Screenshots show **committed
> content only**. Any check of a stroke *while the stylus is down* must be confirmed by the tester —
> this is why the Lab's conformance run has `CONFIRM` items (PLAN.md §4.3).

---

## Release builds

```sh
./gradlew :lab:assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out lab/build/outputs/apk/release/lab-release-signed.apk \
  lab/build/outputs/apk/release/lab-release-unsigned.apk
```

Worth doing at least once after Phase 4/5 land: it is the only way to prove the adapters'
`consumer-rules.pro` keep rules actually protect the reflectively-discovered engine factories. A
shrunk build that silently falls back to the generic engine is exactly the failure this repo must not
ship.

---

## Device serials

> Carried over from Notesprout's device records and **not all re-verified for this project.** Run
> `adb devices -l` if a serial does not respond. ✅ = confirmed working on sprout-canvas.

### BOOX — `OnyxInkEngine` (Phase 4)

| Device | Nickname | Serial | Panel |
|---|---|---|---|
| BOOX Go 10.3 Gen 2 | **G102** | `b7a46e13` | 10.3" mono — **Onyx flagship target** |
| BOOX NoteAir5C | **NA5C** | `92c16533` ✅ | 10.3" Kaleido colour |
| BOOX Note Max | **MAX** | `6325773d` | 13.3" mono |
| BOOX Go 6 Gen II | **G6** | `DAF86F61` | 6" mono |
| BOOX Palma2 Pro | **P2P** | `287d2364` | 6.1" colour — **narrowest, `sw439dp`** |
| BOOX Go 10.3 | **G10** | `34E517F9` ✅ | 10.3" mono (Android 12 / SDK 32) |
| BOOX NoteAir4C | **NA4C** | `1d36f870` | colour |
| BOOX Tab XC | **TXC** | `d852bed0` | colour |
| BOOX Go Color 7 Gen II | **GC7** | `98d56306` | 7" colour |
| BOOX Go 7 | **G7** | `17845014` | 7" mono |

**Phase 4 minimum: two panels — G102 (mono flagship) plus NA5C or P2P (colour).**

### Supernote — `SupernoteInkEngine` (Phase 5)

| Device | Nickname | Serial | Note |
|---|---|---|---|
| Supernote Nomad | **SNN** | `SN078D10012852` | `deviceType 3 / A5X2` — pen codes confirmed here |
| Supernote Manta | — | *(none recorded)* | Same firmware + chipset as Nomad; only screen size differs |

### Generic — `GenericInkEngine` (Phases 2–3)

| Device | Nickname | Serial |
|---|---|---|
| Samsung Galaxy S26 Ultra | **S26U** | `R3GL307HGDH` |
| Wacom Movink Pad 11 | **MIP11** | `5HL21V5007384` |
| Wacom Movink Pad 14 Pro | — | *(none recorded)* |
| Paper 7 | **P7** | `T1737BBR0327` |

---

## Which devices each phase needs

Mirrors PLAN.md §7 — change them in both places or they drift.

| Phase | Devices |
|---|---|
| 0 — Foundation | Any one device ✅ *(done on G10)* |
| 1 — Core model & API | None required ✅ *(done anyway on NA5C)* |
| 2 — Generic engine | A generic stylus tablet: S26U and/or MIP11 |
| 3 — Tooling & render fidelity | Same generic tablet |
| 4 — Onyx adapter | G102 **and** one colour panel (NA5C or P2P) |
| 5 — Supernote adapter | Nomad, plus Manta if present |
| 6 — Conformance harness | All three platforms |
| 7 — Packaging | One device per platform |
