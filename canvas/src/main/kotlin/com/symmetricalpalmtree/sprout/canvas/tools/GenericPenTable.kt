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
 * ### Re-examined in Phase 3, and deliberately left alone
 *
 * A table where every row says the same thing invites the suspicion that nobody decided it, so:
 * this was looked at again once all nine renderers existed and had been drawn with a real pen, and
 * it is unchanged on purpose.
 *
 * [PenFidelity] answers "how faithfully does *this engine* reproduce the pen a host asked for" — it
 * is a statement about the path, not a rating of how good the ink looks. Downgrading the pencil
 * here because its grain could be prettier would tell a host app that some other path on this device
 * would do better, and on an ordinary tablet there is no other path. The honest signal for
 * appearance is the golden suite, which now pins each texture pen across the width ladder, not a
 * capability flag a host would have to guess the meaning of.
 *
 * **What would change it:** a pen the software renderer genuinely cannot express. There is no such
 * pen today — every one of the nine has a renderer and none of them silently no-ops (PLAN.md §5.5).
 * The other trigger is Phase 4/5: once the firmware paths exist to be compared against, this table
 * is re-read alongside `OnyxPenTable` and `SupernotePenTable`, where the interesting fidelity
 * differences actually live.
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
