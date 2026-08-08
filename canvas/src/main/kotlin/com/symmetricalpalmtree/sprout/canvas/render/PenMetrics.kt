package com.symmetricalpalmtree.sprout.canvas.render

import androidx.annotation.ColorInt
import androidx.annotation.RestrictTo
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * The two tuning numbers a vendor adapter has to agree with, and the reason it has to.
 *
 * ### The disagreement this closes
 *
 * On e-ink two entirely separate pieces of code draw the same stroke: the firmware overlay while the
 * pen is down, and the library's committed renderer from the moment it lifts. An adapter arms the
 * firmware with a width and a colour, and if those are not the *same* numbers its committed renderer
 * will use, every stroke visibly changes weight or shade the instant the pen leaves the glass.
 *
 * That is not hypothetical. [PenTuning] draws a marker at 1.75× its nominal width and a highlighter
 * at 4×, because that is what makes them read as a marker and a highlighter. An adapter arming the
 * overlay from a table of BOOX's own multipliers — which cover only charcoal and brush — would set
 * the firmware to draw a 1× marker under a 1.75× committed one.
 *
 * So the renderers' numbers are published here, to the library group only, and there is exactly one
 * copy of each. [com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable] still records BOOX's own
 * multipliers as a documented fact about the vendor; this is what anything arming hardware reads.
 *
 * Internal to the library and its adapters. A host app never sees a drawn width — it sets a nominal
 * one and the library makes it mean the same thing on every device.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PenMetrics {

    /**
     * Drawn width ÷ nominal width for [pen] — what the ink actually measures on the panel.
     *
     * Corrective, not decorative: a texture pen drawn at its nominal width has no room for its
     * grain and comes out solid (PLAN.md §5.7).
     */
    public fun widthMultiplier(pen: SproutPen): Float = PenTuning.forPen(pen).widthMultiplier

    /**
     * The alpha this pen paints with when the app has expressed no opinion, `0..255`.
     *
     * Only the highlighter is below opaque. An app that set its own alpha has said what it wants and
     * is left alone, which is why this takes a pen rather than a colour — see [paintColor].
     */
    public fun defaultAlpha(pen: SproutPen): Int = PenTuning.forPen(pen).defaultAlpha

    /**
     * The colour [tool] actually paints with, after the pen's default translucency.
     *
     * **A stroke's stored colour is never rewritten** — this is the colour to hand a paint or a
     * firmware overlay, derived on the way to pixels and thrown away afterwards. A red stroke
     * captured on a greyscale panel stays red in the data and merely renders grey (PLAN.md §3.6).
     */
    @ColorInt
    public fun paintColor(tool: ToolSpec): Int = resolvePaintColor(tool, PenTuning.forPen(tool.pen))
}
