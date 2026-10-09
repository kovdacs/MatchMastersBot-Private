package com.match3vision.analyzer.play

/**
 * Hammer and Shuffle. At most one help per turn. A missing charge read does
 * not tap: the button rectangles are not calibrated on an owner frame.
 */
object HelpPolicy {
    const val MAX_PER_TURN = 1

    data class Choice(val id: String, val needsTarget: Boolean, val reason: String)

    data class Charges(val hammer: Int?, val shuffle: Int?, val geometryVerified: Boolean)

    fun choose(
        hasLegalMove: Boolean,
        extraMoveAvailable: Boolean,
        charges: Charges,
        usedThisTurn: Int,
    ): Choice? {
        if (!PlayFlags.helps) return null
        if (!charges.geometryVerified) return null
        if (usedThisTurn >= MAX_PER_TURN) return null
        val hammer = charges.hammer ?: 0
        if (hammer <= 0) return null
        if (!hasLegalMove) return Choice("hammer", needsTarget = true, reason = "no 3-match")
        if (extraMoveAvailable) return Choice("hammer", needsTarget = true, reason = "enables extra move")
        return null
    }
}
