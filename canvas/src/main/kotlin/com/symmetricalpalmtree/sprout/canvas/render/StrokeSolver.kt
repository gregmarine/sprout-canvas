package com.symmetricalpalmtree.sprout.canvas.render

import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import kotlin.math.sqrt

/**
 * Turns captured samples into a drawable centreline: which points survive, and how wide the stroke
 * is at each of them.
 *
 * ### Why this is separate from the renderers
 *
 * Nothing here touches `android.graphics`. That is deliberate: the taper of a fountain pen, the
 * cadence of a dash and the decimation threshold are all *geometry*, and geometry can be asserted
 * exactly, on the JVM, in microseconds, on every build. A renderer regression that changes a paint
 * style still needs pixels to catch — that is what the golden suite is for (PLAN.md §4.1.1) — but a
 * regression that changes the *shape* of the ink is caught here, without a device, without
 * Robolectric, and without the rendering variance that makes pixel comparison a poor build gate.
 *
 * ### Reuse
 *
 * One solver per renderer, reused across strokes. The output arrays grow to the longest stroke seen
 * and are then reused forever, so recording a page of committed content allocates nothing after the
 * first stroke. Everything here runs on the main thread; the buffers are not thread-safe and are not
 * meant to be.
 */
internal class StrokeSolver {

    /** Points in the solved centreline. Never larger than the sample count. */
    var count: Int = 0
        private set

    /** Solved x, valid in `0 until count`. */
    var x: FloatArray = FloatArray(INITIAL_CAPACITY)
        private set

    /** Solved y, valid in `0 until count`. */
    var y: FloatArray = FloatArray(INITIAL_CAPACITY)
        private set

    /** Drawn width in px at each solved point, valid in `0 until count`. */
    var width: FloatArray = FloatArray(INITIAL_CAPACITY)
        private set

    /** The largest entry in [width]. What a renderer outsets its damage rect by. */
    var maxWidth: Float = 0f
        private set

    /** Total length of the solved centreline in px. Grain and dash cadence are measured against it. */
    var length: Float = 0f
        private set

    /** Convenience overload for the committed path, where the samples are already assembled. */
    fun solve(samples: StrokeSamples, tuning: PenTuning, nominalWidthPx: Float) {
        solve(
            srcX = samples.x,
            srcY = samples.y,
            srcPressure = samples.pressure,
            srcTimestampMs = samples.timestampMs,
            srcCount = samples.count,
            tuning = tuning,
            nominalWidthPx = nominalWidthPx,
        )
    }

    /**
     * Solves from raw columnar sample data.
     *
     * @param nominalWidthPx the width the app asked for, in px, **before** [PenTuning.widthMultiplier].
     */
    fun solve(
        srcX: FloatArray,
        srcY: FloatArray,
        srcPressure: FloatArray?,
        srcTimestampMs: LongArray?,
        srcCount: Int,
        tuning: PenTuning,
        nominalWidthPx: Float,
    ) {
        count = 0
        maxWidth = 0f
        length = 0f
        if (srcCount <= 0) return

        ensureCapacity(srcCount)
        val drawnWidth = nominalWidthPx * tuning.widthMultiplier
        val alpha = 1f - tuning.smoothing.coerceIn(0f, 1f)

        // A stroke's samples are decimated and widened in one pass: each sample that survives
        // decimation is measured against the sample that survived before it, so the speed the
        // velocity fallback reads is the speed along the line actually drawn.
        var lastKeptSrc = 0
        var smoothed = 0f

        for (i in 0 until srcCount) {
            val px = srcX[i]
            val py = srcY[i]

            var segment = 0f
            if (count > 0) {
                val dx = px - x[count - 1]
                val dy = py - y[count - 1]
                segment = sqrt(dx * dx + dy * dy)
                // Always keep the final sample: a stroke that ended just short of the threshold
                // would otherwise stop a fraction of a pixel before the pen did, and a two-sample
                // tap would collapse to a dot in the wrong place.
                if (segment < MIN_SEGMENT_PX && i != srcCount - 1) continue
            }

            val pressure = effectivePressure(
                srcPressure = srcPressure,
                srcTimestampMs = srcTimestampMs,
                index = i,
                previousIndex = lastKeptSrc,
                distancePx = segment,
                tuning = tuning,
                isFirst = count == 0,
            )

            val factor = (1f + tuning.pressureSensitivity * (2f * pressure - 1f))
                .coerceIn(tuning.minWidthFactor, tuning.maxWidthFactor)
            val raw = drawnWidth * factor
            smoothed = if (count == 0) raw else smoothed + alpha * (raw - smoothed)

            x[count] = px
            y[count] = py
            width[count] = smoothed
            if (smoothed > maxWidth) maxWidth = smoothed
            length += segment
            count++
            lastKeptSrc = i
        }
    }

    /**
     * The pressure to draw sample [index] at, in `0..1`.
     *
     * Real pressure wins whenever the device reports it. Speed stands in only where the channel is
     * absent and the pen asked for the fallback — see [PenTuning.velocityFallback]. With neither,
     * every sample sits at [NEUTRAL_PRESSURE], which makes the width factor exactly 1 and the pen
     * draw at its nominal width: a pressure-sensitive pen on a digitizer that reports nothing must
     * look like an even pen, not like a pen pressed as hard as possible or not at all.
     */
    private fun effectivePressure(
        srcPressure: FloatArray?,
        srcTimestampMs: LongArray?,
        index: Int,
        previousIndex: Int,
        distancePx: Float,
        tuning: PenTuning,
        isFirst: Boolean,
    ): Float {
        if (srcPressure != null) return srcPressure[index].coerceIn(0f, 1f)
        if (!tuning.velocityFallback || srcTimestampMs == null || isFirst) return NEUTRAL_PRESSURE

        val dtMs = srcTimestampMs[index] - srcTimestampMs[previousIndex]
        if (dtMs <= 0L) return NEUTRAL_PRESSURE
        val speed = distancePx / dtMs
        return (1f - speed / VELOCITY_REFERENCE_PX_PER_MS).coerceIn(0f, 1f)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= x.size) return
        x = FloatArray(needed)
        y = FloatArray(needed)
        width = FloatArray(needed)
    }

    companion object {

        /**
         * Samples closer together than this are dropped.
         *
         * Sub-pixel spacing costs geometry and buys nothing a screen can show, and digitizers
         * report at up to 26× the sample density of a slow stroke depending only on how fast the
         * hand moved (PLAN.md §5.6). Half a pixel is below the threshold of visible change and well
         * above the noise floor. Note this affects **rendering only** — the captured samples are
         * stored and handed back untouched.
         */
        const val MIN_SEGMENT_PX: Float = 0.5f

        /**
         * The speed, in px per ms, at which the velocity fallback reports zero pressure.
         *
         * Fast handwriting runs around 2 px/ms at typical tablet densities, so a value slightly
         * above that puts ordinary writing across the usable part of the curve instead of pinned at
         * one end of it.
         */
        const val VELOCITY_REFERENCE_PX_PER_MS: Float = 2.5f

        /** The pressure that produces exactly the nominal width, whatever the sensitivity. */
        const val NEUTRAL_PRESSURE: Float = 0.5f

        private const val INITIAL_CAPACITY = 256
    }
}
