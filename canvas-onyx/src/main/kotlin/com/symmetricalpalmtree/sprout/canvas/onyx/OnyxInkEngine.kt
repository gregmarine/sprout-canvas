package com.symmetricalpalmtree.sprout.canvas.onyx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
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
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.PenMetrics
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRenderer
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable
import java.util.UUID

/**
 * The BOOX engine: the panel's own firmware paints the ink, and this class stays out of its way.
 *
 * ### What "hardware ink" actually means here
 *
 * Nothing in this file draws a live stroke. The Onyx raw-drawing pipeline paints the stylus's path
 * straight onto the EPD panel at sub-frame latency, below the Android view system entirely, and
 * [drawLiveInk] is deliberately empty as a result. What this engine does is arm that pipeline, keep
 * it armed correctly through every lifecycle transition a host app can produce, and hand the points
 * it reports back up to the canvas so the committed layer underneath ends up matching.
 *
 * The consequence worth internalising: **a screenshot cannot capture live ink on this engine.** It
 * is not in the framebuffer. Every check of a stroke while the stylus is down has to be confirmed by
 * a human (PLAN.md §4.3).
 *
 * ### The rules this class exists to obey
 *
 * Almost every non-obvious line here is one of the hard-won lessons in PLAN.md §5.1–§5.7, and each
 * is commented where it lives rather than only in the plan:
 *
 *  - the app-scope fast-mode pin, without which the first stroke after opening lags 1–2 seconds;
 *  - `setRawDrawingRenderEnabled(false)` does **not** clear the panel, so every content change needs
 *    an explicit `handwritingRepaint` or the user is left looking at grey residue;
 *  - `handwritingRepaint` on a move event costs one full-panel flash per event, so it happens at
 *    gesture boundaries only;
 *  - the eraser must release the overlay *before* any erase logic runs, or the overlay hides the
 *    result and phantom strokes stay visible;
 *  - the exclusion list must never be empty, because the SDK reads an empty list as "no change";
 *  - the raw pipeline is process-global, so every close is a close-if-still-owner ([OnyxPenOwner]).
 *
 * @see OnyxPenOwner
 * @see CoordinateSpace
 */
