package com.symmetricalpalmtree.sprout.canvas.tools

import androidx.annotation.RestrictTo
import com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen

/**
 * [SproutPen] → Supernote firmware pen codes, plus the honest fidelity of each result.
 *
 * Declared in core for the same reason as [OnyxPenTable]: plain integers, no vendor types, one
 * copy of the mapping.
 *
 * ### Why Supernote is the argument for splitting MARKER and HIGHLIGHTER
 *
 * On this platform they are genuinely different firmware pens — [NEEDLE] and [MARK] — and each is
 * a perfect match for its standardized name. On Onyx they are one style separated by width and
 * alpha. Had the two been merged into a single "marker", one name would mean an opaque flat pen on
 * one device and a translucent wash on another, which is precisely the vendor confusion this
 * vocabulary exists to remove.
 *
 * Internal to the library and its adapters.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object SupernotePenTable {

    /** Even-width firmware pen. The Supernote workhorse. */
    public const val NEEDLE: Int = 10

    /** A true highlighter — wide and translucent, leaving ink beneath it readable. */
    public const val MARK: Int = 11

    /** Chisel nib. */
    public const val CALLIGRAPHY: Int = 15

    /** Pressure-responsive fountain-style ink. */
    public const val INK: Int = 16

    /**
     * The firmware pen code for [pen], or `null` where the firmware has no equivalent and our own
     * software renderer draws the committed stroke.
     *
     * `null` only for [SproutPen.DASHED].
     *
     * Codes are confirmed on the **Nomad** (deviceType 3 / A5X2). The Manta shares the firmware and
     * the base chipset — only the screen size differs — so they are expected to carry over
     * unchanged, and Phase 5 verifies that rather than assuming it.
     */
    public fun penCode(pen: SproutPen): Int? = when (pen) {
        SproutPen.BALLPOINT -> NEEDLE
        SproutPen.FOUNTAIN -> INK
        SproutPen.BRUSH -> INK
        SproutPen.MARKER -> NEEDLE
        SproutPen.HIGHLIGHTER -> MARK
        // The firmware has no grain. NEEDLE keeps the live stroke honest about position and width;
        // the grain arrives when our renderer commits the stroke.
        SproutPen.PENCIL -> NEEDLE
        SproutPen.CHARCOAL -> NEEDLE
        SproutPen.CALLIGRAPHY -> CALLIGRAPHY
        SproutPen.DASHED -> null
    }

    /**
     * How faithfully a Supernote reproduces [pen].
     *
     * [SproutPen.HIGHLIGHTER] and [SproutPen.CALLIGRAPHY] are the platform's two clear wins — real
     * firmware pens that mean exactly what the standardized name means. The texture pens and the
     * dashed pen are the platform's weak spot: the firmware paints a clean line while the stylus is
     * down and the grain or the dashes only appear when the stroke commits, so what the user
     * watches appear is not what they end up with. That divergence is what
     * [PenFidelity.APPROXIMATE] is for.
     */
    public fun fidelity(pen: SproutPen): PenFidelity = when (pen) {
        SproutPen.BALLPOINT -> PenFidelity.NATIVE
        SproutPen.FOUNTAIN -> PenFidelity.NATIVE
        SproutPen.HIGHLIGHTER -> PenFidelity.NATIVE
        SproutPen.CALLIGRAPHY -> PenFidelity.NATIVE
        // INK is the fountain nib; a brush is that nib pushed harder, not its own firmware pen.
        SproutPen.BRUSH -> PenFidelity.EMULATED
        // NEEDLE is even-width, which is what a marker is — it is simply not a distinct pen here.
        SproutPen.MARKER -> PenFidelity.EMULATED
        SproutPen.PENCIL -> PenFidelity.APPROXIMATE
        SproutPen.CHARCOAL -> PenFidelity.APPROXIMATE
        SproutPen.DASHED -> PenFidelity.APPROXIMATE
    }
}
