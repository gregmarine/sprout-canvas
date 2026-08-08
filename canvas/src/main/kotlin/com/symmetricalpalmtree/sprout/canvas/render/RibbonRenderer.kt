package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import kotlin.math.atan2

/**
 * The pressure-responsive pens: [SproutPen.FOUNTAIN] and [SproutPen.BRUSH].
 *
 * ### Why these cannot be a stroked path
 *
 * `Paint.strokeWidth` is one number for an entire path, and a nib whose width does not change is not
 * a nib. So the ink is built as an explicit outline — offset left along the centreline, back along
 * the right — and **filled**, with the width free to change at every sample.
 *
 * ### Why the caps are part of the outline
 *
 * The ends could be drawn as two circles on top of the ribbon, and for an opaque pen nobody would
 * know. Under any alpha they would blend twice and leave a visibly darker blob at each end of every
 * stroke. Closing the outline with semicircular arcs keeps the whole stroke a single fill, which
 * composites once no matter what alpha the app chose.
 */
internal class RibbonRenderer : StrokeRenderer {

    private val solver = StrokeSolver()
    private val ribbon = RibbonSolver()
    private val path = Path()
    private val capOval = RectF()
    private val dot = FloatArray(2)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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

        val color = resolvePaintColor(tool, tuning)

        if (solver.count == 1) {
            dotPaint.color = color
            dotPaint.strokeWidth = solver.width[0]
            dot[0] = solver.x[0]
            dot[1] = solver.y[0]
            canvas.drawPoints(dot, dotPaint)
            return
        }

        ribbon.solve(solver)
        if (ribbon.pointCount < 4) return

        val n = solver.count
        val outline = ribbon.outline

        path.rewind()
        path.moveTo(outline[0], outline[1])
        for (i in 1 until n) path.lineTo(outline[i * 2], outline[i * 2 + 1])
        // Round the far end, from the left offset across to the right one.
        addCap(index = n - 1, arrivingOffset = (n - 1) * 2)
        for (i in n until ribbon.pointCount) path.lineTo(outline[i * 2], outline[i * 2 + 1])
        // Round the near end, closing the loop back to where the outline started.
        addCap(index = 0, arrivingOffset = (ribbon.pointCount - 1) * 2)
        path.close()

        paint.color = color
        canvas.drawPath(path, paint)
    }

    /**
     * Sweeps a half-circle cap around solved point [index], starting from the outline vertex the
     * path has just reached — given as its offset into [RibbonSolver.outline].
     *
     * The sweep is negative because the outline runs up the left side and back down the right: in
     * Android's y-down coordinate system that puts the bulge *outside* the stroke at both ends. A
     * positive sweep would tuck each cap back over the ribbon it is supposed to finish.
     */
    private fun addCap(index: Int, arrivingOffset: Int) {
        val cx = solver.x[index]
        val cy = solver.y[index]
        val radius = solver.width[index] * 0.5f
        if (radius <= 0f) return

        val startAngle = Math.toDegrees(
            atan2(
                (ribbon.outline[arrivingOffset + 1] - cy).toDouble(),
                (ribbon.outline[arrivingOffset] - cx).toDouble(),
            ),
        ).toFloat()

        capOval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        path.arcTo(capOval, startAngle, -HALF_TURN_DEGREES, false)
    }

    override fun outsetPx(tool: ToolSpec, context: RenderContext): Float =
        maxDrawnWidthPx(tool, PenTuning.forPen(tool.pen), context) * 0.5f

    private companion object {
        const val HALF_TURN_DEGREES = 180f
    }
}
