package com.match3vision.analyzer.gamemode

enum class GameMode {
    NORMAL, BOOSTER_RELATED, EVENT, CHALLENGE, TOURNAMENT, UNKNOWN_GAME_MODE,
}

class GameModeDetector {
    data class Result(val mode: GameMode, val confidence: Float, val evidence: List<String>)
    fun detect(uiLabels: List<String>): Result {
        if (uiLabels.isEmpty()) return Result(GameMode.UNKNOWN_GAME_MODE, 0f, listOf("no UI labels"))
        val upper = uiLabels.map { it.uppercase() }
        fun has(vararg keys: String) = keys.any { k -> upper.any { it.contains(k) } }
        return when {
            has("TOURNAMENT", "ARENA") -> Result(GameMode.TOURNAMENT, 0.7f, listOf("tournament keyword"))
            has("CHALLENGE", "DAILY") -> Result(GameMode.CHALLENGE, 0.7f, listOf("challenge keyword"))
            has("EVENT", "LIMITED") -> Result(GameMode.EVENT, 0.65f, listOf("event keyword"))
            has("BOOSTER", "POWER-UP", "POWERUP") -> Result(GameMode.BOOSTER_RELATED, 0.65f, listOf("booster keyword"))
            has("VS", "PVP", "MATCH") && has("NORMAL", "CLASSIC") ->
                Result(GameMode.NORMAL, 0.6f, listOf("normal/classic keyword"))
            has("VS", "PVP") -> Result(GameMode.UNKNOWN_GAME_MODE, 0.3f, listOf("pvp without mode flavor"))
            else -> Result(GameMode.UNKNOWN_GAME_MODE, 0.1f, listOf("unrecognized UI labels"))
        }
    }
}
