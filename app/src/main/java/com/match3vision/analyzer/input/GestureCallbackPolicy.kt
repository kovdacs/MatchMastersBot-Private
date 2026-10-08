package com.match3vision.analyzer.input

/**
 * Maps an AccessibilityService gesture callback to a dispatch result.
 *
 * [MatchMastersAccessibilityService.dispatchGesture] uses [decide] after
 * `super.dispatchGesture`. onCompleted is not VERIFY SUCCESS.
 * onCancelled and a callback that never arrives are failures, not success.
 */
object GestureCallbackPolicy {
    enum class Kind {
        NOT_SCHEDULED,
        /** onCompleted. Still not a verified move. */
        COMPLETED,
        CANCELLED,
        /** Latch timed out — callback never arrived. */
        TIMED_OUT,
        UNKNOWN,
        /** Main-thread schedule only. Not proof the gesture finished. */
        SCHEDULED_ONLY,
    }

    data class Decision(val kind: Kind, val reason: String) {
        fun toResult(gesture: GestureSpec): InputDispatchResult = when (kind) {
            Kind.COMPLETED -> InputDispatchResult.Dispatched(
                gesture,
                callbackCompleted = true,
            )
            Kind.SCHEDULED_ONLY -> InputDispatchResult.Failed(
                "SCHEDULED_ONLY — gesture scheduled only; callback was not awaited; " +
                    "not eligible for verification",
            )
            else -> InputDispatchResult.Failed(reason)
        }

        /**
         * onCompleted is still not a verified move.
         * [Kind.SCHEDULED_ONLY] is not eligible: the gesture may not have run.
         */
        fun verifyLabel(): String = when (kind) {
            Kind.COMPLETED -> VerificationPolicy.PENDING
            else -> VerificationPolicy.FAILED
        }

        fun eligibleForVerification(): Boolean = kind == Kind.COMPLETED
    }

    fun decide(
        scheduled: Boolean,
        awaitCallback: Boolean,
        callbackArrived: Boolean,
        completed: Boolean,
        cancelled: Boolean,
    ): Decision {
        if (!scheduled) {
            return Decision(Kind.NOT_SCHEDULED, "AccessibilityService.dispatchGesture returned false")
        }
        if (!awaitCallback) {
            return Decision(
                Kind.SCHEDULED_ONLY,
                "SCHEDULED_ONLY — dispatch scheduled on main thread; callback not awaited",
            )
        }
        if (!callbackArrived) {
            return Decision(
                Kind.TIMED_OUT,
                "gesture callback timeout — callback never arrived",
            )
        }
        if (cancelled) {
            return Decision(
                Kind.CANCELLED,
                "gesture onCancelled (overlay/touch conflict or system interrupt)",
            )
        }
        if (completed) {
            return Decision(Kind.COMPLETED, "onCompleted")
        }
        return Decision(Kind.UNKNOWN, "gesture callback unknown state")
    }
}
