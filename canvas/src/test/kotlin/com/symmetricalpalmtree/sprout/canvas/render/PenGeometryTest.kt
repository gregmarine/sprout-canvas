package com.symmetricalpalmtree.sprout.canvas.render

import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Ribbon outlines, nib sweeps, grain scatter and dash cadence — the per-pen geometry, asserted
 * without a screen.
 *
 * Same reasoning as [StrokeSolverTest]: this is the part of a renderer that can be checked exactly,
 * so it is checked exactly and on every build.
 */
class PenGeometryTest {

    private val solver = StrokeSolver()

    /** Solves a horizontal line of [count] samples at a fixed even width. */
    private fun horizontalLine(
        count: Int = 5,
        spacing: Float = 10f,
        pen: SproutPen = SproutPen.BALLPOINT,
        widthPx: Float = 4f,
        pressure: FloatArray? = null,
    ): StrokeSolver {
        val x = FloatArray(count) { it * spacing }
        val y = FloatArray(count) { 50f }
        solver.solve(x, y, pressure, null, count, PenTuning.forPen(pen), widthPx)
        return solver
    }

    // --- Ribbon -----------------------------------------------------------------------------

    @Test
    fun `the outline has two vertices per centreline point`() {
        val stroke = horizontalLine(count = 6)
        val ribbon = RibbonSolver()
        ribbon.solve(stroke)
        assertEquals(stroke.count * 2, ribbon.pointCount)
    }

    @Test
    fun `the outline sits half a width either side of the centreline`() {
        val stroke = horizontalLine(count = 4, widthPx = 6f)
        val ribbon = RibbonSolver()
        ribbon.solve(stroke)

        for (i in 0 until stroke.count) {
            val half = stroke.width[i] * 0.5f
            val left = i * 2
            val right = (2 * stroke.count - 1 - i) * 2
            // A horizontal stroke offsets purely in y, and by exactly half the width each way.
            assertEquals(stroke.x[i], ribbon.outline[left], 1e-3f)
            assertEquals(stroke.x[i], ribbon.outline[right], 1e-3f)
            assertEquals(half, abs(ribbon.outline[left + 1] - stroke.y[i]), 1e-3f)
            assertEquals(half, abs(ribbon.outline[right + 1] - stroke.y[i]), 1e-3f)
            assertNotEquals(ribbon.outline[left + 1], ribbon.outline[right + 1])
        }
    }

    @Test
    fun `the outline widens where the pressure does`() {
        val stroke = horizontalLine(
            count = 6,
            pen = SproutPen.FOUNTAIN,
            widthPx = 4f,
            pressure = floatArrayOf(0.1f, 0.1f, 0.1f, 1f, 1f, 1f),
        )
        val ribbon = RibbonSolver()
        ribbon.solve(stroke)

        val n = stroke.count
        fun spanAt(i: Int): Float {
            val left = i * 2
            val right = (2 * n - 1 - i) * 2
            return hypot(
                ribbon.outline[left] - ribbon.outline[right],
                ribbon.outline[left + 1] - ribbon.outline[right + 1],
            )
        }
        assertTrue("start=${spanAt(0)} end=${spanAt(n - 1)}", spanAt(n - 1) > spanAt(0))
    }

    @Test
    fun `a stroke too short to have a direction produces no outline`() {
        // One point has nothing to offset perpendicular to. The renderer draws a dot instead, and
        // an outline of one vertex would be a zero-area sliver.
        val stroke = horizontalLine(count = 1)
        val ribbon = RibbonSolver()
        ribbon.solve(stroke)
        assertEquals(0, ribbon.pointCount)
    }

    // --- Nib ---------------------------------------------------------------------------------

    @Test
    fun `the nib sweeps one quad per segment`() {
        val stroke = horizontalLine(count = 5)
        val nib = NibSolver()
        nib.solve(stroke, nibLengthPx = 12f)
        assertEquals(stroke.count - 1, nib.quadCount)
    }

