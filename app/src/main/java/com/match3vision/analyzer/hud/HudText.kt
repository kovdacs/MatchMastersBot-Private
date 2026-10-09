package com.match3vision.analyzer.hud

import com.match3vision.analyzer.vision.TileColor

/**
 * Reads a few known HUD strings by matching the glyph templates.
 * Anything that does not match is left unread.
 */
internal object HudText {
    const val TURN_TOP = 1000
    const val TURN_BOTTOM = 1064
    const val TURN_LEFT = 80
    const val TURN_RIGHT = 1000

    const val LEGEND_TOP = 2120
    const val LEGEND_BOTTOM = 2270
    const val LEGEND_LEFT = 220
    const val LEGEND_RIGHT = 860

    const val CARD_LEFT = 30
    const val CARD_RIGHT = 360
    const val CARD_TOP = 860
    const val CARD_BOTTOM = 1010

    const val MULTIPLIER_TOP = 360
    const val MULTIPLIER_BOTTOM = 640
    const val MULTIPLIER_LEFT = 160
    const val MULTIPLIER_RIGHT = 920

    private const val REF_W = 1080
    private const val REF_H = 2400
    private const val INK = 175
    private const val PHRASE_FLOOR = 0.80
    private const val DIGIT_FLOOR = 0.78

    data class Turn(
        val state: String,
        val label: String,
        val seconds: Int?,
    )

    data class Card(
        val text: String,
        val activateWord: Boolean,
    )

    data class Multiplier(val value: Int?, val note: String)

    fun turn(pixels: IntArray, width: Int, height: Int): Turn {
        val ink = inkBox(
            pixels, width, height,
            scaleX(TURN_LEFT, width), scaleX(TURN_RIGHT, width),
            scaleY(TURN_TOP, height), scaleY(TURN_BOTTOM, height),
        ) ?: return unreadTurn()
        val your = Glyphs.named("your")
        val opponent = Glyphs.named("opponent")
        val phrase = Glyphs.best(ink.width, ink.height, ink.bits, listOf(your, opponent), PHRASE_FLOOR)
        if (phrase?.name == "your") {
            return Turn(HudObservation.TURN_YOUR, "Your Turn", null)
        }
        if (phrase?.name == "opponent") {
            return Turn(HudObservation.TURN_OPPONENT, "Opponent's Turn", null)
        }
        val seconds = timeLeftSeconds(ink)
        if (seconds != null) {
            return Turn(HudObservation.TURN_TIME, "Time Left: $seconds", seconds)
        }
        return unreadTurn()
    }

    fun legend(pixels: IntArray, width: Int, height: Int): Pair<String, Map<TileColor, Int>> {
        val left = scaleX(LEGEND_LEFT, width)
        val right = scaleX(LEGEND_RIGHT, width)
        val top = scaleY(LEGEND_TOP, height)
        val bottom = scaleY(LEGEND_BOTTOM, height)
        val points = LinkedHashMap<TileColor, Int>()
        val blobs = colorBlobs(pixels, width, height, left, right, top, bottom)
        for (blob in blobs) {
            val digit = digitAt(pixels, width, height, blob.left, blob.right, blob.top, blob.bottom) ?: continue
            val color = legendColor(blob.r, blob.g, blob.b) ?: continue
            if (color in points) continue
            points[color] = digit
        }
        if (points.isEmpty()) {
            return "legend default" to emptyMap()
        }
        val shown = points.entries.joinToString(",") { "${it.key.name}=${it.value}" }
        return "read $shown" to points
    }

    fun card(pixels: IntArray, width: Int, height: Int): Card {
        val ink = inkBox(
            pixels, width, height,
            scaleX(CARD_LEFT, width), scaleX(CARD_RIGHT, width),
            scaleY(CARD_TOP, height), scaleY(CARD_BOTTOM, height),
        ) ?: return Card(HudObservation.NOT_DETECTABLE, false)
        val word = Glyphs.best(ink.width, ink.height, ink.bits, Glyphs.cardWords, PHRASE_FLOOR)
            ?: return Card(HudObservation.NOT_DETECTABLE, false)
        return when (word.name) {
            "activate" -> Card("ACTIVATE", true)
            "full" -> Card("FULL", false)
            else -> {
                val n = word.name.removePrefix("f").toIntOrNull()
                if (n == null) Card(HudObservation.NOT_DETECTABLE, false)
                else Card("$n/7", false)
            }
        }
    }

