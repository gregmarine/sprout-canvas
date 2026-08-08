package com.symmetricalpalmtree.sprout.canvas.geometry

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver

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
 * ### What it listens to, and why it is the view *tree*
 *
 * The obvious implementation watches each registered view's own layout. It is also wrong, and
 * wrong in a way that only shows up on a device: **a view set to `GONE` is never laid out**, so its
 * layout listener never fires and its zone stays armed after the chrome is dismissed. The canvas is
 * then left with a dead region exactly where a popup used to be — nothing on screen explains it and
 * no amount of tapping fixes it.
 *
 * So the tracker listens to the canvas's [ViewTreeObserver] instead. `OnGlobalLayoutListener` covers
 * hiding, showing, moving and resizing in one signal.
 *
 * It does not cover **`INVISIBLE`**, though the documentation's mention of visibility suggests it
 * should. Measured on a Wacom Movink Pad (API 34): `GONE` fires it and `INVISIBLE` does not, because
 * an invisible view keeps its space and the framework never schedules a layout pass. So a second,
 * deliberately cheap listener runs before each draw and compares each tracked view's [View.isShown]
 * against the last value seen — an int and a short parent walk per registered view, and a flush only
 * when one of them actually changed.
 *
 * ### Coalescing
 *
 * A layout pass fires that listener for the whole tree, and a screen full of chrome can change
 * several things at once. Recomputing and re-arming the engine for each would push several updates
 * for one visual change — and on hardware engines re-arming is not free. Changes set a dirty flag
 * and one recomputation is posted to the view.
 */
internal class ExclusionZoneTracker(
    private val canvas: View,
    private val onZonesChanged: () -> Unit,
) {

    private class Entry(val trackedView: View?, val staticRect: Rect?) {
        /** The last visibility seen, so a change can be spotted without recomputing any geometry. */
        var wasShown: Boolean = trackedView?.isShown ?: true
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val canvasLocation = IntArray(2)
    private val viewLocation = IntArray(2)

    private var flushPosted = false

    /**
     * Whether the tree observer is currently installed.
     *
     * Tracked rather than assumed, because a canvas can attach and detach repeatedly — a fragment
     * in a pager, a recycled list row — and adding the listener twice would fan one layout pass out
     * into a growing pile of duplicate work.
     */
    private var listenerAttached = false

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { postFlush() }

    /**
     * Catches the visibility changes a layout pass does not report — see the class KDoc.
     *
     * Always returns true: this observes, and must never delay or cancel a frame.
     */
    private val visibilityListener = ViewTreeObserver.OnPreDrawListener {
        var changed = false
        for (entry in entries.values) {
            val view = entry.trackedView ?: continue
            val shown = view.isShown
            if (shown != entry.wasShown) {
                entry.wasShown = shown
                changed = true
            }
        }
        if (changed) postFlush()
        true
    }

    /** Views registered so far. */
    val size: Int get() = entries.size

    /** Registers [view], tracking its bounds and visibility until it is removed. */
    fun addView(id: String, view: View) {
        remove(id)
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
        entries.remove(id) ?: return false
        postFlush()
        return true
    }

    /** Removes every registration. */
    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        postFlush()
    }

    /** Stops observing without notifying. Called when the canvas leaves its window. */
    fun releaseListeners() {
        if (!listenerAttached) return
        listenerAttached = false
        canvas.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        canvas.viewTreeObserver.removeOnPreDrawListener(visibilityListener)
    }

    /**
     * Starts observing the canvas's view tree. Called when the canvas joins a window.
     *
     * The observer is only meaningful while attached: a detached view hands back a floating
     * observer that is merged into the real one later, and a listener added to it would be silently
     * dropped.
     */
    fun reattachListeners() {
        if (listenerAttached) return
        listenerAttached = true
        canvas.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        canvas.viewTreeObserver.addOnPreDrawListener(visibilityListener)
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
