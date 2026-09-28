package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.parity.VisionResultExporter
import org.junit.Assume
import org.junit.Test
import kotlin.math.abs

/**
 * REAL_FRAME structured Android export regression for `pvp_board.jpg`.
 *
 * Runs [VisionPipeline] → [VisionResultExporter], asserts PASS + stable key
 * numerics / cell labels against the checked-in golden
 * (`real_frames/pvp_board_android_export.json`), and verifies export schema.
 *
 * **Android export only** — not a Python V3.1 dump.
 * `REFERENCE_PENDING=YES`, `PYTHON_REFERENCE_AVAILABLE=NO`, `PARITY_VERIFIED=NO`.
 *
 * Detector math / ROI / thresholds are untouched; this only serializes + gates.
 */
class RealFrameExportTest {

    private val pipeline = VisionPipeline()

    @Test
    fun realFrame_pvpBoard_androidExport_matchesGoldenAndSchema() {
        Assume.assumeTrue(
            "REAL_FRAME_MISSING: ${RealFrameLoader.PVP_BOARD_RESOURCE}",
            RealFrameLoader.resourceExists(),
        )
        val golden = loadGolden()
        val frame = RealFrameLoader.loadFromResource()
            ?: error("resourceExists true but load failed")

        val result = pipeline.analyze(
            frame.pixels,
            frame.width,
            frame.height,
            ContentRoi.full(frame.width, frame.height),
        )

        // Mandatory REAL_FRAME PASS gate (unchanged thresholds).
        VisionDiagnostics.assertOrDump(
            result,
            result.validation.isPass,
            "REAL_FRAME export regression: primary must PASS",
        )
        assertThat(result.unknownCount).isEqualTo(EXPECTED_UNKNOWN)
        assertThat(result.gridConfidence).isWithin(CONF_EPS).of(EXPECTED_GRID_CONF)
        assertThat(result.boardConfidence).isWithin(CONF_EPS).of(EXPECTED_BOARD_CONF)
        assertThat(result.method).isEqualTo(GridMethod.PROJECTION)

        val roi = result.grid.boardRoi
        assertThat(roi.left).isEqualTo(EXPECTED_ROI_LEFT)
        assertThat(roi.top).isEqualTo(EXPECTED_ROI_TOP)
        assertThat(roi.right).isEqualTo(EXPECTED_ROI_RIGHT)
        assertThat(roi.bottom).isEqualTo(EXPECTED_ROI_BOTTOM)

        // Boundaries: edges lock to board ROI; internal gutters match golden (±0.5px).
        assertThat(result.grid.xBoundaries).hasLength(8)
        assertThat(result.grid.yBoundaries).hasLength(8)
        assertThat(result.grid.xBoundaries[0]).isWithin(0.01f).of(EXPECTED_ROI_LEFT.toFloat())
        assertThat(result.grid.xBoundaries[7]).isWithin(0.01f).of(EXPECTED_ROI_RIGHT.toFloat())
        assertThat(result.grid.yBoundaries[0]).isWithin(0.01f).of(EXPECTED_ROI_TOP.toFloat())
        assertThat(result.grid.yBoundaries[7]).isWithin(0.01f).of(EXPECTED_ROI_BOTTOM.toFloat())
        for (i in golden.xBoundaries.indices) {
            assertWithMessage("xBoundaries[$i]").that(result.grid.xBoundaries[i]).isWithin(BOUNDARY_EPS).of(golden.xBoundaries[i])
            assertWithMessage("yBoundaries[$i]").that(result.grid.yBoundaries[i]).isWithin(BOUNDARY_EPS).of(golden.yBoundaries[i])
        }

        // Per-cell reconciled labels + occlusion/unknown + confidence snapshot.
        assertThat(golden.cells).hasSize(49)
        var unk = 0
        for (cell in golden.cells) {
            val live = result.board.get(cell.row, cell.col)
            val box = result.grid.cellBox(cell.row, cell.col)
            assertWithMessage("color(${cell.row},${cell.col})").that(live.color.name).isEqualTo(cell.color)
            assertWithMessage("shape(${cell.row},${cell.col})").that(live.shape.name).isEqualTo(cell.shape)
            assertWithMessage("special(${cell.row},${cell.col})").that(live.special.name).isEqualTo(cell.special)
            assertWithMessage("occ(${cell.row},${cell.col})").that(live.occluded).isEqualTo(cell.occlusion)
            assertWithMessage("unk(${cell.row},${cell.col})").that(live.isUnknown).isEqualTo(cell.isUnknown)
            assertWithMessage("conf(${cell.row},${cell.col})").that(abs(live.confidence - cell.confidence)).isAtMost(CELL_CONF_EPS)
            assertThat(box.centerX()).isWithin(0.51f).of(cell.centerX)
            assertThat(box.centerY()).isWithin(0.51f).of(cell.centerY)
            val expectedFinal = if (cell.isUnknown) {
                "UNKNOWN"
            } else {
                "${cell.color}/${cell.shape}/${cell.special}"
            }
            assertThat(cell.finalTile).isEqualTo(expectedFinal)
            if (live.isUnknown) unk++
        }
        assertThat(unk).isEqualTo(EXPECTED_UNKNOWN)

        // Live exporter schema + ANDROID_EXPORT marker (not Python READY).
        val json = VisionResultExporter.toJson(
            result = result,
            imageWidth = frame.width,
            imageHeight = frame.height,
            letterboxRoi = ContentRoi.full(frame.width, frame.height),
            pretty = true,
            frameSource = RealFrameLoader.PVP_BOARD_RESOURCE,
        )
        REQUIRED_SCHEMA_KEYS.forEach { key ->
            assertThat(json).contains("\"$key\"")
        }
        assertThat(json).contains("\"status\": \"ANDROID_EXPORT\"")
        assertThat(json).contains("\"gate\": \"PASS\"")
        assertThat(json).contains("\"validation\": \"PASS\"")
        assertThat(json).contains("\"boardRoi\"")
        assertThat(json).contains("\"centerX\"")
        assertThat(json).contains("\"centerY\"")
        assertThat(json).contains("\"finalTile\"")
        assertThat(json).doesNotContain("\"status\": \"READY\"")
        assertThat(json).contains("PARITY_VERIFIED=NO")
        assertThat(json).contains("REFERENCE_PENDING=YES")

        // Golden file itself must stay Android export, never fake Python READY.
        assertThat(golden.status).isEqualTo("ANDROID_EXPORT")
        assertThat(golden.gate).isEqualTo("PASS")
        assertThat(golden.unknownCount).isEqualTo(EXPECTED_UNKNOWN)

        println(
            "REAL_FRAME_EXPORT ok path=$GOLDEN_RESOURCE " +
                "gridConf=${"%.4f".format(result.gridConfidence)} " +
                "boardConf=${"%.4f".format(result.boardConfidence)} " +
                "unk=${result.unknownCount} gate=PASS " +
                "boardRoi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "parity=PENDING android_export_only",
        )
    }

