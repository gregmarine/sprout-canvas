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
 * Phase 2 measured both candidate tiers against each other and wired this to the winner —
 * Robolectric with native graphics. The measurement, and what would change the answer, is in
 * `docs/golden-tier.md`. The instrumented tier renders the identical scenes and stays available as
 * a cross-check (`./gradlew :canvas:connectedDebugAndroidTest`).
 *
 * ```sh
 * ./gradlew goldenTest                                  # compare against the committed images
 * ./gradlew goldenTest -Psprout.golden.regenerate=true   # accept the current rendering, then review the diff
 * ```
 */
tasks.register("goldenTest") {
    group = "verification"
    description = "Runs the golden-image render regression suite (on demand — not part of `check`)."
    dependsOn(":canvas:goldenTest")
}
