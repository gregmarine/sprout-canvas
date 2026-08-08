package com.symmetricalpalmtree.sprout.canvas.onyx

import android.graphics.Rect

/**
 * Builds the two rectangle lists `TouchHelper.setLimitRect` is armed with.
 *
 * Kept as pure geometry so the one rule that is easy to get wrong — and produced a canvas that
 * refused to capture in a region where nothing was drawn, in the reference project — can be
 * asserted by a test rather than rediscovered on a panel.
 */
internal object OnyxLimitRects {

    /**
     * An off-screen rectangle that excludes nothing.
     *
     * ### Why "no exclusions" cannot be sent as no exclusions
     *
     * `TouchHelper` treats an **empty** exclusion list as a no-op: it ignores the call entirely and
     * keeps whatever zone was previously active — including one the SDK restored from its own
     * persisted state, set by a different session of a different app. So dismissing the last
     * toolbar over a canvas would leave a dead region behind it with nothing on screen to explain
     * the strip of panel that will not take ink.
     *
     * A single-entry list containing a rectangle nobody can touch forces the SDK to process the
     * update, and clears the previous zone as a side effect (PLAN.md §3.7).
     */
    val NOTHING_EXCLUDED: Rect = Rect(-1, -1, 0, 0)

    /**
     * The exclusion list to hand the SDK for [zones].
     *
     * @param zones exclusion zones in whichever space the pipeline is armed in. May be empty.
     */
    fun excludeRects(zones: List<Rect>): List<Rect> {
        if (zones.isEmpty()) return listOf(Rect(NOTHING_EXCLUDED))
        // Copied, because the SDK holds the list it is given and the caller's rects are recomputed
        // on every layout pass.
        return zones.map { Rect(it) }
    }
}
