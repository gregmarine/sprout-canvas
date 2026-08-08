package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * [SproutPen.CALLIGRAPHY] — a chisel nib held at a fixed angle.
 *
 * The characteristic thick-and-thin comes out of the geometry rather than a width curve: the nib is
 * a straight edge, so dragging it perpendicular to that edge lays down its whole length and dragging
 * it along the edge lays down almost nothing. [NibSolver] sweeps it; this fills what it swept.
 *
 * ### The nib has thickness, and it has to
 *
 * A nib modelled as a line of zero thickness draws **nothing at all** when the stroke happens to run
 * exactly along the angle it is held at — every swept quad collapses to a degenerate sliver. A real
 * chisel dragged along its own edge leaves a hairline, not a blank space, so the quads are filled
 * *and* stroked at [NIB_THICKNESS_FACTOR] of the nominal width. That single paint setting is the
 * difference between a calligraphy pen and a pen that silently fails on one diagonal.
 *
 * ### One path, filled once
 *
 * Every quad goes into a single [Path] with the nonzero fill rule. Two things follow from that:
 * overlapping quads at a tight corner composite once instead of darkening, and — the reason
 * [NibSolver] normalizes each quad's winding — a stroke that doubles back on itself does not punch a
 * hole through the ink it already laid down.
 */
internal class CalligraphyRenderer : StrokeRenderer {

    private val solver = StrokeSolver()
    private val nib = NibSolver()
    private val path = Path()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // FILL_AND_STROKE, not FILL: the stroke is what gives the nib its thickness. See the KDoc.
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isDither = true
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    override fun draw(
        canvas: Canvas,
        samples: StrokeSamples,
        tool: ToolSpec,
        seed: Int,
        context: RenderContext,
    ) {
        val tuning = PenTuning.forPen(tool.pen)
        val nominal = context.toPx(tool.widthDp)
        solver.solve(samples, tuning, nominal)
        if (solver.count == 0) return

        val nibLength = nominal * tuning.widthMultiplier * NibSolver.NIB_LENGTH_FACTOR
        nib.solve(solver, nibLength)
        paint.color = resolvePaintColor(tool, tuning)
        paint.strokeWidth = nibThicknessPx(nominal)

        // A tap leaves the nib's own footprint: a short line along the edge, not a round dot.
        if (nib.quadCount == 0) {
            dotPaint.color = paint.color
            dotPaint.strokeWidth = nibThicknessPx(nominal)
            canvas.drawLine(
                solver.x[0] - nib.nibOffsetX,
                solver.y[0] - nib.nibOffsetY,
                solver.x[0] + nib.nibOffsetX,
                solver.y[0] + nib.nibOffsetY,
                dotPaint,
            )
            return
        }

        path.rewind()
        path.fillType = Path.FillType.WINDING
        for (q in 0 until nib.quadCount) {
            val o = q * 8
            path.moveTo(nib.quads[o], nib.quads[o + 1])
            path.lineTo(nib.quads[o + 2], nib.quads[o + 3])
            path.lineTo(nib.quads[o + 4], nib.quads[o + 5])
            path.lineTo(nib.quads[o + 6], nib.quads[o + 7])
            path.close()
        }
        canvas.drawPath(path, paint)
    }

    /** The nib reaches half its own length past the centreline, in whichever direction it is held. */
    override fun outsetPx(tool: ToolSpec, context: RenderContext): Float {
        val tuning = PenTuning.forPen(tool.pen)
        val nominal = context.toPx(tool.widthDp)
        return nominal * tuning.widthMultiplier * NibSolver.NIB_LENGTH_FACTOR * 0.5f +
            nibThicknessPx(nominal) * 0.5f
    }

    private fun nibThicknessPx(nominalPx: Float): Float =
        (nominalPx * NIB_THICKNESS_FACTOR).coerceAtLeast(MIN_NIB_PX)

    private companion object {
        /** How thick the nib's edge is, as a fraction of nominal width. A chisel is not a knife. */
        const val NIB_THICKNESS_FACTOR = 0.35f

        /** A nib thinner than a pixel is a nib that vanishes on a low-density screen. */
        const val MIN_NIB_PX = 1f
    }
}