    fun multiplier(pixels: IntArray, width: Int, height: Int): Multiplier {
        val ink = inkBox(
            pixels, width, height,
            scaleX(MULTIPLIER_LEFT, width), scaleX(MULTIPLIER_RIGHT, width),
            scaleY(MULTIPLIER_TOP, height), scaleY(MULTIPLIER_BOTTOM, height),
        ) ?: return Multiplier(null, "not detectable")
        val word = Glyphs.similarity(ink.width, ink.height, ink.bits, Glyphs.multiplierWord)
        if (word < PHRASE_FLOOR) return Multiplier(null, "not detectable")
        val value = trailingDigits(ink)
        return if (value == null) {
            Multiplier(null, "present value=not detectable")
        } else {
            Multiplier(value, "x$value")
        }
    }

    private fun unreadTurn() = Turn(HudObservation.NOT_DETECTABLE, HudObservation.NOT_DETECTABLE, null)

    private fun timeLeftSeconds(ink: Ink): Int? {
        val prefix = Glyphs.named("time")
        val prefixW = (ink.height * prefix.width / prefix.height).coerceIn(1, ink.width - 1)
        if (prefixW >= ink.width) return null
        val left = crop(ink, 0, prefixW)
        if (Glyphs.similarity(left.width, left.height, left.bits, prefix) < PHRASE_FLOOR) return null
        return trailingDigits(crop(ink, prefixW, ink.width))
    }

    private fun trailingDigits(ink: Ink): Int? {
        val glyphs = splitGlyphs(ink)
        if (glyphs.isEmpty() || glyphs.size > 2) return null
        val digits = glyphs.map { glyph ->
            val hit = Glyphs.best(glyph.width, glyph.height, glyph.bits, Glyphs.digits, DIGIT_FLOOR) ?: return null
            hit.name.removePrefix("d").toInt()
        }
        var value = 0
        for (digit in digits) value = value * 10 + digit
        return value
    }

