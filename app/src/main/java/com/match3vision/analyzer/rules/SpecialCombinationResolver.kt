package com.match3vision.analyzer.rules

import com.match3vision.analyzer.vision.SpecialType

/** Generic special+special resolutions (ASSUMPTIONS). */
class SpecialCombinationResolver {
    enum class ComboEffect {
        CLEAR_ROW, CLEAR_COL, CLEAR_ROW_AND_COL, CLEAR_3X3, CLEAR_5X5,
        CLEAR_COLOR, BOARD_WIDE, UNCERTAIN, NONE,
    }

    data class Resolution(val effect: ComboEffect, val uncertain: Boolean, val note: String)

    fun resolve(a: SpecialType, b: SpecialType): Resolution {
        if (a == SpecialType.NONE && b == SpecialType.NONE) {
            return Resolution(ComboEffect.NONE, false, "no specials")
        }
        val types = setOf(a, b).filter { it != SpecialType.NONE }.toSet()
        return when {
            a == SpecialType.TWO_WAY_ARROW && b == SpecialType.TWO_WAY_ARROW ->
                Resolution(ComboEffect.CLEAR_ROW_AND_COL, false, "arrow+arrow ASSUMPTION")
            a == SpecialType.BOMB && b == SpecialType.BOMB ->
                Resolution(ComboEffect.CLEAR_5X5, false, "bomb+bomb ASSUMPTION")
            SpecialType.LIGHTNING in types ->
                Resolution(ComboEffect.CLEAR_COLOR, false, "lightning combo ASSUMPTION")
            types == setOf(SpecialType.TWO_WAY_ARROW, SpecialType.BOMB) ->
                Resolution(ComboEffect.CLEAR_ROW_AND_COL, false, "arrow+bomb ASSUMPTION")
            types == setOf(SpecialType.TWO_WAY_ARROW) ->
                Resolution(ComboEffect.CLEAR_ROW, false, "single arrow")
            types == setOf(SpecialType.BOMB) ->
                Resolution(ComboEffect.CLEAR_3X3, false, "single bomb")
            else -> Resolution(ComboEffect.UNCERTAIN, true, "unknown special combo")
        }
    }
}
