package dev.herakles.nightjar.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit4, no Robolectric — [clampZoomScale], [fitContentSize], [maxPanOffset] and
 * [clampPanOffset] are all plain `Float` arithmetic with no Compose/Android type in their
 * signatures, the same "pure, `internal`, directly testable" split this codebase already uses
 * for its other display-logic functions (e.g. `audioCarrierViewOptions`).
 */
class FullscreenImageViewerMathTest {

    // --- clampZoomScale -------------------------------------------------------------------

    @Test
    fun `clampZoomScale passes through an in-range value`() {
        assertEquals(3f, clampZoomScale(3f, min = 1f, max = 5f), 0f)
    }

    @Test
    fun `clampZoomScale floors below the minimum`() {
        assertEquals(1f, clampZoomScale(0.2f, min = 1f, max = 5f), 0f)
    }

    @Test
    fun `clampZoomScale ceilings above the maximum`() {
        assertEquals(5f, clampZoomScale(12f, min = 1f, max = 5f), 0f)
    }

    // --- fitContentSize (ContentScale.Fit, worked out by hand) -----------------------------

    @Test
    fun `fitContentSize width-constrains a source wider than the container`() {
        // source aspect 2.0, container aspect 1.0 -- source is relatively wider, so its width
        // fills the container and height shrinks to keep the source's own aspect ratio.
        val (w, h) = fitContentSize(sourceWidth = 200, sourceHeight = 100, containerWidth = 100f, containerHeight = 100f)
        assertEquals(100f, w, 0f)
        assertEquals(50f, h, 0f)
    }

    @Test
    fun `fitContentSize height-constrains a source taller than the container`() {
        // source aspect 0.5, container aspect 1.0 -- source is relatively taller, height fills.
        val (w, h) = fitContentSize(sourceWidth = 100, sourceHeight = 200, containerWidth = 100f, containerHeight = 100f)
        assertEquals(50f, w, 0f)
        assertEquals(100f, h, 0f)
    }

    @Test
    fun `fitContentSize matches the container exactly when aspect ratios are equal`() {
        val (w, h) = fitContentSize(sourceWidth = 100, sourceHeight = 50, containerWidth = 200f, containerHeight = 100f)
        assertEquals(200f, w, 0f)
        assertEquals(100f, h, 0f)
    }

    @Test
    fun `fitContentSize degenerates to the container size for a not-yet-measured container`() {
        val (w, h) = fitContentSize(sourceWidth = 100, sourceHeight = 100, containerWidth = 0f, containerHeight = 0f)
        assertEquals(0f, w, 0f)
        assertEquals(0f, h, 0f)
    }

    @Test
    fun `fitContentSize degenerates to the container size for an empty bitmap`() {
        val (w, h) = fitContentSize(sourceWidth = 0, sourceHeight = 0, containerWidth = 100f, containerHeight = 100f)
        assertEquals(100f, w, 0f)
        assertEquals(100f, h, 0f)
    }

    // --- maxPanOffset / clampPanOffset (the bounds-constraint itself) ----------------------

    @Test
    fun `maxPanOffset is zero in both axes at fit scale`() {
        val (maxX, maxY) = maxPanOffset(
            contentWidth = 100f, contentHeight = 100f,
            containerWidth = 100f, containerHeight = 100f,
            scale = MIN_ZOOM_SCALE,
        )
        assertEquals(0f, maxX, 0f)
        assertEquals(0f, maxY, 0f)
    }

    @Test
    fun `clampPanOffset pins offset to zero at fit scale no matter what's requested`() {
        // The core "the image can never be lost off-screen" guarantee: at MIN_ZOOM_SCALE the
        // content never exceeds the viewport, so any requested pan collapses to (0, 0).
        val (x, y) = clampPanOffset(
            offsetX = 500f, offsetY = -500f,
            contentWidth = 100f, contentHeight = 100f,
            containerWidth = 100f, containerHeight = 100f,
            scale = MIN_ZOOM_SCALE,
        )
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun `clampPanOffset allows a pan within bounds once zoomed in`() {
        // content 100x100 at scale 2 -> scaled 200x200 in a 100x100 viewport -> max pan 50 each
        // axis. A request well inside that bound passes through unchanged.
        val (x, y) = clampPanOffset(
            offsetX = 30f, offsetY = -20f,
            contentWidth = 100f, contentHeight = 100f,
            containerWidth = 100f, containerHeight = 100f,
            scale = 2f,
        )
        assertEquals(30f, x, 0f)
        assertEquals(-20f, y, 0f)
    }

    @Test
    fun `clampPanOffset clamps an over-eager pan to the content's own edge`() {
        val (x, y) = clampPanOffset(
            offsetX = 500f, offsetY = -500f,
            contentWidth = 100f, contentHeight = 100f,
            containerWidth = 100f, containerHeight = 100f,
            scale = 2f,
        )
        assertEquals(50f, x, 0f)
        assertEquals(-50f, y, 0f)
    }

    @Test
    fun `clampPanOffset clamps each axis independently for asymmetric content`() {
        // content 80x160 at scale 2 -> scaled 160x320 in a 100x100 viewport.
        // maxX = (160-100)/2 = 30, maxY = (320-100)/2 = 110.
        val (x, y) = clampPanOffset(
            offsetX = 200f, offsetY = 200f,
            contentWidth = 80f, contentHeight = 160f,
            containerWidth = 100f, containerHeight = 100f,
            scale = 2f,
        )
        assertEquals(30f, x, 0f)
        assertEquals(110f, y, 0f)
    }
}
