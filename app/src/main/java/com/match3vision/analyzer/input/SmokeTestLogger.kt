package com.match3vision.analyzer.input

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Detailed smoke-test log: in-memory (UI-readable) + optional file sink.
 * Pure-Kotlin safe for JVM unit tests; pass a [File] on-device for persistence.
 */
class SmokeTestLogger(
    private var file: File? = null,
) {
    private val lines = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun setFile(file: File?) {
        this.file = file
    }

    fun clear() {
        lines.clear()
    }

    fun log(message: String) {
        val stamped = "${timeFmt.format(Date())}  $message"
        lines += stamped
        val f = file
        if (f != null) {
            try {
                f.parentFile?.mkdirs()
                f.appendText(stamped + "\n")
            } catch (_: Throwable) {
                // File I/O must never break the smoke harness.
            }
        }
    }

    fun lines(): List<String> = lines.toList()

    fun dump(): String = lines.joinToString("\n")

    fun lineCount(): Int = lines.size
}
