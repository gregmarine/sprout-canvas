package com.symmetricalpalmtree.sprout.canvas

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.symmetricalpalmtree.sprout.canvas.engine.EngineIds
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngine
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineFactory
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost
import com.symmetricalpalmtree.sprout.canvas.engine.NoOpInkEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The library entry point: initialization, discovery, and the promise it makes when skipped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class SproutCanvasTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SproutCanvas.resetForTesting()
        EngineRegistry.resetForTesting()
    }

    @After
    fun tearDown() {
        SproutCanvas.resetForTesting()
        EngineRegistry.resetForTesting()
    }

    @Test
    fun `library reports its version`() {
        assertEquals("0.1.0-SNAPSHOT", SproutCanvas.VERSION)
    }

    @Test
    fun `robolectric provides an Android runtime at the library's minSdk`() {
        // minSdk 29 (Build.VERSION_CODES.Q) is the floor the RenderNode render model requires.
        // Asserting it here means a future minSdk change cannot pass unnoticed.
        assertEquals(Build.VERSION_CODES.Q, Build.VERSION.SDK_INT)
        assertTrue(ApplicationProvider.getApplicationContext<android.content.Context>() != null)
    }

    @Test
    fun `the library starts uninitialized and knows it`() {
        assertFalse(SproutCanvas.isInitialized)
    }

    @Test
    fun `initialize marks the library ready and runs discovery`() {
        SproutCanvas.initialize(application)
        assertTrue(SproutCanvas.isInitialized)
        // No adapters on this classpath, which is the normal case for a phone-only app.
        assertTrue(EngineRegistry.registeredFactories().isEmpty())
    }

    @Test
    fun `initialize is idempotent`() {
        SproutCanvas.initialize(application)
        SproutCanvas.initialize(application)
        assertTrue(SproutCanvas.isInitialized)
    }

    @Test
    fun `initialize turns on strict checks for a debuggable host`() {
        // Robolectric's test application is debuggable, so the library's own guardrails switch on
        // in exactly the builds where a developer wants to hear about a broken rule immediately.
        SproutCanvas.initialize(application)
        assertTrue(SproutCanvas.strictMode)
        assertTrue(SproutCanvas.debugLogging)
    }

    @Test
    fun `a manually registered engine is selectable`() {
        SproutCanvas.registerEngine(ManualFactory)
        assertEquals(listOf("manual"), SproutCanvas.availableEngines(application))
        assertEquals("manual", EngineRegistry.select(application).info.id)
    }

    @Test
    fun `availableEngines omits engines this device does not support`() {
        SproutCanvas.registerEngine(ManualFactory)
        SproutCanvas.registerEngine(UnsupportedFactory)
        assertEquals(listOf("manual"), SproutCanvas.availableEngines(application))
    }

    @Test
    fun `the engine id constants match the shipped engines`() {
        assertEquals("generic", EngineIds.GENERIC)
        assertEquals("onyx", EngineIds.ONYX)
        assertEquals("supernote", EngineIds.SUPERNOTE)
        assertEquals(EngineIds.NO_OP, NoOpInkEngine.INFO.id)
    }

    private object ManualFactory : InkEngineFactory {
        override val info = EngineInfo("manual", "Manual", 10)
        override fun isSupported(context: android.content.Context): Boolean = true
        override fun create(host: InkEngineHost): InkEngine = NoOpInkEngine(host)
    }

    private object UnsupportedFactory : InkEngineFactory {
        override val info = EngineInfo("absent", "Absent", 20)
        override fun isSupported(context: android.content.Context): Boolean = false
        override fun create(host: InkEngineHost): InkEngine = NoOpInkEngine(host)
    }
}
