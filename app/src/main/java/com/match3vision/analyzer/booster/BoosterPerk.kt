package com.match3vision.analyzer.booster

data class BoosterEffect(val id: String, val description: String, val scoreBias: Float, val known: Boolean)
data class PerkEffect(val id: String, val description: String, val scoreBias: Float, val known: Boolean)

class BoosterDatabase {
    private val known = mapOf(
        "GENERIC_LINE_CLEAR" to BoosterEffect("GENERIC_LINE_CLEAR", "Assumed line clear booster", 12f, true),
        "GENERIC_BOMB" to BoosterEffect("GENERIC_BOMB", "Assumed bomb booster", 18f, true),
    )
    fun get(id: String) = known[id] ?: BoosterEffect(id, "UNKNOWN booster", 0f, false)
    fun allKnown() = known.values.toList()
}

class PerkDatabase {
    private val known = mapOf(
        "GENERIC_STAR_BONUS" to PerkEffect("GENERIC_STAR_BONUS", "Assumed star perk", 8f, true),
    )
    fun get(id: String) = known[id] ?: PerkEffect(id, "UNKNOWN perk", 0f, false)
}

class BoosterDetector {
    data class Detection(val boosterId: String?, val unknown: Boolean, val note: String)
    fun detectFromScreenLabels(labels: List<String>): Detection {
        if (labels.isEmpty()) return Detection(null, true, "no booster UI labels → UNKNOWN")
        val hit = labels.firstOrNull { it.startsWith("BOOSTER:") }
        return if (hit == null) Detection(null, true, "no matched booster label → UNKNOWN")
        else Detection(hit.removePrefix("BOOSTER:"), false, "label matched")
    }
}

class PerkDetector {
    data class Detection(val perkId: String?, val unknown: Boolean, val note: String)
    fun detectFromScreenLabels(labels: List<String>): Detection {
        if (labels.isEmpty()) return Detection(null, true, "no perk UI labels → UNKNOWN")
        val hit = labels.firstOrNull { it.startsWith("PERK:") }
        return if (hit == null) Detection(null, true, "no matched perk label → UNKNOWN")
        else Detection(hit.removePrefix("PERK:"), false, "label matched")
    }
}

class BoosterSimulator(private val db: BoosterDatabase = BoosterDatabase()) {
    fun simulateBias(boosterId: String?): Float {
        if (boosterId == null) return 0f
        val e = db.get(boosterId)
        return if (e.known) e.scoreBias else 0f
    }
}

class BoosterEvaluator(
    private val db: BoosterDatabase = BoosterDatabase(),
    private val simulator: BoosterSimulator = BoosterSimulator(db),
) {
    fun evaluate(detection: BoosterDetector.Detection): Pair<Float, Boolean> {
        if (detection.unknown) return 0f to true
        return simulator.simulateBias(detection.boosterId) to false
    }
}

class PerkEvaluator(private val db: PerkDatabase = PerkDatabase()) {
    fun evaluate(detection: PerkDetector.Detection): Pair<Float, Boolean> {
        if (detection.unknown || detection.perkId == null) return 0f to true
        val e = db.get(detection.perkId)
        return (if (e.known) e.scoreBias else 0f) to !e.known
    }
}
