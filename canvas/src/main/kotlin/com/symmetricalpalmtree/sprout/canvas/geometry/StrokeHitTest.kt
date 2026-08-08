package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.PointF
import android.graphics.RectF
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke

/**
 * Decides which strokes an erase gesture touched.
 *
 * ### Why the host owns this and not the hardware
 *
 * No platform's firmware knows which stroke a pixel belonged to — not even the ones that painted the
 * pixel themselves. An e-ink panel's ink overlay is a bitmap; it has no notion of strokes at all. So
 * erase is reported to the library as a *path*, on every engine, and the hit-test happens here where
 * the stroke data actually lives (PLAN.md §3.6).
 *
 * ### Why the bounding box comes first
 *
 * An erase gesture fires a dozen times a second and every one of them is compared against the whole
 * canvas. A page can hold thousands of strokes with thousands of samples each; comparing every erase
 * point against every sample would be millions of distance tests per gesture. Each stroke carries a
 * bounding box computed once at construction, so the overwhelming majority are rejected in four
 * float comparisons and never walked at all.
 */
internal object StrokeHitTest {

    /**
     * The strokes in [strokes] that the erase path passes within [radiusPx] of.
     *
     * @param path erase points in canvas coordinates. Treated as a polyline, not a set of dots —
     *   a fast swipe delivers points far apart, and testing only the points would let a stroke slip
     *   between two of them untouched.
     * @param radiusPx the eraser's own radius.
     * @param strokeOutset how far the ink extends past the centreline for a given stroke, from the
     *   renderer that draws it. A 12 dp highlighter has to be erasable by touching the mark the user
     *   can see, not the invisible line through the middle of it.
     */
    fun strokesTouching(
        strokes: Collection<InkStroke>,
        path: List<PointF>,
        radiusPx: Float,
        strokeOutset: (InkStroke) -> Float,
    ): List<InkStroke> {
        if (strokes.isEmpty() || path.isEmpty()) return emptyList()

        val eraseBounds = RectF(path[0].x, path[0].y, path[0].x, path[0].y)
        for (i in 1 until path.size) {
            val p = path[i]
            if (p.x < eraseBounds.left) eraseBounds.left = p.x
            if (p.x > eraseBounds.right) eraseBounds.right = p.x
            if (p.y < eraseBounds.top) eraseBounds.top = p.y
            if (p.y > eraseBounds.bottom) eraseBounds.bottom = p.y
        }

        val strokeBounds = RectF()
        val hits = ArrayList<InkStroke>()
        for (stroke in strokes) {
            if (stroke.isEmpty) continue
            val reach = radiusPx + strokeOutset(stroke)
            stroke.getBounds(strokeBounds)
            strokeBounds.inset(-reach, -reach)
            if (!RectF.intersects(strokeBounds, eraseBounds)) continue
            if (touches(stroke, path, reach)) hits += stroke
        }
        return hits
    }

    /**
     * True when any segment of the erase path comes within [reach] of any segment of [stroke].
     *
     * **Both** sides are walked as polylines, and that is not symmetry for its own sake. A fast
     * swipe delivers erase points far apart, so testing only those points would let a stroke slip
     * between two of them; a straight stroke may be captured as two samples a hand's width apart,
     * so testing only *its* samples would let the eraser pass through the middle of it. Each
     * failure looks like ink that "sometimes will not erase", and they have different causes.
     *
     * A single point on either side is treated as a zero-length segment, which the distance
     * function already handles — so a dot is erasable and a tap of the eraser erases.
     */
    private fun touches(stroke: InkStroke, path: List<PointF>, reach: Float): Boolean {
        val samples = stroke.samples
        val reachSquared = reach * reach
        val strokeSegments = maxOf(samples.count - 1, 1)
        val eraseSegments = maxOf(path.size - 1, 1)

        for (s in 0 until strokeSegments) {
            val ax = samples.x[s]
            val ay = samples.y[s]
            val bx = samples.x[minOf(s + 1, samples.count - 1)]
            val by = samples.y[minOf(s + 1, samples.count - 1)]

            for (e in 0 until eraseSegments) {
                val start = path[e]
                val end = path[minOf(e + 1, path.size - 1)]
                if (squaredDistanceBetweenSegments(
                        ax, ay, bx, by,
                        start.x, start.y, end.x, end.y,
                    ) <= reachSquared
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Squared distance between two segments.
     *
     * Crossing segments are the case the endpoint distances miss entirely: an eraser swiped
     * straight across a stroke can have both of its endpoints far away from both of the stroke's,
     * and still pass right through it.
     */
    private fun squaredDistanceBetweenSegments(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
    ): Float {
        if (segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            squaredDistanceToSegment(ax, ay, cx, cy, dx, dy),
            squaredDistanceToSegment(bx, by, cx, cy, dx, dy),
            squaredDistanceToSegment(cx, cy, ax, ay, bx, by),
            squaredDistanceToSegment(dx, dy, ax, ay, bx, by),
        )
    }

    /** True when the two segments properly intersect. Collinear overlap is left to the distances. */
    private fun segmentsCross(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    /** Which side of the line `(ax, ay) → (bx, by)` the point `(px, py)` falls on. */
    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    /** Squared distance from `(px, py)` to the segment `(ax, ay) → (bx, by)`. */
    private fun squaredDistanceToSegment(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val lengthSquared = abx * abx + aby * aby
        // A degenerate segment — two identical samples — is just a point.
        val t = if (lengthSquared <= 0f) {
            0f
        } else {
            (((px - ax) * abx + (py - ay) * aby) / lengthSquared).coerceIn(0f, 1f)
        }
        val dx = px - (ax + abx * t)
        val dy = py - (ay + aby * t)
        return dx * dx + dy * dy
    }
}
