package com.symmetricalpalmtree.sprout.canvas

import android.util.Log
import androidx.annotation.RestrictTo

/**
 * The library's logging chokepoint.
 *
 * ### Why not `Log.d` directly
 *
 * A library's debug logging is paid for by every app that ships it. [d] is `inline` and takes the
 * message as a lambda, so when [SproutCanvas.debugLogging] is off the string is never built and the
 * call collapses to a branch on a boolean. Diagnostics that cost nothing when disabled are
 * diagnostics you can afford to leave in.
 *
 * Warnings and errors always log. A host that skipped [SproutCanvas.initialize] and silently lost
 * the hardware ink path must be *told*, not left to wonder why a BOOX writes like a phone.
 *
 * Everything carries one stable tag, so a device session is greppable:
 * `adb logcat -s SproutCanvas`.
 *
 * Internal to the library and its vendor adapters — not part of the app-facing API.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object SproutLog {

    /** The single logcat tag every message in the library carries. */
    public const val TAG: String = "SproutCanvas"

    /** Logs a debug message, building it only when [SproutCanvas.debugLogging] is on. */
    public inline fun d(message: () -> String) {
        if (SproutCanvas.debugLogging) Log.d(TAG, message())
    }

    /** Logs a warning. Always emitted. */
    public fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    /** Logs an error. Always emitted. */
    public fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
