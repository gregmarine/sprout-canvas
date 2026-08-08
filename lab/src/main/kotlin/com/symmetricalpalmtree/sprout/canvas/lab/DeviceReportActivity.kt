package com.symmetricalpalmtree.sprout.canvas.lab

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasListener
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke
import com.symmetricalpalmtree.sprout.canvas.onyx.OnyxDiagnostics
import com.symmetricalpalmtree.sprout.canvas.onyx.OnyxRenderMode
import kotlin.math.abs

/**
 * The Device report: what the library chose, what it measured, and what the hardware actually sent.
 *
 * ### The diagnostic the Onyx survey needed and did not have
 *
 * Every value here exists because assuming it cost real debugging time somewhere. `maxPressure` is
 * 4095 on some BOOX models and 4096 on others, and it is the divisor for every pressure
 * normalization. Tilt has no common scale at all — one model reported roughly a hundred times what
 * four others did, with no vendor API anywhere to normalize against (PLAN.md §5.6).
 *
 * ### Why *observed* ranges as well as declared ones
 *
 * A digitizer's declared motion range is what the driver claims. The observed range is what it
 * actually sent while somebody wrote on it, and the two disagree often enough to be worth showing
 * side by side. A pressure channel that declares `0..4096` and only ever reports `0` is a channel
 * that is present, useless, and otherwise completely invisible.
 */
class DeviceReportActivity : InkLabActivity() {

    private lateinit var canvas: SproutCanvasView

    override val inkCanvas: SproutCanvasView?
        get() = if (::canvas.isInitialized) canvas else null
    private lateinit var report: TextView

