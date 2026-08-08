package com.symmetricalpalmtree.sprout.canvas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * What the canvas actually puts on screen: ingest, erase, resize, and the round trip that makes
 * handing strokes back a no-op.
 *
 * ### Why these draw through a software canvas
 *
 * `view.draw(Canvas(bitmap))` is not hardware accelerated, so every assertion here goes through
 * [com.symmetricalpalmtree.sprout.canvas.render.CommittedLayer]'s **fallback** branch. That is
 * deliberate. The fallback is the branch Onyx's `EpdController.handwritingRepaint` uses when it
 * re-draws the view to capture it for the panel, and if it were missing every e-ink repaint would
 * come back blank — on a device, with no test failing anywhere (PLAN.md §3.8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SproutCanvasViewRenderTest {

    private lateinit var activity: Activity
    private lateinit var root: FrameLayout

    @Before
    fun setUp() {
        EngineRegistry.resetForTesting()
        SproutCanvas.resetForTesting()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
    }

    @After
    fun tearDown() {
        EngineRegistry.resetForTesting()
        SproutCanvas.resetForTesting()
    }

    private fun canvas(width: Int = SIZE, height: Int = SIZE): SproutCanvasView {
        val view = SproutCanvasView(activity)
        root.addView(view, FrameLayout.LayoutParams(width, height))
        idle()
        return view
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Draws the view over white, so "inked" means "changed from the background". */
    private fun snapshot(view: SproutCanvasView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bitmap
    }

    private fun inkedPixels(bitmap: Bitmap): Int {
        var inked = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) inked++
            }
        }
        return inked
    }

    private fun stroke(
        id: String,
        pen: SproutPen = SproutPen.BALLPOINT,
        fromX: Float = 20f,
        toX: Float = 120f,
        y: Float = 60f,
    ): InkStroke {
        val count = 12
        val x = FloatArray(count) { fromX + it * (toX - fromX) / (count - 1) }
        val ys = FloatArray(count) { y }
        return InkStroke(
            id = id,
            samples = StrokeSamples(count, x, ys, pressure = FloatArray(count) { 0.6f }),
            tool = ToolSpec(pen = pen, widthDp = 3f, color = Color.BLACK),
            capture = CaptureInfo(EngineIds.GENERIC, DeviceCalibration.UNKNOWN, 0L, 1L),
        )
    }

    // --- Ingest -------------------------------------------------------------------------------

    @Test
    fun `ingested strokes are actually drawn`() {
        val view = canvas()
        assertEquals(0, inkedPixels(snapshot(view)))

        view.setStrokes(listOf(stroke("a")))
        assertTrue(inkedPixels(snapshot(view)) > 0)
    }

    @Test
    fun `handing the canvas its own strokes back changes nothing on screen`() {
        // G4, at the pixel level. It holds only because a renderer is a pure function of its
        // inputs — including the grain of a pencil, which is seeded from the stroke's own id.
        val view = canvas()
        view.setStrokes(SproutPen.entries.mapIndexed { index, pen ->
            stroke("stroke-$pen", pen = pen, y = 10f + index * 20f)
        })
        val before = snapshot(view)

        view.setStrokes(view.getStrokes())

        assertTrue("re-ingesting changed the pixels", before.sameAs(snapshot(view)))
    }

    @Test
    fun `every pen survives the round trip`() {
        SproutPen.entries.forEach { pen ->
            val view = canvas()
            view.setStrokes(listOf(stroke("only", pen = pen)))
            val before = snapshot(view)
            assertTrue("$pen drew nothing through the view", inkedPixels(before) > 0)

            view.setStrokes(view.getStrokes())
            assertTrue("$pen re-ingested differently", before.sameAs(snapshot(view)))
            root.removeAllViews()
        }
    }

    @Test
    fun `clearing the canvas removes the ink`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b", y = 100f)))
        assertTrue(inkedPixels(snapshot(view)) > 0)

        view.clear()

        assertEquals(0, inkedPixels(snapshot(view)))
    }

    @Test
    fun `removing one stroke leaves the other`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b", y = 100f)))
        val both = inkedPixels(snapshot(view))

        view.removeStrokes(listOf("a"))

        val remaining = inkedPixels(snapshot(view))
        assertTrue("a=$both remaining=$remaining", remaining in 1 until both)
    }

    // --- Resize (G8) ---------------------------------------------------------------------------

    @Test
    fun `content survives a resize`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a")))
        assertTrue(inkedPixels(snapshot(view)) > 0)

        view.layoutParams = FrameLayout.LayoutParams(SIZE + 60, SIZE + 40)
        idle()

        assertEquals(SIZE + 60, view.width)
        assertTrue("the ink did not survive the resize", inkedPixels(snapshot(view)) > 0)
    }

    @Test
    fun `content survives leaving its window and coming back`() {
        // Detaching discards the display list, which is GPU memory belonging to a window the view
        // has left. The strokes are the source of truth and the node is only ever a cache of them.
        val view = canvas()
        view.setStrokes(listOf(stroke("a")))
        val before = inkedPixels(snapshot(view))

        root.removeView(view)
        idle()
        root.addView(view, FrameLayout.LayoutParams(SIZE, SIZE))
        idle()

        assertEquals(before, inkedPixels(snapshot(view)))
    }

    // --- Erase --------------------------------------------------------------------------------

    @Test
    fun `an erase gesture removes the ink and reports it once`() {
        val view = canvas()
        val removed = mutableListOf<List<InkStroke>>()
        view.listener = object : SproutCanvasListener {
            override fun onStrokesRemoved(strokes: List<InkStroke>) {
                removed += strokes
            }
        }
        view.setStrokes(listOf(stroke("a"), stroke("b", y = 140f)))
        view.eraser = EraserSpec.DEFAULT

        // Straight down the stroke at y = 60, nowhere near the one at y = 140.
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, 30f, 60f))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, 60f, 60f, eventTime = 1_010L))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, 90f, 60f, eventTime = 1_020L))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, 110f, 60f, eventTime = 1_030L))
        idle()

        assertEquals(listOf("b"), view.getStrokes().map { it.id })
        // One swipe is one action to a user, so it is one callback — not one per move event.
        assertEquals(1, removed.size)
        assertEquals(listOf("a"), removed.single().map { it.id })
    }

    @Test
    fun `an erase gesture that touches nothing reports nothing`() {
        val view = canvas()
        var reports = 0
        view.listener = object : SproutCanvasListener {
            override fun onStrokesRemoved(strokes: List<InkStroke>) {
                reports++
            }
        }
        view.setStrokes(listOf(stroke("a")))
        view.eraser = EraserSpec.DEFAULT

        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, 30f, 180f))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, 90f, 180f, eventTime = 1_010L))
        idle()

        assertEquals(1, view.strokeCount)
        assertEquals(0, reports)
    }

    // --- Capture end to end ---------------------------------------------------------------------

    @Test
    fun `a drawn stroke is captured, committed and rendered`() {
        val view = canvas()
        val completed = mutableListOf<InkStroke>()
        view.listener = object : SproutCanvasListener {
            override fun onStrokeCompleted(stroke: InkStroke) {
                completed += stroke
            }
        }

        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, 20f, 20f))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, 60f, 60f, eventTime = 1_010L))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_MOVE, 100f, 100f, eventTime = 1_020L))
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, 140f, 140f, eventTime = 1_030L))
        idle()

        assertEquals(1, completed.size)
        assertEquals(4, completed.single().sampleCount)
        assertEquals(1, view.strokeCount)
        assertTrue("the committed stroke was not drawn", inkedPixels(snapshot(view)) > 0)
    }

    @Test
    fun `the pen-activity gate opens and closes around a stroke`() {
        val view = canvas()
        val states = mutableListOf<Boolean>()
        view.listener = object : SproutCanvasListener {
            override fun onPenActiveChanged(active: Boolean) {
                states += active
            }
        }

        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, 20f, 20f))
        assertTrue(view.isPenActive)
        view.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, 60f, 60f, eventTime = 1_010L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(listOf(true, false), states)
        assertTrue(!view.isPenActive)
    }

    private fun stylus(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long = 1_000L,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.5f
        }
        return MotionEvent.obtain(
            1_000L,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coords),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    private companion object {
        /** Comfortably inside Robolectric's small default screen, so the canvas is fully visible. */
        const val SIZE = 200
    }
}
