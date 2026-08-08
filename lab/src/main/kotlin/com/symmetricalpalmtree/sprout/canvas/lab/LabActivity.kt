package com.symmetricalpalmtree.sprout.canvas.lab

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas

/**
 * Sprout Canvas Lab — the conformance harness (PLAN.md §4.3).
 *
 * This is the durable regression instrument, not a throwaway demo. It grows one screen per phase:
 * Canvas and Overlays and Device report in Phase 2, Tools in Phase 3, Data and Conformance run in
 * Phase 6.
 *
 * Phase 0 is a launch target only: it proves the app builds, installs, and is findable by name on a
 * device, and it reports the library version so a device session can always name the build it ran.
 */
class LabActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)

        findViewById<android.widget.TextView>(R.id.libraryVersion).text =
            getString(R.string.library_version, SproutCanvas.VERSION)
    }
}
