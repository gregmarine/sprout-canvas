package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Color
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The numbers a vendor adapter arms hardware with.
 *
 * ### Why this file exists at all
 *
 * On e-ink the firmware draws the stroke under the pen and the library's renderer draws it forever
 * after. An adapter arms the firmware with a width and a colour, and if those are not the numbers
 * the renderer will use, every stroke visibly changes the instant the pen lifts. [PenMetrics] is the
 * one place both read from, and these tests are what keep it the one place.
 */
@RunWith(RobolectricTestRunner::class)
class PenMetricsTest {

    @Test
    fun `every pen has a usable width multiplier`() {
        SproutPen.entries.forEach { pen ->
            assertTrue(
                "$pen has a multiplier of ${PenMetrics.widthMultiplier(pen)}",
                PenMetrics.widthMultiplier(pen) > 0f,
            )
        }
    }

    /**
     * The renderers solve against [PenTuning] and hardware is armed from [PenMetrics]. If those two
     * ever diverge, the disagreement shows up as ink that changes weight at pen-up — on a device,
     * where nothing points at a number.
     */
    @Test
    fun `the published metrics are the renderers' own`() {
        SproutPen.entries.forEach { pen ->
            val tuning = PenTuning.forPen(pen)
            assertEquals(pen.name, tuning.widthMultiplier, PenMetrics.widthMultiplier(pen), 0f)
            assertEquals(pen.name, tuning.defaultAlpha.toLong(), PenMetrics.defaultAlpha(pen).toLong())
        }
    }

    /**
     * ### The trap this pins
     *
     * `OnyxPenTable.widthMultiplier` records **BOOX's own** constants — charcoal ×5 and brush ×2
     * from `NoteConstant`, and 1.0 for everything else because nothing else was measured. It is a
     * documented fact about the vendor, and it is the obvious-looking thing to arm a BOOX overlay
     * with. It is also wrong for that job: the library draws a marker at 1.75× and a highlighter at
     * 4×, so arming the firmware from the Onyx table would put a 1× live marker under a 1.75×
     * committed one.
     *
     * The two tables are *supposed* to disagree. This test states that out loud so that a later
     * session finding the mismatch does not "fix" it by making one match the other.
     */
    @Test
    fun `the Onyx vendor table and the drawn-width metrics deliberately disagree`() {
        // Where BOOX published a multiplier, ours matches it — those two came from the vendor.
        assertEquals(OnyxPenTable.widthMultiplier(SproutPen.CHARCOAL), PenMetrics.widthMultiplier(SproutPen.CHARCOAL), 0f)
        assertEquals(OnyxPenTable.widthMultiplier(SproutPen.BRUSH), PenMetrics.widthMultiplier(SproutPen.BRUSH), 0f)

        // Where BOOX published nothing, the vendor table says 1.0 and the renderers do not.
        assertEquals(1.0f, OnyxPenTable.widthMultiplier(SproutPen.MARKER), 0f)
        assertNotEquals(
            "arming hardware from the Onyx table would under-draw the marker",
            OnyxPenTable.widthMultiplier(SproutPen.MARKER),
            PenMetrics.widthMultiplier(SproutPen.MARKER),
        )
        assertEquals(1.0f, OnyxPenTable.widthMultiplier(SproutPen.HIGHLIGHTER), 0f)
        assertNotEquals(
            "arming hardware from the Onyx table would draw a highlighter at pen width",
            OnyxPenTable.widthMultiplier(SproutPen.HIGHLIGHTER),
            PenMetrics.widthMultiplier(SproutPen.HIGHLIGHTER),
        )
    }

    @Test
    fun `only the highlighter is translucent by default`() {
        SproutPen.entries.forEach { pen ->
            val expected = if (pen == SproutPen.HIGHLIGHTER) 255 else 255
            if (pen != SproutPen.HIGHLIGHTER) {
                assertEquals("$pen must be opaque", expected.toLong(), PenMetrics.defaultAlpha(pen).toLong())
            } else {
                assertTrue("the highlighter must be translucent", PenMetrics.defaultAlpha(pen) < 255)
            }
        }
    }

    @Test
    fun `the highlighter's paint colour picks up its translucency`() {
        val tool = ToolSpec(pen = SproutPen.HIGHLIGHTER, widthDp = 4f, color = Color.YELLOW)

        val painted = PenMetrics.paintColor(tool)

        assertEquals(PenMetrics.defaultAlpha(SproutPen.HIGHLIGHTER).toLong(), Color.alpha(painted).toLong())
        assertEquals(Color.red(Color.YELLOW).toLong(), Color.red(painted).toLong())
        assertEquals(Color.green(Color.YELLOW).toLong(), Color.green(painted).toLong())
        assertEquals(Color.blue(Color.YELLOW).toLong(), Color.blue(painted).toLong())
    }

    /**
     * An app that set its own alpha has said what it wants. The default is for the case where it
     * expressed no opinion at all.
     */
    @Test
    fun `an app's own alpha is left alone`() {
        val tool = ToolSpec(pen = SproutPen.HIGHLIGHTER, widthDp = 4f, color = Color.argb(200, 255, 0, 0))

        assertEquals(200L, Color.alpha(PenMetrics.paintColor(tool)).toLong())
    }

    @Test
    fun `an opaque pen's colour passes through untouched`() {
        val tool = ToolSpec(pen = SproutPen.BALLPOINT, widthDp = 2f, color = Color.RED)

        assertEquals(Color.RED, PenMetrics.paintColor(tool))
    }
}
