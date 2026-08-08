package com.symmetricalpalmtree.sprout.canvas.engine

import android.content.Context

/**
 * Creates an [InkEngine], and decides whether it belongs on this device.
 *
 * A vendor adapter ships exactly one of these, registered with [EngineRegistry] — automatically by
 * discovery, or manually by a host that would rather be explicit.
 *
 * Implementations should be stateless and cheap to construct; [isSupported] may be called before
 * any canvas exists.
 */
public interface InkEngineFactory {

    /** Identity and selection weight. Matches the engine's [InkEngine.info]. */
    public val info: EngineInfo

    /**
     * Whether this engine can run here — **probed, never guessed**.
     *
     * A brand or manufacturer string is a cheap pre-filter, not an answer. The authority is
     * whether the thing the engine actually needs is present and responds:
     *
     *  - **Onyx** — `Build.MANUFACTURER` contains `"onyx"` *and* the SDK classes resolve.
     *  - **Supernote** — brand ≈ `ratta` as a pre-filter, *then* the firmware ink binder actually
     *    answers a `ServiceManager` lookup. A Supernote whose firmware lacks the service must land
     *    on the generic engine, not on a hardware path that will fail silently.
     *  - **Generic** — always `true`.
     *
     * Must not throw. A factory that cannot determine its own support returns `false`; a device
     * that falls back to the generic engine still draws.
     */
    public fun isSupported(context: Context): Boolean

    /** Builds an engine bound to [host]. Called once per canvas view. */
    public fun create(host: InkEngineHost): InkEngine
}
