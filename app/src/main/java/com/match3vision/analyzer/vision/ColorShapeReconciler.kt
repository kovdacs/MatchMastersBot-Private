package com.match3vision.analyzer.vision

/**
 * Expected color↔shape pairs for this prototype's game palette:
 * - blue → star
 * - red → circle
 * - yellow → triangle
 * - green → diamond
 * - purple → square
 * - orange → hex/gem
 *
 * High-confidence contradiction → UNKNOWN.
 */
object ColorShapeReconciler {

    private val EXPECTED: Map<TileColor, TileShape> = mapOf(
        TileColor.B to TileShape.STAR,
        TileColor.R to TileShape.CIRCLE,
        TileColor.Y to TileShape.TRIANGLE,
        TileColor.G to TileShape.DIAMOND,
        TileColor.P to TileShape.SQUARE,
        TileColor.O to TileShape.HEX,
    )

    fun expectedShape(color: TileColor): TileShape? = EXPECTED[color]

    fun expectedColor(shape: TileShape): TileColor? =
        EXPECTED.entries.firstOrNull { it.value == shape }?.key

    /**
     * @return reconciled [CellVision] (non-occluded path). Special is passed through.
     */
    fun reconcile(
        color: TileColor,
        colorConf: Float,
        shape: TileShape,
        shapeConf: Float,
        special: SpecialType,
    ): CellVision {
        if (color == TileColor.UNKNOWN && shape == TileShape.UNKNOWN) {
            return CellVision.unknown(confidence = minOf(colorConf, shapeConf))
        }

        val expectedForColor = EXPECTED[color]
        val high = VisionThresholds.RECONCILE_HIGH_CONFIDENCE

        // Both known and disagree with high confidence → UNKNOWN
        if (color != TileColor.UNKNOWN && shape != TileShape.UNKNOWN &&
            expectedForColor != null && expectedForColor != shape &&
            colorConf >= high && shapeConf >= high
        ) {
            return CellVision(
                color = TileColor.UNKNOWN,
                shape = TileShape.UNKNOWN,
                special = SpecialType.NONE,
                occluded = false,
                confidence = minOf(colorConf, shapeConf),
                isUnknown = true,
            )
        }

        // Prefer consistent pair; if shape unknown, trust color (+ expected shape soft)
        // if color unknown, trust shape (+ expected color soft)
        val finalColor: TileColor
        val finalShape: TileShape
        val conf: Float

        when {
            color != TileColor.UNKNOWN && shape != TileShape.UNKNOWN &&
                expectedForColor == shape -> {
                finalColor = color
                finalShape = shape
                conf = (colorConf * 0.55f + shapeConf * 0.45f).coerceIn(0f, 1f)
            }
            color != TileColor.UNKNOWN && (shape == TileShape.UNKNOWN || shapeConf < high) -> {
                finalColor = color
                finalShape = expectedForColor ?: TileShape.UNKNOWN
                conf = colorConf * 0.85f
            }
            shape != TileShape.UNKNOWN && (color == TileColor.UNKNOWN || colorConf < high) -> {
                finalShape = shape
                finalColor = expectedColor(shape) ?: TileColor.UNKNOWN
                conf = shapeConf * 0.85f
            }
            else -> {
                // Mild disagreement at low confidence — mark unknown rather than guess
                finalColor = TileColor.UNKNOWN
                finalShape = TileShape.UNKNOWN
                conf = minOf(colorConf, shapeConf) * 0.5f
            }
        }

        val unknown = finalColor == TileColor.UNKNOWN || finalShape == TileShape.UNKNOWN
        return CellVision(
            color = finalColor,
            shape = finalShape,
            special = if (unknown) SpecialType.NONE else special,
            occluded = false,
            confidence = conf.coerceIn(0f, 1f),
            isUnknown = unknown,
        )
    }
}
