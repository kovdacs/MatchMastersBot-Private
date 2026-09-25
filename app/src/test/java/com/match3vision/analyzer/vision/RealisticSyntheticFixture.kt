package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.ContentRoi
import kotlin.math.roundToInt

/**
 * **REALISTIC_SYNTHETIC** IntArray board builder — NOT a real Match Masters frame.
 *
 * Distinct from the clean [SyntheticFrames.letterboxedBoard] unit fixture:
 * this applies anti-aliased cell edges, per-tile shading, brightness/contrast,
 * additive noise, JPEG-like channel quantization, gutter width jitter (±1–2),
 * letterbox offset, mild scale jitter, and optional partial UI/overlay occlusion.
 *
 * Ground truth MUST be taken from [RealisticSyntheticGroundTruth.fromParams]
 * (construction parameters / what was drawn), never from VisionPipeline detector output.
 *
 * Name/docs intentionally say REALISTIC_SYNTHETIC so CI logs and reports never
 * claim a real Match Masters capture.
 */
object RealisticSyntheticFixture {

    const val LABEL = "REALISTIC_SYNTHETIC"
    const val PROVENANCE =
        "Authored from fixture construction parameters (drawn colors/occlusion), " +
            "NOT from VisionPipeline / detector output."

    val DEFAULT_COLOR_INDICES: IntArray = IntArray(49) { i ->
        // Deterministic but non-trivial palette walk (not a simple cycle of 6)
        val r = i / 7
        val c = i % 7
        (r * 3 + c * 2 + (r xor c)) % SyntheticFrames.PALETTE.size
    }

    /** Fixed construction parameters for the canonical always-on harness fixture. */
    data class Params(
        val cellSize: Int = 18,
        val baseGutter: Int = 2,
        val gutterJitterAmp: Int = 1, // ±1 px on internal gutters
        val letterboxTop: Int = 28,
        val letterboxBottom: Int = 18,
        val letterboxLeft: Int = 22,
        val letterboxRight: Int = 12,
        val noiseAmp: Int = 8,
        val jpegQuantStep: Int = 8,
        val brightness: Float = 1.05f,
        val contrast: Float = 1.08f,
        val scaleJitter: Float = 1.0f, // board pitch multiplier (1.0 = nominal)
        val seed: Int = 20260925,
        /** Row-major 7×7 palette indices into [SyntheticFrames.PALETTE]. */
        val colorIndices: IntArray = DEFAULT_COLOR_INDICES,
        /** Cells marked occluded by construction (partial dark/warm overlay). */
        val occludedCells: Set<Pair<Int, Int>> = setOf(3 to 5),
        val occlusionKind: OcclusionKind = OcclusionKind.PARTIAL_DARK_60,
    ) {
        init {
            require(colorIndices.size == 49) { "need 49 color indices" }
            require(cellSize >= 8)
            require(baseGutter >= 1)
        }
    }

    enum class OcclusionKind {
        PARTIAL_DARK_60,
        PARTIAL_WARM_BANNER_55,
        PARTIAL_WHITE_UI_60,
    }

    data class Built(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val contentRoi: ContentRoi,
        val params: Params,
        /** Absolute board ROI in frame coords (left, top, right, bottom). */
        val boardRoi: ContentRoi,
        /** Authored 8 x-boundaries and 8 y-boundaries from construction (pre-jitter draw). */
        val authoredXBoundaries: FloatArray,
        val authoredYBoundaries: FloatArray,
        val label: String = LABEL,
    )

    fun buildCanonical(): Built = build(Params())

