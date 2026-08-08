package com.symmetricalpalmtree.sprout.canvas.model

/**
 * How an eraser removes ink.
 *
 * Only [STROKE] is implemented in v1. The other two are declared from the start so that
 * [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.supports] has something honest
 * to say `false` about, and so adding them later is not a breaking change to the enum.
 */
public enum class EraserMode {

    /**
     * Contact removes every whole stroke it touches. Implemented on every engine, by software
     * hit-test — even on platforms whose firmware paints the ink, because the firmware gives back
     * no notion of which stroke a pixel belonged to.
     */
    STROKE,

    /** A dragged rectangle removes the strokes inside it. **Not supported in v1.** */
    AREA,

    /** Partial erase — removes the touched segment, leaving the rest of the stroke. **Not supported in v1.** */
    PIXEL,
}

/**
 * What the canvas erases with.
 *
 * Assigning a non-null [EraserSpec] to [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView.eraser]
 * arms the eraser; assigning `null` returns to the armed [ToolSpec].
 *
 * ### The barrel button ignores all of this
 *
 * A stylus barrel button engages [EraserMode.STROKE] erase for as long as it is held, on every
 * engine, regardless of which tool is armed — the hardware reports it as an eraser whether the
 * library asks or not, and an app that shipped without erase-on-button would feel broken.
 */
public data class EraserSpec(

    /** Which erase behaviour. Defaults to the only one supported in v1. */
    public val mode: EraserMode = EraserMode.STROKE,

    /**
     * Eraser contact width in **dp**, greater than zero.
     *
     * For [EraserMode.STROKE] this is the diameter of the hit-test disc dragged along the erase
     * path, not a width of removed ink.
     */
    public val widthDp: Float = DEFAULT_WIDTH_DP,
) {

    init {
        require(widthDp > 0f && widthDp.isFinite()) {
            "widthDp must be a finite value greater than zero, was $widthDp"
        }
    }

    public companion object {
        /** 12 dp — wide enough to hit a thin stroke without demanding precision. */
        public const val DEFAULT_WIDTH_DP: Float = 12f

        /** [EraserMode.STROKE] at [DEFAULT_WIDTH_DP]. */
        public val DEFAULT: EraserSpec = EraserSpec()
    }
}
