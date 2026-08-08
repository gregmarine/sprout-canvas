package com.symmetricalpalmtree.sprout.canvas.onyx

import androidx.annotation.VisibleForTesting
import com.symmetricalpalmtree.sprout.canvas.SproutLog

/**
 * Which canvas currently owns the BOOX raw-drawing pipeline — the whole process, not this view.
 *
 * ### The bug this exists to prevent
 *
 * On BOOX the raw-drawing pipeline is a **single process-global hardware resource.** Every canvas
 * builds its own `TouchHelper`, but only one of them can hold the pipeline at a time.
 *
 * Android opens an incoming screen's surface *before* closing the outgoing one. So a canvas that
 * closes the pipeline during its own teardown tears down the session the *new* canvas has already
 * claimed — and the visible screen silently stops accepting the stylus until a focus cycle re-arms
 * it. It is intermittent, because the incoming open is asynchronous while the outgoing close is
 * synchronous, which is the worst shape a bug can have.
 *
 * ### The guard
 *
 * Every close is a *close-if-still-owner*. A superseded canvas skips the global close and drops only
 * its own local state; the current owner manages its own teardown.
 *
 * ### Why the reference project had to do this five times and this library does it once
 *
 * In Notesprout the same guard had to be hand-threaded through five Activities, because each screen
 * owned its own drawing view and its own lifecycle. Here it lives inside the adapter, below the
 * public API, and a host app is never asked to know the hazard exists. That is one of the strongest
 * reasons for this library to exist at all (PLAN.md §5.2).
 *
 * ### Threading
 *
 * All access is on the main thread — view and lifecycle callbacks. `@Volatile` is belt and braces
 * for the case where a vendor callback arrives on another thread before it hops back.
 *
 * Deliberately holds an opaque [Any] rather than an engine type: ownership is an ordering question,
 * not a drawing one, and keeping it free of SDK and engine types makes the state machine testable
 * on the JVM, where the hazard's ordering can actually be reproduced.
 */
internal object OnyxPenOwner {

    @Volatile
    private var owner: Any? = null

    /** True when [candidate] holds the pipeline. */
    fun isOwner(candidate: Any): Boolean = owner === candidate

    /** True when anybody holds the pipeline. */
    fun isHeld(): Boolean = owner != null

    /**
     * Records [candidate] as the owner, superseding whoever held it.
     *
     * Called immediately after a successful open. Any earlier owner's pending close is neutralised
     * by [releaseIfOwner] from that moment on.
     */
    fun claim(candidate: Any) {
        if (owner === candidate) return
        val previous = owner
        owner = candidate
        SproutLog.d {
            if (previous == null) "onyx pen pipeline claimed" else "onyx pen pipeline taken over"
        }
    }

    /**
     * Runs [close] only if [candidate] still owns the pipeline, and gives up ownership if it did.
     *
     * @return true when the close actually happened.
     *
     * The `false` return is the interesting one: it means a newer canvas has the pipeline and this
     * teardown was about to kill a live session.
     */
    fun releaseIfOwner(candidate: Any, close: () -> Unit): Boolean {
        if (owner !== candidate) {
            SproutLog.d { "onyx close skipped — another canvas owns the pipeline" }
            return false
        }
        owner = null
        close()
        return true
    }

    /** Restores the pristine state. Tests only. */
    @VisibleForTesting
    fun resetForTesting() {
        owner = null
    }
}
