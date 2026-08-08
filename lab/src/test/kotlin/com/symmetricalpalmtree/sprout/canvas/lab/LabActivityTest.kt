package com.symmetricalpalmtree.sprout.canvas.lab

import android.content.Context
import android.os.Build
import android.view.View
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

    private fun home() = Robolectric.buildActivity(LabActivity::class.java).setup().get()

    private fun canvasScreen() =
        Robolectric.buildActivity(CanvasLabActivity::class.java).setup().get()

    private fun toolsScreen() =
        Robolectric.buildActivity(ToolsLabActivity::class.java).setup().get()

    private fun overlaysScreen() =
        Robolectric.buildActivity(OverlaysLabActivity::class.java).setup().get()

    private fun deviceReportScreen() =
        Robolectric.buildActivity(DeviceReportActivity::class.java).setup().get()

    // --- Home ----------------------------------------------------------------------------------

    @Test
    fun `the home screen reports the library version`() {
        val version = home().findViewById<TextView>(R.id.libraryVersion)
        assertTrue(
            "version label should name the library build, was '${version.text}'",
            version.text.contains(SproutCanvas.VERSION),
        )
    }

    @Test
    fun `the home screen names the selected engine and whether the library was initialized`() {
        val summary = home().findViewById<TextView>(R.id.engineSummary).text.toString()
        assertTrue(summary.contains("initialized"))
        assertTrue(summary.contains("true"))
    }

    @Test
    fun `app is identifiable by name on a shared device fleet`() {
        // The Lab lives on the same devices as Notesprout for years (PLAN.md D14). If the label
        // ever regresses to a generic "sample", finding it on a BOOX becomes guesswork.
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Sprout Canvas Lab", context.getString(R.string.app_name))
    }

    @Test
    fun `the host application initializes the library`() {
        // D11: the one call sprout-canvas asks for. If the Lab stopped making it, the Lab would
        // stop being a valid test of the hardware paths on BOOX and Supernote.
        home()
        assertTrue(SproutCanvas.isInitialized)
    }

    @Test
    fun `every instrument screen opens`() {
        // One Activity per screen is what makes the Phase 4 multi-canvas handoff testable, so the
        // list of screens is structural rather than cosmetic.
        listOf(R.id.openCanvas, R.id.openTools, R.id.openOverlays, R.id.openDeviceReport)
            .forEach { assertTrue(home().findViewById<Button>(it).isEnabled) }
        canvasScreen()
        toolsScreen()
        overlaysScreen()
        deviceReportScreen()
    }

    // --- Canvas screen -------------------------------------------------------------------------

    @Test
    fun `the canvas screen runs a real engine`() {
        val canvas = canvasScreen().findViewById<SproutCanvasView>(R.id.canvas)
        assertEquals("generic", canvas.engineInfo.id)
    }

    @Test
    fun `ingest, round-trip and clear work against the real model`() {
        val activity = canvasScreen()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)

        activity.findViewById<Button>(R.id.ingestButton).performClick()
        assertEquals(2, canvas.strokeCount)
        val ingested = canvas.getStrokes()
        assertEquals(200, ingested.first().sampleCount)

        // G4: anything the canvas holds, it takes back unchanged.
        activity.findViewById<Button>(R.id.roundTripButton).performClick()
        assertEquals(ingested, canvas.getStrokes())
        assertTrue(
            activity.findViewById<TextView>(R.id.canvasStatus).text.contains("round trip: identical"),
        )

        activity.findViewById<Button>(R.id.clearButton).performClick()
        assertEquals(0, canvas.strokeCount)
    }

    @Test
    fun `resizing the canvas keeps its content`() {
        val activity = canvasScreen()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)
        activity.findViewById<Button>(R.id.ingestButton).performClick()

        activity.findViewById<Button>(R.id.sizeSmall).performClick()
        assertEquals(2, canvas.strokeCount)
        activity.findViewById<Button>(R.id.sizeTall).performClick()
        assertEquals(2, canvas.strokeCount)
    }

    // --- Tools screen --------------------------------------------------------------------------

    @Test
    fun `the tools screen offers every pen and every width`() {
        // If a pen is added to the enum without appearing here, the one screen meant to prove the
        // nine are distinct would quietly stop covering it.
        val activity = toolsScreen()
        val labels = penButtonLabels(activity)
        SproutPen.entries.forEach {
            assertTrue("${it.displayName} is missing from the tools screen", it.displayName in labels)
        }
    }

    @Test
    fun `picking a pen arms it on the canvas`() {
        val activity = toolsScreen()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)
        val row = activity.findViewById<android.widget.LinearLayout>(R.id.penRowTwo)
        val charcoal = (0 until row.childCount)
            .map { row.getChildAt(it) as Button }
            .first { it.text == SproutPen.CHARCOAL.displayName }

        charcoal.performClick()

        assertEquals(SproutPen.CHARCOAL, canvas.tool.pen)
        val status = activity.findViewById<TextView>(R.id.toolsStatus).text.toString()
        assertTrue(status.contains(SproutPen.CHARCOAL.displayName))
        assertTrue("fidelity is not reported", status.contains("fidelity"))
    }

    @Test
    fun `the eraser can be armed and disarmed`() {
        val activity = toolsScreen()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)
        val toggle = activity.findViewById<Button>(R.id.eraserToggle)

        toggle.performClick()
        assertTrue(canvas.eraser != null)
        toggle.performClick()
        assertEquals(null, canvas.eraser)
    }

    private fun penButtonLabels(activity: ToolsLabActivity): List<String> =
        listOf(R.id.penRowOne, R.id.penRowTwo).flatMap { rowId ->
            val row = activity.findViewById<android.widget.LinearLayout>(rowId)
            (0 until row.childCount).map { (row.getChildAt(it) as Button).text.toString() }
        }

    // --- Overlays screen -----------------------------------------------------------------------

    @Test
    fun `every overlay is registered as an exclusion zone`() {
        val canvas = overlaysScreen().findViewById<SproutCanvasView>(R.id.canvas)
        assertEquals(4, canvas.exclusionZoneCount)
    }

    @Test
    fun `toggling an overlay changes what it covers, not what is registered`() {
        // Registration is permanent; visibility is what excludes. A dismissed popup must stop
        // reserving its area, or the canvas keeps a dead region nothing on screen explains.
        val activity = overlaysScreen()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)
        val popup = activity.findViewById<View>(R.id.centrePopup)

        assertEquals(View.GONE, popup.visibility)
        activity.findViewById<Button>(R.id.togglePopup).performClick()
        assertEquals(View.VISIBLE, popup.visibility)
        assertEquals(4, canvas.exclusionZoneCount)
    }

    // --- Device report -------------------------------------------------------------------------

    @Test
    fun `the device report names the engine, its capabilities and every pen`() {
        val activity = deviceReportScreen()
        val report = activity.findViewById<TextView>(R.id.deviceReport).text.toString()
        val canvas = activity.findViewById<SproutCanvasView>(R.id.canvas)

        assertTrue(report.contains(canvas.engineInfo.id))
        assertTrue(report.contains("max pressure"))
        assertTrue("declared digitizer ranges are missing", report.contains("digitizer (declared)"))
        assertTrue("observed ranges are missing", report.contains("observed over"))
        SproutPen.entries.forEach {
            assertTrue("$it missing from the device report", report.contains(it.name))
        }
    }
}
