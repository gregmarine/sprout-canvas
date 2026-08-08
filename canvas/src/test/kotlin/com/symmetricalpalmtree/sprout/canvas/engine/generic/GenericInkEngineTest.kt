package com.symmetricalpalmtree.sprout.canvas.engine.generic

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost
import com.symmetricalpalmtree.sprout.canvas.engine.PenActivityGate
import com.symmetricalpalmtree.sprout.canvas.model.EraserMode
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The software engine's capture contract, driven by synthesized stylus input.
 *
 * ### What is asserted here and what is not
 *
 * Everything that is *logic*: which events become strokes, where capture stops, when the eraser
 * fires, how the pen-activity gate opens and closes. All of it is deterministic and none of it needs
 * hardware.
 *
 * What is **not** asserted here is channel capture. Robolectric has no `InputDevice` behind a
 * synthesized event, so the engine correctly probes an unknown digitizer and reports the one channel
 * it can always fill. Proving that injected pressure, tilt and orientation survive into a stroke
 * needs a real device reporting real motion ranges — that is the instrumented suite's job
 * (PLAN.md §4.2), and it is the one part of this engine a JVM test genuinely cannot cover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GenericInkEngineTest {

    private lateinit var activity: Activity
    private lateinit var view: View
    private lateinit var host: RecordingHost
    private lateinit var engine: GenericInkEngine

    private val downTime = 1_000L

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity)
        host = RecordingHost(activity)
        engine = GenericInkEngine(host)
        engine.attach(view)
        engine.onBoundsChanged(Rect(0, 0, CANVAS_SIZE, CANVAS_SIZE), Point(0, 0))
        engine.setTool(ToolSpec.DEFAULT)
        engine.resume()
    }

    @After
    fun tearDown() {
        engine.detach()
    }

    // --- Capture ------------------------------------------------------------------------------

    @Test
    fun `a stylus down, move and up produces one stroke`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 40f, 40f, eventTime = downTime + 10))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 70f, 70f, eventTime = downTime + 20))

        assertEquals(1, host.seeds.size)
        assertEquals(1, host.ended.size)
        assertEquals(host.seeds.single().id, host.ended.single())
        assertEquals(3, host.sampleCount(host.seeds.single().id))
    }

    @Test
    fun `the engine consumes stylus events and leaves everything else alone`() {
        assertTrue(engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f)))
        assertFalse(engine.onTouchEvent(finger(MotionEvent.ACTION_DOWN, 20f, 20f)))
    }

    @Test
    fun `a stylus event it refuses to capture is still consumed`() {
        // Returning false from ACTION_DOWN makes Android stop delivering the rest of the gesture,
        // so the ACTION_UP that closes the pen-activity gate would never arrive. The gate would
        // latch open and suppress the host's chrome for the rest of the session.
        engine.onExclusionZonesChanged(listOf(Rect(0, 0, 100, 100)))
        assertTrue(engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 50f, 50f)))
        assertTrue(engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, CANVAS_SIZE + 40f, 10f)))

        engine.pause()
        assertTrue(engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 150f, 150f)))
    }

    @Test
    fun `a stroke refused at pen-down still closes the gate`() {
        engine.onExclusionZonesChanged(listOf(Rect(0, 0, 100, 100)))

        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 50f, 50f))
        assertTrue(engine.isPenActive)
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 60f, 60f, eventTime = downTime + 10))
        idleFor(PenActivityGate.TAIL_MS + 50)

        assertFalse("the gate latched open on a refused stroke", engine.isPenActive)
        assertEquals(listOf(true, false), host.penActive)
    }

    @Test
    fun `a finger draws nothing`() {
        // A canvas that inked on finger contact would turn a resting palm into a scribble, and
        // hosts routinely wrap one of these in a scrolling container.
        engine.onTouchEvent(finger(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(finger(MotionEvent.ACTION_MOVE, 40f, 40f))
        engine.onTouchEvent(finger(MotionEvent.ACTION_UP, 70f, 70f))
        assertTrue(host.seeds.isEmpty())
    }

    @Test
    fun `historical samples are harvested, not discarded`() {
        // At writing speed most of a stroke lives in the history buffer. An engine that read only
        // getX/getY would throw away the majority of every stroke and draw visible polygons.
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        val move = stylus(MotionEvent.ACTION_MOVE, 20f, 20f, eventTime = downTime + 5)
        move.addBatch(downTime + 10, arrayOf(coordsAt(30f, 30f)), 0)
        move.addBatch(downTime + 15, arrayOf(coordsAt(40f, 40f)), 0)
        engine.onTouchEvent(move)
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 50f, 50f, eventTime = downTime + 20))

        // down + three from the batched move + up
        assertEquals(5, host.sampleCount(host.seeds.single().id))
    }

    @Test
    fun `a stroke keeps the tool armed when it started`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.setTool(ToolSpec(pen = SproutPen.CHARCOAL, widthDp = 9f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 40f, 40f, eventTime = downTime + 10))

        // A stroke that changed pen halfway through is not something any device can render, and not
        // something a user could have meant.
        assertEquals(SproutPen.BALLPOINT, host.seeds.single().tool.pen)
    }

    @Test
    fun `a tap is a dot, not nothing`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 10f, 10f, eventTime = downTime + 5))
        assertEquals(1, host.seeds.size)
        assertTrue(host.sampleCount(host.seeds.single().id) >= 1)
    }

    @Test
    fun `a paused engine captures nothing`() {
        engine.pause()
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 40f, 40f, eventTime = downTime + 10))
        assertTrue(host.seeds.isEmpty())
    }

    @Test
    fun `detaching mid-stroke commits what was captured`() {
        // Navigating away with the pen down is a real thing users do. The ink drawn so far is ink
        // they drew.
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 40f, 40f, eventTime = downTime + 10))
        engine.detach()
        assertEquals(1, host.ended.size)
    }

    // --- Bounds and exclusion zones ---------------------------------------------------------

    @Test
    fun `a stroke cannot begin outside the capture region`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, CANVAS_SIZE + 50f, 10f))
        assertTrue(host.seeds.isEmpty())
    }

    @Test
    fun `a stroke that wanders out of the canvas stops there`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 50f, 50f, eventTime = downTime + 10))
        engine.onTouchEvent(
            stylus(MotionEvent.ACTION_MOVE, CANVAS_SIZE + 100f, 50f, eventTime = downTime + 20),
        )
        engine.onTouchEvent(
            stylus(MotionEvent.ACTION_MOVE, CANVAS_SIZE + 120f, 60f, eventTime = downTime + 30),
        )

        assertEquals(1, host.ended.size)
        assertEquals(2, host.sampleCount(host.seeds.single().id))
    }

    @Test
    fun `a stroke cannot begin inside an exclusion zone`() {
        engine.onExclusionZonesChanged(listOf(Rect(0, 0, 100, 100)))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 50f, 50f))
        assertTrue(host.seeds.isEmpty())
    }

    @Test
    fun `a stroke that wanders under a toolbar stops at its edge`() {
        // Uniform on every engine: no capture inside a zone, period. This matches what the Onyx
        // hardware limit rect does, so the software engines are written to match the hardware.
        engine.onExclusionZonesChanged(listOf(Rect(150, 0, CANVAS_SIZE, CANVAS_SIZE)))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 100f, 10f, eventTime = downTime + 10))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 200f, 10f, eventTime = downTime + 20))

        assertEquals(1, host.ended.size)
        assertEquals(2, host.sampleCount(host.seeds.single().id))
    }

    @Test
    fun `an empty capture region captures nothing`() {
        // A canvas scrolled off screen must capture nothing, not everything.
        engine.onBoundsChanged(Rect(), Point(0, 0))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertTrue(host.seeds.isEmpty())
    }

    // --- Erase --------------------------------------------------------------------------------

    @Test
    fun `the eraser tool type erases instead of drawing`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f, toolType = MotionEvent.TOOL_TYPE_ERASER))
        engine.onTouchEvent(
            stylus(MotionEvent.ACTION_MOVE, 40f, 40f, toolType = MotionEvent.TOOL_TYPE_ERASER, eventTime = downTime + 10),
        )
        engine.onTouchEvent(
            stylus(MotionEvent.ACTION_UP, 70f, 70f, toolType = MotionEvent.TOOL_TYPE_ERASER, eventTime = downTime + 20),
        )

        assertTrue(host.seeds.isEmpty())
        assertEquals(3, host.erasePaths.size)
        assertEquals(1, host.eraseEnds)
    }

    @Test
    fun `the barrel button erases whatever tool is armed`() {
        // The hardware reports the button whether the library asks or not; an app that shipped
        // without erase-on-button would feel broken (PLAN.md §5.4).
        engine.setTool(ToolSpec(pen = SproutPen.FOUNTAIN))
        engine.onTouchEvent(
            stylus(MotionEvent.ACTION_DOWN, 10f, 10f, buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY),
        )
        engine.onTouchEvent(
            stylus(
                MotionEvent.ACTION_UP,
                40f,
                40f,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
                eventTime = downTime + 10,
            ),
        )

        assertTrue(host.seeds.isEmpty())
        assertTrue(host.erasePaths.isNotEmpty())
        assertEquals(1, host.eraseEnds)
    }

    @Test
    fun `an armed eraser erases with an ordinary stylus`() {
        engine.setEraser(EraserSpec(EraserMode.STROKE, widthDp = 20f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 40f, 40f, eventTime = downTime + 10))

        assertTrue(host.seeds.isEmpty())
        assertEquals(1, host.eraseEnds)
        // Radius, not diameter: half of 20 dp at Robolectric's 1× density.
        assertEquals(10f, host.eraseRadii.last(), 1e-3f)
    }

    @Test
    fun `erasing under a toolbar erases nothing`() {
        engine.setEraser(EraserSpec.DEFAULT)
        engine.onExclusionZonesChanged(listOf(Rect(0, 0, 100, 100)))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 50f, 50f))
        assertTrue(host.erasePaths.isEmpty())
    }

    @Test
    fun `pressing the barrel button mid-stroke ends the stroke rather than annotating it`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 40f, 40f, eventTime = downTime + 10))
        engine.onTouchEvent(
            stylus(
                MotionEvent.ACTION_MOVE,
                60f,
                60f,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
                eventTime = downTime + 20,
            ),
        )

        assertEquals(1, host.ended.size)
        assertTrue(host.erasePaths.isNotEmpty())
    }

    @Test
    fun `an erase gesture reports its end exactly once`() {
        engine.setEraser(EraserSpec.DEFAULT)
        repeat(2) { pass ->
            val time = downTime + pass * 100
            engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f, eventTime = time))
            engine.onTouchEvent(stylus(MotionEvent.ACTION_MOVE, 20f, 20f, eventTime = time + 5))
            engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 30f, 30f, eventTime = time + 10))
        }
        assertEquals(2, host.eraseEnds)
    }

    // --- The pen-activity gate ----------------------------------------------------------------

    @Test
    fun `the gate opens on contact and stays open past the double-tap window`() {
        assertFalse(engine.isPenActive)

        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertTrue(engine.isPenActive)
        assertEquals(listOf(true), host.penActive)

        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 40f, 40f, eventTime = downTime + 10))
        // Still active: the tail is what stops the second half of a palm-induced double tap from
        // landing just after the pen leaves the glass and passing for a deliberate gesture.
        assertTrue(engine.isPenActive)
        assertEquals(listOf(true), host.penActive)

        idleFor(PenActivityGate.TAIL_MS + 50)
        assertFalse(engine.isPenActive)
        assertEquals(listOf(true, false), host.penActive)
    }

    @Test
    fun `the gate opens even where capture is not allowed`() {
        // Its job is to tell the host a pen is on the glass. A stroke starting outside the bounds
        // does not make that less true, and the host's chrome still needs to know.
        engine.onExclusionZonesChanged(listOf(Rect(0, 0, 100, 100)))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 50f, 50f))
        assertTrue(engine.isPenActive)
    }

    @Test
    fun `writing again before the tail expires keeps the gate open`() {
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 10f, 10f))
        engine.onTouchEvent(stylus(MotionEvent.ACTION_UP, 20f, 20f, eventTime = downTime + 10))
        idleFor(100)
        engine.onTouchEvent(stylus(MotionEvent.ACTION_DOWN, 30f, 30f, eventTime = downTime + 110))
        idleFor(PenActivityGate.TAIL_MS + 50)

        assertTrue("the gate closed while the pen was still down", engine.isPenActive)
        assertEquals(listOf(true), host.penActive)
    }

    @Test
    fun `a finger never opens the gate`() {
        engine.onTouchEvent(finger(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertFalse(engine.isPenActive)
        assertTrue(host.penActive.isEmpty())
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun idleFor(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun coordsAt(x: Float, y: Float): MotionEvent.PointerCoords =
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.5f
            size = 0.1f
        }

    private fun stylus(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
        eventTime: Long = downTime,
    ): MotionEvent = motionEvent(action, x, y, toolType, buttonState, eventTime)

    private fun finger(action: Int, x: Float, y: Float): MotionEvent =
        motionEvent(action, x, y, MotionEvent.TOOL_TYPE_FINGER, 0, downTime)

    private fun motionEvent(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int,
        buttonState: Int,
        eventTime: Long,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordsAt(x, y)),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    /** Records everything the engine reports, so each assertion can be about one thing. */
    private class RecordingHost(override val context: Context) : InkEngineHost {
        val seeds = mutableListOf<StrokeSeed>()
        val samples = mutableMapOf<String, Int>()
        val ended = mutableListOf<String>()
        val erasePaths = mutableListOf<List<PointF>>()
        val eraseRadii = mutableListOf<Float>()
        var eraseEnds = 0
        val penActive = mutableListOf<Boolean>()

        fun sampleCount(strokeId: String): Int = samples[strokeId] ?: 0

        override fun onStrokeBegan(seed: StrokeSeed) {
            seeds += seed
        }

        override fun onStrokeSamples(strokeId: String, samples: StrokeSamples) {
            this.samples[strokeId] = (this.samples[strokeId] ?: 0) + samples.count
        }

        override fun onStrokeEnded(strokeId: String) {
            ended += strokeId
        }

        override fun onEraseAt(path: List<PointF>, radiusPx: Float) {
            erasePaths += path
            eraseRadii += radiusPx
        }

        override fun onEraseEnded() {
            eraseEnds++
        }

        override fun onPenActiveChanged(active: Boolean) {
            penActive += active
        }

        override fun requestInvalidate() = Unit

        override fun requestCommittedRepaint(region: Rect?) = Unit
    }

    private companion object {
        const val CANVAS_SIZE = 300
    }
}
