package com.match3vision.analyzer.play

/**
 * Each 0.28 behavior has its own switch. Tests can turn one off without
 * touching the others. Defaults are the full-game foundation.
 */
object PlayFlags {
    var continuous: Boolean = true
    var pvpResume: Boolean = true
    var boosters: Boolean = true
    /** Off until an owner frame names the equipped booster. A target tap is not sent. */
    var targetBoosters: Boolean = false
    /** Off. A live solo loop was stuck retapping Hammer and never swiped. */
    var helps: Boolean = false
    /** Off until real frames calibrate arrow, bomb, and color-bomb pixels. */
    var specials: Boolean = false
    var modeAdapt: Boolean = true

    fun reset() {
        continuous = true
        pvpResume = true
        boosters = true
        targetBoosters = false
        helps = false
        specials = false
        modeAdapt = true
    }

    fun log(): String =
        "continuous=$continuous pvpResume=$pvpResume boosters=$boosters " +
            "targetBoosters=$targetBoosters helps=$helps specials=$specials modeAdapt=$modeAdapt"
}
