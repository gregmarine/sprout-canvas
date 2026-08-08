plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

/**
 * Golden-image render regression suite (PLAN.md D13, §4.1.1).
 *
 * Deliberately separate from `test`: geometry assertions run on every build, but pixel comparison
 * is run on demand and before every release, so routine builds are never gated on rendering
 * variance between environments.
 *
 * Empty until Phase 2, which measures Robolectric's graphics fidelity and wires this to whichever
 * tier wins (Robolectric or instrumented). The task exists from Phase 0 so the split is structural
 * rather than retrofitted.
 */
tasks.register("goldenTest") {
    group = "verification"
    description = "Runs the golden-image render regression suite (on demand — not part of `check`)."
    doLast {
        logger.lifecycle("goldenTest: no golden suite yet — wired in Phase 2 (see PLAN.md §4.1.1).")
    }
}