    fun build(params: Params): Built {
        val cell = ((params.cellSize * params.scaleJitter).roundToInt()).coerceAtLeast(8)

        // Gutter widths with ±jitter on internals (decided before board size)
        val gutterWidths = IntArray(8) { params.baseGutter }
        var rng = params.seed
        for (i in 1..6) {
            rng = next(rng)
            val delta = (rng % (2 * params.gutterJitterAmp + 1)) - params.gutterJitterAmp
            gutterWidths[i] = (params.baseGutter + delta).coerceAtLeast(1)
        }

        // Board size = sum(gutters) + 7*cell — exact fit for drawn layout
        val boardSize = gutterWidths.sum() + cell * 7
        val meanPitch = boardSize.toFloat() / 7f
        val contentW = boardSize
        val contentH = boardSize
        val width = params.letterboxLeft + contentW + params.letterboxRight
        val height = params.letterboxTop + contentH + params.letterboxBottom
        val pixels = IntArray(width * height) { SyntheticFrames.BLACK }

        val boardLeft = params.letterboxLeft
        val boardTop = params.letterboxTop
        val boardRight = boardLeft + boardSize
        val boardBottom = boardTop + boardSize

        // Content background (slightly above letterbox black)
        for (y in boardTop until boardBottom) {
            for (x in boardLeft until boardRight) {
                pixels[y * width + x] = SyntheticFrames.DARK
            }
        }

        // Authored uniform boundaries (construction intent / mean pitch)
        val authoredX = FloatArray(8) { i -> boardLeft + i * meanPitch }
        authoredX[7] = boardRight.toFloat()
        val authoredY = FloatArray(8) { i -> boardTop + i * meanPitch }
        authoredY[7] = boardBottom.toFloat()

        // Cumulative cell origins from variable gutters (realistic pitch drift)
        val xStarts = IntArray(7)
        val yStarts = IntArray(7)
        var xCursor = boardLeft + gutterWidths[0]
        var yCursor = boardTop + gutterWidths[0]
        for (i in 0 until 7) {
            xStarts[i] = xCursor
            yStarts[i] = yCursor
            xCursor += cell + gutterWidths[i + 1]
            yCursor += cell + gutterWidths[i + 1]
        }

        // Draw gutters
        fun paintGutterV(gx: Int, gw: Int) {
            for (yy in boardTop until boardBottom) {
                for (dx in 0 until gw) {
                    val x = gx + dx
                    if (x in boardLeft until boardRight) {
                        pixels[yy * width + x] = SyntheticFrames.GUTTER
                    }
                }
            }
        }
        fun paintGutterH(gy: Int, gw: Int) {
            for (xx in boardLeft until boardRight) {
                for (dy in 0 until gw) {
                    val y = gy + dy
                    if (y in boardTop until boardBottom) {
                        pixels[y * width + xx] = SyntheticFrames.GUTTER
                    }
                }
            }
        }
        var gx = boardLeft
        var gy = boardTop
        for (i in 0..7) {
            paintGutterV(gx, gutterWidths[i])
            paintGutterH(gy, gutterWidths[i])
            if (i < 7) {
                gx += gutterWidths[i] + cell
                gy += gutterWidths[i] + cell
            }
        }

        // Draw shaded + AA cells
        for (r in 0 until 7) {
            for (c in 0 until 7) {
                val idx = r * 7 + c
                val base = SyntheticFrames.PALETTE[params.colorIndices[idx]]
                val x0 = xStarts[c]
                val y0 = yStarts[r]
                val occluded = params.occludedCells.contains(r to c)
                fillCellShadedAa(
                    pixels, width, height,
                    x0, y0, cell, cell,
                    base, params.seed + idx * 97,
                )
                if (occluded) {
                    applyOcclusion(
                        pixels, width, height,
                        x0, y0, cell, cell,
                        params.occlusionKind,
                    )
                }
            }
        }

        // Global brightness / contrast then noise then JPEG-like quant
        applyBrightnessContrast(pixels, params.brightness, params.contrast)
        addNoise(pixels, params.noiseAmp, params.seed xor 0xA5A5)
        jpegQuantize(pixels, params.jpegQuantStep)

        val contentRoi = ContentRoi(
            params.letterboxLeft,
            params.letterboxTop,
            width - params.letterboxRight,
            height - params.letterboxBottom,
        )
        val boardRoi = ContentRoi(boardLeft, boardTop, boardRight, boardBottom)

        return Built(
            pixels = pixels,
            width = width,
            height = height,
            contentRoi = contentRoi,
            params = params,
            boardRoi = boardRoi,
            authoredXBoundaries = authoredX,
            authoredYBoundaries = authoredY,
        )
    }

