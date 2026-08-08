package com.symmetricalpalmtree.sprout.canvas.engine

import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.EraserMode
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkChannels
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen

/**
 * What the selected engine can actually do on the device it is running on.
 *
 * ### Capability is measured, never assumed
 *
 * Vendor SDKs fail silently. `TouchHelper.setStrokeStyle(int)` is a pass-through to a reflected
 * hidden framework method whose reflection helper **swallows failures** — no exception, no return
 * value, no log — and on a non-Onyx device the underlying implementation is an empty method, so the
 * whole call simply disappears. There is no `isStrokeStyleSupported()` anywhere to ask.
 *
 * So an engine fills this in from what it probed on *this* device, and the honest answer is
 * published rather than a hopeful one. An app can then annotate a tool picker instead of shipping a
 * pen that quietly does nothing.
 *
 * Read it from [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView.capabilities].
 */
public class CanvasCapabilities(

    /** The engine these capabilities describe — [EngineInfo.id]. */
    public val engineId: String,

    /**
     * The per-sample channels this engine can report on this device, as an [InkChannel] bitmask.
     *
     * A channel absent here will be absent from every stroke this canvas captures.
     */
    @InkChannels public val channels: Int,

    /**
     * Whether stroke colour alpha is honoured when rendering.
     *
     * A stroke's stored colour keeps its alpha regardless — the data is never rewritten to suit a
     * device. This says only whether the pixels will show it.
     */
    public val supportsAlpha: Boolean,

    /** Which [EraserMode]s this engine implements. `STROKE` on every engine in v1. */
    public val supportedEraserModes: Set<EraserMode>,

    /** Per-pen reproduction quality. Must cover every [SproutPen] — see the `init` block. */
    penFidelities: Map<SproutPen, PenFidelity>,

    /**
     * True when live ink is painted by the panel's own hardware rather than through the Android
     * view system.
     *
     * The single most consequential fact about an engine. When it is true the library must **not**
     * draw the active stroke itself — the firmware already did, at sub-frame latency, and drawing
     * over it produces a doubled, laggy stroke that feels broken. It also means a screenshot cannot
     * capture live ink: the firmware paints straight to the panel, outside the Android framebuffer,
     * so every live-ink check has to be confirmed by a human.
     */
    public val liveInkIsHardware: Boolean = false,

    /**
     * Whether the **live** stroke shows a translucent colour while the pen is on the glass.
     *
     * Distinct from [supportsAlpha], which describes the ink that ends up on the canvas. On an
     * engine whose live ink is painted by firmware these are two different pieces of hardware
     * answering two different questions, and on BOOX they disagree: the committed stroke honours
     * alpha exactly, and the firmware overlay — measured on a NoteAir5C — paints **nothing at all**
     * for a translucent colour. Not a solid stroke, not a wrong colour; no ink, no error.
     *
     * Where that is false the adapter forces the live preview opaque, so a highlighter writes as a
     * solid band that settles to translucent on pen-up. That is the best behaviour available and it
     * is still a visible disagreement between what the user writes and what they end up with, which
     * is why it is published rather than smoothed over. A host can use it to explain the effect, or
     * to prefer an opaque tool while writing.
     *
     * `true` on any engine that draws its own live ink, where one renderer serves both paths.
     */
    public val livePreviewSupportsAlpha: Boolean = true,

    /**
     * Live-preview colour floor, `0` when there is none.
     *
     * On Onyx Kaleido panels the firmware overlay paints a colour as **black** once its dominant
     * RGB channel drops below roughly this value. It is a *preview* limitation only: the stroke is
     * captured, stored and committed in its true colour, so the ink corrects itself on pen-up.
     *
     * Reported so an app can explain the effect. Never a reason to refuse a colour.
     */
    public val livePreviewColorFloor: Int = 0,

    /** What the engine measured about this digitizer. */
    public val calibration: DeviceCalibration = DeviceCalibration.UNKNOWN,
) {

    /**
     * Copied at construction. Capabilities are read on every tool-picker refresh and must not be
     * something a caller can change afterwards by holding on to the map it passed in.
     */
    private val fidelities: Map<SproutPen, PenFidelity> = penFidelities.toMap()

    init {
        // Adding a SproutPen without deciding what it does on this engine is a bug, and it is the
        // kind that hides: the pen would simply be missing from a picker, or throw much later at
        // render time. Fail here, loudly, at construction (PLAN.md §4.4).
        val missing = SproutPen.entries.filter { it !in fidelities }
        require(missing.isEmpty()) {
            "engine '$engineId' declares no fidelity for: ${missing.joinToString()}"
        }
        require(InkChannel.isValid(channels)) {
            "unknown channel bits in 0x${channels.toString(16)}"
        }
    }

    /** How faithfully this engine reproduces [pen] on this device. Never null, for any pen. */
    public fun fidelity(pen: SproutPen): PenFidelity = fidelities.getValue(pen)

    /** True when this engine implements [mode]. */
    public fun supports(mode: EraserMode): Boolean = mode in supportedEraserModes

    /** True when this engine reports [channel]. Convenience for [InkChannel.contains]. */
    public fun reports(@InkChannels channel: Int): Boolean =
        InkChannel.contains(channels, channel)

    /** Every pen whose reproduction is less than [PenFidelity.NATIVE] here. */
    public fun pensBelowNative(): List<SproutPen> =
        SproutPen.entries.filter { fidelity(it) != PenFidelity.NATIVE }

    /** A multi-line summary for a device report. */
    public fun describe(): String = buildString {
        appendLine("engine:            $engineId")
        appendLine("channels:          ${InkChannel.describe(channels)}")
        appendLine("alpha:             $supportsAlpha")
        appendLine("alpha (live):      $livePreviewSupportsAlpha")
        appendLine("live ink:          ${if (liveInkIsHardware) "hardware overlay" else "software"}")
        appendLine("colour floor:      ${if (livePreviewColorFloor == 0) "none" else "$livePreviewColorFloor (live preview)"}")
        appendLine("eraser modes:      ${supportedEraserModes.joinToString()}")
        appendLine("max pressure:      ${calibration.maxPressure}")
        appendLine("tilt units known:  ${calibration.tiltUnitsKnown}")
        appendLine("digitizer:         ${calibration.digitizerWidth}×${calibration.digitizerHeight}")
        appendLine("density:           ${calibration.densityDpi} dpi")
        appendLine("pens:")
        SproutPen.entries.forEach { appendLine("  ${it.name.padEnd(12)} ${fidelity(it)}") }
    }

    override fun toString(): String =
        "CanvasCapabilities(engine=$engineId, channels=${InkChannel.describe(channels)}, " +
            "hardwareLiveInk=$liveInkIsHardware)"

    public companion object {
        /** Builds a map giving every [SproutPen] the same [fidelity]. */
        public fun uniformFidelity(fidelity: PenFidelity): Map<SproutPen, PenFidelity> =
            SproutPen.entries.associateWith { fidelity }
    }
}
