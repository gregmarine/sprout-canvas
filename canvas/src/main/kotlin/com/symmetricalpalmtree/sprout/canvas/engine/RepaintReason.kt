package com.symmetricalpalmtree.sprout.canvas.engine

/**
 * Why the canvas's committed content changed.
 *
 * ### Why an engine is told *why*, not just *that*
 *
 * On e-ink the correct response to a repaint is not one thing. Committing a stroke can be absorbed
 * by the panel's fast waveform, while clearing the canvas needs a full-panel handwriting repaint —
 * and calling for that repaint on the wrong event costs a visible black flash per event. Disabling
 * the hardware render toggle does **not** clear the panel's buffer, so any change to *what is on
 * screen* has to be followed by an explicit repaint or the user is left looking at grey residue of
 * ink that no longer exists.
 *
 * An engine that only knew "something changed" would have to choose between flashing constantly and
 * leaving stale ink on the panel. So the view says which of those cases it is.
 *
 * @see InkEngine.onCommittedContentChanged
 */
public enum class RepaintReason {

    /** A captured stroke was committed. The most frequent case, and the cheapest. */
    STROKE_COMMITTED,

    /** Strokes were erased or removed. Content disappeared; residue is the risk. */
    STROKES_REMOVED,

    /** The canvas's whole content was replaced by the host — `setStrokes`. */
    STROKES_REPLACED,

    /** The canvas was emptied — `clear`. */
    CLEARED,

    /** The canvas was resized, re-laid-out or rotated. Everything on screen moved. */
    BOUNDS_CHANGED,

    /**
     * The engine is giving up the panel, or taking it back — a handoff between canvases, or a
     * window regaining focus. Committed content did not change, but what is on the panel did.
     */
    HANDOFF,
}
