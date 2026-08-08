package com.symmetricalpalmtree.sprout.canvas.lab

import android.app.Application
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas

/**
 * The one piece of host cooperation sprout-canvas asks for (PLAN.md D11).
 *
 * The Lab does it the way every host app should: one call, in `Application.onCreate`, before any
 * canvas exists. Skipping it is survivable — the canvas falls back to the software engine and logs
 * a clear error — but on a BOOX or a Supernote it means giving up the firmware ink path, which is
 * the entire reason those devices feel different to write on.
 */
class LabApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SproutCanvas.initialize(this)
        // Before any canvas is inflated: a canvas reads the committed-layer renderer when its
        // engine attaches, so a later restore would miss the first screen opened (LabSettings).
        LabSettings.restore(this)
    }
}