    private fun digitAt(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Int? {
        val ink = inkBox(pixels, width, height, left, right, top, bottom) ?: return null
        val hit = Glyphs.best(ink.width, ink.height, ink.bits, Glyphs.digits, DIGIT_FLOOR) ?: return null
        return hit.name.removePrefix("d").toInt()
    }

    private data class Ink(val width: Int, val height: Int, val bits: BooleanArray)

    private fun inkBox(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Ink? {
        var x0 = right
        var x1 = left
        var y0 = bottom
        var y1 = top
        for (y in top..bottom) {
            if (y !in 0 until height) continue
            val row = y * width
            for (x in left..right) {
                if (x !in 0 until width) continue
                if (!ink(pixels[row + x])) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 <= x0 || y1 <= y0) return null
        return cropRect(pixels, width, height, x0, x1, y0, y1)
    }

    private fun cropRect(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Ink {
        val w = right - left + 1
        val h = bottom - top + 1
        val bits = BooleanArray(w * h)
        for (y in 0 until h) {
            val py = top + y
            if (py !in 0 until height) continue
            val row = py * width
            for (x in 0 until w) {
                val px = left + x
                if (px !in 0 until width) continue
                bits[y * w + x] = ink(pixels[row + px])
            }
        }
        return Ink(w, h, bits)
    }

    private fun crop(ink: Ink, fromX: Int, toX: Int): Ink {
        val start = fromX.coerceIn(0, ink.width - 1)
        val end = toX.coerceIn(start + 1, ink.width)
        val w = end - start
        val bits = BooleanArray(w * ink.height)
        var minX = w
        var maxX = 0
        var minY = ink.height
        var maxY = 0
        for (y in 0 until ink.height) {
            for (x in 0 until w) {
                val on = ink.bits[y * ink.width + start + x]
                bits[y * w + x] = on
                if (on) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return Ink(w, ink.height, bits)
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val cropped = BooleanArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                cropped[y * cw + x] = bits[(minY + y) * w + minX + x]
            }
        }
        return Ink(cw, ch, cropped)
    }

    private fun splitGlyphs(ink: Ink): List<Ink> {
        val columns = BooleanArray(ink.width)
        for (x in 0 until ink.width) {
            for (y in 0 until ink.height) {
                if (ink.bits[y * ink.width + x]) {
                    columns[x] = true
                    break
                }
            }
        }
        val glyphs = ArrayList<Ink>()
        var x = 0
        while (x < ink.width) {
            while (x < ink.width && !columns[x]) x++
            if (x >= ink.width) break
            val start = x
            while (x < ink.width && columns[x]) x++
            glyphs += crop(ink, start, x)
        }
        return glyphs
    }

    private data class Blob(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val r: Int,
        val g: Int,
        val b: Int,
    )

    private fun colorBlobs(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): List<Blob> {
        val seen = HashSet<Int>()
        val blobs = ArrayList<Blob>()
        for (y in top..bottom) {
            if (y !in 0 until height) continue
            for (x in left..right) {
                if (x !in 0 until width) continue
                val index = y * width + x
                if (!seen.add(index)) continue
                val color = pixels[index]
                if (legendColor(red(color), green(color), blue(color)) == null) continue
                var x0 = x
                var x1 = x
                var y0 = y
                var y1 = y
                var rs = 0
                var gs = 0
                var bs = 0
                var n = 0
                val queue = ArrayDeque<Int>()
                queue.add(index)
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    val ay = at / width
                    val ax = at - ay * width
                    val sample = pixels[at]
                    val sr = red(sample)
                    val sg = green(sample)
                    val sb = blue(sample)
                    if (legendColor(sr, sg, sb) == null) continue
                    rs += sr
                    gs += sg
                    bs += sb
                    n++
                    if (ax < x0) x0 = ax
                    if (ax > x1) x1 = ax
                    if (ay < y0) y0 = ay
                    if (ay > y1) y1 = ay
                    val neighbors = intArrayOf(ax - 1, ax + 1, ax, ax)
                    val neighborRows = intArrayOf(ay, ay, ay - 1, ay + 1)
                    for (i in 0 until 4) {
                        val nx = neighbors[i]
                        val ny = neighborRows[i]
                        if (nx !in left..right || ny !in top..bottom) continue
                        val next = ny * width + nx
                        if (next in seen) continue
                        seen.add(next)
                        queue.add(next)
                    }
                }
                if (n < 12 || x1 - x0 < 6 || y1 - y0 < 6) continue
                if (x1 - x0 > 80 || y1 - y0 > 80) continue
                blobs += Blob(x0, x1, y0, y1, rs / n, gs / n, bs / n)
            }
        }
        return blobs
    }

    private fun legendColor(r: Int, g: Int, b: Int): TileColor? {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 70 || max - min < 35) return null
        return when {
            g > r + 25 && g > b + 15 && r < 140 -> TileColor.G
            r > 170 && g > 140 && b < 110 -> TileColor.Y
            r > 170 && g in 60..170 && b < 90 -> TileColor.O
            b > 120 && r > 90 && g < 130 && b > g -> TileColor.P
            b > r + 15 && b > g + 10 && r < 120 -> TileColor.B
            r > 150 && r > g + 35 && r > b + 35 -> TileColor.R
            else -> null
        }
    }

    private fun ink(color: Int): Boolean {
        val r = red(color)
        val g = green(color)
        val b = blue(color)
        val luma = (r + g + b) / 3
        return luma >= INK && kotlin.math.abs(r - g) < 50 && kotlin.math.abs(g - b) < 50
    }

    private fun red(color: Int) = (color shr 16) and 0xff
    private fun green(color: Int) = (color shr 8) and 0xff
    private fun blue(color: Int) = color and 0xff

    private fun scaleX(x: Int, width: Int) = x * width / REF_W
    private fun scaleY(y: Int, height: Int) = y * height / REF_H
}
