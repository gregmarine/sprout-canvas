package com.symmetricalpalmtree.sprout.canvas.render

import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the ink, asserted without a screen.
 *
 * Plain JUnit — no Robolectric, no device, nothing from `android.graphics`. These run in
 * milliseconds on every build, which is the whole reason the solver was separated from the
 * renderers: a regression that changes how wide a fountain pen gets under pressure is caught here,
 * long before anyone compares pixels.
 */
class StrokeSolverTest {

    private val solver = StrokeSolver()

    private fun tuning(pen: SproutPen) = PenTuning.forPen(pen)

    /** A straight horizontal run of [count] samples, [spacing] px apart. */
    private fun line(count: Int, spacing: Float = 10f): Pair<FloatArray, FloatArray> =
        FloatArray(count) { it * spacing } to FloatArray(count) { 50f }

    // --- Decimation ---------------------------------------------------------------------------

    @Test
    fun `samples closer than the threshold are dropped`() {
        // Ten samples a tenth of a pixel apart: sub-pixel detail no screen can show, and exactly
        // what a slow stroke on a high-rate digitizer produces.
        val (x, y) = line(count = 10, spacing = 0.1f)
        solver.solve(x, y, null, null, 10, tuning(SproutPen.BALLPOINT), nominalWidthPx = 2f)
        assertTrue("kept ${solver.count} of 10", solver.count < 10)
    }

    @Test
    fun `the last sample is always kept`() {
        // Otherwise a stroke ends a fraction of a pixel short of where the pen actually stopped,
        // and a two-sample tap collapses onto the wrong point.
        val x = floatArrayOf(0f, 0.1f)
        val y = floatArrayOf(0f, 0f)
        solver.solve(x, y, null, null, 2, tuning(SproutPen.BALLPOINT), nominalWidthPx = 2f)
        assertEquals(2, solver.count)
        assertEquals(0.1f, solver.x[1], 1e-4f)
    }

    @Test
    fun `well-spaced samples all survive`() {
        val (x, y) = line(count = 5)
        solver.solve(x, y, null, null, 5, tuning(SproutPen.BALLPOINT), nominalWidthPx = 2f)
        assertEquals(5, solver.count)
    }

    @Test
    fun `an empty sample set solves to nothing`() {
        solver.solve(FloatArray(0), FloatArray(0), null, null, 0, tuning(SproutPen.BALLPOINT), 2f)
        assertEquals(0, solver.count)
        assertEquals(0f, solver.maxWidth, 0f)
        assertEquals(0f, solver.length, 0f)
    }

    @Test
    fun `a single sample is a dot, not nothing`() {
        solver.solve(floatArrayOf(7f), floatArrayOf(9f), null, null, 1, tuning(SproutPen.BALLPOINT), 2f)
        assertEquals(1, solver.count)
        assertEquals(7f, solver.x[0], 0f)
        assertEquals(9f, solver.y[0], 0f)
        assertTrue(solver.width[0] > 0f)
    }

    @Test
    fun `length is the distance actually travelled`() {
        val (x, y) = line(count = 4, spacing = 10f)
        solver.solve(x, y, null, null, 4, tuning(SproutPen.BALLPOINT), 2f)
        assertEquals(30f, solver.length, 1e-3f)
    }

    // --- Width ---------------------------------------------------------------------------------

    @Test
    fun `an even-width pen ignores pressure entirely`() {
        val (x, y) = line(count = 5)
        val pressure = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        solver.solve(x, y, pressure, null, 5, tuning(SproutPen.BALLPOINT), nominalWidthPx = 3f)
        for (i in 0 until solver.count) assertEquals(3f, solver.width[i], 1e-4f)
    }

    @Test
    fun `the width multiplier scales the drawn width`() {
        val (x, y) = line(count = 2)
        val marker = tuning(SproutPen.MARKER)
        solver.solve(x, y, null, null, 2, marker, nominalWidthPx = 4f)
        assertEquals(4f * marker.widthMultiplier, solver.width[0], 1e-4f)
    }

    @Test
    fun `half pressure draws exactly the nominal width`() {
        // The pivot of the whole curve: sensitivity swings the width symmetrically either side of
        // the width the app asked for, so a pen with any sensitivity agrees with an even pen here.
        val (x, y) = line(count = 4)
        val pressure = FloatArray(4) { 0.5f }
        solver.solve(x, y, pressure, null, 4, tuning(SproutPen.FOUNTAIN), nominalWidthPx = 2f)
        for (i in 0 until solver.count) assertEquals(2f, solver.width[i], 1e-4f)
    }

    @Test
    fun `a pressure-sensitive pen gets wider under pressure`() {
        val (x, y) = line(count = 2)
        solver.solve(x, y, floatArrayOf(0.1f, 0.1f), null, 2, tuning(SproutPen.FOUNTAIN), 2f)
        val light = solver.width[0]
        solver.solve(x, y, floatArrayOf(0.9f, 0.9f), null, 2, tuning(SproutPen.FOUNTAIN), 2f)
        val heavy = solver.width[0]
        assertTrue("light=$light heavy=$heavy", heavy > light * 1.5f)
    }

