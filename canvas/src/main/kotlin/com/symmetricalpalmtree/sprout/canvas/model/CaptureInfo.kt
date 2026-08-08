package com.symmetricalpalmtree.sprout.canvas.model

import com.symmetricalpalmtree.sprout.canvas.SproutCanvas

/**
 * Provenance for a captured stroke: which engine produced it, on what hardware, and when.
 *
 * A stroke that cannot name the conditions it was captured under is a stroke that cannot be
 * debugged. When a device report says pressure looks wrong, this is what turns "a BOOX" into "the
 * Onyx engine, `maxPressure` 4096, library 0.1.0" — which is usually the whole answer.
 */
public data class CaptureInfo(

    /** [com.symmetricalpalmtree.sprout.canvas.engine.EngineInfo.id] of the capturing engine. */
    public val engineId: String,

    /** What the library measured about the digitizer that produced this stroke. */
    public val calibration: DeviceCalibration,

    /** Wall-clock time of pen-down, ms since epoch. */
    public val startedAtMs: Long,

    /** Wall-clock time of pen-up, ms since epoch. */
    public val endedAtMs: Long,

    /** The library build that captured the stroke. */
    public val libraryVersion: String = SproutCanvas.VERSION,
) {

    /** How long the stylus was on the glass, in ms. Never negative. */
    public val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
}
