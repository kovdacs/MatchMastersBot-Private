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

    /** Keeps a HOLD that was already stored on disk. Does not replace it. */
    fun adoptPinnedJson(json: String) {
        if (json.isBlank()) return
        if (pinnedJson == null) pinnedJson = json
    }

    fun clear() {
        ring.clear()
        pinnedFirstHold = null
        pinnedJson = null
    }

    fun ringSnapshot(): List<Entry> = ring.toList()

    fun latest(): Entry? = ring.lastOrNull()

    companion object {
        const val DEFAULT_CAPACITY = 8
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
        val latest = history.latest()
        if (latest != null) {
            File(dir, "latest.json").writeText(latest.bundle.toJson())
        }
        File(dir, "export.txt").writeText(DiagnosticExportText.render(history))
    }

    /** Text plus every downscaled frame PNG the share sheet should attach. */
    fun shareAttachments(dir: File): List<File> {
        val out = ArrayList<File>()
        File(dir, "export.txt").takeIf { it.isFile }?.let { out.add(it) }
        File(dir, "pinned-first-hold.png").takeIf { it.isFile }?.let { out.add(it) }
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
        val latest = history.latest()
        if (latest == null) {
            appendLine("none")
        } else {
            appendLine(latest.bundle.toJson().trimEnd())
            appendLine("frameExport=${latest.frame.status}")
            appendLine(latest.frame.reason)
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

    fun install(dir: File) {
        synchronized(lock) {
            directory = dir
            dir.mkdirs()
            val pin = File(dir, "pinned-first-hold.json")
            if (pin.isFile) history.adoptPinnedJson(pin.readText())
        }
    }

    fun directory(): File? = directory

    fun record(bundle: DiagnosticBundle, frame: DiagnosticFrame.Export) {
        synchronized(lock) {
            history.record(bundle, frame)
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
