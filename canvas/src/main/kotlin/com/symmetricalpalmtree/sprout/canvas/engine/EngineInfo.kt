package com.symmetricalpalmtree.sprout.canvas.engine

/**
 * Identity and selection weight for an ink engine.
 *
 * @see EngineRegistry
 */
public data class EngineInfo(

    /**
     * A stable, lowercase identifier — `"generic"`, `"onyx"`, `"supernote"`.
     *
     * Stable because it is written into every [com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo],
     * exported in conformance reports, and accepted by
     * [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView.enginePreference] and the
     * `app:sproutEngine` XML attribute. Renaming one invalidates old capture data.
     */
    public val id: String,

    /** A human-readable name for a device report or an engine picker. */
    public val displayName: String,

    /**
     * Selection weight — the supported factory with the highest priority wins.
     *
     * Vendor adapters sit above the generic engine so that installing one is enough to get the
     * hardware path. The generic engine is deliberately the lowest, and is always supported.
     */
    public val priority: Int,
) {

    init {
        require(id.isNotEmpty()) { "an engine id must not be empty" }
    }

    public companion object {
        /** Priority of the always-available software engine. Nothing may sit below it. */
        public const val PRIORITY_GENERIC: Int = 0

        /** Priority of a hardware-backed vendor adapter. */
        public const val PRIORITY_VENDOR: Int = 100
    }
}

/**
 * The engine ids this library ships or expects, as constants.
 *
 * Available in core so the harness, the XML `app:sproutEngine` attribute and tests can name an
 * engine without depending on the adapter module that implements it — a phone-only app can still
 * write `enginePreference = EngineIds.GENERIC`.
 */
public object EngineIds {

    /** The software engine in `:canvas`. Always available, always last. */
    public const val GENERIC: String = "generic"

    /** The BOOX adapter in `:canvas-onyx`. */
    public const val ONYX: String = "onyx"

    /** The Supernote adapter in `:canvas-supernote`. */
    public const val SUPERNOTE: String = "supernote"

    /** The inert stub engine — captures nothing, draws nothing. */
    public const val NO_OP: String = "no-op"
}
