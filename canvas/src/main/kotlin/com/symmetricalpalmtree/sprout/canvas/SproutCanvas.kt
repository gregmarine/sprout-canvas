package com.symmetricalpalmtree.sprout.canvas

/**
 * Entry point and identity for the sprout-canvas library.
 *
 * ### What lives here
 *
 * This object is the library's front door. In later phases it gains:
 *
 *  - `initialize(application)` — the one piece of host cooperation the library asks for. The Onyx
 *    SDK needs a process-wide hidden-API exemption before it is touched, and the Supernote firmware
 *    client needs hidden `ServiceManager` access. A library must not install either behind the
 *    host's back, so the host opts in explicitly. If it is never called, the hardware adapters
 *    report themselves unsupported and the canvas runs its generic engine — with a clear logged
 *    error, never a crash and never a silent loss of the hardware path.
 *  - Engine registration and discovery.
 *  - The global capability query.
 *
 * ### Phase 0 scope
 *
 * Identity only. The API surface is designed and documented in Phase 1; the hardware paths that
 * make `initialize` necessary arrive in Phases 4 and 5.
 */
public object SproutCanvas {

    /**
     * The library version, matching the published Maven coordinate
     * `com.symmetricalpalmtree.sprout:canvas`.
     *
     * Exposed so a host app — and the conformance harness in particular — can report exactly which
     * build produced a stroke capture or a device report. Diagnostics that cannot name their own
     * version age badly.
     */
    public const val VERSION: String = "0.1.0-SNAPSHOT"
}
