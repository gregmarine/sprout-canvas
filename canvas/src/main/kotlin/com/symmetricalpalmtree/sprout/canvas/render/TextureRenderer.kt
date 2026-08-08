package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec
import kotlin.math.roundToInt

/**
 * The grainy pens: [SproutPen.PENCIL] and [SproutPen.CHARCOAL].
 *
 * ### Grain is missing ink, not a paint style
 *
 * Graphite does not lay down a solid line — it catches on the tooth of the paper and leaves gaps.
 * There is no `Paint` setting for that, so the ink is drawn as a scatter of small dots across the
 * stroke's width ([GrainSolver]), bucketed into three size-and-opacity tiers and emitted as three
 * `drawPoints` calls.
 *
 * ### The width trap
 *
 * A texture pen at its nominal width has no room for grain and comes out **solid and grainless with
 * no error at all** — the failure looks like a renderer that ignored the pen entirely. That is why
 * [PenTuning] multiplies these two up (×2.5 and ×5.0, the latter matching BOOX's own charcoal
 * multiplier) and why the multipliers are load-bearing rather than cosmetic. PLAN.md §5.7.
 */
internal class TextureRenderer : StrokeRenderer {

    private val solver = StrokeSolver()
    private val grain = GrainSolver()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        grain.solve(solver, tuning, seed)

        val color = resolvePaintColor(tool, tuning)
        val baseAlpha = Color.alpha(color)

        for (tier in 0 until GrainSolver.TIERS) {
            val floats = grain.floatCount(tier)
            if (floats == 0) continue
            paint.color = color
            paint.alpha = (baseAlpha * GrainSolver.TIER_ALPHA_FACTOR[tier]).roundToInt()
                .coerceIn(0, 255)
            // A stamp is a round cap on a zero-length point, so strokeWidth *is* its diameter.
            paint.strokeWidth = grain.tierDiameter[tier].coerceAtLeast(MIN_STAMP_PX)
            canvas.drawPoints(grain.tierPoints(tier), 0, floats, paint)
        }
    }

    /**
     * Grain scatters across the full width and each stamp has a radius of its own, so the ink
     * reaches half a stamp beyond where a solid pen of the same width would stop.
     */
    override fun outsetPx(tool: ToolSpec, context: RenderContext): Float {
        val tuning = PenTuning.forPen(tool.pen)
        val width = maxDrawnWidthPx(tool, tuning, context)
        return width * 0.5f * (1f + GrainSolver.TIER_DIAMETER_FACTOR.max())
    }

    private companion object {
        /** Below this a stamp is invisible on any panel, and thousands of them are still invisible. */
        const val MIN_STAMP_PX = 0.75f
    }
}