    private fun fillCellShadedAa(
        pixels: IntArray,
        frameW: Int,
        frameH: Int,
        x0: Int,
        y0: Int,
        cw: Int,
        ch: Int,
        base: Int,
        seed: Int,
    ) {
        val highlight = blend(base, PixelMath.rgb(255, 255, 255), 0.22f)
        val shadow = blend(base, PixelMath.rgb(0, 0, 0), 0.28f)
        for (dy in 0 until ch) {
            for (dx in 0 until cw) {
                val x = x0 + dx
                val y = y0 + dy
                if (x !in 0 until frameW || y !in 0 until frameH) continue
                val tVert = dy.toFloat() / (ch - 1).coerceAtLeast(1)
                val shaded = blend(highlight, shadow, tVert)
                // Soft AA toward gutter on outer 1px ring
                val edge =
                    dx == 0 || dy == 0 || dx == cw - 1 || dy == ch - 1
                val pix = if (edge) blend(shaded, SyntheticFrames.GUTTER, 0.35f) else shaded
                // Mild intra-tile grain (pre-global noise)
                pixels[y * frameW + x] = jitter(pix, seed + dx * 13 + dy * 31, 3)
            }
        }
    }

    private fun applyOcclusion(
        pixels: IntArray,
        frameW: Int,
        frameH: Int,
        x0: Int,
        y0: Int,
        cw: Int,
        ch: Int,
        kind: OcclusionKind,
    ) {
        val coverH = when (kind) {
            OcclusionKind.PARTIAL_DARK_60 -> (ch * 0.60f).toInt()
            OcclusionKind.PARTIAL_WARM_BANNER_55 -> (ch * 0.55f).toInt()
            OcclusionKind.PARTIAL_WHITE_UI_60 -> (ch * 0.60f).toInt()
        }
        for (dy in 0 until coverH) {
            for (dx in 0 until cw) {
                val x = x0 + dx
                val y = y0 + dy
                if (x !in 0 until frameW || y !in 0 until frameH) continue
                pixels[y * frameW + x] = when (kind) {
                    OcclusionKind.PARTIAL_DARK_60 -> SyntheticFrames.DARK
                    OcclusionKind.PARTIAL_WARM_BANNER_55 ->
                        if ((dx + dy) % 3 == 0) SyntheticFrames.BANNER_ORANGE
                        else SyntheticFrames.BANNER_RED
                    OcclusionKind.PARTIAL_WHITE_UI_60 -> PixelMath.rgb(245, 245, 248)
                }
            }
        }
    }

    private fun applyBrightnessContrast(pixels: IntArray, brightness: Float, contrast: Float) {
        for (i in pixels.indices) {
            if (pixels[i] == SyntheticFrames.BLACK) continue
            fun adj(v: Int): Int {
                val centered = (v - 128f) * contrast + 128f
                return (centered * brightness).roundToInt().coerceIn(0, 255)
            }
            val p = pixels[i]
            pixels[i] = PixelMath.rgb(
                adj(PixelMath.red(p)),
                adj(PixelMath.green(p)),
                adj(PixelMath.blue(p)),
            )
        }
    }

    private fun addNoise(pixels: IntArray, amp: Int, seed: Int) {
        if (amp <= 0) return
        var s = seed
        for (i in pixels.indices) {
            if (pixels[i] == SyntheticFrames.BLACK) continue
            s = next(s)
            val d = (s % (2 * amp + 1)) - amp
            fun ch(v: Int) = (v + d).coerceIn(0, 255)
            val p = pixels[i]
            pixels[i] = PixelMath.rgb(
                ch(PixelMath.red(p)),
                ch(PixelMath.green(p)),
                ch(PixelMath.blue(p)),
            )
        }
    }

    private fun jpegQuantize(pixels: IntArray, step: Int) {
        if (step <= 1) return
        for (i in pixels.indices) {
            val p = pixels[i]
            fun q(v: Int) = ((v / step) * step).coerceIn(0, 255)
            pixels[i] = PixelMath.rgb(q(PixelMath.red(p)), q(PixelMath.green(p)), q(PixelMath.blue(p)))
        }
    }

    private fun next(s: Int): Int = (s * 1103515245 + 12345) and 0x7fffffff

    private fun jitter(argb: Int, seed: Int, amp: Int): Int {
        if (amp <= 0) return argb
        var s = seed and 0x7fffffff
        s = next(s)
        val d = (s % (2 * amp + 1)) - amp
        fun ch(v: Int) = (v + d).coerceIn(0, 255)
        return PixelMath.rgb(ch(PixelMath.red(argb)), ch(PixelMath.green(argb)), ch(PixelMath.blue(argb)))
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x * (1 - tt) + y * tt).roundToInt().coerceIn(0, 255)
        return PixelMath.rgb(
            mix(PixelMath.red(a), PixelMath.red(b)),
            mix(PixelMath.green(a), PixelMath.green(b)),
            mix(PixelMath.blue(a), PixelMath.blue(b)),
        )
    }
}
