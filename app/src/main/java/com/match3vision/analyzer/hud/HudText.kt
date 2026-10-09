package com.match3vision.analyzer.hud

import com.match3vision.analyzer.vision.TileColor

/**
 * Reads a few known HUD strings by matching the glyph templates.
 * Anything that does not match is left unread.
 */
internal object HudText {
    const val TURN_TOP = 1000
    const val TURN_BOTTOM = 1100
    const val TURN_LEFT = 80
    const val TURN_RIGHT = 1000

    const val LEGEND_TOP = 2120
    const val LEGEND_BOTTOM = 2270
    const val LEGEND_LEFT = 220
    const val LEGEND_RIGHT = 860

    const val CARD_LEFT = 10
    const val CARD_RIGHT = 340
    const val CARD_TOP = 865
    const val CARD_BOTTOM = 985

    const val MULTIPLIER_TOP = 360
    const val MULTIPLIER_BOTTOM = 640
    const val MULTIPLIER_LEFT = 160
    const val MULTIPLIER_RIGHT = 920

    private const val REF_W = 1080
    private const val REF_H = 2400
    private const val INK = 175
    internal const val PHRASE_FLOOR = 0.80
    private const val DIGIT_FLOOR = 0.78

    data class Turn(
        val state: String,
        val label: String,
        val seconds: Int?,
        val yourScore: Double = 0.0,
        val opponentScore: Double = 0.0,
        val timeScore: Double = 0.0,
    )

    data class Card(
        val text: String,
        val activateWord: Boolean,
        /** Best ACTIVATE-word shape score this frame, even when it is under the floor. */
        val activateScore: Double = 0.0,
        val activateRect: String = "none",
        val floor: Double = PHRASE_FLOOR,
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
        val yourScore = Glyphs.similarity(ink.width, ink.height, ink.bits, your)
        val opponentScore = Glyphs.similarity(ink.width, ink.height, ink.bits, opponent)
        val timeScore = timePrefixScore(ink)
        val phrase = Glyphs.best(ink.width, ink.height, ink.bits, listOf(your, opponent, Glyphs.yourGame), PHRASE_FLOOR)
            ?: letterRow(pixels, width, height, scaleX(TURN_LEFT, width), scaleX(TURN_RIGHT, width), scaleY(TURN_TOP, height), scaleY(TURN_BOTTOM, height))
                ?.let { band ->
                    Glyphs.best(band.width, band.height, band.bits, listOf(Glyphs.yourGame, your, opponent), PHRASE_FLOOR)
                }
        if (phrase?.name == "your" || phrase?.name == "your-game") {
            return Turn(HudObservation.TURN_YOUR, "Your Turn", null, yourScore, opponentScore, timeScore)
        }
        if (phrase?.name == "opponent") {
            return Turn(HudObservation.TURN_OPPONENT, "Opponent's Turn", null, yourScore, opponentScore, timeScore)
        }
        val seconds = if (timeScore >= PHRASE_FLOOR) timeLeftSeconds(ink) else null
        if (seconds != null) {
            return Turn(
                HudObservation.TURN_TIME,
                "Time Left: $seconds",
                seconds,
                yourScore,
                opponentScore,
                timeScore,
            )
        }
        return Turn(
            HudObservation.NOT_DETECTABLE,
            HudObservation.NOT_DETECTABLE,
            null,
            yourScore,
            opponentScore,
            timeScore,
        )
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
        val left = scaleX(CARD_LEFT, width)
        val right = scaleX(CARD_RIGHT, width)
        val top = scaleY(CARD_TOP, height)
        val bottom = scaleY(CARD_BOTTOM, height)
        val search = "LTRB($left,$top,$right,$bottom)"
        val ink = inkBox(pixels, width, height, left, right, top, bottom)
        val classic = if (ink == null) null else Glyphs.scored(ink.width, ink.height, ink.bits, Glyphs.cardWords)
        val band = letterRow(pixels, width, height, left, right, top, bottom)
        val shaped = if (band == null) {
            null
        } else {
            Glyphs.scored(band.width, band.height, band.bits, Glyphs.cardWords)
        }
        val activateScore = maxOf(wordScore(ink), wordScore(band))
        val rect = if (band != null) {
            "LTRB(${band.x0},${band.y0},${band.x1},${band.y1})"
        } else {
            search
        }
        val word = Glyphs.accepted(classic, PHRASE_FLOOR) ?: Glyphs.accepted(shaped, PHRASE_FLOOR)
        if (word == null) {
            return Card(HudObservation.NOT_DETECTABLE, false, activateScore, rect, PHRASE_FLOOR)
        }
        return when (word.name) {
            "activate", "activate-game" -> Card("ACTIVATE", true, activateScore, rect, PHRASE_FLOOR)
            "full" -> Card("FULL", false, activateScore, rect, PHRASE_FLOOR)
            else -> {
                val n = word.name.removePrefix("f").toIntOrNull()
                if (n == null) Card(HudObservation.NOT_DETECTABLE, false, activateScore, rect, PHRASE_FLOOR)
                else Card("$n/7", false, activateScore, rect, PHRASE_FLOOR)
            }
        }
    }

