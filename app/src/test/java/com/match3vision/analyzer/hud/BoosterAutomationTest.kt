package com.match3vision.analyzer.hud

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import org.junit.Test
import java.io.File

/**
 * The catalog is data. The planner may return a tap only for a phone-verified
 * no-target booster. Nothing here dispatches a gesture.
 */
class BoosterAutomationTest {
    private val catalog = BoosterCatalog.parse(catalogFile().readText())

    @Test
    fun catalog_countsBoostersPerksAndPoweredUp() {
        assertThat(catalog.entries).hasSize(59)
        assertThat(catalog.count("booster")).isEqualTo(40)
        assertThat(catalog.count("powered_up_variant")).isEqualTo(4)
        assertThat(catalog.count("perk")).isEqualTo(14)
        assertThat(catalog.count("legacy_booster")).isEqualTo(1)
        assertThat(catalog.get("laser_beam")).isNotNull()
        assertThat(catalog.get("balloon_blast")!!.v1Safe).isTrue()
        assertThat(catalog.get("all_aboard")!!.name).isEqualTo("All Aboard")
        assertThat(catalog.get("detonator")!!.kind).isEqualTo("perk")
    }

    @Test
    fun preconditions_yellowAndSpecialAreTheOnlyBoardChecks() {
        assertThat(BoosterAutomation.classify(catalog.get("all_aboard")!!.targetRequirement))
            .isEqualTo(BoosterAutomation.Precondition.YELLOW)
        assertThat(BoosterAutomation.classify(catalog.get("detonator")!!.targetRequirement))
            .isEqualTo(BoosterAutomation.Precondition.SPECIAL)
        assertThat(BoosterAutomation.classify(catalog.get("crazy_clovers")!!.targetRequirement))
            .isEqualTo(BoosterAutomation.Precondition.NONE)
        assertThat(BoosterAutomation.classify(catalog.get("doctor_color_se")!!.targetRequirement))
            .isEqualTo(BoosterAutomation.Precondition.UNRECOGNIZED)
    }

    @Test
    fun module_isNotEnabled_andTheBubbleDoesNotCallIt() {
        assertThat(BoosterAutomation.ENABLED).isFalse()
        val bubble = File(moduleRoot(), "src/main/java/com/match3vision/analyzer/overlay/FloatingBubbleService.kt")
        assertThat(bubble.readText()).doesNotContain("BoosterAutomation")
        assertThat(bubble.readText()).doesNotContain("booster_db.json")
    }

    @Test
    fun allAboard_withoutYellow_doesNotTap() {
        val decision = plan("all_aboard", board = noYellow(), verified = setOf("all_aboard"))
        assertThat(decision).isInstanceOf(Decision.Wait::class.java)
        assertThat((decision as Decision.Wait).reason).contains("yellow")
    }

    @Test
    fun allAboard_withYellowAndNoExtra_tapsOnce() {
        val decision = plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"))
        val tap = decision as Decision.Tap
        assertThat(tap.id).isEqualTo("all_aboard")
        assertThat(tap.verification.requiresStablePass).isTrue()
        assertThat(tap.verification.requiresBoardChange).isTrue()
        assertThat(tap.verification.latchIfUnsure).isTrue()
    }

    @Test
    fun allAboard_waitsWhileAnExtraMoveRemains() {
        val decision = plan(
            "all_aboard",
            board = withYellow(),
            verified = setOf("all_aboard"),
            extraMoveAvailable = true,
        )
        assertThat(decision).isInstanceOf(Decision.Wait::class.java)
        assertThat((decision as Decision.Wait).reason).contains("extra move")
    }

    @Test
    fun detonator_withoutASpecial_doesNotTap() {
        val decision = plan("detonator", board = noYellow(), verified = setOf("detonator"))
        assertThat(decision).isInstanceOf(Decision.Wait::class.java)
        assertThat((decision as Decision.Wait).reason).contains("special")
    }

    @Test
    fun detonator_withASpecial_stillRefusesBecausePerksAreNotCalibrated() {
        val decision = plan("detonator", board = withSpecial(), verified = setOf("detonator"))
        val refuse = decision as Decision.Refuse
        assertThat(refuse.latch).isTrue()
        assertThat(refuse.reason).contains("perk")
    }

