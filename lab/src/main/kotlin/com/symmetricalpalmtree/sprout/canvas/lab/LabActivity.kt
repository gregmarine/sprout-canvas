package com.symmetricalpalmtree.sprout.canvas.lab

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas

/**
 * Sprout Canvas Lab — the home screen (PLAN.md §4.3).
 *
 * ### Why a list of screens rather than one long page
 *
 * The Lab is a conformance harness that happens to be a demo, not the reverse, and each of its
 * screens asks a different question of the library. Giving each its own Activity keeps those
 * questions from interfering: the Canvas screen can be full-bleed and resizable, the Overlays screen
 * can cover the canvas in chrome, and — the reason that matters later — two canvases in two
 * Activities is exactly the shape of the process-global pipeline handoff that has to be proved safe
 * on BOOX in Phase 4 (PLAN.md §5.2).
 *
 * This class keeps its name because the device tooling launches it by name. Renaming it would
 * silently break every install script in `.claude/skills/device-build-install`.
 */
class LabActivity : AppCompatActivity() {

    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)

        findViewById<TextView>(R.id.libraryVersion).text =
            getString(R.string.library_version, SproutCanvas.VERSION)
        summary = findViewById(R.id.engineSummary)

        open(R.id.openCanvas, CanvasLabActivity::class.java)
        open(R.id.openTools, ToolsLabActivity::class.java)
        open(R.id.openOverlays, OverlaysLabActivity::class.java)
        open(R.id.openDeviceReport, DeviceReportActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        val engines = SproutCanvas.availableEngines(this)
        summary.text = buildString {
            appendLine(getString(R.string.summary_initialized, SproutCanvas.isInitialized.toString()))
            append(
                getString(
                    R.string.summary_engines,
                    engines.joinToString().ifEmpty { getString(R.string.summary_no_adapters) },
                ),
            )
        }
    }

    private fun open(buttonId: Int, screen: Class<out AppCompatActivity>) {
        findViewById<Button>(buttonId).setOnClickListener {
            startActivity(Intent(this, screen))
        }
    }
}
