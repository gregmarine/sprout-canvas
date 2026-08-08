package com.symmetricalpalmtree.sprout.canvas.model

import android.graphics.RectF

/**
 * The samples of one stroke, stored **columnar** — one array per channel, not one object per point.
 *
 * ### Why struct-of-arrays
 *
 * A stroke can carry thousands of samples across ten channels. An array-of-structs would allocate an
 * object per point on the capture hot path, on devices whose digitizers report at up to 26× the
 * sample density of a slow stroke. Columnar storage keeps capture allocation-light and makes it
 * trivial for a host app to bulk-copy, serialize, or hand a channel straight to native code.
 *
 * For readable one-off access, [get] materializes an [InkPoint] on demand.
 *
 * ### Array ownership
 *
 * The public constructor **copies** every array it is given and trims it to [count], so a host that
 * builds a stroke from its own working buffers cannot corrupt it later by reusing them. Nothing
 * mutates a `StrokeSamples` after construction, and every exposed array has `size == count`.
 *
 * The capture path uses [Builder] instead, which appends without allocating per point and hands the
 * finished arrays over at [Builder.build].
 *
 * ### Channels
 *
 * [channels] is **derived** from which arrays are present, never passed in. A bitmask supplied
 * separately from the data it describes is a bitmask that can disagree with it; deriving removes the
 * failure mode entirely. Absent means *the device did not report it* — see [InkChannel].
 *
 * @see InkChannel
 * @see InkStroke
 */
