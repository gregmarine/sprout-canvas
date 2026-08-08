package com.symmetricalpalmtree.sprout.canvas.onyx

import androidx.test.core.app.ApplicationProvider
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild

/**
 * When the BOOX adapter stands down, and whether it says why.
 *
 * ### The failure this is guarding against
 *
 * A host app that forgets `SproutCanvas.initialize` on a BOOX gets a canvas that works perfectly.
 * It draws, it erases, it captures every channel the ordinary Android input path reports — it is
 * simply not using the panel's ink hardware, and there is nothing on screen to suggest it. That is
 * the single most expensive way this library could fail, because the symptom is "writing on the
 * BOOX feels a bit laggy" and the cause is one missing line in `Application.onCreate` (PLAN.md D11).
 *
 * So the contract is: report unsupported, log an error naming the missing call, and never crash.
 */
@RunWith(RobolectricTestRunner::class)
class OnyxInkEngineFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun reset() {
        SproutCanvas.resetForTesting()
        OnyxSdk.resetForTesting()
        ShadowBuild.reset()
    }

    @Test
    fun `the adapter stands down on hardware that is not a BOOX`() {
        ShadowBuild.setManufacturer("Wacom")
        ShadowBuild.setBrand("Wacom")
        SproutCanvas.initialize(context)

        assertFalse(OnyxInkEngineFactory.isSupported(context))
    }

    /**
     * The manufacturer string is a pre-filter and never the answer. `BaseDevice`'s implementation
     * of the entire pen layer is an empty method, so on a non-Onyx device every SDK call succeeds
     * and does nothing — which is why the probe has to go further than a brand check.
     */
    @Test
    fun `a BOOX without initialize falls back rather than failing`() {
        ShadowBuild.setManufacturer("ONYX")

        assertFalse(
            "the adapter must report unsupported, not throw",
            OnyxInkEngineFactory.isSupported(context),
        )
    }

    /**
     * `isSupported` is called on every canvas, on every device, before anything else. A throw here
     * would take down an app that has not drawn a single stroke — so the registry treats a throwing
     * factory as unsupported, and this factory must not make it prove that.
     */
    @Test
    fun `probing never throws, initialized or not`() {
        ShadowBuild.setManufacturer("ONYX")
        OnyxInkEngineFactory.isSupported(context)

        SproutCanvas.initialize(context)
        OnyxInkEngineFactory.isSupported(context)
    }

    @Test
    fun `the factory claims vendor priority so installing it is enough`() {
        assertEquals("onyx", OnyxInkEngineFactory.info.id)
        assertEquals(
            com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo.PRIORITY_VENDOR,
            OnyxInkEngineFactory.info.priority,
        )
    }
}
