package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * The pens that draw one width from end to end: [SproutPen.BALLPOINT], [SproutPen.MARKER],
 * [SproutPen.HIGHLIGHTER] and [SproutPen.DASHED].
 *
 * A stroked `Path` is exactly the right tool here and nothing more is needed — `Paint.strokeWidth`
 * is a single number, which is a problem only for the pens whose width varies (see [RibbonRenderer]).
 * What separates these four from each other is the cap, the width multiplier, the translucency and
 * the dash — not the geometry.
 */
internal class EvenWidthRenderer : StrokeRenderer {

    private val solver = StrokeSolver()
    private val path = Path()
    private val dot = FloatArray(2)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        isDither = true
    }

    override fun draw(
        canvas: Canvas,
        samples: StrokeSamples,
        tool: ToolSpec,
        seed: Int,
        context: RenderContext,
    ) {
        val tuning = PenTuning.forPen(tool.pen)
        solver.solve(samples, tuning, context.toPx(tool.widthDp))
        if (solver.count == 0) return

        val width = solver.width[0]
        paint.color = resolvePaintColor(tool, tuning)
        paint.strokeWidth = width
        paint.strokeCap = capFor(tool.pen)
        paint.strokeJoin = Paint.Join.ROUND
        paint.pathEffect = if (tool.pen == SproutPen.DASHED) {
            DashPathEffect(DashCadence.intervals(width), 0f)
        } else {
            null
        }

        // A tap is a dot, and a dot is a legitimate mark. `drawPoints` paints the cap shape at the
        // point, so a ballpoint tap is round and a marker tap is square — which is what those pens
        // would actually leave behind.
        if (solver.count == 1) {
            dot[0] = solver.x[0]
            dot[1] = solver.y[0]
            paint.pathEffect = null
            canvas.drawPoints(dot, paint)
            return
        }

        path.rewind()
        path.moveTo(solver.x[0], solver.y[0])
        for (i in 1 until solver.count) path.lineTo(solver.x[i], solver.y[i])
        canvas.drawPath(path, paint)
    }

    override fun outsetPx(tool: ToolSpec, context: RenderContext): Float =
        maxDrawnWidthPx(tool, PenTuning.forPen(tool.pen), context) * 0.5f

    /**
     * A flat end for the flat pens.
     *
     * A marker and a highlighter are chisel-ended in life, and a round cap on a wide translucent
     * wash reads as a lozenge rather than as a highlighter run across a line of text.
     */
    private fun capFor(pen: SproutPen): Paint.Cap = when (pen) {
        SproutPen.MARKER, SproutPen.HIGHLIGHTER -> Paint.Cap.SQUARE
        else -> Paint.Cap.ROUND
    }
}
