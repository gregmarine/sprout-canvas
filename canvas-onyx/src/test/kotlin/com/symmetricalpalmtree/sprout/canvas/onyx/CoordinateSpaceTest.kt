package com.symmetricalpalmtree.sprout.canvas.onyx

import android.graphics.Point
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The probe that decides whether the BOOX raw-input pipeline speaks screen or view coordinates.
 *
 * Getting this wrong on a canvas that is not at the screen origin means every point is rejected by
 * the capture bounds and the canvas takes no ink at all — with nothing on screen to say why. The
 * probe is what makes that self-correcting, so its decision rule is worth pinning precisely,
 * including the cases where it must decline to decide.
 */
@RunWith(RobolectricTestRunner::class)
class CoordinateSpaceTest {

    private val bounds = Rect(0, 0, 400, 300)

    /**
     * View coordinates are the default because a NoteAir5C said so, not because they were expected
     * — see the class KDoc. Arming the other way produced a session that reported itself correctly
     * armed and captured nothing anywhere.
     */
    @Test
    fun `view coordinates are the measured default`() {
        val space = CoordinateSpace()

        assertEquals(CoordinateSpace.Space.VIEW, space.space)
        assertFalse(space.confirmed)
    }

    /**
     * The canvas is at the origin, so both readings put the point in the same place. There is
     * nothing to learn and nothing that could be got wrong.
     */
    @Test
    fun `a canvas at the screen origin settles nothing and needs to settle nothing`() {
        val space = CoordinateSpace()

        space.observe(rawX = 10f, rawY = 10f, canvasBounds = bounds, screenOffset = Point(0, 0))

        assertFalse(space.confirmed)
        assertEquals(CoordinateSpace.Space.VIEW, space.space)
    }

    @Test
    fun `a point that only makes sense as a view coordinate switches to view`() {
        val space = CoordinateSpace()
        val offset = Point(0, 500)

        // Canvas-local (10, 10). As a screen coordinate this would be (10, -490) — above the canvas.
        space.observe(rawX = 10f, rawY = 10f, canvasBounds = bounds, screenOffset = offset)

        assertTrue(space.confirmed)
        assertEquals(CoordinateSpace.Space.VIEW, space.space)
        assertEquals(10f, space.toCanvasY(10f, offset), 0f)
    }

    @Test
    fun `a point that only makes sense as a screen coordinate confirms screen`() {
        val space = CoordinateSpace()
        val offset = Point(0, 500)

        // Canvas-local (10, 250) reported as screen (10, 750). As a view coordinate, 750 is well
        // below the canvas's 300 px height.
        space.observe(rawX = 10f, rawY = 750f, canvasBounds = bounds, screenOffset = offset)

        assertTrue(space.confirmed)
        assertEquals(CoordinateSpace.Space.SCREEN, space.space)
        assertEquals(250f, space.toCanvasY(750f, offset), 0f)
    }

    /**
     * A small offset leaves the two rectangles overlapping, and a point in the overlap is consistent
     * with both readings. Deciding from it would be a coin toss recorded as a fact.
     */
    @Test
    fun `a point consistent with both readings decides nothing`() {
        val space = CoordinateSpace()
        val offset = Point(0, 20)

        space.observe(rawX = 100f, rawY = 100f, canvasBounds = bounds, screenOffset = offset)

        assertFalse(space.confirmed)
    }

    @Test
    fun `a point outside the canvas under either reading decides nothing`() {
        val space = CoordinateSpace()

        space.observe(rawX = 5000f, rawY = 5000f, canvasBounds = bounds, screenOffset = Point(0, 500))

        assertFalse(space.confirmed)
    }

    /**
     * A digitizer does not change which space it reports in halfway through a session, so once a
     * stroke has settled the question a later ambiguous point must not unsettle it.
     */
    @Test
    fun `a confirmed answer is never revisited`() {
        val space = CoordinateSpace()
        val offset = Point(0, 500)

        space.observe(rawX = 10f, rawY = 10f, canvasBounds = bounds, screenOffset = offset)
        assertEquals(CoordinateSpace.Space.VIEW, space.space)

        // Would confirm SCREEN on a fresh probe.
        space.observe(rawX = 10f, rawY = 750f, canvasBounds = bounds, screenOffset = offset)

        assertEquals(CoordinateSpace.Space.VIEW, space.space)
    }

    @Test
    fun `an unlaid-out canvas offers no evidence`() {
        val space = CoordinateSpace()

        space.observe(rawX = 10f, rawY = 10f, canvasBounds = Rect(), screenOffset = Point(0, 500))

        assertFalse(space.confirmed)
    }

    @Test
    fun `a rect is armed unchanged under the view reading`() {
        val space = CoordinateSpace()
        val out = Rect()

        space.fromCanvasRect(Rect(10, 20, 110, 120), Point(30, 500), out)

        assertEquals(Rect(10, 20, 110, 120), out)
    }

    @Test
    fun `a rect is offset to the panel under the screen reading`() {
        val space = CoordinateSpace()
        val offset = Point(0, 500)
        // Only a point that makes sense as a screen coordinate can select that reading.
        space.observe(rawX = 10f, rawY = 750f, canvasBounds = bounds, screenOffset = offset)
        assertEquals(CoordinateSpace.Space.SCREEN, space.space)
        val out = Rect()

        space.fromCanvasRect(Rect(10, 20, 110, 120), offset, out)

        assertEquals(Rect(10, 520, 110, 620), out)
    }

    /**
     * The armed rect and the incoming points have to be in the *same* space, or the pipeline
     * captures one rectangle and reports positions relative to another. Round-tripping a canvas
     * corner through both directions is the cheapest way to state that.
     */
    @Test
    fun `arming and reading round-trip in both spaces`() {
        val offset = Point(37, 613)
        listOf(
            CoordinateSpace(),
            CoordinateSpace().apply { observe(10f, 300f + offset.y, bounds, offset) },
        ).forEach { space ->
            val armed = space.fromCanvasRect(bounds, offset, Rect())

            assertEquals(
                "left edge, ${space.space}",
                bounds.left.toFloat(),
                space.toCanvasX(armed.left.toFloat(), offset),
                0f,
            )
            assertEquals(
                "top edge, ${space.space}",
                bounds.top.toFloat(),
                space.toCanvasY(armed.top.toFloat(), offset),
                0f,
            )
        }
    }
}