internal class OnyxInkEngine(
    private val host: InkEngineHost,
) : InkEngine {

    override val info: EngineInfo get() = INFO

    private val main = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var touchHelper: TouchHelper? = null

    /** True while a raw-drawing session is open. Not the same as "capturing" — see [resumed]. */
    private var sessionOpen = false
    private var resumed = false

    private var calibration: DeviceCalibration = DeviceCalibration.UNKNOWN
    private var renderContext = RenderContext(density = 1f)

    private var capabilitiesField: CanvasCapabilities = buildCapabilities(DeviceCalibration.UNKNOWN)
    override val capabilities: CanvasCapabilities get() = capabilitiesField

    private val gate = PenActivityGate { active -> host.onPenActiveChanged(active) }
    override val isPenActive: Boolean get() = gate.isActive

    private val coordinateSpace = CoordinateSpace()
    private val timestampClock = TimestampClock()

    private var canvasBounds = Rect()
    private val screenOffset = Point()
    private var zones: List<Rect> = emptyList()

    /** Scratch for arming the SDK, so a layout pass allocates nothing. */
    private val armedLimit = Rect()

    private var tool: ToolSpec = ToolSpec.DEFAULT
    private var eraser: EraserSpec? = null

    /** Committed-layer renderers, if the host asked for the SDK's own. See [OnyxRenderMode]. */
    private var overrides: Map<SproutPen, StrokeRenderer> = emptyMap()
    override val rendererOverrides: Map<SproutPen, StrokeRenderer> get() = overrides

    // --- The stroke in progress ---------------------------------------------------------------

    private var activeId: String? = null
    private var activeTool: ToolSpec = ToolSpec.DEFAULT
    private var samples: StrokeSamples.Builder? = null

    /** True between the first erase hit of a gesture and its end, so the boundary fires once. */
    private var erasing = false

    /** Logged once, because the answer is a property of the SDK build and not of a stroke. */
    private var callbackThreadReported = false

    /**
     * How many raw callbacks the SDK has delivered, for the device report.
     *
     * Zero after a stroke is a complete diagnosis on its own: the pipeline is armed and the pen is
     * on the glass, and the SDK is telling this adapter nothing.
     */
    private var rawCallbackCount = 0

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    override fun attach(view: View) {
        this.view = view
        val metrics = host.context.resources.displayMetrics
        renderContext = RenderContext(density = metrics.density)
        calibration = OnyxSdk.readCalibration(metrics.densityDpi)
        capabilitiesField = buildCapabilities(calibration)
        overrides = OnyxRenderMode.renderersFor(OnyxRenderMode.current, calibration, renderContext)
        OnyxDiagnostics.publishEngineState(::describeState)
        SproutLog.d { "onyx engine attached — ${OnyxSdk.describeDevice()}" }
    }

    /**
     * Live SDK state for the Lab's device report.
     *
     * `isRawDrawingInputEnabled` is the one thing on this platform that answers back. Everything
     * else the adapter calls returns nothing and validates nothing, so when a canvas takes no ink
     * this line is what separates "the adapter never armed the pipeline" from "the pipeline is
     * armed and the digitizer is sending nothing" — two problems with no other way to tell apart.
     */
    private fun describeState(): String = buildString {
        val helper = touchHelper
        appendLine("session:           ${if (sessionOpen) "open" else "closed"}, ${if (resumed) "resumed" else "paused"}")
        appendLine("owns pipeline:     ${OnyxPenOwner.isOwner(this@OnyxInkEngine)}")
        appendLine(
            "sdk raw input:     " + when {
                helper == null -> "no TouchHelper"
                else -> runCatching { helper.isRawDrawingInputEnabled.toString() }
                    .getOrDefault("unreadable")
            },
        )
        appendLine("armed limit rect:  $armedLimit")
        appendLine("raw callbacks:     $rawCallbackCount")
        appendLine("armed tool:        ${tool.pen} ${tool.widthDp}dp → overlay style " +
            "${OnyxPenTable.overlayStyle(tool.pen)}, width ${"%.1f".format(overlayWidthPx(tool))}px")
    }

    override fun detach() {
        // A stroke in progress when the view leaves its window is still ink the user drew. Ending
        // it commits what was captured.
        endActiveStroke()
        finishErase()
        gate.reset()
        closeSession("detach")
        OnyxDiagnostics.publishEngineState(null)
        touchHelper = null
        view = null
    }

    override fun resume() {
        resumed = true
        openSessionIfReady()
    }

    override fun pause() {
        resumed = false
        endActiveStroke()
        finishErase()
        // The session stays open and owned; only input is disabled. Reopening on every focus change
        // would cost a panel mode-switch each time, which is what the fast-mode pin exists to avoid.
        if (sessionOpen) {
            runQuietly("pause") { touchHelper?.setRawDrawingEnabled(false) }
        }
    }

    override fun releaseForHandoff() {
        endActiveStroke()
        finishErase()
        closeSession("handoff")
    }

    override fun releaseLiveInk() {
        if (!sessionOpen) return
        // Hands the panel back to the Android layer. The overlay re-enables itself on the next pen
        // stroke through onBeginRawDrawing, so this is cheap and needs no repaint of its own.
        runQuietly("releaseLiveInk") {
            touchHelper?.setRawDrawingRenderEnabled(false)
            view?.invalidate()
        }
    }

    /**
     * Opens the raw-drawing session, once the view is attached, resumed and actually laid out.
     *
     * The readiness check is not defensive padding. `SproutCanvasView` resumes its engine from
     * `onAttachedToWindow`, which runs *before* the first layout pass, so a session opened there
     * would arm a zero-sized capture region — a canvas that never takes ink, on a device where the
     * only symptom is that nothing happens. The bounds arrive on layout, and that is what actually
     * triggers the open.
     */
    private fun openSessionIfReady() {
        val view = this.view ?: return
        if (!resumed || canvasBounds.isEmpty || view.width <= 0 || view.height <= 0) return

        val helper = touchHelper ?: runCatching { TouchHelper.create(view, rawInput) }
            .onFailure { SproutLog.e("TouchHelper.create failed; this canvas has no hardware ink", it) }
            .getOrNull()
            ?.also { touchHelper = it }
            ?: return

        runQuietly("open") {
            if (!sessionOpen) {
                armLimitRect(helper)
                helper.setStrokeWidth(overlayWidthPx(tool))
                    .setStrokeColor(overlayColor(tool))
                    .setStrokeStyle(OnyxPenTable.overlayStyle(tool.pen))
                    .openRawDrawing()
                sessionOpen = true
            } else {
                armLimitRect(helper)
                helper.restartRawDrawing()
                // Re-asserted after a restart because some panels do not default to the ink that
                // was set before it — the NoteAir5C is the recorded example (PLAN.md §5.1).
                helper.setStrokeColor(overlayColor(tool))
                helper.setStrokeStyle(OnyxPenTable.overlayStyle(tool.pen))
            }

            // Claimed *after* a successful open, so a failed open never neutralises the live
            // canvas's close.
            OnyxPenOwner.claim(this)

            // The barrel button is handled by the SDK at the hardware level while the raw pipeline
            // is live, which is the only place it can be caught without also catching the pen tip.
            helper.enableSideBtnErase(true)
            helper.setRawDrawingEnabled(true)

            if (eraser != null) {
                // Pen render off in eraser mode, always. With it on, the overlay paints the
                // eraser's own path as ink — phantom strokes that look real and vanish at the next
                // panel refresh (PLAN.md §5.1).
                helper.setRawDrawingRenderEnabled(false)
            } else {
                pinFastMode()
            }

            // Suppresses the panel's own mid-session GC16 quality refresh, so the only full-panel
            // repaints are the ones this engine asks for deliberately.
            EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
        }
    }

    /**
     * Pins the app into the fast handwriting waveform.
     *
     * The first stroke after opening a surface used to lag 1–2 seconds, because the panel sits in a
     * quality waveform and the first stroke pays for the mode switch. A device sweep of every EPD
     * mode found this to be the **sole** fix — scribble mode, view mode and system-fast all still
     * lagged. Applied when the pipeline opens, cleared when it is given up, so menus and dialogs
     * elsewhere in the host app still render at full quality (PLAN.md §5.1).
     */
    private fun pinFastMode() {
        EpdController.applyAppScopeUpdate(
            HWR_APP_SCOPE,
            true,
            false,
            UpdateMode.HAND_WRITING_REPAINT_MODE,
            0,
        )
    }

    /**
     * Closes the raw-drawing session — but only if this canvas still owns the pipeline.
     *
     * See [OnyxPenOwner] for why the guard is the whole point.
     */
    private fun closeSession(caller: String) {
        if (!sessionOpen) return
        OnyxPenOwner.releaseIfOwner(this) {
            runQuietly("close/$caller") {
                touchHelper?.closeRawDrawing()
                // Dropped with ownership so the next screen renders at normal quality; the next
                // owner re-applies it when it opens.
                EpdController.clearAppScopeUpdate()
            }
        }
        sessionOpen = false
        SproutLog.d { "onyx session closed by $caller" }
    }

    // ------------------------------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------------------------------

    override fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point) {
        this.canvasBounds = Rect(canvasBounds)
        this.screenOffset.set(screenOffset.x, screenOffset.y)
        if (sessionOpen) touchHelper?.let { runQuietly("bounds") { armLimitRect(it) } }
        // The first layout is also what makes the session openable at all.
        openSessionIfReady()
    }

    override fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>) {
        zones = zonesInCanvasCoords.map { Rect(it) }
        if (sessionOpen) touchHelper?.let { runQuietly("zones") { armLimitRect(it) } }
    }

    /**
     * Arms the capture region and the zones the host's chrome sits over.
     *
     * Two things here are not obvious and both cost real debugging time:
     *
     *  - **Never call `restartRawDrawing()` to re-arm.** The SDK persists the exclusion zone and
     *    restores it on every open and restart, so a restart immediately undoes the zone just set.
     *    `setLimitRect` is honoured dynamically; that is the call to use.
     *  - **Never pass an empty exclusion list.** See [OnyxLimitRects.NOTHING_EXCLUDED].
     */
    private fun armLimitRect(helper: TouchHelper) {
        val limit = coordinateSpace.fromCanvasRect(canvasBounds, screenOffset, armedLimit)
        val exclude = OnyxLimitRects.excludeRects(
            zones.map { coordinateSpace.fromCanvasRect(it, screenOffset, Rect()) },
        )
        helper.setLimitRect(Rect(limit), exclude)
        SproutLog.d { "onyx limit rect $limit, ${zones.size} zone(s) excluded" }
    }

    override fun setTool(tool: ToolSpec) {
        this.tool = tool
        if (!sessionOpen) return
        // No session restart is needed for any of these: verified on five devices, all three take
        // effect on the very next stroke and survive the pinned fast-mode waveform (PLAN.md §5.1).
        runQuietly("setTool") {
            touchHelper
                ?.setStrokeStyle(OnyxPenTable.overlayStyle(tool.pen))
                ?.setStrokeWidth(overlayWidthPx(tool))
                ?.setStrokeColor(overlayColor(tool))
        }
    }

    override fun setEraser(eraser: EraserSpec?) {
        this.eraser = eraser
        val helper = touchHelper ?: return
        if (!sessionOpen) return
        runQuietly("setEraser") {
            if (eraser != null) {
                // Order matters: the overlay comes off first. Leaving it on means the panel keeps
                // showing the ink that the erase is in the middle of removing.
                helper.setRawDrawingRenderEnabled(false)
                view?.invalidate()
                helper.setEraserRawDrawingEnabled(true, eraserDiameterPx(eraser))
                EpdController.clearAppScopeUpdate()
            } else {
                helper.setEraserRawDrawingEnabled(false, 0)
                helper.setRawDrawingEnabled(true)
                pinFastMode()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Committed content
    // ------------------------------------------------------------------------------------------

    /**
     * Repaints the panel when, and only when, the change actually needs one.
     *
     * ### Why this is a `when` and not a repaint
     *
     * [RepaintReason.STROKE_COMMITTED] deliberately does nothing. The firmware has already painted
     * that stroke and is still showing it; our committed layer has merely caught up underneath. A
     * repaint there would cost a visible full-panel flash on every single pen lift — the most
     * common event there is — to redraw something the user is already looking at.
     *
     * Every other reason removes or moves pixels the panel is still displaying, and
     * `setRawDrawingRenderEnabled(false)` does **not** clear the hardware buffer: it is a
     * lightweight toggle. Without an explicit `handwritingRepaint` the user is left looking at grey
     * residue of ink that no longer exists, followed by a black flash the next time anything else
     * forces a refresh (PLAN.md §5.1).
     */
    override fun onCommittedContentChanged(reason: RepaintReason) {
        when (reason) {
            RepaintReason.STROKE_COMMITTED -> Unit

            RepaintReason.STROKES_REMOVED,
            RepaintReason.STROKES_REPLACED,
            RepaintReason.CLEARED,
            RepaintReason.BOUNDS_CHANGED,
            RepaintReason.HANDOFF,
            -> repaintPanel()
        }
    }

    /**
     * Hands the panel back, lets the view redraw, then captures it to the panel and re-arms.
     *
     * The `post` is load-bearing. `handwritingRepaint` captures the view **through a software
     * canvas** as it stands right now, and the view has only been invalidated — it has not drawn
     * yet. Repainting synchronously would capture the previous frame, so the change being repainted
     * for would be the one thing missing from the result.
     */
    private fun repaintPanel() {
        val view = this.view ?: return
        if (!sessionOpen) return
        runQuietly("repaint") { touchHelper?.setRawDrawingRenderEnabled(false) }
        view.invalidate()
        view.post {
            runQuietly("repaint/post") {
                EpdController.handwritingRepaint(view, Rect(0, 0, view.width, view.height))
                if (eraser == null) {
                    touchHelper?.setRawDrawingEnabled(true)
                } else {
                    touchHelper?.setRawDrawingRenderEnabled(false)
                }
            }
        }
    }

    /** Nothing — the firmware painted it. Drawing again produces a doubled, trailing stroke. */
    override fun drawLiveInk(canvas: Canvas) = Unit

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    /**
     * Watches Android events for the two things that still arrive that way.
     *
     * While the raw pipeline is live the stylus never reaches here at all — that is what makes the
     * ink fast. Two things do:
     *
     *  - **A palm on the glass**, which is the entire reason the pen-activity gate is public API.
     *    On this engine the palm is the *only* thing the host's gesture detectors can see mid-word.
     *  - **The stylus itself, when the session is not open** — before layout, while paused, or on a
     *    device where the SDK failed to initialize. The gate has to stay correct there too.
     *
     * Finger input is passed through untouched and reported as unconsumed, so a canvas inside a
     * scrolling parent still scrolls under a finger while the pen draws on it.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylus = stylusPointerIndex(event)

        if (stylus != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> gate.onPenDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                    gate.onPenUp()
            }
        }

        // The helper wants the stream regardless, for its own region bookkeeping. Its return value
        // is not the answer to "did the canvas consume this" — that is decided above, by tool type.
        if (sessionOpen) runQuietly("onTouchEvent") { touchHelper?.onTouchEvent(event) }

        return stylus != null
    }

    /**
     * The SDK's raw callbacks. Everything the panel knows about the stylus arrives here.
     *
     * Each one hops to the main thread if it is not already on it — see [onMain]. The SPI is
     * main-thread only, and a vendor SDK's threading is not something to take on trust.
     */
    private val rawInput = object : RawInputCallback() {

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) = onMain {
            reportCallbackThreadOnce()
            // Marked before anything else and regardless of mode: the pen is on the glass, and the
            // gate's job is to tell the host that, whatever this contact turns out to do.
            gate.onPenDown()
            if (eraser != null) return@onMain

            // Re-arm the overlay. This is the other half of [releaseLiveInk], and without it that
            // call is a one-way door: the host releases the panel so its own chrome can repaint, and
            // live ink never comes back. The canvas still captures perfectly and still commits on
            // pen-up, so what the user sees is a pen that has silently stopped writing in real time
            // while the strokes keep appearing a moment later — the exact symptom that makes an
            // e-ink canvas feel broken (PLAN.md §5.1).
            //
            // Here rather than anywhere else because pen-down is the one moment that means "the
            // overlay is wanted again", and it costs nothing when it is already on.
            if (sessionOpen) {
                runQuietly("beginDrawing") { touchHelper?.setRawDrawingRenderEnabled(true) }
            }

            beginStroke()
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) = onMain {
            gate.onPenUp()
            if (eraser != null) {
                finishErase()
                return@onMain
            }
            endActiveStroke()
        }

        /**
         * Ignored for drawing, on purpose.
         *
         * Every point delivered here is delivered again in
         * [onRawDrawingTouchPointListReceived], and accumulating both would double every sample in
         * every stroke. In eraser mode there is no list to wait for, so this is where the work
         * happens.
         */
        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) = onMain {
            if (eraser != null) eraseAt(listOf(point))
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) = onMain {
            // Eraser mode does not stop the SDK routing pen-tip contact through the *drawing*
            // callbacks; it only changes what the panel paints. So the eraser has to be handled
            // here as well as in the erasing callbacks, or an armed eraser draws.
            if (eraser != null) {
                eraseAt(list.points.orEmpty())
                return@onMain
            }
            // A pen-down that produced points before this engine saw its begin callback is still a
            // real stroke; starting one here rather than dropping the batch costs nothing.
            if (activeId == null) beginStroke()
            appendSamples(list.points.orEmpty())
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) = onMain {
            reportCallbackThreadOnce()
            gate.onPenDown()
            // Release the overlay *first*, before any erase logic. With the overlay still on, the
            // panel keeps showing the ink being erased and the user watches phantom strokes survive
            // an eraser passing straight through them (PLAN.md §5.1).
            runQuietly("beginErasing") {
                touchHelper?.setRawDrawingRenderEnabled(false)
                view?.invalidate()
            }
            // A barrel press mid-stroke ends the stroke rather than extending it: the ink drawn so
            // far is real and belongs on the canvas, and what happens next is an erase.
            endActiveStroke()
        }

        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint) = onMain {
            gate.onPenUp()
            // The host removes the strokes and calls back with STROKES_REMOVED, which is what
            // repaints the panel. One repaint per gesture, at its boundary — never per move event,
            // which would cost a full-panel flash for each of the dozens an erase produces.
            finishErase()
            if (eraser == null) {
                runQuietly("endErasing") {
                    touchHelper?.setRawDrawingEnabled(true)
                    pinFastMode()
                }
            }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) = onMain {
            eraseAt(listOf(point))
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) = onMain {
            eraseAt(list.points.orEmpty())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------------------------------

    private fun beginStroke() {
        val id = UUID.randomUUID().toString()
        activeId = id
        activeTool = tool

        if (samples?.channels != CHANNELS) samples = StrokeSamples.Builder(CHANNELS)
        samples?.reset()

        host.onStrokeBegan(
            StrokeSeed(
                id = id,
                tool = activeTool,
                calibration = calibration,
                engineId = EngineIds.ONYX,
                channels = CHANNELS,
                startedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Converts one batch of the SDK's points and hands it to the host.
     *
     * ### Everything the panel reports is kept
     *
     * `TouchPoint` carries pressure, contact size, both tilt axes and a timestamp on every point,
     * and they have been arriving all along — the reference project reads `x` and `y` and discards
     * the rest. Pressure reaches its full 1–4095 range with hundreds of distinct values per stroke;
     * this library will not throw that away (PLAN.md G3).
     *
     * Tilt is passed through **raw**. There is no `getMaxTilt()` anywhere in the SDK to normalize
     * against, and one BOOX model reports these values roughly a hundred times larger than the
     * others, so any scale invented here would be a lie that silently corrupts every app that
     * trusted it (PLAN.md §3.5).
     */
    private fun appendSamples(points: List<TouchPoint>) {
        val id = activeId ?: return
        val builder = samples ?: return
        if (points.isEmpty()) return

        builder.reset()
        // Read once for the whole batch, so every point in it is shifted by the same offset — see
        // TimestampClock.normalize.
        val uptimeNow = SystemClock.uptimeMillis()
        val wallNow = System.currentTimeMillis()
        var kept = 0
        var strayed = false
        for (point in points) {
            observeCoordinateSpace(point)
            val x = coordinateSpace.toCanvasX(point.x, screenOffset)
            val y = coordinateSpace.toCanvasY(point.y, screenOffset)
            if (!canCapture(x, y)) {
                strayed = true
                break
            }
            builder.add(
                x = x,
                y = y,
                pressure = calibration.normalizePressure(point.pressure),
                tiltX = point.tiltX.toFloat(),
                tiltY = point.tiltY.toFloat(),
                size = point.size,
                timestampMs = timestampClock.normalize(point.timestamp, uptimeNow, wallNow),
            )
            kept++
        }
        if (kept > 0) host.onStrokeSamples(id, builder.build())

        // A stroke that wanders out of the canvas or under a registered overlay **stops there** —
        // the same rule the software engine follows, and the same one the firmware's own limit rect
        // enforces (PLAN.md §3.7).
        //
        // Ending it rather than skipping the stray points is what makes that rule independent of
        // the SDK. Skipping would hand the renderer two samples either side of a toolbar with
        // nothing between them, and a renderer joins consecutive samples — so the ink the hardware
        // correctly refused to paint would reappear on commit as a straight line across the host's
        // chrome. On this device the firmware splits the stroke itself and these points never
        // arrive; the whole point is not to depend on that, because a vendor SDK that silently
        // stopped enforcing its own limit rect is exactly this project's recurring failure mode.
        if (strayed) endActiveStroke()
    }

    private fun endActiveStroke() {
        val id = activeId ?: return
        activeId = null
        host.onStrokeEnded(id)
    }

    private fun eraseAt(points: List<TouchPoint>) {
        if (points.isEmpty()) return
        val path = ArrayList<PointF>(points.size)
        for (point in points) {
            observeCoordinateSpace(point)
            val x = coordinateSpace.toCanvasX(point.x, screenOffset)
            val y = coordinateSpace.toCanvasY(point.y, screenOffset)
            if (canCapture(x, y)) path += PointF(x, y)
        }
        if (path.isEmpty()) return
        erasing = true
        host.onEraseAt(path, eraseRadiusPx())
    }

    private fun finishErase() {
        if (!erasing) return
        erasing = false
        host.onEraseEnded()
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /**
     * Feeds one raw point to the coordinate-space probe and publishes the answer.
     *
     * Called before the point is converted, so a stroke that settles the question is converted
     * correctly by its own evidence rather than by the next stroke's.
     */
    private fun observeCoordinateSpace(point: TouchPoint) {
        coordinateSpace.observe(point.x, point.y, canvasBounds, screenOffset)
        OnyxDiagnostics.recordCoordinateSpace(coordinateSpace.space, coordinateSpace.confirmed)
    }

    private fun canCapture(x: Float, y: Float): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        if (!canvasBounds.contains(px, py)) return false
        return zones.none { it.contains(px, py) }
    }

    /**
     * The width handed to the firmware, in px.
     *
     * The SDK takes a bare float with no unit, no minimum and no maximum. Converting from dp here
     * is what makes a 2 dp pen the same thickness on a 350 dpi panel as on a phone.
     *
     * ### The firmware is handed the width the app asked for, and nothing else
     *
     * No multiplier, no compensation for the multipliers our own renderers apply. The panel is armed
     * with the nominal width converted to px, it scales that however it scales it, and whatever it
     * then reports back through `TouchPoint` is what gets captured and rendered.
     *
     * That is a deliberate baseline rather than a tuned answer. Both sides scale a nominal width and
     * they do not scale it by the same factors — BOOX applies charcoal ×5 and brush ×2 internally
     * ([OnyxPenTable.widthMultiplier]), while our renderers additionally apply marker ×1.75,
     * highlighter ×4 and pencil ×2 ([PenMetrics.widthMultiplier]) — and a session on a NoteAir5C
     * established that compensating for the difference moves the error around rather than removing
     * it: pre-multiplying scaled charcoal twice, passing the nominal width left the marker thin, and
     * passing the ratio fixed the marker and left charcoal disagreeing for a reason that turned out
     * to be the *renderer* rather than the width at all.
     *
     * So the arithmetic was removed. Compensation layered on top of an unmeasured vendor behaviour
     * is guesswork that looks deliberate, and the honest baseline is to render what the hardware
     * actually reports. Closing the remaining gap is a tuning pass against this baseline, with the
     * committed-layer renderer switchable so the two candidates can be compared by hand — see
     * [OnyxRenderMode].
     */
    private fun overlayWidthPx(tool: ToolSpec): Float = renderContext.toPx(tool.widthDp)

    /**
     * The colour handed to the firmware overlay — **always opaque**.
     *
     * ### Measured on a NoteAir5C: the overlay draws nothing at all for a translucent colour
     *
     * This was the open device question Phase 4 existed to answer, and the answer is worse than the
     * one anticipated. The expectation was that the firmware would ignore the alpha byte and paint
     * an opaque stroke, so a highlighter would read solid while writing and turn translucent on
     * lift. What it actually does is paint **nothing**: pen down, pen moves, no ink anywhere, no
     * error. The stroke is captured perfectly and appears in full on pen-up — so the tool works and
     * feels completely broken, which on an e-ink device is the worst of both.
     *
     * Confirmed to be the alpha and not the width: a highlighter at 0.5 dp hands the firmware a
     * ~4 px stroke and is equally invisible.
     *
     * So the alpha is dropped here, and only here. The stroke's stored colour keeps it, the
     * committed renderer honours it, and the user gets a live stroke that is opaque while the pen is
     * down and settles to translucent when it lifts. That disagreement between the two paths is
     * real, and it is reported rather than hidden — see
     * [CanvasCapabilities.livePreviewSupportsAlpha] and [OnyxPenTable.fidelity].
     *
     * The same rule applies to every pen, not just the highlighter: an app is free to set alpha on a
     * ballpoint, and it would hit exactly this wall.
     *
     * **Kaleido panels separately paint a colour black** once its dominant channel falls below
     * roughly [LIVE_PREVIEW_COLOR_FLOOR]. Nothing is done about *that* on purpose: the stroke is
     * committed in its true colour and corrects itself on pen-up, and quietly lightening a user's
     * ink to survive a preview would be a worse lie than the preview is (PLAN.md §3.6).
     */
    private fun overlayColor(tool: ToolSpec): Int = PenMetrics.paintColor(tool) or ALPHA_OPAQUE

    private fun eraseRadiusPx(): Float =
        renderContext.toPx(eraser?.widthDp ?: EraserSpec.DEFAULT_WIDTH_DP) * 0.5f

    private fun eraserDiameterPx(spec: EraserSpec): Int =
        renderContext.toPx(spec.widthDp).toInt().coerceAtLeast(1)

    private fun stylusPointerIndex(event: MotionEvent): Int? {
        for (i in 0 until event.pointerCount) {
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                return i
            }
        }
        return null
    }

    /**
     * Runs [block] on the main thread, inline when already there.
     *
     * Posting unconditionally would add a frame of latency to every callback and, worse, would
     * reorder a begin against the points that follow it if the SDK ever delivered them from
     * different threads. Running inline when the thread is already right keeps the common case
     * exact; the post is there because a vendor SDK's threading is not a thing to assume.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun reportCallbackThreadOnce() {
        rawCallbackCount++
        if (callbackThreadReported) return
        callbackThreadReported = true
        SproutLog.d { "onyx raw callbacks arrive on '${Thread.currentThread().name}'" }
    }

    /**
     * Runs an SDK call, treating a throw as a lost hardware path rather than a lost app.
     *
     * The SDK reaches hidden framework methods through a reflection helper that swallows failures
     * silently, so most of what goes wrong here produces no exception at all. What is left — a
     * firmware that resolves the class but not the method, a panel in a state the SDK did not
     * expect — must not take a host app down mid-stroke. Losing hardware ink is survivable and
     * loud; crashing is neither.
     */
    private inline fun runQuietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            SproutLog.e("onyx SDK call '$what' failed", t)
        }
    }

    private fun buildCapabilities(calibration: DeviceCalibration): CanvasCapabilities =
        CanvasCapabilities(
            engineId = EngineIds.ONYX,
            channels = CHANNELS,
            // The committed layer honours alpha in full. Whether the *firmware overlay* does is a
            // separate question, and it is reported through the highlighter's fidelity rather than
            // here, because it affects one pen's live preview and not the canvas's colour handling.
            supportsAlpha = true,
            supportedEraserModes = setOf(EraserMode.STROKE),
            penFidelities = SproutPen.entries.associateWith { OnyxPenTable.fidelity(it) },
            liveInkIsHardware = true,
            livePreviewColorFloor = LIVE_PREVIEW_COLOR_FLOOR,
            // Measured, not assumed: the firmware overlay draws nothing whatsoever for a
            // translucent colour, so the live stroke is forced opaque and settles to the real
            // colour on pen-up. See overlayColor.
            livePreviewSupportsAlpha = false,
            calibration = calibration,
        )

    companion object {

        /** Identity of the BOOX adapter. Above the generic engine, so installing it is enough. */
        val INFO: EngineInfo = EngineInfo(
            id = EngineIds.ONYX,
            displayName = "Onyx (BOOX firmware ink)",
            priority = EngineInfo.PRIORITY_VENDOR,
        )

        /**
         * What a BOOX `TouchPoint` carries, on every point, on every device surveyed.
         *
         * No `ORIENTATION` and no `ALTITUDE`: those are Android's own properly-specified radian
         * axes, and this pipeline does not produce them. What it produces is `tiltX`/`tiltY` as
         * bare ints in an undocumented unit, which is exactly what `TILT` means.
         */
        const val CHANNELS: Int =
            InkChannel.PRESSURE or InkChannel.TILT or InkChannel.SIZE or InkChannel.TIMESTAMP

        /**
         * The Kaleido overlay paints a colour as black once its dominant RGB channel drops below
         * roughly this. A live-preview limitation only — see [overlayColor].
         */
        const val LIVE_PREVIEW_COLOR_FLOOR: Int = 180

        /** Forces the alpha byte of an ARGB colour to 255. See [overlayColor]. */
        private const val ALPHA_OPAQUE: Int = 0xFF shl 24

        /** App-scope tag for the pinned handwriting waveform. See [pinFastMode]. */
        private const val HWR_APP_SCOPE = "sprout_canvas_hwr"

        /** Suppresses the panel's mid-session quality refresh. See [openSessionIfReady]. */
        private const val EPD_UPDATE_LIST_SIZE = 512
    }
}

/**
 * Factory for [OnyxInkEngine] — found by reflection, so nothing references it in compiled code.
 *
 * @see com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
 */
public object OnyxInkEngineFactory : InkEngineFactory {

    override val info: EngineInfo get() = OnyxInkEngine.INFO

    /**
     * Whether this device can actually run the BOOX ink path — probed, in three steps.
     *
     * 1. **The manufacturer string**, as a cheap pre-filter. It is never the answer: `BaseDevice`'s
     *    implementation of the whole pen layer is an empty method, so on a non-Onyx device every
     *    SDK call succeeds and does nothing at all.
     * 2. **[com.symmetricalpalmtree.sprout.canvas.SproutCanvas.initialize]**, because the SDK needs
     *    a process-wide hidden-API exemption installed before it is touched, and no library should
     *    install one behind a host app's back. A host that forgot gets the generic engine and an
     *    error naming the missing call — never a crash, and never a BOOX that quietly started
     *    writing like a phone with nothing in logcat to explain it (PLAN.md D11).
     * 3. **The SDK itself**, resolved and prepared. A device whose manufacturer says "onyx" but
     *    whose firmware lacks the pen layer lands on the generic engine here, rather than halfway
     *    through a stroke.
     */
    override fun isSupported(context: Context): Boolean {
        if (!OnyxSdk.isOnyxHardware()) return false

        if (!com.symmetricalpalmtree.sprout.canvas.SproutCanvas.isInitialized) {
            SproutLog.e(
                "this is a BOOX device, but SproutCanvas.initialize(application) was never " +
                    "called — the Onyx hardware ink path needs a process-wide hidden-API " +
                    "exemption that a library must not install on its own. Falling back to the " +
                    "generic engine. Call SproutCanvas.initialize(this) from Application.onCreate().",
            )
            return false
        }

        return OnyxSdk.prepare(context)
    }

    override fun create(host: InkEngineHost): InkEngine = OnyxInkEngine(host)
}
