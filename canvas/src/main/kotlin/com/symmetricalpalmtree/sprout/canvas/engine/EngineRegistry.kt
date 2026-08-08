package com.symmetricalpalmtree.sprout.canvas.engine

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.symmetricalpalmtree.sprout.canvas.SproutLog

/**
 * Knows which [InkEngineFactory]s exist and picks one for a canvas.
 *
 * ### Selection order
 *
 * 1. **An explicit override**, if the canvas set one — [com.symmetricalpalmtree.sprout.canvas.SproutCanvasView.enginePreference]
 *    or the `app:sproutEngine` XML attribute. This is not a convenience: the conformance harness
 *    *requires* it, because judging whether the hardware and software ink paths agree means forcing
 *    a BOOX onto the generic engine and drawing the same stroke twice.
 * 2. **Registered factories by descending [EngineInfo.priority]**, first whose
 *    [InkEngineFactory.isSupported] returns true.
 * 3. **The fallback engine** — always supported, always last.
 *
 * ### Discovery
 *
 * Vendor adapters are found by reflection over a fixed list of factory class names, so that adding
 * `canvas-onyx` to a build is the only step a host takes. Each adapter ships a `consumer-rules.pro`
 * keep rule for its factory, and AGP applies an AAR's consumer rules automatically, so R8 cannot
 * strip the class the lookup depends on.
 *
 * [register] is the escape hatch for a host that would rather be explicit, or for a third-party
 * engine this list has never heard of.
 */
public object EngineRegistry {

    /**
     * Factory class names probed at [com.symmetricalpalmtree.sprout.canvas.SproutCanvas.initialize].
     *
     * A fixed list, deliberately: it is the simplest thing that works and the easiest to debug when
     * an adapter does not turn up — the failure is one `Class.forName` away, not buried in a
     * service-loader manifest merge.
     */
    private val ADAPTER_FACTORY_CLASSES = listOf(
        "com.symmetricalpalmtree.sprout.canvas.onyx.OnyxInkEngineFactory",
        "com.symmetricalpalmtree.sprout.canvas.supernote.SupernoteInkEngineFactory",
    )

    private val factories = ArrayList<InkEngineFactory>()

    private var discovered = false

    /**
     * The engine used when nothing else is supported. Always supported by definition.
     *
     * [NoOpInkEngineFactory] until Phase 2 replaces it with the generic software engine.
     */
    @VisibleForTesting
    internal var fallbackFactory: InkEngineFactory = NoOpInkEngineFactory

    /**
     * Registers a factory, replacing any earlier one with the same [EngineInfo.id].
     *
     * Safe to call more than once with the same factory — re-registering is not an error, because a
     * host that registers manually should not have to know whether discovery already found it.
     */
    @MainThread
    public fun register(factory: InkEngineFactory) {
        factories.removeAll { it.info.id == factory.info.id }
        factories += factory
        SproutLog.d { "registered engine factory '${factory.info.id}' (priority ${factory.info.priority})" }
    }

    /** Removes the factory with this [EngineInfo.id]. Returns true if one was removed. */
    @MainThread
    public fun unregister(id: String): Boolean = factories.removeAll { it.info.id == id }

    /** Every registered factory, highest priority first. Does not include the fallback. */
    @MainThread
    public fun registeredFactories(): List<InkEngineFactory> =
        factories.sortedByDescending { it.info.priority }

    /**
     * Picks the factory for a canvas.
     *
     * @param preferredEngineId an [EngineInfo.id] to force. When it names an engine that is absent
     *   or reports itself unsupported here, the preference is **logged and ignored** rather than
     *   honoured into a broken canvas — the harness routinely asks for engines a given device does
     *   not have, and a wrong answer there should be visible, not fatal.
     */
    @MainThread
    public fun select(context: Context, preferredEngineId: String? = null): InkEngineFactory {
        if (preferredEngineId != null) {
            val preferred = (factories + fallbackFactory).firstOrNull { it.info.id == preferredEngineId }
            when {
                preferred == null ->
                    SproutLog.w(
                        "engine preference '$preferredEngineId' is not registered; " +
                            "available: ${(factories + fallbackFactory).joinToString { it.info.id }}",
                    )

                !preferred.supportsQuietly(context) ->
                    SproutLog.w(
                        "engine preference '$preferredEngineId' is registered but not supported " +
                            "on this device; falling back",
                    )

                else -> {
                    SproutLog.d { "selected engine '$preferredEngineId' (explicit preference)" }
                    return preferred
                }
            }
        }

        val selected = factories
            .sortedByDescending { it.info.priority }
            .firstOrNull { it.supportsQuietly(context) }

        if (selected != null) {
            SproutLog.d { "selected engine '${selected.info.id}'" }
            return selected
        }

        SproutLog.d { "selected fallback engine '${fallbackFactory.info.id}'" }
        return fallbackFactory
    }

    /**
     * Looks for the vendor adapters on the classpath and registers whichever are present.
     *
     * Absence is the normal case, not a failure: a phone-only app has no reason to carry the BOOX
     * SDK, and that is the whole point of keeping the adapters in separate modules.
     */
    @MainThread
    internal fun discoverAdapters() {
        if (discovered) return
        discovered = true
        ADAPTER_FACTORY_CLASSES.forEach { className ->
            val factory = instantiateFactory(className)
            if (factory != null) register(factory) else SproutLog.d { "adapter not present: $className" }
        }
    }

    /**
     * Reflectively instantiates a factory, accepting either a Kotlin `object` or a class with a
     * no-argument constructor. Returns null when the class is absent or unusable.
     */
    private fun instantiateFactory(className: String): InkEngineFactory? = try {
        val type = Class.forName(className)
        val instance = runCatching { type.getDeclaredField("INSTANCE").get(null) }
            .getOrNull()
            ?: type.getDeclaredConstructor().newInstance()
        if (instance is InkEngineFactory) {
            instance
        } else {
            SproutLog.w("$className is not an InkEngineFactory; ignoring it")
            null
        }
    } catch (_: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        // A present-but-broken adapter must not take the host app down with it. Losing the
        // hardware path is survivable; crashing on a canvas that has not drawn anything is not.
        SproutLog.e("failed to load engine factory $className; continuing without it", t)
        null
    }

    /**
     * [InkEngineFactory.isSupported] must not throw, but a vendor adapter reaching into hidden
     * framework APIs is exactly the code that will, on the one firmware nobody tested.
     */
    private fun InkEngineFactory.supportsQuietly(context: Context): Boolean = try {
        isSupported(context)
    } catch (t: Throwable) {
        SproutLog.e("engine '${info.id}' threw from isSupported; treating as unsupported", t)
        false
    }

    /** Restores the pristine state. Tests only. */
    @VisibleForTesting
    internal fun resetForTesting() {
        factories.clear()
        fallbackFactory = NoOpInkEngineFactory
        discovered = false
    }
}
