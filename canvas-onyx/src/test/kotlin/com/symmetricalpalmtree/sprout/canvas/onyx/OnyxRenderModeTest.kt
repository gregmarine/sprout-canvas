package com.symmetricalpalmtree.sprout.canvas.onyx

import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.render.RenderContext
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which pens each committed-layer mode takes over.
 *
 * The interesting assertion is the negative one: a pen with no SDK solver must be left with the
 * library's renderer rather than handed a vendor one that will throw at draw time — inside the
 * committed display-list recording, which is the worst place on the canvas to throw.
 */
@RunWith(RobolectricTestRunner::class)
class OnyxRenderModeTest {

    private val calibration = DeviceCalibration(
        maxPressure = 4096f,
        pressureIsNormalized = false,
        tiltUnitsKnown = false,
        digitizerWidth = 12399,
        digitizerHeight = 9299,
        densityDpi = 350,
    )

    private val context = RenderContext(density = 2.1875f)

    @After
    fun reset() {
        OnyxRenderMode.current = OnyxRenderMode.Mode.SOFTWARE
    }

    @Test
    fun `software mode overrides nothing`() {
        val renderers = OnyxRenderMode.renderersFor(
            OnyxRenderMode.Mode.SOFTWARE,
            calibration,
            context,
        )

        assertTrue(renderers.isEmpty())
    }

    @Test
    fun `neo-pen mode overrides exactly the pens the SDK has a solver for`() {
        val renderers = OnyxRenderMode.renderersFor(
            OnyxRenderMode.Mode.NEO_PEN,
            calibration,
            context,
        )

        val expected = SproutPen.entries.filter { OnyxPenTable.neoPenType(it) != null }.toSet()
        assertEquals(expected, renderers.keys)
    }

    /**
     * `DASHED` is the one pen with no SDK equivalent — the firmware dashes live ink, but the SDK
     * ships no dashed software pen. Its committed stroke is ours in either mode.
     */
    @Test
    fun `the dashed pen is never handed to the SDK`() {
        val renderers = OnyxRenderMode.renderersFor(
            OnyxRenderMode.Mode.NEO_PEN,
            calibration,
            context,
        )

        assertTrue(SproutPen.DASHED !in renderers)
    }

    @Test
    fun `software is the default mode`() {
        assertEquals(OnyxRenderMode.Mode.SOFTWARE, OnyxRenderMode.current)
    }
}
