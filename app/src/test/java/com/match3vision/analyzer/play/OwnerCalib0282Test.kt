package com.match3vision.analyzer.play

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.hud.HudReader
import com.match3vision.analyzer.hud.HudText
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.overlay.OwnerStatus
import com.match3vision.analyzer.vision.OwnerSpecials
import com.match3vision.analyzer.vision.PixelMath
import com.match3vision.analyzer.vision.RealFrameLoader
import com.match3vision.analyzer.vision.SpecialDetector
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import org.junit.After
import org.junit.Test

/**
 * 0.28.2 calibration against the owner layout (1080×2400).
 * The chat attachments were not on disk, so these tests paint the same
 * cues: blue Your Turn bar, red Opponent bar, yellow OUT OF MOVES,
 * pink OPPONENT WINS, yellow Pick a piece, and the special-gem shapes.
 * Any JPEG under owner/ is loaded and must stay 1080×2400.
 */
class OwnerCalib0282Test {
    @After
    fun resetFlags() {
        PlayFlags.reset()
    }

    @Test
    fun turnBars_banners_timeGlyph_andMultiplier() {
        val yours = frame()
        paint(yours, HudText.TURN_LEFT, HudText.TURN_RIGHT, HudText.TURN_TOP, HudText.TURN_BOTTOM, 40, 120, 230)
        val yourHud = HudReader.read(yours, W, H)
        assertThat(yourHud.turnState).isEqualTo(HudObservation.TURN_YOUR)

        val theirs = frame()
        paint(theirs, HudText.TURN_LEFT, HudText.TURN_RIGHT, HudText.TURN_TOP, HudText.TURN_BOTTOM, 190, 40, 70)
        val theirHud = HudReader.read(theirs, W, H)
        assertThat(theirHud.turnState).isEqualTo(HudObservation.TURN_OPPONENT)

        val timed = frame()
        val time = com.match3vision.analyzer.hud.Glyphs.named("time")
        com.match3vision.analyzer.hud.Glyphs.paint(timed, W, H, 180, 1024, time, PixelMath.rgb(255, 255, 255))
        com.match3vision.analyzer.hud.Glyphs.paint(
            timed, W, H, 180 + time.width + 4, 1024,
            com.match3vision.analyzer.hud.Glyphs.named("d7"),
            PixelMath.rgb(255, 255, 255),
        )
        val timeHud = HudReader.read(timed, W, H)
        assertThat(timeHud.timeLeftSeconds).isEqualTo(7)
        assertThat(timeHud.turnState).isEqualTo(HudObservation.TURN_TIME)

        val mult = frame()
        com.match3vision.analyzer.hud.Glyphs.paint(
            mult, W, H, 360, 450,
            com.match3vision.analyzer.hud.Glyphs.named("d3"),
            PixelMath.rgb(255, 255, 255),
        )
        val multHud = HudReader.read(mult, W, H)
        assertThat(multHud.multiplier).isEqualTo(3)

        val pvp = HudObservation(
            soloPositive = true,
            circlesClassifiable = true,
            circlesBright = 3,
            turnState = HudObservation.TURN_YOUR,
            multiplier = 4,
            mode = "pvp",
        )
        assertThat(PlayMode.classify(pvp)).isEqualTo(PlayMode.PVP)
        val opponentCircles = HudObservation(
            soloPositive = true,
            circlesClassifiable = true,
            circlesBright = 8,
            turnState = HudObservation.TURN_OPPONENT,
        )
        assertThat(PlayMode.classify(opponentCircles)).isEqualTo(PlayMode.PVP)
    }

    @Test
    fun endScreens_stopWithJatekVege_andPvpDoesNotStopOnSoloCircles() {
        val out = frame()
        paint(out, 120, 960, 1280, 1680, 240, 200, 40)
        val outHud = HudReader.read(out, W, H)
        assertThat(outHud.endScreen).isEqualTo("out-of-moves")

        val wins = frame()
        paint(wins, 160, 920, 1040, 1400, 230, 40, 140)
        val winsHud = HudReader.read(wins, W, H)
        assertThat(winsHud.endScreen).isEqualTo("opponent-wins")
        assertThat(OwnerStatus.hu("STOP — Játék vége")).isEqualTo("Játék vége")

        val prompt = frame()
        paint(prompt, 250, 830, 680, 820, 240, 210, 30)
        assertThat(HudReader.pickAPiecePrompt(prompt, W, H)).isTrue()
        assertThat(HudReader.pickAPiecePrompt(frame(), W, H)).isFalse()

        val session = FiveMoveSession()
        session.arm(0L)
        repeat(FiveMoveSession.MODE_CONFIRM_FRAMES) {
            session.observeHud(HudObservation.pvp(turnState = HudObservation.TURN_OPPONENT))
        }
        assertThat(session.noteCircles(true, 0, true, 1_000L, 1L)).isNull()
        assertThat(session.noteCircles(true, 0, true, 2_000L, 2L)).isNull()
        assertThat(session.phase).isEqualTo(FiveMoveSession.Phase.RUNNING)
    }

