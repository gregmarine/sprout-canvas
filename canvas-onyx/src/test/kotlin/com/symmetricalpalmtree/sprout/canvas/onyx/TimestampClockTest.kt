package com.symmetricalpalmtree.sprout.canvas.onyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Putting the SDK's timestamps onto the clock the library documents.
 *
 * The values below are realistic on purpose: a device up for about three hours, and a wall clock in
 * 2026. The gap between the two candidates is the whole reason detection is safe, and writing them
 * out makes that visible rather than asserted.
 */
class TimestampClockTest {

    /** ~3 hours of uptime. */
    private val uptimeNow = 10_800_000L

    /** 2026-08-08, in ms since the epoch. */
    private val wallNow = 1_786_000_000_000L

    @Test
    fun `nothing is decided before a timestamp arrives`() {
        assertNull(TimestampClock().clock)
    }

    /**
     * The NoteAir5C's behaviour: `TouchPoint.timestamp` is `System.currentTimeMillis`, so a point
     * captured two seconds ago must come out two seconds before *uptime* now.
     */
    @Test
    fun `wall-clock timestamps are shifted onto the uptime clock`() {
        val clock = TimestampClock()

        val normalized = clock.normalize(wallNow - 2_000L, uptimeNow, wallNow)

        assertEquals(TimestampClock.Clock.WALL, clock.clock)
        assertEquals(uptimeNow - 2_000L, normalized)
    }

    @Test
    fun `uptime timestamps are passed through untouched`() {
        val clock = TimestampClock()

        val normalized = clock.normalize(uptimeNow - 2_000L, uptimeNow, wallNow)

        assertEquals(TimestampClock.Clock.UPTIME, clock.clock)
        assertEquals(uptimeNow - 2_000L, normalized)
    }

    /**
     * Intervals *within* a stroke are what a host actually computes velocity and duration from, and
     * they must survive the conversion exactly — a shift is an offset, not a scale.
     */
    @Test
    fun `intervals within a batch are preserved exactly`() {
        val clock = TimestampClock()
        val raw = listOf(0L, 8L, 17L, 33L).map { wallNow - 1_000L + it }

        val normalized = raw.map { clock.normalize(it, uptimeNow, wallNow) }

        normalized.zipWithNext().forEachIndexed { i, (a, b) ->
            assertEquals("gap $i", raw[i + 1] - raw[i], b - a)
        }
    }

    /**
     * A batch is converted against one pair of "now" readings, so re-deciding per point cannot
     * introduce jitter into the one channel whose job is measuring time.
     */
    @Test
    fun `the decision is made once and reused`() {
        val clock = TimestampClock()

        clock.normalize(wallNow, uptimeNow, wallNow)
        // A later point that looks like uptime must not flip the decision — on a wall-clock device
        // it is a corrupt reading, not a new fact.
        val normalized = clock.normalize(uptimeNow, uptimeNow, wallNow)

        assertEquals(TimestampClock.Clock.WALL, clock.clock)
        assertEquals(uptimeNow - wallNow + uptimeNow, normalized)
    }

    /**
     * A point with no timestamp is stamped with the moment it was converted. Shifting a zero as if
     * it were a wall-clock reading would place it decades before the device booted.
     */
    @Test
    fun `a missing timestamp becomes now, not a negative`() {
        val clock = TimestampClock()

        assertEquals(uptimeNow, clock.normalize(0L, uptimeNow, wallNow))
        assertEquals(uptimeNow, clock.normalize(-1L, uptimeNow, wallNow))
        assertNull("a missing timestamp is not evidence of a clock", clock.clock)
    }
}
