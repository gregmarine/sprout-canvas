package com.symmetricalpalmtree.sprout.canvas.tools

import com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of the standardized ↔ vendor mapping tables.
 *
 * ### Why this suite matters more than it looks
 *
 * Mapping tables rot silently. Adding a pen without mapping it on every platform produces code that
 * still compiles and a pen that simply does nothing on one device — and vendor SDKs make that
 * failure invisible, because `setStrokeStyle` swallows an unsupported value with no exception, no
 * return value and no log. There is no `isStrokeStyleSupported()` to ask afterwards.
 *
 * So the enum is walked here rather than a fixed list being checked: a new [SproutPen] joins these
 * tests automatically, and fails them until somebody decides what it does on every platform.
 */
class PenTableTest {

    @Test
    fun `there are exactly nine pens, in the documented order`() {
        // The order is load-bearing: attrs.xml maps app:sproutPen enum values to ordinals, so a
        // reorder would silently re-point every XML-configured canvas at a different pen.
        assertEquals(
            listOf(
                "BALLPOINT", "FOUNTAIN", "BRUSH", "MARKER", "HIGHLIGHTER",
                "PENCIL", "CHARCOAL", "CALLIGRAPHY", "DASHED",
            ),
            SproutPen.entries.map { it.name },
        )
    }

    // --- Onyx ---------------------------------------------------------------------------------

    @Test
    fun `every pen has an Onyx overlay style`() {
        SproutPen.entries.forEach { pen ->
            val style = OnyxPenTable.overlayStyle(pen)
            assertTrue(
                "$pen maps to overlay style $style, outside the nine the firmware implements",
                style in 0..7,
            )
        }
    }

    @Test
    fun `every pen has an Onyx committed renderer, or is documented as software-drawn`() {
        SproutPen.entries.forEach { pen ->
            val neoPen = OnyxPenTable.neoPenType(pen)
            if (pen == SproutPen.DASHED) {
                assertNull("DASHED is dashed by our own renderer on commit", neoPen)
            } else {
                assertNotNull("$pen has no committed renderer", neoPen)
            }
        }
    }

    @Test
    fun `every pen has an Onyx fidelity and a width multiplier`() {
        SproutPen.entries.forEach { pen ->
            assertNotNull(OnyxPenTable.fidelity(pen))
            assertTrue(
                "$pen has a non-positive width multiplier",
                OnyxPenTable.widthMultiplier(pen) > 0f,
            )
        }
    }

    @Test
    fun `Onyx pens map to the styles the five-device survey confirmed`() {
        assertEquals(OnyxPenTable.STROKE_STYLE_PENCIL, OnyxPenTable.overlayStyle(SproutPen.BALLPOINT))
        assertEquals(OnyxPenTable.STROKE_STYLE_FOUNTAIN, OnyxPenTable.overlayStyle(SproutPen.FOUNTAIN))
        assertEquals(OnyxPenTable.STROKE_STYLE_NEO_BRUSH, OnyxPenTable.overlayStyle(SproutPen.BRUSH))
        assertEquals(OnyxPenTable.STROKE_STYLE_MARKER, OnyxPenTable.overlayStyle(SproutPen.MARKER))
        assertEquals(OnyxPenTable.STROKE_STYLE_CHARCOAL, OnyxPenTable.overlayStyle(SproutPen.PENCIL))
        assertEquals(OnyxPenTable.STROKE_STYLE_CHARCOAL_V2, OnyxPenTable.overlayStyle(SproutPen.CHARCOAL))
        assertEquals(OnyxPenTable.STROKE_STYLE_SQUARE_PEN, OnyxPenTable.overlayStyle(SproutPen.CALLIGRAPHY))
        assertEquals(OnyxPenTable.STROKE_STYLE_DASH, OnyxPenTable.overlayStyle(SproutPen.DASHED))
    }

    @Test
    fun `the ballpoint maps to Onyx's misleadingly named PENCIL style`() {
        // STROKE_STYLE_PENCIL is a plain even line; BOOX's own UI labels it "Pen". The grainy
        // pencil a user would point at is internally charcoal. This is the naming trap the
        // standardized vocabulary exists to close, so it gets its own test.
        assertEquals(0, OnyxPenTable.overlayStyle(SproutPen.BALLPOINT))
        assertEquals(4, OnyxPenTable.overlayStyle(SproutPen.PENCIL))
    }

