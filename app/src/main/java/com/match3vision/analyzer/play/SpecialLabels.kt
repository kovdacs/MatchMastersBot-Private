package com.match3vision.analyzer.play

import com.match3vision.analyzer.vision.SpecialType

/**
 * Names the simulator already models. Row versus column is the arrow axis
 * when the detector measured one; otherwise the label stays "arrow".
 * Axis numbers match [com.match3vision.analyzer.moves.MatchShape].
 */
object SpecialLabels {
    const val AXIS_ROW = com.match3vision.analyzer.vision.SpecialDetector.AXIS_ROW
    const val AXIS_COL = com.match3vision.analyzer.vision.SpecialDetector.AXIS_COL

    fun label(special: SpecialType, axis: Int = 0): String = when {
        special == SpecialType.TWO_WAY_ARROW && axis == AXIS_ROW -> "arrow-row"
        special == SpecialType.TWO_WAY_ARROW && axis == AXIS_COL -> "arrow-col"
        special == SpecialType.TWO_WAY_ARROW -> "arrow"
        special == SpecialType.BOMB -> "bomb"
        special == SpecialType.LIGHTNING -> "color-bomb"
        else -> "none"
    }
}