    private val observed = ObservedRanges()
    private var strokesSeen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_report)

        canvas = findViewById(R.id.canvas)
        report = findViewById(R.id.deviceReport)

        canvas.listener = object : SproutCanvasListener {
            override fun onStrokeCompleted(stroke: InkStroke) {
                strokesSeen++
                observed.record(stroke)
                refresh()
            }
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            canvas.clear()
            observed.reset()
            strokesSeen = 0
            refresh()
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }

        // Switching the committed-layer renderer recreates the Activity rather than reaching into
        // the live canvas. A canvas reads the mode when its engine attaches, and adding a second
        // way to change it afterwards would be a second thing to keep correct for no benefit — the
        // comparison this button exists for is "draw the same handwriting through each", not
        // "change it mid-stroke".
        findViewById<Button>(R.id.renderModeButton).setOnClickListener {
            LabSettings.setRenderMode(
                this,
                when (OnyxRenderMode.current) {
                    OnyxRenderMode.Mode.SOFTWARE -> OnyxRenderMode.Mode.NEO_PEN
                    OnyxRenderMode.Mode.NEO_PEN -> OnyxRenderMode.Mode.SOFTWARE
                },
            )
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Refresh again once the window has settled.
     *
     * `onResume` runs before the first layout, so anything derived from the canvas's geometry is
     * not knowable yet. Reporting only from `onResume` is how Phase 1's report came to claim zero
     * active exclusion zones with a toolbar plainly sitting on the canvas — a bug in the diagnostic
     * that reads exactly like a bug in the thing being diagnosed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun refresh() {
        report.text = buildString {
            appendLine("── library ─────────────────────────")
            appendLine("version:           ${SproutCanvas.VERSION}")
            appendLine("initialized:       ${SproutCanvas.isInitialized}")
            appendLine("available engines: ${
                SproutCanvas.availableEngines(this@DeviceReportActivity)
                    .joinToString().ifEmpty { "none registered" }
            }")
            appendLine()
            appendLine("── selected engine ─────────────────")
            appendLine("name:              ${canvas.engineInfo.displayName}")
            append(canvas.capabilities.describe())
            appendLine()
            appendLine("── onyx adapter ────────────────────")
            append(OnyxDiagnostics.describe())
            appendLine("canvas on screen:  ${canvasScreenOffset()}")
            appendLine()
            appendLine("── digitizer (declared) ────────────")
            append(declaredStylusRanges())
            appendLine()
            appendLine("── observed over $strokesSeen stroke(s) ─────")
            append(observed.describe())
        }
    }

    /**
     * Where the canvas sits on the panel.
     *
     * Not decoration. The hardware ink pipeline sits below the view system, and whether it speaks
     * screen or view coordinates only *matters* when those two disagree — which is exactly when
     * this is not `0, 0`. A coordinate-space line in the section above that says "assumed" alongside
     * an offset of `0, 0` means the question could not arise; the same line beside a real offset
     * means it was answered.
     */
    private fun canvasScreenOffset(): String {
        val location = IntArray(2)
        canvas.getLocationOnScreen(location)
        return "${location[0]}, ${location[1]} (${canvas.width} × ${canvas.height} px)"
    }

    /**
     * What the connected stylus devices claim, straight from `InputDevice`.
     *
     * Read here rather than through the library on purpose: this is the raw platform answer the
     * engine's own probe is derived from, and having both on one screen is what turns "pressure
     * looks wrong" into "the driver reports 0..1 and the engine agrees".
     */
    private fun declaredStylusRanges(): String {
        val devices = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { it.supportsSource(InputDevice.SOURCE_STYLUS) }
            .toList()

        if (devices.isEmpty()) return "no stylus device connected\n"

        return buildString {
            devices.forEach { device ->
                appendLine("device:            ${device.name}")
                AXES.forEach { (label, axis) ->
                    val range = device.getMotionRange(axis)
                    appendLine(
                        "  ${label.padEnd(LABEL_WIDTH)}${
                            if (range == null) "absent"
                            else "${format(range.min)} … ${format(range.max)}"
                        }",
                    )
                }
            }
        }
    }

    private companion object {
        val AXES = listOf(
            "pressure" to MotionEvent.AXIS_PRESSURE,
            "tilt (altitude)" to MotionEvent.AXIS_TILT,
            "orientation" to MotionEvent.AXIS_ORIENTATION,
            "size" to MotionEvent.AXIS_SIZE,
            "x" to MotionEvent.AXIS_X,
            "y" to MotionEvent.AXIS_Y,
        )

        fun format(value: Float): String = "%.4g".format(value)

        /** Wide enough for the longest axis label, so the columns line up. */
        const val LABEL_WIDTH = 17

        /**
         * How stale the last sample may be and still identify its clock.
         *
         * Generous on purpose: the report is refreshed by hand, possibly a while after the stroke
         * that filled it in. The two candidate clocks differ by the device's uptime — minutes at
         * best and usually far more — so a wide window costs nothing, while a narrow one would
         * report "neither clock" for a tester who took their time.
         */
        const val CLOCK_TOLERANCE_MS = 60_000L
    }

    /** Min and max of every channel actually seen in captured ink. */
    private class ObservedRanges {

        private val minimum = HashMap<String, Float>()
        private val maximum = HashMap<String, Float>()
        private var channels = InkChannel.NONE
        private var samples = 0L

        /** The newest raw timestamp seen, which is what identifies the clock it is on. */
        private var lastTimestampMs: Long? = null

        fun reset() {
            minimum.clear()
            maximum.clear()
            channels = InkChannel.NONE
            samples = 0L
            lastTimestampMs = null
        }

        fun record(stroke: InkStroke) {
            val s = stroke.samples
            channels = channels or s.channels
            samples += s.count
            track("pressure", s.pressure, s.count)
            track("tiltX", s.tiltX, s.count)
            track("tiltY", s.tiltY, s.count)
            track("orientation", s.orientation, s.count)
            track("altitude", s.altitude, s.count)
            track("size", s.size, s.count)
            s.timestampMs?.let { if (s.count > 0) lastTimestampMs = it[s.count - 1] }
        }

        private fun track(name: String, values: FloatArray?, count: Int) {
            if (values == null) return
            for (i in 0 until count) {
                val value = values[i]
                minimum[name] = minOf(minimum[name] ?: value, value)
                maximum[name] = maxOf(maximum[name] ?: value, value)
            }
        }

        fun describe(): String = buildString {
            appendLine("channels seen:     ${InkChannel.describe(channels)}")
            appendLine("samples:           $samples")
            appendLine("timestamp clock:   ${timestampClock()}")
            if (minimum.isEmpty()) {
                appendLine("draw on the canvas below to fill this in")
                return@buildString
            }
            minimum.keys.sorted().forEach { name ->
                appendLine(
                    "  ${name.padEnd(LABEL_WIDTH)}${format(minimum.getValue(name))} … " +
                        format(maximum.getValue(name)),
                )
            }
        }

        /**
         * Which clock the captured timestamps are on, worked out by comparing them to both.
         *
         * [com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples.timestampMs] is documented as
         * `SystemClock.uptimeMillis`, which is true by construction on the generic engine — it reads
         * `MotionEvent.getEventTime`. A vendor pipeline hands back a `timestamp` field with no
         * documented clock at all, and the difference is not cosmetic: uptime and wall clock are
         * hours or years apart, and a host computing stroke duration or replay timing from the
         * wrong one gets an answer that is wrong by that much.
         *
         * Nothing in an API can answer this. Two subtractions can.
         */
        private fun timestampClock(): String {
            val last = lastTimestampMs ?: return "no timestamps captured yet"
            val uptimeDelta = abs(SystemClock.uptimeMillis() - last)
            val wallDelta = abs(System.currentTimeMillis() - last)
            return when {
                uptimeDelta < CLOCK_TOLERANCE_MS -> "uptimeMillis (as documented) — Δ ${uptimeDelta} ms"
                wallDelta < CLOCK_TOLERANCE_MS ->
                    "⚠ currentTimeMillis, NOT uptimeMillis — Δ ${wallDelta} ms"
                else -> "⚠ neither clock — uptime Δ $uptimeDelta ms, wall Δ $wallDelta ms"
            }
        }
    }

}
