package com.match3vision.analyzer.vision

/**
 * Shared failure-path diagnostics for vision unit tests.
 * Formats a 7×7 UNKNOWN map and per-cell detail — use only when asserts fail
 * (avoid verbose success logs).
 */
object VisionDiagnostics {

    fun formatResult(result: VisionResult, header: String = "VisionDiagnostics"): String {
        val sb = StringBuilder()
        sb.append(header).append('\n')
        sb.append("gridConfidence=").append("%.4f".format(result.gridConfidence))
            .append(" boardConfidence=").append("%.4f".format(result.boardConfidence))
            .append(" unknownCount=").append(result.unknownCount)
            .append(" gate=").append(gateLabel(result.validation))
            .append(" method=").append(result.method)
            .append('\n')
        sb.append("unknownMap 7x7 (U=unknown .=known):\n")
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                sb.append(if (result.board.get(r, c).isUnknown) 'U' else '.')
            }
            sb.append('\n')
        }
        sb.append("per-cell detail (unknowns first, then all):\n")
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                val cell = result.board.get(r, c)
                if (!cell.isUnknown) continue
                appendCell(sb, r, c, cell, result)
            }
        }
        for (r in 0 until GridGeometry.GRID_SIZE) {
            for (c in 0 until GridGeometry.GRID_SIZE) {
                val cell = result.board.get(r, c)
                if (cell.isUnknown) continue
                appendCell(sb, r, c, cell, result)
            }
        }
        if (result.diagnostics.isNotEmpty()) {
            sb.append("pipelineDiag=").append(result.diagnostics).append('\n')
        }
        return sb.toString()
    }

    fun assertOrDump(result: VisionResult, condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError("$message\n${formatResult(result)}")
        }
    }

    private fun appendCell(
        sb: StringBuilder,
        r: Int,
        c: Int,
        cell: CellVision,
        result: VisionResult,
    ) {
        sb.append("  (").append(r).append(',').append(c).append(")")
            .append(" color=").append(cell.color)
            .append(" shape=").append(cell.shape)
            .append(" special=").append(cell.special)
            .append(" occ=").append(cell.occluded)
            .append(" conf=").append("%.3f".format(cell.confidence))
            .append(" unk=").append(cell.isUnknown)
        val occKey = result.diagnostics["occ_${r}_${c}"]
        if (occKey != null) sb.append(" occReason=").append(occKey)
        sb.append('\n')
    }

    private fun gateLabel(v: ValidationResult): String = when (v) {
        is ValidationResult.Pass -> "PASS"
        is ValidationResult.Hold -> "HOLD(${v.reason})"
    }
}