    @Test
    fun balloonBlast_isNotTappedUntilVerifiedOnThisPhone() {
        val decision = plan("balloon_blast", board = noYellow(), verified = emptySet())
        val refuse = decision as Decision.Refuse
        assertThat(refuse.latch).isTrue()
        assertThat(refuse.reason).isEqualTo("not verified on this phone")
    }

    @Test
    fun unrecognizedPrecondition_latchesAndDoesNotTap() {
        val decision = plan("doctor_color_se", board = withYellow(), verified = setOf("doctor_color_se"))
        val refuse = decision as Decision.Refuse
        assertThat(refuse.latch).isTrue()
        assertThat(refuse.reason).contains("precondition")
    }

    @Test
    fun card_mustBeLeftActivate_andAFullBarWaits() {
        assertThat(plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"), label = "3/7"))
            .isInstanceOf(Decision.Wait::class.java)
        assertThat(plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"), label = "FULL"))
            .isInstanceOf(Decision.Wait::class.java)
        assertThat(plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"), label = "7/7"))
            .isInstanceOf(Decision.Wait::class.java)
        val right = plan(
            "all_aboard",
            board = withYellow(),
            verified = setOf("all_aboard"),
            card = OwnCard(CardSide.RIGHT, "ACTIVATE"),
        )
        assertThat((right as Decision.Refuse).latch).isTrue()
        val unread = plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"), label = "not detectable")
        assertThat(unread).isInstanceOf(Decision.Wait::class.java)
    }

    @Test
    fun unknownIdentity_doesNotLatchTheSession() {
        val missing = plan(null, board = withYellow(), verified = setOf("all_aboard"))
        assertThat((missing as Decision.Refuse).latch).isFalse()
        val again = plan("all_aboard", board = withYellow(), verified = setOf("all_aboard"), alreadyTapped = true)
        assertThat((again as Decision.Refuse).latch).isTrue()
    }

    @Test
    fun followUpCategory_isRefused() {
        val decision = plan("jelly_se", board = withYellow(), verified = setOf("jelly_se"))
        assertThat(decision).isInstanceOf(Decision.Refuse::class.java)
    }

    @Test
    fun settle_latchesWhetherOrNotTheBoardChanged() {
        assertThat(BoosterAutomation.settle(boardChanged = true, stablePass = true).latch).isTrue()
        val missed = BoosterAutomation.settle(boardChanged = false, stablePass = true)
        assertThat(missed.latch).isTrue()
        assertThat(missed.reason).contains("not verified")
        assertThat(BoosterAutomation.settle(boardChanged = true, stablePass = false).latch).isTrue()
    }

    private fun plan(
        identity: String?,
        board: Board?,
        verified: Set<String>,
        extraMoveAvailable: Boolean = false,
        label: String = "ACTIVATE",
        card: OwnCard = OwnCard.left(label),
        alreadyTapped: Boolean = false,
    ): Decision = BoosterAutomation.plan(
        Request(
            catalog = catalog,
            identity = identity,
            card = card,
            board = board,
            extraMoveAvailable = extraMoveAvailable,
            phoneVerified = verified,
            alreadyTapped = alreadyTapped,
        ),
    )

    private fun noYellow(): Board {
        val palette = listOf(TileColor.R, TileColor.B, TileColor.G, TileColor.P, TileColor.O)
        return Board.fromColors(Array(7) { r -> Array(7) { c -> palette[(r * 2 + c) % 5] } })
    }

    private fun withYellow(): Board {
        val base = noYellow()
        val cell = base.get(0, 0)
        return base.setCopy(
            0,
            0,
            cell.copy(color = TileColor.Y, shape = TileShape.TRIANGLE),
        )
    }

    private fun withSpecial(): Board {
        val base = noYellow()
        val cell = base.get(1, 1)
        return base.setCopy(1, 1, cell.copy(special = SpecialType.BOMB))
    }

    private fun catalogFile(): File {
        val names = listOf(
            File("src/main/assets/booster_db.json"),
            File("app/src/main/assets/booster_db.json"),
        )
        return names.firstOrNull { it.isFile }
            ?: error("booster_db.json not found from ${File(".").absolutePath}")
    }

    private fun moduleRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        if (File(cwd, "src/main").isDirectory) return cwd
        val app = File(cwd, "app")
        if (File(app, "src/main").isDirectory) return app
        error("cannot find app/src/main from ${cwd.absolutePath}")
    }
}
