package com.match3vision.analyzer.input

/**
 * Hand-measured gem centres on `real_frames/pvp_board.jpg` (1080×2400).
 *
 * These numbers are not computed from [com.match3vision.analyzer.vision.BoardFinder]
 * boundaries. They were read from the JPEG by a separate chroma-blob centroid
 * inside hand-picked row bands on a color-glyph map of the playfield:
 *
 * column centres (px): 89, 239, 389, 539, 690, 839, 989
 * row centres (px): 1267, 1411, 1563, 1719, 1865, 2015
 *   Row 3 is the median Y of the five saturated blobs in that band
 *   (1714.5, 1714.5, 1719.0, 1727.8, 1727.8). Columns 3 and 5 of that row
 *   are low-chroma and were not used. This is not the detector centre 1740.5.
 *   Row 6 is 2165, one median pitch (~149.6 px) below row 5, because the
 *   bottom band is low-chroma and the blob centroid is not stable there.
 *
 * [TOLERANCE_PX] is 28. Half the tile pitch is about 75 px, so a centre that
 * lands on a neighbouring tile fails. The tolerance covers the difference
 * between a gem's visual centroid and the geometric cell centre.
 */
object HandMeasuredPvpCenters {
    const val TOLERANCE_PX = 28f
    val columnCenterX = floatArrayOf(89f, 239f, 389f, 539f, 690f, 839f, 989f)
    val rowCenterY = floatArrayOf(1267f, 1411f, 1563f, 1719f, 1865f, 2015f, 2165f)
}
