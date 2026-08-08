package com.symmetricalpalmtree.sprout.canvas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The instrumented suite (PLAN.md §4.2): the canvas on real hardware, driven by synthesized stylus
 * input.
 *
 * ### What this tier is for
 *
 * Everything logical about capture is already covered on the JVM, in milliseconds, on every build.
 * What a JVM test cannot supply is a **real `InputDevice`**: Robolectric has no digitizer behind a
 * synthesized event, so the engine correctly reports the one channel it can always fill and the
 * question of whether injected pressure, tilt, orientation and size survive into an [InkStroke] goes
 * unanswered. That question is the reason this tier exists.
 *
 * ### How stylus input is faked
 *
 * `MotionEvent.obtain` with `toolType = TOOL_TYPE_STYLUS` and the axes populated, sent with the
 * **device id of a real digitizer on this machine**. That last part is the whole trick: an event
 * with no device behind it looks to the engine exactly like a device that reports nothing, which is
 * both correct behaviour and useless for this test. Where the hardware declares no such axis the
 * assertion is skipped rather than faked — a tablet without a tilt-reporting stylus is not a
 * failure, it is a tablet.
 */
@RunWith(AndroidJUnit4::class)
class CanvasInstrumentedTest {

    // ---------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------

    @Test
    fun aStrokeIsCapturedCommittedAndRendered() {
        withCanvas { canvas ->
            val completed = mutableListOf<InkStroke>()
            canvas.listener = object : SproutCanvasListener {
                override fun onStrokeCompleted(stroke: InkStroke) {
                    completed += stroke
                }
            }

            drawLine(canvas, fromX = 40f, fromY = 40f, toX = 240f, toY = 200f, steps = 8)

            assertEquals(1, completed.size)
            assertEquals(1, canvas.strokeCount)
            assertTrue("nothing was drawn", inkedPixels(snapshot(canvas)) > 0)
        }
    }

    @Test
    fun historicalSamplesAreHarvested() {
        withCanvas { canvas ->
            val completed = mutableListOf<InkStroke>()
            canvas.listener = object : SproutCanvasListener {
                override fun onStrokeCompleted(stroke: InkStroke) {
                    completed += stroke
                }
            }

            send(canvas, MotionEvent.ACTION_DOWN, 40f, 40f)
            val move = event(MotionEvent.ACTION_MOVE, 80f, 80f, eventTime = downTime + 10)
            move.addBatch(downTime + 15, arrayOf(coords(120f, 120f)), 0)
            move.addBatch(downTime + 20, arrayOf(coords(160f, 160f)), 0)
            dispatch(canvas, move)
            send(canvas, MotionEvent.ACTION_UP, 200f, 200f, eventTime = downTime + 30)

            // down + three from the batched move + up. At writing speed most of a stroke lives in
            // that history buffer, so losing it means losing most of every stroke.
            assertEquals(5, completed.single().sampleCount)
        }
    }

