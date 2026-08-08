package com.symmetricalpalmtree.sprout.canvas.onyx

import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.render.PenMetrics
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The width rule that makes the firmware's live stroke and our committed stroke the same weight.
 *
 * ### Why this deserves its own test
 *
 * It is the one number in the adapter that is wrong in three different ways depending on which
 * plausible rule you pick, and each wrong version looks right for some of the pens — which is
 * precisely how it survived two rounds of device testing before being pinned down:
 *
 *  - pre-multiplied → charcoal drawn at ×25 against our ×5;
 *  - nominal → charcoal and brush correct, marker visibly thin;
 *  - the ratio → all nine correct.
 *
 * The arithmetic is asserted here so the next person to touch it does not have to rediscover that
 * on a panel, with a stylus, by eye. `OnyxInkEngine.overlayWidthPx` is private and needs a live SDK,
 * so the *rule* is restated here against the two published tables it is built from — which is what
 * would actually break if either table moved.
 */
@RunWith(RobolectricTestRunner::class)
class OnyxOverlayWidthTest {

    /** The engine's rule, in one line: nominal × (our scaling ÷ BOOX's). */
    private fun overlayScale(pen: SproutPen): Float =
        PenMetrics.widthMultiplier(pen) / OnyxPenTable.widthMultiplier(pen)

    /**
     * The two pens that agreed from the very first stroke on hardware, before anything was tuned.
     * Neither side scales them, so every candidate rule happens to be correct here — which is why
     * they could not have revealed the bug, and why they are the control rather than the evidence.
     */
    @Test
    fun `the pens neither side scales are handed their nominal width`() {
        assertEquals(1f, overlayScale(SproutPen.BALLPOINT), 0f)
        assertEquals(1f, overlayScale(SproutPen.FOUNTAIN), 0f)
    }

    /**
     * Where BOOX publishes the same factor we use, the ratio cancels — the firmware does the
     * scaling and we must not do it again. This is the case that pre-multiplying got wrong by ×5.
     */
    @Test
    fun `pens BOOX scales identically cancel to nominal`() {
        assertEquals(5f, OnyxPenTable.widthMultiplier(SproutPen.CHARCOAL), 0f)
        assertEquals(5f, PenMetrics.widthMultiplier(SproutPen.CHARCOAL), 0f)
        assertEquals(1f, overlayScale(SproutPen.CHARCOAL), 0f)

        assertEquals(2f, OnyxPenTable.widthMultiplier(SproutPen.BRUSH), 0f)
        assertEquals(2f, PenMetrics.widthMultiplier(SproutPen.BRUSH), 0f)
        assertEquals(1f, overlayScale(SproutPen.BRUSH), 0f)
    }

    /**
     * Where the factor is ours alone, the firmware has to be told — and this is the case that
     * passing the nominal width got wrong, leaving the marker's live stroke visibly thin against
     * its committed one.
     */
    @Test
    fun `pens only we scale carry their full factor to the firmware`() {
        assertEquals(1f, OnyxPenTable.widthMultiplier(SproutPen.MARKER), 0f)
        assertEquals(1.75f, overlayScale(SproutPen.MARKER), 1e-6f)
        assertEquals(4f, overlayScale(SproutPen.HIGHLIGHTER), 1e-6f)
        assertEquals(2f, overlayScale(SproutPen.PENCIL), 1e-6f)
    }

    /**
     * A zero or negative scale would arm the firmware with a width it cannot draw, and the SDK
     * validates nothing and reports nothing — the stroke would simply not appear.
     */
    @Test
    fun `every pen produces a usable positive scale`() {
        SproutPen.entries.forEach { pen ->
            val scale = overlayScale(pen)
            assert(scale > 0f && scale.isFinite()) { "$pen scales the overlay width by $scale" }
        }
    }
}
