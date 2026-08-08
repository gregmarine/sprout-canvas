package com.symmetricalpalmtree.sprout.canvas.golden

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.SproutWidth
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.CommittedLayer
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

    /**
     * One stroke inside a scene: a tool and the samples drawn with it.
     *
     * Scenes carry a *list* of these because the interesting failures in a drawing library are
     * compositing failures, and a single stroke on white cannot express one. A translucent
     * highlighter over existing ink is the reference case — for its first two phases this suite had
     * a scene named `highlighter-over-ink` that drew a highlighter onto a blank bitmap, asserting
     * nothing about the blend it was named for.
     */
    class Layer(val tool: ToolSpec, val samples: StrokeSamples)

    /** Which of the library's two drawing paths a scene is rendered through. */
    enum class Path {

        /**
         * Straight through the [StrokeRendererRegistry] — one pen's own appearance, nothing else in
         * the way. Most scenes want this, because most scenes are about a renderer.
         */
        RENDERER,

        /**
         * Through [CommittedLayer], onto a software canvas.
         *
         * This is the **fallback branch** of the committed-content model, and it is not an
         * afterthought: a `RenderNode` can only be drawn onto a hardware canvas, while Onyx's
         * `EpdController.handwritingRepaint` re-draws the view through a *software* one to capture
         * it for the panel (PLAN.md §3.8). If that branch ever stops drawing, every e-ink repaint
         * comes back blank and every host screenshot comes back empty — a failure with no geometry
         * consequence whatsoever, which is exactly the kind goldens are for.
         */
        COMMITTED,
    }

    /** One scene: a name, the strokes that make it, and the path they are drawn through. */
    class Scene(
        val name: String,
        val layers: List<Layer>,
        val path: Path = Path.RENDERER,
    ) {
        /** The common case — one stroke, one pen. */
        constructor(name: String, tool: ToolSpec, samples: StrokeSamples) :
            this(name, listOf(Layer(tool, samples)))
    }

    /**
     * Every scene in the suite.
     *
     * Four groups, each earning its images:
     *
     * - **One per pen**, so a renderer that stopped being itself is caught.
     * - **Width ladders** for the pens whose appearance is a function of width. A texture pen drawn
     *   narrow has no room for its grain and comes out solid and grainless — the failure BOOX's own
     *   ×5 charcoal multiplier exists to prevent (PLAN.md §5.7) — and a suite that rendered every
     *   pen at exactly one width could never see it.
     * - **The cases with somewhere specific to go wrong**: pressure response, translucency over
     *   existing ink, a host that sets its own alpha, a tap, a stroke that doubles back.
     * - **The committed layer's software branch**, which no pixel test reached before.
     */
    fun scenes(): List<Scene> = buildList {
        SproutPen.entries.forEach { pen ->
            add(Scene("pen-${pen.name.lowercase()}", ToolSpec(pen, 3f, Color.BLACK), wave()))
        }

        add(Scene("pressure-fountain", ToolSpec(SproutPen.FOUNTAIN, 4f, Color.BLACK), ramp()))
        add(Scene("pressure-brush", ToolSpec(SproutPen.BRUSH, 3f, Color.BLACK), ramp()))

        // Width ladders. Real rungs off SproutWidth rather than invented numbers, so a change to
        // the ladder shows up here rather than leaving the suite testing widths nothing offers.
        // Texture pens get a middle rung too: theirs is the appearance that varies non-linearly,
        // solid at the bottom and open grain at the top, and two points cannot show a curve.
        listOf(SproutPen.PENCIL, SproutPen.CHARCOAL).forEach { pen ->
            listOf(SproutWidth.HAIRLINE, SproutWidth.BOLD, SproutWidth.XXL).forEach { rung ->
                add(widthScene(pen, rung))
            }
        }
        listOf(SproutPen.BALLPOINT, SproutPen.MARKER, SproutPen.CALLIGRAPHY).forEach { pen ->
            listOf(SproutWidth.HAIRLINE, SproutWidth.XXL).forEach { rung ->
                add(widthScene(pen, rung))
            }
        }

        // The blend the highlighter exists for: ink underneath, wash on top, both still legible.
        add(Scene("highlighter-over-ink", highlighterOverInk()))

        // The app stated an alpha of its own, so the pen's default translucency steps aside and the
        // app's value is used verbatim — the stored colour is never second-guessed (PLAN.md §3.6).
        // Rendered over ink for the same reason as above: alpha on white is hard to read by eye.
        add(
            Scene(
                "highlighter-host-alpha",
                listOf(
                    Layer(ToolSpec(SproutPen.BALLPOINT, 3f, Color.BLACK), wave()),
                    Layer(ToolSpec(SproutPen.HIGHLIGHTER, 6f, Color.argb(220, 0, 0, 0)), straight()),
                ),
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

        // Both committed scenes reuse a layer set that is also rendered directly, so the two paths
        // can be compared to each other and not merely to a picture of themselves.
        add(Scene("committed-page", page(), Path.COMMITTED))
        add(Scene("committed-highlighter-over-ink", highlighterOverInk(), Path.COMMITTED))
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
        when (scene.path) {
            Path.RENDERER -> drawLayers(canvas, scene)
            Path.COMMITTED -> CommittedLayer { drawLayers(it, scene) }
                .apply { record(WIDTH, HEIGHT) }
                .draw(canvas)
        }
        return bitmap
    }

    /**
     * Draws a scene's layers in order, bottom first.
     *
     * The registry is built per call rather than held: it is what the view does per canvas, and a
     * shared one would let one scene's renderer buffers reach another's. Cheap, and the suite is
     * not a benchmark.
     */
    private fun drawLayers(canvas: Canvas, scene: Scene) {
        val registry = StrokeRendererRegistry()
        val context = RenderContext(DENSITY)
        scene.layers.forEachIndexed { index, layer ->
            registry
                .rendererFor(layer.tool.pen)
                .draw(canvas, layer.samples, layer.tool, seedFor(scene, index), context)
        }
    }

    /**
     * The seed a layer's texture grain is scattered from.
     *
     * Stable across runs and distinct within a scene, so two texture strokes on one page do not
     * come out wearing identical grain. Offsetting by the index leaves a single-layer scene on
     * exactly the seed it had before layers existed, which keeps the committed images from churning
     * for no reason.
     */
    private fun seedFor(scene: Scene, index: Int): Int = scene.name.hashCode() + index

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

    // --- Composite layer sets ------------------------------------------------------------------

    /** Ink first, then a translucent wash across it. The blend `HIGHLIGHTER` exists to produce. */
    private fun highlighterOverInk(): List<Layer> = listOf(
        Layer(ToolSpec(SproutPen.BALLPOINT, 3f, Color.BLACK), wave()),
        Layer(ToolSpec(SproutPen.HIGHLIGHTER, 6f, Color.BLACK), straight()),
    )

    /**
     * Several pens on one surface, which is what a page of committed content actually is.
     *
     * Deliberately mixes an even-width pen, a pressure ribbon, a texture and a translucent wash:
     * four different paint configurations composited in one pass is where a shared `Paint` that
     * someone forgot to reset shows up.
     */
    private fun page(): List<Layer> = listOf(
        Layer(ToolSpec(SproutPen.BALLPOINT, 3f, Color.BLACK), band(0)),
        Layer(ToolSpec(SproutPen.FOUNTAIN, 3f, Color.rgb(30, 60, 160)), band(1)),
        Layer(ToolSpec(SproutPen.PENCIL, 3f, Color.BLACK), band(2)),
        Layer(ToolSpec(SproutPen.HIGHLIGHTER, 6f, Color.rgb(220, 180, 40)), band(3)),
    )

    // --- Sample sets ---------------------------------------------------------------------------

    /** A scene showing [pen] at one rung of the width ladder, on a flat run for comparability. */
    private fun widthScene(pen: SproutPen, rung: SproutWidth): Scene = Scene(
        "width-${pen.name.lowercase()}-${rung.name.lowercase()}",
        ToolSpec(pen, rung.dp, Color.BLACK),
        straight(),
    )

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

    /** One of four evenly spaced horizontal runs, for scenes that stack several strokes. */
    private fun band(index: Int, count: Int = 20): StrokeSamples {
        val top = HEIGHT / 5f * (index + 1)
        val x = FloatArray(count) { 24f + it * (WIDTH - 48f) / (count - 1) }
        val y = FloatArray(count) { top }
        return StrokeSamples(count, x, y, pressure = FloatArray(count) { 0.3f + index * 0.2f })
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
