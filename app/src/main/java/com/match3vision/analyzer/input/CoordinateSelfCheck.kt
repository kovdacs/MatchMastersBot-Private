package com.match3vision.analyzer.input

import java.io.File

/**
 * In-memory record of one TESZT ÉRINTÉS self-check.
 *
 * EGY LÉPÉS calls [allowsSingleMoveArm] and refuses until the status is
 * [STATUS_RECORDED_UNPROVEN]. That status means the check was recorded.
 * It does not mean the touch landed on the expected pixel.
 * [Record.alignmentProven] is always false.
 *
 * The record lives in this process. Process death clears it. A new process
 * must run TESZT ÉRINTÉS again. [com.match3vision.analyzer.input.AutoPlayController.resetForNewSession]
 * does not clear it.
 */
object CoordinateSelfCheck {
    const val STATUS_ABSENT = "ABSENT"
    const val STATUS_REFUSED = "REFUSED"
    const val STATUS_RECORDED_UNPROVEN = "RECORDED_UNPROVEN"

    data class Record(
        val status: String,
        val expectedX: Float,
        val expectedY: Float,
        val screenWidth: Int,
        val screenHeight: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        val rotation: Int,
        val statusBarInsetPx: Int,
        val navigationBarInsetPx: Int,
        val cutoutInsetPx: Int,
        val originOffsetX: Int,
        val originOffsetY: Int,
        val observedNote: String,
        val alignmentProven: Boolean,
        val reason: String,
    ) {
        init {
            require(!alignmentProven) { "coordinate self-check cannot set alignmentProven" }
        }

        fun toJson(): String = buildString {
            append("{\n")
            line("status", status)
            line("expectedX", expectedX.toString(), numeric = true)
            line("expectedY", expectedY.toString(), numeric = true)
            line("screenWidth", screenWidth.toString(), numeric = true)
            line("screenHeight", screenHeight.toString(), numeric = true)
            line("frameWidth", frameWidth.toString(), numeric = true)
            line("frameHeight", frameHeight.toString(), numeric = true)
            line("rotation", rotation.toString(), numeric = true)
            line("statusBarInsetPx", statusBarInsetPx.toString(), numeric = true)
            line("navigationBarInsetPx", navigationBarInsetPx.toString(), numeric = true)
            line("cutoutInsetPx", cutoutInsetPx.toString(), numeric = true)
            line("originOffsetX", originOffsetX.toString(), numeric = true)
            line("originOffsetY", originOffsetY.toString(), numeric = true)
            line("observedNote", observedNote)
            line("alignmentProven", "false", raw = true)
            line("reason", reason, last = true)
            append("}\n")
        }

        private fun StringBuilder.line(
            name: String,
            value: String,
            numeric: Boolean = false,
            raw: Boolean = false,
            last: Boolean = false,
        ) {
            append("  \"").append(name).append("\": ")
            if (raw || numeric) append(value) else append(jsonString(value))
            if (!last) append(",")
            append("\n")
        }
    }

    @Volatile
    private var current: Record? = null

    fun current(): Record? = current

    fun statusLabel(): String = current?.status ?: STATUS_ABSENT

    fun clear() {
        current = null
    }

    fun allowsSingleMoveArm(): Boolean = current?.status == STATUS_RECORDED_UNPROVEN

    fun refuse(reason: String): Record {
        val rec = Record(
            status = STATUS_REFUSED,
            expectedX = 0f,
            expectedY = 0f,
            screenWidth = 0,
            screenHeight = 0,
            frameWidth = 0,
            frameHeight = 0,
            rotation = -1,
            statusBarInsetPx = 0,
            navigationBarInsetPx = 0,
            cutoutInsetPx = 0,
            originOffsetX = 0,
            originOffsetY = 0,
            observedNote = "touch indicator NOT MEASURED",
            alignmentProven = false,
            reason = reason,
        )
        current = rec
        return rec
    }

    /**
     * @param observedLuma luma of the capture pixel at the expected point, or
     * null when no frame was copied. Null is recorded as not measured. It is
     * not a detected touch-indicator position.
     * @param statusBarInsetPx recorded only. It is not added to [expectedY].
     */
    fun record(
        expectedX: Float,
        expectedY: Float,
        screenWidth: Int,
        screenHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotation: Int,
        statusBarInsetPx: Int = 0,
        navigationBarInsetPx: Int = 0,
        cutoutInsetPx: Int = 0,
        originOffsetX: Int = 0,
        originOffsetY: Int = 0,
        observedLuma: Float? = null,
    ): Record {
        val observed = if (observedLuma == null) {
            "touch indicator NOT MEASURED — no capture pixel at " +
                "(${expectedX.toInt()},${expectedY.toInt()})"
        } else {
            "luma at expected (${expectedX.toInt()},${expectedY.toInt()})=" +
                "%.1f".format(observedLuma) +
                "; this is not a measured touch-indicator position"
        }
        val insetNote = "statusBar=${statusBarInsetPx} nav=${navigationBarInsetPx} " +
            "cutout=${cutoutInsetPx} recorded, not applied"
        val policy = DisplayInsetPolicy.refusal(rotation, originOffsetX, originOffsetY)
        val frameKnown = frameWidth > 0 || frameHeight > 0
        val sizeMismatch = frameKnown &&
            (frameWidth != screenWidth || frameHeight != screenHeight)
        val (status, reason) = when {
            screenWidth <= 0 || screenHeight <= 0 -> STATUS_REFUSED to
                "screen size unknown ${screenWidth}x$screenHeight"
            policy != null -> STATUS_REFUSED to policy
            sizeMismatch -> STATUS_REFUSED to
                "frame/screen size mismatch frame=${frameWidth}x$frameHeight " +
                "screen=${screenWidth}x$screenHeight"
            else -> STATUS_RECORDED_UNPROVEN to
                "TESZT ÉRINTÉS recorded expected=(${expectedX},${expectedY}) " +
                "screen=${screenWidth}x$screenHeight frame=${frameWidth}x$frameHeight " +
                "rotation=$rotation. $insetNote. $observed. " +
                "alignmentProven=false. Physical alignment is NOT proven."
        }
        val rec = Record(
            status = status,
            expectedX = expectedX,
            expectedY = expectedY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            rotation = rotation,
            statusBarInsetPx = statusBarInsetPx,
            navigationBarInsetPx = navigationBarInsetPx,
            cutoutInsetPx = cutoutInsetPx,
            originOffsetX = originOffsetX,
            originOffsetY = originOffsetY,
            observedNote = observed,
            alignmentProven = false,
            reason = reason,
        )
        current = rec
        return rec
    }

    fun write(directory: File) {
        directory.mkdirs()
        val body = current?.toJson() ?: "{ \"status\": \"ABSENT\", \"alignmentProven\": false }\n"
        File(directory, "coordinate-self-check.json").writeText(body)
    }

    private fun jsonString(raw: String): String {
        val escaped = buildString(raw.length + 8) {
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }
}
