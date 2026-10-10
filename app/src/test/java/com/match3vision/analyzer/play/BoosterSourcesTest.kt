package com.match3vision.analyzer.play

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.hud.BoosterCatalog
import java.io.File
import org.junit.Test

class BoosterSourcesTest {
    @Test
    fun registryAndCatalogAgree() {
        assertThat(BoosterSources.PLAY).isEqualTo("booster_registry.json")
        assertThat(BoosterSources.RESEARCH).isEqualTo("booster_db.json")
        val registry = BoosterRegistry.parse(asset(BoosterSources.PLAY).readText())
        val catalog = BoosterCatalog.parse(asset(BoosterSources.RESEARCH).readText())
        assertThat(BoosterSources.inconsistencies(registry, catalog)).isEmpty()
    }

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .firstOrNull { it.exists() }
            ?: error("$name not found")
}
