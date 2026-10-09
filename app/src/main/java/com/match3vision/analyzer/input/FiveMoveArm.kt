package com.match3vision.analyzer.input

/**
 * Hungarian reasons the 5 LÉPÉS TESZT button shows. Exact strings.
 * This does not change move gates, the 60 s limit, or vision thresholds.
 */
object FiveMoveArm {
    const val NEED_A11Y = "Kisegítő szolgáltatás nincs bekapcsolva"
    const val NEED_CALIBRATION = "Előbb TESZT ÉRINTÉS kalibráció kell"
    const val NEED_BOARD = "Tábla nem látszik"
    const val NEED_START = "Először INDÍTÁS, várd meg a stabil táblát."
    const val ALREADY = "10 LÉPÉS TESZT már fut"
    const val CONTINUOUS = "Folyamatos játék fut — előbb STOP."
    const val STOPPED = "leállítva — új Indítás kell az alkalmazásban"
    const val PRESS_START = "Előbb nyomd meg: INDÍTÁS"
    const val DO_NOT_TOUCH = "Ne érintsd a képernyőt"
    const val CAP = "10 LÉPÉS refused — MOVE UNCONFIRMED cap is latched"
}
