package com.symmetricalpalmtree.sprout.canvas.engine.generic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.sprout.canvas.SproutLog
import com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngine
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineFactory
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost
import com.symmetricalpalmtree.sprout.canvas.engine.PenActivityGate
import com.symmetricalpalmtree.sprout.canvas.engine.RepaintReason
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.EraserMode
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRendererRegistry
import com.symmetricalpalmtree.sprout.canvas.tools.GenericPenTable
import java.util.UUID

/**
 * The software engine: ordinary Android stylus input, ordinary Android rendering.
 *
 * ### Why this one comes first
 *
 * Every device falls back to this engine, including the vendor devices when their SDK is absent, an
 * adapter is missing, or a host forgot [com.symmetricalpalmtree.sprout.canvas.SproutCanvas.initialize].
 * It is also the reference the hardware paths are tuned to match, so it has to be complete and
 * correct before any adapter exists to disagree with it.
 *
 * ### What it captures
 *
 * Stylus and eraser tool types only. Finger and mouse input is left alone entirely — a drawing
 * canvas that inked on finger contact would make a palm resting on the glass into a scribble, and
 * hosts routinely put scrolling containers around one of these.
 *
 * Every axis the digitizer reports is captured, including the **historical** samples Android batches
 * into a single move event. Those are not optional: at a fast writing speed most of the stroke lives
 * in the history buffer, and an engine that read only `getX()`/`getY()` would throw away the
 * majority of every stroke it captured and produce visibly polygonal ink.
 */
