package com.symmetricalpalmtree.sprout.canvas.onyx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The process-global ownership guard.
 *
 * ### Why this is worth a test when the class is thirty lines
 *
 * The bug it prevents is invisible in code review and intermittent on hardware: Android opens an
 * incoming screen's surface *before* closing the outgoing one, so the ordering that breaks a BOOX
 * canvas is `B.open()` then `A.close()` — and `A.close()` looks completely reasonable sitting in
 * `A`'s own teardown. On a device the symptom is a canvas that silently stops accepting the stylus
 * some of the time.
 *
 * That ordering is trivial to reproduce here and impossible to reproduce reliably there, which is
 * exactly why the guard is kept free of SDK and engine types.
 */
class OnyxPenOwnerTest {

    private val canvasA = Any()
    private val canvasB = Any()

    @Before
    @After
    fun reset() {
        OnyxPenOwner.resetForTesting()
    }

    @Test
    fun `nobody owns the pipeline before anything claims it`() {
        assertFalse(OnyxPenOwner.isHeld())
        assertFalse(OnyxPenOwner.isOwner(canvasA))
    }

    @Test
    fun `a claim makes the claimant the owner`() {
        OnyxPenOwner.claim(canvasA)

        assertTrue(OnyxPenOwner.isHeld())
        assertTrue(OnyxPenOwner.isOwner(canvasA))
        assertFalse(OnyxPenOwner.isOwner(canvasB))
    }

    @Test
    fun `the owner closes the pipeline and gives up ownership`() {
        OnyxPenOwner.claim(canvasA)

        var closed = 0
        val ranClose = OnyxPenOwner.releaseIfOwner(canvasA) { closed++ }

        assertTrue(ranClose)
        assertEquals(1, closed)
        assertFalse(OnyxPenOwner.isHeld())
    }

    /**
     * The whole point. `B` claims the pipeline, then `A`'s late teardown runs — and must not touch
     * the session `B` is now writing into.
     */
    @Test
    fun `a superseded canvas cannot close the live canvas's session`() {
        OnyxPenOwner.claim(canvasA)
        OnyxPenOwner.claim(canvasB)

        var closed = 0
        val ranClose = OnyxPenOwner.releaseIfOwner(canvasA) { closed++ }

        assertFalse("A must not close B's session", ranClose)
        assertEquals(0, closed)
        assertTrue("B still owns the pipeline", OnyxPenOwner.isOwner(canvasB))
    }

    @Test
    fun `closing twice closes once`() {
        OnyxPenOwner.claim(canvasA)

        var closed = 0
        OnyxPenOwner.releaseIfOwner(canvasA) { closed++ }
        OnyxPenOwner.releaseIfOwner(canvasA) { closed++ }

        assertEquals(1, closed)
    }

    @Test
    fun `re-claiming an already-owned pipeline is a no-op`() {
        OnyxPenOwner.claim(canvasA)
        OnyxPenOwner.claim(canvasA)

        var closed = 0
        assertTrue(OnyxPenOwner.releaseIfOwner(canvasA) { closed++ })
        assertEquals(1, closed)
    }

    /**
     * A canvas that never opened successfully never claimed, so its teardown must be inert rather
     * than closing whatever session happens to be live.
     */
    @Test
    fun `a canvas that never claimed closes nothing`() {
        OnyxPenOwner.claim(canvasA)

        var closed = 0
        assertFalse(OnyxPenOwner.releaseIfOwner(canvasB) { closed++ })
        assertEquals(0, closed)
        assertTrue(OnyxPenOwner.isOwner(canvasA))
    }
}
