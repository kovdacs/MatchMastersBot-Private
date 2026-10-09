package com.match3vision.analyzer.input

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/**
 * Screen identity for a saved TESZT ÉRINTÉS hit.
 * A later session reuses the hit only when every field still matches.
 */
data class ScreenKey(
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val densityDpi: Int,
    val versionCode: Int,
) {
    fun text(): String =
        "${screenWidth}x$screenHeight r=$rotation dpi=$densityDpi code=$versionCode"

    companion object {
        private val PATTERN = Regex("""(\d+)x(\d+) r=(-?\d+) dpi=(\d+) code=(-?\d+)""")

        fun parse(text: String): ScreenKey? {
            val match = PATTERN.find(text.trim()) ?: return null
            return ScreenKey(
                screenWidth = match.groupValues[1].toInt(),
                screenHeight = match.groupValues[2].toInt(),
                rotation = match.groupValues[3].toInt(),
                densityDpi = match.groupValues[4].toInt(),
                versionCode = match.groupValues[5].toInt(),
            )
        }
    }
}

/**
 * One successful measurement plus the screen key it belongs to.
 * [CoordinateSelfCheck.Record.alignmentProven] stays false.
 */
data class SavedCalibration(
    val key: ScreenKey,
    val record: CoordinateSelfCheck.Record,
) {
    init {
        require(!record.alignmentProven) { "saved calibration cannot set alignmentProven" }
    }

    fun distancePx(): Float? {
        val observedX = record.observedX ?: return null
        val observedY = record.observedY ?: return null
        val dx = observedX - record.expectedX
        val dy = observedY - record.expectedY
        return sqrt(dx * dx + dy * dy)
    }

    /** Chip and export line for a calibration reused from a previous session. */
    fun chip(zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DATE.format(Instant.ofEpochMilli(record.recordedAtMs).atZone(zone))
        val distance = distancePx()
        val shown = if (distance == null) "n/a" else "%.1fpx".format(Locale.US, distance)
        return "kalibráció: mentett ($date, $shown)"
    }

    fun encode(): String = buildString {
        appendLine("key=${key.text()}")
        appendLine("status=${record.status}")
        appendLine("expectedX=${record.expectedX}")
        appendLine("expectedY=${record.expectedY}")
        appendLine("screenWidth=${record.screenWidth}")
        appendLine("screenHeight=${record.screenHeight}")
        appendLine("frameWidth=${record.frameWidth}")
        appendLine("frameHeight=${record.frameHeight}")
        appendLine("rotation=${record.rotation}")
        appendLine("statusBarInsetPx=${record.statusBarInsetPx}")
        appendLine("navigationBarInsetPx=${record.navigationBarInsetPx}")
        appendLine("cutoutInsetPx=${record.cutoutInsetPx}")
        appendLine("originOffsetX=${record.originOffsetX}")
        appendLine("originOffsetY=${record.originOffsetY}")
        appendLine("observedX=${record.observedX ?: ""}")
        appendLine("observedY=${record.observedY ?: ""}")
        appendLine("recordedAtMs=${record.recordedAtMs}")
        appendLine("alignmentProven=false")
        appendLine("observedNote=${enc(record.observedNote)}")
        appendLine("reason=${enc(record.reason)}")
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun decode(block: String): SavedCalibration? {
            val fields = LinkedHashMap<String, String>()
            for (raw in block.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line == "---") continue
                val cut = line.indexOf('=')
                if (cut <= 0) continue
                fields[line.substring(0, cut)] = line.substring(cut + 1)
            }
            val key = fields["key"]?.let { ScreenKey.parse(it) } ?: return null
            val status = fields["status"] ?: return null
            if (status != CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE) return null
            if (fields["alignmentProven"] == "true") return null
            val observedX = fields["observedX"]?.toFloatOrNull()
            val observedY = fields["observedY"]?.toFloatOrNull()
            val record = CoordinateSelfCheck.Record(
                status = status,
                expectedX = fields["expectedX"]?.toFloatOrNull() ?: return null,
                expectedY = fields["expectedY"]?.toFloatOrNull() ?: return null,
                screenWidth = fields["screenWidth"]?.toIntOrNull() ?: return null,
                screenHeight = fields["screenHeight"]?.toIntOrNull() ?: return null,
                frameWidth = fields["frameWidth"]?.toIntOrNull() ?: 0,
                frameHeight = fields["frameHeight"]?.toIntOrNull() ?: 0,
                rotation = fields["rotation"]?.toIntOrNull() ?: return null,
                statusBarInsetPx = fields["statusBarInsetPx"]?.toIntOrNull() ?: 0,
                navigationBarInsetPx = fields["navigationBarInsetPx"]?.toIntOrNull() ?: 0,
                cutoutInsetPx = fields["cutoutInsetPx"]?.toIntOrNull() ?: 0,
                originOffsetX = fields["originOffsetX"]?.toIntOrNull() ?: 0,
                originOffsetY = fields["originOffsetY"]?.toIntOrNull() ?: 0,
                observedNote = dec(fields["observedNote"] ?: ""),
                alignmentProven = false,
                reason = dec(fields["reason"] ?: ""),
                observedX = observedX,
                observedY = observedY,
                recordedAtMs = fields["recordedAtMs"]?.toLongOrNull() ?: 0L,
            )
            return SavedCalibration(key, record)
        }

        private fun enc(raw: String): String = URLEncoder.encode(raw, Charsets.UTF_8.name())
        private fun dec(raw: String): String = URLDecoder.decode(raw, Charsets.UTF_8.name())
    }
}