public class GenericInkEngine internal constructor(
    private val host: InkEngineHost,
) : InkEngine {

    override val info: EngineInfo get() = INFO

    private var capabilitiesField: CanvasCapabilities = buildCapabilities(
        StylusCapture(InkChannel.TIMESTAMP, DeviceCalibration.UNKNOWN, null),
    )

    override val capabilities: CanvasCapabilities get() = capabilitiesField

    private val gate = PenActivityGate { active -> host.onPenActiveChanged(active) }

    override val isPenActive: Boolean get() = gate.isActive

    /**
     * This engine's own renderers, separate from the view's.
     *
     * The live stroke and the committed stroke must be pixel-identical — the moment the pen lifts is
     * the moment any disagreement between them becomes visible. Identical *implementations* give
     * that, because a renderer is a pure function of its inputs; what they must not share is their
     * scratch buffers, since the view can be recording committed content while this engine is
     * drawing the stroke still in progress.
     */
    private val renderers = StrokeRendererRegistry()

    private var renderContext = RenderContext(density = 1f)
    private var densityDpi = 0

    private var limitRect = Rect()
    private var zones: List<Rect> = emptyList()

    private var tool: ToolSpec = ToolSpec.DEFAULT
    private var eraser: EraserSpec? = null
    private var resumed = false

    // --- The stroke in progress -------------------------------------------------------------

    private var activeId: String? = null
    private var activeTool: ToolSpec = ToolSpec.DEFAULT
    private var activeSeed: Int = 0
    private var activeCalibration: DeviceCalibration = DeviceCalibration.UNKNOWN
    private var liveSamples: StrokeSamples.Builder? = null
    private var batch: StrokeSamples.Builder? = null

    /** The built form of [liveSamples], rebuilt only when samples were added since the last draw. */
    private var liveSnapshot: StrokeSamples? = null
    private var liveSnapshotStale = true

    private var erasing = false

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun attach(view: View) {
        // The view itself is never held: everything this engine needs from it — invalidation, the
        // capture region, the exclusion zones — arrives through InkEngineHost and the SPI's own
        // callbacks. Keeping a reference would only be a way to leak one.
        val metrics = host.context.resources.displayMetrics
        renderContext = RenderContext(density = metrics.density)
        densityDpi = metrics.densityDpi
        capabilitiesField = buildCapabilities(StylusProbe.probeConnectedStylus(densityDpi))
        SproutLog.d {
            "generic engine attached; channels ${InkChannel.describe(capabilitiesField.channels)}"
        }
    }

    override fun detach() {
        // A stroke in progress when the view leaves its window is still ink the user drew. Ending
        // it commits what was captured; dropping it would lose a word mid-sentence on any host that
        // navigates away while the pen is down.
        endActiveStroke()
        gate.reset()
    }

    override fun resume() {
        resumed = true
    }

    override fun pause() {
        resumed = false
        endActiveStroke()
    }

    override fun releaseForHandoff() {
        releaseLiveInk()
    }

    override fun releaseLiveInk() {
        endActiveStroke()
    }

    override fun onCommittedContentChanged(reason: RepaintReason) {
        // Nothing to do: on this engine committed content is drawn by the view through the ordinary
        // view system, and it has already invalidated itself. The reason matters only where a panel
        // has to be told what kind of repaint it is about to get.
    }

    // ------------------------------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------------------------------

    override fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point) {
        limitRect = Rect(canvasBounds)
    }

    override fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>) {
        zones = zonesInCanvasCoords.map { Rect(it) }
    }

    override fun setTool(tool: ToolSpec) {
        this.tool = tool
    }

    override fun setEraser(eraser: EraserSpec?) {
        this.eraser = eraser
    }

    // ------------------------------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------------------------------

    /**
     * @return true for **every** stylus event, whether or not anything was captured.
     *
     * ### Why it consumes even what it refuses
     *
     * Android stops delivering the rest of a gesture to a view that returned false from its
     * `ACTION_DOWN`. So a stylus-down this engine declined — outside the capture region, under a
     * registered toolbar, on a paused canvas — would never be followed by the `ACTION_UP` that
     * closes the pen-activity gate. The gate would latch open and quietly suppress the host's own
     * chrome for the rest of the session, with nothing on screen to explain it.
     *
     * Refusing to *capture* and refusing to *receive* are different decisions, and only the first
     * one belongs to the exclusion rules.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointer = stylusPointerIndex(event) ?: return false

        // The gate is fed before anything else, and regardless of whether this engine goes on to
        // capture the event. Its whole job is to tell the *host* that a pen is on the glass, and a
        // paused canvas or a stroke starting outside the bounds does not make that less true.
        when (phaseOf(event, pointer)) {
            Phase.DOWN -> gate.onPenDown()
            Phase.UP, Phase.CANCEL -> gate.onPenUp()
            Phase.MOVE -> Unit
        }

        if (!resumed) return true

        // The barrel button erases whatever tool is armed. The hardware reports it as an eraser
        // whether the library asks or not, and an app that shipped without erase-on-button would
        // feel broken (PLAN.md §5.4). Both spellings are checked because platforms disagree about
        // which one a given stylus produces.
        val erasingNow = event.getToolType(pointer) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
            eraser != null

        if (erasingNow) handleErase(event, pointer) else handleDraw(event, pointer)
        return true
    }

    private fun handleDraw(event: MotionEvent, pointer: Int) {
        when (phaseOf(event, pointer)) {
            Phase.DOWN -> {
                // An erase gesture that turns into a draw (the button released mid-air) has to
                // close its own gesture first, or the strokes it removed are never reported.
                finishErase()
                if (!canCapture(event.getX(pointer), event.getY(pointer))) return
                beginStroke(event, pointer)
                appendCurrent(event, pointer)
                flushBatch()
                host.requestInvalidate()
            }

            Phase.MOVE -> {
                if (activeId == null) return
                appendHistorical(event, pointer)
                appendCurrent(event, pointer)
                flushBatch()
                host.requestInvalidate()
            }

            Phase.UP, Phase.CANCEL -> {
                if (activeId == null) return
                appendHistorical(event, pointer)
                appendCurrent(event, pointer)
                flushBatch()
                endActiveStroke()
            }
        }
    }

    private fun handleErase(event: MotionEvent, pointer: Int) {
        // A barrel press mid-stroke ends the stroke rather than annotating it: the ink drawn so far
        // is real and belongs on the canvas, and what happens next is an erase, not more of it.
        endActiveStroke()

        val path = ArrayList<PointF>(event.historySize + 1)
        for (h in 0 until event.historySize) {
            val x = event.getHistoricalX(pointer, h)
            val y = event.getHistoricalY(pointer, h)
            if (canCapture(x, y)) path += PointF(x, y)
        }
        val x = event.getX(pointer)
        val y = event.getY(pointer)
        if (canCapture(x, y)) path += PointF(x, y)

        if (path.isNotEmpty()) {
            erasing = true
            host.onEraseAt(path, eraseRadiusPx())
        }

        when (phaseOf(event, pointer)) {
            Phase.UP, Phase.CANCEL -> finishErase()
            else -> Unit
        }
    }

    private fun finishErase() {
        if (!erasing) return
        erasing = false
        host.onEraseEnded()
    }

    private fun beginStroke(event: MotionEvent, pointer: Int) {
        val probe = StylusProbe.probe(event, densityDpi)
        refineCapabilities(probe)

        val id = UUID.randomUUID().toString()
        activeId = id
        activeTool = tool
        activeSeed = id.hashCode()
        activeCalibration = probe.calibration

        // The builders are rebuilt only when the channel set changes, so a writing session reuses
        // the same two buffers stroke after stroke.
        if (liveSamples?.channels != probe.channels) {
            liveSamples = StrokeSamples.Builder(probe.channels)
            batch = StrokeSamples.Builder(probe.channels, initialCapacity = BATCH_CAPACITY)
        }
        liveSamples?.reset()
        batch?.reset()
        liveSnapshotStale = true

        host.onStrokeBegan(
            StrokeSeed(
                id = id,
                tool = activeTool,
                calibration = probe.calibration,
                engineId = EngineIds.GENERIC,
                channels = probe.channels,
                startedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Harvests the samples Android batched into this move event.
     *
     * The history buffer holds every sample the digitizer produced since the last delivered event.
     * At writing speed on a 240 Hz panel that is most of the stroke.
     */
    private fun appendHistorical(event: MotionEvent, pointer: Int) {
        for (h in 0 until event.historySize) {
            val consumed = appendSample(
                x = event.getHistoricalX(pointer, h),
                y = event.getHistoricalY(pointer, h),
                pressure = event.getHistoricalPressure(pointer, h),
                orientation = event.getHistoricalOrientation(pointer, h),
                altitude = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, h),
                size = event.getHistoricalSize(pointer, h),
                timestampMs = event.getHistoricalEventTime(h),
            )
            if (!consumed) return
        }
    }

    private fun appendCurrent(event: MotionEvent, pointer: Int) {
        appendSample(
            x = event.getX(pointer),
            y = event.getY(pointer),
            pressure = event.getPressure(pointer),
            orientation = event.getOrientation(pointer),
            altitude = event.getAxisValue(MotionEvent.AXIS_TILT, pointer),
            size = event.getSize(pointer),
            timestampMs = event.eventTime,
        )
    }

    /**
     * Adds one sample, unless it falls somewhere capture is not allowed.
     *
     * @return false when the sample was rejected — the stroke has been ended and the rest of the
     *   batch must be discarded with it.
     *
     * A stroke that wanders out of the canvas or under a registered toolbar **stops there**. That is
     * what the hardware limit rect does on a BOOX, so the software engines are written to match the
     * hardware rather than the other way round (PLAN.md §3.7).
     */
    private fun appendSample(
        x: Float,
        y: Float,
        pressure: Float,
        orientation: Float,
        altitude: Float,
        size: Float,
        timestampMs: Long,
    ): Boolean {
        if (!canCapture(x, y)) {
            flushBatch()
            endActiveStroke()
            return false
        }
        batch?.add(
            x = x,
            y = y,
            pressure = activeCalibration.normalizePressure(pressure),
            orientation = orientation,
            altitude = altitude,
            size = size,
            timestampMs = timestampMs,
        )
        return true
    }

    /**
     * Hands the samples gathered from one event to the host, and keeps a copy for live drawing.
     *
     * Batched rather than reported per sample because a single move event can carry dozens, and the
     * host's accumulator would otherwise be entered once per point.
     */
    private fun flushBatch() {
        val id = activeId ?: return
        val pending = batch ?: return
        if (pending.count == 0) return
        val samples = pending.build()
        pending.reset()
        liveSamples?.addAll(samples)
        liveSnapshotStale = true
        host.onStrokeSamples(id, samples)
    }

    private fun endActiveStroke() {
        val id = activeId ?: return
        activeId = null
        liveSnapshot = null
        liveSnapshotStale = true
        host.onStrokeEnded(id)
        host.requestInvalidate()
    }

    // ------------------------------------------------------------------------------------------
    // Live ink
    // ------------------------------------------------------------------------------------------

    override fun drawLiveInk(canvas: Canvas) {
        if (activeId == null) return
        val builder = liveSamples ?: return
        if (builder.count == 0) return

        if (liveSnapshotStale || liveSnapshot == null) {
            liveSnapshot = builder.build()
            liveSnapshotStale = false
        }
        val samples = liveSnapshot ?: return
        renderers.rendererFor(activeTool.pen)
            .draw(canvas, samples, activeTool, activeSeed, renderContext)
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /** Where in a gesture the stylus pointer is, independent of how many fingers are also down. */
    private enum class Phase { DOWN, MOVE, UP, CANCEL }

    private fun phaseOf(event: MotionEvent, pointer: Int): Phase = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        -> if (event.actionIndex == pointer) Phase.DOWN else Phase.MOVE

        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_POINTER_UP,
        -> if (event.actionIndex == pointer) Phase.UP else Phase.MOVE

        MotionEvent.ACTION_CANCEL -> Phase.CANCEL

        else -> Phase.MOVE
    }

    /**
     * The index of the stylus pointer in [event], or null if there is not one.
     *
     * Searched rather than assumed to be zero: a palm on the glass is a pointer too, and if it
     * landed first it owns index 0. Reading the tool type of pointer 0 alone would then decide the
     * stylus was a finger and drop the stroke.
     */
    private fun stylusPointerIndex(event: MotionEvent): Int? {
        for (i in 0 until event.pointerCount) {
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                return i
            }
        }
        return null
    }

    /** True when `(x, y)` is inside the armed capture region and outside every exclusion zone. */
    private fun canCapture(x: Float, y: Float): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        if (!limitRect.contains(px, py)) return false
        return zones.none { it.contains(px, py) }
    }

    private fun eraseRadiusPx(): Float {
        val widthDp = eraser?.widthDp ?: EraserSpec.DEFAULT_WIDTH_DP
        return renderContext.toPx(widthDp) * 0.5f
    }

    /**
     * Adds anything a real stroke revealed that the startup scan missed — and never takes anything
     * away.
     *
     * ### Why capabilities only ever grow
     *
     * The scan at attach time can genuinely miss a pen: some styluses only enumerate as an input
     * device while they are in range of the digitizer, so the first stroke is sometimes the first
     * chance to learn what the hardware can do. That is worth folding in.
     *
     * Retracting a channel on the strength of one stroke is a different matter. [CanvasCapabilities]
     * is a statement about *the device* — an app builds its tool picker from it — and plenty of
     * things that are not the user's pen arrive as `TOOL_TYPE_STYLUS`: a knuckle on the glass, a
     * capacitive stylus on a tablet that also has an EMR pen, and synthesized input, which reports
     * from a virtual device that declares no axes at all. Any of those would otherwise grey out the
     * pressure-sensitive pens on hardware that has pressure, and nothing would ever turn them back
     * on.
     *
     * No accuracy is lost by refusing to downgrade: every [com.symmetricalpalmtree.sprout.canvas.model.InkStroke]
     * carries the channels and the calibration of the digitizer that actually produced *it*.
     * Capabilities describe what the canvas can do; a stroke describes what happened.
     */
    private fun refineCapabilities(probe: StylusCapture) {
        val merged = capabilitiesField.channels or probe.channels
        if (merged == capabilitiesField.channels) return
        capabilitiesField = buildCapabilities(
            StylusCapture(merged, probe.calibration, probe.deviceName),
        )
        SproutLog.d {
            "stylus '${probe.deviceName}' added ${InkChannel.describe(probe.channels)}; " +
                "engine now reports ${InkChannel.describe(merged)}"
        }
    }

    private fun buildCapabilities(capture: StylusCapture): CanvasCapabilities = CanvasCapabilities(
        engineId = EngineIds.GENERIC,
        channels = capture.channels,
        supportsAlpha = true,
        supportedEraserModes = setOf(EraserMode.STROKE),
        penFidelities = GenericPenTable.fidelities(),
        liveInkIsHardware = false,
        livePreviewColorFloor = 0,
        calibration = capture.calibration,
    )

    public companion object {

        /** Identity of the software engine. Lowest priority, and always supported. */
        public val INFO: EngineInfo = EngineInfo(
            id = EngineIds.GENERIC,
            displayName = "Generic (software)",
            priority = EngineInfo.PRIORITY_GENERIC,
        )

        /**
         * Room for one event's worth of samples.
         *
         * A single move event carries a handful at a walking pace and a few dozen from a fast
         * stroke on a high-rate digitizer; the buffer grows past this at most once and then stops,
         * because it survives the reset between events.
         */
        private const val BATCH_CAPACITY = 64
    }
}

/**
 * Factory for [GenericInkEngine] — the registry's last resort.
 *
 * Always supported, by definition: there is no device this library runs on where an Android
 * `MotionEvent` and an Android `Canvas` are unavailable. Every other engine is an optimisation over
 * this one.
 */
public object GenericInkEngineFactory : InkEngineFactory {

    override val info: EngineInfo get() = GenericInkEngine.INFO

    override fun isSupported(context: Context): Boolean = true

    override fun create(host: InkEngineHost): InkEngine = GenericInkEngine(host)
}
