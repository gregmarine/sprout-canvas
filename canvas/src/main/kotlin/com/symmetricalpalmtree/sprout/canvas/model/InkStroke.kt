package com.symmetricalpalmtree.sprout.canvas.model

import android.graphics.RectF

/**
 * One complete stroke: its samples, the tool that drew it, and where it came from.
 *
 * This is the unit the library hands to a host app and accepts back. A stroke captured on a BOOX
 * renders identically when handed to a canvas on a phone — that round trip is a guarantee, not a
 * best effort, and it is why [capture] travels with the geometry.
 *
 * Immutable. [samples] copies its arrays on construction, so a stroke cannot be changed out from
 * under a host that stored it.
 */
public class InkStroke(

    /** Unique within a canvas; carried over from [StrokeSeed.id]. */
    public val id: String,

    /** The stroke's geometry and per-sample channels. */
    public val samples: StrokeSamples,

    /** The tool armed when the stroke was drawn. */
    public val tool: ToolSpec,

    /** Which engine captured it, on what hardware, when. */
    public val capture: CaptureInfo,
) {

    private val boundsInternal: RectF = samples.computeBounds(RectF())

    init {
        require(id.isNotEmpty()) { "a stroke id must not be empty" }
    }

    /** True when the stroke carries no samples. */
    public val isEmpty: Boolean get() = samples.isEmpty

    /** Number of samples. */
    public val sampleCount: Int get() = samples.count

    /**
     * The stroke's centreline bounding box, as a fresh [RectF].
     *
     * **Allocates on every access** — [android.graphics.RectF] is mutable, so handing out the
     * stored instance would let a caller corrupt the stroke. Use [getBounds] on the render and
     * hit-test paths, where this would allocate once per stroke per frame.
     *
     * Stroke *width* is not included: it is a rendering property, applied by the renderer.
     */
    public val bounds: RectF get() = RectF(boundsInternal)

    /**
     * Copies the stroke's centreline bounding box into [out] and returns it. Allocation-free.
     *
     * This is the O(1) pre-filter that keeps erase hit-testing cheap: reject a stroke on its box
     * before walking its samples.
     */
    public fun getBounds(out: RectF): RectF {
        out.set(boundsInternal)
        return out
    }

    /** Returns a copy of this stroke with [tool] replaced — used by ingest and by tests. */
    public fun withTool(tool: ToolSpec): InkStroke = InkStroke(id, samples, tool, capture)

    /** Returns a copy of this stroke under a new [id]. */
    public fun withId(id: String): InkStroke = InkStroke(id, samples, tool, capture)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InkStroke) return false
        return id == other.id &&
            samples == other.samples &&
            tool == other.tool &&
            capture == other.capture
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + samples.hashCode()
        result = 31 * result + tool.hashCode()
        result = 31 * result + capture.hashCode()
        return result
    }

    override fun toString(): String =
        "InkStroke(id=$id, ${samples.count} samples, pen=${tool.pen}, engine=${capture.engineId})"
}
