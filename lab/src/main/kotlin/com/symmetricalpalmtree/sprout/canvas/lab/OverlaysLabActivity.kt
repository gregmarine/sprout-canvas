package com.symmetricalpalmtree.sprout.canvas.lab

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasListener
import com.symmetricalpalmtree.sprout.canvas.SproutCanvasView
import com.symmetricalpalmtree.sprout.canvas.model.InkStroke

/**
 * The Overlays screen: the direct visual proof of G7.
 *
 * ### What it is here to prove
 *
 * That the canvas never writes under the host's chrome, on any platform, at any placement. Four
 * overlays sit over the canvas — a floating toolbar, a bottom bar, a side panel and a centre popup —
 * and each can be toggled independently while writing continues.
 *
 * ### Why every overlay is registered once and then only shown or hidden
 *
 * A hidden view excludes nothing: the tracker watches visibility as well as layout, so a dismissed
 * popup stops reserving its area the moment it disappears. That is the behaviour worth proving,
 * because the alternative failure — a dead region on the canvas where a popup *used* to be — is
 * invisible until a user tries to write there and nothing happens.
 *
 * On a BOOX in Phase 4 the same screen answers a harder question, since there the exclusion is
 * enforced by the panel's own hardware limit rect rather than by our capture code.
 */
class OverlaysLabActivity : AppCompatActivity() {

    private lateinit var canvas: SproutCanvasView
    private lateinit var status: TextView

    private val overlays = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlays_lab)

        canvas = findViewById(R.id.canvas)
        status = findViewById(R.id.overlaysStatus)

        // Without this the stroke count sits at whatever it was when the screen last gained focus,
        // and a status line reading "strokes: 0" beside ink you can see is the kind of diagnostic
        // that sends someone hunting a bug in the canvas rather than in the label.
        canvas.listener = object : SproutCanvasListener {
            override fun onStrokeCompleted(stroke: InkStroke) = refreshStatus()
            override fun onStrokesRemoved(removed: List<InkStroke>) = refreshStatus()
            override fun onCanvasCleared(removed: List<InkStroke>) = refreshStatus()
        }

        register(R.id.floatingToolbar, "floating-toolbar")
        register(R.id.bottomBar, "bottom-bar")
        register(R.id.sidePanel, "side-panel")
        register(R.id.centrePopup, "centre-popup")

        toggle(R.id.toggleToolbar, R.id.floatingToolbar)
        toggle(R.id.toggleBottomBar, R.id.bottomBar)
        toggle(R.id.toggleSidePanel, R.id.sidePanel)
        toggle(R.id.togglePopup, R.id.centrePopup)

        findViewById<Button>(R.id.clearButton).setOnClickListener { canvas.clear() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Zone computation is coalesced into one posted pass per layout, and onResume runs before
        // the first layout — reporting from there alone shows "0 active" with the chrome plainly on
        // the canvas, which reads as a bug in the tracker rather than in the report.
        if (hasFocus) refreshStatus()
    }

    /** Registers a chrome view once. The library tracks its layout and visibility from here on. */
    private fun register(viewId: Int, id: String) {
        val overlay = findViewById<View>(viewId)
        overlays += overlay
        canvas.addExclusionZone(overlay, id)
    }

    private fun toggle(buttonId: Int, overlayId: Int) {
        findViewById<Button>(buttonId).setOnClickListener {
            val overlay = findViewById<View>(overlayId)
            overlay.visibility = if (overlay.isShown) View.GONE else View.VISIBLE
            // Read after the zones have actually settled. A plain `post` runs before the layout
            // pass the visibility change triggers, so the armed-zone count comes back one change
            // stale — which on a diagnostic screen reads as the tracker having missed the toggle.
            canvas.postDelayed(::refreshStatus, ZONE_SETTLE_MS)
        }
    }

    private fun refreshStatus() {
        status.text = getString(
            R.string.overlays_status,
            canvas.exclusionZoneCount,
            overlays.count { it.isShown },
            canvas.activeExclusionZones().size,
            canvas.strokeCount,
        )
    }

    private companion object {
        /** Long enough for a layout pass and the tracker's coalesced recomputation to both land. */
        const val ZONE_SETTLE_MS = 100L
    }
}
