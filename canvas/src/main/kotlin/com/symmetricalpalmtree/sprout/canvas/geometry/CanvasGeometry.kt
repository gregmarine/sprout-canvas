package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.Rect

/**
 * The coordinate arithmetic behind bounds and exclusion zones, as pure functions.
 *
 * ### Why this is separated from the view
 *
 * Every rule here is a rule that was learned the hard way somewhere, and each one is one sign flip
 * away from a bug that only shows up on a device: a stroke that lands an inch from the pen, ink
 * under a toolbar, capture outside the canvas. Pure functions over plain rects can be tested
 * exhaustively on the JVM, including the cases nobody thinks to try on hardware — a zero-size
 * canvas, a zone entirely off-screen, a zone that swallows the canvas whole.
 */
internal object CanvasGeometry {

    /**
     * The region the engine may capture in: the canvas's own rect, intersected with whatever part
     * of it is actually visible.
     *
     * @param width canvas width in px.
     * @param height canvas height in px.
     * @param visibleFrame the visible portion in **canvas coordinates**, or null when the whole
     *   canvas is visible.
     *
     * Returns an empty rect when nothing is visible — a canvas scrolled off-screen must capture
     * nothing, not everything.
     */
    fun limitRect(width: Int, height: Int, visibleFrame: Rect?): Rect {
        val full = Rect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        if (full.isEmpty) return Rect()
        if (visibleFrame == null) return full
        val clipped = Rect(full)
        return if (clipped.intersect(visibleFrame)) clipped else Rect()
    }

    /**
     * Maps a view's on-screen rectangle into canvas coordinates.
     *
     * The offset is the entire point: firmware ink pipelines paint in **screen** coordinates while
     * `MotionEvent` arrives in **view** coordinates. Mixing the two produces a stroke that is baked
     * a fixed distance from where it was drawn — the tell is ink that visibly *jumps* on pen-lift.
     */
    fun mapToCanvas(
        viewLeftOnScreen: Int,
        viewTopOnScreen: Int,
        viewWidth: Int,
        viewHeight: Int,
        canvasLeftOnScreen: Int,
        canvasTopOnScreen: Int,
    ): Rect {
        val left = viewLeftOnScreen - canvasLeftOnScreen
        val top = viewTopOnScreen - canvasTopOnScreen
        return Rect(left, top, left + viewWidth, top + viewHeight)
    }

    /**
     * Clips [zone] to the canvas rect, returning null when the two do not overlap at all.
     *
     * A zone outside the canvas is not an error — a host may well register a toolbar that sits
     * beside the canvas rather than over it — it simply has nothing to exclude.
     */
    fun clipToCanvas(zone: Rect, canvasWidth: Int, canvasHeight: Int): Rect? {
        if (zone.isEmpty) return null
        val clipped = Rect(zone)
        return if (clipped.intersect(0, 0, canvasWidth, canvasHeight)) clipped else null
    }

    /**
     * Removes empty zones and zones wholly contained in another.
     *
     * Deliberately **not** a general rectangle union: merging two overlapping rects into their
     * bounding box would exclude area that neither of them covers, and silently blocking capture
     * where the host never put any chrome is a worse failure than sending one extra rect. Fewer
     * rects is a nicety; a correct region is not.
     */
    fun coalesce(zones: List<Rect>): List<Rect> {
        val nonEmpty = zones.filterNot { it.isEmpty }
        if (nonEmpty.size < 2) return nonEmpty
        val result = ArrayList<Rect>(nonEmpty.size)
        for (candidate in nonEmpty) {
            val containedInKept = result.any { it.contains(candidate) }
            if (containedInKept) continue
            result.removeAll { candidate.contains(it) }
            result += candidate
        }
        return result
    }
}
