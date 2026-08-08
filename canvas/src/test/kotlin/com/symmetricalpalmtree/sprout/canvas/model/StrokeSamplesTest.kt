package com.symmetricalpalmtree.sprout.canvas.model

import android.graphics.RectF
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The columnar sample model's invariants.
 *
 * These are the tests that make every downstream consumer's life simple: if `count` and array
 * lengths always agree, a renderer can index with one bounds check, and if the channel mask is
 * always derived, nothing anywhere has to consider the case where it lies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class StrokeSamplesTest {

    // --- Channel derivation -------------------------------------------------------------------

    @Test
    fun `channels are derived from the arrays actually supplied`() {
        val positionOnly = StrokeSamples(2, floatArrayOf(0f, 1f), floatArrayOf(0f, 1f))
        assertEquals(InkChannel.NONE, positionOnly.channels)

        val withPressure = StrokeSamples(
            count = 2,
            x = floatArrayOf(0f, 1f),
            y = floatArrayOf(0f, 1f),
            pressure = floatArrayOf(0.5f, 0.6f),
        )
        assertEquals(InkChannel.PRESSURE, withPressure.channels)
        assertTrue(withPressure.hasChannel(InkChannel.PRESSURE))
        assertFalse(withPressure.hasChannel(InkChannel.TILT))
    }

    @Test
    fun `every channel round-trips through the mask`() {
        val samples = StrokeSamples(
            count = 1,
            x = floatArrayOf(1f),
            y = floatArrayOf(2f),
            pressure = floatArrayOf(0.5f),
            tiltX = floatArrayOf(10f),
            tiltY = floatArrayOf(20f),
            orientation = floatArrayOf(0.1f),
            altitude = floatArrayOf(0.2f),
            size = floatArrayOf(3f),
            timestampMs = longArrayOf(99L),
        )
        assertEquals(InkChannel.ALL, samples.channels)
        assertEquals("PRESSURE|TILT|ORIENTATION|ALTITUDE|SIZE|TIMESTAMP", InkChannel.describe(samples.channels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tilt axes must be supplied together`() {
        StrokeSamples(1, floatArrayOf(0f), floatArrayOf(0f), tiltX = floatArrayOf(1f))
    }

    // --- Array ownership ----------------------------------------------------------------------

    @Test
    fun `the public constructor copies, so a caller can reuse its buffers`() {
        val x = floatArrayOf(1f, 2f, 3f)
        val y = floatArrayOf(4f, 5f, 6f)
        val pressure = floatArrayOf(0.1f, 0.2f, 0.3f)
        val samples = StrokeSamples(3, x, y, pressure = pressure)

        x[0] = 999f
        y[0] = 999f
        pressure[0] = 999f

        assertEquals(1f, samples.x[0], 0f)
        assertEquals(4f, samples.y[0], 0f)
        assertEquals(0.1f, samples.pressure!![0], 0f)
    }

    @Test
    fun `arrays longer than count are trimmed, not kept`() {
        // A capture buffer is almost always longer than the stroke it holds. Trimming here is what
        // lets every consumer trust `array.size == count` instead of carrying `count` alongside.
        val samples = StrokeSamples(2, FloatArray(64) { it.toFloat() }, FloatArray(64) { it * 2f })
        assertEquals(2, samples.count)
        assertEquals(2, samples.x.size)
        assertEquals(2, samples.y.size)
        assertArrayEquals(floatArrayOf(0f, 1f), samples.x, 0f)
        assertArrayEquals(floatArrayOf(0f, 2f), samples.y, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an array shorter than count is rejected rather than zero-padded`() {
        // copyOf would silently pad with zeros, producing a stroke with phantom samples at the
        // origin. Better to fail at construction than to draw a line to the top-left corner.
        StrokeSamples(5, floatArrayOf(1f, 2f), floatArrayOf(1f, 2f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative count is rejected`() {
        StrokeSamples(-1, floatArrayOf(), floatArrayOf())
    }

    // --- Cursor -------------------------------------------------------------------------------

    @Test
    fun `get materializes a point with absent channels as null`() {
        val samples = StrokeSamples(
            count = 2,
            x = floatArrayOf(1f, 2f),
            y = floatArrayOf(3f, 4f),
            pressure = floatArrayOf(0.25f, 0.75f),
        )
        val point = samples[1]
        assertEquals(2f, point.x, 0f)
        assertEquals(4f, point.y, 0f)
        assertEquals(0.75f, point.pressure!!, 0f)
        // Absent means "the device did not report it", not "zero".
        assertNull(point.tiltX)
        assertNull(point.orientation)
        assertNull(point.timestampMs)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun `get rejects an index past count`() {
        StrokeSamples(2, floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))[2]
    }

    // --- Bounds -------------------------------------------------------------------------------

    @Test
    fun `computeBounds spans every sample`() {
        val samples = StrokeSamples(
            count = 4,
            x = floatArrayOf(10f, -5f, 30f, 12f),
            y = floatArrayOf(20f, 40f, 5f, 33f),
        )
        val bounds = samples.computeBounds(RectF())
        assertEquals(-5f, bounds.left, 0f)
        assertEquals(5f, bounds.top, 0f)
        assertEquals(30f, bounds.right, 0f)
        assertEquals(40f, bounds.bottom, 0f)
    }

    @Test
    fun `computeBounds on an empty sample set is empty`() {
        assertTrue(StrokeSamples.EMPTY.computeBounds(RectF()).isEmpty)
    }

    @Test
    fun `computeBounds of a single sample is a zero-area rect at that point`() {
        val bounds = StrokeSamples(1, floatArrayOf(7f), floatArrayOf(9f)).computeBounds(RectF())
        assertEquals(7f, bounds.left, 0f)
        assertEquals(9f, bounds.top, 0f)
        assertEquals(7f, bounds.right, 0f)
        assertEquals(9f, bounds.bottom, 0f)
    }

    // --- Equality -----------------------------------------------------------------------------

    @Test
    fun `equality compares contents, not identity`() {
        val a = StrokeSamples(2, floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), pressure = floatArrayOf(0.1f, 0.2f))
        val b = StrokeSamples(2, floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), pressure = floatArrayOf(0.1f, 0.2f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val differentChannels = StrokeSamples(2, floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        assertNotEquals(a, differentChannels)
    }

    @Test
    fun `toString summarizes rather than dumping`() {
        val samples = StrokeSamples(3, FloatArray(3), FloatArray(3), pressure = FloatArray(3))
        assertEquals("StrokeSamples(count=3, channels=PRESSURE)", samples.toString())
    }

    // --- Builder ------------------------------------------------------------------------------

    @Test
    fun `the builder allocates only the channels it declares`() {
        val builder = StrokeSamples.Builder(InkChannel.PRESSURE or InkChannel.TIMESTAMP)
        builder.add(x = 1f, y = 2f, pressure = 0.5f, tiltX = 99f, timestampMs = 7L)
        val samples = builder.build()

        assertEquals(InkChannel.PRESSURE or InkChannel.TIMESTAMP, samples.channels)
        assertEquals(0.5f, samples.pressure!![0], 0f)
        assertEquals(7L, samples.timestampMs!![0])
        // tiltX was passed but not declared, so it was ignored rather than silently stored.
        assertNull(samples.tiltX)
    }

    @Test
    fun `the builder grows past its initial capacity`() {
        val builder = StrokeSamples.Builder(InkChannel.PRESSURE, initialCapacity = 2)
        repeat(1000) { builder.add(x = it.toFloat(), y = -it.toFloat(), pressure = 1f) }
        val samples = builder.build()

        assertEquals(1000, samples.count)
        assertEquals(1000, samples.x.size)
        assertEquals(999f, samples.x[999], 0f)
        assertEquals(-999f, samples.y[999], 0f)
    }

    @Test
    fun `build hands over trimmed arrays that later adds cannot disturb`() {
        val builder = StrokeSamples.Builder(InkChannel.NONE, initialCapacity = 8)
        builder.add(1f, 1f)
        builder.add(2f, 2f)
        val first = builder.build()

        builder.add(3f, 3f)
        val second = builder.build()

        assertEquals(2, first.count)
        assertEquals(3, second.count)
        assertEquals(1f, first.x[0], 0f)
        assertEquals(2f, first.x[1], 0f)
    }

    @Test
    fun `reset discards samples and keeps the builder usable`() {
        val builder = StrokeSamples.Builder(InkChannel.NONE)
        builder.add(1f, 1f)
        builder.reset()
        assertEquals(0, builder.count)
        builder.add(5f, 5f)
        val samples = builder.build()
        assertEquals(1, samples.count)
        assertEquals(5f, samples.x[0], 0f)
    }

    @Test
    fun `addAll concatenates batches, which is how a multi-callback stroke is assembled`() {
        // A single pen-down to pen-up is not guaranteed to produce one point-list callback.
        val builder = StrokeSamples.Builder(InkChannel.PRESSURE)
        builder.addAll(StrokeSamples(2, floatArrayOf(1f, 2f), floatArrayOf(1f, 2f), pressure = floatArrayOf(0.1f, 0.2f)))
        builder.addAll(StrokeSamples(1, floatArrayOf(3f), floatArrayOf(3f), pressure = floatArrayOf(0.3f)))

        val samples = builder.build()
        assertEquals(3, samples.count)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), samples.x, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), samples.pressure, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addAll rejects a batch whose channels differ from the declared set`() {
        val builder = StrokeSamples.Builder(InkChannel.PRESSURE)
        builder.addAll(StrokeSamples(1, floatArrayOf(1f), floatArrayOf(1f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the builder rejects an unknown channel bit`() {
        StrokeSamples.Builder(1 shl 20)
    }
}