    @Test
    fun `the width factor is clamped at both ends`() {
        val (x, y) = line(count = 2)
        val fountain = tuning(SproutPen.FOUNTAIN)
        solver.solve(x, y, floatArrayOf(0f, 0f), null, 2, fountain, nominalWidthPx = 2f)
        assertTrue(solver.width[0] >= 2f * fountain.minWidthFactor - 1e-4f)
        solver.solve(x, y, floatArrayOf(1f, 1f), null, 2, fountain, nominalWidthPx = 2f)
        assertTrue(solver.width[0] <= 2f * fountain.maxWidthFactor + 1e-4f)
    }

    @Test
    fun `pressure above one is treated as full pressure, not amplified`() {
        // Digitizers occasionally report slightly over their own stated maximum.
        val (x, y) = line(count = 2)
        solver.solve(x, y, floatArrayOf(1f, 1f), null, 2, tuning(SproutPen.FOUNTAIN), 2f)
        val atMax = solver.width[0]
        solver.solve(x, y, floatArrayOf(1.4f, 1.4f), null, 2, tuning(SproutPen.FOUNTAIN), 2f)
        assertEquals(atMax, solver.width[0], 1e-4f)
    }

    @Test
    fun `width changes are smoothed rather than tracked sample by sample`() {
        // Raw digitizer pressure is noisy; an unsmoothed width follows the noise into visibly lumpy
        // ink. The first sample after a jump must sit between the old width and the new target.
        val (x, y) = line(count = 3)
        val pressure = floatArrayOf(0.5f, 1f, 1f)
        val fountain = tuning(SproutPen.FOUNTAIN)
        solver.solve(x, y, pressure, null, 3, fountain, nominalWidthPx = 2f)

        val target = 2f * (1f + fountain.pressureSensitivity)
        assertEquals(2f, solver.width[0], 1e-4f)
        assertTrue("jumped straight to $target", solver.width[1] < target - 1e-3f)
        assertTrue(solver.width[1] > solver.width[0])
        assertTrue(solver.width[2] > solver.width[1])
    }

    // --- Velocity fallback ---------------------------------------------------------------------

    @Test
    fun `without pressure or a velocity fallback the pen draws its nominal width`() {
        // Not "as light as possible" and not "as hard as possible" — a pressure-sensitive pen on a
        // digitizer that reports nothing has to look like an even pen.
        val (x, y) = line(count = 3)
        solver.solve(x, y, null, null, 3, tuning(SproutPen.PENCIL), nominalWidthPx = 2f)
        val expected = 2f * tuning(SproutPen.PENCIL).widthMultiplier
        for (i in 0 until solver.count) assertEquals(expected, solver.width[i], 1e-4f)
    }

    @Test
    fun `speed stands in for pressure when the device reports none`() {
        val (x, y) = line(count = 6, spacing = 10f)
        val slow = LongArray(6) { it * 40L }   // 0.25 px/ms
        val fast = LongArray(6) { it * 2L }    // 5 px/ms — past the reference speed
        val fountain = tuning(SproutPen.FOUNTAIN)

        solver.solve(x, y, null, slow, 6, fountain, nominalWidthPx = 2f)
        val slowWidth = solver.width[solver.count - 1]
        solver.solve(x, y, null, fast, 6, fountain, nominalWidthPx = 2f)
        val fastWidth = solver.width[solver.count - 1]

        assertTrue("slow=$slowWidth fast=$fastWidth", slowWidth > fastWidth)
    }

    @Test
    fun `real pressure is never modulated by speed`() {
        // A device that reports pressure has already answered the question. Blending speed on top
        // would be inventing data about how hard the user pressed.
        val (x, y) = line(count = 6, spacing = 10f)
        val pressure = FloatArray(6) { 0.8f }
        val slow = LongArray(6) { it * 40L }
        val fast = LongArray(6) { it * 2L }

        solver.solve(x, y, pressure, slow, 6, tuning(SproutPen.FOUNTAIN), 2f)
        val slowWidth = solver.width[solver.count - 1]
        solver.solve(x, y, pressure, fast, 6, tuning(SproutPen.FOUNTAIN), 2f)
        assertEquals(slowWidth, solver.width[solver.count - 1], 1e-4f)
    }

    @Test
    fun `identical timestamps do not divide by zero`() {
        val (x, y) = line(count = 4)
        val stuck = LongArray(4) { 1000L }
        solver.solve(x, y, null, stuck, 4, tuning(SproutPen.FOUNTAIN), 2f)
        for (i in 0 until solver.count) {
            assertTrue("width ${solver.width[i]} is not finite", solver.width[i].isFinite())
        }
    }

    // --- Reuse ----------------------------------------------------------------------------------

    @Test
    fun `a reused solver carries nothing over from the previous stroke`() {
        val (longX, longY) = line(count = 20)
        solver.solve(longX, longY, null, null, 20, tuning(SproutPen.BALLPOINT), 2f)
        assertEquals(20, solver.count)

        val (shortX, shortY) = line(count = 3)
        solver.solve(shortX, shortY, null, null, 3, tuning(SproutPen.BALLPOINT), 2f)
        assertEquals(3, solver.count)
        assertEquals(20f, solver.length, 1e-3f)
    }

    @Test
    fun `maxWidth is the widest the stroke ever gets`() {
        val (x, y) = line(count = 5)
        val pressure = floatArrayOf(0.5f, 0.6f, 1f, 0.6f, 0.5f)
        solver.solve(x, y, pressure, null, 5, tuning(SproutPen.BRUSH), 2f)
        var widest = 0f
        for (i in 0 until solver.count) widest = maxOf(widest, solver.width[i])
        assertEquals(widest, solver.maxWidth, 0f)
    }
}
