package com.match3vision.analyzer.play

import com.match3vision.analyzer.hud.BoosterCatalog

/**
 * Two files, two jobs. They stay separate: the catalog is research prose and
 * the registry is the play table. Merging them would turn notes into tap rules.
 *
 * [BoosterRegistry.ASSET] (`booster_registry.json`) is what play reads: id,
 * needsTarget, target type, activation. An unknown id still taps ACTIVATE.
 *
 * [BoosterCatalog.ASSET_PATH] (`booster_db.json`) is the research catalog:
 * effects, sources, and a written target note. It does not decide a tap.
 * Perks and legacy rows live only there.
 *
 * [PLAY_ONLY] ids are on the play table and have no research row yet.
 * A catalog note that starts with "none" means no second tap. "none for
 * activation" is a follow-up the play table may still target.
 */
object BoosterSources {
    const val PLAY = BoosterRegistry.ASSET
    const val RESEARCH = BoosterCatalog.ASSET_PATH

    val PLAY_ONLY = setOf("army_duck")

    private val PLAY_KINDS = setOf("booster", "perk", "powered_up_variant")

    fun inconsistencies(registry: BoosterRegistry, catalog: BoosterCatalog): List<String> {
        val problems = ArrayList<String>()
        for (id in PLAY_ONLY) {
            if (registry.get(id) == null) problems += "play-only $id missing from $PLAY"
            if (catalog.get(id) != null) problems += "play-only $id is also in $RESEARCH"
        }
        for (entry in registry.entries) {
            if (entry.activation != BoosterRegistry.ACTIVATION) {
                problems += "${entry.id} activation=${entry.activation}"
            }
            if (entry.needsTarget != (entry.targetType != "none")) {
                problems += "${entry.id} needsTarget=${entry.needsTarget} targetType=${entry.targetType}"
            }
            if (entry.id in PLAY_ONLY) continue
            val researched = catalog.get(entry.id)
            if (researched == null) {
                problems += "registry id ${entry.id} missing from $RESEARCH"
                continue
            }
            if (researched.kind !in PLAY_KINDS) {
                problems += "${entry.id} kind=${researched.kind}"
            }
            val note = researched.targetRequirement.trim().lowercase()
            val noSecondTap = note == "none" || note.startsWith("none ")
            val followUp = note.startsWith("none for activation")
            if (noSecondTap && entry.needsTarget && !followUp) {
                problems +=
                    "${entry.id} registry needsTarget but catalog says no second tap " +
                    "(${researched.targetRequirement})"
            }
        }
        return problems
    }
}
