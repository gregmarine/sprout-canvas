package com.symmetricalpalmtree.sprout.canvas.tools

import com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen

/**
 * What the software engine does with each [SproutPen].
 *
 * ### Why every pen is NATIVE here
 *
 * There is no platform ink engine on an ordinary tablet, so there is nothing for the software
 * renderer to be an approximation *of* — it is the reference the vendor paths are tuned to match.
 * A tool picker on a generic tablet should annotate nothing, and this table says so.
 *
 * Internal: the software renderers are `:canvas`'s own, and an app reads
 * [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.fidelity] rather than this.
 */
internal object GenericPenTable {

    fun fidelity(pen: SproutPen): PenFidelity {
        // `when` over the enum with no else branch: adding a pen without deciding what the software
        // renderer does with it must fail to compile, not default to something plausible.
        return when (pen) {
            SproutPen.BALLPOINT,
            SproutPen.FOUNTAIN,
            SproutPen.BRUSH,
            SproutPen.MARKER,
            SproutPen.HIGHLIGHTER,
            SproutPen.PENCIL,
            SproutPen.CHARCOAL,
            SproutPen.CALLIGRAPHY,
            SproutPen.DASHED,
            -> PenFidelity.NATIVE
        }
    }

    /** The fidelity map for the software engine's [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities]. */
    fun fidelities(): Map<SproutPen, PenFidelity> = SproutPen.entries.associateWith(::fidelity)
}
