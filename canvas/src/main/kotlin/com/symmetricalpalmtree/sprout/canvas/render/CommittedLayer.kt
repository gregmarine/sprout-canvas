package com.symmetricalpalmtree.sprout.canvas.render

import android.graphics.Canvas
import android.graphics.RenderNode
import com.symmetricalpalmtree.sprout.canvas.SproutLog

/**
 * The canvas's committed content, held as a recorded display list.
 *
 * ### Why a `RenderNode`
 *
 * Committed content changes rarely — a stroke commits, an erase lands, a host ingests — but it is
 * redrawn on every frame, including every frame of an in-progress stroke. Recording it once into a
 * hardware [RenderNode] turns those frames into a GPU texture blit instead of re-tessellating every
 * path on the page. It is the reason [minSdk 29][android.os.Build.VERSION_CODES.Q] is an
 * architectural floor for this library rather than a config value (PLAN.md D4, §3.8).
 *
 * ### The software branch is not optional
 *
 * A `RenderNode` can only be drawn onto a **hardware** canvas. Onyx's `EpdController.handwritingRepaint`
 * re-draws the view through a **software** canvas in order to capture it for the panel — so without
 * the fallback branch, every e-ink panel repaint would come back blank. The same branch covers
 * `View.draw(Canvas)` into a bitmap, which is how any host would screenshot the canvas.
 *
 * That is why this class takes the content as a lambda rather than a list of strokes: the exact same
 * drawing code has to serve both the recording and the fallback, and two copies of it would drift.
 *
 * @param content draws the committed content. Called during recording, and again on every software
 *   draw.
 */
internal class CommittedLayer(private val content: (Canvas) -> Unit) {

    private var node: RenderNode? = null

    /**
     * Set when a `RenderNode` could not be created or recorded on this device.
     *
     * Hardware-accelerated rendering is a platform guarantee at API 29, but this library runs on
     * heavily-modified vendor firmware where guarantees have been wrong before. Losing the display
     * list costs some per-frame work; taking the host app down with it would cost rather more.
     */
    private var nodeUnavailable = false

    private var width = 0
    private var height = 0

    /** True when there is a recorded display list ready to blit. */
    val hasDisplayList: Boolean get() = node?.hasDisplayList() == true

    /**
     * Records the committed content at [width] × [height].
     *
     * Called only when committed content actually changes. During active writing the view merely
     * invalidates, and this is not touched — re-recording every page of ink per frame of a stroke is
     * the performance trap this whole model exists to avoid.
     */
    fun record(width: Int, height: Int) {
        this.width = width
        this.height = height
        if (width <= 0 || height <= 0 || nodeUnavailable) return

        val target = node ?: createNode() ?: return
        try {
            target.setPosition(0, 0, width, height)
            val canvas = target.beginRecording(width, height)
            try {
                content(canvas)
            } finally {
                target.endRecording()
            }
        } catch (t: Throwable) {
            SproutLog.e("committed content could not be recorded; drawing it directly instead", t)
            nodeUnavailable = true
            node = null
        }
    }

    /**
     * Draws the committed content onto [canvas], by whichever path that canvas supports.
     *
     * The branch is on the canvas, not on the device: the same view is drawn through a hardware
     * canvas for the screen and a software one for a panel repaint or a screenshot, within the same
     * session.
     */
    fun draw(canvas: Canvas) {
        val target = node
        if (canvas.isHardwareAccelerated && target != null && target.hasDisplayList()) {
            canvas.drawRenderNode(target)
        } else {
            content(canvas)
        }
    }

    /** Drops the display list. Called when the view detaches. */
    fun discard() {
        node?.discardDisplayList()
        node = null
    }

    private fun createNode(): RenderNode? = try {
        RenderNode(NODE_NAME).also { node = it }
    } catch (t: Throwable) {
        SproutLog.e("RenderNode is unavailable here; committed content will be drawn directly", t)
        nodeUnavailable = true
        null
    }

    private companion object {
        /** Shows up in `dumpsys gfxinfo`, so it is worth being able to find. */
        const val NODE_NAME = "sprout-committed"
    }
}
