package com.match3vision.analyzer.analytics

data class AnalyticsSnapshot(
    val framesAnalyzed: Int,
    val recognitionOk: Int,
    val unknownRate: Float,
    val gateFailRate: Float,
    val gridOk: Int,
    val colorOk: Int,
    val shapeOk: Int,
    val specialOk: Int,
    val movesEvaluated: Int,
    val simsRun: Int,
)

class AnalyticsCollector {
    private var frames = 0
    private var recognitionOk = 0
    private var unknownSum = 0
    private var examinedCells = 0
    private var gateFails = 0
    private var gridOk = 0
    private var colorOk = 0
    private var shapeOk = 0
    private var specialOk = 0
    private var movesEvaluated = 0
    private var simsRun = 0

    fun onVision(gatePass: Boolean, unknownCount: Int, cells: Int = 49) {
        require(cells > 0) { "examined cell count must be positive, was $cells" }
        require(unknownCount in 0..cells) {
            "unknownCount $unknownCount is outside 0..$cells"
        }
        frames++
        if (gatePass) recognitionOk++ else gateFails++
        unknownSum += unknownCount
        examinedCells += cells
        if (gatePass) {
            gridOk++
            colorOk += (cells - unknownCount)
            shapeOk += (cells - unknownCount)
            specialOk += cells
        }
    }

    fun onMoves(evaluated: Int, sims: Int) {
        movesEvaluated += evaluated
        simsRun += sims
    }

    fun snapshot(): AnalyticsSnapshot {
        val f = frames.coerceAtLeast(1)
        return AnalyticsSnapshot(
            framesAnalyzed = frames,
            recognitionOk = recognitionOk,
            unknownRate = if (examinedCells == 0) 0f else unknownSum.toFloat() / examinedCells.toFloat(),
            gateFailRate = gateFails.toFloat() / f,
            gridOk = gridOk,
            colorOk = colorOk,
            shapeOk = shapeOk,
            specialOk = specialOk,
            movesEvaluated = movesEvaluated,
            simsRun = simsRun,
        )
    }

    fun exportJson(): String {
        val s = snapshot()
        return """{"frames":${s.framesAnalyzed},"recognitionOk":${s.recognitionOk},"unknownRate":${s.unknownRate},"gateFailRate":${s.gateFailRate},"movesEvaluated":${s.movesEvaluated},"simsRun":${s.simsRun}}"""
    }
}
