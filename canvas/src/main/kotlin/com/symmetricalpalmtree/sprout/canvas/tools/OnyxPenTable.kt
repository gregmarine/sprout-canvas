package com.symmetricalpalmtree.sprout.canvas.tools

import androidx.annotation.RestrictTo
import com.symmetricalpalmtree.sprout.canvas.engine.PenFidelity
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen

/**
 * [SproutPen] → the two Onyx constants a BOOX needs, plus the honest fidelity of each result.
 *
 * ### Why this lives in core rather than in `:canvas-onyx`
 *
 * The table is *standardization*, not vendor code. It carries no Onyx types — only plain integers —
 * so `:canvas` keeps its zero-vendor-dependency rule, while the adapter consumes the mapping
 * instead of redefining it. A mapping that existed in two places would drift, and the drift would
 * be invisible: both copies would still compile, and one of them would quietly draw the wrong pen.
 *
 * ### Why there are two constants per pen
 *
 * A BOOX draws each stroke twice, through two unrelated code paths:
 *
 *  - **Path A, live** — the firmware overlay, armed with an *overlay style* ([overlayStyle]).
 *  - **Path B, committed** — our own re-render after pen-up, which may use the SDK's `NeoPen`
 *    renderers ([neoPenType]) to stay close to what the user just watched appear.
 *
 * The user sees path A while writing and path B forever after, so the two must agree. Keeping them
 * agreeing is a standing requirement of every rendering change, not a one-off task.
 *
 * ### The naming trap this table closes
 *
 * Onyx's constants do not mean what they say. `STROKE_STYLE_PENCIL` (0) is a plain even line —
 * BOOX's own UI calls it *Pen* — while the grainy pencil a user would point at is internally
 * *charcoal*, and "Ballpoint" is internally *oily pen*. Read the [SproutPen] column, never the
 * Onyx one.
 *
 * Internal to the library and its adapters. Apps read
 * [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.fidelity] instead.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object OnyxPenTable {

    // --- Overlay styles: TouchHelper.setStrokeStyle (path A, live firmware ink) ----------------
    //
    // A five-device survey found all nine of these render on all five BOOX devices tested, with
    // zero silent failures. Styles 9-15 are ignored by the firmware; the style space really is
    // closed at nine.

    /** A plain even line. **Not a pencil** — BOOX's own UI labels this one "Pen". */
    public const val STROKE_STYLE_PENCIL: Int = 0

    /** Pressure-responsive fountain nib. */
    public const val STROKE_STYLE_FOUNTAIN: Int = 1

    /** Flat even-width marker. */
    public const val STROKE_STYLE_MARKER: Int = 2

    /** Heavier pressure-responsive brush. */
    public const val STROKE_STYLE_NEO_BRUSH: Int = 3

    /** Grainy. This is what a user would call a pencil. */
    public const val STROKE_STYLE_CHARCOAL: Int = 4

    /** Dashed line. */
    public const val STROKE_STYLE_DASH: Int = 5

    /** Heavier grain than [STROKE_STYLE_CHARCOAL]. */
    public const val STROKE_STYLE_CHARCOAL_V2: Int = 6

    /** Chisel nib. */
    public const val STROKE_STYLE_SQUARE_PEN: Int = 7

    // --- NeoPen types: the SDK's software renderers (path B, committed content) -----------------

    /** `NEOPEN_PEN_TYPE_BRUSH`. */
    public const val NEOPEN_PEN_TYPE_BRUSH: Int = 1

    /** `NEOPEN_PEN_TYPE_MARKER`. */
    public const val NEOPEN_PEN_TYPE_MARKER: Int = 3

    /** `NEOPEN_PEN_TYPE_CHARCOAL_V2`. */
    public const val NEOPEN_PEN_TYPE_CHARCOAL_V2: Int = 5

    /** `NEOPEN_PEN_TYPE_FOUNTAIN_V2`. */
    public const val NEOPEN_PEN_TYPE_FOUNTAIN_V2: Int = 6

    /** `NEOPEN_PEN_TYPE_PENCIL`. */
    public const val NEOPEN_PEN_TYPE_PENCIL: Int = 7

    /** `NEOPEN_PEN_TYPE_BALLPOINT`. */
    public const val NEOPEN_PEN_TYPE_BALLPOINT: Int = 8

    /** `NEOPEN_PEN_TYPE_SQUARE`. */
    public const val NEOPEN_PEN_TYPE_SQUARE: Int = 9

    /**
     * The firmware overlay style for [pen] — path A, the ink the user watches appear.
     *
     * Every pen maps to a style: there is no "no live ink" case on a BOOX.
     */
    public fun overlayStyle(pen: SproutPen): Int = when (pen) {
        SproutPen.BALLPOINT -> STROKE_STYLE_PENCIL
        SproutPen.FOUNTAIN -> STROKE_STYLE_FOUNTAIN
        SproutPen.BRUSH -> STROKE_STYLE_NEO_BRUSH
        SproutPen.MARKER -> STROKE_STYLE_MARKER
        // No highlighter style exists. MARKER, widened and given alpha, is the highlighter.
        SproutPen.HIGHLIGHTER -> STROKE_STYLE_MARKER
        SproutPen.PENCIL -> STROKE_STYLE_CHARCOAL
        SproutPen.CHARCOAL -> STROKE_STYLE_CHARCOAL_V2
        SproutPen.CALLIGRAPHY -> STROKE_STYLE_SQUARE_PEN
        SproutPen.DASHED -> STROKE_STYLE_DASH
    }

    /**
     * The SDK `NeoPen` renderer for [pen] — path B, the committed stroke — or `null` where the SDK
     * has no equivalent and our own software renderer draws it.
     *
     * `null` only for [SproutPen.DASHED]: the firmware dashes live ink, but the SDK ships no dashed
     * software pen, so the committed stroke is dashed by us.
     */
    public fun neoPenType(pen: SproutPen): Int? = when (pen) {
        SproutPen.BALLPOINT -> NEOPEN_PEN_TYPE_BALLPOINT
        SproutPen.FOUNTAIN -> NEOPEN_PEN_TYPE_FOUNTAIN_V2
        SproutPen.BRUSH -> NEOPEN_PEN_TYPE_BRUSH
        SproutPen.MARKER -> NEOPEN_PEN_TYPE_MARKER
        SproutPen.HIGHLIGHTER -> NEOPEN_PEN_TYPE_MARKER
        SproutPen.PENCIL -> NEOPEN_PEN_TYPE_PENCIL
        SproutPen.CHARCOAL -> NEOPEN_PEN_TYPE_CHARCOAL_V2
        SproutPen.CALLIGRAPHY -> NEOPEN_PEN_TYPE_SQUARE
        SproutPen.DASHED -> null
    }

    /**
     * How much BOOX scales a pen's nominal width, so pens *feel* like the width that was asked for.
     *
     * These multipliers are not cosmetic. Texture pens need real width or their grain has no room
     * to exist: a charcoal at nominal width 8 renders solid and grainless, and only shows its
     * texture around 32. A library that passed the nominal width straight through would ship a
     * charcoal pen that looks exactly like a ballpoint and gives no hint why.
     *
     * Only the two multipliers observed in the reference project are encoded. Everything else is
     * `1.0` until measured — a guessed multiplier is worse than none, because it looks deliberate.
     */
    public fun widthMultiplier(pen: SproutPen): Float = when (pen) {
        SproutPen.CHARCOAL -> 5.0f
        SproutPen.BRUSH -> 2.0f
        else -> 1.0f
    }

    /**
     * How faithfully a BOOX reproduces [pen].
     *
     * Two pens are below native, for two different reasons:
     *
     *  - **[SproutPen.HIGHLIGHTER]** — there is no highlighter style to arm; it is
     *    [SproutPen.MARKER] widened and given alpha.
     *
     *    The open question was whether the firmware overlay would honour that alpha in live
     *    preview, and the NoteAir5C answered it in two parts. First: **the overlay paints nothing
     *    at all for a translucent colour.** Not a solid stroke, not a wrong colour; the pen moves
     *    and no ink appears, with no error anywhere. It is the alpha and not the width — a
     *    highlighter at 0.5 dp is equally invisible. So the adapter forces the *preview* opaque
     *    while the committed stroke keeps its translucency
     *    ([com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities.livePreviewSupportsAlpha]).
     *
     *    Second, and the reason this stayed `EMULATED` rather than dropping to
     *    [PenFidelity.APPROXIMATE]: on the Kaleido panel that forced-opaque band **still reads as a
     *    true highlight**. Ink underneath stays visible the whole time the pen is down, and pen-up
     *    produces no visible change at all. The disagreement the compensation should have created
     *    does not appear, so reporting one would be the inaccuracy.
     *
     *    *Measured on a colour panel. A mono panel renders that same band as flat grey and may well
     *    cover the ink beneath it, which would make this `APPROXIMATE` there — so this is a fidelity
     *    that may yet need to vary by panel rather than by vendor.*
     *
     *  - **[SproutPen.DASHED]** — the firmware dashes live ink but the SDK has no dashed software
     *    pen, so the committed stroke is ours. The lower of the two paths is what gets reported.
     */
    public fun fidelity(pen: SproutPen): PenFidelity = when (pen) {
        SproutPen.HIGHLIGHTER -> PenFidelity.EMULATED
        SproutPen.DASHED -> PenFidelity.EMULATED
        else -> PenFidelity.NATIVE
    }
}
