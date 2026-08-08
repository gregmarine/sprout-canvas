package com.symmetricalpalmtree.sprout.canvas.model

import kotlin.math.abs

/**
 * A preset ladder of stroke widths, in **dp**.
 *
 * Offered for convenience — a tool picker wants a handful of rungs, not a slider from 0.1 to 40.
 * Arbitrary widths are accepted everywhere the ladder is: [ToolSpec.widthDp] takes a raw `Float`.
 *
 * ### Why dp, always
 *
 * Each platform measures width in its own units — Onyx takes a raw float in its own scale,
 * Supernote takes an EMR size integer — and those units do not agree with each other or with
 * pixels. The library converts at the boundary so an app never learns a device unit, and a 2 dp
 * line is the same physical thickness on a 227 dpi phone and a 300 dpi e-ink panel.
 *
 * @see ToolSpec
 */
public enum class SproutWidth(
    /** The width in density-independent pixels. */
    public val dp: Float,
) {

    /** 0.5 dp — the thinnest line that survives on an e-ink panel. */
    HAIRLINE(0.5f),

    /** 1 dp. */
    FINE(1f),

    /** 1.5 dp. */
    THIN(1.5f),

    /** 2 dp — the default. Comfortable handwriting weight at typical tablet densities. */
    MEDIUM(2f),

    /** 3 dp. */
    BOLD(3f),

    /** 5 dp. */
    HEAVY(5f),

    /** 8 dp. */
    XL(8f),

    /** 12 dp — broad enough for a highlighter, and for texture pens to show their grain. */
    XXL(12f),

    ;

    public companion object {
        /** [MEDIUM] — 2 dp. */
        public val DEFAULT: SproutWidth = MEDIUM

        /** The rung nearest [dp]. Ties resolve to the thinner rung. */
        public fun nearest(dp: Float): SproutWidth =
            entries.minByOrNull { abs(it.dp - dp) } ?: DEFAULT
    }
}
