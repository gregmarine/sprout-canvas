package com.symmetricalpalmtree.sprout.canvas.onyx

import android.graphics.Canvas
import android.graphics.Paint
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.BallpointPenRenderWrapper
import com.onyx.android.sdk.pen.CharcoalNeoPenRender
import com.onyx.android.sdk.pen.NeoBrushPen
import com.onyx.android.sdk.pen.NeoCharcoalPenV2
import com.onyx.android.sdk.pen.NeoFountainPenV2
import com.onyx.android.sdk.pen.NeoMarkerPen
import com.onyx.android.sdk.pen.NeoPen
import com.onyx.android.sdk.pen.NeoPenConfig
import com.onyx.android.sdk.pen.NeoPenRender
import com.onyx.android.sdk.pen.NeoPencilPen
import com.onyx.android.sdk.pen.NeoSquarePen
import com.symmetricalpalmtree.sprout.canvas.SproutLog
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.PenMetrics
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRenderer
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable

/**
 * Draws a committed stroke through the SDK's own `NeoPen` solver — the closest this library can get
 * to the ink the firmware overlay painted while the pen was down.
 *
 * Active only under [OnyxRenderMode.Mode.NEO_PEN]; see that class for why the choice is a switch
 * rather than a decision.
 *
 * ### A vendor solver may not take the canvas down with it
 *
 * Every path through here is wrapped, and any failure falls back to the library's own renderer for
 * the same pen — permanently, after one logged message. Three things make that non-negotiable:
 *
 *  - `NEOPEN_PEN_TYPE_BRUSH_SIGN` was found to construct and then **throw on render**, so "the pen
 *    was created" is not evidence that drawing it will work.
 *  - The bitmap-backed pens resolve their grain through `ResManager`, which [OnyxSdk] initializes —
 *    but a firmware that ships different SDK resources is exactly the case nobody can test for.
 *  - This runs while recording the committed display list. A throw there is not a missing stroke,
 *    it is a dead canvas holding a page of a user's handwriting.
 *
 * ### The cost, stated plainly
 *
 * A native pen is built and destroyed for every stroke drawn. That is the SDK's own one-off
 * rendering pattern, and it is affordable here only because committed content is re-recorded when
 * it *changes* rather than per frame — a page of ink redrawn every frame through this would not be.
 * If this mode ever becomes the default, the pens want caching by (type, width, colour) first.
 *
 * @param fallback the library's renderer for this pen, used before the SDK is asked and after it
 *   has failed.
 */
