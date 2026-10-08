package com.match3vision.analyzer.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share and copy of the diagnostic export. Both read the files directory
 * the bubble already wrote. Neither path needs ADB.
 */
object DiagnosticShare {
    fun copyToClipboard(context: Context, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("hold-diagnostic", text))
        return true
    }

    /**
     * Opens the system share sheet with the export text and, when present,
     * the pinned overlay PNG. The caller must have already written [directory].
     */
    fun share(context: Context, directory: File, text: String): Boolean {
        directory.mkdirs()
        val textFile = File(directory, "export.txt")
        textFile.writeText(text)
        val png = File(directory, "pinned-first-hold.png").takeIf { it.isFile }
            ?: directory.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".png") }
        val authority = context.packageName + ".fileprovider"
        val textUri = FileProvider.getUriForFile(context, authority, textFile)
        val intent = if (png != null) {
            val pngUri = FileProvider.getUriForFile(context, authority, png)
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(textUri, pngUri),
                )
                clipData = ClipData.newRawUri("hold-diagnostic", textUri).also {
                    it.addItem(ClipData.Item(pngUri))
                }
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, textUri)
                putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newRawUri("hold-diagnostic", textUri)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(intent, "HOLD diagnosztika").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        return true
    }
}
