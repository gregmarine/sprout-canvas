package com.symmetricalpalmtree.sprout.canvas.lab

import android.os.Build
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The harness app, which doubles as the integration test for consuming `:canvas` from outside.
 *
 * `:lab` crossing the module boundary is the same edge every host app will cross, so a break here
 * is a break in the integration path — not just in a demo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class LabActivityTest {

    private fun launch() = Robolectric.buildActivity(LabActivity::class.java).setup().get()

    @Test
    fun `activity launches and reports the library version`() {
        val version = launch().findViewById<TextView>(R.id.libraryVersion)
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

    @Test
    fun `the host application initializes the library`() {
        // D11: the one call sprout-canvas asks for. If the Lab stopped making it, the Lab would
        // stop being a valid test of the hardware paths on BOOX and Supernote.
        launch()
        assertTrue(SproutCanvas.isInitialized)
    }

    @Test
    fun `a canvas inflated from XML honours its attributes`() {
        // The attrs.xml surface, end to end: layout says fountain at 2dp, the canvas agrees.
        val canvas = launch().findViewById<SproutCanvasView>(R.id.canvas)
        assertEquals(SproutPen.FOUNTAIN, canvas.tool.pen)
        assertEquals(2f, canvas.tool.widthDp, 0f)
    }

    @Test
    fun `the device report names the selected engine`() {
        val activity = launch()
        val report = activity.findViewById<TextView>(R.id.deviceReport).text.toString()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)
        assertTrue(report.contains(canvas.engineInfo.id))
        assertTrue(report.contains("max pressure"))
        SproutPen.entries.forEach {
            assertTrue("$it missing from the device report", report.contains(it.name))
        }
    }

    @Test
    fun `ingest, round-trip and clear work against the real model`() {
        val activity = launch()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)

        activity.findViewById<Button>(R.id.ingestButton).performClick()
        assertEquals(2, canvas.strokeCount)
        val ingested = canvas.getStrokes()
        assertEquals(200, ingested.first().sampleCount)

        // G4: anything the canvas holds, it takes back unchanged.
        activity.findViewById<Button>(R.id.roundTripButton).performClick()
        assertEquals(ingested, canvas.getStrokes())
        assertTrue(
            activity.findViewById<TextView>(R.id.deviceReport).text.contains("round trip: identical"),
        )

        activity.findViewById<Button>(R.id.clearButton).performClick()
        assertEquals(0, canvas.strokeCount)
    }

    @Test
    fun `the floating toolbar is registered as an exclusion zone`() {
        val canvas = launch().findViewById<SproutCanvasView>(R.id.canvas)
        assertEquals(1, canvas.exclusionZoneCount)
    }
}