    @Test
    fun `travelling along the nib's own angle lays down almost nothing`() {
        // This is the pen: a chisel dragged along its edge leaves a hairline, and dragged across it
        // leaves its full length. Both cases come out of the geometry, not out of a width curve.
        val diagonal = StrokeSolver()
        val x = floatArrayOf(0f, 40f)
        val y = floatArrayOf(0f, 40f) // 45°, exactly the nib angle
        diagonal.solve(x, y, null, null, 2, PenTuning.forPen(SproutPen.CALLIGRAPHY), 4f)

        val nib = NibSolver()
        nib.solve(diagonal, nibLengthPx = 12f)
        assertEquals(1, nib.quadCount)
        assertTrue("thin sweep had area ${quadArea(nib, 0)}", abs(quadArea(nib, 0)) < 1f)
    }

    @Test
    fun `travelling across the nib lays down its full length`() {
        val across = StrokeSolver()
        val x = floatArrayOf(0f, 40f)
        val y = floatArrayOf(0f, -40f) // 135° — perpendicular to a 45° nib
        across.solve(x, y, null, null, 2, PenTuning.forPen(SproutPen.CALLIGRAPHY), 4f)

        val nib = NibSolver()
        nib.solve(across, nibLengthPx = 12f)
        val segmentLength = hypot(40f, 40f)
        assertEquals(segmentLength * 12f, abs(quadArea(nib, 0)), 1f)
    }

    @Test
    fun `every quad winds the same way, even when the stroke doubles back`() {
        // Mixed winding inside one nonzero-filled path makes the overlaps cancel: a stroke that
        // came back over itself would punch a hole through ink it had already laid down.
        val zigzag = StrokeSolver()
        val x = floatArrayOf(0f, 40f, 0f, 40f)
        val y = floatArrayOf(0f, 20f, 40f, 60f)
        zigzag.solve(x, y, null, null, 4, PenTuning.forPen(SproutPen.CALLIGRAPHY), 4f)

        val nib = NibSolver()
        nib.solve(zigzag, nibLengthPx = 12f)
        assertTrue(nib.quadCount >= 3)
        for (q in 0 until nib.quadCount) {
            assertTrue("quad $q winds the other way: ${quadArea(nib, q)}", quadArea(nib, q) >= -1e-3f)
        }
    }

    @Test
    fun `the nib is held at 45 degrees`() {
        val stroke = horizontalLine(count = 2)
        val nib = NibSolver()
        nib.solve(stroke, nibLengthPx = 10f)
        // Equal x and y components is what 45° means, and the offset is half the nib's length.
        assertEquals(nib.nibOffsetX, nib.nibOffsetY, 1e-4f)
        assertEquals(5f, hypot(nib.nibOffsetX, nib.nibOffsetY), 1e-3f)
    }

    /** Shoelace area of quad [index]. Signed — the sign is the winding direction. */
    private fun quadArea(nib: NibSolver, index: Int): Float {
        val o = index * 8
        var sum = 0f
        for (v in 0 until 4) {
            val ax = nib.quads[o + v * 2]
            val ay = nib.quads[o + v * 2 + 1]
            val bx = nib.quads[o + ((v + 1) % 4) * 2]
            val by = nib.quads[o + ((v + 1) % 4) * 2 + 1]
            sum += ax * by - bx * ay
        }
        return sum * 0.5f
    }

    // --- Grain -------------------------------------------------------------------------------

    @Test
    fun `the same stroke grains identically every time`() {
        // Ingest round-trip fidelity depends on this: re-scattered grain would make
        // setStrokes(getStrokes()) a visible change for the texture pens and nothing else, which is
        // the sort of bug that gets blamed on the ingest path.
        val stroke = horizontalLine(count = 8, pen = SproutPen.PENCIL, widthPx = 4f)
        val tuning = PenTuning.forPen(SproutPen.PENCIL)

        val first = GrainSolver().apply { solve(stroke, tuning, seed = 12345) }
        val second = GrainSolver().apply { solve(stroke, tuning, seed = 12345) }

        assertEquals(first.stampCount, second.stampCount)
        for (tier in 0 until GrainSolver.TIERS) {
            assertEquals(first.floatCount(tier), second.floatCount(tier))
            for (i in 0 until first.floatCount(tier)) {
                assertEquals(first.tierPoints(tier)[i], second.tierPoints(tier)[i], 0f)
            }
        }
    }

