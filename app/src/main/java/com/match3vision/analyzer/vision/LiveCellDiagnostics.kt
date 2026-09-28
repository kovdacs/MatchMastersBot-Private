package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.ContentRoi

/**
 * Diagnostic dump from a real capture / REAL_FRAME analysis — size, ROI, 7×7
 * bounds, per-cell color/shape/special/unknown reason/confidence, unknownCount.
 * Does **not** loosen PASS/HOLD gates.
 */
object LiveCellDiagnostics {

    data class CellDiag(
        val row: Int,
        val col: Int,
        val color: TileColor,
        val shape: TileShape,
        val special: SpecialType,
        val confidence: Float,
        val unknown: Boolean,
        val unknownReason: String,
        val meanRgb: Triple<Int, Int, Int>? = null,
        val meanHsv: Triple<Float, Float, Float>? = null,
        val meanLuma: Float? = null,
    )

    data class Report(
        val frameWidth: Int,
        val frameHeight: Int,
        val contentRoi: String,
        val boardRoi: String,
        val gridMethod: String,
        val gridConfidence: Float,
        val boardConfidence: Float,
        val unknownCount: Int,
        val validation: String,
        val cells: List<CellDiag>,
    ) {
        fun format(): String = buildString {
            appendLine("=== LIVE CELL DIAGNOSTICS ===")
            appendLine("frame=${frameWidth}x$frameHeight contentRoi=$contentRoi")
            appendLine("boardRoi=$boardRoi method=$gridMethod")
            appendLine(
                "gridConf=%.4f boardConf=%.4f unk=%d gate=%s".format(
                    gridConfidence, boardConfidence, unknownCount, validation,
                ),
            )
            appendLine("--- per-cell ---")
            for (c in cells) {
                append("(%d,%d) color=%s shape=%s special=%s conf=%.3f unk=%s".format(
                    c.row, c.col, c.color, c.shape, c.special, c.confidence, c.unknown,
                ))
                if (c.unknownReason.isNotBlank()) append(" reason=").append(c.unknownReason)
                c.meanRgb?.let { append(" RGB(%d,%d,%d)".format(it.first, it.second, it.third)) }
                c.meanHsv?.let {
                    append(" HSV(%.1f,%.2f,%.2f)".format(it.first, it.second, it.third))
                }
                c.meanLuma?.let { append(" L=%.1f".format(it)) }
                appendLine()
            }
        }
    }

    fun fromVision(
        result: VisionResult,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        contentRoi: ContentRoi? = null,
        cellPixels: ((row: Int, col: Int) -> IntArray?)? = null,
    ): Report {
        val cells = mutableListOf<CellDiag>()
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                val cell = result.board.get(r, c)
                val reason = when {
                    !cell.isUnknown -> ""
                    cell.occluded -> "occluded:" + (result.diagnostics["occ_${r}_${c}"] ?: "?")
                    cell.color == TileColor.UNKNOWN && cell.shape == TileShape.UNKNOWN ->
                        "color+shape UNKNOWN"
                    cell.color == TileColor.UNKNOWN -> "color UNKNOWN"
                    cell.shape == TileShape.UNKNOWN -> "shape UNKNOWN"
                    else -> "isUnknown flag"
                }
                var rgb: Triple<Int, Int, Int>? = null
                var hsv: Triple<Float, Float, Float>? = null
                var luma: Float? = null
                val px = cellPixels?.invoke(r, c)
                if (px != null && px.isNotEmpty()) {
                    var rSum = 0L; var gSum = 0L; var bSum = 0L
                    var hSum = 0.0; var sSum = 0.0; var vSum = 0.0
                    var lSum = 0.0
                    val buf = FloatArray(3)
                    for (p in px) {
                        rSum += (p shr 16) and 0xFF
                        gSum += (p shr 8) and 0xFF
                        bSum += p and 0xFF
                        PixelMath.rgbToHsv(p, buf)
                        hSum += buf[0]; sSum += buf[1]; vSum += buf[2]
                        lSum += PixelMath.luma(p)
                    }
                    val n = px.size
                    rgb = Triple((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
                    hsv = Triple((hSum / n).toFloat(), (sSum / n).toFloat(), (vSum / n).toFloat())
                    luma = (lSum / n).toFloat()
                }
                cells += CellDiag(
                    row = r, col = c,
                    color = cell.color, shape = cell.shape, special = cell.special,
                    confidence = cell.confidence, unknown = cell.isUnknown,
                    unknownReason = reason, meanRgb = rgb, meanHsv = hsv, meanLuma = luma,
                )
            }
        }
        val gate = when (val v = result.validation) {
            is ValidationResult.Pass -> "PASS"
            is ValidationResult.Hold -> "HOLD(${v.reason})"
        }
        return Report(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            contentRoi = contentRoi?.let {
                "LTRB(${it.left},${it.top},${it.right},${it.bottom})"
            } ?: (result.diagnostics["contentRoi"] ?: "—"),
            boardRoi = result.diagnostics["boardRoi"] ?: result.grid.boardRoi.let {
                "LTRB(${it.left},${it.top},${it.right},${it.bottom})"
            },
            gridMethod = result.method.name,
            gridConfidence = result.gridConfidence,
            boardConfidence = result.boardConfidence,
            unknownCount = result.unknownCount,
            validation = gate,
            cells = cells,
        )
    }
}
