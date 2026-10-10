package com.match3vision.analyzer.play

/**
 * Hammer, Shuffle, and Box. At most one help per turn.
 * Shuffle only when nothing can be swapped. Hammer when there is no 3-match
 * or a hammer would open an extra move. Box only beside a matchable cluster.
 * A missing charge is not tapped.
 */
object HelpPolicy {
    const val MAX_PER_TURN = 1

    data class Choice(val id: String, val needsTarget: Boolean, val reason: String)

    data class Charges(
        val hammer: Int?,
        val shuffle: Int?,
        val geometryVerified: Boolean,
        val box: Int? = null,
    )

    fun choose(
        hasLegalMove: Boolean,
        extraMoveAvailable: Boolean,
        charges: Charges,
        usedThisTurn: Int,
        hasThreeMatch: Boolean = hasLegalMove,
        boxReady: Boolean = false,
    ): Choice? {
        if (!PlayFlags.helps) return null
        if (!charges.geometryVerified) return null
        if (usedThisTurn >= MAX_PER_TURN) return null
        val shuffle = charges.shuffle ?: 0
        val hammer = charges.hammer ?: 0
        val box = charges.box ?: 0
        if (!hasLegalMove && shuffle > 0) {
            return Choice("shuffle", needsTarget = false, reason = "no legal move")
        }
        if (hammer > 0 && !hasThreeMatch) {
            return Choice("hammer", needsTarget = true, reason = "no 3-match")
        }
        if (hammer > 0 && extraMoveAvailable) {
            return Choice("hammer", needsTarget = true, reason = "enables extra move")
        }
        if (box > 0 && boxReady) {
            return Choice("box", needsTarget = true, reason = "adjacent to a cluster")
        }
        return null
    }
}
