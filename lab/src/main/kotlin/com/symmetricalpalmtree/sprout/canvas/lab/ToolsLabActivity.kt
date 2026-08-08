package com.symmetricalpalmtree.sprout.canvas.lab

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.SproutWidth
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import com.symmetricalpalmtree.sprout.canvas.onyx.OnyxRenderMode
import kotlin.math.roundToInt

/**
 * The Tools screen: every standardized pen, every width, switchable mid-session.
 *
 * ### What it is here to prove
 *
 * That the nine pens are nine *different* pens. A marker and a highlighter must be unmistakably
 * different tools rather than one tool under two names — that is the whole argument for splitting
 * them (PLAN.md D12) — and a pencil must show grain rather than drawing a clean line and calling
 * itself graphite.
 *
 * It also shows the **fidelity** the running engine reports for each pen. On the generic engine
 * every pen is `NATIVE`, because the software renderer is the reference the vendor paths are tuned
 * against. On a BOOX or a Supernote this column is where a pen that quietly degraded becomes
 * visible, which is what the fidelity model exists for.
 */
class ToolsLabActivity : InkLabActivity() {

    private lateinit var canvas: SproutCanvasView

    override val inkCanvas: SproutCanvasView?
        get() = if (::canvas.isInitialized) canvas else null
    private lateinit var status: TextView

    private var pen = SproutPen.BALLPOINT
    private var width = SproutWidth.MEDIUM
    private var color = Color.BLACK

