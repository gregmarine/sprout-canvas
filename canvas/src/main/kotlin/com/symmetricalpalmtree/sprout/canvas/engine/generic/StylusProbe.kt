package com.symmetricalpalmtree.sprout.canvas.engine.generic

import android.view.InputDevice
import android.view.MotionEvent
import com.symmetricalpalmtree.sprout.canvas.model.DeviceCalibration
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.InkChannels
import kotlin.math.roundToInt

/**
 * What a digitizer actually reports, read from the device rather than assumed.
 *
 * @param channels the [InkChannel] mask this device can fill.
 * @param calibration what was measured about it.
 * @param deviceName the input device's name, for the Lab's device report. Null when no stylus was
 *   found at all.
 */
internal class StylusCapture(
    @InkChannels val channels: Int,
    val calibration: DeviceCalibration,
    val deviceName: String?,
)

/**
 * Reads a stylus's real capabilities out of its `InputDevice` motion ranges.
 *
 * ### Why probe at all
 *
 * "Android stylus" is not one thing. An S-Pen, a Wacom EMR panel and a capacitive stylus report
 * overlapping but different subsets of the axes, and a channel that is absent must be reported as
 * absent rather than as zero — an app that reads a missing pressure channel as "pressure 0" draws
 * nothing at all (see [InkChannel]).
 *
 * ### Tilt, and the two things called tilt
 *
 * Android defines [MotionEvent.AXIS_TILT] (the angle away from perpendicular) and
 * [MotionEvent.AXIS_ORIENTATION] (rotation about the stylus's own axis), both in radians, both
 * properly specified. This engine reports those as [InkChannel.ALTITUDE] and
 * [InkChannel.ORIENTATION].
 *
 * It never reports [InkChannel.TILT], which is reserved for the raw, unit-less `tiltX`/`tiltY` a
 * *vendor* pipeline hands back — values whose scale differs by a factor of a hundred between BOOX
 * models with no API anywhere to normalize against (PLAN.md §3.5). Nothing on the ordinary Android
 * input path produces those, so the honest mask here simply omits the channel.
 */
internal object StylusProbe {

    /**
     * Probes the device that produced [event].
     *
     * The most reliable moment to ask, because the device is the one actually in the user's hand —
     * a tablet can have several input devices, and the one a scan happens to find first is not
     * necessarily the one writing.
     */
    fun probe(event: MotionEvent, densityDpi: Int): StylusCapture =
        probe(event.device, densityDpi)

    /**
     * Looks for any connected stylus.
     *
     * Used to answer [com.symmetricalpalmtree.sprout.canvas.engine.CanvasCapabilities] before a
     * stroke has ever been drawn — a device report that said nothing until the user wrote on it
     * would be useless exactly when it is most wanted. Refined by [probe] on the first real stroke.
     */
    fun probeConnectedStylus(densityDpi: Int): StylusCapture {
        val device = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.supportsSource(InputDevice.SOURCE_STYLUS) }
        return probe(device, densityDpi)
    }

    private fun probe(device: InputDevice?, densityDpi: Int): StylusCapture {
        // Event time is read straight off the MotionEvent, so timestamps need no hardware support
        // and are the one channel that is always available.
        var channels = InkChannel.TIMESTAMP

        if (device == null) {
            return StylusCapture(
                channels = channels,
                calibration = DeviceCalibration.UNKNOWN.copy(densityDpi = densityDpi),
                deviceName = null,
            )
        }

        val pressureRange = device.getMotionRange(MotionEvent.AXIS_PRESSURE)
        val pressureMax = pressureRange?.max ?: 0f
        val reportsPressure = pressureRange != null && pressureMax > 0f
        if (reportsPressure) channels = channels or InkChannel.PRESSURE
        if (device.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null) {
            channels = channels or InkChannel.ORIENTATION
        }
        if (device.getMotionRange(MotionEvent.AXIS_TILT) != null) {
            channels = channels or InkChannel.ALTITUDE
        }
        if (device.getMotionRange(MotionEvent.AXIS_SIZE) != null) {
            channels = channels or InkChannel.SIZE
        }

        return StylusCapture(
            channels = channels,
            calibration = DeviceCalibration(
                maxPressure = if (reportsPressure) pressureMax else 1f,
                // A max of 1 means the driver has already normalized, which is the ordinary Android
                // contract. Anything larger is raw digitizer counts — the same 4095-or-4096 problem
                // that makes hardcoding a divisor wrong on half a vendor's fleet (PLAN.md §5.6).
                pressureIsNormalized = !reportsPressure || pressureMax <= NORMALIZED_MAX_EPSILON,
                // Never true on this path: see the class KDoc.
                tiltUnitsKnown = false,
                digitizerWidth = device.getMotionRange(MotionEvent.AXIS_X)?.max?.roundToInt() ?: 0,
                digitizerHeight = device.getMotionRange(MotionEvent.AXIS_Y)?.max?.roundToInt() ?: 0,
                densityDpi = densityDpi,
            ),
            deviceName = device.name,
        )
    }

    /** Floating-point slack on the "already normalized" test. */
    private const val NORMALIZED_MAX_EPSILON = 1.0001f
}
