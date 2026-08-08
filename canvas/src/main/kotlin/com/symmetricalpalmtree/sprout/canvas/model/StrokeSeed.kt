package com.symmetricalpalmtree.sprout.canvas.model

/**
 * Everything known about a stroke at pen-down, before any samples exist.
 *
 * An engine emits one of these through
 * [com.symmetricalpalmtree.sprout.canvas.engine.InkEngineHost.onStrokeBegan], then streams samples
 * against [id], then ends the stroke. The view assembles the [InkStroke].
 *
 * ### Why [channels] is declared up front
 *
 * A stroke's samples may arrive in several batches — a single pen-down to pen-up is **not**
 * guaranteed to produce one callback on e-ink, and an engine that assumed so would silently
 * truncate long strokes. Declaring the channel set once, here, lets the view size its accumulator
 * correctly on the first batch and reject a later batch that changed shape, rather than producing a
 * stroke whose channel mask is a lie about part of its own data.
 */
public data class StrokeSeed(

    /** Unique within the canvas. Samples and the end-of-stroke callback refer back to it. */
    public val id: String,

    /** The tool armed at pen-down. A tool change mid-stroke does not affect a stroke in progress. */
    public val tool: ToolSpec,

    /** What the capturing engine measured about this digitizer. */
    public val calibration: DeviceCalibration,

    /** [com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo.id] of the capturing engine. */
    public val engineId: String,

    /** The channels every sample batch for this stroke will carry. See the class KDoc. */
    @InkChannels public val channels: Int,

    /** Wall-clock time of pen-down, ms since epoch. */
    public val startedAtMs: Long,
) {

    init {
        require(id.isNotEmpty()) { "a stroke id must not be empty" }
        require(InkChannel.isValid(channels)) {
            "unknown channel bits in 0x${channels.toString(16)}"
        }
    }
}
