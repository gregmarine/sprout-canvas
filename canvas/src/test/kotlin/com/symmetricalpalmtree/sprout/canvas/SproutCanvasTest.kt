package com.symmetricalpalmtree.sprout.canvas

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 0 smoke test — proves the JVM test tier is wired end to end: JUnit 4 runs, Robolectric
 * boots an Android runtime, and library code is reachable from it.
 *
 * The substantive model, geometry and mapping-table tests arrive in Phase 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class SproutCanvasTest {

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
}
