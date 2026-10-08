package com.match3vision.analyzer.input

/**
 * Hand-measured gem centres on `real_frames/pvp_board.jpg` (1080×2400).
 *
 * These numbers are not computed from [com.match3vision.analyzer.vision.BoardFinder]
 * boundaries. They were read from the JPEG by a separate chroma-blob centroid
 * inside hand-picked row bands on a color-glyph map of the playfield:
 *
 * column centres (px): 89, 239, 389, 539, 690, 839, 989
 * row centres (px): 1267, 1411, 1563, 1719, 1865, 2015, 2157
 *   Row 3 is the median Y of the five saturated blobs in that band
 *   (1714.5, 1714.5, 1719.0, 1727.8, 1727.8). Columns 3 and 5 of that row
 *   are low-chroma and were not used. This is not the detector centre 1740.5.
 *   Row 6 is 2157, the median of seven luma-weighted centroids in the bottom
 *   band (2157.4, 2161.2, 2155.5, 2159.6, 2155.7, 2158.1, 2153.6). Median
 *   2157.4, stored as 2157. This replaces the earlier pitch extrapolation
 *   2165. It is not the detector centre 2171.
 *
 * [TOLERANCE_PX] is [MIN_COLUMN_PITCH_PX] / 5. Column pitches are 150, 150,
 * 150, 151, 149, 150. The minimum is 149 (690 to 839). 149/5 = 29.8.
 * That divisor is one fifth of a tile pitch, not the observed row-2 error.
 * Half of the minimum pitch is 74.5, so a neighbouring tile still fails.
 * Row 2's hand Y is 1563. The fixture detector centre is about 27.5 px away.
 * 27.5 is inside 29.8 by 2.3 px and about 47 px inside half a pitch.
 * The tolerance was not chosen to hide that error.
 */
object HandMeasuredPvpCenters {
    const val MIN_COLUMN_PITCH_PX = 149f
    const val TOLERANCE_PX = MIN_COLUMN_PITCH_PX / 5f
    val columnCenterX = floatArrayOf(89f, 239f, 389f, 539f, 690f, 839f, 989f)
    val rowCenterY = floatArrayOf(1267f, 1411f, 1563f, 1719f, 1865f, 2015f, 2157f)
}