    @Test
    fun goldenClasspath_isAndroidExport_notPythonReady() {
        val stream = javaClass.classLoader.getResourceAsStream(GOLDEN_RESOURCE)
            ?: error("missing $GOLDEN_RESOURCE")
        val text = stream.bufferedReader().use { it.readText() }
        assertThat(text).contains("\"status\": \"ANDROID_EXPORT\"")
        assertThat(text).doesNotContain("\"status\": \"READY\"")
        assertThat(text).contains("PARITY_VERIFIED=NO")
        assertThat(text).contains("\"gate\": \"PASS\"")
        assertThat(text).contains("\"unknownCount\": 0")
        assertThat(text).contains("\"gridConfidence\": 0.9872")
        assertThat(text).contains("\"boardConfidence\": 1.0")
        REQUIRED_SCHEMA_KEYS.forEach { key ->
            assertThat(text).contains("\"$key\"")
        }
    }

    private fun loadGolden(): GoldenExport {
        val stream = javaClass.classLoader.getResourceAsStream(GOLDEN_RESOURCE)
            ?: error("missing classpath golden $GOLDEN_RESOURCE")
        val text = stream.bufferedReader().use { it.readText() }
        return parseGolden(text)
    }

    /**
     * Minimal golden parser (no org.json). Expects the checked-in pretty JSON shape.
     */
    private fun parseGolden(text: String): GoldenExport {
        fun str(key: String): String {
            val re = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
            return re.find(text)?.groupValues?.get(1)
                ?: error("missing string key $key")
        }
        fun num(key: String): Float {
            val re = Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            return re.find(text)?.groupValues?.get(1)?.toFloat()
                ?: error("missing num key $key")
        }
        fun int(key: String): Int = num(key).toInt()
        fun floatArray(key: String): FloatArray {
            val re = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            val body = re.find(text)?.groupValues?.get(1) ?: error("missing array $key")
            return body.split(",").map { it.trim().toFloat() }.toFloatArray()
        }

        val cells = mutableListOf<GoldenCell>()
        val cellRe = Regex(
            "\\{\\s*\"row\"\\s*:\\s*(\\d+)\\s*,\\s*\"col\"\\s*:\\s*(\\d+)\\s*," +
                "\\s*\"color\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"shape\"\\s*:\\s*\"([^\"]+)\"\\s*," +
                "\\s*\"special\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"occlusion\"\\s*:\\s*(true|false)\\s*," +
                "\\s*\"confidence\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"isUnknown\"\\s*:\\s*(true|false)\\s*," +
                "\\s*\"finalTile\"\\s*:\\s*\"([^\"]+)\"\\s*," +
                "\\s*\"centerX\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"centerY\"\\s*:\\s*([0-9.]+)\\s*\\}",
        )
        // Prefer the "cells" array section (second large array of objects with finalTile)
        val cellsIdx = text.indexOf("\"cells\"")
        require(cellsIdx >= 0) { "no cells key" }
        val cellsSection = text.substring(cellsIdx)
        for (m in cellRe.findAll(cellsSection)) {
            cells.add(
                GoldenCell(
                    row = m.groupValues[1].toInt(),
                    col = m.groupValues[2].toInt(),
                    color = m.groupValues[3],
                    shape = m.groupValues[4],
                    special = m.groupValues[5],
                    occlusion = m.groupValues[6].toBoolean(),
                    confidence = m.groupValues[7].toFloat(),
                    isUnknown = m.groupValues[8].toBoolean(),
                    finalTile = m.groupValues[9],
                    centerX = m.groupValues[10].toFloat(),
                    centerY = m.groupValues[11].toFloat(),
                ),
            )
        }
        require(cells.size == 49) { "expected 49 golden cells, got ${cells.size}" }

        return GoldenExport(
            status = str("status"),
            gate = str("gate"),
            unknownCount = int("unknownCount"),
            gridConfidence = num("gridConfidence"),
            boardConfidence = num("boardConfidence"),
            xBoundaries = floatArray("xBoundaries"),
            yBoundaries = floatArray("yBoundaries"),
            cells = cells,
        )
    }

