package com.symmetricalpalmtree.sprout.canvas.engine.generic

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * True while the stylus is on the glass, plus a tail after it lifts.
 *
 * ### The bug this exists to prevent
 *
 * A palm resting on a panel produces `MotionEvent`s — and on e-ink, stylus ink bypasses those
 * events entirely, so the palm is the *only* thing the host's gesture detectors see mid-word. They
 * fire, their handlers reach into the live pen session, and the stroke being written is dropped.
 * One cause, two symptoms that look completely unrelated: strokes intermittently not registering,
 * and phantom double-taps (PLAN.md §5.3).
 *
 * ### Why the tail, and why this length
 *
 * [TAIL_MS] is deliberately longer than the platform's double-tap window (~300 ms). Without that
 * margin the *second* half of a palm-induced double tap lands just after the pen leaves the glass,
 * where it looks like a clean deliberate gesture and is treated as one. Short enough that a real
 * finger tap right after writing still registers — **this constant is the tuning dial** if taps ever
 * start feeling swallowed.
 *
 * The gate is fed on the generic engine from stylus `MotionEvent`s alone, because on this engine all
 * ink arrives that way. Vendor engines must feed it from their SDK's begin/end callbacks *as well*,
 * since in modes where raw drawing is disabled the SDK goes silent and the stylus arrives as an
 * ordinary event.
 *
 * @param onChanged fired on every transition, on the main thread. Never fired for a repeat of the
 *   state already reported.
 */
internal class PenActivityGate(private val onChanged: (Boolean) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())

    private var penDown = false
    private var hasLifted = false
    private var lastLiftMs = 0L
    private var reported = false

    private val expire = Runnable { publish() }

    /** True while the stylus is down, and for [TAIL_MS] after it lifts. */
    val isActive: Boolean
        get() = penDown || (hasLifted && SystemClock.uptimeMillis() - lastLiftMs < TAIL_MS)

    /** The stylus touched down. */
    fun onPenDown() {
        handler.removeCallbacks(expire)
        penDown = true
        publish()
    }

    /**
     * The stylus lifted or was cancelled.
     *
     * The gate stays open for the tail, so the transition to `false` is posted rather than reported
     * now.
     */
    fun onPenUp() {
        penDown = false
        hasLifted = true
        lastLiftMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(expire)
        // One millisecond past the window, so the delayed check cannot land on the exact boundary
        // and find the gate still open by a rounding error.
        handler.postDelayed(expire, TAIL_MS + 1)
        publish()
    }

    /** Closes the gate immediately, without a tail. For detach and teardown. */
    fun reset() {
        handler.removeCallbacks(expire)
        penDown = false
        hasLifted = false
        publish()
    }

    private fun publish() {
        val active = isActive
        if (active == reported) return
        reported = active
        onChanged(active)
    }

    companion object {
        /** 350 ms — longer than the platform double-tap window. See the class KDoc. */
        const val TAIL_MS: Long = 350L
    }
}
