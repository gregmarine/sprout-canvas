package com.symmetricalpalmtree.sprout.canvas.lab

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasListener
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.SproutWidth
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Canvas screen: draw, erase, resize, and prove the canvas is a component.
 *
 * ### What it is here to prove
 *
 * - **G8, dynamically.** The canvas changes size while it holds content — by preset and by dragging
 *   its own corner — and the ink stays where it was drawn. Rotating the device is the same test
 *   without touching the app.
 * - **It is a component, not a screen.** It lives inside a scrolling parent here, at a size the
 *   parent decides, which is how a host app will actually embed it.
 * - **G4, in two taps.** Ingest a known set of strokes, hand them straight back, and see whether
 *   anything moved.
 */
class CanvasLabActivity : AppCompatActivity() {

    private lateinit var canvas: SproutCanvasView
    private lateinit var container: FrameLayout
    private lateinit var status: TextView

    private var lastEvent = "—"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas_lab)

        canvas = findViewById(R.id.canvas)
        container = findViewById(R.id.canvasContainer)
        status = findViewById(R.id.canvasStatus)

        canvas.listener = object : SproutCanvasListener {
            override fun onStrokeCompleted(stroke: InkStroke) {
                note("stroke: ${stroke.sampleCount} samples, ${InkChannel.describe(stroke.samples.channels)}")
            }

            override fun onStrokesRemoved(removed: List<InkStroke>) = note("erased ${removed.size}")

            override fun onCanvasCleared(removed: List<InkStroke>) = note("cleared ${removed.size}")
        }

        findViewById<Button>(R.id.sizeSmall).setOnClickListener { resize(240, 180) }
        findViewById<Button>(R.id.sizeMedium).setOnClickListener { resize(MATCH, 320) }
        findViewById<Button>(R.id.sizeTall).setOnClickListener { resize(MATCH, 640) }
        findViewById<Button>(R.id.clearButton).setOnClickListener { canvas.clear() }
        findViewById<Button>(R.id.ingestButton).setOnClickListener {
            canvas.setStrokes(sampleStrokes())
            note("ingested ${canvas.strokeCount}")
        }
        findViewById<Button>(R.id.roundTripButton).setOnClickListener {
            // G4 as a two-tap check: anything the canvas holds, it takes back unchanged.
            val before = canvas.getStrokes()
            canvas.setStrokes(before)
            note(if (before == canvas.getStrokes()) "round trip: identical" else "round trip: MISMATCH")
        }

        installResizeHandle(findViewById(R.id.resizeHandle))
        note(lastEvent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Again once the window has settled: `onResume` runs before the canvas has a size. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun resize(width: Int, height: Int) {
        val density = resources.displayMetrics.density
        container.layoutParams = container.layoutParams.apply {
            this.width = if (width == MATCH) MATCH else (width * density).roundToInt()
            this.height = (height * density).roundToInt()
        }
        container.requestLayout()
        container.post { refreshStatus() }
    }

    /**
     * Drags the canvas's bottom-right corner.
     *
     * The scrolling parent has to be told to keep its hands off mid-drag, or it treats the vertical
     * part of the gesture as a scroll and the handle only ever resizes horizontally.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installResizeHandle(handle: View) {
        var startX = 0f
        var startY = 0f
        var startWidth = 0
        var startHeight = 0

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startWidth = container.width
                    startHeight = container.height
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    container.layoutParams = container.layoutParams.apply {
                        width = (startWidth + (event.rawX - startX)).roundToInt()
                            .coerceAtLeast(MIN_SIZE_PX)
                        height = (startHeight + (event.rawY - startY)).roundToInt()
                            .coerceAtLeast(MIN_SIZE_PX)
                    }
                    container.requestLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    refreshStatus()
                    true
                }

                else -> false
            }
        }
    }

    private fun note(message: String) {
        lastEvent = message
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = getString(
            R.string.canvas_status,
            canvas.engineInfo.id,
            canvas.width,
            canvas.height,
            canvas.strokeCount,
            lastEvent,
        )
    }

    /**
     * Two synthetic strokes with full channel coverage, built through the same builder an engine
     * uses.
     *
     * They exist so ingest and round-trip can be checked without drawing anything first — including
     * on a device whose stylus is in another room.
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
        val density = resources.displayMetrics.density

        return listOf(
            syntheticStroke("sample-wave", calibration, now) { i ->
                val t = i / 200f
                (20f + t * 260f) * density to (40f + sin(t * 6.0).toFloat() * 25f) * density
            },
            syntheticStroke("sample-line", calibration, now) { i ->
                val t = i / 200f
                (20f + t * 260f) * density to (100f + t * 20f) * density
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
            tool = ToolSpec(SproutPen.FOUNTAIN, SproutWidth.BOLD, Color.BLACK),
            capture = CaptureInfo(
                engineId = canvas.engineInfo.id,
                calibration = calibration,
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs + 200,
            ),
        )
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val MIN_SIZE_PX = 120
    }
}
