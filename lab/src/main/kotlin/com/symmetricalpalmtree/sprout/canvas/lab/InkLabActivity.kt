package com.symmetricalpalmtree.sprout.canvas.lab

import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView

/**
 * A Lab screen that hosts a canvas, and therefore has to hand the panel back when a finger arrives.
 *
 * ### Why every screen here needs this and not just one
 *
 * On e-ink the firmware ink overlay owns the entire panel while it is armed, so nothing the Android
 * view system draws reaches the screen until it is released. Every control in this app is therefore
 * mute while the canvas is live: a tapped button runs its handler, updates its own state, and shows
 * the user the frame from before the tap.
 *
 * It was found the way these things always are — a tester walking the nine pens could not tell
 * whether their taps had registered, because the selected-pen highlight and the status line were
 * both painting into a panel nobody was allowed to see. The tap worked. The feedback did not.
 *
 * Releasing on any finger contact is safe and cheap: the stylus is the only thing that needs the
 * overlay, and the engine re-arms it on the next pen stroke, so the release is invisible in use.
 *
 * This is the pattern any host app with chrome over or beside a canvas has to implement, which is
 * why the library gives it a first-class call rather than leaving each app to discover the problem
 * — see [SproutCanvasView.releaseLiveInk].
 */
abstract class InkLabActivity : AppCompatActivity() {

    /** The screen's canvas, or null before it has been inflated. */
    protected abstract val inkCanvas: SproutCanvasView?

    /**
     * Releases the overlay on finger contact, before the control underneath handles the event.
     *
     * `dispatchTouchEvent` rather than a touch listener, because button children consume their own
     * touches and a listener on the container would never fire.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val canvas = inkCanvas
            // Gated on the pen-activity gate, and that is load-bearing: a palm resting on the glass
            // mid-word produces finger events too, and releasing the overlay underneath a stroke in
            // progress drops the stroke being written (PLAN.md §5.3).
            if (canvas != null && !isStylus(event) && !canvas.isPenActive) {
                canvas.releaseLiveInk()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isStylus(event: MotionEvent): Boolean {
        val type = event.getToolType(0)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }
}
