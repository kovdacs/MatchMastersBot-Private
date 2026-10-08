package com.match3vision.analyzer.input

import java.io.File

/**
 * Last-N diagnostic ring plus the first HOLD of the run.
 *
 * The pinned HOLD is a separate copy. Rotating the ring does not replace it.
 * [clear] is the only way to drop it. Writing the latest bundle does not
 * delete the pin. Nothing in this type runs vision or returns a PASS.
 */
class DiagnosticHistory(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    data class Entry(
        val bundle: DiagnosticBundle,
        val frame: DiagnosticFrame.Export,
    )

    private val ring = ArrayDeque<Entry>()

    var pinnedFirstHold: Entry? = null
        private set

    /** Last eligible in-game sample. Separate from the time-spread ring. */
    private var latestLiveEntry: Entry? = null

    var bestInGame: Entry? = null
        private set

    private var bestPlausible: Boolean = false

    var fiveMoveReport: String? = null
        private set

    data class AdmitEffect(
        val enteredRing: Boolean,
        val becameBest: Boolean,
    )

    /**
     * JSON of the pinned HOLD. Set from [record] or from a file reloaded at
     * process start so a later bundle cannot replace it until [clear].
     */
    var pinnedJson: String? = null
        private set

    fun record(bundle: DiagnosticBundle, frame: DiagnosticFrame.Export): Boolean {
        val entry = Entry(bundle, frame)
        var pinnedNow = false
        if (bundle.isHoldEvidence() && pinnedJson == null) {
            pinnedFirstHold = entry
            pinnedJson = bundle.toJson()
            pinnedNow = true
        }
        ring.addLast(entry)
        while (ring.size > capacity) {
            ring.removeFirst()
        }
        return pinnedNow
    }

    /**
     * Live admission. Own-app frames and the post-collapse transition do not
     * enter the ring, the pin, the latest sample, or the best frame.
     * Ring entries are at least [SAMPLE_INTERVAL_MS] apart and stay spread
     * across the run. [record] is unchanged for older callers.
     */
    fun admitLive(
        bundle: DiagnosticBundle,
        frame: DiagnosticFrame.Export,
        ownUi: Boolean,
        pastTransition: Boolean,
        plausibleRoi: Boolean,
    ): AdmitEffect {
        if (ownUi || !pastTransition) {
            return AdmitEffect(enteredRing = false, becameBest = false)
        }
        val entry = Entry(bundle, frame)
        latestLiveEntry = entry
        var pinnedNow = false
        if (bundle.isHoldEvidence() && pinnedJson == null) {
            pinnedFirstHold = entry
            pinnedJson = bundle.toJson()
            pinnedNow = true
        }
        val entered = considerRing(entry)
        val becameBest = considerBest(entry, plausibleRoi)
        return AdmitEffect(enteredRing = entered || pinnedNow, becameBest = becameBest)
    }

    fun replaceBestFrame(frame: DiagnosticFrame.Export) {
        val current = bestInGame ?: return
        bestInGame = current.copy(frame = frame)
    }

    fun setFiveMoveReport(text: String) {
        fiveMoveReport = text
    }

    /** Keeps a HOLD that was already stored on disk. Does not replace it. */
    fun adoptPinnedJson(json: String) {
        if (json.isBlank()) return
        if (pinnedJson == null) pinnedJson = json
    }

    /** Drops the pin only. The ring stays. */
    fun clearPin() {
        pinnedFirstHold = null
        pinnedJson = null
    }

    fun clear() {
        ring.clear()
        pinnedFirstHold = null
        pinnedJson = null
        latestLiveEntry = null
        bestInGame = null
        bestPlausible = false
        fiveMoveReport = null
    }

    fun ringSnapshot(): List<Entry> = ring.toList()

    fun latest(): Entry? = ring.lastOrNull()

    /** Last eligible in-game frame when [admitLive] is in use. */
    fun latestLive(): Entry? = latestLiveEntry

    private fun considerRing(entry: Entry): Boolean {
        if (ring.isEmpty()) {
            ring.addLast(entry)
            return true
        }
        val lastTs = ring.last().bundle.captureTimestampMs
        if (entry.bundle.captureTimestampMs < lastTs + SAMPLE_INTERVAL_MS) return false
        if (ring.size < capacity) {
            ring.addLast(entry)
            return true
        }
        val stretched = resample(ring.toList() + entry, capacity)
        ring.clear()
        stretched.forEach { ring.addLast(it) }
        return true
    }

    private fun considerBest(entry: Entry, plausibleRoi: Boolean): Boolean {
        val current = bestInGame
        if (current == null) {
            bestInGame = entry
            bestPlausible = plausibleRoi
            return true
        }
        val better = when {
            plausibleRoi && !bestPlausible -> true
            !plausibleRoi && bestPlausible -> false
            else -> {
                val nextUnknown = entry.bundle.unknownCount
                val currentUnknown = current.bundle.unknownCount
                nextUnknown >= 0 && (currentUnknown < 0 || nextUnknown < currentUnknown)
            }
        }
        if (!better) return false
        bestInGame = entry
        bestPlausible = plausibleRoi
        return true
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
        const val SAMPLE_INTERVAL_MS = 20_000L
        const val TRANSITION_SKIP_MS = 2_000L

        fun resample(items: List<Entry>, n: Int): List<Entry> {
            if (items.size <= n) return items
            if (n <= 0) return emptyList()
            if (n == 1) return listOf(items.last())
            val start = items.first().bundle.captureTimestampMs
            val end = items.last().bundle.captureTimestampMs
            val used = HashSet<Int>()
            val chosen = ArrayList<Int>()
            fun take(index: Int) {
                if (used.add(index)) chosen.add(index)
            }
            take(0)
            val gaps = n - 1
            for (slot in 1 until n - 1) {
                val target = start + (end - start) * slot / gaps
                var best = -1
                var bestDist = Long.MAX_VALUE
                for (index in items.indices) {
                    if (index in used) continue
                    val dist = kotlin.math.abs(items[index].bundle.captureTimestampMs - target)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = index
                    }
                }
                if (best >= 0) take(best)
            }
            take(items.lastIndex)
            return chosen.sorted().map { items[it] }
        }
    }
}

