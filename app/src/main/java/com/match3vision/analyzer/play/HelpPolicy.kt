package com.match3vision.analyzer.play

/**
 * Hammer and Shuffle (Box only behind [PlayFlags.boxHelp]).
 *
 * 0.28.7 rules:
 * - Only on our turn, with the booster not ACTIVATE-ready and charges read.
 * - Shuffle when there is no legal move or every legal move is very weak.
 * - Hammer only when no legal swap gives an extra move (4+ match) and a
 *   hammer on a verified cell would create one.
 * - At most one attempt per help type per stall (reset by a verified swipe).
 * - A help that failed once in this match is never tried again in it.
 * A missing or unread charge is not tapped.
 */
object HelpPolicy {
    /** One attempt per type (hammer, shuffle) per stall. */
    const val MAX_PER_TURN = 2

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
        hammerMakesExtra: Boolean = false,
        allWeak: Boolean = !hasLegalMove,
        unavailable: Set<String> = emptySet(),
        triedThisStall: Set<String> = emptySet(),
        boosterReady: Boolean = false,
        ourTurn: Boolean = true,
    ): Choice? {
        if (!PlayFlags.helps) return null
        if (!charges.geometryVerified) return null
        if (!ourTurn || boosterReady) return null
        if (usedThisTurn >= MAX_PER_TURN) return null
        fun ready(id: String, charge: Int?): Boolean =
            (charge ?: 0) > 0 && id !in unavailable && id !in triedThisStall
        if (!hasLegalMove && ready("shuffle", charges.shuffle)) {
            return Choice("shuffle", needsTarget = false, reason = "no legal move")
        }
        if (hasLegalMove && !extraMoveAvailable && hammerMakesExtra && ready("hammer", charges.hammer)) {
            return Choice("hammer", needsTarget = true, reason = "enables extra move")
        }
        if (hasLegalMove && allWeak && !extraMoveAvailable && ready("shuffle", charges.shuffle)) {
            return Choice("shuffle", needsTarget = false, reason = "all moves weak")
        }
        if (PlayFlags.boxHelp && boxReady && ready("box", charges.box)) {
            return Choice("box", needsTarget = true, reason = "adjacent to a cluster")
        }
        return null
    }
}
