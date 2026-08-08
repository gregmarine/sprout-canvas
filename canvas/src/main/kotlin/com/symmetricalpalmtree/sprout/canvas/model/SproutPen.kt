package com.symmetricalpalmtree.sprout.canvas.model

/**
 * The standardized pen vocabulary — nine pens, identical on every device.
 *
 * ### Why a vocabulary of our own
 *
 * These names mean **what they say**, which is deliberately not what the vendors' constants say.
 * The trap this exists to close: Onyx's `STROKE_STYLE_PENCIL` (0) is a plain even line — BOOX's own
 * UI labels it *Pen* — while BOOX's grainy "Pencil" is internally *charcoal*, and its "Ballpoint" is
 * internally *oily pen*. An app that learned one vendor's constants would be learning three
 * mutually contradictory dialects. sprout-canvas translates once, at the boundary.
 *
 * ### Every pen renders on every engine
 *
 * Where a platform has no native equivalent the software renderer covers it, and
 * [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.fidelity] reports honestly how
 * close the result is. What must never happen is a silent no-op: vendor SDKs swallow unsupported
 * values without an exception, a return value or a log, so a pen that "doesn't work" would simply
 * draw nothing and say nothing.
 *
 * @see com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
 * @see ToolSpec
 */
public enum class SproutPen(
    /** A short label suitable for a tool picker. */
    public val displayName: String,
    /**
     * True when the pen's width is meant to vary with stylus pressure.
     *
     * Descriptive, not a capability claim: a pressure-sensitive pen on a device that reports no
     * pressure draws at its nominal width. Check
     * [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.channels] for what the
     * hardware actually reports.
     */
    public val isPressureSensitive: Boolean,
    /**
     * True when the pen is meant to be translucent, so ink beneath it stays readable.
     *
     * Only [HIGHLIGHTER]. This is the whole reason [MARKER] and [HIGHLIGHTER] are separate tools.
     */
    public val isTranslucentByDefault: Boolean,
) {

    /** Even-width opaque line — **the default**, and the pen every other one is judged against. */
    BALLPOINT("Ballpoint", isPressureSensitive = false, isTranslucentByDefault = false),

    /** Pressure-responsive, thin to thick. */
    FOUNTAIN("Fountain", isPressureSensitive = true, isTranslucentByDefault = false),

    /** Heavier pressure-responsive stroke; broader than [FOUNTAIN] at the same nominal width. */
    BRUSH("Brush", isPressureSensitive = true, isTranslucentByDefault = false),

    /**
     * Flat, even-width, **opaque** pen.
     *
     * Not a highlighter. On Supernote these are genuinely different firmware pens (`NEEDLE` vs
     * `MARK`); on Onyx they are one firmware style separated by width and alpha. Collapsing them
     * into one name would recreate exactly the vendor ambiguity this enum exists to remove.
     */
    MARKER("Marker", isPressureSensitive = false, isTranslucentByDefault = false),

    /** Wide translucent wash that leaves ink underneath readable. */
    HIGHLIGHTER("Highlighter", isPressureSensitive = false, isTranslucentByDefault = true),

    /** Grainy graphite. Needs real width to show its grain — see the width trap in PLAN.md §5.7. */
    PENCIL("Pencil", isPressureSensitive = true, isTranslucentByDefault = false),

    /** Heavy grain, broader than [PENCIL]. */
    CHARCOAL("Charcoal", isPressureSensitive = true, isTranslucentByDefault = false),

    /** 45° chisel nib — one diagonal thick, the other thin. */
    CALLIGRAPHY("Calligraphy", isPressureSensitive = false, isTranslucentByDefault = false),

    /** Dashed line at an even width. */
    DASHED("Dashed", isPressureSensitive = false, isTranslucentByDefault = false),

    ;

    public companion object {
        /**
         * [BALLPOINT] — the pen a canvas is armed with when the host says nothing.
         *
         * An even-width opaque line is the one result that looks intentional on every panel, from a
         * colour LCD to a mono e-ink screen with no grain rendering at all.
         */
        public val DEFAULT: SproutPen = BALLPOINT
    }
}
