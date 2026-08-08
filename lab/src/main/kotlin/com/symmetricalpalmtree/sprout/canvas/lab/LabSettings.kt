package com.symmetricalpalmtree.sprout.canvas.lab

import android.content.Context
import com.symmetricalpalmtree.sprout.canvas.onyx.OnyxRenderMode

/**
 * The handful of harness choices that have to outlive the process.
 *
 * ### Why this exists at all
 *
 * [OnyxRenderMode.current] is process-global state, so it resets to its default every time the
 * process dies — including on every `adb install`. During a device session that is worse than
 * inconvenient, it is actively misleading: the tester selects the SDK renderers, a rebuild is
 * installed to fix something unrelated, and every comparison afterwards is silently measuring the
 * *other* path. The session's conclusions are then wrong in a way nothing on screen contradicts.
 *
 * Found exactly that way. A setting that quietly reverts is worse than no setting.
 */
internal object LabSettings {

    private const val FILE = "sprout-canvas-lab"
    private const val KEY_RENDER_MODE = "onyx.render.mode"

    /**
     * Restores every saved choice. Called from `Application.onCreate`, before any canvas exists —
     * a canvas reads the render mode when its engine attaches, so restoring it later would be too
     * late for the first screen the tester opens.
     */
    fun restore(context: Context) {
        val saved = prefs(context).getString(KEY_RENDER_MODE, null) ?: return
        OnyxRenderMode.current = runCatching { OnyxRenderMode.Mode.valueOf(saved) }
            .getOrDefault(OnyxRenderMode.Mode.SOFTWARE)
    }

    /** Applies and remembers the committed-layer renderer. */
    fun setRenderMode(context: Context, mode: OnyxRenderMode.Mode) {
        OnyxRenderMode.current = mode
        prefs(context).edit().putString(KEY_RENDER_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