    @Test
    fun helps_shuffleThenHammerThenBox_andThePromptTap() {
        PlayFlags.helps = true
        val none = HelpPolicy.Charges(hammer = 1, shuffle = 1, box = 1, geometryVerified = true)
        val stuck = HelpPolicy.choose(false, false, none, 0, hasThreeMatch = false, boxReady = true)
        assertThat(stuck!!.id).isEqualTo("shuffle")
        assertThat(stuck.reason).isEqualTo("no legal move")
        val noThree = HelpPolicy.Charges(hammer = 1, shuffle = 0, box = 0, geometryVerified = true)
        val hammer = HelpPolicy.choose(true, false, noThree, 0, hasThreeMatch = false, boxReady = false)
        assertThat(hammer!!.id).isEqualTo("hammer")
        assertThat(hammer.reason).isEqualTo("no 3-match")
        val boxOnly = HelpPolicy.Charges(hammer = 0, shuffle = 0, box = 1, geometryVerified = true)
        val box = HelpPolicy.choose(true, false, boxOnly, 0, hasThreeMatch = true, boxReady = true)
        assertThat(box!!.id).isEqualTo("box")
        assertThat(box.reason).isEqualTo("adjacent to a cluster")
        assertThat(HelpPolicy.choose(true, false, boxOnly, HelpPolicy.MAX_PER_TURN, boxReady = true)).isNull()

        val pixels = frame()
        paintDisk(pixels, HelpButtons.HAMMER_X, HelpButtons.HAMMER_Y, 40, 140, 230)
        paintDisk(pixels, HelpButtons.SHUFFLE_X, HelpButtons.SHUFFLE_Y, 90, 90, 90)
        val charges = HelpButtons.charges(pixels, W, H)
        assertThat(charges.geometryVerified).isTrue()
        assertThat(charges.hammer).isEqualTo(1)
        assertThat(charges.shuffle).isEqualTo(0)

        val session = FiveMoveSession()
        session.arm(0L)
        val choice = session.considerHelp(
            hasLegalMove = false,
            extraMoveAvailable = false,
            hasThreeMatch = false,
            charges = HelpPolicy.Charges(hammer = 1, shuffle = 0, box = 0, geometryVerified = true),
        )
        val button = session.advanceHelp(1_000L, false, true, choice, 400f, 1500f)
        assertThat(button).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        assertThat(session.helpPhase).isEqualTo("prompt")
        assertThat(session.report()).contains("help tap=hammer")
        val waiting = session.advanceHelp(1_400L, false, true, null, 400f, 1500f)
        assertThat(waiting).isInstanceOf(FiveMoveSession.HelpGesture.Hold::class.java)
        assertThat((waiting as FiveMoveSession.HelpGesture.Hold).reason).contains("pick a piece")
        val pick = session.advanceHelp(1_800L, true, true, null, 400f, 1500f)
        assertThat(pick).isInstanceOf(FiveMoveSession.HelpGesture.Tap::class.java)
        assertThat((pick as FiveMoveSession.HelpGesture.Tap).x).isEqualTo(400f)
        assertThat(session.report()).contains("help pick")

        val colors = Array(Board.SIZE) { Array(Board.SIZE) { TileColor.R } }
        colors[2][2] = TileColor.B
        colors[2][3] = TileColor.B
        colors[2][4] = TileColor.G
        val beside = HelpTargets.adjacentToCluster(Board.fromColors(colors))
        assertThat(beside).isNotNull()
        assertThat(beside!!.first).isEqualTo(2)
    }

    @Test
    fun noTargetBoosters_balloonBlastAndArmyDuck() {
        val registry = BoosterRegistry.parse(asset("booster_registry.json").readText())
        assertThat(registry.get("balloon_blast")!!.needsTarget).isFalse()
        assertThat(registry.get("army_duck")!!.needsTarget).isFalse()
        val helps = BoosterRegistry.parse(asset("help_registry.json").readText())
        assertThat(helps.get("box")!!.needsTarget).isTrue()
        assertThat(helps.get("hammer")!!.needsTarget).isTrue()
        assertThat(helps.get("shuffle")!!.needsTarget).isFalse()
    }

    @Test
    fun specials_perClassOnOwnerShapes_andFlagStaysOff() {
        assertThat(PlayFlags.specials).isFalse()
        val cases = listOf(
            expect("arrow-row", rowArrow(), SpecialType.TWO_WAY_ARROW, SpecialDetector.AXIS_ROW),
            expect("arrow-col", colArrow(), SpecialType.TWO_WAY_ARROW, SpecialDetector.AXIS_COL),
            expect("bomb-skull", skull(), SpecialType.BOMB, 0),
            expect("bomb-round", roundBomb(), SpecialType.BOMB, 0),
            expect("color-icy", icy(), SpecialType.LIGHTNING, 0),
            expect("color-rainbow", rainbow(), SpecialType.LIGHTNING, 0),
            expect("plain", solid(40, 180, 60), SpecialType.NONE, 0),
        )
        val misses = cases.filter { sample ->
            val got = OwnerSpecials.classify(sample.pixels, CELL, CELL)
            got.special != sample.special || (sample.special == SpecialType.TWO_WAY_ARROW && got.axis != sample.axis)
        }
        assertThat(misses.map { it.name }).isEmpty()
        assertThat(cases.map { it.name }).containsAtLeast(
            "arrow-row",
            "arrow-col",
            "bomb-skull",
            "bomb-round",
            "color-icy",
            "color-rainbow",
            "plain",
        )
    }

