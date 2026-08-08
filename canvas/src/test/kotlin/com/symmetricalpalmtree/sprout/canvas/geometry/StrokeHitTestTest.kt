package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.PointF
import android.os.Build
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which strokes an erase gesture actually removes.
 *
 * Every case here is one a user would notice immediately: ink that will not erase, ink that
 * disappears when the eraser passes a centimetre away, a fast swipe that leaves gaps behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class StrokeHitTestTest {

    /** A stroke through the given points, as `x, y` pairs. */
    private fun stroke(id: String, vararg coordinates: Float): InkStroke {
        val count = coordinates.size / 2
        val x = FloatArray(count) { coordinates[it * 2] }
        val y = FloatArray(count) { coordinates[it * 2 + 1] }
        return InkStroke(
            id = id,
            samples = StrokeSamples(count, x, y),
            tool = ToolSpec.DEFAULT,
            capture = CaptureInfo(EngineIds.GENERIC, DeviceCalibration.UNKNOWN, 0L, 1L),
        )
    }

    private fun path(vararg coordinates: Float): List<PointF> =
        (0 until coordinates.size / 2).map { PointF(coordinates[it * 2], coordinates[it * 2 + 1]) }

    private fun hits(
        strokes: List<InkStroke>,
        path: List<PointF>,
        radiusPx: Float = 6f,
        outset: Float = 1f,
    ): List<String> = StrokeHitTest
        .strokesTouching(strokes, path, radiusPx) { outset }
        .map { it.id }

    @Test
    fun `an eraser passing over a stroke removes it`() {
        val ink = stroke("a", 0f, 50f, 100f, 50f)
        assertEquals(listOf("a"), hits(listOf(ink), path(50f, 52f)))
    }

    @Test
    fun `an eraser passing well clear of a stroke removes nothing`() {
        val ink = stroke("a", 0f, 50f, 100f, 50f)
        assertTrue(hits(listOf(ink), path(50f, 200f)).isEmpty())
    }

    @Test
    fun `the eraser has to reach the stroke, not merely its bounding box`() {
        // A diagonal stroke's bounding box covers the whole square it spans. Rejecting on the box
        // alone would erase it from a corner the ink never went near.
        val diagonal = stroke("a", 0f, 0f, 200f, 200f)
        assertTrue(hits(listOf(diagonal), path(190f, 10f)).isEmpty())
        assertEquals(listOf("a"), hits(listOf(diagonal), path(100f, 100f)))
    }

    @Test
    fun `a fast swipe erases along its whole path, not only at its samples`() {
        // A quick gesture delivers points far apart. Testing only the points themselves would let a
        // stroke slip between two of them untouched.
        val ink = stroke("a", 100f, 0f, 100f, 200f)
        assertEquals(listOf("a"), hits(listOf(ink), path(0f, 100f, 300f, 100f)))
    }

    @Test
    fun `a long stroke is caught between two distant samples`() {
        // The mirror image: the *stroke* is sparse. A straight line captured as two samples a
        // hundred pixels apart is still a line all the way along.
        val ink = stroke("a", 0f, 0f, 300f, 0f)
        assertEquals(listOf("a"), hits(listOf(ink), path(150f, 3f)))
    }

    @Test
    fun `a wider eraser reaches further`() {
        val ink = stroke("a", 0f, 50f, 100f, 50f)
        assertTrue(hits(listOf(ink), path(50f, 70f), radiusPx = 6f).isEmpty())
        assertEquals(listOf("a"), hits(listOf(ink), path(50f, 70f), radiusPx = 25f))
    }

    @Test
    fun `the ink's own width counts, not just its centreline`() {
        // A 12 dp highlighter has to be erasable by touching the mark the user can see, rather than
        // the invisible line through the middle of it.
        val ink = stroke("a", 0f, 50f, 100f, 50f)
        assertTrue(hits(listOf(ink), path(50f, 68f), radiusPx = 6f, outset = 1f).isEmpty())
        assertEquals(
            listOf("a"),
            hits(listOf(ink), path(50f, 68f), radiusPx = 6f, outset = 20f),
        )
    }

    @Test
    fun `a dot is erasable`() {
        val dot = stroke("a", 40f, 40f)
        assertEquals(listOf("a"), hits(listOf(dot), path(42f, 42f)))
        assertTrue(hits(listOf(dot), path(300f, 300f)).isEmpty())
    }

    @Test
    fun `one gesture can remove several strokes and leaves the rest alone`() {
        val strokes = listOf(
            stroke("a", 0f, 10f, 100f, 10f),
            stroke("b", 0f, 20f, 100f, 20f),
            stroke("c", 0f, 500f, 100f, 500f),
        )
        assertEquals(listOf("a", "b"), hits(strokes, path(50f, 15f), radiusPx = 10f))
    }

    @Test
    fun `an empty canvas and an empty path are both no-ops`() {
        assertTrue(hits(emptyList(), path(10f, 10f)).isEmpty())
        assertTrue(hits(listOf(stroke("a", 0f, 0f, 10f, 10f)), emptyList()).isEmpty())
    }

    @Test
    fun `a stroke with no samples is skipped rather than crashing`() {
        val empty = InkStroke(
            id = "empty",
            samples = StrokeSamples.EMPTY,
            tool = ToolSpec.DEFAULT,
            capture = CaptureInfo(EngineIds.GENERIC, DeviceCalibration.UNKNOWN, 0L, 1L),
        )
        assertTrue(hits(listOf(empty), path(0f, 0f)).isEmpty())
    }

    @Test
    fun `duplicate samples do not divide by zero`() {
        val stalled = stroke("a", 50f, 50f, 50f, 50f, 50f, 50f)
        assertEquals(listOf("a"), hits(listOf(stalled), path(51f, 51f)))
    }
}
