package com.match3vision.analyzer.moves

data class Move(
    val r1: Int,
    val c1: Int,
    val r2: Int,
    val c2: Int,
) {
    init { require(r1 in 0..6 && c1 in 0..6 && r2 in 0..6 && c2 in 0..6) }

    fun normalized(): Move =
        if (r1 < r2 || (r1 == r2 && c1 <= c2)) this else Move(r2, c2, r1, c1)

    override fun toString(): String = "($r1,$c1)↔($r2,$c2)"
}
