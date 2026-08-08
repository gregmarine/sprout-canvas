package com.symmetricalpalmtree.sprout.canvas.onyx

import android.content.Context
import android.os.Build
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.base.utils.ResManager
import com.symmetricalpalmtree.sprout.canvas.SproutCanvas
import com.symmetricalpalmtree.sprout.canvas.SproutLog
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.roundToInt

/**
 * Everything that has to be true about the *process* before a single Onyx class is touched.
 *
 * ### Why this exists as a separate object
 *
 * The BOOX SDK's raw-drawing path reaches hidden framework methods by reflection, and it needs the
 * exemption for that installed **before** any of its classes initialize. That is a process-wide
 * change to the host app's runtime, and it is the reason a library cannot simply set itself up
 * behind the app's back — see [SproutCanvas.initialize] and PLAN.md D11.
 *
 * Keeping the preparation here, in a class with no Onyx types on its own initialization path, is
 * what makes the ordering guarantee real: [prepare] runs the bypass first and only then resolves an
 * SDK class. An adapter that named an Onyx type in a field initializer would have loaded the SDK
 * before its own first line ran.
 */
internal object OnyxSdk {

    /**
     * Result of the one-time preparation, or null while it has not been attempted.
     *
     * Cached because the answer cannot change within a process, and because the failure path logs.
     */
    private var prepared: Boolean? = null

    /** True when the running device claims to be made by Onyx. A pre-filter, never the answer. */
    fun isOnyxHardware(): Boolean =
        Build.MANUFACTURER.contains(MANUFACTURER, ignoreCase = true) ||
            Build.BRAND.contains(MANUFACTURER, ignoreCase = true)

    /**
     * Installs the hidden-API exemption, initializes the SDK's resource loader, and confirms the
     * SDK actually resolves here.
     *
     * Idempotent, and safe to call on a device that has no BOOX SDK at all — that is the whole
     * point of returning a boolean instead of throwing.
     *
     * @return true when the Onyx ink path can be used in this process.
     */
    fun prepare(context: Context): Boolean {
        prepared?.let { return it }

        val app = context.applicationContext

        // Order matters and is the reason this method is not three call sites. The exemption has to
        // be in place before the SDK's static initializers run, and the class-resolution probe
        // below is what runs them.
        installHiddenApiBypass()

        val result = runCatching {
            // The class-resolution probe. A device whose manufacturer string says "onyx" but whose
            // firmware does not carry the pen layer lands here rather than crashing later, halfway
            // into a stroke.
            Class.forName("com.onyx.android.sdk.pen.TouchHelper")
            Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")

            // Mandatory before any bitmap-backed pen. Nothing in TouchHelper or the NeoPen layer
            // does it, because BOOX's own Notes app does it at startup and the SDK assumes someone
            // has. The failure mode without it is genuinely nasty: the pencil first renders solid
            // and grainless with no error at all, and only throws much later when something forces
            // the pen to be rebuilt (PLAN.md §5.7).
            ResManager.init(app)
            true
        }.getOrElse { t ->
            SproutLog.e("the Onyx SDK is not usable on this device; falling back to generic", t)
            false
        }

        prepared = result
        if (result) SproutLog.d { "Onyx SDK prepared: ${describeDevice()}" }
        return result
    }

    /**
     * Grants the whole process access to the hidden framework methods the SDK reflects on.
     *
     * The empty-string prefix exempts everything, which is what the SDK needs and what BOOX's own
     * documentation asks for. A failure here is logged and not fatal: on API 27 and below there is
     * no restriction to lift, and on a non-BOOX device nothing will call the restricted methods
     * anyway.
     */
    private fun installHiddenApiBypass() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { SproutLog.w("hidden-API exemption failed: ${it.message}") }
    }

    /**
     * What the panel reports about itself, read at runtime.
     *
     * ### Every field here is read because a "constant" turned out not to be one
     *
     *  - **[DeviceCalibration.maxPressure] is 4095 on some BOOX models and 4096 on others.** It is
     *    the divisor for every pressure normalization and every `NeoPen` configuration, so
     *    hardcoding either value is quietly wrong on half the fleet (PLAN.md §5.6).
     *  - **The digitizer is not the screen.** Observed from 7239×5359 to 27040×20280 across five
     *    devices, none of which matches its own panel resolution.
     *
     * [DeviceCalibration.tiltUnitsKnown] is `false` and always will be on this platform: the SDK
     * hands back `tiltX`/`tiltY` as bare ints with no documented unit and no `getMaxTilt()` to
     * normalize against, and one model reports them roughly a hundred times larger than the rest.
     * They are passed through raw, and this flag says so rather than inventing a scale (PLAN.md §3.5).
     */
    fun readCalibration(densityDpi: Int): DeviceCalibration {
        val maxPressure = runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f)
        val usablePressure = maxPressure > 1f && maxPressure.isFinite()
        return DeviceCalibration(
            maxPressure = if (usablePressure) maxPressure else 1f,
            pressureIsNormalized = !usablePressure,
            tiltUnitsKnown = false,
            digitizerWidth = runCatching { EpdController.getTouchWidth() }.getOrDefault(0f).roundToInt(),
            digitizerHeight = runCatching { EpdController.getTouchHeight() }.getOrDefault(0f).roundToInt(),
            densityDpi = densityDpi,
        )
    }

    /** A one-line device summary for logs and the Lab's device report. */
    fun describeDevice(): String = buildString {
        append(Build.MODEL)
        append(" · maxPressure=")
        append(runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f))
        append(" · digitizer=")
        append(runCatching { EpdController.getTouchWidth() }.getOrDefault(0f).roundToInt())
        append('×')
        append(runCatching { EpdController.getTouchHeight() }.getOrDefault(0f).roundToInt())
    }

    private const val MANUFACTURER = "onyx"

    /** Restores the pristine state. Tests only. */
    internal fun resetForTesting() {
        prepared = null
    }
}
