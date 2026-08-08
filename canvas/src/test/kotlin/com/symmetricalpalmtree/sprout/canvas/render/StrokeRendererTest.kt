package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every pen puts ink on the screen, and no two of them put down the same ink.
 *
 * ### Why this is not the golden suite
 *
 * These assertions are about *coverage and difference* — that a pen marked the bitmap at all, that a
 * highlighter is translucent where a marker is opaque, that grain leaves gaps where a ballpoint does
 * not. They hold under any reasonable renderer and do not encode one particular set of pixels, so
 * they can run on every build without becoming a tripwire for harmless rendering variance.
 *
 * The golden suite is the separate, deliberate thing that *does* pin exact pixels
 * (PLAN.md D13, §4.1.1).
 *
 * ### Why NATIVE graphics
 *
 * Robolectric's default graphics mode records draw calls without executing them, so every bitmap
 * comes back blank and a test like this would pass while asserting nothing. `NATIVE` runs the real
 * Skia pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeRendererTest {

    private val registry = StrokeRendererRegistry()
    private val context = RenderContext(density = 2f)

    private fun bitmap() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    /** A diagonal stroke across the bitmap, with pressure ramping from nothing to full. */
    private fun samples(count: Int = 24, withPressure: Boolean = true): StrokeSamples {
        val x = FloatArray(count) { 20f + it * (WIDTH - 40f) / (count - 1) }
        val y = FloatArray(count) { 20f + it * (HEIGHT - 40f) / (count - 1) }
        val pressure = if (withPressure) FloatArray(count) { it / (count - 1f) } else null
        return StrokeSamples(count, x, y, pressure = pressure)
    }

    private fun render(
        pen: SproutPen,
        widthDp: Float = 3f,
        color: Int = Color.BLACK,
        seed: Int = 42,
        samples: StrokeSamples = samples(),
    ): Bitmap {
        val target = bitmap()
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        registry.rendererFor(pen)
            .draw(canvas, samples, ToolSpec(pen, widthDp, color), seed, context)
        return target
    }

    /** How many pixels differ from the white background. */
    private fun inkedPixels(bitmap: Bitmap): Int {
        var inked = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) inked++
            }
        }
        return inked
    }

    /** Mean darkness of the inked pixels, 0 (white) to 255 (black). */
    private fun meanInkDarkness(bitmap: Bitmap): Float {
        var total = 0L
        var inked = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == Color.WHITE) continue
                total += 255 - Color.red(pixel)
                inked++
            }
        }
        return if (inked == 0) 0f else total.toFloat() / inked
    }

    // --- Every pen draws -----------------------------------------------------------------------

    @Test
    fun `every pen puts ink on the canvas`() {
        // The failure this catches is the one vendor SDKs specialize in: a pen that draws nothing,
        // reports nothing, and logs nothing (PLAN.md §5.5). Our own renderers must never join in.
        SproutPen.entries.forEach { pen ->
            val inked = inkedPixels(render(pen))
            assertTrue("$pen drew nothing", inked > 0)
        }
    }

    @Test
    fun `every pen draws a single-sample tap`() {
        val dot = StrokeSamples(1, floatArrayOf(WIDTH / 2f), floatArrayOf(HEIGHT / 2f))
        SproutPen.entries.forEach { pen ->
            val inked = inkedPixels(render(pen, widthDp = 6f, samples = dot))
            assertTrue("$pen lost a tap", inked > 0)
        }
    }

    @Test
    fun `every pen honours its colour`() {
        SproutPen.entries.forEach { pen ->
            val bitmap = render(pen, color = Color.RED)
            var sawRed = false
            outer@ for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > Color.blue(pixel) + 40) {
                        sawRed = true
                        break@outer
                    }
                }
            }
            assertTrue("$pen ignored its colour", sawRed)
        }
    }

    @Test
    fun `a wider pen puts down more ink`() {
        SproutPen.entries.forEach { pen ->
            val thin = inkedPixels(render(pen, widthDp = 1f))
            val thick = inkedPixels(render(pen, widthDp = 8f))
            assertTrue("$pen: 1dp=$thin 8dp=$thick", thick > thin)
        }
    }

    // --- The pens are genuinely different ------------------------------------------------------

    @Test
    fun `a marker and a highlighter are not the same tool`() {
        // The clearest case for why the two are separate names (PLAN.md D12): one covers what it
        // marks and the other does not.
        val marker = render(SproutPen.MARKER, widthDp = 4f)
        val highlighter = render(SproutPen.HIGHLIGHTER, widthDp = 4f)

        assertTrue(
            "highlighter is not wider than the marker",
            inkedPixels(highlighter) > inkedPixels(marker),
        )
        assertTrue(
            "highlighter is as opaque as the marker: ${meanInkDarkness(highlighter)} vs " +
                meanInkDarkness(marker),
            meanInkDarkness(highlighter) < meanInkDarkness(marker) * 0.75f,
        )
    }

    @Test
    fun `a highlighter leaves an explicit colour alone`() {
        // The pen supplies translucency only when the app expressed no opinion. An app that set its
        // own alpha has said what it wants.
        val defaulted = meanInkDarkness(render(SproutPen.HIGHLIGHTER, color = Color.BLACK))
        val explicit = meanInkDarkness(
            render(SproutPen.HIGHLIGHTER, color = Color.argb(255, 0, 0, 0).let { Color.argb(200, 0, 0, 0) }),
        )
        assertTrue("$defaulted vs $explicit", explicit > defaulted)
    }

    @Test
    fun `a fountain pen varies with pressure and a ballpoint does not`() {
        val withPressure = inkedPixels(render(SproutPen.FOUNTAIN, samples = samples(withPressure = true)))
        val without = inkedPixels(render(SproutPen.FOUNTAIN, samples = samples(withPressure = false)))
        assertNotEquals("pressure changed nothing", withPressure, without)

        val ballpointWith = inkedPixels(render(SproutPen.BALLPOINT, samples = samples(withPressure = true)))
        val ballpointWithout = inkedPixels(render(SproutPen.BALLPOINT, samples = samples(withPressure = false)))
        assertEquals("a ballpoint responded to pressure", ballpointWith, ballpointWithout)
    }

    @Test
    fun `a texture pen leaves gaps where a solid pen does not`() {
        // Grain is missing ink. A pencil that covered its whole width would be a ballpoint.
        val pencil = render(SproutPen.PENCIL, widthDp = 4f)
        val ballpoint = render(SproutPen.BALLPOINT, widthDp = 4f)
        assertTrue(
            "pencil ink is as solid as a ballpoint's: ${meanInkDarkness(pencil)} vs " +
                meanInkDarkness(ballpoint),
            meanInkDarkness(pencil) < meanInkDarkness(ballpoint),
        )
    }

    @Test
    fun `a dashed line covers less than a solid one of the same width`() {
        val dashed = inkedPixels(render(SproutPen.DASHED, widthDp = 3f))
        val solid = inkedPixels(render(SproutPen.BALLPOINT, widthDp = 3f))
        assertTrue("dashed=$dashed solid=$solid", dashed < solid)
    }

    @Test
    fun `a calligraphy nib is thick on one diagonal and thin on the other`() {
        val nibAngle = StrokeSamples(2, floatArrayOf(20f, 180f), floatArrayOf(20f, 180f))
        val acrossNib = StrokeSamples(2, floatArrayOf(20f, 180f), floatArrayOf(180f, 20f))

        val along = inkedPixels(render(SproutPen.CALLIGRAPHY, widthDp = 4f, samples = nibAngle))
        val across = inkedPixels(render(SproutPen.CALLIGRAPHY, widthDp = 4f, samples = acrossNib))
        assertTrue("along=$along across=$across", across > along * 3)
    }

    // --- Determinism ---------------------------------------------------------------------------

    @Test
    fun `re-rendering a stroke reproduces it exactly`() {
        // This is the ingest guarantee at the pixel level: setStrokes(getStrokes()) is a visual
        // no-op (G4) only if a renderer given the same inputs produces the same output.
        SproutPen.entries.forEach { pen ->
            val first = render(pen, seed = 7)
            val second = render(pen, seed = 7)
            assertTrue("$pen re-rendered differently", first.sameAs(second))
        }
    }

    @Test
    fun `two texture strokes with different ids grain differently`() {
        val first = render(SproutPen.PENCIL, widthDp = 5f, seed = 1)
        val second = render(SproutPen.PENCIL, widthDp = 5f, seed = 2)
        assertTrue("both strokes scattered identical grain", !first.sameAs(second))
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 200
    }
}
