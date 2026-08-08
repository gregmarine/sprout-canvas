package com.symmetricalpalmtree.sprout.canvas.engine

/**
 * How faithfully an engine reproduces a given [com.symmetricalpalmtree.sprout.canvas.model.SproutPen].
 *
 * ### Why this is public API
 *
 * Every pen renders on every engine — there is no such thing as an unsupported pen. But "renders"
 * covers a range, and an app building a tool picker deserves to know where on that range it is
 * standing, so it can annotate a pen rather than let a user wonder why the pencil on their
 * Supernote has no grain.
 *
 * ### Where the two ink paths disagree
 *
 * On e-ink the user sees the *firmware's* stroke while writing and *our* committed stroke forever
 * after. Where those two differ, the fidelity reported is **the lower of the two**. A pen that
 * looks right only after pen-up is not a pen that looks right.
 *
 * @see CanvasCapabilities.fidelity
 */
public enum class PenFidelity {

    /**
     * The engine reproduces this pen fully, using the best path it has.
     *
     * On a vendor engine that means a real firmware pen — Supernote's `MARK` genuinely is a
     * highlighter. On the generic engine it means our own software renderer, which is the reference
     * the vendor paths are tuned against.
     */
    NATIVE,

    /**
     * Reproduced by composing a different underlying pen, and visually correct.
     *
     * The reference case: Onyx has no highlighter, so `HIGHLIGHTER` is its `MARKER` style widened
     * and given alpha. The result is a highlighter; it is simply not a distinct hardware pen.
     */
    EMULATED,

    /**
     * A recognizable but visibly imperfect stand-in.
     *
     * The reference case: Supernote's firmware has no grain, so `PENCIL` writes as a clean line
     * while the pen is on the glass. It reads as a pencil in context; it does not read as graphite.
     */
    APPROXIMATE,
}
