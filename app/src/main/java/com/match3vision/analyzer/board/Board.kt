package com.match3vision.analyzer.board

import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.VisionBoard

data class Board(
    private val tiles: Array<Array<Tile>>,
) {
    init {
        require(tiles.size == SIZE) { "Board must be ${SIZE}×${SIZE}" }
        require(tiles.all { it.size == SIZE }) { "Board must be ${SIZE}×${SIZE}" }
    }

    fun get(row: Int, col: Int): Tile {
        require(row in 0 until SIZE && col in 0 until SIZE)
        return tiles[row][col]
    }

    fun setCopy(row: Int, col: Int, tile: Tile): Board {
        val copy = Array(SIZE) { r -> Array(SIZE) { c -> tiles[r][c] } }
        copy[row][col] = tile.copy(row = row, col = col)
        return Board(copy)
    }

    fun swapCopy(r1: Int, c1: Int, r2: Int, c2: Int): Board {
        val a = get(r1, c1)
        val b = get(r2, c2)
        return setCopy(r1, c1, b).setCopy(r2, c2, a)
    }

    fun unknownCount(): Int {
        var n = 0
        forEachTile { if (it.isUnknown) n++ }
        return n
    }

    fun meanConfidence(): Float {
        var s = 0f
        var n = 0
        forEachTile { s += it.confidence; n++ }
        return if (n == 0) 0f else s / n
    }

    fun isValidStructure(): Boolean {
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            val t = tiles[r][c]
            if (t.row != r || t.col != c) return false
            if (t.confidence !in 0f..1f) return false
        }
        return true
    }

    fun contentHash(): Long {
        var h = 1125899906842597L
        forEachTile { t ->
            h = h * 31 + t.color.ordinal
            h = h * 31 + t.shape.ordinal
            h = h * 31 + t.special.ordinal
            h = h * 31 + (if (t.isUnknown) 1 else 0)
            h = h * 31 + t.starValue
        }
        return h
    }

    /**
     * Color and shape of ordinary cells. A special cell is a wildcard so a
     * glowing special does not change the key.
     */
    fun labelHash(): Long {
        var h = 1125899906842597L
        forEachTile { t ->
            if (t.special != SpecialType.NONE) {
                h = h * 31 + 1
            } else {
                h = h * 31 + t.color.ordinal
                h = h * 31 + t.shape.ordinal
            }
        }
        return h
    }

    /** True when every cell that is not a special on either board has the same color and shape. */
    fun labelsAgree(other: Board): Boolean {
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val left = get(row, col)
                val right = other.get(row, col)
                if (left.special != SpecialType.NONE || right.special != SpecialType.NONE) continue
                if (left.color != right.color || left.shape != right.shape) return false
            }
        }
        return true
    }

    fun snapshot(): Board = this

    fun forEachTile(block: (Tile) -> Unit) {
        for (r in 0 until SIZE) for (c in 0 until SIZE) block(tiles[r][c])
    }

    fun toMutableGrid(): Array<Array<Tile>> =
        Array(SIZE) { r -> Array(SIZE) { c -> tiles[r][c] } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        return tiles.contentDeepEquals(other.tiles)
    }

    override fun hashCode(): Int = tiles.contentDeepHashCode()

    companion object {
        const val SIZE = GridGeometry.GRID_SIZE

        fun fromGrid(tiles: Array<Array<Tile>>): Board = Board(tiles)

        fun fromVision(board: VisionBoard): Board {
            val tiles = Array(SIZE) { r ->
                Array(SIZE) { c ->
                    val v: CellVision = board.get(r, c)
                    Tile(
                        row = r, col = c,
                        color = v.color, shape = v.shape, special = v.special,
                        confidence = v.confidence, visible = !v.occluded, locked = false,
                    )
                }
            }
            return Board(tiles)
        }

        fun filled(tileFactory: (row: Int, col: Int) -> Tile): Board =
            Board(Array(SIZE) { r -> Array(SIZE) { c -> tileFactory(r, c) } })

        fun fromColors(colors: Array<Array<TileColor>>): Board =
            filled { r, c ->
                val color = colors[r][c]
                Tile(
                    row = r, col = c, color = color, shape = shapeFor(color),
                    special = SpecialType.NONE,
                    confidence = if (color == TileColor.UNKNOWN) 0f else 1f,
                )
            }

        fun shapeFor(color: TileColor): TileShape = when (color) {
            TileColor.B -> TileShape.STAR
            TileColor.R -> TileShape.CIRCLE
            TileColor.Y -> TileShape.TRIANGLE
            TileColor.G -> TileShape.DIAMOND
            TileColor.P -> TileShape.SQUARE
            TileColor.O -> TileShape.HEX
            TileColor.UNKNOWN -> TileShape.UNKNOWN
        }
    }
}
