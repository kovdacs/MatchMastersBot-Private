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
 * High-confidence near-tie contradiction → UNKNOWN.
 * When one channel clearly dominates (e.g. strong purple on a mushroom/+3
 * booster vs a false STAR silhouette), prefer the stronger channel so typical
 * clear PvP boards stay within [VisionThresholds.MAX_UNKNOWN_COUNT].
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

    /**
     * Acceptable shapes per color. Orange MM gems are inverted triangles in capture
     * but classic palette also uses HEX — both are consistent (not contradictions).
     */
    private val ACCEPTABLE: Map<TileColor, Set<TileShape>> = mapOf(
        TileColor.B to setOf(TileShape.STAR),
        TileColor.R to setOf(TileShape.CIRCLE),
        TileColor.Y to setOf(TileShape.TRIANGLE),
        TileColor.G to setOf(TileShape.DIAMOND),
        TileColor.P to setOf(TileShape.SQUARE),
        TileColor.O to setOf(TileShape.HEX, TileShape.TRIANGLE),
    )

    /**
     * Minimum |colorConf − shapeConf| to trust the stronger channel on a
     * high-confidence disagreement. Near-ties (e.g. 0.95 vs 0.92) stay UNKNOWN.
     */
    const val DOMINANCE_MARGIN = 0.08f

    fun expectedShape(color: TileColor): TileShape? = EXPECTED[color]

    fun expectedColor(shape: TileShape): TileColor? =
        EXPECTED.entries.firstOrNull { it.value == shape }?.key

    private fun isAcceptable(color: TileColor, shape: TileShape): Boolean =
        ACCEPTABLE[color]?.contains(shape) == true

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
        val cell = reconcilePair(color, colorConf, shape, shapeConf, special)
        if (special == SpecialType.NONE) return cell
        return cell.copy(
            special = special,
            isUnknown = false,
            confidence = maxOf(cell.confidence, VisionThresholds.SPECIAL_MIN_CONFIDENCE).coerceIn(0f, 1f),
        )
    }

    private fun reconcilePair(
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

        // Both known and disagree with high confidence
        if (color != TileColor.UNKNOWN && shape != TileShape.UNKNOWN &&
            !isAcceptable(color, shape) &&
            colorConf >= high && shapeConf >= high
        ) {
            val margin = colorConf - shapeConf
            return when {
                // Stronger color (mushrooms/+3 boosters, purple squares misread as STAR)
                margin >= DOMINANCE_MARGIN -> trustColor(color, colorConf, expectedForColor, special)
                // Stronger shape
                -margin >= DOMINANCE_MARGIN -> trustShape(shape, shapeConf, special)
                // Near-tie → UNKNOWN (keeps B@0.95+CIRCLE@0.92 unknown)
                else -> CellVision(
                    color = TileColor.UNKNOWN,
                    shape = TileShape.UNKNOWN,
                    special = SpecialType.NONE,
                    occluded = false,
                    confidence = minOf(colorConf, shapeConf),
                    isUnknown = true,
                )
            }
        }

        // Prefer consistent pair; if shape unknown, trust color (+ expected shape soft)
        // if color unknown, trust shape (+ expected color soft)
        val finalColor: TileColor
        val finalShape: TileShape
        val conf: Float

        when {
            color != TileColor.UNKNOWN && shape != TileShape.UNKNOWN &&
                isAcceptable(color, shape) -> {
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

    private fun trustColor(
        color: TileColor,
        colorConf: Float,
        expectedForColor: TileShape?,
        special: SpecialType,
    ): CellVision {
        val shape = expectedForColor ?: TileShape.UNKNOWN
        val unknown = shape == TileShape.UNKNOWN
        return CellVision(
            color = color,
            shape = shape,
            special = if (unknown) SpecialType.NONE else special,
            occluded = false,
            confidence = (colorConf * 0.85f).coerceIn(0f, 1f),
            isUnknown = unknown,
        )
    }

    private fun trustShape(
        shape: TileShape,
        shapeConf: Float,
        special: SpecialType,
    ): CellVision {
        val color = expectedColor(shape) ?: TileColor.UNKNOWN
        val unknown = color == TileColor.UNKNOWN
        return CellVision(
            color = color,
            shape = shape,
            special = if (unknown) SpecialType.NONE else special,
            occluded = false,
            confidence = (shapeConf * 0.85f).coerceIn(0f, 1f),
            isUnknown = unknown,
        )
    }
}
