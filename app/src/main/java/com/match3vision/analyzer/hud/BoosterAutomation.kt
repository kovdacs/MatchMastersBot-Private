package com.match3vision.analyzer.hud

import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor

/**
 * Data-driven booster and perk planner. Not wired into the bubble.
 *
 * [ENABLED] stays false. [SoloBooster] remains the only live ACTIVATE path,
 * and that path stays behind the debug toggle. Calling [plan] from a test
 * does not dispatch a gesture.
 *
 * A tap is allowed only for a phone-verified `NO_TARGET_TAP` booster, on the
 * player's LEFT card, while the word ACTIVATE is showing, after the player's
 * own 4+ extra moves are gone, and only when a known precondition holds.
 * Perks are never tapped: their button is not calibrated. Anything unsure
 * latches and must not be tapped again.
 */
object BoosterAutomation {
    const val ENABLED = false

    const val CATEGORY_NO_TARGET = "NO_TARGET_TAP"
    const val KIND_PERK = "perk"

    fun plan(request: Request): Decision {
        if (request.alreadyTapped) {
            return Decision.Refuse("already tapped this session", latch = true)
        }
        val id = request.identity
            ?: return Decision.Refuse("booster identity not recognized", latch = false)
        val entry = request.catalog.get(id)
            ?: return Decision.Refuse("unknown booster id", latch = true)
        if (entry.category != CATEGORY_NO_TARGET) {
            return Decision.Refuse("category ${entry.category} is not a single tap", latch = true)
        }
        if (id !in request.phoneVerified) {
            return Decision.Refuse("not verified on this phone", latch = true)
        }
        if (request.card.side != CardSide.LEFT) {
            return Decision.Refuse("ACTIVATE is only read on the left card", latch = true)
        }
        when (readiness(request.card.label)) {
            Readiness.CHARGING -> return Decision.Wait("bar is ${request.card.label}")
            Readiness.FULL -> return Decision.Wait("waiting for ACTIVATE")
            Readiness.UNREAD -> return Decision.Wait("card not readable")
            Readiness.ACTIVATE -> Unit
        }
        if (request.extraMoveAvailable) {
            return Decision.Wait("extra move still on the board")
        }
        when (val gate = precondition(entry, request.board)) {
            is PreconditionResult.Wait -> return Decision.Wait(gate.reason)
            is PreconditionResult.Refuse -> return Decision.Refuse(gate.reason, latch = true)
            PreconditionResult.Ok -> Unit
        }
        if (entry.kind == KIND_PERK) {
            return Decision.Refuse("perk tap point is not calibrated", latch = true)
        }
        return Decision.Tap(entry.id, Verification())
    }

    /**
     * One attempt. A stable PASS whose board changed counts as verified.
     * Any other result latches: do not tap again.
     */
    fun settle(boardChanged: Boolean, stablePass: Boolean): Decision.Refuse {
        val verified = boardChanged && stablePass
        val reason = if (verified) {
            "verified; do not tap again"
        } else {
            "not verified; do not tap again"
        }
        return Decision.Refuse(reason, latch = true)
    }

    internal fun classify(targetRequirement: String): Precondition = when {
        targetRequirement.trim().startsWith("none", ignoreCase = true) -> Precondition.NONE
        targetRequirement.contains("YELLOW", ignoreCase = true) &&
            !targetRequirement.contains("special", ignoreCase = true) -> Precondition.YELLOW
        targetRequirement.equals("at least one special piece on board", ignoreCase = true) ->
            Precondition.SPECIAL
        else -> Precondition.UNRECOGNIZED
    }

    private fun precondition(entry: BoosterEntry, board: Board?): PreconditionResult {
        return when (classify(entry.targetRequirement)) {
            Precondition.NONE -> {
                if (board == null || board.unknownCount() > 0) {
                    PreconditionResult.Wait("board not stable enough to tap")
                } else {
                    PreconditionResult.Ok
                }
            }
            Precondition.YELLOW -> when {
                board == null || board.unknownCount() > 0 ->
                    PreconditionResult.Wait("yellow not confirmed")
                !hasColor(board, TileColor.Y) ->
                    PreconditionResult.Wait("needs a yellow piece")
                else -> PreconditionResult.Ok
            }
            Precondition.SPECIAL -> when {
                board == null || board.unknownCount() > 0 ->
                    PreconditionResult.Wait("special not confirmed")
                !hasSpecial(board) ->
                    PreconditionResult.Wait("needs a special piece")
                else -> PreconditionResult.Ok
            }
            Precondition.UNRECOGNIZED ->
                PreconditionResult.Refuse("precondition not understood: ${entry.targetRequirement}")
        }
    }

    private fun hasColor(board: Board, color: TileColor): Boolean {
        var found = false
        board.forEachTile { if (!it.isUnknown && it.color == color) found = true }
        return found
    }

    private fun hasSpecial(board: Board): Boolean {
        var found = false
        board.forEachTile { if (!it.isUnknown && it.special != SpecialType.NONE) found = true }
        return found
    }

    private fun readiness(label: String): Readiness = when (label) {
        "ACTIVATE" -> Readiness.ACTIVATE
        "FULL" -> Readiness.FULL
        else -> {
            val match = Regex("""^([0-7])/7$""").matchEntire(label)
            when {
                match == null -> Readiness.UNREAD
                match.groupValues[1] == "7" -> Readiness.FULL
                else -> Readiness.CHARGING
            }
        }
    }

    private enum class Readiness { ACTIVATE, FULL, CHARGING, UNREAD }

    internal enum class Precondition { NONE, YELLOW, SPECIAL, UNRECOGNIZED }

    private sealed class PreconditionResult {
        data object Ok : PreconditionResult()
        data class Wait(val reason: String) : PreconditionResult()
        data class Refuse(val reason: String) : PreconditionResult()
    }
}

enum class CardSide { LEFT, RIGHT }

data class OwnCard(val side: CardSide, val label: String) {
    companion object {
        fun left(label: String) = OwnCard(CardSide.LEFT, label)
    }
}

data class Request(
    val catalog: BoosterCatalog,
    val identity: String?,
    val card: OwnCard,
    val board: Board?,
    val extraMoveAvailable: Boolean,
    val phoneVerified: Set<String> = emptySet(),
    val alreadyTapped: Boolean = false,
)

data class Verification(
    val requiresStablePass: Boolean = true,
    val requiresBoardChange: Boolean = true,
    val latchIfUnsure: Boolean = true,
)

sealed class Decision {
    data class Tap(val id: String, val verification: Verification) : Decision()
    data class Wait(val reason: String) : Decision()
    data class Refuse(val reason: String, val latch: Boolean) : Decision()
}
