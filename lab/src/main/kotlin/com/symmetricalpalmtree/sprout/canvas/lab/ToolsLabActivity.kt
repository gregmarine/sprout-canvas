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
class ToolsLabActivity : AppCompatActivity() {

    private lateinit var canvas: SproutCanvasView
    private lateinit var status: TextView

    private var pen = SproutPen.BALLPOINT
    private var width = SproutWidth.MEDIUM
    private var color = Color.BLACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_lab)

        canvas = findViewById(R.id.canvas)
        status = findViewById(R.id.toolsStatus)

        buildPenButtons(findViewById(R.id.penRowOne), SproutPen.entries.take(PENS_PER_ROW))
        buildPenButtons(findViewById(R.id.penRowTwo), SproutPen.entries.drop(PENS_PER_ROW))
        buildWidthButtons(findViewById(R.id.widthRow))
        buildColorButtons(findViewById(R.id.colorRow))

        findViewById<Button>(R.id.eraserToggle).setOnClickListener {
            canvas.eraser = if (canvas.eraser == null) EraserSpec.DEFAULT else null
            applyTool()
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener { canvas.clear() }

        applyTool()
    }

    private fun buildPenButtons(row: LinearLayout, pens: List<SproutPen>) {
        pens.forEach { candidate ->
            row.addView(
                compactButton(candidate.displayName) {
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
                compactButton(formatDp(rung.dp)) {
                    width = rung
                    applyTool()
                },
            )
        }
    }

    private fun buildColorButtons(row: LinearLayout) {
        SWATCHES.forEach { (label, value) ->
            row.addView(
                compactButton(label) {
                    color = value
                    applyTool()
                },
            )
        }
    }

    private fun compactButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
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
        }

    private fun applyTool() {
        canvas.tool = ToolSpec(pen = pen, widthDp = width.dp, color = color)
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
        const val PENS_PER_ROW = 5
        const val BUTTON_HEIGHT_DP = 40
        const val PADDING_PX = 18

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
