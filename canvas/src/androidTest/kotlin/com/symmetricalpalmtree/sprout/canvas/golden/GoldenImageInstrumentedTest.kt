package com.symmetricalpalmtree.sprout.canvas.golden

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The golden scenes, rendered on real hardware.
 *
 * ### What this is for
 *
 * It is the other half of the measurement that settled **R1** — which tier should host the golden
 * suite (PLAN.md §10.2, D13). Both tiers render the identical scenes from [GoldenScenes], so the
 * question "does a device produce the same pixels the JVM does?" has an answer rather than an
 * opinion. The result, and what would change it, is written up in `docs/golden-tier.md`.
 *
 * ### Why it stays after the decision
 *
 * Because the answer is about a *pair* of environments and either can move underneath us. A future
 * Robolectric or Skia change that silently altered rendering would leave the committed goldens
 * agreeing with a JVM that no longer agrees with any real device — which is precisely the failure
 * mode of trusting one tier and never checking. Running this on hardware re-measures that in a
 * minute:
 *
 * ```sh
 * ./gradlew :canvas:connectedDebugAndroidTest
 * ```
 *
 * ### What it asserts, and what it only reports
 *
 * It **asserts** the two things that would make goldens meaningless anywhere: that a scene draws
 * something, and that rendering it twice gives the same pixels. The device-versus-JVM difference is
 * **reported**, not asserted — a device that anti-aliases a shade differently is not a bug in this
 * library, and a red build for it would train people to ignore the suite. Every difference is
 * written to the run's output and, per scene, to the device's own files directory so it can be
 * pulled and looked at.
 */
@RunWith(AndroidJUnit4::class)
class GoldenImageInstrumentedTest {

    /** An empty host. The golden scenes render to their own bitmaps and never need a view. */
    class GoldenHostActivity : Activity()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everySceneDrawsSomething() {
        // A golden of a blank bitmap passes forever and proves nothing.
        GoldenScenes.scenes().forEach { scene ->
            val bitmap = GoldenScenes.render(scene)
            assertTrue("${scene.name} is blank on this device", inkedPixels(bitmap) > 0)
        }
    }

    @Test
    fun renderingIsDeterministicOnDevice() {
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
    fun theCommittedLayerSoftwareBranchMatchesDirectRenderingOnDevice() {
        // Worth asserting on hardware and not only on the JVM: this is the branch an e-ink panel
        // repaint takes (PLAN.md §3.8), and `RenderNode` behaviour is exactly the kind of platform
        // guarantee that vendor firmware has been wrong about before.
        val committedScenes = GoldenScenes.scenes().filter { it.path == GoldenScenes.Path.COMMITTED }
        assertTrue("no scene exercises the committed path", committedScenes.isNotEmpty())

        committedScenes.forEach { scene ->
            val direct = GoldenScenes.render(GoldenScenes.Scene(scene.name, scene.layers))
            val committed = GoldenScenes.render(scene)
            assertTrue(
                "${scene.name} drew differently through the committed layer on this device: " +
                    GoldenScenes.compare(direct, committed),
                direct.sameAs(committed),
            )
        }
    }

    @Test
    fun reportDifferenceFromTheCommittedGoldens() {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "golden")
        output.mkdirs()

        val lines = mutableListOf<String>()
        var identical = 0
        var missing = 0
        var worstDelta = 0

        GoldenScenes.scenes().forEach { scene ->
            val actual = GoldenScenes.render(scene)
            write(actual, File(output, "${scene.name}.png"))

            val expected = loadGolden(scene.name)
            if (expected == null) {
                missing++
                lines += "${scene.name}: no committed golden"
                return@forEach
            }
            val difference = GoldenScenes.compare(expected, actual)
            if (difference.isIdentical) {
                identical++
                lines += "${scene.name}: identical"
            } else {
                worstDelta = maxOf(worstDelta, difference.maxChannelDelta)
                lines += "${scene.name}: $difference"
            }
        }

        val summary = buildString {
            appendLine("── golden tier comparison ─────────────────────────────")
            appendLine("device:     ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "(API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("scenes:     ${GoldenScenes.scenes().size}")
            appendLine("identical:  $identical")
            appendLine("different:  ${GoldenScenes.scenes().size - identical - missing}")
            appendLine("missing:    $missing")
            appendLine("worst per-channel delta: $worstDelta")
            appendLine()
            lines.forEach { appendLine("  $it") }
            appendLine()
            appendLine("images written to $output")
        }
        println(summary)
        File(output, "comparison.txt").writeText(summary)

        assertTrue(
            "no committed goldens were found in the test assets — run `./gradlew goldenTest " +
                "-Psprout.golden.regenerate=true` first",
            missing < GoldenScenes.scenes().size,
        )
    }

    /**
     * Loads a committed golden from the test APK's assets.
     *
     * The assets directory is wired to the JVM tier's `resources`, so both tiers read the very same
     * files. Two copies of a golden is two goldens that can disagree.
     */
    private fun loadGolden(name: String): Bitmap? = runCatching {
        instrumentation.context.assets.open("golden/$name.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun write(bitmap: Bitmap, file: File) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun inkedPixels(bitmap: Bitmap): Int {
        var inked = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) inked++
            }
        }
        return inked
    }
}
