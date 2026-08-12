package com.crunzex.linuxondex.display.vnc

/**
 * How a guest framebuffer maps onto the view: pure math, kept out of
 * [VncView] so the fit and stretch rules are unit-testable.
 */
object VncViewGeometry {

    data class DrawScales(val scaleX: Float, val scaleY: Float)

    /**
     * Scales for drawing a [frameWidth]×[frameHeight] framebuffer into a
     * [viewWidth]×[viewHeight] view.
     *
     * Fit mode preserves aspect ratio and letterboxes the remainder; stretch
     * mode fills the view on both axes. Degenerate sizes return an identity
     * scale rather than dividing by zero.
     */
    fun drawScales(
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        stretchToFill: Boolean,
    ): DrawScales {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return DrawScales(1f, 1f)
        }
        val fitScale = minOf(
            viewWidth.toFloat() / frameWidth,
            viewHeight.toFloat() / frameHeight,
        )
        return if (stretchToFill) {
            DrawScales(
                scaleX = viewWidth.toFloat() / frameWidth,
                scaleY = viewHeight.toFloat() / frameHeight,
            )
        } else {
            DrawScales(fitScale, fitScale)
        }
    }
}
