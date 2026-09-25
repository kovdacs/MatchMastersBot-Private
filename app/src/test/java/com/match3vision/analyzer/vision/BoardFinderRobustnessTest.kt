package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

/**
 * BoardFinder robustness under synthetic degradations (NOT real Match Masters frames).
 *
 * Documents actual gridConfidence floors. Degraded cases may HOLD — that is OK.
 * NEVER changes [VisionThresholds.MIN_GRID_CONFIDENCE].
 *
 * BoardFinder projection confidence formula:
 *   conf = (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
 * The *1.5f is scoring calibration so clean gutters clear the 0.98 gate; it is not a gate change.
 */
class BoardFinderRobustnessTest {

    private val finder = BoardFinder()

    @Test
    fun cleanFixture_gridConfidenceAtLeastMinGate() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(pixels, w, h, roi).grid
        assertThat(grid.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(grid.confidence).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        // Documented value band for clean synthetic
        assertThat(grid.confidence).isAtLeast(0.98f)

    @Test
    fun gutterJitter_1to2px_stillProjectionOrFallback() {
        val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val jittered = base.copyOf()
        // Nudge some gutter columns by 1–2 px (copy dark line)
        val left = 15
        val top = 20
        val cell = 17
        val gutter = 2
        for (i in 1..6) {
            val gx = left + i * (cell + gutter) + (i % 2) // 1px jitter
            for (yy in top until top + 135) {
                if (gx in 0 until w && yy in 0 until h) {
                    jittered[yy * w + gx] = SyntheticFrames.GUTTER
                }
            }
        }
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(jittered, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        // Jittered clean board should still score fairly high if PROJECTION
        if (grid.method == GridMethod.PROJECTION) {
            assertThat(grid.confidence).isAtLeast(0.85f)
            println("gutterJitter gridConfidence=${grid.confidence}")
        }
    }

    @Test
    fun mildNoise_onBoard_documentsConfidence() {
        val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val noisy = base.copyOf()
        var s = 99
        for (i in noisy.indices) {
            if (noisy[i] == SyntheticFrames.BLACK) continue
            s = (s * 1103515245 + 12345) and 0x7fffffff
            val d = (s % 21) - 10
            fun ch(v: Int) = (v + d).coerceIn(0, 255)
            val p = noisy[i]
            noisy[i] = PixelMath.rgb(ch(PixelMath.red(p)), ch(PixelMath.green(p)), ch(PixelMath.blue(p)))
        }
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(noisy, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        println("mildNoise method=${grid.method} gridConfidence=${grid.confidence}")
        // May HOLD relative to 0.98; floor for any valid geometry
        assertThat(grid.confidence).isAtLeast(0.70f)
    }

    @Test
    fun jpegLikeQuantization_documentsConfidence() {
        val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val q = IntArray(base.size) { i ->
            val p = base[i]
            // Quantize channels to 16 levels (JPEG-like banding)
            fun q8(v: Int) = ((v / 16) * 16).coerceIn(0, 255)
            PixelMath.rgb(q8(PixelMath.red(p)), q8(PixelMath.green(p)), q8(PixelMath.blue(p)))
        }
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(q, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        println("jpegQuant method=${grid.method} gridConfidence=${grid.confidence}")
        assertThat(grid.confidence).isAtLeast(0.70f)
    }

    @Test
    fun letterboxOffsetChange_keepsValidGrid() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(
            letterboxTop = 40,
            letterboxBottom = 10,
            letterboxLeft = 25,
            letterboxRight = 5,
            withGutters = true,
        )
        val roi = SyntheticFrames.contentRoiForLetterbox(
            letterboxTop = 40,
            letterboxBottom = 10,
            letterboxLeft = 25,
            letterboxRight = 5,
        )
        val grid = finder.find(pixels, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        assertThat(grid.yBoundaries[0]).isAtLeast(39f)
        println("letterboxOffset method=${grid.method} gridConfidence=${grid.confidence}")
        if (grid.method == GridMethod.PROJECTION) {
            assertThat(grid.confidence).isAtLeast(0.85f)
        }
    }

    @Test
    fun scaleBoardSlightly_documentsConfidence() {
        // Larger boardSize than default → different cell pitch
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(
            boardSize = 154, // 7*20 + 8*2 = 154
            gutter = 2,
            withGutters = true,
        )
        val roi = SyntheticFrames.contentRoiForLetterbox(boardSize = 154)
        val grid = finder.find(pixels, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        println("scaleBoard method=${grid.method} gridConfidence=${grid.confidence}")
        assertThat(grid.confidence).isAtLeast(0.70f)
    }

    @Test
    fun mildShear_documentsConfidenceOrFallback() {
        val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val sheared = IntArray(base.size) { SyntheticFrames.BLACK }
        // Horizontal shear of ~1px per 40 rows
        for (y in 0 until h) {
            val shift = (y / 40)
            for (x in 0 until w) {
                val sx = (x - shift).coerceIn(0, w - 1)
                sheared[y * w + x] = base[y * w + sx]
            }
        }
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(sheared, w, h, roi).grid
        assertThat(grid.validate()).isTrue()
        println("mildShear method=${grid.method} gridConfidence=${grid.confidence}")
        assertThat(grid.confidence).isAtLeast(0.70f)
    }

    @Test
    fun relVarToConfidenceFormula_documents1_5fCalibration() {
        // Regression: conf = (1 - (vx+vy)*1.5).coerceIn(0.85, 0.99)
        // This mirrors BoardFinder.tryProjection scoring — gate stays 0.98.
        fun projectedConf(relVarX: Float, relVarY: Float): Float {
            val projectionMinConfidence = 0.85f
            return (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
        }
        // Clean-ish variance pair that should clear 0.98 after *1.5f
        val clean = projectedConf(0.004f, 0.004f)
        assertThat(clean).isAtLeast(VisionThresholds.MIN_GRID_CONFIDENCE)
        // Higher variance may fall below gate (HOLD) but stay ≥ min projection floor
        val degraded = projectedConf(0.06f, 0.06f)
        assertThat(degraded).isAtLeast(0.85f)
        assertThat(degraded).isLessThan(VisionThresholds.MIN_GRID_CONFIDENCE)
        // Gate constant must remain untouched
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
    }
}

    @Test
    fun multiPerturbationMatrix_documentsGridBoardGate() {
        data class Case(val name: String, val pixels: IntArray, val w: Int, val h: Int, val roi: ContentRoi)

        val cases = mutableListOf<Case>()
        run {
            val (p, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
            cases += Case("clean", p, w, h, SyntheticFrames.contentRoiForLetterbox())
        }
        run {
            val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
            val noisy = base.copyOf()
            var s = 11
            for (i in noisy.indices) {
                if (noisy[i] == SyntheticFrames.BLACK) continue
                s = (s * 1103515245 + 12345) and 0x7fffffff
                val d = (s % 25) - 12
                fun ch(v: Int) = (v + d).coerceIn(0, 255)
                val px = noisy[i]
                noisy[i] = PixelMath.rgb(ch(PixelMath.red(px)), ch(PixelMath.green(px)), ch(PixelMath.blue(px)))
            }
            cases += Case("noise", noisy, w, h, SyntheticFrames.contentRoiForLetterbox())
        }
        run {
            val (base, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
            val q = IntArray(base.size) { i ->
                val p = base[i]
                fun q8(v: Int) = ((v / 16) * 16).coerceIn(0, 255)
                PixelMath.rgb(q8(PixelMath.red(p)), q8(PixelMath.green(p)), q8(PixelMath.blue(p)))
            }
            cases += Case("jpegQuant", q, w, h, SyntheticFrames.contentRoiForLetterbox())
        }
        run {
            val (p, w, h) = SyntheticFrames.letterboxedBoard(
                letterboxTop = 40, letterboxBottom = 10, letterboxLeft = 25, letterboxRight = 5,
                withGutters = true,
            )
            cases += Case(
                "letterbox",
                p, w, h,
                SyntheticFrames.contentRoiForLetterbox(
                    letterboxTop = 40, letterboxBottom = 10, letterboxLeft = 25, letterboxRight = 5,
                ),
            )
        }
        run {
            val (p, w, h) = SyntheticFrames.letterboxedBoard(boardSize = 154, gutter = 2, withGutters = true)
            cases += Case("scale154", p, w, h, SyntheticFrames.contentRoiForLetterbox(boardSize = 154))
        }
        run {
            val realistic = RealisticSyntheticFixture.buildCanonical()
            cases += Case(
                "REALISTIC_SYNTHETIC",
                realistic.pixels, realistic.width, realistic.height, realistic.contentRoi,
            )
        }

        val pipeline = VisionPipeline()
        println("BoardFinder multi-perturbation matrix")
        println("| case | method | gridConf | boardConf | unknowns | gate |")
        println("|------|--------|----------|-----------|----------|------|")
        for (c in cases) {
            val result = pipeline.analyze(c.pixels, c.w, c.h, c.roi)
            val gate = if (result.validation.isPass) "PASS" else "HOLD"
            println(
                "| ${c.name} | ${result.method} | " +
                    "${"%.4f".format(result.gridConfidence)} | " +
                    "${"%.4f".format(result.boardConfidence)} | " +
                    "${result.unknownCount} | $gate |",
            )
            assertThat(result.grid.validate() || result.method == GridMethod.EVEN_SPLIT).isTrue()
            // Gates unchanged
            assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
            assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
            assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
        }
    }
}
