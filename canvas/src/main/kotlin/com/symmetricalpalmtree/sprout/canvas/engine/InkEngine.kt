package com.symmetricalpalmtree.sprout.canvas.engine

import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * One platform's way of getting ink onto a screen: input capture plus **live** stroke display.
 *
 * ### The split that makes this library work
 *
 * The *view* always owns committed content and always renders it. The *engine* owns live ink only.
 *
 * That division is not an aesthetic choice. On e-ink the panel's own firmware paints the stroke
 * under the stylus at sub-frame latency, and the correct thing for software to do is get out of its
 * way: the engine hands live ink to the hardware and the view's committed layer catches up on
 * pen-up. Drawing live ink through an Android `Canvas` on an e-ink device produces a stroke that
 * visibly trails the pen, and it feels broken in a way no amount of tuning fixes.
 *
 * On a generic tablet there is no firmware ink, so the same interface draws the active stroke
 * through [drawLiveInk]. One contract, two completely different implementations, and a host app
 * that writes identical code either way.
 *
 * ### Threading
 *
 * Every method is called on the main thread. An engine that receives callbacks from a vendor SDK on
 * another thread is responsible for hopping back before it calls [InkEngineHost].
 *
 * @see InkEngineHost
 * @see InkEngineFactory
 */
@MainThread
public interface InkEngine {

    /** Identity and selection weight. Matches the factory's [InkEngineFactory.info]. */
    public val info: EngineInfo

    /** What this engine can do **on this device**, probed rather than assumed. */
    public val capabilities: CanvasCapabilities

    /**
     * True while the stylus is on the glass, **plus a tail after it lifts**.
     *
     * ### Why the tail exists
     *
     * On e-ink, stylus ink bypasses `MotionEvent` entirely — but a palm resting on the glass still
     * produces MotionEvents. Without a gate, a palm roll mid-word registers in the host app as a
     * tap, a swipe or a double-tap, whose handler then reaches into the live pen session and drops
     * the stroke being written. One cause, two symptoms that look unrelated: strokes intermittently
     * not registering, and phantom double-taps.
     *
     * The tail must be **longer than the platform double-tap window** (~300 ms), so that the second
     * half of a palm-induced "double tap" cannot land just after the pen leaves the glass. ~350 ms.
     *
     * Track it from both directions — the SDK's begin/end callbacks *and* stylus MotionEvents —
     * because in modes where raw drawing is disabled the SDK is silent and the stylus arrives as an
     * ordinary event.
     */
    public val isPenActive: Boolean

    /** Binds the engine to the canvas view. Called when the view attaches to a window. */
    public fun attach(view: View)

    /** Releases everything bound in [attach]. Called when the view detaches. */
    public fun detach()

    /**
     * Arms the engine's capture region.
     *
     * @param canvasBounds the region to capture in, in **view coordinates** — the view's own bounds
     *   intersected with the visible display frame. Nothing outside it may be captured.
     * @param screenOffset the canvas's top-left in **screen coordinates**.
     *
     * The offset matters more than it looks: firmware ink pipelines paint in screen coordinates
     * while `MotionEvent` arrives in view coordinates. Getting the offset wrong shows up as a baked
     * stroke visibly *jumping* on pen-lift — that is the tell, and it is the whole bug.
     *
     * Re-arm **between** strokes, never mid-contact: changing the capture region while the stylus
     * is down drops the stroke being written.
     */
    public fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point)

    /**
     * Sets the regions inside the canvas where capture must not happen — the areas covered by the
     * host's toolbars, popups and panels.
     *
     * **The semantics are uniform on every engine: no capture inside a zone, period.** A stroke may
     * not begin in one, and a stroke that wanders into one stops. This matches what Onyx's hardware
     * limit rect does, so the software engines are written to match the hardware rather than the
     * reverse.
     *
     * @param zonesInCanvasCoords zones in **view coordinates**, already clipped to the canvas and
     *   coalesced. May be empty.
     *
     * **Onyx implementors:** an empty list must not be forwarded to `TouchHelper` as-is. The SDK
     * treats an empty exclusion list as a no-op and keeps whatever zone was previously active —
     * including one restored from its own persisted state. Pass a single off-screen dummy rect to
     * genuinely clear.
     */
    public fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>)

    /** Arms a drawing tool. Takes effect on the next stroke, never on one in progress. */
    public fun setTool(tool: ToolSpec)

    /**
     * Arms the eraser, or returns to pen mode when [eraser] is `null`.
     *
     * **Onyx implementors:** release the hardware overlay *before* running any erase logic —
     * `setRawDrawingRenderEnabled(false)` and `invalidate()` first. Otherwise the overlay sits on
     * top of the result and phantom strokes stay visible after the ink they represent is gone.
     */
    public fun setEraser(eraser: EraserSpec?)

    /** Resumes capture. Called on attach and when the host's window regains focus. */
    public fun resume()

    /** Pauses capture, keeping the engine's resources. Called on focus loss. */
    public fun pause()

    /**
     * Gives up ownership of any process-global hardware pipeline, for another canvas to take.
     *
     * ### The hazard this exists for
     *
     * On BOOX the raw-drawing pipeline is a **single process-global hardware resource**, and
     * Android opens an incoming screen's surface *before* closing the outgoing one. A naive close
     * during teardown therefore kills the canvas that just came alive — intermittently, because the
     * open is asynchronous and the close is synchronous, which is the worst possible failure shape
     * to debug.
     *
     * Every close must be a *close-if-still-owner* against a process-global owner reference. That
     * guard belongs inside the adapter; a host app must never be asked to thread it through its
     * own navigation.
     */
    public fun releaseForHandoff()

    /** Drops live ink immediately — "another surface needs the panel now". */
    public fun releaseLiveInk()

    /**
     * The view's committed content changed, and why.
     *
     * Hardware engines use the reason to decide what kind of panel repaint is warranted; see
     * [RepaintReason], which explains why "something changed" is not enough information.
     */
    public fun onCommittedContentChanged(reason: RepaintReason)

    /**
     * Host-supplied `MotionEvent`, for engines that capture that way.
     *
     * @return true if the engine consumed the event.
     *
     * Even a hardware engine that gets its ink elsewhere should watch these: the stylus barrel
     * button arrives here when the raw pipeline is disabled, and palm contact arrives here always.
     */
    public fun onTouchEvent(event: MotionEvent): Boolean

    /**
     * Draws the in-progress stroke.
     *
     * **A no-op on engines where [CanvasCapabilities.liveInkIsHardware] is true** — the firmware has
     * already painted it, and painting again produces a doubled, trailing stroke.
     */
    public fun drawLiveInk(canvas: Canvas)
}
