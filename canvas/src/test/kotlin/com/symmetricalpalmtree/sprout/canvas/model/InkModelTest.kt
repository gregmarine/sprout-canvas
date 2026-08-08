package com.symmetricalpalmtree.sprout.canvas.model

import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [InkStroke], [ToolSpec], [EraserSpec], [SproutWidth] and [DeviceCalibration]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class InkModelTest {

    private fun samples(vararg points: Pair<Float, Float>) = StrokeSamples(
        count = points.size,
        x = FloatArray(points.size) { points[it].first },
        y = FloatArray(points.size) { points[it].second },
    )

    private fun stroke(id: String = "s1", samples: StrokeSamples = samples(0f to 0f, 10f to 10f)) =
        InkStroke(
            id = id,
            samples = samples,
            tool = ToolSpec.DEFAULT,
            capture = CaptureInfo("test", DeviceCalibration.UNKNOWN, 1_000L, 1_250L),
        )

    // --- InkStroke ----------------------------------------------------------------------------

    @Test
    fun `bounds are derived at construction and cannot be wrong`() {
        val subject = stroke(samples = samples(5f to 5f, -3f to 12f, 20f to 1f))
        val bounds = subject.bounds
        assertEquals(-3f, bounds.left, 0f)
        assertEquals(1f, bounds.top, 0f)
        assertEquals(20f, bounds.right, 0f)
        assertEquals(12f, bounds.bottom, 0f)
    }

    @Test
    fun `bounds hands out a copy, so a caller cannot corrupt the stroke`() {
        val subject = stroke()
        val first = subject.bounds
        assertNotSame(first, subject.bounds)
        first.set(0f, 0f, 0f, 0f)
        assertEquals(10f, subject.bounds.right, 0f)
    }

    @Test
    fun `getBounds fills a caller-supplied rect for the allocation-free path`() {
        val out = RectF()
        val returned = stroke().getBounds(out)
        assertTrue(returned === out)
        assertEquals(10f, out.bottom, 0f)
    }

    @Test
    fun `equality is by content, so a round-tripped stroke equals the original`() {
        assertEquals(stroke(), stroke())
        assertEquals(stroke().hashCode(), stroke().hashCode())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a stroke id must not be empty`() {
        stroke(id = "")
    }

    @Test
    fun `capture duration is never negative even if the clocks disagree`() {
        val backwards = CaptureInfo("test", DeviceCalibration.UNKNOWN, startedAtMs = 500L, endedAtMs = 100L)
        assertEquals(0L, backwards.durationMs)
    }

    @Test
    fun `capture info stamps the library version by default`() {
        val info = CaptureInfo("test", DeviceCalibration.UNKNOWN, 0L, 1L)
        assertEquals(com.symmetricalpalmtree.sprout.canvas.SproutCanvas.VERSION, info.libraryVersion)
    }

    // --- ToolSpec -----------------------------------------------------------------------------

    @Test
    fun `the default tool is a medium black ballpoint`() {
        assertEquals(SproutPen.BALLPOINT, ToolSpec.DEFAULT.pen)
        assertEquals(2f, ToolSpec.DEFAULT.widthDp, 0f)
        assertEquals(Color.BLACK, ToolSpec.DEFAULT.color)
        assertEquals(255, ToolSpec.DEFAULT.alpha)
    }

    @Test
    fun `a tool can be built from a preset width rung`() {
        val tool = ToolSpec(SproutPen.HIGHLIGHTER, SproutWidth.XXL, Color.YELLOW)
        assertEquals(12f, tool.widthDp, 0f)
        assertEquals(SproutPen.HIGHLIGHTER, tool.pen)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero width is rejected`() {
        ToolSpec(widthDp = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-finite width is rejected`() {
        ToolSpec(widthDp = Float.NaN)
    }

    @Test
    fun `alpha survives into the tool, because stored colour is never rewritten`() {
        val translucent = ToolSpec(SproutPen.HIGHLIGHTER, 12f, Color.argb(96, 255, 235, 59))
        assertEquals(96, translucent.alpha)
    }

    // --- SproutWidth --------------------------------------------------------------------------

    @Test
    fun `the width ladder is strictly ascending`() {
        val widths = SproutWidth.entries.map { it.dp }
        assertEquals(widths.sorted(), widths)
        assertEquals(widths.distinct().size, widths.size)
    }

    @Test
    fun `nearest snaps an arbitrary width to a rung`() {
        assertEquals(SproutWidth.MEDIUM, SproutWidth.nearest(2.1f))
        assertEquals(SproutWidth.HAIRLINE, SproutWidth.nearest(0f))
        assertEquals(SproutWidth.XXL, SproutWidth.nearest(100f))
    }

    // --- EraserSpec ---------------------------------------------------------------------------

    @Test
    fun `the default eraser removes whole strokes`() {
        assertEquals(EraserMode.STROKE, EraserSpec.DEFAULT.mode)
        assertEquals(12f, EraserSpec.DEFAULT.widthDp, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-width eraser is rejected`() {
        EraserSpec(widthDp = 0f)
    }

    // --- DeviceCalibration --------------------------------------------------------------------

    @Test
    fun `raw pressure is normalized against the device maximum`() {
        // 4095 on some BOOX models and 4096 on others — which is exactly why this is a field.
        val panel4095 = DeviceCalibration(4095f, false, false, 7239, 5359, 300)
        assertEquals(1f, panel4095.normalizePressure(4095f), 1e-6f)
        assertEquals(0.5f, panel4095.normalizePressure(2047.5f), 1e-6f)

        val panel4096 = panel4095.copy(maxPressure = 4096f)
        assertEquals(1f, panel4096.normalizePressure(4096f), 1e-6f)
    }

    @Test
    fun `pressure is clamped, because digitizers overshoot their own maximum`() {
        val calibration = DeviceCalibration(4095f, false, false, 0, 0, 300)
        assertEquals(1f, calibration.normalizePressure(5000f), 0f)
        assertEquals(0f, calibration.normalizePressure(-1f), 0f)
    }

    @Test
    fun `an already-normalized device is passed through`() {
        val calibration = DeviceCalibration(1f, true, false, 0, 0, 300)
        assertEquals(0.42f, calibration.normalizePressure(0.42f), 1e-6f)
    }

    @Test
    fun `an unusable maximum yields zero rather than an infinity`() {
        // An infinity here would propagate into every width calculation downstream and render as
        // nothing at all, with no clue where it came from.
        val broken = DeviceCalibration(0f, false, false, 0, 0, 300)
        assertEquals(0f, broken.normalizePressure(2000f), 0f)
    }

    @Test
    fun `tilt units are never claimed to be known`() {
        // No getMaxTilt() exists anywhere in the vendor SDKs, and one BOOX model reports tilt on a
        // scale ~100x the others. Any normalization would be invented, so tilt stays raw.
        assertTrue(!DeviceCalibration.UNKNOWN.tiltUnitsKnown)
    }
}
