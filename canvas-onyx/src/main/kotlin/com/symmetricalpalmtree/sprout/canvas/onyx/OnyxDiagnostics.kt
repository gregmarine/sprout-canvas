package com.symmetricalpalmtree.sprout.canvas.onyx

import android.os.Build

/**
 * What the adapter knows about the panel it is running on, as text for a device report.
 *
 * ### Why an adapter publishes a diagnostic at all
 *
 * Almost nothing about this platform can be asserted by a test. The firmware paints live ink outside
 * the Android framebuffer, so a screenshot cannot see it; the SDK swallows its own failures, so a
 * call that did nothing is indistinguishable from one that worked. What is left is showing the
 * tester what the adapter measured and letting them compare it against what they can see — which is
 * exactly the diagnostic the reference project's five-device pen survey wanted and had to build by
 * hand, five times.
 *
 * Safe to call on any device, including one with no BOOX SDK at all: every value is either a
 * constant or already-cached, and nothing here initializes the SDK.
 */
public object OnyxDiagnostics {

    private var coordinateSpace: CoordinateSpace.Space? = null
    private var coordinateSpaceConfirmed = false
    private var engineState: (() -> String)? = null

    /** Recorded by the engine on every stroke, so the report shows what is actually in force. */
    internal fun recordCoordinateSpace(space: CoordinateSpace.Space, confirmed: Boolean) {
        coordinateSpace = space
        coordinateSpaceConfirmed = confirmed
    }

    /**
     * Registered by the attached engine so the report can read live SDK state.
     *
     * ### Why the report has to ask the SDK rather than the adapter
     *
     * Almost everything on this platform fails silently. `setRawDrawingEnabled(true)` returns
     * nothing and validates nothing, so "the adapter called it" is not evidence that input is armed
     * — and when a canvas takes no ink, the first question is which of the two is true. The SDK
     * does expose `isRawDrawingInputEnabled`, and it is the only second opinion available anywhere
     * on this device.
     *
     * A lambda rather than a value because the answer changes with focus, eraser mode and handoff,
     * and a snapshot taken at attach would be reassuring and wrong.
     */
    internal fun publishEngineState(state: (() -> String)?) {
        engineState = state
    }

    /** A multi-line summary. Never throws, on any device. */
    public fun describe(): String = buildString {
        val onyxHardware = runCatching { OnyxSdk.isOnyxHardware() }.getOrDefault(false)
        appendLine("manufacturer:      ${Build.MANUFACTURER} / ${Build.BRAND}")
        appendLine("onyx hardware:     $onyxHardware")

        if (!onyxHardware) {
            appendLine("(not a BOOX device — the Onyx adapter is present but stands down)")
            return@buildString
        }

        appendLine("device:            ${runCatching { OnyxSdk.describeDevice() }.getOrDefault("unreadable")}")
        appendLine("committed layer:   ${OnyxRenderMode.current}")
        append(engineState?.invoke() ?: "engine state:      no onyx engine attached\n")

        val space = coordinateSpace
        appendLine(
            "raw input coords:  " + when {
                space == null -> "not yet observed — draw a stroke"
                coordinateSpaceConfirmed -> "$space (confirmed by a stroke)"
                // The two spaces coincide when the canvas sits at the screen origin, so no stroke
                // can tell them apart and none needed to.
                else -> "$space (assumed — canvas is at the screen origin, so it cannot differ)"
            },
        )
    }

    /** Restores the pristine state. Tests only. */
    internal fun resetForTesting() {
        coordinateSpace = null
        coordinateSpaceConfirmed = false
    }
}