    data class GoldenCell(
        val row: Int,
        val col: Int,
        val color: String,
        val shape: String,
        val special: String,
        val occlusion: Boolean,
        val confidence: Float,
        val isUnknown: Boolean,
        val finalTile: String,
        val centerX: Float,
        val centerY: Float,
    )

    data class GoldenExport(
        val status: String,
        val gate: String,
        val unknownCount: Int,
        val gridConfidence: Float,
        val boardConfidence: Float,
        val xBoundaries: FloatArray,
        val yBoundaries: FloatArray,
        val cells: List<GoldenCell>,
    )

    companion object {
        const val GOLDEN_RESOURCE = "real_frames/pvp_board_android_export.json"

        const val EXPECTED_GRID_CONF = 0.9872f
        const val EXPECTED_BOARD_CONF = 1.0000f
        const val EXPECTED_UNKNOWN = 0
        const val EXPECTED_ROI_LEFT = 20
        const val EXPECTED_ROI_TOP = 1206
        const val EXPECTED_ROI_RIGHT = 1060
        const val EXPECTED_ROI_BOTTOM = 2246

        const val CONF_EPS = 5e-4f
        const val BOUNDARY_EPS = 0.51f
        const val CELL_CONF_EPS = 1.5e-3f

        val REQUIRED_SCHEMA_KEYS = listOf(
            "imageWidth", "imageHeight", "letterboxRoi", "boardRoi", "roiOffset",
            "gridMethod", "gridConfidence", "boardConfidence",
            "xBoundaries", "yBoundaries", "cellBoxes", "cells",
            "unknownCount", "gate", "validation", "centerX", "centerY", "finalTile",
        )
    }
}
