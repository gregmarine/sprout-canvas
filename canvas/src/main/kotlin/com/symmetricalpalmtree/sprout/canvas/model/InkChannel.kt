package com.symmetricalpalmtree.sprout.canvas.model

import androidx.annotation.IntDef

/**
 * Type marker for an [InkChannel] bitmask.
 *
 * Source-retention only; it exists so an IDE can offer the right constants and flag a value that is
 * not a channel mask at all.
 */
@Retention(AnnotationRetention.SOURCE)
@IntDef(
    flag = true,
    value = [
        // NONE is listed explicitly: "position only" is a real answer, not the absence of one.
        // An engine that reports no optional channels — the stub, or a digitizer that gives back
        // bare coordinates — has to be able to say so without tripping lint.
        InkChannel.NONE,
        InkChannel.PRESSURE,
        InkChannel.TILT,
        InkChannel.ORIENTATION,
        InkChannel.ALTITUDE,
        InkChannel.SIZE,
        InkChannel.TIMESTAMP,
    ],
)
public annotation class InkChannels

/**
 * The optional per-sample channels a stroke may carry, as a bitmask.
 *
 * ### Absent means "the device did not report it"
 *
 * A cleared bit is a statement about the hardware, not about the value. It does **not** mean the
 * channel was zero — it means the device never told us. That distinction matters: an app that treats
 * a missing pressure channel as "pressure 0" draws nothing, and an app that treats it as
 * "pressure 1" draws a stroke that ignores a pressure-sensitive digitizer. Ask the mask first.
 *
 * `x` and `y` are not channels — every sample has a position, on every device.
 *
 * @see StrokeSamples.channels
 * @see com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.channels
 */
public object InkChannel {

    /** No optional channels — position only. */
    public const val NONE: Int = 0

    /** Stylus pressure, **normalized to `0..1`** against [DeviceCalibration.maxPressure]. */
    public const val PRESSURE: Int = 1 shl 0

    /**
     * Vendor tilt (`tiltX` / `tiltY`), reported in **raw device units**.
     *
     * There is no common scale and no vendor API to discover one — see the warning on
     * [DeviceCalibration.tiltUnitsKnown]. Both axes are always present together or not at all.
     */
    public const val TILT: Int = 1 shl 1

    /**
     * Stylus rotation about its own axis, in radians, with Android's
     * [android.view.MotionEvent.AXIS_ORIENTATION] semantics. Properly defined, unlike [TILT].
     */
    public const val ORIENTATION: Int = 1 shl 2

    /**
     * Stylus angle away from the surface, in radians, with Android's
     * [android.view.MotionEvent.AXIS_TILT] semantics — `0` is perpendicular to the panel.
     * Properly defined, unlike [TILT].
     */
    public const val ALTITUDE: Int = 1 shl 3

    /** Contact size, as the device reports it. */
    public const val SIZE: Int = 1 shl 4

    /** Per-sample event time in milliseconds, on [android.os.SystemClock.uptimeMillis]' clock. */
    public const val TIMESTAMP: Int = 1 shl 5

    /** Every channel this version of the library defines. */
    public const val ALL: Int = PRESSURE or TILT or ORIENTATION or ALTITUDE or SIZE or TIMESTAMP

    /** True when [mask] carries every bit in [channel]. `channel == NONE` is always true. */
    public fun contains(@InkChannels mask: Int, @InkChannels channel: Int): Boolean =
        mask and channel == channel

    /** True when [mask] contains no bits outside [ALL]. */
    public fun isValid(@InkChannels mask: Int): Boolean = mask and ALL.inv() == 0

    /**
     * A stable, human-readable rendering of [mask] — `"PRESSURE|TILT|TIMESTAMP"`, or `"none"`.
     *
     * Used by the Lab's device report. Diagnostics that print a bare integer age badly.
     */
    public fun describe(@InkChannels mask: Int): String {
        if (mask == NONE) return "none"
        val names = ArrayList<String>(6)
        if (contains(mask, PRESSURE)) names += "PRESSURE"
        if (contains(mask, TILT)) names += "TILT"
        if (contains(mask, ORIENTATION)) names += "ORIENTATION"
        if (contains(mask, ALTITUDE)) names += "ALTITUDE"
        if (contains(mask, SIZE)) names += "SIZE"
        if (contains(mask, TIMESTAMP)) names += "TIMESTAMP"
        val unknown = mask and ALL.inv()
        if (unknown != 0) names += "0x${unknown.toString(16)}"
        return names.joinToString("|")
    }
}