public class StrokeSamples private constructor(
    /** Number of valid samples. Every array below has exactly this many entries. */
    public val count: Int,
    /** Bitmask of the optional channels actually present. Derived — see [InkChannel]. */
    @InkChannels public val channels: Int,
    /** X in canvas coordinates, px, origin at the canvas's top-left. */
    public val x: FloatArray,
    /** Y in canvas coordinates, px, origin at the canvas's top-left. */
    public val y: FloatArray,
    /** Normalized `0..1` against [DeviceCalibration.maxPressure]; `null` when not reported. */
    public val pressure: FloatArray?,
    /** **Raw** vendor units — see [InkChannel.TILT]. `null` when not reported. */
    public val tiltX: FloatArray?,
    /** **Raw** vendor units — see [InkChannel.TILT]. `null` when not reported. */
    public val tiltY: FloatArray?,
    /** Radians, [InkChannel.ORIENTATION] semantics; `null` when not reported. */
    public val orientation: FloatArray?,
    /** Radians, [InkChannel.ALTITUDE] semantics; `null` when not reported. */
    public val altitude: FloatArray?,
    /** Contact size as reported; `null` when not reported. */
    public val size: FloatArray?,
    /** Event time in ms on [android.os.SystemClock.uptimeMillis]' clock; `null` when not reported. */
    public val timestampMs: LongArray?,
) {

    init {
        // Cheap, once per stroke. These invariants are what let every consumer index the arrays
        // with a single bounds check against `count`.
        require(count >= 0) { "count must be >= 0, was $count" }
        require(x.size == count && y.size == count) {
            "x/y must have exactly count entries: count=$count x=${x.size} y=${y.size}"
        }
        requireExact(pressure, "pressure")
        requireExact(tiltX, "tiltX")
        requireExact(tiltY, "tiltY")
        requireExact(orientation, "orientation")
        requireExact(altitude, "altitude")
        requireExact(size, "size")
        require(timestampMs == null || timestampMs.size == count) {
            "timestampMs must have exactly count entries: count=$count was ${timestampMs?.size}"
        }
        require((tiltX == null) == (tiltY == null)) {
            "tiltX and tiltY are one channel and must be supplied together"
        }
    }

    /**
     * Builds an immutable sample set from caller-owned arrays.
     *
     * Every array is copied and trimmed to [count]; the caller keeps ownership of what it passed in
     * and may reuse it freely. Arrays longer than [count] are allowed — the surplus is discarded.
     *
     * @throws IllegalArgumentException if [count] is negative, if any supplied array is shorter than
     *   [count], or if only one of [tiltX] / [tiltY] is supplied.
     */
    public constructor(
        count: Int,
        x: FloatArray,
        y: FloatArray,
        pressure: FloatArray? = null,
        tiltX: FloatArray? = null,
        tiltY: FloatArray? = null,
        orientation: FloatArray? = null,
        altitude: FloatArray? = null,
        size: FloatArray? = null,
        timestampMs: LongArray? = null,
    ) : this(
        count = count,
        channels = deriveChannels(
            pressure = pressure,
            tiltX = tiltX,
            orientation = orientation,
            altitude = altitude,
            size = size,
            timestampMs = timestampMs,
        ),
        x = trimmedFloats(x, count, "x"),
        y = trimmedFloats(y, count, "y"),
        pressure = trimmedFloatsOrNull(pressure, count, "pressure"),
        tiltX = trimmedFloatsOrNull(tiltX, count, "tiltX"),
        tiltY = trimmedFloatsOrNull(tiltY, count, "tiltY"),
        orientation = trimmedFloatsOrNull(orientation, count, "orientation"),
        altitude = trimmedFloatsOrNull(altitude, count, "altitude"),
        size = trimmedFloatsOrNull(size, count, "size"),
        timestampMs = trimmedLongsOrNull(timestampMs, count, "timestampMs"),
    )

    /** True when this stroke carries no samples at all. */
    public val isEmpty: Boolean get() = count == 0

    /** True when [channel] is present. Convenience for [InkChannel.contains]. */
    public fun hasChannel(@InkChannels channel: Int): Boolean = InkChannel.contains(channels, channel)

    /**
     * Materializes sample [index] as an [InkPoint].
     *
     * Allocates — this is the readable-access path, not the render path. A renderer walks the
     * arrays directly.
     *
     * @throws IndexOutOfBoundsException if [index] is not in `0 until count`.
     */
    public operator fun get(index: Int): InkPoint {
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("index $index not in 0 until $count")
        }
        return InkPoint(
            x = x[index],
            y = y[index],
            pressure = pressure?.get(index),
            tiltX = tiltX?.get(index),
            tiltY = tiltY?.get(index),
            orientation = orientation?.get(index),
            altitude = altitude?.get(index),
            size = size?.get(index),
            timestampMs = timestampMs?.get(index),
        )
    }

    /**
     * Writes the axis-aligned bounding box of the sample positions into [out] and returns it.
     *
     * Allocation-free, so it is safe on the render and hit-test paths. An empty sample set produces
     * an empty rect. Note this is the bound of the *centreline*: stroke width is a rendering
     * concern and is added by the renderer, not stored here.
     */
    public fun computeBounds(out: RectF): RectF {
        if (count == 0) {
            out.setEmpty()
            return out
        }
        var left = x[0]
        var top = y[0]
        var right = left
        var bottom = top
        for (i in 1 until count) {
            val px = x[i]
            val py = y[i]
            if (px < left) left = px
            if (px > right) right = px
            if (py < top) top = py
            if (py > bottom) bottom = py
        }
        out.set(left, top, right, bottom)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeSamples) return false
        return count == other.count &&
            channels == other.channels &&
            x.contentEquals(other.x) &&
            y.contentEquals(other.y) &&
            pressure.contentEqualsOrNull(other.pressure) &&
            tiltX.contentEqualsOrNull(other.tiltX) &&
            tiltY.contentEqualsOrNull(other.tiltY) &&
            orientation.contentEqualsOrNull(other.orientation) &&
            altitude.contentEqualsOrNull(other.altitude) &&
            size.contentEqualsOrNull(other.size) &&
            timestampMs.contentEqualsOrNull(other.timestampMs)
    }

    override fun hashCode(): Int {
        var result = count
        result = 31 * result + channels
        result = 31 * result + x.contentHashCode()
        result = 31 * result + y.contentHashCode()
        result = 31 * result + (pressure?.contentHashCode() ?: 0)
        result = 31 * result + (tiltX?.contentHashCode() ?: 0)
        result = 31 * result + (tiltY?.contentHashCode() ?: 0)
        result = 31 * result + (orientation?.contentHashCode() ?: 0)
        result = 31 * result + (altitude?.contentHashCode() ?: 0)
        result = 31 * result + (size?.contentHashCode() ?: 0)
        result = 31 * result + (timestampMs?.contentHashCode() ?: 0)
        return result
    }

    /** A summary, never a dump — a stroke can hold thousands of samples. */
    override fun toString(): String =
        "StrokeSamples(count=$count, channels=${InkChannel.describe(channels)})"

    private fun requireExact(array: FloatArray?, name: String) {
        require(array == null || array.size == count) {
            "$name must have exactly count entries: count=$count was ${array?.size}"
        }
    }

    /**
     * Appends samples without allocating per point, then hands the result over as an immutable
     * [StrokeSamples].
     *
     * ### How engines use it
     *
     * One builder per stroke, or one reused across strokes with [reset] in between. [add] is
     * amortized allocation-free: the backing arrays grow geometrically and are **retained** across
     * [build], so a long capture session allocates a handful of arrays rather than one per stroke.
     *
     * [build] trims to [count] and transfers those trimmed arrays to the returned `StrokeSamples`
     * outright — the result never aliases the builder's buffers, so continuing to [add] afterwards
     * cannot disturb a stroke already handed to the host.
     *
     * Values passed to [add] for channels not declared in [channels] are ignored, so a capture loop
     * can call one fixed `add(...)` regardless of what the device turned out to report.
     *
     * @param channels the channels this stroke will carry. [InkChannel.TILT] allocates both tilt
     *   axes, since they are one channel.
     * @param initialCapacity samples to pre-allocate room for.
     */
    public class Builder(
        @InkChannels public val channels: Int,
        initialCapacity: Int = DEFAULT_INITIAL_CAPACITY,
    ) {
        private var capacity: Int = if (initialCapacity < 1) 1 else initialCapacity

        private var xBuf = FloatArray(capacity)
        private var yBuf = FloatArray(capacity)
        private var pressureBuf = allocIf(InkChannel.PRESSURE)
        private var tiltXBuf = allocIf(InkChannel.TILT)
        private var tiltYBuf = allocIf(InkChannel.TILT)
        private var orientationBuf = allocIf(InkChannel.ORIENTATION)
        private var altitudeBuf = allocIf(InkChannel.ALTITUDE)
        private var sizeBuf = allocIf(InkChannel.SIZE)
        private var timestampBuf =
            if (InkChannel.contains(channels, InkChannel.TIMESTAMP)) LongArray(capacity) else null

        /** Samples appended since construction or the last [reset]. */
        public var count: Int = 0
            private set

        init {
            require(InkChannel.isValid(channels)) {
                "unknown channel bits in 0x${channels.toString(16)}"
            }
        }

        /** Appends one sample. Arguments for undeclared channels are ignored. */
        public fun add(
            x: Float,
            y: Float,
            pressure: Float = 0f,
            tiltX: Float = 0f,
            tiltY: Float = 0f,
            orientation: Float = 0f,
            altitude: Float = 0f,
            size: Float = 0f,
            timestampMs: Long = 0L,
        ) {
            ensureCapacity(count + 1)
            val i = count
            xBuf[i] = x
            yBuf[i] = y
            pressureBuf?.set(i, pressure)
            tiltXBuf?.set(i, tiltX)
            tiltYBuf?.set(i, tiltY)
            orientationBuf?.set(i, orientation)
            altitudeBuf?.set(i, altitude)
            sizeBuf?.set(i, size)
            timestampBuf?.set(i, timestampMs)
            count = i + 1
        }

        /**
         * Appends every sample of [samples].
         *
         * Requires an exact channel match. A stroke's channels are declared once by its
         * [StrokeSeed] and every batch an engine emits for it must carry the same set — a batch
         * that quietly changed shape mid-stroke would produce a stroke whose channel mask is a lie
         * about part of its own data.
         *
         * @throws IllegalArgumentException if `samples.channels != channels`.
         */
        public fun addAll(samples: StrokeSamples) {
            require(samples.channels == channels) {
                "channel mismatch: builder=${InkChannel.describe(channels)} " +
                    "batch=${InkChannel.describe(samples.channels)}"
            }
            if (samples.count == 0) return
            ensureCapacity(count + samples.count)
            System.arraycopy(samples.x, 0, xBuf, count, samples.count)
            System.arraycopy(samples.y, 0, yBuf, count, samples.count)
            samples.pressure?.let { System.arraycopy(it, 0, pressureBuf!!, count, samples.count) }
            samples.tiltX?.let { System.arraycopy(it, 0, tiltXBuf!!, count, samples.count) }
            samples.tiltY?.let { System.arraycopy(it, 0, tiltYBuf!!, count, samples.count) }
            samples.orientation?.let { System.arraycopy(it, 0, orientationBuf!!, count, samples.count) }
            samples.altitude?.let { System.arraycopy(it, 0, altitudeBuf!!, count, samples.count) }
            samples.size?.let { System.arraycopy(it, 0, sizeBuf!!, count, samples.count) }
            samples.timestampMs?.let { System.arraycopy(it, 0, timestampBuf!!, count, samples.count) }
            count += samples.count
        }

        /** Builds an immutable snapshot of the samples added so far. The builder stays usable. */
        public fun build(): StrokeSamples = StrokeSamples(
            count = count,
            channels = channels,
            x = xBuf.copyOf(count),
            y = yBuf.copyOf(count),
            pressure = pressureBuf?.copyOf(count),
            tiltX = tiltXBuf?.copyOf(count),
            tiltY = tiltYBuf?.copyOf(count),
            orientation = orientationBuf?.copyOf(count),
            altitude = altitudeBuf?.copyOf(count),
            size = sizeBuf?.copyOf(count),
            timestampMs = timestampBuf?.copyOf(count),
        )

        /** Discards the samples added so far, retaining the buffers for the next stroke. */
        public fun reset() {
            count = 0
        }

        private fun allocIf(channel: Int): FloatArray? =
            if (InkChannel.contains(channels, channel)) FloatArray(capacity) else null

        private fun ensureCapacity(needed: Int) {
            if (needed <= capacity) return
            var next = capacity
            while (next < needed) next += next shr 1 // ×1.5, the usual growth compromise
            xBuf = xBuf.copyOf(next)
            yBuf = yBuf.copyOf(next)
            pressureBuf = pressureBuf?.copyOf(next)
            tiltXBuf = tiltXBuf?.copyOf(next)
            tiltYBuf = tiltYBuf?.copyOf(next)
            orientationBuf = orientationBuf?.copyOf(next)
            altitudeBuf = altitudeBuf?.copyOf(next)
            sizeBuf = sizeBuf?.copyOf(next)
            timestampBuf = timestampBuf?.copyOf(next)
            capacity = next
        }

        public companion object {
            /**
             * Room for 256 samples up front. A slow short stroke fits inside it; a fast long one
             * grows two or three times and then stops, because the buffers survive [reset].
             */
            public const val DEFAULT_INITIAL_CAPACITY: Int = 256
        }
    }

    public companion object {
        /** A sample set with no samples and no channels. */
        public val EMPTY: StrokeSamples = StrokeSamples(0, FloatArray(0), FloatArray(0))
    }
}

