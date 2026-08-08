package com.symmetricalpalmtree.sprout.canvas.engine

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import androidx.annotation.MainThread
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed

/**
 * The canvas view, as an engine sees it. Everything an engine reports goes through here.
 *
 * Implemented by [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView]; an engine never touches
 * the view directly.
 *
 * ### Threading
 *
 * Every method must be called on the main thread. Vendor SDKs are not careful about this — an
 * engine receiving callbacks off the main thread must hop back before calling anything here.
 */
@MainThread
public interface InkEngineHost {

    /** A context suitable for reading resources and system services. */
    public val context: Context

    /**
     * A stroke began. The seed declares the stroke's id, its tool, and which channels every
     * subsequent batch will carry.
     */
    public fun onStrokeBegan(seed: StrokeSeed)

    /**
     * Samples for a stroke in progress.
     *
     * **May be called more than once per stroke, and usually is.** A single pen-down to pen-up is
     * *not* guaranteed to produce one callback — vendor pipelines batch, and an engine that assumed
     * one callback per stroke would silently truncate every long stroke on the device. The host
     * accumulates.
     *
     * @param strokeId the id from the [StrokeSeed] this batch belongs to.
     * @param samples a batch whose [StrokeSamples.channels] must equal the seed's declared channels.
     */
    public fun onStrokeSamples(strokeId: String, samples: StrokeSamples)

    /** The stroke ended. The host assembles and commits it, then notifies the app. */
    public fun onStrokeEnded(strokeId: String)

    /**
     * The user erased along a path.
     *
     * Reported as a path rather than a set of stroke ids because no platform's firmware knows which
     * stroke a pixel belonged to — even where the hardware paints the ink. The host owns the
     * hit-test.
     *
     * @param path erase points in **view coordinates**.
     * @param radiusPx the erase radius in px, derived from [com.symmetricalpalmtree.sprout.canvas.model.EraserSpec.widthDp].
     */
    public fun onEraseAt(path: List<PointF>, radiusPx: Float)

    /**
     * The erase gesture ended — the eraser left the glass.
     *
     * ### Why the boundary has to be reported
     *
     * One swipe of an eraser produces a stream of [onEraseAt] calls, and the strokes it removes
     * arrive a few at a time. Without a gesture boundary the host would have to report each of
     * those batches to the app separately, and a host implementing undo would get five or ten
     * entries for something the user experienced as a single action. It is also the point at which
     * a hardware engine may finally repaint its panel: doing that per move event costs one
     * full-screen flash per event (PLAN.md §5.1).
     *
     * Safe to call when nothing was erased; the host ignores it.
     */
    public fun onEraseEnded()

    /**
     * The pen-activity gate changed. See [InkEngine.isPenActive] for why this exists and why it is
     * public API rather than an internal detail.
     */
    public fun onPenActiveChanged(active: Boolean)

    /** Requests a redraw of the live layer. Cheap — no committed content is re-recorded. */
    public fun requestInvalidate()

    /**
     * Requests that committed content be re-recorded and redrawn.
     *
     * @param region the affected area in view coordinates, or `null` for the whole canvas.
     */
    public fun requestCommittedRepaint(region: Rect?)
}
