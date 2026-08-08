package com.symmetricalpalmtree.sprout.canvas

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngine
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineFactory
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost
import com.symmetricalpalmtree.sprout.canvas.engine.NoOpInkEngineFactory
import com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
import com.symmetricalpalmtree.sprout.canvas.engine.RepaintReason
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.EraserMode
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The public surface of [SproutCanvasView], exercised against a recording engine.
 *
 * Nothing here draws — Phase 1 is the contract, not the renderer. What is asserted is that the
 * contract behaves as documented: content goes in and comes back out unchanged, the listener
 * reports exactly the changes it promises to report, and the engine is armed with the right
 * geometry at the right moments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class SproutCanvasViewTest {

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

    // --- Helpers ------------------------------------------------------------------------------

    /**
     * An attached, laid-out canvas.
     *
     * The size is given as real layout params and the framework's own layout pass applies it —
     * a hand-rolled `measure`/`layout` would be overwritten by the parent's pass and leave the
     * canvas at whatever the screen happens to be. [CANVAS_WIDTH] × [CANVAS_HEIGHT] fits inside
     * Robolectric's default screen, so the canvas is fully visible and its limit rect is its own
     * bounds rather than a clip of them.
     */
    private fun canvas(
        width: Int = CANVAS_WIDTH,
        height: Int = CANVAS_HEIGHT,
    ): SproutCanvasView {
        val view = SproutCanvasView(activity)
        root.addView(view, FrameLayout.LayoutParams(width, height))
        idle()
        return view
    }

    private fun resize(view: View, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(width, height)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun stroke(id: String, x: Float = 0f, y: Float = 0f) = InkStroke(
        id = id,
        samples = StrokeSamples(2, floatArrayOf(x, x + 10f), floatArrayOf(y, y + 10f)),
        tool = ToolSpec.DEFAULT,
        capture = CaptureInfo(EngineIds.NO_OP, DeviceCalibration.UNKNOWN, 0L, 1L),
    )

    private class Recorder : SproutCanvasListener {
        val completed = mutableListOf<InkStroke>()
        val removed = mutableListOf<List<InkStroke>>()
        val cleared = mutableListOf<List<InkStroke>>()
        val engines = mutableListOf<EngineInfo>()
        val penActive = mutableListOf<Boolean>()
        override fun onStrokeCompleted(stroke: InkStroke) { completed += stroke }
        override fun onStrokesRemoved(removed: List<InkStroke>) { this.removed += removed }
        override fun onCanvasCleared(removed: List<InkStroke>) { cleared += removed }
        override fun onEngineSelected(info: EngineInfo) { engines += info }
        override fun onPenActiveChanged(active: Boolean) { penActive += active }
    }

    // --- Engine wiring ------------------------------------------------------------------------

    @Test
    fun `a canvas with no engines registered runs the generic engine`() {
        // The software engine is the last resort, not the stub: a host with no vendor adapter still
        // gets a canvas that captures and draws.
        assertEquals(EngineIds.GENERIC, canvas().engineInfo.id)
    }

    @Test
    fun `the canvas reports the running engine's capabilities`() {
        EngineRegistry.register(RecordingFactory("generic", priority = 0))
        val view = canvas()
        assertEquals("generic", view.capabilities.engineId)
        assertTrue(view.capabilities.reports(InkChannel.PRESSURE))
    }

    @Test
    fun `the listener is told which engine was selected`() {
        val recorder = Recorder()
        val view = SproutCanvasView(activity)
        view.listener = recorder
        root.addView(view, FrameLayout.LayoutParams(CANVAS_WIDTH, CANVAS_HEIGHT))
        idle()
        assertEquals(listOf(EngineIds.GENERIC), recorder.engines.map { it.id })
    }

    @Test
    fun `an engine preference switches the engine and keeps the content`() {
        EngineRegistry.register(RecordingFactory("onyx", priority = 100))
        EngineRegistry.register(RecordingFactory("generic", priority = 0))
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b")))
        assertEquals("onyx", view.engineInfo.id)

        view.enginePreference = "generic"

        assertEquals("generic", view.engineInfo.id)
        assertEquals(listOf("a", "b"), view.getStrokes().map { it.id })
    }

    @Test
    fun `an unsupported engine preference is ignored rather than fatal`() {
        EngineRegistry.register(RecordingFactory("generic", priority = 0))
        val view = canvas()
        view.enginePreference = "onyx"
        assertEquals("generic", view.engineInfo.id)
        assertEquals("onyx", view.enginePreference)
    }

    // --- Content ------------------------------------------------------------------------------

    @Test
    fun `setStrokes then getStrokes returns the same content in order`() {
        val view = canvas()
        val strokes = listOf(stroke("a"), stroke("b"), stroke("c"))
        view.setStrokes(strokes)
        assertEquals(strokes, view.getStrokes())
        assertEquals(3, view.strokeCount)
    }

    @Test
    fun `getStrokes hands back a snapshot, not a live view`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a")))
        val snapshot = view.getStrokes()
        view.addStroke(stroke("b"))
        assertEquals(1, snapshot.size)
        assertEquals(2, view.strokeCount)
    }

    @Test
    fun `setStrokes replaces rather than appends`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a")))
        view.setStrokes(listOf(stroke("b")))
        assertEquals(listOf("b"), view.getStrokes().map { it.id })
    }

    @Test
    fun `addStroke with an existing id replaces it`() {
        val view = canvas()
        view.addStroke(stroke("a", x = 0f))
        view.addStroke(stroke("a", x = 100f))
        assertEquals(1, view.strokeCount)
        assertEquals(100f, view.getStrokes().single().bounds.left, 0f)
    }

    @Test
    fun `removeStrokes returns what it removed and ignores unknown ids`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b"), stroke("c")))
        val removed = view.removeStrokes(listOf("b", "nonexistent"))
        assertEquals(listOf("b"), removed.map { it.id })
        assertEquals(listOf("a", "c"), view.getStrokes().map { it.id })
    }

    @Test
    fun `clear empties the canvas and returns what it held`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b")))
        assertEquals(listOf("a", "b"), view.clear().map { it.id })
        assertEquals(0, view.strokeCount)
    }

    // --- Listener contract --------------------------------------------------------------------

    @Test
    fun `installing content fires nothing, which is how listener loops are avoided`() {
        val recorder = Recorder()
        val view = canvas()
        view.listener = recorder
        view.setStrokes(listOf(stroke("a")))
        view.addStroke(stroke("b"))
        assertTrue(recorder.completed.isEmpty())
        assertTrue(recorder.removed.isEmpty())
        assertTrue(recorder.cleared.isEmpty())
    }

    @Test
    fun `removal hands back the strokes themselves, so a host can undo without a shadow copy`() {
        val recorder = Recorder()
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b")))
        view.listener = recorder

        view.removeStrokes(listOf("a"))
        assertEquals(listOf("a"), recorder.removed.single().map { it.id })

        view.clear()
        assertEquals(listOf("b"), recorder.cleared.single().map { it.id })
    }

    @Test
    fun `a no-op removal fires nothing`() {
        val recorder = Recorder()
        val view = canvas()
        view.listener = recorder
        view.removeStrokes(listOf("nonexistent"))
        view.clear()
        assertTrue(recorder.removed.isEmpty())
        assertTrue(recorder.cleared.isEmpty())
    }

    // --- Tools --------------------------------------------------------------------------------

    @Test
    fun `the default tool is a medium black ballpoint and reaches the engine`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        assertEquals(ToolSpec.DEFAULT, view.tool)

        view.tool = ToolSpec(SproutPen.FOUNTAIN, 3f, android.graphics.Color.RED)
        assertEquals(SproutPen.FOUNTAIN, factory.engine!!.tools.last().pen)
    }

    @Test
    fun `assigning the same tool does not re-arm the engine`() {
        // Re-arming is not free on a hardware engine, and the SDK is not always careful about it.
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        val before = factory.engine!!.tools.size
        view.tool = view.tool
        assertEquals(before, factory.engine!!.tools.size)
    }

    @Test
    fun `arming an eraser mode the engine does not implement is rejected at the call site`() {
        // The stub engine implements no eraser at all, which makes it a convenient stand-in for the
        // AREA and PIXEL modes that are declared but unsupported everywhere in v1.
        EngineRegistry.register(NoOpInkEngineFactory)
        val view = canvas()
        view.enginePreference = EngineIds.NO_OP
        val failure = runCatching { view.eraser = EraserSpec(EraserMode.STROKE) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, view.eraser)
    }

    @Test
    fun `an eraser the engine supports is armed and can be disarmed`() {
        EngineRegistry.register(RecordingFactory("generic", priority = 0))
        val view = canvas()
        view.eraser = EraserSpec(EraserMode.STROKE)
        assertEquals(EraserMode.STROKE, view.eraser?.mode)
        view.eraser = null
        assertEquals(null, view.eraser)
    }

    @Test
    fun `switching to an engine that cannot honour the armed eraser disarms it`() {
        EngineRegistry.register(RecordingFactory("generic", priority = 0))
        EngineRegistry.register(NoOpInkEngineFactory)
        val view = canvas()
        view.eraser = EraserSpec(EraserMode.STROKE)

        view.enginePreference = EngineIds.NO_OP

        assertEquals(null, view.eraser)
    }

    // --- Geometry -----------------------------------------------------------------------------

    @Test
    fun `the engine is armed with the canvas bounds on layout`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        assertEquals(Rect(0, 0, view.width, view.height), factory.engine!!.bounds.last())
    }

    @Test
    fun `capture is clipped to the visible part of an oversized canvas`() {
        // A canvas larger than the window captures only what is on screen. Arming the engine with
        // the full rect would let a hardware pipeline paint ink into a region the user cannot see.
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas(width = 5000, height = 5000)
        val armed = factory.engine!!.bounds.last()
        val visible = Rect().also { view.getLocalVisibleRect(it) }
        assertEquals(visible, armed)
        assertTrue(armed.width() < 5000)
    }

    @Test
    fun `a manual exclusion zone reaches the engine`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        view.addExclusionZone(TOOLBAR_ZONE, id = "toolbar")
        idle()
        assertEquals(listOf(TOOLBAR_ZONE), factory.engine!!.zones.last())
        assertEquals(listOf(TOOLBAR_ZONE), view.activeExclusionZones())
    }

    @Test
    fun `hiding a tracked view stops it excluding anything`() {
        // Found on a Wacom Movink Pad: the Lab reported two armed zones with one overlay on screen.
        // A view set to GONE is never laid out, so its own layout listener never fires and its zone
        // was left armed — a dead region on the canvas exactly where a dismissed popup used to be,
        // which nothing on screen explains and no amount of tapping fixes.
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        val toolbar = View(activity)
        root.addView(toolbar, FrameLayout.LayoutParams(60, 20))
        view.addExclusionZone(toolbar, id = "toolbar")
        idle()
        assertEquals(1, view.activeExclusionZones().size)

        toolbar.visibility = View.GONE
        idle()

        assertTrue("the zone survived its view being hidden", view.activeExclusionZones().isEmpty())

        toolbar.visibility = View.VISIBLE
        idle()
        assertEquals(1, view.activeExclusionZones().size)
    }

    @Test
    fun `a zone outside the canvas excludes nothing`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        view.addExclusionZone(Rect(2000, 2000, 2100, 2100), id = "elsewhere")
        idle()
        assertTrue(view.activeExclusionZones().isEmpty())
    }

    @Test
    fun `a zone overlapping the canvas edge is clipped to it`() {
        val view = canvas()
        view.addExclusionZone(Rect(CANVAS_WIDTH - 20, -50, CANVAS_WIDTH + 500, 40), id = "spill")
        idle()
        assertEquals(
            listOf(Rect(CANVAS_WIDTH - 20, 0, CANVAS_WIDTH, 40)),
            view.activeExclusionZones(),
        )
    }

    @Test
    fun `a new engine is armed with the existing exclusion zones`() {
        // A fresh engine has nothing armed. Skipping the push because the zones had not *changed*
        // would leave the canvas writable under the host's toolbars for the rest of the session —
        // and it would look like the exclusion API had simply stopped working.
        EngineRegistry.register(RecordingFactory("onyx", priority = 100))
        val generic = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(generic)

        val view = canvas()
        view.addExclusionZone(TOOLBAR_ZONE, id = "toolbar")
        idle()

        view.enginePreference = "generic"
        idle()

        assertEquals(listOf(TOOLBAR_ZONE), generic.engine!!.zones.last())
    }

    @Test
    fun `removing a zone re-arms the engine without it`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        view.addExclusionZone(TOOLBAR_ZONE, id = "toolbar")
        idle()
        assertTrue(view.removeExclusionZone("toolbar"))
        idle()
        assertTrue(view.activeExclusionZones().isEmpty())
        assertTrue(factory.engine!!.zones.last().isEmpty())
    }

    @Test
    fun `a tracked view is counted, and a hidden one excludes nothing`() {
        val view = canvas()
        val toolbar = View(activity).apply { visibility = View.GONE }
        root.addView(toolbar, FrameLayout.LayoutParams(120, 40))
        idle()

        view.addExclusionZone(toolbar, id = "toolbar")
        idle()

        // Registered, but contributing no zone: chrome that is hidden is not covering anything,
        // and excluding the area under a dismissed popup would leave a dead region behind.
        assertEquals(1, view.exclusionZoneCount)
        assertTrue(view.activeExclusionZones().isEmpty())
    }

    @Test
    fun `a tracked view contributes its own bounds, mapped into canvas coordinates`() {
        val view = canvas()
        val toolbar = View(activity)
        root.addView(toolbar, FrameLayout.LayoutParams(120, 40))
        idle()

        view.addExclusionZone(toolbar, id = "toolbar")
        idle()

        // Both sit at the root's origin, so the toolbar's canvas-space zone is its own size. The
        // general offset arithmetic is covered exhaustively in CanvasGeometryTest.
        assertEquals(listOf(Rect(0, 0, 120, 40)), view.activeExclusionZones())
    }

    @Test
    fun `clearExclusionZones removes every registration`() {
        val view = canvas()
        view.addExclusionZone(Rect(0, 0, 10, 10), id = "a")
        view.addExclusionZone(Rect(20, 20, 30, 30), id = "b")
        idle()
        assertEquals(2, view.exclusionZoneCount)
        view.clearExclusionZones()
        idle()
        assertEquals(0, view.exclusionZoneCount)
        assertTrue(view.activeExclusionZones().isEmpty())
    }

    @Test
    fun `re-registering an id replaces the earlier zone`() {
        val view = canvas()
        view.addExclusionZone(Rect(0, 0, 10, 10), id = "toolbar")
        view.addExclusionZone(Rect(100, 100, 200, 200), id = "toolbar")
        idle()
        assertEquals(1, view.exclusionZoneCount)
        assertEquals(listOf(Rect(100, 100, 200, 200)), view.activeExclusionZones())
    }

    @Test
    fun `activeExclusionZones hands out copies`() {
        val view = canvas()
        view.addExclusionZone(Rect(0, 0, 10, 10), id = "a")
        idle()
        view.activeExclusionZones().single().set(0, 0, 0, 0)
        assertEquals(Rect(0, 0, 10, 10), view.activeExclusionZones().single())
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    @Test
    fun `attaching and detaching drives the engine's lifecycle`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        val engine = factory.engine!!
        assertTrue(engine.attached)
        assertTrue(engine.resumed)

        root.removeView(view)
        assertFalse(engine.attached)
        assertFalse(engine.resumed)
    }

    @Test
    fun `resizing tells the engine its committed content moved`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        resize(view, width = 160, height = 120)
        assertTrue(RepaintReason.BOUNDS_CHANGED in factory.engine!!.repaints)
        assertEquals(Rect(0, 0, 160, 120), factory.engine!!.bounds.last())
    }

    @Test
    fun `content survives a resize`() {
        val view = canvas()
        view.setStrokes(listOf(stroke("a"), stroke("b")))
        resize(view, width = 160, height = 120)
        assertEquals(listOf("a", "b"), view.getStrokes().map { it.id })
    }

    // --- Capture plumbing ---------------------------------------------------------------------

    @Test
    fun `an engine's stroke callbacks assemble a stroke and reach the listener`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val recorder = Recorder()
        val view = canvas()
        view.listener = recorder

        factory.engine!!.emitStroke(
            id = "captured",
            batches = listOf(
                StrokeSamples(2, floatArrayOf(1f, 2f), floatArrayOf(1f, 2f), pressure = floatArrayOf(0.4f, 0.5f)),
                StrokeSamples(1, floatArrayOf(3f), floatArrayOf(3f), pressure = floatArrayOf(0.6f)),
            ),
        )

        val captured = recorder.completed.single()
        // Batched delivery is the normal case, not an edge case: one pen-down to pen-up is not
        // guaranteed to produce one callback.
        assertEquals(3, captured.sampleCount)
        assertEquals(InkChannel.PRESSURE, captured.samples.channels)
        assertEquals(0.6f, captured.samples.pressure!![2], 0f)
        assertEquals(listOf("captured"), view.getStrokes().map { it.id })
        assertEquals(ToolSpec.DEFAULT, captured.tool)
        assertEquals("generic", captured.capture.engineId)
    }

    @Test
    fun `a stroke that produced no samples is not committed`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val recorder = Recorder()
        val view = canvas()
        view.listener = recorder

        factory.engine!!.emitStroke(id = "tap", batches = emptyList())

        assertTrue(recorder.completed.isEmpty())
        assertEquals(0, view.strokeCount)
    }

    @Test
    fun `the pen-activity gate is forwarded to the host app`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val recorder = Recorder()
        val view = canvas()
        view.listener = recorder

        factory.engine!!.setPenActive(true)
        factory.engine!!.setPenActive(false)

        assertEquals(listOf(true, false), recorder.penActive)
    }

    @Test
    fun `re-arming is deferred while the stylus is down and flushed when it lifts`() {
        // Changing the capture region mid-contact drops the stroke being written, and a re-layout
        // during a stroke is exactly when that would happen.
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        val engine = factory.engine!!

        engine.setPenActive(true)
        val boundsBefore = engine.bounds.size
        resize(view, width = 160, height = 120)
        assertEquals(boundsBefore, engine.bounds.size)

        engine.setPenActive(false)
        assertEquals(Rect(0, 0, 160, 120), engine.bounds.last())
    }

    @Test
    fun `the canvas exposes the engine's pen-activity state`() {
        val factory = RecordingFactory("generic", priority = 0)
        EngineRegistry.register(factory)
        val view = canvas()
        assertFalse(view.isPenActive)
        factory.engine!!.setPenActive(true)
        assertTrue(view.isPenActive)
    }

    // --- Uninitialized library ------------------------------------------------------------------

    @Test
    fun `a canvas built without SproutCanvas initialize still works`() {
        // D11: never a crash, never a silent loss of the hardware path — just a logged error and
        // the software engine.
        assertFalse(SproutCanvas.isInitialized)
        val view = canvas()
        view.setStrokes(listOf(stroke("a")))
        assertEquals(1, view.strokeCount)
        assertNotEquals("", view.engineInfo.id)
    }

    // --- Recording fakes ------------------------------------------------------------------------

    private class RecordingFactory(id: String, priority: Int) : InkEngineFactory {
        override val info = EngineInfo(id, id, priority)
        var engine: RecordingEngine? = null
        override fun isSupported(context: Context): Boolean = true
        override fun create(host: InkEngineHost): InkEngine =
            RecordingEngine(info, host).also { engine = it }
    }

    private class RecordingEngine(
        override val info: EngineInfo,
        private val host: InkEngineHost,
    ) : InkEngine {

        override val capabilities = CanvasCapabilities(
            engineId = info.id,
            channels = InkChannel.PRESSURE or InkChannel.TILT,
            supportsAlpha = true,
            supportedEraserModes = setOf(EraserMode.STROKE),
            penFidelities = CanvasCapabilities.uniformFidelity(PenFidelity.NATIVE),
        )

        val tools = mutableListOf<ToolSpec>()
        val bounds = mutableListOf<Rect>()
        val zones = mutableListOf<List<Rect>>()
        val repaints = mutableListOf<RepaintReason>()
        var attached = false
        var resumed = false

        private var penActive = false
        override val isPenActive: Boolean get() = penActive

        fun setPenActive(active: Boolean) {
            penActive = active
            host.onPenActiveChanged(active)
        }

        fun emitStroke(id: String, batches: List<StrokeSamples>) {
            val channels = batches.firstOrNull()?.channels ?: InkChannel.NONE
            host.onStrokeBegan(
                com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed(
                    id = id,
                    tool = tools.lastOrNull() ?: ToolSpec.DEFAULT,
                    calibration = DeviceCalibration.UNKNOWN,
                    engineId = info.id,
                    channels = channels,
                    startedAtMs = 0L,
                ),
            )
            batches.forEach { host.onStrokeSamples(id, it) }
            host.onStrokeEnded(id)
        }

        override fun attach(view: View) { attached = true }
        override fun detach() { attached = false }
        override fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point) { bounds += Rect(canvasBounds) }
        override fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>) { zones += zonesInCanvasCoords.map { Rect(it) } }
        override fun setTool(tool: ToolSpec) { tools += tool }
        override fun setEraser(eraser: EraserSpec?) {}
        override fun resume() { resumed = true }
        override fun pause() { resumed = false }
        override fun releaseForHandoff() {}
        override fun releaseLiveInk() {}
        override fun onCommittedContentChanged(reason: RepaintReason) { repaints += reason }
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun drawLiveInk(canvas: Canvas) {}
    }

    private companion object {
        /** Comfortably inside Robolectric's default screen, so the canvas is fully visible. */
        const val CANVAS_WIDTH = 200
        const val CANVAS_HEIGHT = 300

        val TOOLBAR_ZONE: Rect = Rect(120, 0, 200, 40)
    }
}
