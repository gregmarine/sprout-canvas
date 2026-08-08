package com.symmetricalpalmtree.sprout.canvas.model

/**
 * What the library measured about this device's digitizer, recorded alongside every stroke.
 *
 * ### Read at runtime, never assumed
 *
 * Every field here exists because a value that "obviously" holds across a vendor's line turned out
 * not to. Two examples that cost real debugging time in the reference project:
 *
 *  - **[maxPressure] is 4095 on some BOOX models and 4096 on others.** It is the divisor for every
 *    pressure normalization, so hardcoding either one silently skews pressure on half the fleet.
 *  - **Tilt has no common scale.** A five-device survey found `tiltX` on one BOOX model reported in
 *    the *thousands* — a 2625-unit span within a single stroke — while four others reported roughly
 *    ±60. See [tiltUnitsKnown].
 *
 * Recording the calibration with the stroke means a capture can still be interpreted correctly
 * years later, on a different device, by code that never saw the one it came from.
 */
public data class DeviceCalibration(

    /**
     * The largest pressure value this digitizer reports — the divisor for normalization.
     *
     * Meaningless when [pressureIsNormalized] is true.
     */
    public val maxPressure: Float,

    /**
     * True when the device already reports pressure in `0..1` (the ordinary Android
     * [android.view.MotionEvent.getPressure] contract), false when it reports raw digitizer counts
     * that must be divided by [maxPressure].
     */
    public val pressureIsNormalized: Boolean,

    /**
     * **Always `false` on every vendor device known to this library.**
     *
     * There is no `getMaxTilt()` anywhere in the Onyx SDK, and no documented unit for the tilt a
     * vendor pipeline reports. So [StrokeSamples.tiltX] / [StrokeSamples.tiltY] are passed through
     * **raw**, unnormalized, and this flag says so out loud. Inventing a normalization would be a
     * lie that silently corrupts every app that trusts it.
     *
     * Where Android itself supplies the properly-defined [android.view.MotionEvent.AXIS_TILT] and
     * [android.view.MotionEvent.AXIS_ORIENTATION] (both in radians), those are captured separately
     * as [InkChannel.ALTITUDE] and [InkChannel.ORIENTATION] and *are* well defined.
     */
    public val tiltUnitsKnown: Boolean,

    /** Digitizer width in its own units. Differs from screen resolution, often by a large factor. */
    public val digitizerWidth: Int,

    /** Digitizer height in its own units. */
    public val digitizerHeight: Int,

    /** Screen density in dpi, for the dp → px conversion applied to widths. */
    public val densityDpi: Int,
) {

    /**
     * Converts a raw pressure reading to the `0..1` value stored in [StrokeSamples.pressure].
     *
     * Clamped, because digitizers occasionally report slightly above their own stated maximum.
     * Returns `0f` if [maxPressure] is not usable as a divisor, rather than producing an infinity
     * that would propagate through every width calculation downstream.
     */
    public fun normalizePressure(raw: Float): Float {
        if (pressureIsNormalized) return raw.coerceIn(0f, 1f)
        if (maxPressure <= 0f || !maxPressure.isFinite()) return 0f
        return (raw / maxPressure).coerceIn(0f, 1f)
    }

    public companion object {
        /**
         * The calibration used before a device has been probed, and by engines that capture no
         * pressure at all.
         *
         * [pressureIsNormalized] is `true` so that [normalizePressure] is the identity rather than
         * a division by a made-up maximum.
         */
        public val UNKNOWN: DeviceCalibration = DeviceCalibration(
            maxPressure = 1f,
            pressureIsNormalized = true,
            tiltUnitsKnown = false,
            digitizerWidth = 0,
            digitizerHeight = 0,
            densityDpi = 0,
        )
    }
}