    /**
     * Every button that can be selected, with the test for whether it currently is.
     *
     * ### Why a harness needs selection state at all
     *
     * The status line has always named the armed tool, and on a phone that would be enough. On
     * e-ink it is not: a tap produces no ripple, no press animation and no colour change, so a
     * tester walking the nine pens has nothing telling them the tap registered. The failure is
     * quiet and it lands on the *tester* — they draw nine strokes believing they changed pen and
     * cannot tell afterwards which stroke was which.
     *
     * Found by a tester trying to do exactly that. An instrument that cannot be read is not an
     * instrument.
     */
    private val selectableButtons = mutableListOf<Pair<Button, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_lab)

        canvas = findViewById(R.id.canvas)
        status = findViewById(R.id.toolsStatus)

        buildPenButtons(findViewById(R.id.penRowOne), SproutPen.entries.take(PENS_PER_ROW))
        buildPenButtons(findViewById(R.id.penRowTwo), SproutPen.entries.drop(PENS_PER_ROW))
        buildWidthButtons(findViewById(R.id.widthRow))
        buildColorButtons(findViewById(R.id.colorRow))

        val eraserToggle = findViewById<Button>(R.id.eraserToggle)
        eraserToggle.setOnClickListener {
            canvas.eraser = if (canvas.eraser == null) EraserSpec.DEFAULT else null
            applyTool()
        }
        selectableButtons += eraserToggle to { canvas.eraser != null }
        findViewById<Button>(R.id.clearButton).setOnClickListener { canvas.clear() }

        // Switching the committed-layer renderer recreates the screen, because a canvas reads the
        // mode when its engine attaches. The strokes already on the canvas are re-rendered by
        // whichever renderer is now armed, which is exactly the comparison worth making: the same
        // captured ink, drawn both ways, without having to write it twice.
        findViewById<Button>(R.id.renderModeButton).setOnClickListener {
            // Carried across the recreation so the *same* captured ink is redrawn by the other
            // renderer. Drawing the comparison stroke twice would compare two strokes as well as
            // two renderers, and by hand on a panel the difference between those is exactly the
            // size of the difference being looked for.
            carriedStrokes = canvas.getStrokes()
            LabSettings.setRenderMode(
                this,
                when (OnyxRenderMode.current) {
                    OnyxRenderMode.Mode.SOFTWARE -> OnyxRenderMode.Mode.NEO_PEN
                    OnyxRenderMode.Mode.NEO_PEN -> OnyxRenderMode.Mode.SOFTWARE
                },
            )
            recreate()
        }

        carriedStrokes?.let { strokes ->
            carriedStrokes = null
            canvas.setStrokes(strokes)
        }

        applyTool()
    }

    private fun buildPenButtons(row: LinearLayout, pens: List<SproutPen>) {
        pens.forEach { candidate ->
            row.addView(
                compactButton(
                    label = candidate.displayName,
                    isSelected = { pen == candidate && canvas.eraser == null },
                ) {
                    pen = candidate
                    canvas.eraser = null
                    applyTool()
                },
            )
        }
    }

    private fun buildWidthButtons(row: LinearLayout) {
        SproutWidth.entries.forEach { rung ->
            row.addView(
                compactButton(formatDp(rung.dp), isSelected = { width == rung }) {
                    width = rung
                    applyTool()
                },
            )
        }
    }

    private fun buildColorButtons(row: LinearLayout) {
        SWATCHES.forEach { (label, value) ->
            row.addView(
                compactButton(label, isSelected = { color == value }) {
                    color = value
                    applyTool()
                },
            )
        }
    }

    private fun compactButton(
        label: String,
        isSelected: (() -> Boolean)? = null,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(PADDING_PX, 0, PADDING_PX, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (BUTTON_HEIGHT_DP * resources.displayMetrics.density).roundToInt(),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        setOnClickListener { onClick() }
        if (isSelected != null) selectableButtons += this to isSelected
    }

    /**
     * Repaints every button so exactly one in each row reads as armed.
     *
     * Inverted rather than tinted, deliberately. A Kaleido panel renders a colour tint as a pale
     * grey barely distinguishable from the default button, and a mono panel renders it as nothing
     * at all — while black-on-white against white-on-black is unmistakable on every panel this
     * library runs on, including the ones with no colour whatsoever.
     */
    private fun refreshSelection() {
        // The button says which renderer is armed, not merely that it toggles something.
        findViewById<Button>(R.id.renderModeButton).text = getString(
            R.string.action_committed_layer_mode,
            if (OnyxRenderMode.current == OnyxRenderMode.Mode.NEO_PEN) "Neo" else "SW",
        )
        selectableButtons.forEach { (button, selected) ->
            if (selected()) {
                button.setBackgroundColor(Color.BLACK)
                button.setTextColor(Color.WHITE)
            } else {
                button.setBackgroundColor(UNSELECTED_BACKGROUND)
                button.setTextColor(Color.BLACK)
            }
        }
    }

    private fun applyTool() {
        canvas.tool = ToolSpec(pen = pen, widthDp = width.dp, color = color)
        refreshSelection()
        val erasing = canvas.eraser != null
        status.text = buildString {
            appendLine(
                getString(
                    R.string.tools_status,
                    if (erasing) getString(R.string.tool_eraser) else pen.displayName,
                    formatDp(width.dp),
                    colorName(color),
                ),
            )
            appendLine(
                getString(
                    R.string.tools_fidelity,
                    canvas.engineInfo.id,
                    canvas.capabilities.fidelity(pen).name,
                ),
            )
            // Named on the screen where the pens are actually compared. Reading it off another
            // screen is how a whole comparison pass gets made against the wrong renderer.
            appendLine(getString(R.string.tools_committed_layer, OnyxRenderMode.current.name))
            // Both of these are things a tool picker in a host app would want to know, and both are
            // answers the library gives rather than assumptions an app has to make.
            append(
                getString(
                    R.string.tools_traits,
                    pen.isPressureSensitive.toString(),
                    pen.isTranslucentByDefault.toString(),
                    canvas.capabilities.supportsAlpha.toString(),
                ),
            )
        }
    }

    private fun formatDp(dp: Float): String =
        if (dp == dp.toInt().toFloat()) "${dp.toInt()}dp" else "${dp}dp"

    private fun colorName(value: Int): String =
        SWATCHES.firstOrNull { it.second == value }?.first ?: "#%06X".format(value and 0xFFFFFF)

    private companion object {
        /**
         * Ink carried across the screen recreation that a renderer switch performs.
         *
         * Static because the Activity instance does not survive it. This is harness state and it
         * lives for exactly one recreation — cleared as soon as it is applied.
         */
        var carriedStrokes: List<com.symmetricalpalmtree.sprout.canvas.model.InkStroke>? = null

        const val PENS_PER_ROW = 5
        const val BUTTON_HEIGHT_DP = 40
        const val PADDING_PX = 18

        /** The unarmed button face. Light enough to read as a control, on any panel. */
        val UNSELECTED_BACKGROUND: Int = Color.rgb(0xDD, 0xDD, 0xDD)

        /**
         * A colour is stored exactly as given and never rewritten to suit the device — a red stroke
         * on a mono panel stays red in the data and merely renders grey (PLAN.md §3.6). These
         * swatches are here to make that visible on a Kaleido panel and unremarkable on a mono one.
         */
        val SWATCHES = listOf(
            "black" to Color.BLACK,
            "red" to Color.rgb(200, 30, 30),
            "blue" to Color.rgb(30, 60, 200),
            "green" to Color.rgb(20, 130, 60),
            "grey" to Color.rgb(120, 120, 120),
        )
    }
}
