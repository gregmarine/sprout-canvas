package com.symmetricalpalmtree.sprout.canvas.golden

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The golden-image suite (PLAN.md D13, §4.1.1).
 *
 * ### How to run it
 *
 * ```sh
 * ./gradlew goldenTest                       # compare against the committed images
 * ./gradlew goldenTest -Psprout.golden.regenerate=true   # accept the current rendering
 * ```
 *
 * It is **not** part of `check`. Geometry runs on every build; pixels run when a renderer changes
 * and before a release, so a routine build is never gated on rendering variance.
 *
 * ### Regenerating
 *
 * Regeneration is a deliberate act with a deliberate flag, and the regenerated images are reviewed
 * in the diff like any other change. A golden that is regenerated reflexively whenever it fails is
 * not a test, it is a log.
 *
 * ### Which tier this is
 *
 * Robolectric, in `NATIVE` graphics mode — the real Skia pipeline rather than the default mode,
 * which records draw calls without executing them and would hand back a blank bitmap for every
 * scene. The instrumented tier renders the identical scenes in
 * `GoldenImageInstrumentedTest`; the measurement that chose between them is recorded in
 * `docs/golden-tier.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoldenImageTest {

    @Test
    fun `every scene matches its golden`() {
        val regenerating = System.getProperty(REGENERATE_PROPERTY) == "true"
        if (regenerating) {
            goldenDirectory.mkdirs()
            GoldenScenes.scenes().forEach { scene ->
                write(GoldenScenes.render(scene), File(goldenDirectory, "${scene.name}.png"))
            }
            println("golden: regenerated ${GoldenScenes.scenes().size} images in $goldenDirectory")
            return
        }

        val missing = mutableListOf<String>()
        val mismatched = mutableListOf<String>()

        GoldenScenes.scenes().forEach { scene ->
            val file = File(goldenDirectory, "${scene.name}.png")
            if (!file.exists()) {
                missing += scene.name
                return@forEach
            }
            val expected = BitmapFactory.decodeFile(file.absolutePath)
            val difference = GoldenScenes.compare(expected, GoldenScenes.render(scene))
            if (!difference.isIdentical) mismatched += "${scene.name} ($difference)"
        }

        assertTrue(
            "no goldens found in $goldenDirectory — run with -P$REGENERATE_PROPERTY=true to create " +
                "them: $missing",
            missing.size != GoldenScenes.scenes().size,
        )
        assertTrue("goldens are missing for: $missing", missing.isEmpty())
        assertTrue(
            "rendering changed for:\n  ${mismatched.joinToString("\n  ")}\n" +
                "If the change is intended, re-run with -P$REGENERATE_PROPERTY=true and review the " +
                "images in the diff.",
            mismatched.isEmpty(),
        )
    }

    @Test
    fun `rendering the same scene twice is byte-identical`() {
        // Determinism is what makes a golden meaningful at all. If this fails, nothing below it is
        // worth debugging — the suite would be measuring the renderer's mood.
        GoldenScenes.scenes().forEach { scene ->
            val first = GoldenScenes.render(scene)
            val second = GoldenScenes.render(scene)
            assertTrue(
                "${scene.name} rendered differently on a second pass: " +
                    GoldenScenes.compare(first, second),
                first.sameAs(second),
            )
        }
    }

    @Test
    fun `every scene draws something`() {
        // A golden of a blank bitmap passes forever and proves nothing. This is the tripwire for a
        // graphics mode that silently stopped executing draw calls.
        assumeTrue(System.getProperty(REGENERATE_PROPERTY) != "true")
        GoldenScenes.scenes().forEach { scene ->
            val bitmap = GoldenScenes.render(scene)
            var inked = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) inked++
                }
            }
            assertTrue("${scene.name} is blank", inked > 0)
        }
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val REGENERATE_PROPERTY = "sprout.golden.regenerate"

        /**
         * Under `src/test/resources` so the instrumented tier can read the very same files through
         * its assets — one set of images, two tiers comparing against it.
         */
        val goldenDirectory = File("src/test/resources/golden")
    }
}