fun DiagnosticBundle.isHoldEvidence(): Boolean =
    visionGate.equals("HOLD", ignoreCase = true) ||
        (failureClass != DiagnosticBundle.CLASS_NONE && failureClass.isNotBlank()) ||
        verificationStatus.equals(VerificationPolicy.FAILED, ignoreCase = true)

/**
 * Files under the app files directory. Share uses these paths.
 * [write] never deletes a pinned HOLD that [history] still has.
 * [clear] is explicit.
 */
object DiagnosticFiles {
    fun write(dir: File, history: DiagnosticHistory) {
        dir.mkdirs()
        val ringDir = File(dir, "ring")
        if (ringDir.exists()) ringDir.deleteRecursively()
        ringDir.mkdirs()
        history.ringSnapshot().forEachIndexed { index, entry ->
            File(ringDir, "bundle-%02d.json".format(index)).writeText(entry.bundle.toJson())
            writeFrame(ringDir, "frame-%02d".format(index), entry.frame)
        }
        val pin = history.pinnedFirstHold
        val pinJson = history.pinnedJson
        if (pinJson != null) {
            File(dir, "pinned-first-hold.json").writeText(pinJson)
        }
        if (pin != null) {
            writeFrame(dir, "pinned-first-hold", pin.frame)
        }
        val latest = history.latestLive() ?: history.latest()
        if (latest != null) {
            File(dir, "latest.json").writeText(latest.bundle.toJson())
        }
        val best = history.bestInGame
        if (best != null) {
            writeFrame(dir, "best-in-game", best.frame)
        }
        File(dir, "export.txt").writeText(DiagnosticExportText.render(history))
    }

    /** Text plus every downscaled frame PNG the share sheet should attach. */
    fun shareAttachments(dir: File): List<File> {
        val out = ArrayList<File>()
        File(dir, "export.txt").takeIf { it.isFile }?.let { out.add(it) }
        File(dir, "pinned-first-hold.png").takeIf { it.isFile }?.let { out.add(it) }
        File(dir, "best-in-game.png").takeIf { it.isFile }?.let { out.add(it) }
        val fiveMove = File(dir, "five-move")
        if (fiveMove.isDirectory) {
            fiveMove.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".png") }
                ?.sortedBy { it.name }
                ?.let { out.addAll(it) }
        }
        val ring = File(dir, "ring")
        if (ring.isDirectory) {
            ring.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".png") }
                ?.sortedBy { it.name }
                ?.let { out.addAll(it) }
        }
        return out
    }

    fun clear(dir: File) {
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun writeFrame(dir: File, name: String, frame: DiagnosticFrame.Export) {
        val png = frame.png
        if (png != null) {
            File(dir, "$name.png").writeBytes(png)
        }
        File(dir, "$name-status.txt").writeText(frame.status + "\n" + frame.reason + "\n")
    }
}

