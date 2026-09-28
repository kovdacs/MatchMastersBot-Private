package com.match3vision.analyzer.vision

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import org.junit.Test

class BoardFinderTest {

    private val finder = BoardFinder()

    @Test
    fun projection_isPrimary_whenGuttersPresent() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(withGutters = true)
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val result = finder.find(pixels, w, h, roi)
        assertThat(result.grid.method).isEqualTo(GridMethod.PROJECTION)
        assertThat(result.grid.xBoundaries).hasLength(8)
        assertThat(result.grid.yBoundaries).hasLength(8)
        assertThat(result.grid.validate()).isTrue()
        assertThat(result.diagnostics["method"]).isEqualTo("PROJECTION")
    }

    @Test
    fun invalidProjection_fallsBackToEvenSplit() {
        val (pixels, w, h) = SyntheticFrames.noGutterBoard()
        val result = finder.find(pixels, w, h, ContentRoi.full(w, h))
        assertThat(result.grid.method).isEqualTo(GridMethod.EVEN_SPLIT)
        assertThat(result.diagnostics["fallback"]).isEqualTo("EVEN_SPLIT")
        assertThat(result.grid.validate()).isTrue()
    }

    @Test
    fun letterbox_doesNotShiftCoordinatesIncorrectly() {
        val top = 20
        val left = 15
        val boardSize = 135
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard(
            boardSize = boardSize,
            letterboxTop = top,
            letterboxBottom = 20,
            letterboxLeft = left,
            letterboxRight = 15,
            withGutters = true,
        )
        val roi = SyntheticFrames.contentRoiForLetterbox(
            boardSize = boardSize,
            letterboxTop = top,
            letterboxBottom = 20,
            letterboxLeft = left,
            letterboxRight = 15,
        )
        val result = finder.find(pixels, w, h, roi)
        val grid = result.grid
        // Board / cells must live in frame coords inside content ROI
        assertThat(grid.boardRoi.left).isAtLeast(left)
        assertThat(grid.boardRoi.top).isAtLeast(top)
        assertThat(grid.xBoundaries.first()).isAtLeast(left.toFloat() - 0.5f)
        assertThat(grid.yBoundaries.first()).isAtLeast(top.toFloat() - 0.5f)
        // Cell centers inside content
        for (cell in grid.cells()) {
            assertThat(cell.centerX()).isGreaterThan(left.toFloat())
            assertThat(cell.centerY()).isGreaterThan(top.toFloat())
            assertThat(cell.centerX()).isLessThan((w - 15).toFloat())
            assertThat(cell.centerY()).isLessThan((h - 20).toFloat())
        }
    }

    @Test
    fun banner_doesNotShiftGrid() {
        val (pixelsNoBanner, w1, h1) = SyntheticFrames.letterboxedBoard(
            bannerRows = 0,
            withGutters = true,
        )
        val roiNo = SyntheticFrames.contentRoiForLetterbox(bannerRows = 0)
        val base = finder.find(pixelsNoBanner, w1, h1, roiNo).grid

        val bannerRows = 12
        val (pixelsBanner, w2, h2) = SyntheticFrames.letterboxedBoard(
            bannerRows = bannerRows,
            withGutters = true,
        )
        val roiBanner = SyntheticFrames.contentRoiForLetterbox(bannerRows = bannerRows)
        val withBanner = finder.find(pixelsBanner, w2, h2, roiBanner).grid

        // X boundaries should stay aligned (banner is horizontal band)
        assertThat(withBanner.xBoundaries).hasLength(8)
        for (i in withBanner.xBoundaries.indices) {
            assertThat(withBanner.xBoundaries[i]).isWithin(4f).of(base.xBoundaries[i])
        }
        // Y grid should sit below the banner — first boundary >= letterboxTop + banner
        assertThat(withBanner.yBoundaries[0]).isAtLeast(20f + bannerRows - 2f)
        // Still 7×7 valid
        assertThat(withBanner.validate()).isTrue()
        assertThat(withBanner.cells()).hasSize(49)
    }

    @Test
    fun grid_is7x7_with8x8Boundaries() {
        val (pixels, w, h) = SyntheticFrames.letterboxedBoard()
        val roi = SyntheticFrames.contentRoiForLetterbox()
        val grid = finder.find(pixels, w, h, roi).grid
        assertThat(grid.xBoundaries).hasLength(8)
        assertThat(grid.yBoundaries).hasLength(8)
        assertThat(grid.cells()).hasSize(49)
        var count = 0
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                grid.cellBox(r, c)
                count++
            }
        }
        assertThat(count).isEqualTo(49)
    }

    @Test
    fun tallPortrait_snapsSquarePlayfieldBelowDarkSeparator() {
        // Synthetic tall portrait: header chrome + dark purple separator + square gem board + footer.
        // Verifies BoardFinder excludes header/footer (geometry only — not color tuning).
        val board = 140
        val header = 200
        val footer = 180
        val sep = 20
        val sidePad = 10
        val w = sidePad + board + sidePad
        val h = header + sep + board + footer
        val pixels = IntArray(w * h) { PixelMath.rgb(30, 30, 40) } // mid chrome

        // Header band (brighter)
        for (y in 0 until header) {
            for (x in 0 until w) pixels[y * w + x] = PixelMath.rgb(60, 80, 160)
        }
        // Dark purple separator (luma ~15, blue-dominant, full width)
        for (y in header until header + sep) {
            for (x in 0 until w) pixels[y * w + x] = PixelMath.rgb(20, 0, 50)
        }
        // Side chrome outside board
        // Board: dark purple bg + gutters + colored cells
        val boardTop = header + sep
        val boardLeft = sidePad
        for (y in boardTop until boardTop + board) {
            for (x in boardLeft until boardLeft + board) {
                pixels[y * w + x] = PixelMath.rgb(25, 15, 55)
            }
        }
        val gutter = 2
        val cell = (board - gutter * 8) / 7
        for (i in 0..7) {
            val gx = boardLeft + i * (cell + gutter)
            val gy = boardTop + i * (cell + gutter)
            for (yy in boardTop until boardTop + board) {
                for (dx in 0 until gutter) {
                    val x = gx + dx
                    if (x in 0 until w) pixels[yy * w + x] = SyntheticFrames.GUTTER
                }
            }
            for (xx in boardLeft until boardLeft + board) {
                for (dy in 0 until gutter) {
                    val y = gy + dy
                    if (y in 0 until h) pixels[y * w + xx] = SyntheticFrames.GUTTER
                }
            }
        }
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val color = SyntheticFrames.PALETTE[(r * 7 + c) % SyntheticFrames.PALETTE.size]
                val x0 = boardLeft + gutter + c * (cell + gutter)
                val y0 = boardTop + gutter + r * (cell + gutter)
                for (yy in y0 until y0 + cell) {
                    for (xx in x0 until x0 + cell) {
                        pixels[yy * w + xx] = color
                    }
                }
            }
        }
        // Footer
        for (y in boardTop + board until h) {
            for (x in 0 until w) pixels[y * w + x] = PixelMath.rgb(50, 50, 70)
        }

        val result = finder.find(pixels, w, h, ContentRoi.full(w, h))
        val roi = result.grid.boardRoi
        assertThat(result.diagnostics["playfieldSnap"]).isEqualTo("separator_square")
        // Board ROI should sit below separator and be near-square — not full-frame tall
        assertThat(roi.top).isAtLeast(header)
        assertThat(roi.height().toFloat() / roi.width().toFloat()).isWithin(0.15f).of(1f)
        assertThat(roi.height()).isLessThan(h * 2 / 3)
        assertThat(result.grid.validate()).isTrue()
        assertThat(result.grid.xBoundaries).hasLength(8)
        assertThat(result.grid.yBoundaries).hasLength(8)
        println(
            "tallPortrait snap boardRoi=LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom}) " +
                "method=${result.grid.method} conf=${result.grid.confidence}",
        )
    }
}
