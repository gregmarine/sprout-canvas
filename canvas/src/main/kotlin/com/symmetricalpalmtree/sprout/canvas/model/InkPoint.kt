package com.symmetricalpalmtree.sprout.canvas.model

/**
 * A single sample, materialized as an object.
 *
 * ### Why this is a *cursor*, not the storage format
 *
 * Strokes are stored columnar — see [StrokeSamples]. A long stroke can carry thousands of samples
 * across ten channels, and an array-of-structs would allocate one object per point. `InkPoint`
 * exists for the cases where readable code matters more than allocation: a test assertion, a
 * diagnostic dump, a host app inspecting the first and last sample. It is produced on demand by
 * [StrokeSamples.get] and never held internally.
 *
 * **A `null` channel means the device did not report it** — not that the value was zero. See
 * [InkChannel].
 */
public data class InkPoint(
    /** X in canvas coordinates, px, origin at the canvas's top-left. */
    public val x: Float,
    /** Y in canvas coordinates, px, origin at the canvas's top-left. */
    public val y: Float,
    /** Normalized `0..1`, or `null` if the device does not report pressure. */
    public val pressure: Float? = null,
    /** Raw vendor units — see [InkChannel.TILT]. `null` if not reported. */
    public val tiltX: Float? = null,
    /** Raw vendor units — see [InkChannel.TILT]. `null` if not reported. */
    public val tiltY: Float? = null,
    /** Radians, [InkChannel.ORIENTATION] semantics. `null` if not reported. */
    public val orientation: Float? = null,
    /** Radians, [InkChannel.ALTITUDE] semantics. `null` if not reported. */
    public val altitude: Float? = null,
    /** Contact size as the device reports it. `null` if not reported. */
    public val size: Float? = null,
    /** Event time in ms on [android.os.SystemClock.uptimeMillis]' clock. `null` if not reported. */
    public val timestampMs: Long? = null,
)