private fun deriveChannels(
    pressure: FloatArray?,
    tiltX: FloatArray?,
    orientation: FloatArray?,
    altitude: FloatArray?,
    size: FloatArray?,
    timestampMs: LongArray?,
): Int {
    var channels = InkChannel.NONE
    if (pressure != null) channels = channels or InkChannel.PRESSURE
    if (tiltX != null) channels = channels or InkChannel.TILT
    if (orientation != null) channels = channels or InkChannel.ORIENTATION
    if (altitude != null) channels = channels or InkChannel.ALTITUDE
    if (size != null) channels = channels or InkChannel.SIZE
    if (timestampMs != null) channels = channels or InkChannel.TIMESTAMP
    return channels
}

private fun trimmedFloats(array: FloatArray, count: Int, name: String): FloatArray {
    require(count >= 0) { "count must be >= 0, was $count" }
    require(array.size >= count) { "$name has ${array.size} entries, fewer than count=$count" }
    return array.copyOf(count)
}

private fun trimmedFloatsOrNull(array: FloatArray?, count: Int, name: String): FloatArray? =
    if (array == null) null else trimmedFloats(array, count, name)

private fun trimmedLongsOrNull(array: LongArray?, count: Int, name: String): LongArray? {
    if (array == null) return null
    require(count >= 0) { "count must be >= 0, was $count" }
    require(array.size >= count) { "$name has ${array.size} entries, fewer than count=$count" }
    return array.copyOf(count)
}

private fun FloatArray?.contentEqualsOrNull(other: FloatArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}

private fun LongArray?.contentEqualsOrNull(other: LongArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
