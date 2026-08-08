package com.symmetricalpalmtree.sprout.canvas

import androidx.annotation.MainThread
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSeed

/**
 * What a canvas tells its host.
 *
 * Every method has a default no-op body, so an app implements only the events it needs:
 *
 * ```
 * canvas.listener = object : SproutCanvasListener {
 *     override fun onStrokeCompleted(stroke: InkStroke) { myRepo.save(stroke) }
 * }
 * ```
 *
 * ### Which changes are reported
 *
 * The library stores nothing and owns no history — undo, redo and persistence belong to the host —
 * so this interface's job is to hand back everything a host needs to implement them. That is why an
 * erase reports the removed [InkStroke]s themselves rather than their ids: a host that got only ids
 * would have to keep a shadow copy of the entire canvas just to be able to undo.
 *
 * The rule for what fires:
 *
 *  - **Capture** — [onStrokeStarted] and [onStrokeCompleted] fire only for ink the user drew.
 *  - **Removal** — [onStrokesRemoved] and [onCanvasCleared] fire whatever caused them, gesture or
 *    host call, because both hand back content that would otherwise be gone.
 *  - **Installation** — `setStrokes` and `addStroke` fire nothing. The host is installing content
 *    it already holds, and echoing it back is how listener loops start.
 *
 * ### Threading
 *
 * Every callback arrives on the main thread.
 */
@MainThread
public interface SproutCanvasListener {

    /**
     * A stroke began. The canvas has committed nothing yet.
     *
     * Useful for dismissing chrome the moment the pen touches down, before the stroke it would have
     * covered is drawn.
     */
    public fun onStrokeStarted(seed: StrokeSeed) {}

    /** A stroke finished and is now part of the canvas's content. */
    public fun onStrokeCompleted(stroke: InkStroke) {}

    /** Strokes were removed — by an erase gesture, or by `removeStrokes`. Never empty. */
    public fun onStrokesRemoved(removed: List<InkStroke>) {}

    /** The canvas was emptied by `clear`. [removed] is everything it held. Never empty. */
    public fun onCanvasCleared(removed: List<InkStroke>) {}

    /**
     * The pen-activity gate opened or closed.
     *
     * ### Why a host app wants this
     *
     * A palm resting on an e-ink panel produces `MotionEvent`s even while the stylus ink itself
     * bypasses them entirely. Host chrome that reacts to taps will therefore fire on a palm roll
     * mid-word, and its handler will reach into the live pen session and drop the stroke being
     * written. Gating that chrome on `!active` fixes it.
     *
     * `false` arrives a short time *after* the pen leaves the glass — long enough that the second
     * half of a palm-induced "double tap" cannot slip through behind it.
     */
    public fun onPenActiveChanged(active: Boolean) {}

    /**
     * The canvas chose an engine, or switched to a different one.
     *
     * Fires once when the canvas is first laid out, and again if `enginePreference` changes it.
     */
    public fun onEngineSelected(info: EngineInfo) {}
}