    @Test
    fun `different strokes grain differently`() {
        val stroke = horizontalLine(count = 8, pen = SproutPen.PENCIL, widthPx = 4f)
        val tuning = PenTuning.forPen(SproutPen.PENCIL)

        val first = GrainSolver().apply { solve(stroke, tuning, seed = 1) }
        val second = GrainSolver().apply { solve(stroke, tuning, seed = 2) }

        var identical = true
        for (i in 0 until minOf(first.floatCount(0), second.floatCount(0))) {
            if (first.tierPoints(0)[i] != second.tierPoints(0)[i]) {
                identical = false
                break
            }
        }
        assertTrue("two different strokes scattered the same grain", !identical)
    }

    @Test
    fun `a seed of zero still scatters`() {
        // xorshift stays at zero forever if it is ever seeded with zero, and a stroke id whose hash
        // happens to be zero is not something to find out about on a device.
        val stroke = horizontalLine(count = 8, pen = SproutPen.PENCIL, widthPx = 4f)
        val grain = GrainSolver()
        grain.solve(stroke, PenTuning.forPen(SproutPen.PENCIL), seed = 0)
        assertTrue(grain.stampCount > 0)

        val tier = (0 until GrainSolver.TIERS).first { grain.floatCount(it) > 0 }
        val allSame = (2 until grain.floatCount(tier)).all {
            grain.tierPoints(tier)[it] == grain.tierPoints(tier)[it % 2]
        }
        assertTrue("every stamp landed on the same spot", !allSame)
    }

    @Test
    fun `grain scatters within the stroke's width`() {
        val stroke = horizontalLine(count = 6, pen = SproutPen.CHARCOAL, widthPx = 4f)
        val grain = GrainSolver()
        grain.solve(stroke, PenTuning.forPen(SproutPen.CHARCOAL), seed = 99)

        val half = stroke.maxWidth * 0.5f
        for (tier in 0 until GrainSolver.TIERS) {
            var i = 0
            while (i < grain.floatCount(tier)) {
                val dy = abs(grain.tierPoints(tier)[i + 1] - 50f)
                assertTrue("stamp $dy px off a ${stroke.maxWidth}px stroke", dy <= half + 1e-3f)
                i += 2
            }
        }
    }

    @Test
    fun `a longer stroke gets more grain`() {
        val tuning = PenTuning.forPen(SproutPen.PENCIL)
        val short = GrainSolver().apply {
            solve(horizontalLine(count = 3, pen = SproutPen.PENCIL), tuning, seed = 7)
        }
        val shortCount = short.stampCount
        val long = GrainSolver().apply {
            solve(horizontalLine(count = 30, pen = SproutPen.PENCIL), tuning, seed = 7)
        }
        assertTrue("short=$shortCount long=${long.stampCount}", long.stampCount > shortCount)
    }

    @Test
    fun `a one-sample texture stroke still leaves graphite`() {
        val dot = StrokeSolver()
        dot.solve(floatArrayOf(5f), floatArrayOf(5f), null, null, 1, PenTuning.forPen(SproutPen.PENCIL), 4f)
        val grain = GrainSolver()
        grain.solve(dot, PenTuning.forPen(SproutPen.PENCIL), seed = 3)
        assertTrue(grain.stampCount > 0)
    }

    // --- Dash --------------------------------------------------------------------------------

    @Test
    fun `dash cadence scales with the stroke width`() {
        val thin = DashCadence.intervals(2f)
        val thick = DashCadence.intervals(12f)
        assertEquals(2f * DashCadence.ON_FACTOR, thin[0], 1e-4f)
        assertEquals(2f * DashCadence.OFF_FACTOR, thin[1], 1e-4f)
        assertTrue(thick[0] > thin[0] && thick[1] > thin[1])
    }

    @Test
    fun `dash intervals are never zero`() {
        // DashPathEffect throws on a zero interval, and a hairline at a low density rounds to one.
        val intervals = DashCadence.intervals(0.01f)
        assertTrue(intervals.all { it > 0f })
    }
}