/** Successful calibrations keyed by screen and app version. */
class CalibrationLibrary(
    records: List<SavedCalibration> = emptyList(),
) {
    private val records = ArrayList(records)

    fun all(): List<SavedCalibration> = records.toList()

    fun put(saved: SavedCalibration) {
        records.removeAll { it.key == saved.key }
        records.add(saved)
    }

    fun find(key: ScreenKey): SavedCalibration? = records.lastOrNull { it.key == key }

    fun encode(): String = buildString {
        appendLine("CALIB v1")
        if (records.isEmpty()) return@buildString
        records.forEach { saved ->
            appendLine("---")
            append(saved.encode())
        }
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(encode())
    }

    companion object {
        fun read(file: File): CalibrationLibrary {
            if (!file.isFile) return CalibrationLibrary()
            return decode(file.readText())
        }

        fun decode(text: String): CalibrationLibrary {
            val library = CalibrationLibrary()
            val body = text.substringAfter("CALIB v1", "")
            for (block in body.split("---")) {
                val saved = SavedCalibration.decode(block) ?: continue
                library.put(saved)
            }
            return library
        }
    }
}

/**
 * A measured hit unlocks play when it was taken in this process on the
 * current screen, or when a saved hit's screen key still matches.
 * Neither path sets alignmentProven.
 */
object CalibrationReuse {
    fun accepts(
        record: CoordinateSelfCheck.Record?,
        serviceWallMs: Long,
        saved: SavedCalibration?,
        now: ScreenKey,
    ): Boolean {
        if (record == null) return false
        if (record.status != CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE) return false
        if (record.alignmentProven) return false
        val sameScreen = record.screenWidth == now.screenWidth &&
            record.screenHeight == now.screenHeight &&
            record.rotation == now.rotation
        if (!sameScreen) return false
        val measuredThisProcess = serviceWallMs > 0L && record.recordedAtMs >= serviceWallMs
        if (measuredThisProcess) return true
        val stored = saved ?: return false
        if (stored.key != now) return false
        if (stored.record.status != CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE) return false
        if (now.densityDpi <= 0 || now.versionCode <= 0) return false
        return true
    }
}
