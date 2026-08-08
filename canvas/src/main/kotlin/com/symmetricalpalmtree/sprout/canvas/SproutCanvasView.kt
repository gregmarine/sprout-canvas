package com.symmetricalpalmtree.sprout.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngine
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost
import com.symmetricalpalmtree.sprout.canvas.engine.RepaintReason
import com.symmetricalpalmtree.sprout.canvas.geometry.CanvasGeometry
import com.symmetricalpalmtree.sprout.canvas.geometry.ExclusionZoneTracker
import com.symmetricalpalmtree.sprout.canvas.geometry.StrokeHitTest
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.render.CommittedLayer
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.render.StrokeRendererRegistry

/**
 * A stylus drawing surface that captures everything the hardware reports.
 *
 * ```
 * canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 2f, color = Color.BLACK)
 * canvas.addExclusionZone(binding.floatingToolbar)
 * canvas.listener = object : SproutCanvasListener {
 *     override fun onStrokeCompleted(stroke: InkStroke) { myRepo.save(stroke) }
 * }
 * canvas.setStrokes(myRepo.load())
 * ```
 *
 * **That code is identical on every device.** On a BOOX it runs the Onyx firmware overlay, on a
 * Supernote the Ratta ink pipeline, on an ordinary tablet a software renderer — and the app never
 * learns which, unless it asks [capabilities]. Choosing is this view's job, done once at attach.
 *
 * ### It is a component, not a screen
 *
 * It embeds in any `ViewGroup`, at any size, several to a window, and it survives resize, rotation
 * and re-layout without losing content. It draws only inside its own rectangle, and never under
 * chrome registered with [addExclusionZone].
 *
 * ### What it does not do
 *
 * It stores nothing. No persistence, no file formats, no clipboard, no undo, no export, no
 * toolbars. Those belong to the host app, and this view hands back everything they need through
 * [SproutCanvasListener]. A drawing library that also owned your document model would be two
 * libraries, one of which you did not ask for.
 *
 * ### Threading
 *
 * **The public API is main-thread only.** In a debuggable build the rule is asserted, at the call
 * site that broke it — see [SproutCanvas.strictMode].
 *
 * @see SproutCanvas.initialize
 * @see SproutCanvasListener
 */
