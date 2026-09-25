package com.match3vision.analyzer.board

import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape

enum class TileOwner { NONE, PLAYER, OPPONENT, UNKNOWN }

data class Tile(
    val row: Int,
    val col: Int,
    val color: TileColor,
    val shape: TileShape,
    val special: SpecialType,
    val owner: TileOwner = TileOwner.NONE,
    val starValue: Int = 0,
    val confidence: Float = 0f,
    val visible: Boolean = true,
    val locked: Boolean = false,
) {
    val isUnknown: Boolean
        get() = color == TileColor.UNKNOWN || shape == TileShape.UNKNOWN || !visible

    val tileType: TileType
        get() = when {
            isUnknown -> TileType.UNKNOWN
            color == TileColor.B -> TileType.BLUE
            color == TileColor.R -> TileType.RED
            color == TileColor.Y -> TileType.YELLOW
            color == TileColor.G -> TileType.GREEN
            color == TileColor.P -> TileType.PURPLE
            color == TileColor.O -> TileType.ORANGE
            else -> TileType.UNKNOWN
        }

    companion object {
        fun empty(row: Int, col: Int) = Tile(
            row = row, col = col,
            color = TileColor.UNKNOWN, shape = TileShape.UNKNOWN,
            special = SpecialType.NONE, confidence = 0f, visible = false,
        )
        fun unknown(row: Int, col: Int, confidence: Float = 0f) = Tile(
            row = row, col = col,
            color = TileColor.UNKNOWN, shape = TileShape.UNKNOWN,
            special = SpecialType.NONE, confidence = confidence, visible = true,
        )
    }
}
