package com.symmetricalpalmtree.sprout.canvas

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.MainThread
import com.symmetricalpalmtree.sprout.canvas.engine.EngineRegistry
import com.symmetricalpalmtree.sprout.canvas.engine.InkEngineFactory

/**
 * The library's front door: one call from the host, then engine discovery and global state.
 *
 * ```
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         SproutCanvas.initialize(this)
 *     }
 * }
 * ```
 *
 * ### Why the host has to ask
 *
 * [initialize] is the one piece of cooperation this library requires, and it requires it explicitly
 * rather than installing itself behind the host's back. The hardware ink paths need process-wide
 * changes that no library should make on an app's behalf without being told to: the Onyx SDK needs
 * a **hidden-API exemption applied to the entire process** before it is touched, and the Supernote
 * firmware client needs hidden `ServiceManager` access. Those are the host's decisions to make.
 *
 * ### What happens if it is skipped
 *
 * The canvas still works. The vendor adapters report themselves unsupported, the generic engine
 * takes over, and a clear error is logged naming the missing call. Never a crash, and never a
 * silent downgrade — a BOOX that quietly started writing like a phone with nothing in logcat is
 * exactly the failure this design refuses to produce.
 */
public object SproutCanvas {

    /**
     * The library version, matching the published Maven coordinate
     * `com.symmetricalpalmtree.sprout:canvas`.
     *
     * Written into every [com.symmetricalpalmtree.sprout.canvas.model.CaptureInfo], so a stroke
     * capture or a device report can always name the build that produced it. Diagnostics that
     * cannot name their own version age badly.
     */
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    private var application: Application? = null

    /** True once [initialize] has run. */
    public val isInitialized: Boolean get() = application != null

    /**
     * Debug logging, off by default.
     *
     * Turning it on makes the library narrate engine selection, capability probing and lifecycle
     * transitions under the `SproutCanvas` logcat tag. Off, those messages cost nothing — the log
     * calls are inline and their strings are never built.
     *
     * Set automatically to the host's own debuggable flag by [initialize]; assign afterwards to
     * override.
     */
    @JvmStatic
    public var debugLogging: Boolean = false

    /**
     * Strict checks — main-thread assertions and argument validation on the public API.
     *
     * Defaults to the host app's debuggable flag, so a debug build gets a loud, immediate failure
     * at the call site that broke the rule and a release build pays nothing. The public API is
     * main-thread only; a violation caught here is far cheaper than the intermittent corruption it
     * would otherwise cause.
     */
    @JvmStatic
    public var strictMode: Boolean = false

    /**
     * Prepares the library and finds whichever vendor adapters are on the classpath.
     *
     * Idempotent — calling it more than once is harmless. Call it from `Application.onCreate`,
     * before any canvas is inflated.
     */
    @MainThread
    @JvmStatic
    public fun initialize(application: Application) {
        if (this.application === application) return
        this.application = application

        val debuggable =
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        debugLogging = debuggable
        strictMode = debuggable

        EngineRegistry.discoverAdapters()
        SproutLog.d {
            "initialized $VERSION; engines: " +
                EngineRegistry.registeredFactories().joinToString { it.info.id }.ifEmpty { "none" }
        }
    }

    /**
     * Registers an engine manually, for a host that would rather not rely on discovery or that
     * supplies an engine this library has never heard of.
     *
     * The engine SPI is open — see [InkEngineFactory].
     */
    @MainThread
    @JvmStatic
    public fun registerEngine(factory: InkEngineFactory) {
        EngineRegistry.register(factory)
    }

    /**
     * The engines available on this device, best first, as `id` strings.
     *
     * Useful for a settings screen or a device report. The one a given canvas actually chose is
     * [SproutCanvasView.engineInfo] — this is what was *available*, which is not always the same
     * thing once an explicit preference is in play.
     */
    @MainThread
    @JvmStatic
    public fun availableEngines(context: Context): List<String> =
        EngineRegistry.registeredFactories()
            .filter { it.isSupported(context) }
            .map { it.info.id }

    /**
     * Logs the missing-initialize error, once, when a canvas is created without it.
     *
     * Once, because a screen full of canvases would otherwise bury the message it is trying to
     * deliver under copies of itself.
     */
    internal fun warnIfUninitialized() {
        if (isInitialized || uninitializedWarningLogged) return
        uninitializedWarningLogged = true
        SproutLog.e(
            "SproutCanvas.initialize(application) was never called. The canvas works, but the " +
                "hardware ink paths are unavailable and this device will use the generic engine. " +
                "Call it from Application.onCreate().",
        )
    }

    private var uninitializedWarningLogged = false

    /** Restores the pristine state. Tests only. */
    internal fun resetForTesting() {
        application = null
        debugLogging = false
        strictMode = false
        uninitializedWarningLogged = false
    }
}
