package com.match3vision.analyzer.vision.parity

import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionResult

/**
 * Exports [VisionResult] to a JSON string matching the vision parity schema.
 * Pure Kotlin — no org.json / Android dependencies (JVM unit-test friendly).
 */
object VisionResultExporter {

    fun toJson(
        result: VisionResult,
        imageWidth: Int,
        imageHeight: Int,
        letterboxRoi: ContentRoi?,
        pretty: Boolean = false,
    ): String {
        val lb = letterboxRoi ?: ContentRoi.full(imageWidth, imageHeight)
        val board = result.grid.boardRoi
        val gate = when (val v = result.validation) {
            is ValidationResult.Pass -> """"PASS""""
            is ValidationResult.Hold -> """"HOLD""""
        }
        val holdReason = when (val v = result.validation) {
            is ValidationResult.Pass -> "null"
            is ValidationResult.Hold -> jsonString(v.reason)
        }

        val sb = StringBuilder()
        val nl = if (pretty) "\n" else ""
        val sp = if (pretty) "  " else ""

        fun indent(level: Int) = if (pretty) sp.repeat(level) else ""

        sb.append("{").append(nl)
        sb.append(indent(1)).append("\"status\": \"ANDROID_EXPORT\",").append(nl)
        sb.append(indent(1)).append("\"imageWidth\": ").append(imageWidth).append(",").append(nl)
        sb.append(indent(1)).append("\"imageHeight\": ").append(imageHeight).append(",").append(nl)
        sb.append(indent(1)).append("\"letterboxRoi\": ").append(roiJson(lb)).append(",").append(nl)
        sb.append(indent(1)).append("\"roiOffset\": {\"dx\": ").append(board.left - lb.left)
            .append(", \"dy\": ").append(board.top - lb.top).append("},").append(nl)
        sb.append(indent(1)).append("\"gridMethod\": ").append(jsonString(result.method.name)).append(",").append(nl)
        sb.append(indent(1)).append("\"gridConfidence\": ").append(result.gridConfidence).append(",").append(nl)
        sb.append(indent(1)).append("\"xBoundaries\": ").append(floatArrayJson(result.grid.xBoundaries)).append(",").append(nl)
        sb.append(indent(1)).append("\"yBoundaries\": ").append(floatArrayJson(result.grid.yBoundaries)).append(",").append(nl)

        // cell boxes
        sb.append(indent(1)).append("\"cellBoxes\": [")
        val boxes = result.grid.cells()
        boxes.forEachIndexed { i, box ->
            if (i > 0) sb.append(",")
            if (pretty) sb.append(nl).append(indent(2))
            sb.append("{\"row\":").append(box.row)
                .append(",\"col\":").append(box.col)
                .append(",\"left\":").append(box.left)
                .append(",\"top\":").append(box.top)
                .append(",\"right\":").append(box.right)
                .append(",\"bottom\":").append(box.bottom)
                .append("}")
        }
        if (pretty) sb.append(nl).append(indent(1))
        sb.append("],").append(nl)

        // cells
        sb.append(indent(1)).append("\"cells\": [")
        var first = true
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                val cell = result.board.get(r, c)
                if (!first) sb.append(",")
                first = false
                if (pretty) sb.append(nl).append(indent(2))
                sb.append("{\"row\":").append(r)
                    .append(",\"col\":").append(c)
                    .append(",\"color\":").append(jsonString(cell.color.name))
                    .append(",\"shape\":").append(jsonString(cell.shape.name))
                    .append(",\"special\":").append(jsonString(cell.special.name))
                    .append(",\"occlusion\":").append(cell.occluded)
                    .append(",\"confidence\":").append(cell.confidence)
                    .append(",\"isUnknown\":").append(cell.isUnknown)
                    .append("}")
            }
        }
        if (pretty) sb.append(nl).append(indent(1))
        sb.append("],").append(nl)

        sb.append(indent(1)).append("\"unknownCount\": ").append(result.unknownCount).append(",").append(nl)
        sb.append(indent(1)).append("\"gate\": ").append(gate).append(",").append(nl)
        sb.append(indent(1)).append("\"holdReason\": ").append(holdReason).append(",").append(nl)
        sb.append(indent(1)).append("\"boardConfidence\": ").append(result.boardConfidence).append(",").append(nl)
        sb.append(indent(1)).append("\"confidence\": ").append(result.confidence).append(nl)
        sb.append("}")
        return sb.toString()
    }

    private fun roiJson(roi: ContentRoi): String =
        "{\"left\":${roi.left},\"top\":${roi.top},\"right\":${roi.right},\"bottom\":${roi.bottom}}"

    private fun floatArrayJson(a: FloatArray): String =
        a.joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        return "\"$escaped\""
    }
}
