package com.symmetricalpalmtree.sprout.canvas.onyx

import android.graphics.Point
import android.graphics.Rect
import com.symmetricalpalmtree.sprout.canvas.SproutLog

/**
 * Which coordinate space the BOOX raw-input pipeline speaks, decided from the points it sends.
 *
 * ### Why this is not simply known
 *
 * `TouchHelper.setLimitRect` takes a `Rect` and `RawInputCallback` hands back `TouchPoint`s, and
 * nothing in the SDK says whether either is relative to the bound view or to the screen. The
 * reference project could not answer it either: its canvas is a full-screen view at the window
 * origin, where the two spaces are numerically identical, so it has been passing view coordinates
 * and reading them back for years without the question ever arising.
 *
 * It arises here. A `SproutCanvasView` is a component — it sits in a `ViewGroup`, below a toolbar,
 * beside other views, at whatever offset the host's layout gives it. Get the space wrong and the
 * canvas captures a rectangle somewhere else on the panel, and every stroke lands displaced by the
 * canvas's own position on screen.
 *
 * ### The answer, measured on a NoteAir5C
 *
 * **View coordinates.** `TouchHelper.setLimitRect` is relative to the bound view, and this was not
 * the expected answer: the raw-input pipeline sits *below* the view system, reading a digitizer that
 * has no notion of a view's coordinate space, so screen coordinates were the reasonable guess and
 * the guess this adapter shipped first.
 *
 * The device settled it in one stroke, and the failure was total rather than subtle. On a canvas at
 * screen offset `23, 1528` measuring 1814 × 929, arming the screen-space rect
 * `(23, 1528)–(1837, 2457)` left the SDK holding a region almost entirely outside the view it was
 * bound to. The session opened, `isRawDrawingInputEnabled` reported `true`, and the pen produced
 * **nothing at all** — no ink, no callbacks, no error. Arming the same region in view coordinates,
 * `(0, 0)–(1814, 929)`, is what makes the panel take ink.
 *
 * That failure mode is worth remembering on its own: an SDK that reports itself correctly armed
 * while capturing a rectangle nobody can reach looks exactly like a device with a broken digitizer.
 *
 * ### Why the probe survives the answer
 *
 * Arming is now a decision rather than a guess, but *reporting* is still the vendor's to change, and
 * this library runs on five firmware families. So the check stays, and settles from the first stroke
 * that can settle it:
 *
 *  - When the canvas is at the screen origin the two spaces agree and there is nothing to decide.
 *    Nothing is logged, because nothing was learned.
 *  - Otherwise a raw point falls inside the canvas under **one** interpretation and outside it under
 *    the other. That point decides it, once, for the process.
 *
 * A point that is ambiguous — inside under both readings, or outside under both — decides nothing
 * and is passed over. That happens for a stroke drawn in the overlap of the two rectangles, and it
 * simply means waiting for one that is not.
 *
 * The result is a device fact rather than a per-stroke one, so it is adopted permanently and never
 * revisited: a digitizer does not change which space it reports in halfway through a session.
 *
 * **The one thing the probe cannot do is rescue a wrong arming choice**, because arming in the
 * wrong space means no points arrive to learn from. That is why the default below is a measurement
 * and not a preference.
 */
internal class CoordinateSpace {

    /** The two readings. */
    enum class Space {
        /** Relative to the panel's top-left. */
        SCREEN,

        /** Relative to the bound view's top-left. What a NoteAir5C measured — see the class KDoc. */
        VIEW,
    }

    /** Which space is in force. [Space.VIEW] until a stroke proves otherwise. */
    var space: Space = Space.VIEW
        private set

    /** True once a point settled the question, rather than the assumption merely standing. */
    var confirmed: Boolean = false
        private set

    /**
     * Offers one raw point as evidence.
     *
     * @param rawX raw x as the SDK reported it.
     * @param rawY raw y as the SDK reported it.
     * @param canvasBounds the armed capture region in **view** coordinates.
     * @param screenOffset the canvas's top-left in screen coordinates.
     */
    fun observe(rawX: Float, rawY: Float, canvasBounds: Rect, screenOffset: Point) {
        if (confirmed) return
        if (screenOffset.x == 0 && screenOffset.y == 0) return
        if (canvasBounds.isEmpty) return

        val fitsAsView = canvasBounds.containsPoint(rawX, rawY)
        val fitsAsScreen = canvasBounds.containsPoint(rawX - screenOffset.x, rawY - screenOffset.y)

        // Ambiguous either way: the point sits in the overlap of the two rectangles, or in neither.
        // Neither case is evidence, and guessing from one would be worse than waiting.
        if (fitsAsView == fitsAsScreen) return

        confirmed = true
        space = if (fitsAsScreen) Space.SCREEN else Space.VIEW
        SproutLog.d {
            "onyx raw input reports $space coordinates " +
                "(raw ${rawX.toInt()},${rawY.toInt()} against bounds $canvasBounds " +
                "at offset ${screenOffset.x},${screenOffset.y})"
        }
    }

    /**
     * Converts a raw x to canvas coordinates.
     *
     * @param screenOffset the canvas's top-left in screen coordinates.
     */
    fun toCanvasX(rawX: Float, screenOffset: Point): Float =
        if (space == Space.SCREEN) rawX - screenOffset.x else rawX

    /** Converts a raw y to canvas coordinates. See [toCanvasX]. */
    fun toCanvasY(rawY: Float, screenOffset: Point): Float =
        if (space == Space.SCREEN) rawY - screenOffset.y else rawY

    /**
     * Converts a view-coordinate rect into whatever space the pipeline is armed in.
     *
     * Writes into [out] and returns it, so arming the limit rect on every layout pass allocates
     * nothing.
     */
    fun fromCanvasRect(rect: Rect, screenOffset: Point, out: Rect): Rect {
        out.set(rect)
        if (space == Space.SCREEN) out.offset(screenOffset.x, screenOffset.y)
        return out
    }

    private fun Rect.containsPoint(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}
