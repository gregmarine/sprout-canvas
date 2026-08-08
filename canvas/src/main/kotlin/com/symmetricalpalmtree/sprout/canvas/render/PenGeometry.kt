package com.symmetricalpalmtree.sprout.canvas.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The outline of a variable-width stroke, as a closed polygon.
 *
 * A pressure-responsive pen cannot be drawn as a stroked path: `Paint.strokeWidth` is one number for
 * the whole path, and the entire point of a fountain nib is that the number changes along it. So the
 * ribbon is built as an explicit outline — offset left along the stroke, back along the right — and
 * filled. The caps are drawn separately by the renderer, which is also what makes a one-sample dot
 * come out round instead of invisible.
 */
internal class RibbonSolver {

    /** Outline vertices as `x, y` pairs, valid in `0 until pointCount * 2`. */
    var outline: FloatArray = FloatArray(INITIAL_CAPACITY)
        private set

    /** Number of `(x, y)` vertices in [outline]. Always `2 ×` the solved centreline, or `0`. */
    var pointCount: Int = 0
        private set

    /**
     * Builds the outline of [stroke].
     *
     * A centreline of fewer than two points produces no outline at all — there is no direction to
     * offset perpendicular to, and the renderer draws a dot instead.
     */
    fun solve(stroke: StrokeSolver) {
        pointCount = 0
        val n = stroke.count
        if (n < 2) return

        ensureCapacity(n * 4)
        val x = stroke.x
        val y = stroke.y

        var normalX = 0f
        var normalY = 0f
        for (i in 0 until n) {
            // The tangent at an interior point spans its neighbours rather than one segment, so a
            // corner produces one averaged normal instead of two conflicting ones and the outline
            // does not pinch.
            val ax = if (i == 0) x[0] else x[i - 1]
            val ay = if (i == 0) y[0] else y[i - 1]
            val bx = if (i == n - 1) x[n - 1] else x[i + 1]
            val by = if (i == n - 1) y[n - 1] else y[i + 1]

            val tx = bx - ax
            val ty = by - ay
            val len = sqrt(tx * tx + ty * ty)
            if (len > 0f) {
                normalX = -ty / len
                normalY = tx / len
            }
            // len == 0 keeps the previous normal: duplicate points survive decimation only at the
            // ends of a stroke, and inheriting is better than emitting a zero-width pinch there.

            val half = stroke.width[i] * 0.5f
            val leftIndex = i * 2
            val rightIndex = (2 * n - 1 - i) * 2
            outline[leftIndex] = x[i] + normalX * half
            outline[leftIndex + 1] = y[i] + normalY * half
            outline[rightIndex] = x[i] - normalX * half
            outline[rightIndex + 1] = y[i] - normalY * half
        }
        pointCount = n * 2
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > outline.size) outline = FloatArray(needed)
    }

    private companion object {
        const val INITIAL_CAPACITY = 512
    }
}

/**
 * The quads swept by a chisel nib.
 *
 * A calligraphy nib is a straight edge held at a fixed angle. Drag it perpendicular to its edge and
 * it lays down its full length; drag it along the edge and it lays down almost nothing. That
 * relationship — thick on one diagonal, thin on the other — *is* the pen, and it falls out of the
 * geometry rather than having to be faked with a width curve.
 */
internal class NibSolver {

    /** Quad vertices, four `x, y` pairs per quad, valid in `0 until quadCount * 8`. */
    var quads: FloatArray = FloatArray(INITIAL_CAPACITY)
        private set

    /** Number of quads — one per centreline segment. */
    var quadCount: Int = 0
        private set

    /** Half the nib edge, as an offset vector. Exposed so a dot can be drawn along the same edge. */
    var nibOffsetX: Float = 0f
        private set

    /** Half the nib edge, as an offset vector. */
    var nibOffsetY: Float = 0f
        private set

