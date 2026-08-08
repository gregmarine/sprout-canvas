package com.symmetricalpalmtree.sprout.canvas.onyx

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exclusion list handed to `TouchHelper.setLimitRect`.
 *
 * One rule, and it is the sort that gets tidied away by someone who does not know why it is there:
 * "no exclusions" must be sent as a list containing an off-screen rectangle, not as an empty list.
 * The SDK reads an empty list as *no change* and keeps whatever zone was previously active — so
 * dismissing the last toolbar over a canvas leaves a strip of panel that silently refuses ink.
 */
@RunWith(RobolectricTestRunner::class)
class OnyxLimitRectsTest {

    @Test
    fun `no zones produces one off-screen rect, never an empty list`() {
        val rects = OnyxLimitRects.excludeRects(emptyList())

        assertEquals(1, rects.size)
        assertEquals(OnyxLimitRects.NOTHING_EXCLUDED, rects[0])
        assertTrue(
            "the dummy rect must be somewhere no stylus can reach",
            rects[0].right <= 0 && rects[0].bottom <= 0,
        )
    }

    @Test
    fun `zones are passed through in order`() {
        val zones = listOf(Rect(0, 0, 100, 50), Rect(0, 200, 100, 260))

        val rects = OnyxLimitRects.excludeRects(zones)

        assertEquals(zones, rects)
    }

    /**
     * The SDK holds the list it is given, and the caller's rects are recomputed on every layout
     * pass. Handing over the same objects would let a re-layout mutate the zones already armed.
     */
    @Test
    fun `zones are copied, not aliased`() {
        val zone = Rect(0, 0, 100, 50)

        val rects = OnyxLimitRects.excludeRects(listOf(zone))
        zone.set(0, 0, 1, 1)

        assertNotSame(zone, rects[0])
        assertEquals(Rect(0, 0, 100, 50), rects[0])
    }

    /**
     * The dummy is handed to the SDK, which is free to do what it likes with it. Returning the
     * shared constant itself would let one call corrupt every later one.
     */
    @Test
    fun `the off-screen rect is a fresh copy each time`() {
        val first = OnyxLimitRects.excludeRects(emptyList())[0]
        first.set(0, 0, 9999, 9999)

        assertEquals(OnyxLimitRects.NOTHING_EXCLUDED, OnyxLimitRects.excludeRects(emptyList())[0])
    }
}