object DiagnosticExportText {
    fun render(history: DiagnosticHistory): String = buildString {
        appendLine("HOLD DIAGNOSTIC EXPORT")
        appendLine("Share/copy retrieves this without ADB.")
        appendLine("diagnosticInfluencesGate=false")
        appendLine("ringCount=${history.ringSnapshot().size}")
        appendLine("capacity=${history.capacity}")
        val ring = history.ringSnapshot()
        appendLine("--- RING ${ring.size}/${history.capacity} ---")
        appendLine(
            "Only these ring entries are checkable. This export does not prove a longer run.",
        )
        if (ring.isEmpty()) {
            appendLine("none")
        } else {
            ring.forEachIndexed { index, entry ->
                appendLine("#$index ${entry.bundle.cycleLine()}")
                appendLine("frameFile=ring/frame-%02d.png".format(index))
                appendLine("frameExport=${entry.frame.status} ${entry.frame.reason}")
            }
        }
        appendLine("--- PINNED FIRST HOLD ---")
        val pinned = history.pinnedJson
        if (pinned == null) {
            appendLine("none")
        } else {
            appendLine(pinned.trimEnd())
            val frame = history.pinnedFirstHold?.frame
            if (frame == null) {
                appendLine("frame: see pinned-first-hold-status.txt")
            } else {
                appendLine("frameExport=${frame.status}")
                appendLine(frame.reason)
            }
        }
        appendLine("--- LATEST ---")
        val latest = history.latestLive() ?: history.latest()
        if (latest == null) {
            appendLine("none")
        } else {
            appendLine(latest.bundle.toJson().trimEnd())
            appendLine("frameExport=${latest.frame.status}")
            appendLine(latest.frame.reason)
        }
        appendLine("--- BEST IN-GAME ---")
        val best = history.bestInGame
        if (best == null) {
            appendLine("none")
        } else {
            appendLine(best.bundle.bestLine())
            appendLine("frameFile=best-in-game.png")
            appendLine("frameExport=${best.frame.status} ${best.frame.reason}")
        }
        val fiveMove = history.fiveMoveReport
        if (!fiveMove.isNullOrBlank()) {
            appendLine(fiveMove.trimEnd())
        }
    }
}

/**
 * Process-wide history. The bubble and the main screen share one directory.
 */
object DiagnosticHistoryStore {
    private val lock = Any()
    private var history = DiagnosticHistory()
    private var directory: File? = null

    fun install(dir: File, versionCode: Int = -1) {
        synchronized(lock) {
            directory = dir
            dir.mkdirs()
            val pin = File(dir, "pinned-first-hold.json")
            if (!pin.isFile) return
            val text = pin.readText()
            val pinnedVersion = pinnedVersionCode(text)
            if (versionCode >= 0 && pinnedVersion != versionCode) {
                history.clearPin()
                dropPinFiles(dir)
                return
            }
            history.adoptPinnedJson(text)
        }
    }

    /** New bubble session. A pin from an older run must not stay in the export. */
    fun clearPinForNewSession() {
        synchronized(lock) {
            history.clearPin()
            directory?.let { dropPinFiles(it) }
        }
    }

    internal fun pinnedVersionCode(json: String): Int? {
        val match = Regex("\"versionCode\"\\s*:\\s*(-?\\d+)").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun dropPinFiles(dir: File) {
        File(dir, "pinned-first-hold.json").delete()
        File(dir, "pinned-first-hold.png").delete()
        File(dir, "pinned-first-hold-status.txt").delete()
    }

    fun directory(): File? = directory

    fun record(bundle: DiagnosticBundle, frame: DiagnosticFrame.Export) {
        synchronized(lock) {
            history.record(bundle, frame)
            directory?.let { DiagnosticFiles.write(it, history) }
        }
    }

    fun admitLive(
        bundle: DiagnosticBundle,
        frame: DiagnosticFrame.Export,
        ownUi: Boolean,
        pastTransition: Boolean,
        plausibleRoi: Boolean,
    ): DiagnosticHistory.AdmitEffect = synchronized(lock) {
        val effect = history.admitLive(bundle, frame, ownUi, pastTransition, plausibleRoi)
        directory?.let { DiagnosticFiles.write(it, history) }
        effect
    }

    fun replaceBestFrame(frame: DiagnosticFrame.Export) {
        synchronized(lock) {
            history.replaceBestFrame(frame)
            directory?.let { DiagnosticFiles.write(it, history) }
        }
    }

    fun setFiveMoveReport(text: String) {
        synchronized(lock) {
            history.setFiveMoveReport(text)
            directory?.let { DiagnosticFiles.write(it, history) }
        }
    }

    fun clear() {
        synchronized(lock) {
            history.clear()
            directory?.let { DiagnosticFiles.clear(it) }
        }
    }

    fun snapshot(): DiagnosticHistory = synchronized(lock) { history }

    fun exportText(): String = synchronized(lock) { DiagnosticExportText.render(history) }

    /** Test-only replacement so cases do not share process state. */
    fun replaceForTest(next: DiagnosticHistory, dir: File?) {
        synchronized(lock) {
            history = next
            directory = dir
        }
    }
}
