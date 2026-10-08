package com.match3vision.analyzer.input

import com.match3vision.analyzer.vision.VisionThresholds

/**
 * Waits for one frame that already passes the 5-move preconditions.
 *
 * A single failing snapshot does not refuse the button. The wait is
 * [SEEK_MS], counted inside the 60 s session once the session arms.
 * Post-move checks stay on [FiveMoveSession]: a later frame must be
 * captured after the gesture.
 *
 * Freshness for this start frame is age <= 3000 ms, or age <= 3x the
 * capture cadence median when that is larger. [GestureFailSafe.MAX_FRAME_AGE_MS]
 * is not changed.
 */
object FiveMoveStart {
    const val SEEK_MS = 5_000L
    const val FRESH_FLOOR_MS = GestureFailSafe.MAX_FRAME_AGE_MS

    data class Sample(
        val nowMs: Long,
        val frameSequence: Long,
        val frameAgeMs: Long,
        val cadenceMedianMs: Long,
        val a11yConnected: Boolean,
        val selfCheckMeasured: Boolean,
        val overlayCollapsed: Boolean,
        val overlayOutsideRoi: Boolean,
        val visionPass: Boolean,
        val unknownCount: Int,
        val roiPlausible: Boolean,
        val roiDetail: String,
        val ownUi: Boolean,
        val calibration: Boolean,
        val msSinceCollapse: Long,
    )

    data class Check(val name: String, val value: String, val pass: Boolean)

    data class Report(
        val ready: Boolean,
        val chip: String,
        val export: String,
        val checks: List<Check>,
    )

    sealed class Offer {
        data class Waiting(val report: Report) : Offer()
        data class Ready(val report: Report) : Offer()
        data class Expired(val report: Report) : Offer()
    }

    fun freshLimitMs(cadenceMedianMs: Long): Long {
        val fromCadence = if (cadenceMedianMs > 0L) cadenceMedianMs * 3L else 0L
        return maxOf(FRESH_FLOOR_MS, fromCadence)
    }

    fun evaluate(sample: Sample): Report {
        val freshLimit = freshLimitMs(sample.cadenceMedianMs)
        val fresh = sample.frameAgeMs in 0..freshLimit
        val unknownOk = sample.unknownCount in 0..VisionThresholds.MAX_UNKNOWN_COUNT
        val collapseOk = sample.msSinceCollapse >= FiveMoveSession.MIN_POST_COLLAPSE_MS
        val checks = listOf(
            Check("a11y", sample.a11yConnected.toString(), sample.a11yConnected),
            Check("selfCheck", sample.selfCheckMeasured.toString(), sample.selfCheckMeasured),
            Check("ownUi", sample.ownUi.toString(), !sample.ownUi),
            Check("calibration", sample.calibration.toString(), !sample.calibration),
            Check("overlayCollapsed", sample.overlayCollapsed.toString(), sample.overlayCollapsed),
            Check("msSinceCollapse", sample.msSinceCollapse.toString(), collapseOk),
            Check("overlayOutside", sample.overlayOutsideRoi.toString(), sample.overlayOutsideRoi),
            Check("roiPlausible", if (sample.roiPlausible) "yes" else sample.roiDetail, sample.roiPlausible),
            Check("visionPass", sample.visionPass.toString(), sample.visionPass),
            Check("unknown", sample.unknownCount.toString(), unknownOk),
            Check(
                "frameFresh",
                "ageMs=${sample.frameAgeMs} limitMs=$freshLimit",
                fresh,
            ),
        )
        val failed = checks.firstOrNull { !it.pass }
        val chip = when (failed?.name) {
            null -> ""
            "a11y" -> FiveMoveArm.NEED_A11Y
            "selfCheck" -> FiveMoveArm.NEED_CALIBRATION
            "ownUi" -> "Saját felület látszik"
            "calibration" -> "Kalibráló réteg látszik"
            "overlayCollapsed" -> "Buborék nincs összecsukva"
            "msSinceCollapse" -> "Várakozás az összecsukás után"
            "overlayOutside" -> "A buborék takarja a táblát"
            "roiPlausible" -> "Tábla nem látszik: ${sample.roiDetail}"
            "visionPass" -> "Tábla nem látszik: vision HOLD"
            "unknown" -> "Tábla nem látszik: unknown=${sample.unknownCount}"
            "frameFresh" -> "Képkocka régi: ${sample.frameAgeMs} ms"
            else -> failed.name
        }
        val export = buildString {
            appendLine("fiveMoveSession")
            appendLine("startMs=${sample.nowMs}")
            appendLine("seekLimitMs=$SEEK_MS")
            appendLine(
                "frame seq=${sample.frameSequence} ageMs=${sample.frameAgeMs} " +
                    "freshLimitMs=$freshLimit cadenceMedianMs=${sample.cadenceMedianMs}",
            )
            checks.forEach { check ->
                appendLine("${check.name}=${check.value} ${if (check.pass) "PASS" else "FAIL"}")
            }
            appendLine("refusal=${if (failed == null) "none" else chip}")
        }
        return Report(ready = failed == null, chip = chip, export = export, checks = checks)
    }

    fun immediate(nowMs: Long, reason: String): String = buildString {
        appendLine("fiveMoveSession")
        appendLine("startMs=$nowMs")
        appendLine("seekLimitMs=$SEEK_MS")
        appendLine("frame seq=none ageMs=none")
        appendLine("refusal=$reason")
    }

    fun noFrame(startedAtMs: Long, nowMs: Long): Report {
        val chip = "nincs friss képkocka"
        val export = buildString {
            appendLine("fiveMoveSession")
            appendLine("startMs=$startedAtMs")
            appendLine("seekLimitMs=$SEEK_MS")
            appendLine("frame seq=none ageMs=none")
            appendLine("elapsedMs=${(nowMs - startedAtMs).coerceAtLeast(0L)}")
            appendLine("refusal=$chip")
        }
        return Report(ready = false, chip = chip, export = export, checks = emptyList())
    }
}

/**
 * Button press starts the wait. One failing frame keeps it open until [FiveMoveStart.SEEK_MS].
 */
class FiveMoveSeek {
    var startedAtMs: Long = 0L
        private set

    var isActive: Boolean = false
        private set

    private var last: FiveMoveStart.Report? = null

    fun begin(nowMs: Long) {
        startedAtMs = nowMs
        isActive = true
        last = null
    }

    fun offer(nowMs: Long, sample: FiveMoveStart.Sample): FiveMoveStart.Offer {
        val report = FiveMoveStart.evaluate(sample.copy(nowMs = startedAtMs))
        last = report
        if (report.ready) {
            isActive = false
            return FiveMoveStart.Offer.Ready(report)
        }
        if (nowMs - startedAtMs >= FiveMoveStart.SEEK_MS) {
            isActive = false
            return FiveMoveStart.Offer.Expired(report)
        }
        return FiveMoveStart.Offer.Waiting(report)
    }

    /** Deadline already passed on an earlier iteration that had no frame. */
    fun expireOverdue(nowMs: Long): FiveMoveStart.Offer.Expired? {
        if (!isActive) return null
        if (nowMs - startedAtMs <= FiveMoveStart.SEEK_MS) return null
        isActive = false
        val report = last ?: FiveMoveStart.noFrame(startedAtMs, nowMs)
        return FiveMoveStart.Offer.Expired(report)
    }
}
