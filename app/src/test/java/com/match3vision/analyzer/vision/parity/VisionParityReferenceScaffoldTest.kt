package com.match3vision.analyzer.vision.parity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the Python V3.1 parity scaffold on the JVM classpath.
 *
 * Until a real dump lands, JSON must stay [REFERENCE_PENDING] with empty/null
 * board fields. Do **not** invent READY data or fake Python outputs.
 *
 * Comparator PENDING short-circuit is covered by [VisionParityComparatorTest].
 */
class VisionParityReferenceScaffoldTest {

    @Test
    fun classpathReference_isReferencePending_notReady() {
        val stream = javaClass.classLoader.getResourceAsStream(REFERENCE_RESOURCE)
            ?: error("missing classpath resource $REFERENCE_RESOURCE")
        val json = stream.bufferedReader().use { it.readText() }

        assertThat(json).contains("\"status\": \"REFERENCE_PENDING\"")
        assertThat(json).doesNotContain("\"status\": \"READY\"")
        assertThat(json).contains("\"pythonVersion\": \"V3.1\"")
        // Schema keys required for a future READY dump (VisionResultExporter mirror)
        listOf(
            "imageWidth", "imageHeight", "letterboxRoi", "roiOffset",
            "gridMethod", "gridConfidence", "xBoundaries", "yBoundaries",
            "cellBoxes", "cells", "unknownCount", "gate",
            "boardConfidence", "confidence",
        ).forEach { key ->
            assertThat(json).contains("\"$key\"")
        }
        // Pending scaffold must not carry fabricated board geometry
        assertThat(json).contains("\"xBoundaries\": []")
        assertThat(json).contains("\"yBoundaries\": []")
        assertThat(json).contains("\"cellBoxes\": []")
        assertThat(json).contains("\"cells\": []")
        assertThat(json).contains("\"imageWidth\": null")
        assertThat(json).contains("\"gate\": null")
    }

    companion object {
        const val REFERENCE_RESOURCE = "vision/parity/pvp_board_reference.json"
    }
}