    /** Similarity of the ACTIVATE templates, even when another word wins the card. */
    private fun wordScore(ink: Ink?): Double {
        if (ink == null) return 0.0
        return maxOf(
            Glyphs.similarity(ink.width, ink.height, ink.bits, Glyphs.named("activate")),
            Glyphs.similarity(ink.width, ink.height, ink.bits, Glyphs.activateGame),
        )
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

    private fun timePrefixScore(ink: Ink): Double {
        val prefix = Glyphs.named("time")
        val prefixW = (ink.height * prefix.width / prefix.height).coerceIn(1, ink.width - 1)
        if (prefixW >= ink.width) return 0.0
        val left = crop(ink, 0, prefixW)
        return Glyphs.similarity(left.width, left.height, left.bits, prefix)
    }

    private fun timeLeftSeconds(ink: Ink): Int? {
        val prefix = Glyphs.named("time")
        val prefixW = (ink.height * prefix.width / prefix.height).coerceIn(1, ink.width - 1)
        if (prefixW >= ink.width) return null
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

    private data class Ink(
        val width: Int,
        val height: Int,
        val bits: BooleanArray,
        val x0: Int = 0,
        val y0: Int = 0,
        val x1: Int = 0,
        val y1: Int = 0,
    )

    /**
     * Union of letter-sized near-white blobs on one text line.
     * The whole pill is brighter than the word and must not be the matched shape.
     */
    private fun letterRow(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ): Ink? {
        data class Comp(val n: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int)
        val letters = ArrayList<Comp>()
        val seen = HashSet<Int>()
        for (y in top..bottom) {
            if (y !in 0 until height) continue
            for (x in left..right) {
                if (x !in 0 until width) continue
                val start = y * width + x
                if (!seen.add(start) || !ink(pixels[start])) continue
                var x0 = x
                var x1 = x
                var y0 = y
                var y1 = y
                var n = 0
                val queue = ArrayDeque<Int>()
                queue.add(start)
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    val ay = at / width
                    val ax = at - ay * width
                    if (!ink(pixels[at])) continue
                    n++
                    if (ax < x0) x0 = ax
                    if (ax > x1) x1 = ax
                    if (ay < y0) y0 = ay
                    if (ay > y1) y1 = ay
                    val neighbors = intArrayOf(ax - 1, ax + 1, ax, ax)
                    val rows = intArrayOf(ay, ay, ay - 1, ay + 1)
                    for (i in 0 until 4) {
                        val nx = neighbors[i]
                        val ny = rows[i]
                        if (nx !in left..right || ny !in top..bottom) continue
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (!seen.add(next)) continue
                        if (!ink(pixels[next])) continue
                        queue.add(next)
                    }
                }
                val cw = x1 - x0 + 1
                val ch = y1 - y0 + 1
                if (n >= 80 && ch in 20..55 && cw in 4..48) letters.add(Comp(n, x0, y0, x1, y1))
            }
        }
        if (letters.size < 5) return null
        val rows = ArrayList<ArrayList<Comp>>()
        for (comp in letters) {
            val cy = (comp.y0 + comp.y1) / 2
            var placed = false
            for (row in rows) {
                var sum = 0
                for (other in row) sum += (other.y0 + other.y1) / 2
                if (kotlin.math.abs(cy - sum / row.size) < 14) {
                    row.add(comp)
                    placed = true
                    break
                }
            }
            if (!placed) rows.add(arrayListOf(comp))
        }
        val row = rows.maxByOrNull { it.size } ?: return null
        if (row.size < 5) return null
        val x0 = row.minOf { it.x0 }
        val y0 = row.minOf { it.y0 }
        val x1 = row.maxOf { it.x1 }
        val y1 = row.maxOf { it.y1 }
        val cropped = cropRect(pixels, width, height, x0, x1, y0, y1)
        return cropped.copy(x0 = x0, y0 = y0, x1 = x1, y1 = y1)
    }

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
