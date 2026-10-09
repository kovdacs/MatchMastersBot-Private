package com.match3vision.analyzer.input

import com.match3vision.analyzer.vision.GridGeometry

/**
 * Coordinate-space facts. Matching sizes do not prove physical alignment.
 *
 * VisionPipeline output is full-frame pixels, not ROI-relative centres.
 * The ROI origin is added in two places and nowhere else:
 * - BoardFinder projection: `xBounds = boardRoi.left + xLocal[i]`
 *   (and the same for Y). See BoardFinder.kt around the projection return.
 * - GridGeometry.evenSplit: `boardRoi.left + i * width / 7`
 *   (VisionModels.kt, [GridGeometry.evenSplit]).
 *
 * [TouchCoordinateMapper.toGesture] copies [GridGeometry.cellBox] centerX/centerY
 * and adds nothing.
 *
 * [MatchMastersAccessibilityService.dispatchGesture] builds a [android.graphics.Path]
 * from those floats and calls AccessibilityService.dispatchGesture. Those are
 * display pixels. This code does not read WindowInsets and does not add the
 * status bar, the navigation bar, or a cutout.
 *
 * MediaProjection: [com.match3vision.analyzer.capture.ScreenCaptureManager] sizes
 * the virtual display with [com.match3vision.analyzer.capture.CaptureDisplaySize]
 * (maximumWindowMetrics on API 30+, getRealMetrics on API 29) and
 * VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR. That request is the full display pixel
 * grid when the system grants entire-display capture. API 34+ consent can
 * still be a single app. Whether the bitmap includes the status bar, the
 * navigation bar, and the cutout on API 29–35 is not proven on a device.
 *
 * Rotation is read in [com.match3vision.analyzer.capture.AndroidScreenMetrics]
 * from `defaultDisplay.rotation` ([android.view.Surface.ROTATION_0] is 0,
 * 90/180/270 are 1/2/3). [com.match3vision.analyzer.capture.ScreenMeasurement.ROTATION_UNKNOWN]
 * is -1. This code does not rotate coordinates. Rotation 1, 2, and 3 are refused.
 *
 * A non-zero origin offset is an inset/offset mismatch. It is refused.
 * It is not added to the gesture. A status-bar inset that is only recorded,
 * and not supplied as an origin offset, is not applied and is not by itself
 * treated as a shift: capture and dispatch are both requested in full-display
 * pixels. That choice is not a proof that the phone uses one origin.
 *
 * [RuntimeCycleContext.coordinateAlignmentProven] stays false.
 */
object CoordinateSpace {
    const val VISION_OUTPUT = "full-frame pixels"
    const val ALIGNMENT_PROVEN = false
}

/**
 * Refuses a rotation or an origin offset. Does not return shifted coordinates.
 */
object DisplayInsetPolicy {
    fun refusal(
        rotation: Int,
        originOffsetX: Int,
        originOffsetY: Int,
    ): String? {
        if (rotation != 0 && rotation != -1) {
            return "rotation $rotation refused; coordinates are not rotated or shifted"
        }
        if (originOffsetX != 0 || originOffsetY != 0) {
            return "inset/offset mismatch originOffset=($originOffsetX,$originOffsetY); " +
                "refusing to shift"
        }
        return null
    }
}

/**
 * Cell 0 must start at the ROI origin. A grid that is still ROI-relative while
 * the ROI is offset would silently shift every gesture. An ROI outside the
 * frame is refused. Nothing is clamped or translated.
 */
object GridOriginPolicy {
    fun refusal(
        grid: GridGeometry,
        frameWidth: Int,
        frameHeight: Int,
    ): String? {
        val roi = grid.boardRoi
        val dx = kotlin.math.abs(grid.xBoundaries[0] - roi.left.toFloat())
        val dy = kotlin.math.abs(grid.yBoundaries[0] - roi.top.toFloat())
        if (dx > 1.5f || dy > 1.5f) {
            return "grid origin (${grid.xBoundaries[0]},${grid.yBoundaries[0]}) != " +
                "ROI origin (${roi.left},${roi.top}); refusing a silent shift"
        }
        if (frameWidth > 0 && frameHeight > 0) {
            if (roi.left < 0 || roi.top < 0 || roi.right > frameWidth || roi.bottom > frameHeight) {
                return "ROI LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                    "is off-screen: outside frame ${frameWidth}x$frameHeight; refusing to clamp or shift"
            }
        }
        return null
    }
}
