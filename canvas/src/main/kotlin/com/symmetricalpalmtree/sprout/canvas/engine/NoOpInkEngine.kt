package com.symmetricalpalmtree.sprout.canvas.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.EraserSpec
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.ToolSpec

/**
 * An engine that captures nothing and draws nothing.
 *
 * ### What it is for
 *
 * It makes [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView] a complete, exercisable object
 * before any real engine exists: the view can be laid out, armed with a tool, given strokes, asked
 * for its capabilities and read on a device, all against a stub that cannot mislead anyone about
 * what it does. Stroke *ingest* works — the model and listener paths are real. Only capture and
 * rendering are absent.
 *
 * ### What it is not
 *
 * It is not a fallback for a device the library does not recognize; that is the generic engine's
 * job, and from Phase 2 onwards the generic engine is the last resort instead. Nothing about this
 * class should survive into a shipping app's behaviour.
 */
public class NoOpInkEngine(
    @Suppress("unused") private val host: InkEngineHost,
) : InkEngine {

    override val info: EngineInfo get() = INFO

    override val capabilities: CanvasCapabilities get() = CAPABILITIES

    /** Always false — no stylus reaches this engine, so nothing can make the gate open. */
    override val isPenActive: Boolean get() = false

    override fun attach(view: View) {}

    override fun detach() {}

    override fun onBoundsChanged(canvasBounds: Rect, screenOffset: Point) {}

    override fun onExclusionZonesChanged(zonesInCanvasCoords: List<Rect>) {}

    override fun setTool(tool: ToolSpec) {}

    override fun setEraser(eraser: EraserSpec?) {}

    override fun resume() {}

    override fun pause() {}

    override fun releaseForHandoff() {}

    override fun releaseLiveInk() {}

    override fun onCommittedContentChanged(reason: RepaintReason) {}

    /** Consumes nothing, so a host's own touch handling still works over a stub canvas. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun drawLiveInk(canvas: Canvas) {}

    public companion object {
        /** Lowest possible priority: this must never win selection over a real engine. */
        public val INFO: EngineInfo = EngineInfo(
            id = EngineIds.NO_OP,
            displayName = "No-op (stub)",
            priority = Int.MIN_VALUE,
        )

        /**
         * Reports nothing it cannot do.
         *
         * Every pen is [PenFidelity.APPROXIMATE] — the lowest value the enum offers — because the
         * stub renders no pen at all, and claiming anything better would be exactly the silent lie
         * the fidelity model exists to prevent.
         */
        public val CAPABILITIES: CanvasCapabilities = CanvasCapabilities(
            engineId = EngineIds.NO_OP,
            channels = InkChannel.NONE,
            supportsAlpha = false,
            supportedEraserModes = emptySet(),
            penFidelities = CanvasCapabilities.uniformFidelity(PenFidelity.APPROXIMATE),
            liveInkIsHardware = false,
            livePreviewColorFloor = 0,
            calibration = DeviceCalibration.UNKNOWN,
        )
    }
}

/**
 * Factory for [NoOpInkEngine]. Supported everywhere, chosen nowhere a real engine exists.
 *
 * Registered as the registry's fallback until Phase 2 delivers `GenericInkEngineFactory`, which
 * takes its place as the always-supported last resort.
 */
public object NoOpInkEngineFactory : InkEngineFactory {

    override val info: EngineInfo get() = NoOpInkEngine.INFO

    override fun isSupported(context: Context): Boolean = true

    override fun create(host: InkEngineHost): InkEngine = NoOpInkEngine(host)
}
