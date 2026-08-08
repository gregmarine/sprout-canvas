package com.symmetricalpalmtree.sprout.canvas.lab

import android.os.Build
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 0 smoke test for the harness app: the Activity inflates, and the library is reachable
 * across the module boundary.
 *
 * That second point is the one worth asserting — `:lab` consuming `:canvas` is the same edge every
 * host app will cross, so a break here is a break in the integration path, not just in the demo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LabActivityTest {

    @Test
    fun `activity launches and reports the library version`() {
        val activity = Robolectric.buildActivity(LabActivity::class.java).setup().get()

        val version = activity.findViewById<TextView>(R.id.libraryVersion)
        assertTrue(
            "version label should name the library build, was '${version.text}'",
            version.text.contains(SproutCanvas.VERSION),
        )
    }

    @Test
    fun `app is identifiable by name on a shared device fleet`() {
        // The Lab lives on the same devices as Notesprout for years (PLAN.md D14). If the label
        // ever regresses to a generic "sample", finding it on a BOOX becomes guesswork.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Sprout Canvas Lab", context.getString(R.string.app_name))
    }
}
