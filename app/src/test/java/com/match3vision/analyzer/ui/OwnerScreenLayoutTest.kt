package com.match3vision.analyzer.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** The owner screen keeps the 0.26.0 buttons, including diagnostic copy. */
class OwnerScreenLayoutTest {
    @Test
    fun exportCopy_staysOnTheVisibleScreenAndBubble() {
        val screen = source("ui/AnalyzerScreen.kt")
        assertThat(screen).contains("Diagnosztika másolása")
        assertThat(screen).contains("\"INDÍTÁS\"")
        assertThat(screen).doesNotContain("debugOpen")

        val bubble = source("overlay/FloatingBubbleService.kt")
        assertThat(bubble).contains("text = \"INDÍTÁS\"")
        assertThat(bubble).contains("text = \"START\"")
        assertThat(bubble).contains("TESZT ÉRINTÉS")
        assertThat(bubble).contains("10 LÉPÉS TESZT")
        assertThat(bubble).contains("EGY LÉPÉS")
        assertThat(bubble).contains("DIAG MÁSOL")
        assertThat(bubble).contains("DIAG MEGOSZT")
        assertThat(bubble).contains("BoosterControl.label()")
        assertThat(bubble).doesNotContain("debugMenu")
        assertThat(bubble).doesNotContain("dispatchGesture(")
    }

    private fun source(relative: String): String {
        val root = moduleRoot()
        val file = File(root, "src/main/java/com/match3vision/analyzer/$relative")
        assertThat(file.isFile).isTrue()
        return file.readText()
    }

    private fun moduleRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        if (File(cwd, "src/main").isDirectory) return cwd
        val app = File(cwd, "app")
        if (File(app, "src/main").isDirectory) return app
        error("cannot find app/src/main from ${cwd.absolutePath}")
    }
}
