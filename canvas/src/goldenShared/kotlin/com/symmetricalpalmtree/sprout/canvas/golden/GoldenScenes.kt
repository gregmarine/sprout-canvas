package com.symmetricalpalmtree.sprout.canvas.golden

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRendererRegistry
import kotlin.math.abs
import kotlin.math.sin

/**
 * The scenes the golden-image suite renders, defined once for both tiers.
 *
 * ### Why goldens exist at all
 *
 * Geometry assertions run on every build and catch a renderer that changed *shape*. They cannot
 * catch a renderer that changed *appearance*: a wrong paint style, a lost alpha, a cap that became
 * square, a texture that stopped being a texture. Every one of those keeps the geometry exactly
 * right and looks obviously wrong to a person.
 *
 * ### Why they are a separate, on-demand suite
 *
 * Pixel comparison is sensitive to the rendering environment in a way geometry is not, so gating
 * routine builds on it would mean a red build whenever a toolchain moved underneath us. Goldens run
 * before a release and whenever a renderer changes — deliberately, by their own command
 * (PLAN.md D13, §4.1.1).
 *
 * ### Why the scenes are shared between the tiers
 *
 * Both the Robolectric suite and the instrumented one render exactly these bitmaps, so the two are
 * directly comparable. That comparison is what settled which tier hosts the suite (R1), and it is
 * what makes the answer re-checkable rather than a one-off judgement in a commit message.
 *
 * Everything here is deterministic: fixed size, fixed density, fixed seeds, no clock, no randomness
 * that is not seeded from the scene's own name.
 */
internal object GoldenScenes {

    /** Golden bitmaps are this size in both tiers. */
    const val WIDTH = 240

    /** Golden bitmaps are this size in both tiers. */
    const val HEIGHT = 160

    /**
     * A fixed density, not the device's.
     *
     * The whole suite would otherwise render differently on every panel it ran on, which is the
     * opposite of what a golden is for. Real density handling is covered by the geometry tests.
     */
    const val DENSITY = 2f

    /** One scene: a name, a tool, and the samples to draw with it. */
    class Scene(
        val name: String,
        val tool: ToolSpec,
        val samples: StrokeSamples,
    )

    /**
     * Every scene in the suite.
     *
     * One per pen at a representative width, plus the cases where a renderer has somewhere
     * specific to go wrong: pressure response, translucency, a tap, and a stroke that doubles back
     * over itself.
     */
    fun scenes(): List<Scene> = buildList {
        SproutPen.entries.forEach { pen ->
            add(Scene("pen-${pen.name.lowercase()}", ToolSpec(pen, 3f, Color.BLACK), wave()))
        }
        add(Scene("pressure-fountain", ToolSpec(SproutPen.FOUNTAIN, 4f, Color.BLACK), ramp()))
        add(Scene("pressure-brush", ToolSpec(SproutPen.BRUSH, 3f, Color.BLACK), ramp()))
        add(
            Scene(
                "highlighter-over-ink",
                ToolSpec(SproutPen.HIGHLIGHTER, 6f, Color.BLACK),
                straight(),
            ),
        )
        add(Scene("marker-flat-ends", ToolSpec(SproutPen.MARKER, 6f, Color.BLACK), straight()))
        add(Scene("dot-ballpoint", ToolSpec(SproutPen.BALLPOINT, 8f, Color.BLACK), dot()))
        add(Scene("dot-calligraphy", ToolSpec(SproutPen.CALLIGRAPHY, 8f, Color.BLACK), dot()))
        // A stroke that crosses itself: the case where a nib's fill rule can punch a hole through
        // ink it already laid down, and where a ribbon's caps can double-blend under alpha.
        add(Scene("doubled-back-calligraphy", ToolSpec(SproutPen.CALLIGRAPHY, 4f, Color.BLACK), zigzag()))
        add(Scene("doubled-back-fountain", ToolSpec(SproutPen.FOUNTAIN, 4f, Color.BLACK), zigzag()))
        add(Scene("colour-red-ballpoint", ToolSpec(SproutPen.BALLPOINT, 4f, Color.rgb(200, 30, 30)), wave()))
    }