internal class NeoPenStrokeRenderer(
    private val pen: SproutPen,
    private val neoPenType: Int,
    private val calibration: DeviceCalibration,
    private val context: RenderContext,
    private val fallback: StrokeRenderer,
) : StrokeRenderer {

    private val paint = Paint().apply { isAntiAlias = true }
    private val points = ArrayList<TouchPoint>()

    /** Set after a failure. One bad pen must not produce one log line per stroke, per frame. */
    private var disabled = false

    override fun draw(
        canvas: Canvas,
        samples: StrokeSamples,
        tool: ToolSpec,
        seed: Int,
        context: RenderContext,
    ) {
        if (disabled || samples.count == 0) {
            fallback.draw(canvas, samples, tool, seed, context)
            return
        }

        var neoPen: NeoPen? = null
        try {
            val config = configFor(tool)
            neoPen = createPen(config)
            paint.color = PenMetrics.paintColor(tool)
            paint.style = paintStyle()
            renderFor(neoPen, config).render(canvas, paint, toTouchPoints(samples))
        } catch (t: Throwable) {
            disabled = true
            SproutLog.e(
                "the SDK's NeoPen renderer for $pen failed; this canvas will draw $pen with the " +
                    "library's own renderer from now on",
                t,
            )
            fallback.draw(canvas, samples, tool, seed, context)
        } finally {
            // A native handle, not a Java object. Leaking one per stroke would exhaust the pen pool
            // on a page of ink.
            runCatching { neoPen?.destroy() }
        }
    }

    override fun outsetPx(tool: ToolSpec, context: RenderContext): Float =
        fallback.outsetPx(tool, context)

    /**
     * Builds the SDK's pen configuration for [tool].
     *
     * Two fields are worth explaining:
     *
     *  - **`maxTouchPressure` is 1**, not the device's 4095-or-4096. Sample pressure is already
     *    normalized to `0..1` by the time it reaches a renderer, and the SDK's only use for the
     *    field is as a divisor. Handing it 1 and normalized pressure is arithmetically identical to
     *    handing it the device maximum and raw counts — and it means a stroke captured on one BOOX
     *    renders identically on another, which the round-trip would not.
     *  - **`scalePrecision` is set explicitly.** The reference survey found the point-based solvers
     *    flooding an entire canvas black at widths of about 12 and up, and named this field and the
     *    display scales as the untested suspects, because a solver handed an unscaled precision can
     *    return point sizes in the thousands and `PenPointResult` assigns `paint.strokeWidth`
     *    straight from the solved size. The SDK ships a helper to compute it and the survey's
     *    harness never called it. This calls it. Whether that is the fix is a device question, and
     *    it is one of the things Phase 4 is measuring.
     */
    private fun configFor(tool: ToolSpec): NeoPenConfig = NeoPenConfig().apply {
        type = neoPenType
        color = PenMetrics.paintColor(tool)
        width = context.toPx(tool.widthDp) * PenMetrics.widthMultiplier(tool.pen)
        maxTouchPressure = 1f
        dpi = calibration.densityDpi.toFloat().takeIf { it > 0f } ?: DEFAULT_DPI
        displayScaleX = 1f
        displayScaleY = 1f
        scalePrecision = NeoPenConfig.Companion.getPrecision(1f)
        // Tilt is reported by this pipeline but in an undocumented unit that differs by roughly a
        // hundred times between BOOX models, so feeding it into the solver's shape would produce a
        // different pen on different hardware. Left off until tilt is characterized per device.
        tiltEnabled = false
    }

    /**
     * Builds the native pen, or returns null for the one pen that builds its own.
     *
     * The ballpoint is the exception: `BallpointPenRenderWrapper.create(config)` constructs the pen
     * *and* the renderer that reads its outline results, so asking for a bare pen first would build
     * one nobody uses and — because nothing else in the SDK constructs pen type 8 — throw.
     */
    private fun createPen(config: NeoPenConfig): NeoPen? = when (neoPenType) {
        OnyxPenTable.NEOPEN_PEN_TYPE_BALLPOINT -> null
        OnyxPenTable.NEOPEN_PEN_TYPE_BRUSH -> NeoBrushPen.Companion.create(config)
        OnyxPenTable.NEOPEN_PEN_TYPE_MARKER -> NeoMarkerPen.Companion.create(config)
        OnyxPenTable.NEOPEN_PEN_TYPE_CHARCOAL_V2 -> NeoCharcoalPenV2.Companion.create(config)
        OnyxPenTable.NEOPEN_PEN_TYPE_FOUNTAIN_V2 -> NeoFountainPenV2.Companion.create(config)
        OnyxPenTable.NEOPEN_PEN_TYPE_PENCIL -> NeoPencilPen.Companion.create(config)
        OnyxPenTable.NEOPEN_PEN_TYPE_SQUARE -> NeoSquarePen.Companion.create(config)
        else -> error("no NeoPen constructor is known for type $neoPenType")
    }

    /**
     * The renderer that knows how to read this pen's results.
     *
     * The result shape differs per pen family, and reading one with the wrong reader throws — which
     * is how the survey established that `BRUSH_SIGN` is a genuinely distinct pen rather than an
     * alias. Stamped pens hand back bitmap positions, the ballpoint hands back an outline path, and
     * the rest hand back sized points.
     */
    private fun renderFor(neoPen: NeoPen?, config: NeoPenConfig): NeoPenRender {
        if (neoPenType == OnyxPenTable.NEOPEN_PEN_TYPE_BALLPOINT) {
            return BallpointPenRenderWrapper.Companion.create(config)
                ?: error("the SDK returned no ballpoint renderer")
        }
        val pen = neoPen ?: error("no pen was built for NeoPen type $neoPenType")
        return when (neoPenType) {
            OnyxPenTable.NEOPEN_PEN_TYPE_PENCIL -> NeoPencilPenRenderOf(pen)
            OnyxPenTable.NEOPEN_PEN_TYPE_CHARCOAL_V2 -> CharcoalNeoPenRender(pen)
            else -> NeoPenRender(pen)
        }
    }

    /** `PenPathResult` is an outline to fill; every other result is drawn as sized strokes. */
    private fun paintStyle(): Paint.Style =
        if (neoPenType == OnyxPenTable.NEOPEN_PEN_TYPE_BALLPOINT) {
            Paint.Style.FILL
        } else {
            Paint.Style.STROKE
        }

    /**
     * Converts the columnar samples back into the SDK's per-point objects.
     *
     * This allocation is the price of the vendor path and the reason the library's own model is
     * columnar in the first place: one object per point per stroke, which is exactly what
     * [StrokeSamples] exists to avoid on the capture path. The list is retained and reused between
     * strokes so the cost is paid once per *longest* stroke rather than once per stroke.
     */
    private fun toTouchPoints(samples: StrokeSamples): List<TouchPoint> {
        while (points.size < samples.count) points.add(TouchPoint())
        for (i in 0 until samples.count) {
            points[i].apply {
                x = samples.x[i]
                y = samples.y[i]
                pressure = samples.pressure?.get(i) ?: DEFAULT_PRESSURE
                size = samples.size?.get(i) ?: 0f
                tiltX = samples.tiltX?.get(i)?.toInt() ?: 0
                tiltY = samples.tiltY?.get(i)?.toInt() ?: 0
                timestamp = samples.timestampMs?.get(i) ?: 0L
            }
        }
        return points.subList(0, samples.count)
    }

    private companion object {
        /** Used when a stroke carries no pressure — mid-range, so a pressure pen still draws. */
        const val DEFAULT_PRESSURE = 0.5f

        /** The SDK's own default, for a device that did not report its density. */
        const val DEFAULT_DPI = 320f
    }
}

/**
 * `PencilNeoPenRender` needs the concrete pencil type, which its factory already returns.
 *
 * Extracted so the cast lives in one place with the reason attached rather than inline in a `when`.
 */
private fun NeoPencilPenRenderOf(neoPen: NeoPen): NeoPenRender =
    com.onyx.android.sdk.pen.PencilNeoPenRender(neoPen as NeoPencilPen)
