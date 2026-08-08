package com.symmetricalpalmtree.sprout.canvas.lab

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasListener
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.SproutWidth
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import kotlin.math.sin

/**
 * Sprout Canvas Lab — the conformance harness (PLAN.md §4.3).
 *
 * The durable regression instrument, not a throwaway demo. It grows one screen per phase: Canvas,
 * Overlays and Device report in Phase 2, Tools in Phase 3, Data and Conformance run in Phase 6.
 *
 * ### What this screen is for in Phase 1
 *
 * Phase 1 delivers the public API contract and nothing that draws, so the Lab's job is to prove two
 * things a compiler cannot:
 *
 *  1. **The API reads well from a real call site.** Everything below is code a host app would
 *     actually write — arming a tool, tracking an overlay, listening for strokes, ingesting
 *     content. If it reads awkwardly here, it will read awkwardly in every app that adopts it, and
 *     Phase 1 is the last cheap moment to fix that.
 *  2. **The wiring reaches the device.** An engine is selected on real hardware, its capabilities
 *     come back, and the round trip in and out of the model survives — all visible on screen,
 *     before any renderer exists to confuse the picture.
 */
class LabActivity : AppCompatActivity() {

    private lateinit var canvas: SproutCanvasView
    private lateinit var deviceReport: TextView

    /** The last thing that happened, appended to the report so a device session has a trail. */
    private var lastEvent: String = "—"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)

        canvas = findViewById(R.id.canvas)
        deviceReport = findViewById(R.id.deviceReport)

        findViewById<TextView>(R.id.libraryVersion).text =
            getString(R.string.library_version, SproutCanvas.VERSION)

        // ---------------------------------------------------------------------------------
        // The intended call sites. This is the whole app-facing API, as a host would use it.
        // ---------------------------------------------------------------------------------

        // Arm a tool. Identical on a BOOX, a Supernote and a phone.
        canvas.tool = ToolSpec(pen = SproutPen.FOUNTAIN, widthDp = 2f, color = Color.BLACK)

        // Never write under the floating toolbar. Registered once; the library tracks it from here.
        canvas.addExclusionZone(findViewById(R.id.floatingToolbar), id = "floating-toolbar")

        // Hear about everything the canvas does.
        canvas.listener = object : SproutCanvasListener {
            override fun onStrokeCompleted(stroke: InkStroke) {
                note("stroke ${stroke.id}: ${stroke.sampleCount} samples")
            }

            override fun onStrokesRemoved(removed: List<InkStroke>) {
                note("removed ${removed.size}")
            }

            override fun onCanvasCleared(removed: List<InkStroke>) {
                note("cleared ${removed.size}")
            }

            override fun onEngineSelected(info: EngineInfo) {
                note("engine: ${info.displayName}")
            }

            override fun onPenActiveChanged(active: Boolean) {
                // What a host app gates its chrome on, so a palm on the glass cannot fire a tap
                // handler that reaches into the live pen session and drops the stroke.
                note("pen active: $active")
            }
        }

        findViewById<Button>(R.id.ingestButton).setOnClickListener {
            canvas.setStrokes(sampleStrokes())
            note("ingested ${canvas.strokeCount}")
        }

        findViewById<Button>(R.id.roundTripButton).setOnClickListener {
            // G4, as a two-tap check: anything the canvas holds, it accepts back unchanged.
            val before = canvas.getStrokes()
            canvas.setStrokes(before)
            val after = canvas.getStrokes()
            note(if (before == after) "round trip: identical" else "round trip: MISMATCH")
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener { canvas.clear() }
    }

    override fun onResume() {
        super.onResume()
        refreshReport()
    }

    private fun note(message: String) {
        lastEvent = message
        refreshReport()
    }

    /**
     * The diagnostic the five-device Onyx pen survey needed and did not have.
     *
     * Selected engine, everything it claims it can do, and what the library measured about the
     * digitizer — on screen, on the device, without a logcat session.
     */
    private fun refreshReport() {
        val available = SproutCanvas.availableEngines(this)
        deviceReport.text = buildString {
            appendLine("initialized:       ${SproutCanvas.isInitialized}")
            appendLine("available engines: ${available.joinToString().ifEmpty { "none registered" }}")
            appendLine("selected:          ${canvas.engineInfo.displayName} (${canvas.engineInfo.id})")
            appendLine()
            append(canvas.capabilities.describe())
            appendLine()
            appendLine("xml tool:          ${canvas.tool.pen} ${canvas.tool.widthDp}dp")
            appendLine("strokes:           ${canvas.strokeCount}")
            appendLine("exclusion zones:   ${canvas.exclusionZoneCount} registered, " +
                "${canvas.activeExclusionZones().size} active")
            appendLine("pen active:        ${canvas.isPenActive}")
            appendLine("last event:        $lastEvent")
        }
    }

    /**
     * Two synthetic strokes with full channel coverage, built through the same [StrokeSamples.Builder]
     * an engine uses.
     *
     * They exist so ingest and round-trip are testable before any engine can capture anything —
     * and so the columnar model gets exercised on a real device with a realistic sample count.
     */
    private fun sampleStrokes(): List<InkStroke> {
        val calibration = DeviceCalibration(
            maxPressure = 4096f,
            pressureIsNormalized = false,
            tiltUnitsKnown = false,
            digitizerWidth = 0,
            digitizerHeight = 0,
            densityDpi = resources.displayMetrics.densityDpi,
        )
        val now = System.currentTimeMillis()

        return listOf(
            syntheticStroke("sample-wave", calibration, now) { i ->
                val t = i / 200f
                (20f + t * 260f) to (60f + sin(t * 6.0).toFloat() * 30f)
            },
            syntheticStroke("sample-line", calibration, now) { i ->
                val t = i / 200f
                (20f + t * 260f) to (140f + t * 20f)
            },
        )
    }

    private inline fun syntheticStroke(
        id: String,
        calibration: DeviceCalibration,
        startedAtMs: Long,
        point: (Int) -> Pair<Float, Float>,
    ): InkStroke {
        val builder = StrokeSamples.Builder(InkChannel.PRESSURE or InkChannel.TIMESTAMP)
        repeat(200) { i ->
            val (x, y) = point(i)
            builder.add(
                x = x,
                y = y,
                pressure = calibration.normalizePressure(1000f + i * 10f),
                timestampMs = startedAtMs + i,
            )
        }
        return InkStroke(
            id = id,
            samples = builder.build(),
            tool = ToolSpec(SproutPen.BALLPOINT, SproutWidth.MEDIUM, Color.BLACK),
            capture = CaptureInfo(
                engineId = canvas.engineInfo.id,
                calibration = calibration,
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs + 200,
            ),
        )
    }
}