    /**
     * Sweeps the nib along [stroke].
     *
     * @param nibLengthPx the length of the nib edge.
     * @param angleRadians the angle the edge is held at. [NIB_ANGLE_RADIANS] by default — 45°, the
     *   angle a right-handed calligrapher actually holds a broad nib at.
     */
    fun solve(stroke: StrokeSolver, nibLengthPx: Float, angleRadians: Float = NIB_ANGLE_RADIANS) {
        quadCount = 0
        val half = nibLengthPx * 0.5f
        nibOffsetX = cos(angleRadians) * half
        nibOffsetY = sin(angleRadians) * half

        val n = stroke.count
        if (n < 2) return

        ensureCapacity((n - 1) * 8)
        for (i in 0 until n - 1) {
            val o = i * 8
            val ax = stroke.x[i] + nibOffsetX
            val ay = stroke.y[i] + nibOffsetY
            val bx = stroke.x[i + 1] + nibOffsetX
            val by = stroke.y[i + 1] + nibOffsetY
            val cx = stroke.x[i + 1] - nibOffsetX
            val cy = stroke.y[i + 1] - nibOffsetY
            val dx = stroke.x[i] - nibOffsetX
            val dy = stroke.y[i] - nibOffsetY

            quads[o] = ax
            quads[o + 1] = ay
            quads[o + 4] = cx
            quads[o + 5] = cy

            // Every quad is emitted with the same winding direction. The natural order flips
            // whenever the stroke turns back across the nib's angle, and mixing directions inside
            // one nonzero-filled path makes the overlaps cancel — a stroke that doubled back would
            // punch a hole through ink it had already laid down.
            val clockwise = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax) >= 0f
            quads[o + 2] = if (clockwise) bx else dx
            quads[o + 3] = if (clockwise) by else dy
            quads[o + 6] = if (clockwise) dx else bx
            quads[o + 7] = if (clockwise) dy else by
        }
        quadCount = n - 1
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > quads.size) quads = FloatArray(needed)
    }

    companion object {
        /** 45°, in radians. */
        const val NIB_ANGLE_RADIANS: Float = (Math.PI / 4).toFloat()

        /**
         * Nib edge length ÷ nominal width.
         *
         * The nib has to be substantially longer than the pen's nominal width or the difference
         * between the thick and thin diagonals is too small to read as calligraphy at all.
         */
        const val NIB_LENGTH_FACTOR: Float = 3f

        private const val INITIAL_CAPACITY = 512
    }
}

/**
 * Graphite and charcoal grain, as batched stamps.
 *
 * ### Why stamps, and why in tiers
 *
 * Grain is the absence of coverage — a pencil is a line that does not quite fill itself in. That
 * cannot be expressed by a paint style, so it is drawn as many small dots scattered across the
 * stroke's width. Drawn one at a time, a long pencil stroke would be thousands of `drawCircle`
 * calls per frame while the pen is still moving.
 *
 * So the stamps are bucketed into [TIERS] tiers by radius and opacity, and each tier is emitted as a
 * single `drawPoints` call with a round cap. Three draw calls per stroke, and the variation that
 * makes it look like graphite survives.
 *
 * ### Why the randomness is seeded
 *
 * Ingest round-trip fidelity is an acceptance criterion: `setStrokes(getStrokes())` must be a visual
 * no-op (G4). A pencil stroke whose grain was re-scattered on every record would fail it — visibly,
 * and only for the texture pens, which is exactly the kind of bug that gets blamed on the ingest
 * path rather than on the renderer. The generator is seeded from the stroke's own id, so the same
 * stroke grains identically forever.
 */
internal class GrainSolver {

    /** Stamp positions per tier, as `x, y` pairs. */
    private val points: Array<FloatArray> = Array(TIERS) { FloatArray(INITIAL_CAPACITY) }

    /** Number of floats used in each tier's array — `2 ×` the stamp count. */
    private val used = IntArray(TIERS)

    /** Stamp diameter per tier, in px. Read by the renderer as `Paint.strokeWidth`. */
    val tierDiameter: FloatArray = FloatArray(TIERS)

    /** Total stamps placed across every tier. Diagnostics and tests. */
    var stampCount: Int = 0
        private set

    /** xorshift32 state. A field rather than a closure so the hot scatter loop allocates nothing. */
    private var random: Int = 1

    /** Stamp coordinates for [tier], valid in `0 until floatCount(tier)`. */
    fun tierPoints(tier: Int): FloatArray = points[tier]

    /** How many floats of [tierPoints] are valid — two per stamp. */
    fun floatCount(tier: Int): Int = used[tier]

