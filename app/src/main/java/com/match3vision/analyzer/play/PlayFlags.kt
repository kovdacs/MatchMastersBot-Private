package com.match3vision.analyzer.play

/**
 * Each 0.28 behavior has its own switch. Tests can turn one off without
 * touching the others. Defaults are the full-game foundation.
 */
object PlayFlags {
    var continuous: Boolean = true
    var pvpResume: Boolean = true
    var boosters: Boolean = true
    var targetBoosters: Boolean = true
    var helps: Boolean = true
    var specials: Boolean = true
    var modeAdapt: Boolean = true

    fun reset() {
        continuous = true
        pvpResume = true
        boosters = true
        targetBoosters = true
        helps = true
        specials = true
        modeAdapt = true
    }

    fun log(): String =
        "continuous=$continuous pvpResume=$pvpResume boosters=$boosters " +
            "targetBoosters=$targetBoosters helps=$helps specials=$specials modeAdapt=$modeAdapt"
}