    @Test
    fun ownerJpegs_whenPresent_are1080x2400() {
        val names = listOf(
            "v1_activate_card.jpg",
            "v1_out_of_moves.jpg",
            "v2_your_turn.jpg",
            "v2_opponent_wins.jpg",
        )
        var present = 0
        for (name in names) {
            val loaded = RealFrameLoader.loadFromResource("owner/$name") ?: continue
            present++
            assertThat(loaded.width).isEqualTo(1080)
            assertThat(loaded.height).isEqualTo(2400)
        }
        if (present == 0) {
            assertThat(RealFrameLoader.resourceExists("owner/v1_activate_card.jpg")).isFalse()
        }
    }

    private data class Sample(val name: String, val pixels: IntArray, val special: SpecialType, val axis: Int)

    private fun expect(name: String, pixels: IntArray, special: SpecialType, axis: Int) =
        Sample(name, pixels, special, axis)

    private fun frame(): IntArray = IntArray(W * H) { PixelMath.rgb(8, 6, 20) }

    private fun paint(pixels: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = PixelMath.rgb(r, g, b)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                pixels[y * W + x] = color
            }
        }
    }

    private fun paintDisk(pixels: IntArray, cx: Int, cy: Int, r: Int, g: Int, b: Int) {
        val color = PixelMath.rgb(r, g, b)
        for (y in cy - 16..cy + 16) {
            for (x in cx - 16..cx + 16) {
                pixels[y * W + x] = color
            }
        }
    }

    private fun solid(r: Int, g: Int, b: Int): IntArray = IntArray(CELL * CELL) { PixelMath.rgb(r, g, b) }

    private fun rowArrow(): IntArray {
        val cell = solid(200, 40, 40)
        paintCell(cell, 1, 12, 20, 28, 250, 220, 40)
        paintCell(cell, 36, 47, 20, 28, 250, 220, 40)
        return cell
    }

    private fun colArrow(): IntArray {
        val cell = solid(40, 90, 200)
        paintCell(cell, 20, 28, 1, 12, 80, 220, 250)
        paintCell(cell, 20, 28, 36, 47, 80, 220, 250)
        return cell
    }

    private fun skull(): IntArray {
        val cell = solid(20, 10, 30)
        val cx = CELL / 2f
        val cy = CELL / 2f
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d < 18 * 18) cell[y * CELL + x] = PixelMath.rgb(210, 30, 40)
                if (d < 6 * 6) cell[y * CELL + x] = PixelMath.rgb(10, 10, 10)
            }
        }
        return cell
    }

    private fun roundBomb(): IntArray {
        val cell = solid(30, 20, 40)
        val cx = CELL / 2f
        val cy = CELL / 2f + 2
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d < 16 * 16) cell[y * CELL + x] = PixelMath.rgb(12, 12, 16)
                if (y < 10 && x in 22..26) cell[y * CELL + x] = PixelMath.rgb(240, 140, 30)
            }
        }
        return cell
    }

    private fun icy(): IntArray {
        val cell = solid(20, 16, 40)
        val cx = CELL / 2
        val cy = CELL / 2
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                val dx = kotlin.math.abs(x - cx)
                val dy = kotlin.math.abs(y - cy)
                if (dx + dy < 16) cell[y * CELL + x] = PixelMath.rgb(230, 245, 255)
            }
        }
        return cell
    }

    private fun rainbow(): IntArray {
        val cell = solid(16, 12, 28)
        val cx = CELL / 2f
        val cy = CELL / 2f
        val hues = arrayOf(
            PixelMath.rgb(230, 40, 40),
            PixelMath.rgb(240, 200, 30),
            PixelMath.rgb(40, 200, 60),
            PixelMath.rgb(40, 80, 230),
            PixelMath.rgb(180, 40, 220),
            PixelMath.rgb(40, 210, 210),
        )
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > 16 * 16) continue
                val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                val bucket = (((angle + Math.PI) / (2 * Math.PI)) * hues.size).toInt().coerceIn(0, hues.lastIndex)
                cell[y * CELL + x] = hues[bucket]
            }
        }
        return cell
    }

    private fun paintCell(cell: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, r: Int, g: Int, b: Int) {
        val color = PixelMath.rgb(r, g, b)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                cell[y * CELL + x] = color
            }
        }
    }

    private fun asset(name: String): java.io.File =
        listOf(java.io.File("src/main/assets/$name"), java.io.File("app/src/main/assets/$name"))
            .firstOrNull { it.exists() }
            ?: error("$name not found")

    private companion object {
        const val W = 1080
        const val H = 2400
        const val CELL = 48
    }
}
