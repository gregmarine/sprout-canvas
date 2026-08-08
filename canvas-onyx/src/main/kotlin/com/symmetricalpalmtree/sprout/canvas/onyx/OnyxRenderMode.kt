package com.symmetricalpalmtree.sprout.canvas.onyx

import com.symmetricalpalmtree.sprout.canvas.SproutLog
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRenderer
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRendererRegistry
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable

/**
 * Which code draws a BOOX canvas's **committed** strokes — ours, or the SDK's.
 *
 * ### The question this exists to answer
 *
 * A BOOX draws every stroke twice through two unrelated code paths. While the pen is on the glass
 * the user watches the *firmware overlay*; from the moment it lifts they are looking at our
 * committed layer instead. Where the two disagree, the stroke changes shape under the user's hand.
 *
 * There are two credible ways to close that gap and no way to choose between them from a desk:
 *
 *  - **[Mode.SOFTWARE]** — the library's own nine renderers, the same ones every other device uses,
 *    tuned to sit as close to the firmware as they can. One visual identity everywhere: a stroke
 *    captured on a BOOX and handed to a canvas on a phone looks like the same stroke.
 *  - **[Mode.NEO_PEN]** — the SDK's own `NeoPen` solvers, which are what BOOX's firmware overlay is
 *    built from. As close to path-A agreement as it is possible to get, at the cost of ink that
 *    only exists on BOOX hardware.
 *
 * So this is a switch rather than a decision, and the Lab exposes it: draw the same handwriting
 * through both on the same panel, and look. That measurement is what Phase 4 is for.
 *
 * ### What is already known about the trade
 *
 * The reference project's five-device survey found the `NeoPen` point-based solvers **flood the
 * whole canvas black at widths of about 12 and above**, while the same pens render correctly at 8.
 * The survey's own conclusion was that the fault is more likely in how the pens were configured
 * than in the SDK — `scalePrecision` and `displayScaleX/Y` are the untested suspects — but it is
 * unresolved, and it is the reason [Mode.SOFTWARE] is the default rather than the other way round.
 *
 * Set before a canvas attaches; a canvas reads it once when its engine attaches.
 */
public object OnyxRenderMode {

    /** How committed strokes are drawn on a BOOX. */
    public enum class Mode {
        /** The library's own renderers — identical to every other device. The default. */
        SOFTWARE,

        /** The SDK's `NeoPen` solvers, for the closest possible agreement with the overlay. */
        NEO_PEN,
    }

    /**
     * The mode new canvases will use. [Mode.SOFTWARE] until something sets it.
     *
     * Read when a canvas's engine attaches, so changing it affects canvases created afterwards.
     * Detaching and re-attaching a canvas — or reassigning its `enginePreference` — re-reads it.
     */
    public var current: Mode = Mode.SOFTWARE
        set(value) {
            if (field == value) return
            field = value
            SproutLog.d { "onyx committed-layer renderer set to $value" }
        }

    /**
     * The renderer overrides for [mode], or an empty map when the library's own should be used.
     *
     * Only the pens the SDK actually has a solver for are overridden. `DASHED` is the one that never
     * is: the firmware dashes live ink but the SDK ships no dashed software pen, so its committed
     * stroke is ours in either mode.
     */
    internal fun renderersFor(
        mode: Mode,
        calibration: DeviceCalibration,
        context: RenderContext,
    ): Map<SproutPen, StrokeRenderer> {
        if (mode == Mode.SOFTWARE) return emptyMap()
        // One registry shared by every override, purely to supply their fallbacks. It is the same
        // table the canvas would have used had this mode never been selected, so a pen that falls
        // back lands exactly where it started rather than somewhere third.
        val software = StrokeRendererRegistry()
        return SproutPen.entries.mapNotNull { pen ->
            val type = OnyxPenTable.neoPenType(pen) ?: return@mapNotNull null
            pen to NeoPenStrokeRenderer(
                pen = pen,
                neoPenType = type,
                calibration = calibration,
                context = context,
                fallback = software.rendererFor(pen),
            )
        }.toMap()
    }
}
