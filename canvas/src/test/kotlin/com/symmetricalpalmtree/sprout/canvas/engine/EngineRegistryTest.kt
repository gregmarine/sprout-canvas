package com.symmetricalpalmtree.sprout.canvas.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Engine selection, with fake probes standing in for the vendor devices.
 *
 * Selection has to be right on hardware nobody has to hand, so the rules are tested against
 * factories whose `isSupported` we control outright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class EngineRegistryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        EngineRegistry.resetForTesting()
    }

    @After
    fun tearDown() {
        EngineRegistry.resetForTesting()
    }

    // --- Selection order ----------------------------------------------------------------------

    @Test
    fun `with nothing registered the fallback wins`() {
        assertEquals(EngineIds.NO_OP, EngineRegistry.select(context).info.id)
    }

    @Test
    fun `the highest-priority supported factory wins`() {
        EngineRegistry.register(FakeFactory("low", priority = 1))
        EngineRegistry.register(FakeFactory("high", priority = 99))
        EngineRegistry.register(FakeFactory("middle", priority = 50))
        assertEquals("high", EngineRegistry.select(context).info.id)
    }

    @Test
    fun `an unsupported factory is skipped no matter how high its priority`() {
        // This is what a BOOX adapter on a phone looks like: present in the build, absent in fact.
        EngineRegistry.register(FakeFactory("onyx", priority = 100, supported = false))
        EngineRegistry.register(FakeFactory("generic", priority = 0))
        assertEquals("generic", EngineRegistry.select(context).info.id)
    }

    @Test
    fun `the fallback is used when nothing registered is supported`() {
        EngineRegistry.register(FakeFactory("onyx", priority = 100, supported = false))
        assertEquals(EngineIds.NO_OP, EngineRegistry.select(context).info.id)
    }

    @Test
    fun `registering the same id twice replaces rather than duplicates`() {
        EngineRegistry.register(FakeFactory("onyx", priority = 100))
        EngineRegistry.register(FakeFactory("onyx", priority = 5))
        assertEquals(1, EngineRegistry.registeredFactories().size)
        assertEquals(5, EngineRegistry.registeredFactories().single().info.priority)
    }

    @Test
    fun `unregister removes a factory`() {
        EngineRegistry.register(FakeFactory("onyx", priority = 100))
        assertTrue(EngineRegistry.unregister("onyx"))
        assertTrue(EngineRegistry.registeredFactories().isEmpty())
        assertTrue(!EngineRegistry.unregister("onyx"))
    }

    // --- Explicit preference ------------------------------------------------------------------

    @Test
    fun `an explicit preference beats a higher-priority engine`() {
        // The conformance harness depends on this: judging whether the hardware and software ink
        // paths agree means forcing a BOOX onto the generic engine and drawing the same stroke.
        EngineRegistry.register(FakeFactory("onyx", priority = 100))
        EngineRegistry.register(FakeFactory("generic", priority = 0))
        assertEquals("generic", EngineRegistry.select(context, preferredEngineId = "generic").info.id)
    }

    @Test
    fun `the fallback engine can be named explicitly`() {
        EngineRegistry.register(FakeFactory("onyx", priority = 100))
        assertEquals(EngineIds.NO_OP, EngineRegistry.select(context, EngineIds.NO_OP).info.id)
    }

    @Test
    fun `an unknown preference is ignored, not fatal`() {
        EngineRegistry.register(FakeFactory("generic", priority = 0))
        assertEquals("generic", EngineRegistry.select(context, "nonexistent").info.id)
    }

    @Test
    fun `a preference for an unsupported engine falls through`() {
        EngineRegistry.register(FakeFactory("onyx", priority = 100, supported = false))
        EngineRegistry.register(FakeFactory("generic", priority = 0))
        assertEquals("generic", EngineRegistry.select(context, "onyx").info.id)
    }

    // --- Robustness ---------------------------------------------------------------------------

    @Test
    fun `a factory that throws from isSupported is treated as unsupported`() {
        // A vendor adapter reaching into hidden framework APIs is exactly the code that throws on
        // the one firmware nobody tested. Losing the hardware path is survivable; crashing on a
        // canvas that has not drawn anything is not.
        EngineRegistry.register(ThrowingFactory)
        EngineRegistry.register(FakeFactory("generic", priority = 0))
        assertEquals("generic", EngineRegistry.select(context).info.id)
    }

    @Test
    fun `no adapters are discovered when none are on the classpath`() {
        // The normal case for a phone-only app, and the reason the adapters are separate modules.
        EngineRegistry.discoverAdapters()
        assertTrue(EngineRegistry.registeredFactories().isEmpty())
    }

    @Test
    fun `discovery runs only once`() {
        EngineRegistry.discoverAdapters()
        EngineRegistry.register(FakeFactory("manual", priority = 1))
        EngineRegistry.discoverAdapters()
        assertEquals(listOf("manual"), EngineRegistry.registeredFactories().map { it.info.id })
    }

    // --- Fakes --------------------------------------------------------------------------------

    private class FakeFactory(
        id: String,
        priority: Int,
        private val supported: Boolean = true,
    ) : InkEngineFactory {
        override val info = EngineInfo(id, id, priority)
        override fun isSupported(context: Context): Boolean = supported
        override fun create(host: InkEngineHost): InkEngine = FakeEngine(info)
    }

    private object ThrowingFactory : InkEngineFactory {
        override val info = EngineInfo("throwing", "Throwing", 1000)
        override fun isSupported(context: Context): Boolean = error("hidden API blew up")
        override fun create(host: InkEngineHost): InkEngine = FakeEngine(info)
    }

    private class FakeEngine(override val info: EngineInfo) : InkEngine {
        override val capabilities = CanvasCapabilities(
            engineId = info.id,
            channels = InkChannel.NONE,
            supportsAlpha = true,
            supportedEraserModes = emptySet(),
            penFidelities = CanvasCapabilities.uniformFidelity(PenFidelity.NATIVE),
        )
        override val isPenActive: Boolean = false
        override fun attach(view: View) {}
        override fun detach() {}
        override fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point) {}
        override fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>) {}
        override fun setTool(tool: ToolSpec) {}
        override fun setEraser(eraser: EraserSpec?) {}
        override fun resume() {}
        override fun pause() {}
        override fun releaseForHandoff() {}
        override fun releaseLiveInk() {}
        override fun onCommittedContentChanged(reason: RepaintReason) {}
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun drawLiveInk(canvas: Canvas) {}
    }
}