public class SproutCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val strokesById = LinkedHashMap<String, InkStroke>()
    private val activeStrokes = HashMap<String, ActiveStroke>()

    private val visibleFrame = Rect()
    private val screenLocation = IntArray(2)

    private var toolField: ToolSpec = ToolSpec.DEFAULT
    private var eraserField: EraserSpec? = null
    private var enginePreferenceField: String? = null

    private var lastPushedZones: List<Rect> = emptyList()
    private var boundsRearmDeferred = false
    private var zonesRearmDeferred = false
    private var engineSelectionAnnounced = false

    /** This canvas's renderers. Per-instance — see [StrokeRendererRegistry]. */
    private val renderers = StrokeRendererRegistry()

    private var renderContext = RenderContext(context.resources.displayMetrics.density)

    private val committed = CommittedLayer { canvas -> drawCommittedContent(canvas) }

    /** Set whenever committed content changed; consumed by the next [onDraw]. */
    private var committedDirty = true

    /** Strokes removed by the erase gesture in progress, reported to the host when it ends. */
    private val eraseGestureRemoved = LinkedHashMap<String, InkStroke>()
    private var eraseRepaintPending = false
    private var lastEraseRepaintMs = 0L
    private val eraseRepaintTick = Runnable {
        eraseRepaintPending = false
        lastEraseRepaintMs = SystemClock.uptimeMillis()
        committedDirty = true
        invalidate()
    }

    /** The host app's listener. Null by default; assigning replaces any previous one. */
    public var listener: SproutCanvasListener? = null

    init {
        // A canvas can exist before Application.onCreate ran the host's initialize() — for example
        // in a layout preview. Say so once, clearly, rather than letting a BOOX quietly write like
        // a phone with nothing in logcat to explain it (PLAN.md D11).
        SproutCanvas.warnIfUninitialized()
        if (attrs != null) readXmlAttributes(attrs, defStyleAttr)
    }

    private val host: InkEngineHost = EngineHost()

    private var engine: InkEngine = EngineRegistry.select(context, enginePreferenceField).create(host)

    private val exclusionTracker = ExclusionZoneTracker(this) { pushExclusionZones(force = false) }

    // ---------------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------------

    /**
     * The pen, width and colour new strokes are drawn with.
     *
     * Takes effect on the **next** stroke. Changing it mid-contact does not alter the stroke being
     * written — a stroke that changed pen halfway through is not something any device can render
     * and not something a user could have meant.
     */
    public var tool: ToolSpec
        get() = toolField
        set(value) {
            assertMainThread("tool")
            if (toolField == value) return
            toolField = value
            engine.setTool(value)
        }

    /**
     * The armed eraser, or `null` for pen mode.
     *
     * Assigning an [EraserSpec] arms the eraser; assigning `null` returns to [tool]. The stylus
     * barrel button erases regardless of this property — the hardware reports it as an eraser
     * whether the library asks or not.
     */
    public var eraser: EraserSpec?
        get() = eraserField
        set(value) {
            assertMainThread("eraser")
            if (eraserField == value) return
            require(value == null || capabilities.supports(value.mode)) {
                "engine '${engineInfo.id}' does not support ${value?.mode}; " +
                    "supported: ${capabilities.supportedEraserModes.joinToString()}"
            }
            eraserField = value
            engine.setEraser(value)
        }

    // ---------------------------------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------------------------------

    /**
     * Forces a specific engine by [EngineInfo.id], or `null` to let the library choose.
     *
     * Assigning rebuilds the engine immediately, keeping all committed content. An id that is
     * absent or unsupported here is logged and ignored — the harness routinely asks a device for an
     * engine it does not have, and that should be visible rather than fatal.
     */
    public var enginePreference: String?
        get() = enginePreferenceField
        set(value) {
            assertMainThread("enginePreference")
            if (enginePreferenceField == value) return
            enginePreferenceField = value
            rebuildEngine()
        }

    /** Which engine this canvas is running. */
    public val engineInfo: EngineInfo get() = engine.info

    /** What the running engine can do on this device. Probed, not assumed. */
    public val capabilities: CanvasCapabilities get() = engine.capabilities

    /**
     * True while the stylus is on the glass, plus a short tail after it lifts.
     *
     * ### Why a host app is given this
     *
     * On e-ink, stylus ink bypasses `MotionEvent` entirely — but a palm resting on the glass still
     * produces them. Host chrome that reacts to taps therefore fires on a palm roll mid-word, and
     * its handler reaches into the live pen session and drops the stroke being written. The two
     * symptoms look unrelated — strokes intermittently not registering, and phantom double-taps —
     * and they have one cause.
     *
     * Gate that chrome on `!isPenActive`, or listen for
     * [SproutCanvasListener.onPenActiveChanged]. Every app on these devices needs this; a library
     * that made each one rediscover it would be withholding the answer.
     */
    public val isPenActive: Boolean get() = engine.isPenActive

    // ---------------------------------------------------------------------------------------
    // Content
    // ---------------------------------------------------------------------------------------

    /** How many strokes the canvas holds. */
    public val strokeCount: Int get() = strokesById.size

    /**
     * The canvas's content, in the order it was added.
     *
     * A snapshot: mutating the returned list does not affect the canvas, and later changes to the
     * canvas do not affect the list. The strokes themselves are immutable.
     */
    @MainThread
    public fun getStrokes(): List<InkStroke> {
        assertMainThread("getStrokes")
        return strokesById.values.toList()
    }

    /**
     * Replaces the canvas's content.
     *
     * `setStrokes(getStrokes())` is a visual no-op — anything the canvas produced, it renders
     * identically when handed back. Fires no listener callback: the host is installing content it
     * already holds.
     */
    @MainThread
    public fun setStrokes(strokes: List<InkStroke>) {
        assertMainThread("setStrokes")
        strokesById.clear()
        strokes.forEach { stroke ->
            if (strokesById.put(stroke.id, stroke) != null) {
                SproutLog.w("setStrokes received duplicate id '${stroke.id}'; the last one wins")
            }
        }
        onCommittedContentChanged(RepaintReason.STROKES_REPLACED)
    }

    /** Adds one stroke. Replaces any existing stroke with the same id. Fires no callback. */
    @MainThread
    public fun addStroke(stroke: InkStroke) {
        assertMainThread("addStroke")
        strokesById[stroke.id] = stroke
        onCommittedContentChanged(RepaintReason.STROKE_COMMITTED)
    }

    /**
     * Removes the strokes with these ids and returns them, in canvas order.
     *
     * Unknown ids are ignored. Fires [SproutCanvasListener.onStrokesRemoved] when anything was
     * actually removed, so a host implementing undo gets the strokes back without having kept its
     * own copy of the canvas.
     */
    @MainThread
    public fun removeStrokes(ids: Collection<String>): List<InkStroke> {
        assertMainThread("removeStrokes")
        if (ids.isEmpty() || strokesById.isEmpty()) return emptyList()
        val idSet = ids.toSet()
        val removed = strokesById.values.filter { it.id in idSet }
        if (removed.isEmpty()) return emptyList()
        removed.forEach { strokesById.remove(it.id) }
        onCommittedContentChanged(RepaintReason.STROKES_REMOVED)
        listener?.onStrokesRemoved(removed)
        return removed
    }

    /**
     * Empties the canvas and returns what it held, in canvas order.
     *
     * Fires [SproutCanvasListener.onCanvasCleared] when anything was removed.
     */
    @MainThread
    public fun clear(): List<InkStroke> {
        assertMainThread("clear")
        if (strokesById.isEmpty()) return emptyList()
        val removed = strokesById.values.toList()
        strokesById.clear()
        onCommittedContentChanged(RepaintReason.CLEARED)
        listener?.onCanvasCleared(removed)
        return removed
    }

    // ---------------------------------------------------------------------------------------
    // Exclusion zones
    // ---------------------------------------------------------------------------------------

    /**
     * Registers a view whose bounds must never be written under — a floating toolbar, a popup, a
     * side panel.
     *
     * The view is **tracked**: its layout and visibility are watched, its bounds are mapped into
     * canvas coordinates on every change, and the engine is re-armed. Register it once and forget
     * it. A hidden view excludes nothing, so a dismissed popup does not leave a dead region behind.
     *
     * Registering the same [id] twice replaces the earlier registration.
     *
     * @param id a stable identifier for [removeExclusionZone]. Defaults to the view's own
     *   `toString`, which is unique per instance.
     */
    @MainThread
    @JvmOverloads
    public fun addExclusionZone(view: View, id: String = view.toString()) {
        assertMainThread("addExclusionZone")
        exclusionTracker.addView(id, view)
    }

    /**
     * Registers a fixed rectangle, in canvas coordinates, that must never be written under.
     *
     * For chrome that is not a `View` — an area reserved by a `SurfaceView` overlay, or a region a
     * host computes itself. Prefer the [View] form where one exists; it cannot fall out of date.
     */
    @MainThread
    public fun addExclusionZone(rect: Rect, id: String) {
        assertMainThread("addExclusionZone")
        exclusionTracker.addRect(id, rect)
    }

    /** Removes a registration. Returns true if one existed. */
    @MainThread
    public fun removeExclusionZone(id: String): Boolean {
        assertMainThread("removeExclusionZone")
        return exclusionTracker.remove(id)
    }

    /** Removes every registration. */
    @MainThread
    public fun clearExclusionZones() {
        assertMainThread("clearExclusionZones")
        exclusionTracker.clear()
    }

    /** How many exclusion zones are registered, tracked and manual together. */
    public val exclusionZoneCount: Int get() = exclusionTracker.size

    /** The zones currently armed on the engine, in canvas coordinates. A snapshot, for diagnostics. */
    @MainThread
    public fun activeExclusionZones(): List<Rect> = lastPushedZones.map { Rect(it) }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Resumes capture.
     *
     * The view manages this itself from attach and window focus. The explicit call is an escape
     * hatch for hosts whose navigation does not line up with those — a fragment in a pager, say.
     */
    @MainThread
    public fun resume() {
        assertMainThread("resume")
        engine.resume()
    }

    /** Pauses capture, keeping the engine's resources. Counterpart of [resume]. */
    @MainThread
    public fun pause() {
        assertMainThread("pause")
        engine.pause()
    }

    /**
     * Gives up any process-global hardware ink pipeline so another canvas can take it.
     *
     * ### Why this is public
     *
     * On BOOX the raw-drawing pipeline is a single process-global hardware resource, and Android
     * opens an incoming screen's surface *before* closing the outgoing one. The library carries the
     * close-if-still-owner guard inside its adapter, so a normal navigation needs nothing from the
     * host. This exists for the case the library cannot see — a host that keeps two canvases alive
     * at once and knows which of them should own the panel.
     */
    @MainThread
    public fun releaseForHandoff() {
        assertMainThread("releaseForHandoff")
        engine.releaseForHandoff()
    }

    /**
     * Hands the panel back to the Android view system, so the host's own chrome can repaint.
     *
     * ### The problem this solves, and it is not obvious
     *
     * On e-ink the firmware ink overlay **owns the whole panel** while it is active. That is what
     * makes writing feel instant, and it means that for as long as it is armed, nothing the Android
     * view system draws reaches the screen. A host app's toolbar can be tapped, its click handler
     * runs, its button changes state — and the panel keeps showing the frame from before the tap.
     *
     * The symptom is a user interface that appears frozen while the canvas underneath works
     * perfectly. It is invariably read as a bug in the button, and it is not: the button drew, and
     * nobody was allowed to see it.
     *
     * ### What a host should do
     *
     * Call this when a **finger** touches chrome — a toolbar, a panel, a menu — before letting the
     * control handle the event. Nothing else needs the overlay; the stylus is the only thing it
     * exists for, and the engine re-arms it automatically on the next pen stroke, so the cost is
     * nothing and the release is invisible.
     *
     * ```
     * override fun dispatchTouchEvent(event: MotionEvent): Boolean {
     *     if (event.actionMasked == MotionEvent.ACTION_DOWN &&
     *         event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
     *         !canvas.isPenActive                       // ← not optional; see below
     *     ) {
     *         canvas.releaseLiveInk()
     *     }
     *     return super.dispatchTouchEvent(event)
     * }
     * ```
     *
     * **Gate it on [isPenActive].** A palm resting on the glass mid-word produces finger events too,
     * and releasing the overlay underneath a stroke in progress drops the stroke being written. That
     * is the same single cause behind the phantom-gesture problem the gate exists for (PLAN.md §5.3).
     *
     * Use `dispatchTouchEvent` rather than a touch listener on the chrome: button children consume
     * their own touches, so a listener on the container never fires.
     *
     * A no-op on engines that draw their own live ink, where the view system was never displaced.
     */
    @MainThread
    public fun releaseLiveInk() {
        assertMainThread("releaseLiveInk")
        engine.releaseLiveInk()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderContext = RenderContext(resources.displayMetrics.density)
        committedDirty = true
        engine.attach(this)
        applyRendererOverrides()
        exclusionTracker.reattachListeners()
        engine.setTool(toolField)
        engine.setEraser(eraserField)
        engine.resume()
        pushBounds(force = true)
        pushExclusionZones(force = true)
        announceEngineSelection()
    }

    override fun onDetachedFromWindow() {
        exclusionTracker.releaseListeners()
        removeCallbacks(eraseRepaintTick)
        eraseRepaintPending = false
        engine.pause()
        engine.detach()
        // The display list is GPU-side memory belonging to a window this view has left. Content is
        // untouched — the strokes are still here, and the node is re-recorded on the next draw.
        committed.discard()
        committedDirty = true
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!isAttachedToWindow) return
        if (hasWindowFocus) engine.resume() else engine.pause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pushBounds(force = false)
        pushExclusionZones(force = false)
        // The display list is sized to the view, so a resize invalidates it even though not one
        // stroke changed. Content survives (G8) because the strokes are the source of truth and the
        // node is only ever a cache of them.
        committedDirty = true
        engine.onCommittedContentChanged(RepaintReason.BOUNDS_CHANGED)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            pushBounds(force = false)
            pushExclusionZones(force = false)
        }
    }

    /**
     * Hands the event to the engine, and holds the gesture against a scrolling parent.
     *
     * ### Why the parent has to be told
     *
     * A canvas is a component, and hosts put components inside `ScrollView`s and pagers. Those
     * parents watch a gesture and, once it has moved far enough, take it over — the child gets an
     * `ACTION_CANCEL` and the page scrolls instead. For a drawing surface that means a stroke is
     * lost the moment it goes more than a few pixels vertically, which is most strokes.
     *
     * So a gesture the engine actually consumed is claimed for the duration. It is claimed *only*
     * then: the engine consumes stylus input and ignores fingers, so a finger swipe across the
     * canvas still scrolls the page it sits in. Pen draws, finger scrolls — which is what a person
     * holding a stylus expects, and it needs no cooperation from the host to get right.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (engine.onTouchEvent(event)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                -> parent?.requestDisallowInterceptTouchEvent(true)

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL,
                -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Recording is deferred to draw time so that several content changes in one frame — an
        // ingest followed by a clear, a burst of erase hits — cost one recording rather than one
        // each. During active writing nothing is re-recorded at all: only the live layer changes.
        if (committedDirty) {
            committed.record(width, height)
            committedDirty = false
        }
        committed.draw(canvas)
        // A no-op on engines whose firmware already painted the stroke. Drawing over hardware ink
        // produces a doubled, trailing stroke that feels broken (PLAN.md G2).
        engine.drawLiveInk(canvas)
    }

    /**
     * Draws every committed stroke.
     *
     * Used both to record the display list and as the software fallback, so the two can never
     * disagree — see [CommittedLayer].
     *
     * Nothing is painted behind the strokes. The canvas is transparent by design: a host sets its
     * own background, and a library that filled white would paint over whatever the app put behind
     * it (PLAN.md §1.3 — surfaces and templates belong to the host).
     */
    private fun drawCommittedContent(canvas: Canvas) {
        for (stroke in strokesById.values) {
            if (stroke.isEmpty) continue
            renderers.rendererFor(stroke.tool.pen)
                .draw(canvas, stroke.samples, stroke.tool, stroke.id.hashCode(), renderContext)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private class ActiveStroke(val seed: StrokeSeed, val samples: StrokeSamples.Builder)

    private fun readXmlAttributes(attrs: AttributeSet, defStyleAttr: Int) {
        val typed = context.obtainStyledAttributes(
            attrs,
            R.styleable.SproutCanvasView,
            defStyleAttr,
            0,
        )
        try {
            enginePreferenceField = typed.getString(R.styleable.SproutCanvasView_sproutEngine)

            val penOrdinal = typed.getInt(
                R.styleable.SproutCanvasView_sproutPen,
                SproutPen.DEFAULT.ordinal,
            )
            val pen = SproutPen.entries.getOrElse(penOrdinal) {
                SproutLog.w("app:sproutPen=$penOrdinal is not a pen; using ${SproutPen.DEFAULT}")
                SproutPen.DEFAULT
            }

            val widthDp = typed.getFloat(
                R.styleable.SproutCanvasView_sproutWidthDp,
                ToolSpec.DEFAULT.widthDp,
            )
            val color = typed.getColor(
                R.styleable.SproutCanvasView_sproutColor,
                ToolSpec.DEFAULT.color,
            )

            toolField = if (widthDp > 0f && widthDp.isFinite()) {
                ToolSpec(pen = pen, widthDp = widthDp, color = color)
            } else {
                SproutLog.w("app:sproutWidthDp=$widthDp is not a usable width; using the default")
                ToolSpec(pen = pen, widthDp = ToolSpec.DEFAULT.widthDp, color = color)
            }
        } finally {
            typed.recycle()
        }
    }

    private fun rebuildEngine() {
        val wasAttached = isAttachedToWindow
        if (wasAttached) {
            engine.pause()
            engine.detach()
        }
        engine = EngineRegistry.select(context, enginePreferenceField).create(host)
        engineSelectionAnnounced = false

        // The armed eraser may not survive the switch — engines do not implement the same modes.
        // Disarm loudly rather than leaving the canvas holding a tool the new engine will ignore.
        val armedMode = eraserField?.mode
        if (armedMode != null && !engine.capabilities.supports(armedMode)) {
            SproutLog.w("engine '${engine.info.id}' does not support $armedMode; disarming the eraser")
            eraserField = null
        }

        if (wasAttached) {
            engine.attach(this)
            applyRendererOverrides()
            engine.setTool(toolField)
            engine.setEraser(eraserField)
            engine.resume()
            pushBounds(force = true)
            // A fresh engine has no zones armed, so the "unchanged since last push" shortcut in
            // pushExclusionZones would skip arming it entirely and leave the canvas writable under
            // the host's toolbars. Forget what the *previous* engine was told.
            lastPushedZones = emptyList()
            pushExclusionZones(force = true)
            announceEngineSelection()
        }
        engine.onCommittedContentChanged(RepaintReason.HANDOFF)
        invalidate()
    }

    /**
     * Lets the attached engine replace the renderers used for committed content.
     *
     * ### Why the table is rebuilt rather than patched
     *
     * Overrides have to be *withdrawn* when an engine goes away, not just installed when one
     * arrives. Forcing a BOOX onto the generic engine is a supported thing to do — it is how the
     * harness compares the two ink paths — and a canvas that kept drawing its committed strokes
     * through the Onyx SDK afterwards would be answering a comparison with the thing being compared.
     * Starting from a fresh table makes the withdrawal automatic instead of a step someone has to
     * remember.
     */
    private fun applyRendererOverrides() {
        renderers.reset()
        val overrides = engine.rendererOverrides
        if (overrides.isEmpty()) return
        overrides.forEach { (pen, renderer) -> renderers.setRenderer(pen, renderer) }
        SproutLog.d {
            "engine '${engine.info.id}' renders ${overrides.keys.joinToString()} on the committed layer"
        }
    }

    private fun announceEngineSelection() {
        if (engineSelectionAnnounced) return
        engineSelectionAnnounced = true
        SproutLog.d { "canvas running engine '${engine.info.id}'" }
        listener?.onEngineSelected(engine.info)
    }

    /**
     * Arms the engine's capture region.
     *
     * Deferred while the stylus is down unless [force] is set: re-arming mid-contact drops the
     * stroke being written, and a re-layout during a stroke is exactly when that happens.
     */
    private fun pushBounds(force: Boolean) {
        if (!isAttachedToWindow) return
        if (!force && isPenActive) {
            boundsRearmDeferred = true
            return
        }
        boundsRearmDeferred = false
        val visible = if (getLocalVisibleRect(visibleFrame)) visibleFrame else null
        val limit = CanvasGeometry.limitRect(width, height, visible)
        getLocationOnScreen(screenLocation)
        engine.onBoundsChanged(limit, Point(screenLocation[0], screenLocation[1]))
    }

    /** Recomputes and arms the exclusion zones. Deferred mid-stroke for the same reason as bounds. */
    private fun pushExclusionZones(force: Boolean) {
        if (!isAttachedToWindow) return
        if (!force && isPenActive) {
            zonesRearmDeferred = true
            return
        }
        zonesRearmDeferred = false
        val zones = exclusionTracker.computeZones()
        if (zones == lastPushedZones) return
        lastPushedZones = zones
        engine.onExclusionZonesChanged(zones)
    }

    private fun flushDeferredRearm() {
        if (boundsRearmDeferred) pushBounds(force = true)
        if (zonesRearmDeferred) pushExclusionZones(force = true)
    }

    private fun onCommittedContentChanged(reason: RepaintReason) {
        committedDirty = true
        engine.onCommittedContentChanged(reason)
        invalidate()
    }

    /**
     * Repaints during an erase gesture at most once per [ERASE_REPAINT_INTERVAL_MS].
     *
     * Erase hits arrive as fast as the digitizer reports, and each one would otherwise re-record
     * every stroke on the canvas. The first hit repaints immediately so the eraser feels connected
     * to the pen; the rest coalesce.
     */
    private fun throttledEraseRepaint() {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastEraseRepaintMs
        if (elapsed >= ERASE_REPAINT_INTERVAL_MS) {
            lastEraseRepaintMs = now
            committedDirty = true
            invalidate()
        } else if (!eraseRepaintPending) {
            eraseRepaintPending = true
            postDelayed(eraseRepaintTick, ERASE_REPAINT_INTERVAL_MS - elapsed)
        }
    }

    /** How far past its centreline the ink of [stroke] actually reaches, in px. */
    private fun inkOutsetPx(stroke: InkStroke): Float =
        renderers.rendererFor(stroke.tool.pen).outsetPx(stroke.tool, renderContext)

    private fun assertMainThread(member: String) {
        if (!SproutCanvas.strictMode) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SproutCanvasView.$member is main-thread only, but was called from " +
                "'${Thread.currentThread().name}'"
        }
    }

    /** The view, as the engine sees it. Private so these callbacks never reach a host app. */
    private inner class EngineHost : InkEngineHost {

        override val context: Context get() = this@SproutCanvasView.context

        override fun onStrokeBegan(seed: StrokeSeed) {
            activeStrokes[seed.id] = ActiveStroke(seed, StrokeSamples.Builder(seed.channels))
            listener?.onStrokeStarted(seed)
        }

        override fun onStrokeSamples(strokeId: String, samples: StrokeSamples) {
            val active = activeStrokes[strokeId]
            if (active == null) {
                SproutLog.w("samples for unknown stroke '$strokeId'; dropping ${samples.count}")
                return
            }
            active.samples.addAll(samples)
            invalidate()
        }

        override fun onStrokeEnded(strokeId: String) {
            val active = activeStrokes.remove(strokeId) ?: return
            // A tap that never moved produces no samples and no stroke. A single sample is a dot,
            // which is a legitimate mark and is kept.
            if (active.samples.count == 0) return
            val stroke = InkStroke(
                id = strokeId,
                samples = active.samples.build(),
                tool = active.seed.tool,
                capture = CaptureInfo(
                    engineId = active.seed.engineId,
                    calibration = active.seed.calibration,
                    startedAtMs = active.seed.startedAtMs,
                    endedAtMs = System.currentTimeMillis(),
                ),
            )
            strokesById[strokeId] = stroke
            onCommittedContentChanged(RepaintReason.STROKE_COMMITTED)
            listener?.onStrokeCompleted(stroke)
        }

        override fun onEraseAt(path: List<PointF>, radiusPx: Float) {
            if (strokesById.isEmpty()) return
            val hits = StrokeHitTest.strokesTouching(
                strokes = strokesById.values,
                path = path,
                radiusPx = radiusPx,
                strokeOutset = ::inkOutsetPx,
            )
            if (hits.isEmpty()) return
            hits.forEach { stroke ->
                strokesById.remove(stroke.id)
                eraseGestureRemoved[stroke.id] = stroke
            }
            throttledEraseRepaint()
        }

        /**
         * The eraser left the glass.
         *
         * Everything the gesture removed is reported to the host **once**, here, rather than in
         * batches as it was hit: one swipe is one action to a user, and a host implementing undo
         * should get one entry for it. The engine is told at the same moment, and not before — on
         * e-ink a panel repaint costs a visible full-screen flash, so one per gesture is the
         * budget, not one per move event (PLAN.md §5.1).
         */
        override fun onEraseEnded() {
            removeCallbacks(eraseRepaintTick)
            eraseRepaintPending = false
            if (eraseGestureRemoved.isEmpty()) return
            val removed = eraseGestureRemoved.values.toList()
            eraseGestureRemoved.clear()
            onCommittedContentChanged(RepaintReason.STROKES_REMOVED)
            listener?.onStrokesRemoved(removed)
        }

        override fun onPenActiveChanged(active: Boolean) {
            listener?.onPenActiveChanged(active)
            if (!active) flushDeferredRearm()
        }

        override fun requestInvalidate() {
            invalidate()
        }

        override fun requestCommittedRepaint(region: Rect?) {
            // The framework's region-based invalidate() is deprecated and a no-op under hardware
            // rendering, which redraws the whole view regardless. The region is not wasted: it is
            // what a hardware engine passes to its own panel repaint, where the area genuinely
            // matters — a full-panel refresh on an e-ink screen is a visible black flash.
            committedDirty = true
            invalidate()
        }
    }

    private companion object {
        /**
         * The floor on time between repaints while an eraser is moving.
         *
         * ~16 repaints a second: fast enough that ink disappears under the eraser rather than
         * behind it, slow enough that a page of committed content is not re-recorded on every
         * digitizer sample.
         */
        const val ERASE_REPAINT_INTERVAL_MS = 60L
    }
}