    @Test
    fun aFingerDrawsNothing() {
        withCanvas { canvas ->
            drawLine(
                canvas,
                fromX = 40f,
                fromY = 40f,
                toX = 240f,
                toY = 200f,
                steps = 6,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
            )
            assertEquals(0, canvas.strokeCount)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Channel capture — the reason this tier exists
    // ---------------------------------------------------------------------------------------

    @Test
    fun theEngineReportsWhatTheDigitizerDeclares() {
        val device = stylusDevice()
        assumeTrue("no digitizer on this device declares any stylus axis", device != null)

        withCanvas { canvas ->
            drawLine(canvas, 40f, 40f, 240f, 200f, steps = 6)
            val channels = canvas.capabilities.channels

            assertEquals(
                "pressure: declared ${device!!.getMotionRange(MotionEvent.AXIS_PRESSURE) != null}",
                device.getMotionRange(MotionEvent.AXIS_PRESSURE) != null,
                InkChannel.contains(channels, InkChannel.PRESSURE),
            )
            assertEquals(
                "altitude (AXIS_TILT)",
                device.getMotionRange(MotionEvent.AXIS_TILT) != null,
                InkChannel.contains(channels, InkChannel.ALTITUDE),
            )
            assertEquals(
                "orientation",
                device.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null,
                InkChannel.contains(channels, InkChannel.ORIENTATION),
            )
            assertEquals(
                "size",
                device.getMotionRange(MotionEvent.AXIS_SIZE) != null,
                InkChannel.contains(channels, InkChannel.SIZE),
            )
            // Vendor tilt has no meaning on the ordinary Android input path and must never be
            // claimed here — raw tiltX/tiltY belongs to the vendor engines (PLAN.md §3.5).
            assertTrue(
                "the generic engine claimed vendor tilt",
                !InkChannel.contains(channels, InkChannel.TILT),
            )
            // Event time needs no hardware support, so it is always available.
            assertTrue(InkChannel.contains(channels, InkChannel.TIMESTAMP))
        }
    }

    @Test
    fun capabilitiesAreNotDowngradedByAStrokeFromALesserDevice() {
        // Found on a Wacom Movink Pad by injecting `adb shell input stylus swipe`: that arrives from
        // a *virtual* input device declaring no axes at all, and the engine used to adopt it —
        // permanently reporting TIMESTAMP-only on hardware with a pressure-sensitive pen. A tool
        // picker built on that would grey out the pressure pens and never turn them back on.
        // A knuckle on the glass and a capacitive stylus on an EMR tablet look the same way.
        val device = stylusDevice()
        assumeTrue(
            "this digitizer declares no pressure axis",
            device?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null,
        )

        withCanvas { canvas ->
            drawLine(canvas, 40f, 40f, 240f, 200f, steps = 6)
            assertTrue(
                "the connected pen's pressure channel was never picked up",
                InkChannel.contains(canvas.capabilities.channels, InkChannel.PRESSURE),
            )

            // Now a stroke from a device that declares nothing.
            send(canvas, MotionEvent.ACTION_DOWN, 40f, 300f, eventTime = downTime + 1_000, startTime = downTime + 1_000, deviceId = NO_SUCH_DEVICE)
            send(canvas, MotionEvent.ACTION_MOVE, 140f, 320f, eventTime = downTime + 1_010, startTime = downTime + 1_000, deviceId = NO_SUCH_DEVICE)
            send(canvas, MotionEvent.ACTION_UP, 240f, 340f, eventTime = downTime + 1_020, startTime = downTime + 1_000, deviceId = NO_SUCH_DEVICE)
            drainMainThread()

            assertTrue(
                "one stroke from an axis-less device retracted the hardware's pressure channel",
                InkChannel.contains(canvas.capabilities.channels, InkChannel.PRESSURE),
            )
        }
    }

    @Test
    fun injectedPressureSurvivesIntoTheStroke() {
        val device = stylusDevice()
        assumeTrue(
            "this digitizer declares no pressure axis",
            device?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null,
        )

        withCanvas { canvas ->
            val completed = mutableListOf<InkStroke>()
            canvas.listener = object : SproutCanvasListener {
                override fun onStrokeCompleted(stroke: InkStroke) {
                    completed += stroke
                }
            }

            val pressures = listOf(0.1f, 0.4f, 0.7f, 1.0f)
            send(canvas, MotionEvent.ACTION_DOWN, 40f, 40f, pressure = pressures[0])
            pressures.drop(1).forEachIndexed { index, pressure ->
                send(
                    canvas,
                    MotionEvent.ACTION_MOVE,
                    40f + (index + 1) * 50f,
                    40f + (index + 1) * 40f,
                    pressure = pressure,
                    eventTime = downTime + (index + 1) * 10L,
                )
            }
            send(canvas, MotionEvent.ACTION_UP, 220f, 180f, pressure = 1f, eventTime = downTime + 40)

            val samples = completed.single().samples
            assertNotNull("pressure was not captured", samples.pressure)
            val captured = samples.pressure!!
            // Stored normalized against the device's own maximum, never against a hardcoded one:
            // the divisor is 4095 on some digitizers and 4096 on others (PLAN.md §5.6).
            assertTrue(captured.all { it in 0f..1f })
            assertTrue(
                "pressure did not vary across the stroke: ${captured.toList()}",
                captured.max() > captured.min(),
            )
        }
    }

    @Test
    fun injectedAxesSurviveIntoTheStroke() {
        val device = stylusDevice()
        assumeTrue("no digitizer declares tilt, orientation or size", device != null)

        val declaresTilt = device!!.getMotionRange(MotionEvent.AXIS_TILT) != null
        val declaresOrientation = device.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null
        val declaresSize = device.getMotionRange(MotionEvent.AXIS_SIZE) != null
        assumeTrue(declaresTilt || declaresOrientation || declaresSize)

        withCanvas { canvas ->
            val completed = mutableListOf<InkStroke>()
            canvas.listener = object : SproutCanvasListener {
                override fun onStrokeCompleted(stroke: InkStroke) {
                    completed += stroke
                }
            }

            send(canvas, MotionEvent.ACTION_DOWN, 40f, 40f, tilt = 0.2f, orientation = 0.3f, size = 0.1f)
            send(
                canvas,
                MotionEvent.ACTION_MOVE,
                140f,
                120f,
                tilt = 0.6f,
                orientation = 0.9f,
                size = 0.4f,
                eventTime = downTime + 10,
            )
            send(
                canvas,
                MotionEvent.ACTION_UP,
                220f,
                180f,
                tilt = 0.9f,
                orientation = 1.2f,
                size = 0.6f,
                eventTime = downTime + 20,
            )

            val samples = completed.single().samples
            if (declaresTilt) {
                assertNotNull("altitude was dropped", samples.altitude)
                assertTrue(samples.altitude!!.max() > samples.altitude!!.min())
            }
            if (declaresOrientation) {
                assertNotNull("orientation was dropped", samples.orientation)
            }
            if (declaresSize) {
                assertNotNull("size was dropped", samples.size)
            }
            assertNotNull("timestamps were dropped", samples.timestampMs)
            assertTrue(samples.timestampMs!!.last() > samples.timestampMs!!.first())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Eraser
    // ---------------------------------------------------------------------------------------

    @Test
    fun theEraserToolTypeErases() {
        withCanvas { canvas ->
            drawLine(canvas, 40f, 100f, 240f, 100f, steps = 6)
            assertEquals(1, canvas.strokeCount)

            drawLine(
                canvas,
                40f,
                100f,
                240f,
                100f,
                steps = 6,
                toolType = MotionEvent.TOOL_TYPE_ERASER,
                startTime = downTime + 1_000,
            )
            assertEquals(0, canvas.strokeCount)
        }
    }

    @Test
    fun theBarrelButtonErasesWhateverToolIsArmed() {
        // The hardware reports the button whether the library asks or not (PLAN.md §5.4).
        withCanvas { canvas ->
            canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 3f)
            drawLine(canvas, 40f, 100f, 240f, 100f, steps = 6)
            assertEquals(1, canvas.strokeCount)

            drawLine(
                canvas,
                40f,
                100f,
                240f,
                100f,
                steps = 6,
                buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
                startTime = downTime + 1_000,
            )
            assertEquals(0, canvas.strokeCount)
        }
    }

    @Test
    fun anArmedEraserRemovesOnlyWhatItTouches() {
        withCanvas { canvas ->
            drawLine(canvas, 40f, 60f, 240f, 60f, steps = 6)
            drawLine(canvas, 40f, 220f, 240f, 220f, steps = 6, startTime = downTime + 1_000)
            assertEquals(2, canvas.strokeCount)

            canvas.eraser = EraserSpec.DEFAULT
            drawLine(canvas, 40f, 60f, 240f, 60f, steps = 6, startTime = downTime + 2_000)

            assertEquals(1, canvas.strokeCount)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bounds and exclusion zones
    // ---------------------------------------------------------------------------------------

    @Test
    fun inputOutsideTheCanvasNeverReachesIt() {
        withCanvas { canvas ->
            val outside = canvas.height + 200f
            drawLine(canvas, 40f, outside, 240f, outside + 40f, steps = 6)
            assertEquals(0, canvas.strokeCount)
        }
    }

    @Test
    fun nothingIsCapturedInsideAnExclusionZone() {
        withCanvas { canvas ->
            canvas.addExclusionZone(Rect(0, 0, canvas.width, 120), id = "toolbar")
            waitForZones(canvas)

            drawLine(canvas, 40f, 40f, 240f, 60f, steps = 6)
            assertEquals(0, canvas.strokeCount)

            drawLine(canvas, 40f, 200f, 240f, 220f, steps = 6, startTime = downTime + 1_000)
            assertEquals(1, canvas.strokeCount)
        }
    }

    @Test
    fun aStrokeThatWandersUnderAToolbarStopsAtItsEdge() {
        withCanvas { canvas ->
            canvas.addExclusionZone(Rect(0, 0, canvas.width, 120), id = "toolbar")
            waitForZones(canvas)

            val completed = mutableListOf<InkStroke>()
            canvas.listener = object : SproutCanvasListener {
                override fun onStrokeCompleted(stroke: InkStroke) {
                    completed += stroke
                }
            }

            // Starts well below the toolbar and runs up into it.
            send(canvas, MotionEvent.ACTION_DOWN, 60f, 300f)
            send(canvas, MotionEvent.ACTION_MOVE, 60f, 250f, eventTime = downTime + 10)
            send(canvas, MotionEvent.ACTION_MOVE, 60f, 200f, eventTime = downTime + 20)
            send(canvas, MotionEvent.ACTION_MOVE, 60f, 60f, eventTime = downTime + 30)
            send(canvas, MotionEvent.ACTION_MOVE, 60f, 30f, eventTime = downTime + 40)
            send(canvas, MotionEvent.ACTION_UP, 60f, 20f, eventTime = downTime + 50)

            val stroke = completed.single()
            assertEquals(3, stroke.sampleCount)
            assertTrue(
                "ink landed inside the zone: ${stroke.bounds}",
                stroke.bounds.top >= 120f,
            )
        }
    }

    @Test
    fun hidingATrackedOverlayReleasesItsZone() {
        // Found on a Wacom Movink Pad: the Lab reported two armed zones with one overlay on screen.
        // A GONE view is never laid out, so watching each view's own layout misses it entirely and
        // the canvas keeps a dead region where a dismissed popup used to be.
        //
        // Both spellings of "hidden" are checked here rather than on the JVM because whether a
        // visibility change reaches a tree observer is a framework behaviour, and the framework is
        // the thing worth asking.
        withCanvas { canvas ->
            lateinit var overlay: View
            onMain {
                overlay = View(canvas.context)
                (canvas.parent as FrameLayout).addView(overlay, FrameLayout.LayoutParams(120, 80))
                canvas.addExclusionZone(overlay, id = "overlay")
            }
            waitForZones(canvas)
            onMain { assertEquals(1, canvas.activeExclusionZones().size) }

            onMain { overlay.visibility = View.GONE }
            drainMainThread()
            drainMainThread()
            onMain {
                assertTrue(
                    "a GONE overlay kept its zone armed",
                    canvas.activeExclusionZones().isEmpty(),
                )
            }

            onMain { overlay.visibility = View.VISIBLE }
            drainMainThread()
            drainMainThread()
            onMain { assertEquals(1, canvas.activeExclusionZones().size) }

            onMain { overlay.visibility = View.INVISIBLE }
            drainMainThread()
            drainMainThread()
            onMain {
                assertTrue(
                    "an INVISIBLE overlay kept its zone armed",
                    canvas.activeExclusionZones().isEmpty(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ingest, resize, multi-canvas
    // ---------------------------------------------------------------------------------------

    @Test
    fun handingTheCanvasItsOwnStrokesBackIsAVisualNoOp() {
        // G4 on real hardware, at the pixel level.
        withCanvas { canvas ->
            SproutPen.entries.forEachIndexed { index, pen ->
                canvas.tool = ToolSpec(pen = pen, widthDp = 3f, color = Color.BLACK)
                drawLine(
                    canvas,
                    40f,
                    30f + index * 30f,
                    240f,
                    40f + index * 30f,
                    steps = 6,
                    startTime = downTime + index * 1_000L,
                )
            }
            assertEquals(SproutPen.entries.size, canvas.strokeCount)

            val before = snapshot(canvas)
            canvas.setStrokes(canvas.getStrokes())
            assertTrue("re-ingesting changed the pixels", before.sameAs(snapshot(canvas)))
        }
    }

    @Test
    fun contentSurvivesAResize() {
        withCanvas { canvas ->
            drawLine(canvas, 40f, 100f, 240f, 160f, steps = 6)
            assertTrue(inkedPixels(snapshot(canvas)) > 0)

            onMain {
                canvas.layoutParams = FrameLayout.LayoutParams(CANVAS_WIDTH - 60, CANVAS_HEIGHT - 40)
                canvas.requestLayout()
            }
            waitForLayout(canvas)

            assertEquals(1, canvas.strokeCount)
            assertTrue("the ink did not survive the resize", inkedPixels(snapshot(canvas)) > 0)
        }
    }

    @Test
    fun twoCanvasesInOneWindowBothWork() {
        // The shape of the process-global pipeline hazard the vendor adapters have to survive
        // (PLAN.md §5.2). On the software engine there is no shared resource to lose, which is
        // exactly why this is the baseline the hardware behaviour will be compared against.
        val scenario = ActivityScenario.launch(CanvasHostActivity::class.java)
        try {
            lateinit var first: SproutCanvasView
            lateinit var second: SproutCanvasView
            scenario.onActivity { activity ->
                first = activity.addCanvas()
                second = activity.addCanvas()
            }
            waitForLayout(first)
            waitForLayout(second)

            drawLine(first, 20f, 20f, 200f, 100f, steps = 6)
            drawLine(second, 20f, 20f, 200f, 100f, steps = 6, startTime = downTime + 1_000)

            assertEquals(1, first.strokeCount)
            assertEquals(1, second.strokeCount)
            assertEquals(EngineIds.GENERIC, first.engineInfo.id)
            assertEquals(EngineIds.GENERIC, second.engineInfo.id)
        } finally {
            scenario.close()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------

    /** An empty host for canvases, so each test lays one out the way a real app would. */
    class CanvasHostActivity : Activity() {

        private lateinit var root: FrameLayout

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            root = FrameLayout(this)
            setContentView(root)
        }

        fun addCanvas(): SproutCanvasView {
            val canvas = SproutCanvasView(this)
            root.addView(canvas, FrameLayout.LayoutParams(CANVAS_WIDTH, CANVAS_HEIGHT))
            return canvas
        }
    }

    private fun withCanvas(block: (SproutCanvasView) -> Unit) {
        val scenario = ActivityScenario.launch(CanvasHostActivity::class.java)
        try {
            lateinit var canvas: SproutCanvasView
            scenario.onActivity { canvas = it.addCanvas() }
            waitForLayout(canvas)
            block(canvas)
        } finally {
            scenario.close()
        }
    }

    private val downTime = 1_000L

    /**
     * A digitizer to attribute synthesized events to.
     *
     * A stylus source first; failing that any touchscreen, which still declares real motion ranges
     * and is what most tablets expose when no pen is in range.
     */
    private fun stylusDevice(): InputDevice? {
        val devices = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
        return devices.firstOrNull { it.supportsSource(InputDevice.SOURCE_STYLUS) }
            ?: InputDevice.getDeviceIds().asSequence()
                .mapNotNull { InputDevice.getDevice(it) }
                .firstOrNull { it.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) }
    }

    private fun coords(
        x: Float,
        y: Float,
        pressure: Float = 0.5f,
        tilt: Float = 0.4f,
        orientation: Float = 0.2f,
        size: Float = 0.2f,
    ): MotionEvent.PointerCoords = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        this.pressure = pressure
        this.size = size
        setAxisValue(MotionEvent.AXIS_TILT, tilt)
        setAxisValue(MotionEvent.AXIS_ORIENTATION, orientation)
    }

    private fun event(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
        pressure: Float = 0.5f,
        tilt: Float = 0.4f,
        orientation: Float = 0.2f,
        size: Float = 0.2f,
        eventTime: Long = downTime,
        startTime: Long = downTime,
        deviceId: Int = stylusDevice()?.id ?: 0,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        return MotionEvent.obtain(
            startTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coords(x, y, pressure, tilt, orientation, size)),
            0,
            buttonState,
            1f,
            1f,
            // Attributing the event to a real digitizer is what makes the engine's probe find real
            // motion ranges. Without it the engine sees a device that reports nothing — correct,
            // and useless for testing channel capture.
            deviceId,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    private fun dispatch(canvas: SproutCanvasView, event: MotionEvent) {
        onMain { canvas.dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun send(
        canvas: SproutCanvasView,
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
        pressure: Float = 0.5f,
        tilt: Float = 0.4f,
        orientation: Float = 0.2f,
        size: Float = 0.2f,
        eventTime: Long = downTime,
        startTime: Long = downTime,
        deviceId: Int = stylusDevice()?.id ?: 0,
    ) {
        dispatch(
            canvas,
            event(
                action, x, y, toolType, buttonState, pressure, tilt, orientation, size,
                eventTime, startTime, deviceId,
            ),
        )
    }

    private fun drawLine(
        canvas: SproutCanvasView,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
        startTime: Long = downTime,
    ) {
        send(canvas, MotionEvent.ACTION_DOWN, fromX, fromY, toolType, buttonState, eventTime = startTime, startTime = startTime)
        for (step in 1 until steps) {
            val t = step / (steps - 1f)
            send(
                canvas,
                MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * t,
                fromY + (toY - fromY) * t,
                toolType,
                buttonState,
                eventTime = startTime + step * 10L,
                startTime = startTime,
            )
        }
        send(
            canvas,
            MotionEvent.ACTION_UP,
            toX,
            toY,
            toolType,
            buttonState,
            eventTime = startTime + steps * 10L,
            startTime = startTime,
        )
        drainMainThread()
    }

    private fun snapshot(canvas: SproutCanvasView): Bitmap {
        lateinit var bitmap: Bitmap
        onMain {
            bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
            val target = Canvas(bitmap)
            target.drawColor(Color.WHITE)
            canvas.draw(target)
        }
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

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** Blocks until everything already posted to the main thread has run. */
    private fun drainMainThread() = onMain { }

    private fun waitForLayout(view: View) {
        val latch = CountDownLatch(1)
        onMain {
            if (view.width > 0 && view.height > 0 && view.isAttachedToWindow) {
                latch.countDown()
            } else {
                view.viewTreeObserver.addOnGlobalLayoutListener(
                    object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            if (view.width > 0 && view.height > 0) {
                                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                latch.countDown()
                            }
                        }
                    },
                )
            }
        }
        assertTrue("the canvas never got a size", latch.await(5, TimeUnit.SECONDS))
        drainMainThread()
    }

    /** Exclusion zones are coalesced into one posted pass, so they land a frame after registration. */
    private fun waitForZones(canvas: SproutCanvasView) {
        drainMainThread()
        drainMainThread()
        onMain {
            assertTrue(
                "no exclusion zone was armed",
                canvas.activeExclusionZones().isNotEmpty(),
            )
        }
    }

    private companion object {
        const val CANVAS_WIDTH = 320
        const val CANVAS_HEIGHT = 400

        /** An input device id nothing is registered under, so `event.device` comes back null. */
        const val NO_SUCH_DEVICE = -7
    }
}
