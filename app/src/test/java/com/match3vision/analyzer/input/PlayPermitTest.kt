package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** [PlayPermit] must stay on the test classpath. Production sources must not name it. */
class PlayPermitTest {

    @Test
    fun playPermit_isAbsentFromProductionSources() {
        val root = moduleRoot()
        val main = File(root, "src/main")
        assertThat(main.isDirectory).isTrue()
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("PlayPermit") }
            .map { it.invariantSeparatorsPath }
            .toList()
        assertThat(hits).isEmpty()
        val permit = File(root, "src/test/java/com/match3vision/analyzer/input/PlayPermit.kt")
        assertThat(permit.isFile).isTrue()
        val productionTouch = File(root, "src/main/java/com/match3vision/analyzer/input/CalibrationTouch.kt")
        assertThat(productionTouch.isFile).isTrue()
        assertThat(productionTouch.readText()).doesNotContain("dispatchGesture(")
        val bubble = File(root, "src/main/java/com/match3vision/analyzer/overlay/FloatingBubbleService.kt")
        assertThat(bubble.readText()).doesNotContain("touchTest.runOnce")
        assertThat(bubble.readText()).doesNotContain("dispatchGesture(")
    }

    private fun moduleRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        if (File(cwd, "src/main").isDirectory) return cwd
        val app = File(cwd, "app")
        if (File(app, "src/main").isDirectory) return app
        error("cannot find app/src/main from ${cwd.absolutePath}")
    }
}
