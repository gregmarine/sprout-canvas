package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.Rect
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The coordinate arithmetic behind "never capture outside the canvas" and "never write under the
 * host's chrome".
 *
 * Every case here is one sign flip away from a bug that only reproduces on hardware — ink an inch
 * from the pen, or a dead region of canvas nobody can explain. The awkward cases nobody tries on a
 * device get tried here: a zero-size canvas, a zone entirely off it, a zone that swallows it whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CanvasGeometryTest {

    // --- Limit rect ---------------------------------------------------------------------------

    @Test
    fun `a fully visible canvas captures its whole rect`() {
        assertEquals(Rect(0, 0, 800, 600), CanvasGeometry.limitRect(800, 600, null))
    }

    @Test
    fun `capture is clipped to the visible part of a partly scrolled canvas`() {
        val limit = CanvasGeometry.limitRect(800, 600, Rect(0, 200, 800, 600))
        assertEquals(Rect(0, 200, 800, 600), limit)
    }

    @Test
    fun `a canvas scrolled off-screen captures nothing, not everything`() {
        assertTrue(CanvasGeometry.limitRect(800, 600, Rect(0, 700, 800, 900)).isEmpty)
    }

    @Test
    fun `a zero-size canvas captures nothing`() {
        assertTrue(CanvasGeometry.limitRect(0, 0, null).isEmpty)
        assertTrue(CanvasGeometry.limitRect(800, 0, null).isEmpty)
    }

    @Test
    fun `a negative size is treated as empty rather than inverted`() {
        assertTrue(CanvasGeometry.limitRect(-10, -10, null).isEmpty)
    }

    @Test
    fun `a visible frame larger than the canvas does not enlarge it`() {
        assertEquals(Rect(0, 0, 800, 600), CanvasGeometry.limitRect(800, 600, Rect(-100, -100, 5000, 5000)))
    }

    // --- Screen to canvas ---------------------------------------------------------------------

    @Test
    fun `a view's screen rect maps into canvas coordinates by the origin delta`() {
        // The canvas sits 40px down and 16px in from the screen origin; a toolbar at screen (56,140)
        // is therefore at canvas (40,100).
        val zone = CanvasGeometry.mapToCanvas(
            viewLeftOnScreen = 56,
            viewTopOnScreen = 140,
            viewWidth = 200,
            viewHeight = 48,
            canvasLeftOnScreen = 16,
            canvasTopOnScreen = 40,
        )
        assertEquals(Rect(40, 100, 240, 148), zone)
    }

    @Test
    fun `a view above and left of the canvas maps to negative coordinates`() {
        // Not an error: the clip step decides what survives. Getting this offset wrong is the bug
        // whose tell is a stroke visibly jumping on pen-lift.
        val zone = CanvasGeometry.mapToCanvas(0, 0, 100, 100, 200, 300)
        assertEquals(Rect(-200, -300, -100, -200), zone)
    }

    // --- Clipping -----------------------------------------------------------------------------

    @Test
    fun `a zone overlapping the canvas edge is clipped to it`() {
        assertEquals(
            Rect(700, 0, 800, 60),
            CanvasGeometry.clipToCanvas(Rect(700, -40, 900, 60), 800, 600),
        )
    }

    @Test
    fun `a zone entirely beside the canvas excludes nothing`() {
        // A host may well register chrome that sits next to the canvas rather than over it.
        assertNull(CanvasGeometry.clipToCanvas(Rect(900, 0, 1000, 100), 800, 600))
    }

    @Test
    fun `a zone that swallows the canvas clips to the whole canvas`() {
        assertEquals(
            Rect(0, 0, 800, 600),
            CanvasGeometry.clipToCanvas(Rect(-500, -500, 5000, 5000), 800, 600),
        )
    }

    @Test
    fun `an empty zone excludes nothing`() {
        assertNull(CanvasGeometry.clipToCanvas(Rect(), 800, 600))
        assertNull(CanvasGeometry.clipToCanvas(Rect(10, 10, 10, 90), 800, 600))
    }

    // --- Coalescing ---------------------------------------------------------------------------

    @Test
    fun `coalesce drops zones contained in another`() {
        val zones = CanvasGeometry.coalesce(
            listOf(Rect(0, 0, 200, 200), Rect(20, 20, 60, 60), Rect(300, 300, 400, 400)),
        )
        assertEquals(listOf(Rect(0, 0, 200, 200), Rect(300, 300, 400, 400)), zones)
    }

    @Test
    fun `coalesce drops a duplicate`() {
        assertEquals(
            listOf(Rect(0, 0, 100, 100)),
            CanvasGeometry.coalesce(listOf(Rect(0, 0, 100, 100), Rect(0, 0, 100, 100))),
        )
    }

    @Test
    fun `coalesce drops a zone that arrives before the one containing it`() {
        assertEquals(
            listOf(Rect(0, 0, 200, 200)),
            CanvasGeometry.coalesce(listOf(Rect(20, 20, 60, 60), Rect(0, 0, 200, 200))),
        )
    }

    @Test
    fun `coalesce keeps merely overlapping zones separate`() {
        // Merging two overlapping rects into their bounding box would exclude area neither covers,
        // silently killing capture where the host put no chrome at all. Fewer rects is a nicety;
        // a correct region is not.
        val overlapping = listOf(Rect(0, 0, 100, 100), Rect(50, 50, 150, 150))
        assertEquals(overlapping, CanvasGeometry.coalesce(overlapping))
    }

    @Test
    fun `coalesce drops empty zones`() {
        assertEquals(
            listOf(Rect(0, 0, 10, 10)),
            CanvasGeometry.coalesce(listOf(Rect(), Rect(0, 0, 10, 10), Rect(5, 5, 5, 5))),
        )
    }

    @Test
    fun `coalescing nothing yields nothing`() {
        assertEquals(emptyList<Rect>(), CanvasGeometry.coalesce(emptyList()))
    }
}
