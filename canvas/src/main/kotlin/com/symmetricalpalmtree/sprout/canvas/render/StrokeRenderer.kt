package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.RestrictTo
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * Everything a renderer needs to know about the surface it is drawing on.
 *
 * Widths reach the library in dp and have to become px somewhere; this is that boundary, and it is
 * the only place a renderer is allowed to care about density.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class RenderContext(
    /** Screen density — px per dp. */
    public val density: Float,
) {
    /** Converts a dp measurement to px. */
    public fun toPx(dp: Float): Float = dp * density
}

/**
 * Draws one [SproutPen]'s ink.
 *
 * ### One renderer, both paths
 *
 * The same renderer draws the *live* stroke under the pen and the *committed* stroke afterwards. It
 * has to: a stroke that changed appearance the instant the pen lifted would be the most obvious
 * possible bug, and the ingest guarantee — `setStrokes(getStrokes())` is a visual no-op (G4) — is
 * only true if re-rendering captured samples reproduces exactly what capture drew.
 *
 * ### Determinism is a requirement, not a nicety
 *
 * Given the same samples, tool, seed and context, a renderer must produce the same pixels every
 * time. Texture pens scatter grain from a generator seeded by the stroke's own id for exactly this
 * reason — see [GrainSolver].
 *
 * Internal to the library and its vendor adapters. A host app selects a pen; it does not supply one.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface StrokeRenderer {

    /**
     * Draws [samples] onto [canvas].
     *
     * @param seed a stable value derived from the stroke's id, for renderers whose appearance
     *   involves randomness. The same stroke must always produce the same seed.
     */
    public fun draw(
        canvas: Canvas,
        samples: StrokeSamples,
        tool: ToolSpec,
        seed: Int,
        context: RenderContext,
    )

    /**
     * How far, in px, the drawn ink can extend beyond the stroke's centreline bounds.
     *
     * Used to grow a stroke's stored centreline box into the area it actually paints — which is
     * what an erase hit-test and a damage rect both need. Overestimating costs a few wasted pixels;
     * underestimating leaves ink behind after an erase.
     */
    public fun outsetPx(tool: ToolSpec, context: RenderContext): Float
}

/**
 * Which renderer draws which pen.
 *
 * ### Why this is an instance and not a singleton
 *
 * Two canvases in one process can be running different engines — that is not a hypothetical, it is
 * how the conformance harness judges whether the hardware and software ink paths agree, by forcing
 * one canvas onto the generic engine beside another on the vendor path (PLAN.md §4.3). A global
 * renderer table would make the second canvas's overrides silently reshape the first one's ink. So
 * each canvas owns its own table, and its renderers own their own reusable geometry buffers.
 *
 * Vendor adapters may substitute their own renderers for closer agreement with the firmware's ink
 * (PLAN.md §3.8); [setRenderer] is where that happens.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class StrokeRendererRegistry {

    private val renderers = HashMap<SproutPen, StrokeRenderer>(SproutPen.entries.size)

    init {
        // Built per instance, deliberately: every renderer holds reusable solver buffers, and
        // sharing those across canvases would mean two views solving into the same arrays.
        val evenWidth = EvenWidthRenderer()
        val ribbon = RibbonRenderer()
        val texture = TextureRenderer()
        val calligraphy = CalligraphyRenderer()

        // A `when` over the enum with no `else`: a new pen must be assigned a renderer or the build
        // fails. A pen that quietly drew nothing is the exact silent no-op this library refuses to
        // ship (PLAN.md §5.5).
        SproutPen.entries.forEach { pen ->
            renderers[pen] = when (pen) {
                SproutPen.BALLPOINT,
                SproutPen.MARKER,
                SproutPen.HIGHLIGHTER,
                SproutPen.DASHED,
                -> evenWidth

                SproutPen.FOUNTAIN,
                SproutPen.BRUSH,
                -> ribbon

                SproutPen.PENCIL,
                SproutPen.CHARCOAL,
                -> texture

                SproutPen.CALLIGRAPHY -> calligraphy
            }
        }
    }

    /** The renderer for [pen]. Never null, for any pen. */
    public fun rendererFor(pen: SproutPen): StrokeRenderer = renderers.getValue(pen)

    /** Replaces the renderer for [pen] — for a vendor adapter matching its own firmware's ink. */
    public fun setRenderer(pen: SproutPen, renderer: StrokeRenderer) {
        renderers[pen] = renderer
    }
}

/**
 * The one place a stroke's stored colour becomes a colour to paint with.
 *
 * ### The rule this enforces
 *
 * **A stroke's stored colour is never rewritten.** A red stroke captured on a greyscale panel stays
 * red in the data and merely renders grey; a colour below the Onyx live-preview floor is captured
 * and committed in full colour even though the firmware previewed it as black. Device adaptation
 * happens to *pixels*, never to data (PLAN.md §3.6).
 *
 * The single adjustment made here is the highlighter's default translucency, and it applies only
 * when the app's colour is fully opaque — that is, only when the app has expressed no opinion. An
 * app that sets its own alpha has stated what it wants and is left alone.
 */
/**
 * The widest the ink can get, in px — nominal width, scaled by the pen's multiplier, at the top of
 * its pressure range. The basis of every renderer's [StrokeRenderer.outsetPx].
 */
internal fun maxDrawnWidthPx(tool: ToolSpec, tuning: PenTuning, context: RenderContext): Float =
    context.toPx(tool.widthDp) * tuning.widthMultiplier * tuning.maxWidthFactor

@ColorInt
internal fun resolvePaintColor(tool: ToolSpec, tuning: PenTuning): Int {
    if (tuning.defaultAlpha >= 255) return tool.color
    if (Color.alpha(tool.color) != 255) return tool.color
    return Color.argb(
        tuning.defaultAlpha,
        Color.red(tool.color),
        Color.green(tool.color),
        Color.blue(tool.color),
    )
}
