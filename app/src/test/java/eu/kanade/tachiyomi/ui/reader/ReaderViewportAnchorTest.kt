package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReaderViewportAnchorTest {
    @Test fun `layout reports cannot turn page one into page twenty while restoring`() {
        val state = ReaderViewportState()
        val first = ReaderViewportAnchor(7, 0)
        state.restore(first)
        repeat(20) { assertFalse(state.observe(ReaderViewportAnchor(7, 19))) }
        assertEquals(first, state.anchor)
        assertTrue(state.isRestoring)
    }

    @Test fun `a real user scroll releases the hold and permits the new scene`() {
        val state = ReaderViewportState()
        state.restore(ReaderViewportAnchor(7, 0))
        state.beginUserScroll()
        assertTrue(state.observe(ReaderViewportAnchor(7, 19, .25f)))
        assertEquals(ReaderViewportAnchor(7, 19, .25f), state.anchor)
    }

    @Test fun `folding repeatedly preserves the scene inside a long webtoon image`() {
        val state = ReaderViewportState()
        state.restore(ReaderViewportAnchor.capture(7, 3, -4000, 10000, 0))
        repeat(100) {
            val folded = state.anchor!!.pixelOffset(4000)
            assertEquals(-1600, folded)
            // A temporary bottom-most page during image layout is not the anchor.
            assertFalse(state.observe(ReaderViewportAnchor(7, 20)))
            val unfolded = state.anchor!!.pixelOffset(10000)
            assertEquals(-4000, unfolded)
        }
        assertEquals(3, state.anchor!!.pageIndex)
    }

    @Test fun `placeholder decoding reapplies the same source fraction instead of its old pixels`() {
        val anchor = ReaderViewportAnchor(7, 0, .6f)
        assertEquals(-60, anchor.pixelOffset(100))
        assertEquals(-6000, anchor.pixelOffset(10000))
        assertEquals(.6f, ReaderViewportAnchor.capture(7, 0, -6000, 10000, 0).offsetFraction, .0001f)
    }

    @Test fun `a saved viewport belongs to its source chapter and page only`() {
        val anchor = ReaderViewportAnchor(7, 0, .6f)
        assertTrue(anchor.matches(7, 0))
        assertFalse(anchor.matches(8, 0))
        assertFalse(anchor.matches(7, 19))
    }

    @Test fun `capture accounts for viewport padding and invalid image geometry`() {
        assertEquals(.5f, ReaderViewportAnchor.capture(7, 2, -480, 1000, 20).offsetFraction, .0001f)
        assertEquals(0f, ReaderViewportAnchor.capture(7, 2, 50, 1000, 0).offsetFraction)
        assertEquals(0f, ReaderViewportAnchor.capture(7, 2, 0, 0, 0).offsetFraction)
        assertEquals(0, ReaderViewportAnchor(7, 0, Float.NaN).pixelOffset(10000))
        assertEquals(0, ReaderViewportAnchor(7, 0, Float.POSITIVE_INFINITY).pixelOffset(10000))
    }

    @Test fun `repeated spread callbacks retain the explicitly selected second source page`() {
        var current = ReaderPositionPolicy.spreadFocus(1, null, 0) { it in 0..1 }
        repeat(20) { current = ReaderPositionPolicy.spreadFocus(null, current, 0) { it in 0..1 } }
        assertEquals(1, current)
    }

    @Test fun `moving to another spread selects its own page rather than pinning the old scene`() {
        assertEquals(2, ReaderPositionPolicy.spreadFocus(null, 1, 2) { it in 2..3 })
        assertEquals(3, ReaderPositionPolicy.spreadFocus(3, 2, 2) { it in 2..3 })
    }
}
