package com.symmetricalpalmtree.sprout.canvas.render

import com.symmetricalpalmtree.sprout.canvas.model.SproutPen

/**
 * The numbers that make each [SproutPen] feel like itself.
 *
 * ### Why the constants are what they are
 *
 * The three defaults — [DEFAULT_SPACING], [DEFAULT_PRESSURE_SENSITIVITY] and [DEFAULT_SMOOTHING] —
 * are borrowed from the Onyx SDK's own `PenUtils`. Nothing forces a software renderer to agree with
 * a vendor's, but two engines have to draw the same stroke on the same device: on e-ink the user
 * sees the *firmware's* ink while writing and *ours* forever after, and any disagreement between
 * them is visible at the moment the pen lifts. Starting from the SDK's own family of values means
 * the vendor adapters are tuning a gap rather than closing a gulf.
 *
 * ### Why the width multipliers are not cosmetic
 *
 * A texture pen drawn at its nominal width has no room for its grain to exist — it comes out solid
 * and grainless, which is the failure BOOX's own ×5 charcoal multiplier exists to prevent
 * (PLAN.md §5.7). The multiplier is what makes the *drawn* width large enough to be grainy while
 * the *perceived* mark still reads like the width the app asked for. The same reasoning, in the
 * other direction, is why a highlighter is broad: a 2 dp wash is not a highlighter.
 */
