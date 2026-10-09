package com.match3vision.analyzer.play

import com.match3vision.analyzer.hud.Json

/**
 * Data-driven booster list. Activation is always an ACTIVATE tap.
 * A target, when [RegistryEntry.needsTarget] is true, is a second tap chosen
 * by [TargetPicker]. The equipped id is not read from the HUD yet.
 */
class BoosterRegistry(val entries: List<RegistryEntry>) {
    private val byId = entries.associateBy { it.id }

    init {
        require(byId.size == entries.size) { "duplicate booster id" }
    }

    fun get(id: String): RegistryEntry? = byId[id]

    fun needsTarget(id: String): Boolean = byId[id]?.needsTarget == true

    companion object {
        const val ASSET = "booster_registry.json"
        const val ACTIVATION = "ACTIVATE tap"

        fun parse(text: String): BoosterRegistry {
            val root = Json.parse(text) as? Json.Arr ?: error("booster registry must be a JSON array")
            val entries = root.items.map { item ->
                val obj = item as? Json.Obj ?: error("booster registry entry must be an object")
                RegistryEntry(
                    id = obj.string("id"),
                    name = obj.string("name"),
                    needsTarget = obj.bool("needsTarget"),
                    targetType = obj.string("targetType"),
                    activation = obj.string("activation"),
                )
            }
            return BoosterRegistry(entries)
        }
    }
}

data class RegistryEntry(
    val id: String,
    val name: String,
    val needsTarget: Boolean,
    val targetType: String,
    val activation: String,
)