    /**
     * The highlighter's Onyx fidelity is a **measurement**, taken on a NoteAir5C in Phase 4.
     *
     * The two pens really are one firmware style separated by width and alpha. The device added two
     * things: the overlay paints nothing at all for a translucent colour, so the adapter forces the
     * live preview opaque — and that forced-opaque band still reads as a true highlight on a colour
     * panel, with ink visible underneath throughout and no change on pen-up. Both paths look right,
     * which is what `EMULATED` means.
     *
     * Moving this to `APPROXIMATE` is a claim that the live and committed strokes visibly disagree,
     * and needs a panel to say so — plausibly a mono one, where the same band renders flat grey.
     */
    @Test
    fun `marker and highlighter share the Onyx style and are separated by width and alpha`() {
        assertEquals(
            OnyxPenTable.overlayStyle(SproutPen.MARKER),
            OnyxPenTable.overlayStyle(SproutPen.HIGHLIGHTER),
        )
        assertEquals(PenFidelity.NATIVE, OnyxPenTable.fidelity(SproutPen.MARKER))
        assertEquals(PenFidelity.EMULATED, OnyxPenTable.fidelity(SproutPen.HIGHLIGHTER))
    }

    @Test
    fun `texture pens carry the width multipliers that let their grain exist`() {
        // A charcoal at nominal width renders solid and grainless, with no error of any kind.
        assertEquals(5.0f, OnyxPenTable.widthMultiplier(SproutPen.CHARCOAL), 0f)
        assertEquals(2.0f, OnyxPenTable.widthMultiplier(SproutPen.BRUSH), 0f)
        assertEquals(1.0f, OnyxPenTable.widthMultiplier(SproutPen.BALLPOINT), 0f)
    }

    // --- Supernote ----------------------------------------------------------------------------

    @Test
    fun `every pen has a Supernote code, or is documented as software-drawn`() {
        SproutPen.entries.forEach { pen ->
            val code = SupernotePenTable.penCode(pen)
            if (pen == SproutPen.DASHED) {
                assertNull("DASHED has no firmware equivalent on Supernote", code)
            } else {
                assertNotNull("$pen has no Supernote pen code", code)
                assertTrue(
                    "$pen maps to firmware code $code, outside the confirmed range",
                    code!! in listOf(
                        SupernotePenTable.NEEDLE,
                        SupernotePenTable.MARK,
                        SupernotePenTable.CALLIGRAPHY,
                        SupernotePenTable.INK,
                    ),
                )
            }
        }
    }

    @Test
    fun `every pen has a Supernote fidelity`() {
        SproutPen.entries.forEach { assertNotNull(SupernotePenTable.fidelity(it)) }
    }

    @Test
    fun `marker and highlighter are genuinely different firmware pens on Supernote`() {
        // This is the whole case for splitting them (PLAN.md D12). If these two ever collapse to
        // the same code, the split has lost its justification and somebody should be told.
        assertEquals(SupernotePenTable.NEEDLE, SupernotePenTable.penCode(SproutPen.MARKER))
        assertEquals(SupernotePenTable.MARK, SupernotePenTable.penCode(SproutPen.HIGHLIGHTER))
        assertEquals(PenFidelity.NATIVE, SupernotePenTable.fidelity(SproutPen.HIGHLIGHTER))
    }

    @Test
    fun `texture pens are honest about having no grain on Supernote firmware`() {
        assertEquals(PenFidelity.APPROXIMATE, SupernotePenTable.fidelity(SproutPen.PENCIL))
        assertEquals(PenFidelity.APPROXIMATE, SupernotePenTable.fidelity(SproutPen.CHARCOAL))
    }

    // --- Generic ------------------------------------------------------------------------------

    @Test
    fun `the software engine reproduces every pen`() {
        // Nothing to be an approximation of: the software renderer is the reference the vendor
        // paths are tuned against, so a tool picker on an ordinary tablet annotates nothing.
        assertEquals(SproutPen.entries.size, GenericPenTable.fidelities().size)
        SproutPen.entries.forEach {
            assertEquals("$it is below native on the software engine", PenFidelity.NATIVE, GenericPenTable.fidelity(it))
        }
    }

    // --- Cross-platform -------------------------------------------------------------------------

    @Test
    fun `every pen renders on every platform`() {
        // The G5 promise, asserted as a table: no pen may be missing anywhere.
        SproutPen.entries.forEach { pen ->
            assertNotNull("$pen: no Onyx live style", OnyxPenTable.overlayStyle(pen))
            assertNotNull("$pen: no Onyx fidelity", OnyxPenTable.fidelity(pen))
            assertNotNull("$pen: no Supernote fidelity", SupernotePenTable.fidelity(pen))
            assertNotNull("$pen: no generic fidelity", GenericPenTable.fidelity(pen))
        }
    }
}