    /**
     * Scatters grain along [stroke].
     *
     * @param seed derived from the stroke's id; identical seeds produce identical grain.
     */
    fun solve(stroke: StrokeSolver, tuning: PenTuning, seed: Int) {
        used.fill(0)
        stampCount = 0
        val n = stroke.count
        if (n == 0) return

        for (tier in 0 until TIERS) {
            tierDiameter[tier] = stroke.maxWidth * TIER_DIAMETER_FACTOR[tier] * tuning.grainScale
        }

        // A seed of zero would lock xorshift at zero forever, so it is forced odd.
        random = seed or 1

        // A single-sample stroke is a dot: scatter one step's worth of grain in place so a tap with
        // a pencil leaves graphite rather than nothing.
        if (n == 1) {
            repeat(STAMPS_PER_STEP) { scatter(stroke.x[0], stroke.y[0], 0f, 1f, stroke.width[0]) }
            return
        }

        val step = (tuning.spacing * stroke.maxWidth).coerceAtLeast(MIN_STEP_PX)
        var carry = 0f
        for (i in 0 until n - 1) {
            val dx = stroke.x[i + 1] - stroke.x[i]
            val dy = stroke.y[i + 1] - stroke.y[i]
            val segment = sqrt(dx * dx + dy * dy)
            if (segment <= 0f) continue
            val ux = dx / segment
            val uy = dy / segment

            var travelled = carry
            while (travelled < segment) {
                val t = travelled / segment
                val w = stroke.width[i] + (stroke.width[i + 1] - stroke.width[i]) * t
                repeat(STAMPS_PER_STEP) {
                    scatter(stroke.x[i] + dx * t, stroke.y[i] + dy * t, -uy, ux, w)
                }
                travelled += step
            }
            carry = travelled - segment
        }
    }

    /** The next value in `0..1`, from the seeded generator. */
    private fun nextUnit(): Float {
        random = random xor (random shl 13)
        random = random xor (random ushr 17)
        random = random xor (random shl 5)
        return (random and 0xFFFF) / 65535f
    }

    /** Places one stamp near `(cx, cy)`, offset mostly across the stroke rather than along it. */
    private fun scatter(
        cx: Float,
        cy: Float,
        normalX: Float,
        normalY: Float,
        widthPx: Float,
    ) {
        val half = widthPx * 0.5f
        val across = (nextUnit() * 2f - 1f) * half
        val along = (nextUnit() * 2f - 1f) * half * ALONG_JITTER
        val tier = (nextUnit() * TIERS).toInt().coerceIn(0, TIERS - 1)

        val px = cx + normalX * across + normalY * along
        val py = cy + normalY * across - normalX * along

        val index = used[tier]
        if (index + 2 > points[tier].size) {
            points[tier] = points[tier].copyOf(points[tier].size * 2)
        }
        points[tier][index] = px
        points[tier][index + 1] = py
        used[tier] = index + 2
        stampCount++
    }

    companion object {
        /** Radius/opacity buckets. Three is enough variation to read as grain, and three draw calls. */
        const val TIERS: Int = 3

        /**
         * Stamp diameter as a fraction of the stroke's width, per tier.
         *
         * Small on purpose. Tuned by eye against the golden images: at a quarter of the stroke's
         * width the stamps read as a scatter of distinct blobs — recognisably *dots*, not graphite.
         * Grain has to be finer than the mark it makes.
         */
        val TIER_DIAMETER_FACTOR: FloatArray = floatArrayOf(0.09f, 0.15f, 0.24f)

        /** Opacity multiplier applied to the stroke colour, per tier. */
        val TIER_ALPHA_FACTOR: FloatArray = floatArrayOf(0.35f, 0.60f, 0.90f)

        /**
         * Stamps placed at each step along the path.
         *
         * With the diameters above this lands coverage around 40%, which is roughly what graphite
         * on paper does: dense enough to read as a line, sparse enough to read as texture.
         */
        const val STAMPS_PER_STEP: Int = 6

        /** Jitter along the stroke, as a fraction of the across-stroke jitter. */
        const val ALONG_JITTER: Float = 0.4f

        /**
         * Floor on the step between stamps.
         *
         * Without it a hairline pencil would place stamps at sub-pixel intervals — thousands of
         * draw points for a mark half a pixel wide.
         */
        const val MIN_STEP_PX: Float = 0.5f

        private const val INITIAL_CAPACITY = 512
    }
}

/**
 * The on/off cadence of a dashed line.
 *
 * Scaled by the stroke's width so a dashed hairline and a dashed 12 dp line read as the same pen,
 * rather than as a dotted line and a row of bricks.
 */
internal object DashCadence {

    /** Dash length ÷ width. */
    const val ON_FACTOR: Float = 3f

    /** Gap length ÷ width. */
    const val OFF_FACTOR: Float = 2.5f

    /**
     * The `[on, off]` intervals for a stroke of [widthPx].
     *
     * Never zero: `DashPathEffect` requires strictly positive intervals and throws otherwise, and a
     * hairline at a low density can round to nothing.
     */
    fun intervals(widthPx: Float): FloatArray = floatArrayOf(
        (widthPx * ON_FACTOR).coerceAtLeast(MIN_INTERVAL_PX),
        (widthPx * OFF_FACTOR).coerceAtLeast(MIN_INTERVAL_PX),
    )

    private const val MIN_INTERVAL_PX = 1f
}
