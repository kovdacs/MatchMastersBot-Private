package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.ContentRoi

/**
 * Synthetic ARGB frame builders for JVM unit tests (no Bitmap / Robolectric).
 */
object SyntheticFrames {

    fun rgb(r: Int, g: Int, b: Int): Int = PixelMath.rgb(r, g, b)

    val BLACK = rgb(0, 0, 0)
    val DARK = rgb(10, 10, 10)
    val GUTTER = rgb(20, 20, 25)
    val BANNER_ORANGE = rgb(255, 120, 20)
    val BANNER_RED = rgb(230, 40, 40)

    val COLOR_B = rgb(40, 90, 220)
    val COLOR_R = rgb(220, 40, 40)
    val COLOR_Y = rgb(230, 210, 40)
    val COLOR_G = rgb(40, 180, 70)
    val COLOR_P = rgb(150, 50, 200)
    val COLOR_O = rgb(240, 140, 30)

    val PALETTE = listOf(COLOR_B, COLOR_R, COLOR_Y, COLOR_G, COLOR_P, COLOR_O)

    /**
     * Build a frame with letterbox bars + a 7×7 board with dark gutters and
     * solid colored cells (cycle palette).
     */
    fun letterboxedBoard(
        boardSize: Int = 135, // exact: 7*17 + 8*2 = 135 (cell=17, gutter=2)
        letterboxTop: Int = 20,
        letterboxBottom: Int = 20,
        letterboxLeft: Int = 15,
        letterboxRight: Int = 15,
        gutter: Int = 2,
        withGutters: Boolean = true,
        bannerRows: Int = 0,
    ): Triple<IntArray, Int, Int> {
        val contentW = boardSize
        val contentH = boardSize + bannerRows
        val width = letterboxLeft + contentW + letterboxRight
        val height = letterboxTop + contentH + letterboxBottom
        val pixels = IntArray(width * height) { BLACK }

        // Content background
        for (y in letterboxTop until letterboxTop + contentH) {
            for (x in letterboxLeft until letterboxLeft + contentW) {
                pixels[y * width + x] = DARK
            }
        }

        val boardTop = letterboxTop + bannerRows
        val boardLeft = letterboxLeft
        val cell = (boardSize - gutter * 8) / 7
        require(cell > 4) { "board too small" }

        // Draw gutters as energy peaks (dark lines) on a slightly brighter board bg
        if (withGutters) {
            for (i in 0..7) {
                val gx = boardLeft + i * (cell + gutter)
                for (yy in boardTop until boardTop + boardSize) {
                    for (dx in 0 until gutter.coerceAtLeast(1)) {
                        val x = gx + dx
                        if (x in 0 until width) pixels[yy * width + x] = GUTTER
                    }
                }
                val gy = boardTop + i * (cell + gutter)
                for (xx in boardLeft until boardLeft + boardSize) {
                    for (dy in 0 until gutter.coerceAtLeast(1)) {
                        val y = gy + dy
                        if (y in 0 until height) pixels[y * width + xx] = GUTTER
                    }
                }
            }
        }

        // Fill cells with colors
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val color = PALETTE[(r * 7 + c) % PALETTE.size]
                val x0 = boardLeft + gutter + c * (cell + gutter)
                val y0 = boardTop + gutter + r * (cell + gutter)
                for (yy in y0 until y0 + cell) {
                    for (xx in x0 until x0 + cell) {
                        if (xx in 0 until width && yy in 0 until height) {
                            pixels[yy * width + xx] = color
                        }
                    }
                }
            }
        }

        // Banner overlay (orange/red text-like band) above board, inside content
        if (bannerRows > 0) {
            for (y in letterboxTop until letterboxTop + bannerRows) {
                for (x in letterboxLeft until letterboxLeft + contentW) {
                    // Alternating warm pixels to look like text, not a gutter
                    pixels[y * width + x] =
                        if ((x + y) % 3 == 0) BANNER_ORANGE else BANNER_RED
                }
            }
        }

        return Triple(pixels, width, height)
    }

    fun contentRoiForLetterbox(
        boardSize: Int = 135,
        letterboxTop: Int = 20,
        letterboxBottom: Int = 20,
        letterboxLeft: Int = 15,
        letterboxRight: Int = 15,
        bannerRows: Int = 0,
    ): ContentRoi {
        val contentW = boardSize
        val contentH = boardSize + bannerRows
        val width = letterboxLeft + contentW + letterboxRight
        val height = letterboxTop + contentH + letterboxBottom
        return ContentRoi(
            letterboxLeft,
            letterboxTop,
            width - letterboxRight,
            height - letterboxBottom,
        )
    }

    /** Flat noisy content — no periodic gutters → projection should fail. */
    fun noGutterBoard(
        size: Int = 135,
        seed: Int = 42,
    ): Triple<IntArray, Int, Int> {
        val pixels = IntArray(size * size)
        var s = seed
        for (i in pixels.indices) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            val v = 80 + (s % 100)
            pixels[i] = rgb(v, v, v)
        }
        return Triple(pixels, size, size)
    }

    /** Single cell crop filled with [color]. */
    fun solidCell(color: Int, w: Int = 24, h: Int = 24): IntArray =
        IntArray(w * h) { color }

    /** Dark occluded cell. */
    fun darkCell(w: Int = 24, h: Int = 24): IntArray =
        IntArray(w * h) { DARK }

    /** Banner-colored cell. */
    fun bannerCell(w: Int = 24, h: Int = 24): IntArray =
        IntArray(w * h) { if (it % 2 == 0) BANNER_ORANGE else BANNER_RED }
}
