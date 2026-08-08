package com.symmetricalpalmtree.sprout.canvas.onyx

import com.symmetricalpalmtree.sprout.canvas.SproutLog
import kotlin.math.abs

/**
 * Puts the SDK's per-point timestamps onto the clock the library documents.
 *
 * ### The mismatch, measured on a NoteAir5C
 *
 * [com.symmetricalpalmtree.sprout.canvas.model.StrokeSamples.timestampMs] is documented as
 * `SystemClock.uptimeMillis`, which is true by construction on the generic engine because it reads
 * `MotionEvent.getEventTime`. The Onyx SDK's `TouchPoint.timestamp` carries **`System.currentTimeMillis`**
 * instead — a fact nothing in the SDK states, and one that a single captured stroke settles beyond
 * doubt: on a device that has been up for a few hours the two clocks are apart by roughly the age of
 * the Unix epoch.
 *
 * ### Why convert rather than redefine
 *
 * Leaving the raw value in place and loosening the model's documentation to "engine-defined" would
 * push the problem onto every host app, and push it there silently: intervals *within* a stroke are
 * identical under either clock, so a host computing stroke duration would work perfectly and a host
 * comparing a stroke's timestamps against anything else on the device would be wrong by years. One
 * clock, stated once, honoured by every engine.
 *
 * ### Why the clock is detected rather than assumed
 *
 * This library targets five BOOX firmware families, and the SDK documents neither behaviour. A
 * conversion applied unconditionally would corrupt the timestamps on any device that already
 * reports uptime — turning a correct engine into a broken one on hardware nobody had to hand. The
 * two candidates are separated by orders of magnitude, so telling them apart needs one subtraction
 * and cannot realistically be got wrong.
 */
internal class TimestampClock {

    /** Which clock the SDK's timestamps are on. */
    enum class Clock {
        /** What the library documents. Passed through untouched. */
        UPTIME,

        /** `System.currentTimeMillis`. Shifted onto the uptime clock. */
        WALL,
    }

    /** The clock in force, or null until a point with a usable timestamp has arrived. */
    var clock: Clock? = null
        private set

    /**
     * Converts one raw timestamp to the documented clock.
     *
     * @param raw the SDK's value.
     * @param uptimeNow `SystemClock.uptimeMillis()`, read once per batch by the caller.
     * @param wallNow `System.currentTimeMillis()`, read once per batch by the caller.
     *
     * Both "now" values are parameters rather than read here so that every point in a batch is
     * shifted by the identical offset. Reading the clocks per point would make the conversion itself
     * a source of jitter, in the one channel whose entire purpose is measuring time.
     */
    fun normalize(raw: Long, uptimeNow: Long, wallNow: Long): Long {
        // A point with no timestamp at all is stamped with the moment it was converted. Shifting it
        // as if it were a wall-clock reading would put it decades before the device booted.
        if (raw <= 0L) return uptimeNow

        val decided = clock ?: decide(raw, uptimeNow, wallNow)
        return when (decided) {
            Clock.UPTIME -> raw
            Clock.WALL -> raw - wallNow + uptimeNow
        }
    }

    private fun decide(raw: Long, uptimeNow: Long, wallNow: Long): Clock {
        val decided = if (abs(raw - wallNow) < abs(raw - uptimeNow)) Clock.WALL else Clock.UPTIME
        clock = decided
        SproutLog.d {
            "onyx timestamps are on the " +
                if (decided == Clock.WALL) {
                    "wall clock; shifting them onto uptimeMillis"
                } else {
                    "uptime clock, as documented"
                }
        }
        return decided
    }
}
