package com.match3vision.analyzer.input

/**
 * What a solo ACTIVATE or help tap does with the channel result.
 *
 * A completed callback counts. A failure, cancel, timeout, or missing callback
 * does not: the booster stays retryable and the help quota is left alone.
 * A scheduled-only result is unconfirmed. The booster enters settle without
 * counting the attempt. The help phase already started is left to settle.
 */
object RecognizedTap {
    enum class Kind { CONFIRMED, UNCONFIRMED, FAILED }

    fun kind(result: InputDispatchResult): Kind = when (result) {
        is InputDispatchResult.Dispatched ->
            if (result.callbackCompleted) Kind.CONFIRMED else Kind.UNCONFIRMED
        is InputDispatchResult.Failed ->
            if (result.reason.contains("SCHEDULED_ONLY")) Kind.UNCONFIRMED else Kind.FAILED
    }

    /**
     * True when the tick must not also swipe: the booster is settling.
     * A failure leaves the session running and the attempt uncounted.
     */
    fun applyBooster(
        session: FiveMoveSession,
        result: InputDispatchResult,
        x: Float,
        y: Float,
        nowMs: Long,
        playExport: String,
        swipeSequence: Long,
        beforeHash: Long,
        beforeLabel: Long,
        beforeCircles: Int?,
    ): Boolean {
        val confirmed = kind(result) == Kind.CONFIRMED
        return when (kind(result)) {
            Kind.FAILED -> {
                val reason = (result as InputDispatchResult.Failed).reason
                session.notePlayBlock(nowMs, "booster tap not sent $reason")
                session.noteBoosterException(reason)
                false
            }
            Kind.CONFIRMED, Kind.UNCONFIRMED -> {
                val decision = session.armBoosterSettle(
                    x = x,
                    y = y,
                    nowMs = nowMs,
                    playExport = playExport,
                    swipeSequence = swipeSequence,
                    beforeHash = beforeHash,
                    beforeLabel = beforeLabel,
                    beforeCircles = beforeCircles,
                    countTap = confirmed,
                    callbackCompleted = confirmed,
                )
                decision is FiveMoveSession.Decision.Hold
            }
        }
    }

    /** True when the help tap was confirmed. Quota moves only in that case. */
    fun applyHelp(session: FiveMoveSession, result: InputDispatchResult, nowMs: Long): Boolean {
        return when (kind(result)) {
            Kind.CONFIRMED -> {
                session.noteHelpSent()
                true
            }
            Kind.UNCONFIRMED -> {
                session.notePlayBlock(nowMs, "help tap unconfirmed")
                false
            }
            Kind.FAILED -> {
                session.abandonHelp(nowMs, "help tap not sent ${(result as InputDispatchResult.Failed).reason}")
                false
            }
        }
    }
}
