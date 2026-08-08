package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.Rect
import android.view.View

/**
 * Keeps the canvas's exclusion zones up to date as the host's chrome moves.
 *
 * ### The ergonomic win
 *
 * A host registers a `View` — its floating toolbar — and never thinks about geometry again. The
 * tracker watches that view's layout and visibility, maps its bounds into canvas coordinates on
 * every change, and re-arms the engine. The reference project hand-computed a union rect for every
 * piece of chrome and recomputed it at each call site that could move something, which is a
 * maintenance burden that grows with the UI and fails silently when a new panel forgets to join in.
 *
 * Manual rects are still accepted, for chrome that is not a `View` at all.
 *
 * ### Coalescing
 *
 * A layout pass can fire the listener once per registered view. Recomputing and re-arming the
 * engine for each of them would push several updates for one visual change — and on hardware
 * engines re-arming is not free. Changes set a dirty flag and one recomputation is posted to the
 * view.
 */
internal class ExclusionZoneTracker(
    private val canvas: View,
    private val onZonesChanged: () -> Unit,
) {

    private class Entry(val trackedView: View?, val staticRect: Rect?)

    private val entries = LinkedHashMap<String, Entry>()
    private val canvasLocation = IntArray(2)
    private val viewLocation = IntArray(2)

    private var flushPosted = false

    /**
     * Whether the layout listeners are currently installed.
     *
     * [View.addOnLayoutChangeListener] does not de-duplicate, so a canvas that attaches and
     * detaches repeatedly — a fragment in a pager, a recycled list row — would accumulate a copy of
     * the listener per cycle and fan one layout change out into a growing pile of work.
     */
    private var listenersAttached = true

    private val layoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> postFlush() }

    /** Views registered so far. */
    val size: Int get() = entries.size

    /** Registers [view], tracking its bounds and visibility until it is removed. */
    fun addView(id: String, view: View) {
        remove(id)
        if (listenersAttached) view.addOnLayoutChangeListener(layoutListener)
        entries[id] = Entry(trackedView = view, staticRect = null)
        postFlush()
    }

    /** Registers a fixed rectangle in canvas coordinates. */
    fun addRect(id: String, rect: Rect) {
        remove(id)
        entries[id] = Entry(trackedView = null, staticRect = Rect(rect))
        postFlush()
    }

    /** Removes a registration. Returns true if one existed. */
    fun remove(id: String): Boolean {
        val removed = entries.remove(id) ?: return false
        removed.trackedView?.removeOnLayoutChangeListener(layoutListener)
        postFlush()
        return true
    }

    /** Removes every registration. */
    fun clear() {
        if (entries.isEmpty()) return
        entries.values.forEach { it.trackedView?.removeOnLayoutChangeListener(layoutListener) }
        entries.clear()
        postFlush()
    }

    /** Detaches every listener without notifying. Called when the canvas leaves its window. */
    fun releaseListeners() {
        if (!listenersAttached) return
        listenersAttached = false
        entries.values.forEach { it.trackedView?.removeOnLayoutChangeListener(layoutListener) }
    }

    /** Re-attaches every listener. Called when the canvas returns to a window. */
    fun reattachListeners() {
        if (listenersAttached) return
        listenersAttached = true
        entries.values.forEach { it.trackedView?.addOnLayoutChangeListener(layoutListener) }
    }

    /**
     * The current zones, in canvas coordinates, clipped to the canvas and coalesced.
     *
     * A view that is not [View.isShown] contributes nothing: chrome that is hidden is not covering
     * anything, and continuing to exclude the area under a dismissed popup would leave a dead
     * region on the canvas that no amount of tapping explains.
     */
    fun computeZones(): List<Rect> {
        if (entries.isEmpty()) return emptyList()
        canvas.getLocationOnScreen(canvasLocation)
        val mapped = ArrayList<Rect>(entries.size)
        for (entry in entries.values) {
            val zone = when {
                entry.staticRect != null -> Rect(entry.staticRect)
                entry.trackedView != null -> {
                    val view = entry.trackedView
                    if (!view.isShown || view.width == 0 || view.height == 0) continue
                    view.getLocationOnScreen(viewLocation)
                    CanvasGeometry.mapToCanvas(
                        viewLeftOnScreen = viewLocation[0],
                        viewTopOnScreen = viewLocation[1],
                        viewWidth = view.width,
                        viewHeight = view.height,
                        canvasLeftOnScreen = canvasLocation[0],
                        canvasTopOnScreen = canvasLocation[1],
                    )
                }

                else -> continue
            }
            CanvasGeometry.clipToCanvas(zone, canvas.width, canvas.height)?.let { mapped += it }
        }
        return CanvasGeometry.coalesce(mapped)
    }

    /** Forces a recomputation on the next flush — for a layout or size change on the canvas itself. */
    fun invalidate() {
        postFlush()
    }

    private fun postFlush() {
        if (flushPosted) return
        flushPosted = true
        canvas.post {
            flushPosted = false
            onZonesChanged()
        }
    }
}
