package com.match3vision.analyzer.input

/**
 * Turns on the same [InputEnableSwitch] a swipe uses, for the duration of one tap.
 * [AutoPlayController.dispatchFiveMoveOnce] does this around [AutomaticInputEngine].
 * The five-move loop leaves the switch off otherwise, so a tap that only reads it
 * is rejected as "input disabled (live)".
 */
object RecognizedTapDispatch {
    fun <T> whileInputEnabled(switch: InputEnableSwitch, block: () -> T): T {
        switch.setEnabled(true)
        return try {
            block()
        } finally {
            switch.setEnabled(false)
        }
    }
}