    /**
     * Renders [scene] onto a white bitmap.
     *
     * White rather than transparent because a golden has to be readable by a person deciding
     * whether a diff is a regression or an improvement, and a transparent PNG of black ink is a
     * black rectangle in most viewers.
     */
    fun render(scene: Scene): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        StrokeRendererRegistry()
            .rendererFor(scene.tool.pen)
            .draw(canvas, scene.samples, scene.tool, scene.name.hashCode(), RenderContext(DENSITY))
        return bitmap
    }

    /**
     * The largest per-channel difference between two bitmaps, `0..255`, and how many pixels differ
     * at all.
     *
     * Reported as a pair rather than a boolean so a failure can say *how* different — one channel
     * out by 1 on eight anti-aliased edge pixels is a different conversation from a stroke that
     * moved.
     */
    fun compare(expected: Bitmap, actual: Bitmap): Difference {
        if (expected.width != actual.width || expected.height != actual.height) {
            return Difference(maxChannelDelta = 255, differingPixels = expected.width * expected.height)
        }
        var maxDelta = 0
        var differing = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val a = expected.getPixel(x, y)
                val b = actual.getPixel(x, y)
                if (a == b) continue
                differing++
                maxDelta = maxOf(
                    maxDelta,
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b)),
                    abs(Color.alpha(a) - Color.alpha(b)),
                )
            }
        }
        return Difference(maxDelta, differing)
    }

    /** How far apart two renderings of the same scene are. */
    class Difference(val maxChannelDelta: Int, val differingPixels: Int) {
        val isIdentical: Boolean get() = differingPixels == 0
        override fun toString(): String =
            "$differingPixels px differ, worst channel delta $maxChannelDelta"
    }

    // --- Sample sets ---------------------------------------------------------------------------

    /** A sine wave across the bitmap, at even pressure. */
    private fun wave(count: Int = 60): StrokeSamples {
        val x = FloatArray(count) { 20f + it * (WIDTH - 40f) / (count - 1) }
        val y = FloatArray(count) { HEIGHT / 2f + sin(it / (count - 1f) * 6.0).toFloat() * 40f }
        return StrokeSamples(count, x, y, pressure = FloatArray(count) { 0.5f })
    }

    /** A straight diagonal with pressure ramping from nothing to full. */
    private fun ramp(count: Int = 40): StrokeSamples {
        val x = FloatArray(count) { 20f + it * (WIDTH - 40f) / (count - 1) }
        val y = FloatArray(count) { 30f + it * (HEIGHT - 60f) / (count - 1) }
        return StrokeSamples(count, x, y, pressure = FloatArray(count) { it / (count - 1f) })
    }

    /** A flat horizontal run — the clearest look at caps, width and translucency. */
    private fun straight(count: Int = 20): StrokeSamples {
        val x = FloatArray(count) { 30f + it * (WIDTH - 60f) / (count - 1) }
        val y = FloatArray(count) { HEIGHT / 2f }
        return StrokeSamples(count, x, y, pressure = FloatArray(count) { 0.5f })
    }

    /** A single sample: the tap case, which every pen has to render as something. */
    private fun dot(): StrokeSamples =
        StrokeSamples(1, floatArrayOf(WIDTH / 2f), floatArrayOf(HEIGHT / 2f), pressure = floatArrayOf(0.7f))

    /** A stroke that reverses across itself several times. */
    private fun zigzag(): StrokeSamples {
        val points = listOf(
            30f to 30f, 200f to 60f, 30f to 90f, 200f to 120f, 60f to 40f, 180f to 130f,
        )
        val x = FloatArray(points.size) { points[it].first }
        val y = FloatArray(points.size) { points[it].second }
        return StrokeSamples(points.size, x, y, pressure = FloatArray(points.size) { 0.6f })
    }
}