internal data class PenTuning(

    /** Drawn width ÷ nominal width. See the class KDoc — this is corrective, not decorative. */
    val widthMultiplier: Float,

    /**
     * How much of the width responds to pressure.
     *
     * Width factor is `1 + sensitivity × (2p − 1)`: half pressure draws the nominal width, and the
     * factor swings symmetrically either side of it. `0` means an even-width pen that ignores
     * pressure entirely.
     */
    val pressureSensitivity: Float,

    /**
     * Whether speed stands in for pressure when the device does not report it.
     *
     * A pressure-sensitive pen on a digitizer with no pressure channel would otherwise draw a dead
     * even line, which is the one thing a fountain pen must not do. Where timestamps exist, a fast
     * stroke thins and a slow one thickens — the same relationship a real nib has, arrived at from
     * the other direction. Never blended with real pressure: a device that reports pressure has
     * already answered the question, and modulating its answer by speed on top would be inventing
     * data.
     */
    val velocityFallback: Boolean,

    /** Lower clamp on the width factor. Keeps a light touch from vanishing entirely. */
    val minWidthFactor: Float,

    /** Upper clamp on the width factor. */
    val maxWidthFactor: Float,

    /**
     * Grain stamp spacing as a fraction of the drawn width. Only texture pens read it.
     *
     * Smaller means denser, heavier grain and more stamps to place.
     */
    val spacing: Float,

    /**
     * Scales the grain's stamp size relative to the drawn width. Only texture pens read it.
     *
     * It exists because charcoal's ×5 width multiplier scales its grain up with it, and grain that
     * grows with the stroke stops being grain: at five times the width the stamps become a row of
     * distinct circles rather than texture. Charcoal wants to be *coarser* than a pencil, not
     * chunkier, so its stamps shrink relative to its width and its spacing tightens to keep the
     * mark dark.
     */
    val grainScale: Float,

    /**
     * Width smoothing across samples, `0..1`. Higher is smoother.
     *
     * Digitizer pressure is noisy at the sample level, and an unsmoothed width tracks that noise
     * into a visibly lumpy stroke. Applied as an exponential moving average with `α = 1 − smoothing`.
     */
    val smoothing: Float,

    /**
     * Alpha applied when the host's colour is fully opaque, `0..255`.
     *
     * Only [SproutPen.HIGHLIGHTER] is below 255. A highlighter that covered what it marked would be
     * a marker, so the pen supplies the translucency when the app did not ask for any — and steps
     * aside the moment the app *does* set an alpha of its own, because at that point the app has
     * stated what it wants and the stored colour is never second-guessed.
     */
    val defaultAlpha: Int,
) {

    companion object {

        /** Grain spacing as a fraction of width. Borrowed from the Onyx SDK's `PenUtils`. */
        const val DEFAULT_SPACING: Float = 0.25f

        /** Pressure→width response. Borrowed from the Onyx SDK's `PenUtils`. */
        const val DEFAULT_PRESSURE_SENSITIVITY: Float = 0.375f

        /** Width smoothing across samples. Borrowed from the Onyx SDK's `PenUtils`. */
        const val DEFAULT_SMOOTHING: Float = 0.6f

        /** Fully opaque. */
        private const val OPAQUE = 255

        private val EVEN = PenTuning(
            widthMultiplier = 1f,
            pressureSensitivity = 0f,
            velocityFallback = false,
            minWidthFactor = 1f,
            maxWidthFactor = 1f,
            spacing = DEFAULT_SPACING,
            grainScale = 1f,
            smoothing = DEFAULT_SMOOTHING,
            defaultAlpha = OPAQUE,
        )

        /**
         * The tuning for [pen].
         *
         * A `when` with no `else`: adding a pen without deciding how it draws must fail to compile
         * rather than silently inherit somebody else's numbers.
         */
        fun forPen(pen: SproutPen): PenTuning = when (pen) {

            // The reference pen. Even width, no pressure response, nothing clever.
            SproutPen.BALLPOINT -> EVEN

            // Sensitivity above the borrowed default on purpose: at 0.375 a fountain pen swings
            // 0.63×–1.38×, which reads as a slightly uneven ballpoint rather than a nib. 0.5 gives
            // a genuine 3:1 between the lightest and heaviest touch, which is what the pen is for.
            SproutPen.FOUNTAIN -> PenTuning(
                widthMultiplier = 1f,
                pressureSensitivity = 0.5f,
                velocityFallback = true,
                minWidthFactor = 0.35f,
                maxWidthFactor = 1.6f,
                spacing = DEFAULT_SPACING,
                grainScale = 1f,
                smoothing = DEFAULT_SMOOTHING,
                defaultAlpha = OPAQUE,
            )

            // ×2.0 matches the multiplier BOOX applies to its own brush, so the two paths agree on
            // what "brush at 2 dp" means. Heavier pressure response than the fountain by design.
            SproutPen.BRUSH -> PenTuning(
                widthMultiplier = 2f,
                pressureSensitivity = 0.6f,
                velocityFallback = true,
                minWidthFactor = 0.3f,
                maxWidthFactor = 1.7f,
                spacing = DEFAULT_SPACING,
                grainScale = 1f,
                smoothing = DEFAULT_SMOOTHING,
                defaultAlpha = OPAQUE,
            )

            // Broader than a ballpoint and flat-ended, but opaque — the whole point of keeping it
            // separate from the highlighter.
            SproutPen.MARKER -> EVEN.copy(widthMultiplier = 1.75f)

            // Wide and translucent. Both properties are the tool; neither is a default worth
            // arguing with.
            SproutPen.HIGHLIGHTER -> EVEN.copy(widthMultiplier = 4f, defaultAlpha = 90)

            // Texture pens: multiplied up so the grain has room to exist (PLAN.md §5.7), with a
            // mild pressure response so the graphite darkens under a firmer hand.
            SproutPen.PENCIL -> PenTuning(
                widthMultiplier = 2f,
                pressureSensitivity = DEFAULT_PRESSURE_SENSITIVITY,
                velocityFallback = false,
                minWidthFactor = 0.6f,
                maxWidthFactor = 1.3f,
                spacing = DEFAULT_SPACING,
                grainScale = 1f,
                smoothing = DEFAULT_SMOOTHING,
                defaultAlpha = OPAQUE,
            )

            // ×5.0, again matching the multiplier BOOX applies to its own charcoal.
            SproutPen.CHARCOAL -> PenTuning(
                widthMultiplier = 5f,
                pressureSensitivity = DEFAULT_PRESSURE_SENSITIVITY,
                velocityFallback = false,
                minWidthFactor = 0.6f,
                maxWidthFactor = 1.35f,
                // Tighter spacing and smaller stamps than a pencil: charcoal at five times the
                // nominal width has to stay dark without its grain growing into circles.
                spacing = 0.08f,
                grainScale = 0.6f,
                smoothing = DEFAULT_SMOOTHING,
                defaultAlpha = OPAQUE,
            )

            // The nib's length does the work here, not the width factor — see CalligraphyRenderer.
            SproutPen.CALLIGRAPHY -> EVEN

            SproutPen.DASHED -> EVEN
        }
    }
}
