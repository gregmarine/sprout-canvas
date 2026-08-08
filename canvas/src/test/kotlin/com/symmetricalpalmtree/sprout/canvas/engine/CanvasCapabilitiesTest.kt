package com.symmetricalpalmtree.sprout.canvas.engine

import com.symmetricalpalmtree.sprout.canvas.model.EraserMode
import com.symmetricalpalmtree.sprout.canvas.model.InkChannel
import com.symmetricalpalmtree.sprout.canvas.model.SproutPen
import com.symmetricalpalmtree.sprout.canvas.tools.GenericPenTable
import com.symmetricalpalmtree.sprout.canvas.tools.OnyxPenTable
import com.symmetricalpalmtree.sprout.canvas.tools.SupernotePenTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability model, including the guard that stops a pen from being forgotten.
 */
class CanvasCapabilitiesTest {

    private fun capabilities(
        penFidelities: Map<SproutPen, PenFidelity> = CanvasCapabilities.uniformFidelity(PenFidelity.NATIVE),
        channels: Int = InkChannel.PRESSURE or InkChannel.TILT,
        eraserModes: Set<EraserMode> = setOf(EraserMode.STROKE),
    ) = CanvasCapabilities(
        engineId = "test",
        channels = channels,
        supportsAlpha = true,
        supportedEraserModes = eraserModes,
        penFidelities = penFidelities,
    )

    @Test(expected = IllegalArgumentException::class)
    fun `an engine that forgot a pen fails at construction`() {
        // Adding a SproutPen without deciding what an engine does with it is a bug that hides: the
        // pen would simply be missing from a picker, or throw much later at render time.
        capabilities(penFidelities = CanvasCapabilities.uniformFidelity(PenFidelity.NATIVE) - SproutPen.CHARCOAL)
    }

    @Test
    fun `the real platform tables all satisfy the guard`() {
        // If any of the three tables ever stops covering the enum, this is where it surfaces.
        capabilities(penFidelities = SproutPen.entries.associateWith(OnyxPenTable::fidelity))
        capabilities(penFidelities = SproutPen.entries.associateWith(SupernotePenTable::fidelity))
        capabilities(penFidelities = GenericPenTable.fidelities())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown channel bit is rejected`() {
        capabilities(channels = 1 shl 20)
    }

    @Test
    fun `channel and eraser queries answer honestly`() {
        val subject = capabilities()
        assertTrue(subject.reports(InkChannel.PRESSURE))
        assertFalse(subject.reports(InkChannel.SIZE))
        assertTrue(subject.supports(EraserMode.STROKE))
        assertFalse(subject.supports(EraserMode.AREA))
        assertFalse(subject.supports(EraserMode.PIXEL))
    }

    @Test
    fun `pensBelowNative names exactly what a tool picker should annotate`() {
        val supernote = capabilities(penFidelities = SproutPen.entries.associateWith(SupernotePenTable::fidelity))
        assertEquals(
            listOf(SproutPen.BRUSH, SproutPen.MARKER, SproutPen.PENCIL, SproutPen.CHARCOAL, SproutPen.DASHED),
            supernote.pensBelowNative(),
        )

        val generic = capabilities(penFidelities = GenericPenTable.fidelities())
        assertTrue(generic.pensBelowNative().isEmpty())
    }

    @Test
    fun `the stub engine claims nothing it cannot do`() {
        val stub = NoOpInkEngine.CAPABILITIES
        assertEquals(InkChannel.NONE, stub.channels)
        assertFalse(stub.supportsAlpha)
        assertFalse(stub.liveInkIsHardware)
        assertTrue(stub.supportedEraserModes.isEmpty())
        assertEquals(SproutPen.entries.size, stub.pensBelowNative().size)
    }

    @Test
    fun `describe names every pen, so a device report cannot silently omit one`() {
        val text = capabilities(penFidelities = SproutPen.entries.associateWith(OnyxPenTable::fidelity)).describe()
        SproutPen.entries.forEach { assertTrue("$it missing from the report", text.contains(it.name)) }
        assertTrue(text.contains("max pressure"))
        assertTrue(text.contains("tilt units known"))
    }

    @Test
    fun `the stub engine sits below every real engine in priority`() {
        assertTrue(NoOpInkEngine.INFO.priority < EngineInfo.PRIORITY_GENERIC)
        assertTrue(EngineInfo.PRIORITY_GENERIC < EngineInfo.PRIORITY_VENDOR)
    }
}
