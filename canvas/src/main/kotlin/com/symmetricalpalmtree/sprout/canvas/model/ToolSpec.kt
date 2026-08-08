package com.symmetricalpalmtree.sprout.canvas.model

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * What the canvas draws with: a pen, a width and a colour.
 *
 * Immutable and cheap — arm a canvas by assigning a new one:
 *
 * ```
 * canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 2f, color = Color.BLACK)
 * ```
 *
 * The same three lines apply on every device. Which firmware pen that becomes, and what unit the
 * width is converted into, is the library's problem.
 *
 * ### Colour is data, not a rendering hint
 *
 * A stroke's stored colour is **never** rewritten to suit a device. A red stroke captured on a
 * greyscale panel stays red in the data and merely renders grey. Two consequences worth knowing:
 *
 *  - **Alpha** is honoured where the engine supports it; check
 *    [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.supportsAlpha].
 *  - **The Onyx live-preview colour floor** paints a colour as black once its dominant RGB channel
 *    drops below roughly 180. That is a *preview* limitation — the stroke is captured, stored and
 *    committed in its true colour. It is reported through
 *    [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.livePreviewColorFloor], never
 *    as a reason to refuse a colour.
 */
public data class ToolSpec(

    /** Which standardized pen. */
    public val pen: SproutPen = SproutPen.DEFAULT,

    /**
     * Nominal stroke width in **dp**, greater than zero.
     *
     * Nominal, because pens are not all drawn at their nominal width: texture pens in particular
     * are scaled up so their grain has room to exist. The library applies those per-pen multipliers;
     * an app sets the width it means.
     */
    public val widthDp: Float = SproutWidth.DEFAULT.dp,

    /** ARGB colour. Stored verbatim — see the class KDoc. */
    @ColorInt public val color: Int = Color.BLACK,
) {

    init {
        require(widthDp > 0f && widthDp.isFinite()) {
            "widthDp must be a finite value greater than zero, was $widthDp"
        }
    }

    /** Builds a tool from a preset rung of the width ladder. */
    public constructor(
        pen: SproutPen,
        width: SproutWidth,
        @ColorInt color: Int = Color.BLACK,
    ) : this(pen, width.dp, color)

    /** The colour's alpha channel, `0..255`. */
    public val alpha: Int get() = Color.alpha(color)

    public companion object {
        /** [SproutPen.BALLPOINT] at [SproutWidth.MEDIUM] in opaque black. */
        public val DEFAULT: ToolSpec = ToolSpec()
    }
}
